package com.limelight;

import android.app.Activity;
import android.graphics.Color;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.widget.LinearLayout;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.limelight.binding.PlatformBinding;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.HostHttpResponseException;
import com.limelight.nvstream.http.NvHTTP;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import okhttp3.ResponseBody;

/**
 * Watch on the TV: the PC's Watch stream (what another TV is playing, or the PC's own screen
 * while it's shared), view only. The PC gives this paired device a watch key (GET /tuna/watch),
 * and the stream comes from the PC's web page port with the same certificate as pairing. Nothing
 * done here reaches the PC: no controller, no keyboard.
 *
 * The stream is short messages: type (1 byte), flags (1), time in microseconds (8, little
 * endian), length (4, little endian), payload. I: details (JSON), V: H.264 (Annex B), A: Opus,
 * C: a cheer (JSON), E: the end (text).
 */
public class WatchActivity extends Activity implements SurfaceHolder.Callback {
    public static final String EXTRA_HOST = "host";
    public static final String EXTRA_PORT = "port";
    public static final String EXTRA_HTTPS_PORT = "httpsPort";
    public static final String EXTRA_UNIQUE_ID = "uniqueId";
    public static final String EXTRA_SERVER_CERT = "serverCert";
    public static final String EXTRA_PC_NAME = "pcName";

    private static final int HEADER = 14;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private SurfaceView surfaceView;
    private TextView status;
    private TextView cheer;
    private volatile Surface surface;
    private volatile boolean stopped;
    private Thread reader;
    private volatile ResponseBody current;
    private volatile NvHTTP http;
    private volatile String watchKey;
    private volatile int watchPort;
    private LinearLayout cheerBar;
    private long lastCheer;

    private static final String[][] CHEERS = {
            {"thumbs", "\uD83D\uDC4D"}, {"heart", "\u2764\uFE0F"}, {"laugh", "\uD83D\uDE02"}, {"fire", "\uD83D\uDD25"},
            {"clap", "\uD83D\uDC4F"}, {"wow", "\uD83D\uDE2E"}, {"gg", "GG"},
    };

    // Video
    private MediaCodec video;
    private int videoWidth;
    private int videoHeight;
    private boolean needKey = true;

