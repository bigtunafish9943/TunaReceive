package com.limelight.nvstream.wol;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Scanner;

import com.limelight.LimeLog;
import com.limelight.nvstream.http.ComputerDetails;

public class WakeOnLanSender {
    // These ports will always be tried as-is.
    private static final int[] STATIC_PORTS_TO_TRY = new int[] {
        9, // Standard WOL port (privileged port)
        47009, // Port opened by Moonlight Internet Hosting Tool for WoL (non-privileged port)
    };

    // These ports will be offset by the base port number (47989) to support alternate ports.
    private static final int[] DYNAMIC_PORTS_TO_TRY = new int[] {
        47998, 47999, 48000, 48002, 48010, // Ports opened by GFE
    };

    private static void sendPacketsForAddress(InetAddress address, int httpPort, DatagramSocket sock, byte[] payload) throws IOException {
        IOException lastException = null;
        boolean sentWolPacket = false;

        // Try the static ports
        for (int port : STATIC_PORTS_TO_TRY) {
            try {
                DatagramPacket dp = new DatagramPacket(payload, payload.length);
                dp.setAddress(address);
                dp.setPort(port);
                sock.send(dp);
                sentWolPacket = true;
            } catch (IOException e) {
                e.printStackTrace();
                lastException = e;
            }
        }

        // Try the dynamic ports
        for (int port : DYNAMIC_PORTS_TO_TRY) {
            try {
                DatagramPacket dp = new DatagramPacket(payload, payload.length);
                dp.setAddress(address);
                dp.setPort((port - 47989) + httpPort);
                sock.send(dp);
                sentWolPacket = true;
            } catch (IOException e) {
                e.printStackTrace();
                lastException = e;
            }
        }

        if (!sentWolPacket) {
            throw lastException;
        }
    }
    
    /**
     * This device's own networks' broadcast addresses (192.168.1.255 and the like). Some routers
     * and TV boxes drop the all-networks broadcast, 255.255.255.255, but pass these.
     */
    private static List<InetAddress> localBroadcasts() {
        List<InetAddress> found = new ArrayList<>();
        try {
            for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nif.isUp() || nif.isLoopback()) {
                    continue;
                }
                for (InterfaceAddress a : nif.getInterfaceAddresses()) {
                    InetAddress broadcast = a.getBroadcast();
                    if (broadcast != null && !found.contains(broadcast)) {
                        found.add(broadcast);
                    }
                }
            }
        } catch (Exception e) {
            LimeLog.warning("Wake: couldn't list this device's networks: " + e);
        }
        return found;
    }

    public static void sendWolPacket(ComputerDetails computer) throws IOException {
        byte[] payload = createWolPayload(computer);
        IOException lastException = null;
        boolean sentWolPacket = false;

        try (final DatagramSocket sock = new DatagramSocket(0)) {
            sock.setBroadcast(true);
            List<InetAddress> broadcasts = localBroadcasts();
            // Try all resolved remote and local addresses and broadcast addresses.
            // The broadcast address is required to avoid stale ARP cache entries
            // making the sleeping machine unreachable.
            for (ComputerDetails.AddressTuple address : new ComputerDetails.AddressTuple[] {
                    computer.localAddress, computer.remoteAddress,
                    computer.manualAddress, computer.ipv6Address,
            }) {
                if (address == null) {
                    continue;
                }

                try {
                    sendPacketsForAddress(InetAddress.getByName("255.255.255.255"), address.port, sock, payload);
                    sentWolPacket = true;
                } catch (IOException e) {
                    e.printStackTrace();
                    lastException = e;
                }

                for (InetAddress broadcast : broadcasts) {
                    try {
                        sendPacketsForAddress(broadcast, address.port, sock, payload);
                        sentWolPacket = true;
                    } catch (IOException e) {
                        lastException = e;
                    }
                }

                try {
                    for (InetAddress resolvedAddress : InetAddress.getAllByName(address.address)) {
                        try {
                            sendPacketsForAddress(resolvedAddress, address.port, sock, payload);
                            sentWolPacket = true;
                        } catch (IOException e) {
                            e.printStackTrace();
                            lastException = e;
                        }
                    }
                } catch (IOException e) {
                    // We may have addresses that don't resolve on this subnet,
                    // but don't throw and exit the whole function if that happens.
                    // We'll throw it at the end if we didn't send a single packet.
                    e.printStackTrace();
                    lastException = e;
                }
            }
        }

        // Propagate the DNS resolution exception if we didn't
        // manage to get a single packet out to the host.
        if (!sentWolPacket && lastException != null) {
            throw lastException;
        }
    }
    
    private static byte[] macStringToBytes(String macAddress) {
        byte[] macBytes = new byte[6];

        try (@SuppressWarnings("resource")
             final Scanner scan = new Scanner(macAddress).useDelimiter(":")
        ) {
            for (int i = 0; i < macBytes.length && scan.hasNext(); i++) {
                try {
                    macBytes[i] = (byte) Integer.parseInt(scan.next(), 16);
                } catch (NumberFormatException e) {
                    LimeLog.warning("Malformed MAC address: " + macAddress + " (index: " + i + ")");
                    break;
                }
            }
            return macBytes;
        }
    }
    
    private static byte[] createWolPayload(ComputerDetails computer) {
        byte[] payload = new byte[102];
        byte[] macAddress = macStringToBytes(computer.macAddress);
        int i;
        
        // 6 bytes of FF
        for (i = 0; i < 6; i++) {
            payload[i] = (byte)0xFF;
        }
        
        // 16 repetitions of the MAC address
        for (int j = 0; j < 16; j++) {
            System.arraycopy(macAddress, 0, payload, i, macAddress.length);
            i += macAddress.length;
        }
        
        return payload;
    }
}
