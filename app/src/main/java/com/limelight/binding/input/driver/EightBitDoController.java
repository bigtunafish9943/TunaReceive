package com.limelight.binding.input.driver;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.os.SystemClock;

import com.limelight.LimeLog;
import com.limelight.nvstream.input.ControllerPacket;
import com.limelight.nvstream.jni.MoonBridge;

/**
 * 8BitDo Ultimate 2 Wireless through its 2.4G adapter, as it appears to Android (2dc8:6012, a
 * HID gamepad). Android reads its buttons but has no way to rumble it, so during a stream
 * TunaReceive takes the adapter over and reads and rumbles it itself.
 *
 * Report layout and rumble command follow SDL's HIDAPI 8BitDo driver (SDL_hidapi_8bitdo.c):
 * input report 0x03 or 0x04 with the hat at byte 1, sticks at 2-5 (centre 0x7f), right then
 * left trigger at 6-7, buttons at 8-10; rumble is output report 0x05 {low, high, 0, 0}.
 * Rumble needs controller firmware 1.03 or later, which sends 34-byte reports (1.02 sent 12).
 */
public class EightBitDoController extends AbstractController {
    private static final int VENDOR_8BITDO = 0x2dc8;
    private static final int PRODUCT_ULTIMATE2_WIRELESS = 0x6012;
    private static final int FIRMWARE_WITH_RUMBLE_REPORT_SIZE = 34;

    /** Size of the last input report seen, for Check my controller (0 = not seen yet). */
    public static volatile int lastReportSize;

    private final UsbDevice device;
    private final UsbDeviceConnection connection;
    private UsbInterface iface;
    private UsbEndpoint inEndpt, outEndpt;
    private Thread inputThread;
    private volatile boolean stopped;
    private volatile boolean rumbleSupported;

    // Rumble goes out from its own thread: the latest strength always wins, and a rumble that
    // is still on is sent again regularly, so a missed report or a controller that lets a long
    // rumble lapse doesn't leave it silent
    private static final long RUMBLE_REFRESH_MS = 200;
    private final Object rumbleLock = new Object();
    private short wantLow, wantHigh;
    private boolean rumbleChanged;
    private Thread rumbleThread;

    public static boolean canClaimDevice(UsbDevice device) {
        return device.getVendorId() == VENDOR_8BITDO &&
                device.getProductId() == PRODUCT_ULTIMATE2_WIRELESS &&
                device.getInterfaceCount() >= 1 &&
                device.getInterface(0).getInterfaceClass() == UsbConstants.USB_CLASS_HID;
    }

    public EightBitDoController(UsbDevice device, UsbDeviceConnection connection, int deviceId, UsbDriverListener listener) {
        super(deviceId, listener, device.getVendorId(), device.getProductId());
        this.device = device;
        this.connection = connection;
        this.type = MoonBridge.LI_CTYPE_XBOX;
        this.capabilities = MoonBridge.LI_CCAP_ANALOG_TRIGGERS | MoonBridge.LI_CCAP_RUMBLE;
        this.supportedButtonFlags =
                ControllerPacket.A_FLAG | ControllerPacket.B_FLAG | ControllerPacket.X_FLAG | ControllerPacket.Y_FLAG |
                ControllerPacket.UP_FLAG | ControllerPacket.DOWN_FLAG | ControllerPacket.LEFT_FLAG | ControllerPacket.RIGHT_FLAG |
                ControllerPacket.LB_FLAG | ControllerPacket.RB_FLAG |
                ControllerPacket.LS_CLK_FLAG | ControllerPacket.RS_CLK_FLAG |
                ControllerPacket.BACK_FLAG | ControllerPacket.PLAY_FLAG | ControllerPacket.SPECIAL_BUTTON_FLAG |
                ControllerPacket.PADDLE1_FLAG | ControllerPacket.PADDLE2_FLAG |
                ControllerPacket.PADDLE3_FLAG | ControllerPacket.PADDLE4_FLAG | ControllerPacket.MISC_FLAG;
    }