    // Sound: decoded and played on a thread of its own, so a slow speaker never holds up the picture
    private final BlockingQueue<Object[]> sound = new ArrayBlockingQueue<>(100);
    private Thread soundThread;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        surfaceView = new SurfaceView(this);
        root.addView(surfaceView, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER));

        status = new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        status.setGravity(Gravity.CENTER);
        status.setPadding(64, 64, 64, 64);
        root.addView(status, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER));

        cheer = new TextView(this);
        cheer.setTextColor(Color.WHITE);
        cheer.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        cheer.setBackgroundColor(0xD9041431);
        cheer.setPadding(36, 18, 36, 18);
        cheer.setVisibility(View.GONE);
        FrameLayout.LayoutParams cheerParams = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.END | Gravity.BOTTOM);
        cheerParams.setMargins(48, 48, 48, 64);
        root.addView(cheer, cheerParams);

        // Cheers: OK on the remote shows them, OK again sends the chosen one, Back hides them
        cheerBar = new LinearLayout(this);
        cheerBar.setOrientation(LinearLayout.HORIZONTAL);
        cheerBar.setBackgroundColor(0xD9041431);
        cheerBar.setPadding(16, 12, 16, 12);
        cheerBar.setVisibility(View.GONE);
        for (String[] c : CHEERS) {
            TextView b = new TextView(this);
            b.setText(c[1]);
            b.setTextColor(Color.WHITE);
            b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30);
            b.setPadding(28, 8, 28, 8);
            b.setFocusable(true);
            b.setBackgroundResource(android.R.drawable.list_selector_background);
            final String kind = c[0];
            b.setOnClickListener(v -> sendCheer(kind));
            cheerBar.addView(b);
        }
        FrameLayout.LayoutParams barParams = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM);
        barParams.setMargins(0, 0, 0, 48);
        root.addView(cheerBar, barParams);

        setContentView(root);
        surfaceView.getHolder().addCallback(this);
        setStatus(getString(R.string.watch_connecting, getIntent().getStringExtra(EXTRA_PC_NAME)));
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        surface = holder.getSurface();
        start();
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        stop();
        surface = null;
    }

    @Override
    protected void onStop() {
        super.onStop();
        finish();  // watching stops when the TV leaves the screen
    }

    @Override
    protected void onDestroy() {
        stop();
        super.onDestroy();
    }

    private void setStatus(final String text) {
        ui.post(() -> {
            status.setText(text);
            status.setVisibility(text == null ? View.GONE : View.VISIBLE);
        });
    }

    private void showCheer(final String text) {
        ui.post(() -> {
            cheer.setText(text);
            cheer.setVisibility(View.VISIBLE);
            ui.removeCallbacks(hideCheer);
            ui.postDelayed(hideCheer, 4000);
        });
    }

    private final Runnable hideCheer = () -> cheer.setVisibility(View.GONE);

    private final Runnable hideCheerBar = () -> cheerBar.setVisibility(View.GONE);

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (cheerBar.getVisibility() != View.VISIBLE && watchKey != null && (keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                || keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_BUTTON_A
                || keyCode == KeyEvent.KEYCODE_DPAD_UP)) {
            cheerBar.setVisibility(View.VISIBLE);
            cheerBar.getChildAt(3).requestFocus();
            ui.removeCallbacks(hideCheerBar);
            ui.postDelayed(hideCheerBar, 8000);
            return true;
        }
        if (cheerBar.getVisibility() == View.VISIBLE && (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_BUTTON_B)) {
            cheerBar.setVisibility(View.GONE);
            return true;
        }
        if (cheerBar.getVisibility() == View.VISIBLE) {
            ui.removeCallbacks(hideCheerBar);
            ui.postDelayed(hideCheerBar, 8000);
        }
        return super.onKeyDown(keyCode, event);
    }

    private void sendCheer(final String kind) {
        final long now = System.currentTimeMillis();
        if (now - lastCheer < 2000 || http == null || watchKey == null) {
            return;
        }
        lastCheer = now;
        cheerBar.setVisibility(View.GONE);
        final NvHTTP h = http;
        final String key = watchKey;
        final int port = watchPort;
        new Thread(() -> {
            try {
                String why = h.sendWatchCheer(port, key, kind);
                if (why != null) {
                    showCheer(why);
                }
            } catch (Exception e) {
                showCheer(getString(R.string.watch_cheer_failed));
            }
        }).start();
    }

    private synchronized void start() {
        if (reader != null) {
            return;
        }
        stopped = false;
        soundThread = new Thread(this::playSound, "Watch sound");
        soundThread.start();
        reader = new Thread(this::watchLoop, "Watch");
        reader.start();
    }

    private synchronized void stop() {
        stopped = true;
        ResponseBody body = current;
        if (body != null) {
            try {
                body.close();
            } catch (Exception ignored) {
            }
        }
        if (reader != null) {
            reader.interrupt();
            try {
                reader.join(2000);
            } catch (InterruptedException ignored) {
            }
            reader = null;
        }
        if (soundThread != null) {
            soundThread.interrupt();
            try {
                soundThread.join(2000);
            } catch (InterruptedException ignored) {
            }
            soundThread = null;
        }
        releaseVideo();
    }

    private NvHTTP connect() throws Exception {
        byte[] der = getIntent().getByteArrayExtra(EXTRA_SERVER_CERT);
        X509Certificate cert = der == null ? null : (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(der));
        return new NvHTTP(new ComputerDetails.AddressTuple(getIntent().getStringExtra(EXTRA_HOST),
                getIntent().getIntExtra(EXTRA_PORT, NvHTTP.DEFAULT_HTTP_PORT)),
                getIntent().getIntExtra(EXTRA_HTTPS_PORT, 0), getIntent().getStringExtra(EXTRA_UNIQUE_ID),
                cert, PlatformBinding.getCryptoProvider(this));
    }

    private void watchLoop() {
        String key = null;
        int webPort = 0;
        try {
            http = connect();
        } catch (Exception e) {
            setStatus(getString(R.string.watch_failed));
            return;
        }
        while (!stopped) {
            try {
                if (key == null) {
                    JSONObject watch = new JSONObject(http.getTunaWatch());
                    key = watch.getString("key");
                    webPort = watch.getInt("port");
                    watchKey = key;
                    watchPort = webPort;
                }
                try (ResponseBody body = http.openWatchStream(webPort, key)) {
                    current = body;
                    needKey = true;
                    String why = read(body.byteStream());
                    setStatus(why != null ? why : getString(R.string.watch_ended));
                }
            } catch (FileNotFoundException e) {
                // An older TunaStream, or not TunaStream at all
                setStatus(getString(R.string.watch_not_available));
                return;
            } catch (HostHttpResponseException e) {
                if (e.getErrorCode() == 401) {
                    key = null;  // a new key next time
                    watchKey = null;
                }
                setStatus(e.getErrorCode() == 409 && e.getErrorMessage() != null ? e.getErrorMessage() : getString(R.string.watch_waiting));
            } catch (Exception e) {
                if (stopped) {
                    return;
                }
                setStatus(getString(R.string.watch_waiting));
            } finally {
                current = null;
                releaseVideo();
            }
            // Try again in a moment: someone may start playing
            for (int i = 0; i < 50 && !stopped; i++) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }
    }

    private static void readFully(InputStream in, byte[] buf, int len) throws IOException {
        int got = 0;
        while (got < len) {
            int n = in.read(buf, got, len - got);
            if (n < 0) {
                throw new EOFException();
            }
            got += n;
        }
    }

    /** Reads messages until the stream ends; returns why it ended, if the PC said. */
    private String read(InputStream in) throws Exception {
        byte[] header = new byte[HEADER];
        byte[] payload = new byte[256 * 1024];
        while (!stopped) {
            readFully(in, header, HEADER);
            ByteBuffer h = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
            char type = (char) header[0];
            boolean isKey = header[1] == 1;
            long time = h.getLong(2);
            int length = h.getInt(10);
            if (length < 0 || length > 16 * 1024 * 1024) {
                throw new IOException("bad message");
            }
            if (length > payload.length) {
                payload = new byte[length];
            }
            readFully(in, payload, length);
            switch (type) {
                case 'I': {
                    JSONObject details = new JSONObject(new String(payload, 0, length, StandardCharsets.UTF_8));
                    startVideo(details.optInt("width", 1280), details.optInt("height", 720));
                    break;
                }
                case 'V':
                    onVideo(payload, length, isKey, time);
                    break;
                case 'A': {
                    byte[] packet = new byte[length];
                    System.arraycopy(payload, 0, packet, 0, length);
                    sound.offer(new Object[]{packet, time});
                    break;
                }
                case 'C': {
                    JSONObject c = new JSONObject(new String(payload, 0, length, StandardCharsets.UTF_8));
                    showCheer(c.optString("emoji") + "  " + c.optString("name"));
                    break;
                }
                case 'E':
                    return new String(payload, 0, length, StandardCharsets.UTF_8);
                default:
                    break;
            }
        }
        return null;
    }

    private void startVideo(int width, int height) throws IOException {
        if (video != null && width == videoWidth && height == videoHeight) {
            return;
        }
        releaseVideo();
        Surface s = surface;
        if (s == null) {
            throw new IOException("no surface");
        }
        MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height);
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, width * height);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1);
        }
        video = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
        video.configure(format, s, null, 0);
        video.start();
        videoWidth = width;
        videoHeight = height;
        needKey = true;
        ui.post(this::fitSurface);
    }

    // Keep the picture's shape: letterbox inside the screen
    private void fitSurface() {
        View parent = (View) surfaceView.getParent();
        int pw = parent.getWidth();
        int ph = parent.getHeight();
        if (pw == 0 || ph == 0 || videoWidth == 0) {
            return;
        }
        float scale = Math.min(pw / (float) videoWidth, ph / (float) videoHeight);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(Math.round(videoWidth * scale), Math.round(videoHeight * scale), Gravity.CENTER);
        surfaceView.setLayoutParams(lp);
    }

    private void onVideo(byte[] data, int length, boolean isKey, long timeUs) {
        if (video == null) {
            return;
        }
        if (needKey && !isKey) {
            return;
        }
        try {
            int index = video.dequeueInputBuffer(20000);
            if (index < 0) {
                needKey = true;  // behind: start again at the next key frame
                return;
            }
            ByteBuffer in = video.getInputBuffer(index);
            if (in == null || in.capacity() < length) {
                video.queueInputBuffer(index, 0, 0, timeUs, 0);
                needKey = true;
                return;
            }
            in.clear();
            in.put(data, 0, length);
            video.queueInputBuffer(index, 0, length, timeUs, 0);
            needKey = false;
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            int out;
            while ((out = video.dequeueOutputBuffer(info, 0)) != MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (out >= 0) {
                    video.releaseOutputBuffer(out, true);
                    if (status.getVisibility() == View.VISIBLE) {
                        setStatus(null);
                    }
                }
            }
        } catch (IllegalStateException e) {
            needKey = true;
        }
    }

    private void releaseVideo() {
        MediaCodec v = video;
        video = null;
        if (v != null) {
            try {
                v.stop();
            } catch (Exception ignored) {
            }
            v.release();
        }
    }

    private static MediaFormat opusFormat() {
        MediaFormat format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, 48000, 2);
        // OpusHead: version 1, 2 channels, pre-skip 312, 48 kHz, no gain, mapping family 0
        ByteBuffer head = ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN);
        head.put("OpusHead".getBytes(StandardCharsets.US_ASCII));
        head.put((byte) 1).put((byte) 2).putShort((short) 312).putInt(48000).putShort((short) 0).put((byte) 0);
        head.flip();
        format.setByteBuffer("csd-0", head);
        ByteBuffer delay = ByteBuffer.allocate(8).order(ByteOrder.nativeOrder());
        delay.putLong(312L * 1000000000L / 48000L).flip();
        format.setByteBuffer("csd-1", delay);
        ByteBuffer preroll = ByteBuffer.allocate(8).order(ByteOrder.nativeOrder());
        preroll.putLong(80000000L).flip();
        format.setByteBuffer("csd-2", preroll);
        return format;
    }

    private void playSound() {
        MediaCodec decoder = null;
        AudioTrack track = null;
        try {
            decoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS);
            decoder.configure(opusFormat(), null, null, 0);
            decoder.start();
            int minBuffer = AudioTrack.getMinBufferSize(48000, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
            track = new AudioTrack(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build(),
                    new AudioFormat.Builder().setSampleRate(48000).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build(),
                    Math.max(minBuffer, 48000 * 4 / 5), AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE);
            track.play();
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            byte[] pcm = new byte[0];
            while (!stopped) {
                Object[] item = sound.poll(200, TimeUnit.MILLISECONDS);
                if (item != null) {
                    byte[] packet = (byte[]) item[0];
                    int index = decoder.dequeueInputBuffer(10000);
                    if (index >= 0) {
                        ByteBuffer in = decoder.getInputBuffer(index);
                        if (in != null && in.capacity() >= packet.length) {
                            in.clear();
                            in.put(packet);
                            decoder.queueInputBuffer(index, 0, packet.length, (Long) item[1], 0);
                        } else {
                            decoder.queueInputBuffer(index, 0, 0, (Long) item[1], 0);
                        }
                    }
                }
                int out;
                while ((out = decoder.dequeueOutputBuffer(info, 0)) >= 0) {
                    ByteBuffer o = decoder.getOutputBuffer(out);
                    if (o != null && info.size > 0) {
                        if (pcm.length < info.size) {
                            pcm = new byte[info.size];
                        }
                        o.position(info.offset);
                        o.get(pcm, 0, info.size);
                        // Don't build up delay: when far behind, skip this bit
                        if (sound.size() < 20) {
                            track.write(pcm, 0, info.size);
                        }
                    }
                    decoder.releaseOutputBuffer(out, false);
                }
            }
        } catch (InterruptedException ignored) {
        } catch (Exception e) {
            // No sound on this TV; the picture carries on
        } finally {
            if (track != null) {
                try {
                    track.stop();
                } catch (Exception ignored) {
                }
                track.release();
            }
            if (decoder != null) {
                try {
                    decoder.stop();
                } catch (Exception ignored) {
                }
                decoder.release();
            }
            sound.clear();
        }
    }
}
