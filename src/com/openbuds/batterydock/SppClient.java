package com.openbuds.batterydock;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.os.Handler;
import android.os.Looper;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.UUID;

/**
 * Classic Bluetooth SPP transport for supported earbuds.
 *
 * The phone's SDP record advertises a custom 128-bit service UUID on RFCOMM
 * channel 6, not the standard 0x1101 SPP UUID.  Connecting with that UUID is
 * the path the official app uses; connecting the bare channel can succeed but
 * stay silent.
 */
public final class SppClient {
    private static final UUID ROSE_SPP_UUID =
            UUID.fromString("0cf12d31-fac3-4553-bd80-d6832e7b3931");

    interface Callback {
        void onConnected();

        void onData(byte[] data);

        void onDisconnected(String detail);
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Callback callback;
    private volatile boolean running;
    private volatile BluetoothSocket socket;
    private Thread thread;

    SppClient(Callback callback) {
        this.callback = callback;
    }

    void connect(final BluetoothDevice device) {
        close();
        running = true;
        thread = new Thread(new Runnable() {
            @Override
            public void run() {
                BluetoothSocket s = null;
                try {
                    s = connectAny(device);
                    if (s == null || !running) {
                        closeQuietly(s);
                        return;
                    }
                    DiagLog.add("spp connected");
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            callback.onConnected();
                        }
                    });
                    readLoop(s);
                } catch (Exception e) {
                    DiagLog.add("spp connect failed " + e.getClass().getSimpleName()
                            + " " + e.getMessage());
                    closeQuietly(s);
                    if (running) {
                        running = false;
                        main.post(new Runnable() {
                            @Override
                            public void run() {
                                callback.onDisconnected("经典串口连接失败");
                            }
                        });
                    }
                }
            }
        }, "rose-spp");
        thread.start();
    }

    private void readLoop(BluetoothSocket s) {
        byte[] buffer = new byte[512];
        try {
            InputStream in = s.getInputStream();
            while (running) {
                int n = in.read(buffer);
                if (n < 0) break;
                if (n == 0) continue;
                final byte[] copy = new byte[n];
                System.arraycopy(buffer, 0, copy, 0, n);
                DiagLog.add("spp data " + toHex(copy));
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        callback.onData(copy);
                    }
                });
            }
        } catch (IOException e) {
            DiagLog.add("spp read ended " + e.getClass().getSimpleName());
        } finally {
            if (running) {
                running = false;
                closeQuietly(s);
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        callback.onDisconnected("经典串口已断开");
                    }
                });
            }
        }
    }

    private BluetoothSocket connectAny(BluetoothDevice device) throws IOException {
        IOException last = null;

        // Interoperability path: SDP lookup of the custom service UUID,
        // which resolves to RFCOMM channel 6 on tested devices.
        try {
            BluetoothSocket s = device.createRfcommSocketToServiceRecord(ROSE_SPP_UUID);
            socket = s;
            DiagLog.add("spp try custom uuid");
            s.connect();
            return s;
        } catch (Exception e) {
            last = asIo(e);
            DiagLog.add("spp custom uuid failed " + e.getClass().getSimpleName());
            closeQuietly(socket);
            socket = null;
        }
        try {
            BluetoothSocket s = device.createInsecureRfcommSocketToServiceRecord(ROSE_SPP_UUID);
            socket = s;
            DiagLog.add("spp try custom uuid insecure");
            s.connect();
            return s;
        } catch (Exception e) {
            last = asIo(e);
            DiagLog.add("spp custom uuid insecure failed " + e.getClass().getSimpleName());
            closeQuietly(socket);
            socket = null;
        }

        // Fallback: direct RFCOMM channel 6. Do not fall back to channel 5/1;
        // those sockets accept the connection but stay silent.
        BluetoothSocket secure = tryChannel(device, 6, true);
        if (secure != null) return secure;
        BluetoothSocket insecure = tryChannel(device, 6, false);
        if (insecure != null) return insecure;
        throw last == null ? new IOException("RFCOMM channel 6 unavailable") : last;
    }

    private BluetoothSocket tryChannel(BluetoothDevice device, int channel, boolean secure) {
        try {
            String methodName = secure ? "createRfcommSocket" : "createInsecureRfcommSocket";
            Method m = device.getClass().getMethod(methodName, int.class);
            m.setAccessible(true);
            BluetoothSocket s = (BluetoothSocket) m.invoke(device, channel);
            socket = s;
            DiagLog.add("spp try channel " + channel + (secure ? "" : " insecure"));
            s.connect();
            return s;
        } catch (Exception e) {
            DiagLog.add("spp channel " + channel + (secure ? "" : " insecure")
                    + " failed " + e.getClass().getSimpleName());
            closeQuietly(socket);
            socket = null;
            return null;
        }
    }

    void close() {
        running = false;
        BluetoothSocket s = socket;
        socket = null;
        closeQuietly(s);
        if (thread != null) {
            thread.interrupt();
            thread = null;
        }
    }

    void send(byte[] data) {
        BluetoothSocket s = socket;
        if (s == null || data == null) return;
        try {
            s.getOutputStream().write(data);
            s.getOutputStream().flush();
            DiagLog.add("spp send " + toHex(data));
        } catch (IOException e) {
            DiagLog.add("spp send failed " + e.getClass().getSimpleName());
        }
    }

    private static void closeQuietly(BluetoothSocket s) {
        if (s == null) return;
        try {
            s.close();
        } catch (IOException ignored) {
        }
    }

    private static IOException asIo(Exception e) {
        if (e instanceof IOException) return (IOException) e;
        return new IOException(e);
    }

    private static String toHex(byte[] value) {
        StringBuilder sb = new StringBuilder(value.length * 3);
        for (int i = 0; i < value.length; i++) {
            if (i > 0) sb.append(' ');
            int v = value[i] & 0xFF;
            if (v < 16) sb.append('0');
            sb.append(Integer.toHexString(v).toUpperCase());
        }
        return sb.toString();
    }
}