    @Override
    public boolean start() {
        iface = device.getInterface(0);
        // Takes the gamepad away from Android's own HID driver, which can't rumble it
        if (!connection.claimInterface(iface, true)) {
            LimeLog.warning("8BitDo: couldn't take over the adapter");
            return false;
        }
        for (int i = 0; i < iface.getEndpointCount(); i++) {
            UsbEndpoint endpt = iface.getEndpoint(i);
            if (endpt.getType() != UsbConstants.USB_ENDPOINT_XFER_INT) {
                continue;
            }
            if (endpt.getDirection() == UsbConstants.USB_DIR_IN && inEndpt == null) {
                inEndpt = endpt;
            } else if (endpt.getDirection() == UsbConstants.USB_DIR_OUT && outEndpt == null) {
                outEndpt = endpt;
            }
        }
        if (inEndpt == null) {
            LimeLog.warning("8BitDo: no input endpoint");
            connection.releaseInterface(iface);
            return false;
        }

        inputThread = new Thread(this::readLoop, "8BitDo input");
        inputThread.start();
        rumbleThread = new Thread(this::rumbleLoop, "8BitDo rumble");
        rumbleThread.start();
        return true;
    }

    private void readLoop() {
        try {
            // Let Android's own copy of the controller go away first, so this one gets its number
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            return;
        }
        notifyDeviceAdded();

        byte[] buffer = new byte[64];
        while (!stopped && !Thread.currentThread().isInterrupted()) {
            long started = SystemClock.uptimeMillis();
            int res = connection.bulkTransfer(inEndpt, buffer, buffer.length, 3000);
            if (res <= 0) {
                if (SystemClock.uptimeMillis() - started < 1000) {
                    // Failed straight away rather than timing out: the adapter has gone
                    LimeLog.warning("8BitDo: adapter stopped responding");
                    stop();
                    break;
                }
                continue;
            }
            if (lastReportSize != res) {
                lastReportSize = res;
                rumbleSupported = res >= FIRMWARE_WITH_RUMBLE_REPORT_SIZE;
                LimeLog.info("8BitDo: " + res + "-byte reports, rumble " +
                        (rumbleSupported ? "available" : "needs controller firmware 1.03 or later"));
            }
            if (parse(buffer, res)) {
                reportInput();
            }
        }
    }

    private static float stick(int raw) {
        float v = (raw - 127) / 127f;
        return Math.max(-1f, Math.min(1f, v));
    }

    private boolean parse(byte[] r, int size) {
        if (size < 10) {
            return false;
        }
        int id = r[0] & 0xff;
        if (id != 0x03 && id != 0x04 && id != 0x01) {
            return false;
        }

        // Hat: 0 is up, then clockwise in eighths; anything else is centred
        int hat = r[1] & 0xff;
        boolean up = hat == 0 || hat == 1 || hat == 7;
        boolean right = hat == 1 || hat == 2 || hat == 3;
        boolean down = hat == 3 || hat == 4 || hat == 5;
        boolean left = hat == 5 || hat == 6 || hat == 7;
        setButtonFlag(ControllerPacket.UP_FLAG, up ? 1 : 0);
        setButtonFlag(ControllerPacket.RIGHT_FLAG, right ? 1 : 0);
        setButtonFlag(ControllerPacket.DOWN_FLAG, down ? 1 : 0);
        setButtonFlag(ControllerPacket.LEFT_FLAG, left ? 1 : 0);

        // Sticks: centre 0x7f; positive Y is down, as the other drivers report it
        leftStickX = stick(r[2] & 0xff);
        leftStickY = stick(r[3] & 0xff);
        rightStickX = stick(r[4] & 0xff);
        rightStickY = stick(r[5] & 0xff);

        rightTrigger = (r[6] & 0xff) / 255f;
        leftTrigger = (r[7] & 0xff) / 255f;

        int b = r[8] & 0xff;
        setButtonFlag(ControllerPacket.A_FLAG, b & 0x01);
        setButtonFlag(ControllerPacket.B_FLAG, b & 0x02);
        setButtonFlag(ControllerPacket.X_FLAG, b & 0x08);
        setButtonFlag(ControllerPacket.Y_FLAG, b & 0x10);
        setButtonFlag(ControllerPacket.LB_FLAG, b & 0x40);
        setButtonFlag(ControllerPacket.RB_FLAG, b & 0x80);
        setButtonFlag(ControllerPacket.PADDLE1_FLAG, b & 0x20);
        setButtonFlag(ControllerPacket.PADDLE2_FLAG, b & 0x04);

        b = r[9] & 0xff;
        setButtonFlag(ControllerPacket.SPECIAL_BUTTON_FLAG, b & 0x10);
        setButtonFlag(ControllerPacket.BACK_FLAG, b & 0x04);
        setButtonFlag(ControllerPacket.PLAY_FLAG, b & 0x08);
        setButtonFlag(ControllerPacket.LS_CLK_FLAG, b & 0x20);
        setButtonFlag(ControllerPacket.RS_CLK_FLAG, b & 0x40);
        setButtonFlag(ControllerPacket.MISC_FLAG, b & 0x80);

        if (size > 10) {
            b = r[10] & 0xff;
            setButtonFlag(ControllerPacket.PADDLE3_FLAG, b & 0x01);
            setButtonFlag(ControllerPacket.PADDLE4_FLAG, b & 0x02);
        }
        return true;
    }

    @Override
    public void rumble(short lowFreqMotor, short highFreqMotor) {
        synchronized (rumbleLock) {
            wantLow = lowFreqMotor;
            wantHigh = highFreqMotor;
            rumbleChanged = true;
            rumbleLock.notifyAll();
        }
    }

    private void rumbleLoop() {
        long lastSent = 0;
        while (!stopped) {
            short low, high;
            boolean changed;
            synchronized (rumbleLock) {
                if (!rumbleChanged) {
                    try {
                        rumbleLock.wait(RUMBLE_REFRESH_MS);
                    } catch (InterruptedException e) {
                        break;
                    }
                }
                low = wantLow;
                high = wantHigh;
                changed = rumbleChanged;
                rumbleChanged = false;
            }
            long now = SystemClock.uptimeMillis();
            boolean on = low != 0 || high != 0;
            if (changed || (on && now - lastSent >= RUMBLE_REFRESH_MS)) {
                sendRumble(low, high);
                lastSent = now;
            }
        }
    }

    private void sendRumble(short lowFreqMotor, short highFreqMotor) {
        if (!rumbleSupported) {
            return;
        }
        byte[] report = {
                0x05,
                (byte) ((lowFreqMotor >> 8) & 0xff),
                (byte) ((highFreqMotor >> 8) & 0xff),
                0x00,
                0x00,
        };
        int res;
        if (outEndpt != null) {
            res = connection.bulkTransfer(outEndpt, report, report.length, 250);
        } else {
            // HID SET_REPORT (output report 5) on the control endpoint
            res = connection.controlTransfer(0x21, 0x09, (0x02 << 8) | 0x05, iface.getId(), report, report.length, 250);
        }
        if (res != report.length) {
            LimeLog.warning("8BitDo: rumble not sent (" + res + ")");
        }
    }

    @Override
    public void rumbleTriggers(short leftTrigger, short rightTrigger) {
        // This controller has no trigger motors
    }

    @Override
    public void stop() {
        if (stopped) {
            return;
        }
        stopped = true;
        if (rumbleThread != null) {
            rumbleThread.interrupt();
            rumbleThread = null;
        }
        // Stop any rumble before letting go
        sendRumble((short) 0, (short) 0);
        if (inputThread != null) {
            inputThread.interrupt();
            inputThread = null;
        }
        if (iface != null) {
            connection.releaseInterface(iface);
        }
        connection.close();
        notifyDeviceRemoved();
    }
}
