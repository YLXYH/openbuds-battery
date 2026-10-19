package com.openbuds.batterydock;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Small, event driven BLE client.
 *
 * Validated battery path (OpenFeel):
 *   180F/2A19  -> total battery
 *   00FE/F1    -> write split-battery query
 *   00FE/F2    -> notify DD ?? 04 0C LL RR CC AA
 *
 * It deliberately only writes the two known read-only query frames. Unknown vendor
 * characteristics are never written to.
 */
@SuppressLint("MissingPermission")
public final class BleBatteryClient {
    private static final String TAG = "RoseBattery";

    public interface Callback {
        void onScanning(boolean scanning);

        void onConnecting(BluetoothDevice device);

        void onConnected(BluetoothDevice device, int rssi);

        void onDisconnected(String detail);

        /** Intentional screen-off power saving; do not auto reconnect. */
        void onIdleDisconnected();

        /** -1 means "this value was not reported in this update". */
        void onBattery(int left, int right, int caseLevel, int total, String protocol);

        /**
         * Charging flags. Bits 0/1/2 = left/right/case, bit 3 = total.
         * knownMask says which bits are valid in this update.
         */
        void onCharging(int chargingMask, int knownMask);

        void onStatus(String title, String detail);

        void onError(String detail);

        void onBluetoothOff();

        void onPermissionMissing();
    }

    private static final UUID SERVICE_BATTERY =
            UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb");
    private static final UUID CHAR_BATTERY_LEVEL =
            UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb");
    private static final UUID SERVICE_PRIVATE =
            UUID.fromString("000000fe-0000-1000-8000-00805f9b34fb");
    private static final UUID CHAR_PRIVATE_WRITE =
            UUID.fromString("000000f1-0000-1000-8000-00805f9b34fb");
    private static final UUID CHAR_PRIVATE_NOTIFY =
            UUID.fromString("000000f2-0000-1000-8000-00805f9b34fb");
    private static final UUID CCCD =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private static final byte[] QUERY_ONE =
            hex("FF000FFA010708090C0D0E122A2B2C2D2E33AA");
    private static final byte[] QUERY_TWO =
            hex("FF0002FA2BAA");

    private static final long SCAN_TIMEOUT_MS = 12_000L;
    private static final long CONNECT_TIMEOUT_MS = 14_000L;
    private static final long QUERY_RETRY_MS = 2_500L;
    private static final int MANUFACTURER_ROSE = 0x0A0B;
    private static final byte[] SPP_GET_ALL = new byte[]{
            (byte) 0xFF, 0x02, 0x1E, (byte) 0xFA,
            0x01, 0x07, 0x08, 0x09, 0x0C, 0x0D, 0x0E, 0x12,
            0x2A, 0x2B, 0x2C, 0x2D, 0x2E, 0x2F,
            0x31, 0x32, 0x33, 0x36, 0x37, 0x38, 0x39,
            0x3A, 0x3B, 0x3C, 0x3D, 0x3F, 0x45, 0x46, 0x49,
            (byte) 0xEB, (byte) 0xAA
    };
    private static final byte[] SPP_GET_BATTERY = new byte[]{
            (byte) 0xFF, 0x00, 0x02, (byte) 0xFA, 0x0C, 0x07, (byte) 0xAA
    };
    private static final byte[] SPP_INIT_0 = new byte[]{
            (byte) 0xFF, 0x00, 0x11, (byte) 0xFA,
            0x0D, 0x0C, 0x09, 0x2C, 0x2D, 0x2A, 0x0E, 0x32,
            0x2B, 0x2F, 0x01, 0x07, 0x31, 0x08, 0x2E, 0x36,
            (byte) 0xEE, (byte) 0xAA
    };
    private static final byte[] SPP_INIT_1 = new byte[]{
            (byte) 0xFF, 0x01, 0x11, (byte) 0xFA,
            0x0D, 0x0C, 0x09, 0x2C, 0x2D, 0x2A, 0x0E, 0x32,
            0x2B, 0x2F, 0x01, 0x07, 0x31, 0x08, 0x2E, 0x36,
            (byte) 0xEF, (byte) 0xAA
    };

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Callback callback;
    private final BluetoothManager manager;

    private BluetoothAdapter adapter;
    private BluetoothLeScanner scanner;
    private ScanCallback scanCallback;
    private BluetoothGatt gatt;

    private boolean running;
    private boolean scanning;
    private boolean connecting;
    private boolean splitNotifyReady;
    private boolean localNotifySet;
    private boolean pendingSplitAfterRead;
    private boolean firstBatteryReadDone;
    private boolean everConnected;
    private boolean intentionallyClosing;

    private BluetoothDevice target;
    private String targetName = "";
    private int targetRssi;
    private int reconnectAttempt;
    private int scanFailures;

    private BluetoothGattCharacteristic totalCharacteristic;
    private BluetoothGattCharacteristic splitWriteCharacteristic;
    private BluetoothGattCharacteristic splitNotifyCharacteristic;

    private String protocol = "等待连接";
    private long lastQueryAt;
    private long lastFrameAt;
    private ScanResult bestResult;
    private int bestScore;
    private boolean bondedTried;
    private int notifyRetries;
    private SppClient spp;
    private boolean sppConnected;
    private boolean sppConnecting;
    private boolean sppMode;
    private boolean sppBatterySeen;
    private boolean sppQueryWasFull;
    private boolean passiveBle;
    private boolean idleDisconnected;
    private final ByteArrayOutputStream sppBuffer = new ByteArrayOutputStream();
    private final ByteArrayOutputStream bleBuffer = new ByteArrayOutputStream();

    private final Runnable scanTimeout = new Runnable() {
        @Override
        public void run() {
            finishScanAndConnect();
        }
    };

    private final Runnable connectTimeout = new Runnable() {
        @Override
        public void run() {
            if (!connecting) return;
            connecting = false;
            closeGatt();
            callback.onError("连接超时，正在重新查找");
            callback.onStatus("连接超时", "请确认耳机已开盖并靠近手机");
            main.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (running) scan();
                }
            }, 1_500L);
        }
    };

    private final Runnable periodicQuery = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            if (sppConnected) return;
            if (!isScreenInteractive()) {
                return;
            }
            if (gatt == null) {
                connectOrScan();
                schedulePeriodic();
                return;
            }
            queryNow(false);
        }
    };

    private final Runnable splitRetry = new Runnable() {
        @Override
        public void run() {
            if (gatt != null && splitWriteCharacteristic != null
                    && System.currentTimeMillis() - lastFrameAt > 3_500L) {
                writeSplitQuery();
            }
        }
    };

    public BleBatteryClient(Context context, Callback callback) {
        this.context = context.getApplicationContext();
        this.callback = callback;
        this.manager = (BluetoothManager) this.context.getSystemService(Context.BLUETOOTH_SERVICE);
        if (manager != null) adapter = manager.getAdapter();
    }

    public void start() {
        if (running) {
            connectOrScan();
            return;
        }
        running = true;
        reconnectAttempt = 0;
        idleDisconnected = false;
        DiagLog.add("start()");
        if (adapter == null) {
            callback.onError("此手机不支持蓝牙");
            return;
        }
        try {
            if (!hasConnectPermission()) {
                callback.onPermissionMissing();
                return;
            }
            if (!adapter.isEnabled()) {
                callback.onBluetoothOff();
                return;
            }
        } catch (SecurityException e) {
            callback.onPermissionMissing();
            return;
        }
        connectOrScan();
    }

    public void stop() {
        running = false;
        idleDisconnected = false;
        main.removeCallbacksAndMessages(null);
        stopScan();
        if (spp != null) spp.close();
        sppConnected = false;
        sppConnecting = false;
        intentionallyClosing = true;
        closeGatt();
        intentionallyClosing = false;
        connecting = false;
        scanning = false;
        callback.onScanning(false);
    }

    public void refresh() {
        if (!running) {
            start();
            return;
        }
        if (idleDisconnected) {
            resumeFromIdle();
            return;
        }
        scanFailures = 0;
        bondedTried = false;
        if (sppConnected) {
            callback.onStatus("经典串口已连接", "等待耳机上报电量");
            return;
        }
        if (gatt != null) {
            queryNow(true);
        } else {
            forceReconnect();
        }
    }

    /**
     * Clears every stale scan/connect flag and retries immediately. Needed when
     * the first attempt was made with the case closed and the backoff/scan flag
     * got stuck; a normal refresh used to return without doing anything.
     */
    public void forceReconnect() {
        reconnectAttempt = 0;
        scanFailures = 0;
        bondedTried = false;
        idleDisconnected = false;
        main.removeCallbacksAndMessages(null);
        stopScan();
        if (spp != null) {
            try {
                spp.close();
            } catch (RuntimeException ignored) {
            }
            spp = null;
        }
        sppConnected = false;
        sppConnecting = false;
        intentionallyClosing = true;
        closeGatt();
        intentionallyClosing = false;
        resetCharacteristics();
        scanning = false;
        connecting = false;
        callback.onScanning(false);
        if (!running) running = true;
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (running) connectOrScan();
            }
        }, 450L);
    }

    /** Stop timer wakeups while the screen is off. */
    public void pausePolling() {
        main.removeCallbacks(periodicQuery);
    }

    public boolean isIdle() {
        return idleDisconnected;
    }

    /** Intentional disconnect for screen-off power saving. */
    public void disconnectForIdle() {
        if (!running) return;
        idleDisconnected = true;
        main.removeCallbacks(periodicQuery);
        stopScan();
        if (spp != null) spp.close();
        sppConnected = false;
        sppConnecting = false;
        connecting = false;
        closeGatt();
        resetCharacteristics();
        callback.onIdleDisconnected();
    }

    public void resumeFromIdle() {
        if (!idleDisconnected) {
            refresh();
            return;
        }
        idleDisconnected = false;
        reconnectAttempt = 0;
        scanFailures = 0;
        bondedTried = false;
        connectOrScan();
    }

    private boolean isScreenInteractive() {
        PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        return pm == null || pm.isInteractive();
    }

    private static boolean isFurinaName(String name) {
        if (name == null) return false;
        String lower = name.toLowerCase(Locale.US);
        return lower.contains("furina") || name.contains("芙宁娜") || name.contains("不休独舞");
    }

    private long queryIntervalMs() {
        return Prefs.queryIntervalSeconds(context) * 1000L;
    }

    private void schedulePeriodic() {
        if (!running || sppConnected) return;
        main.removeCallbacks(periodicQuery);
        main.postDelayed(periodicQuery, queryIntervalMs());
    }

    public void reconnect() {
        reconnectAttempt = 0;
        scanFailures = 0;
        bondedTried = false;
        idleDisconnected = false;
        if (spp != null) spp.close();
        sppConnected = false;
        sppConnecting = false;
        closeGatt();
        connecting = false;
        if (running) {
            main.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (running) scan();
                }
            }, 400L);
        }
    }

    public void forgetAndScan() {
        reconnectAttempt = 0;
        scanFailures = 0;
        bondedTried = false;
        idleDisconnected = false;
        if (spp != null) spp.close();
        sppConnected = false;
        sppConnecting = false;
        closeGatt();
        connecting = false;
        if (!running) running = true;
        scan();
    }

    /** Direct GATT connection by MAC, used by the "paired devices" picker. */
    public void connectToAddress(String mac, String name) {
        if (mac == null || mac.trim().isEmpty()) {
            callback.onError("MAC 地址为空");
            return;
        }
        if (!running) running = true;
        if (!hasConnectPermission()) {
            callback.onPermissionMissing();
            return;
        }
        if (adapter == null) {
            callback.onError("此手机不支持蓝牙");
            return;
        }
        try {
            if (!adapter.isEnabled()) {
                callback.onBluetoothOff();
                return;
            }
        } catch (SecurityException e) {
            callback.onPermissionMissing();
            return;
        }
        DiagLog.add("manual connect " + mac + " name=" + name);
        try {
            BluetoothDevice device = adapter.getRemoteDevice(mac.trim().toUpperCase(Locale.US));
            bondedTried = true;
            connectTo(device, DeviceCatalog.displayName(name), 0);
        } catch (IllegalArgumentException e) {
            callback.onError("MAC 地址格式不正确");
        } catch (SecurityException e) {
            callback.onPermissionMissing();
        }
    }

    public boolean isConnected() {
        return gatt != null || sppConnected;
    }

    private void connectOrScan() {
        if (!running || gatt != null || scanning || connecting || sppConnected
                || idleDisconnected) return;
        if (!hasConnectPermission()) {
            callback.onPermissionMissing();
            return;
        }
        try {
            if (adapter == null || !adapter.isEnabled()) {
                callback.onBluetoothOff();
                return;
            }
        } catch (SecurityException e) {
            callback.onPermissionMissing();
            return;
        }
        String saved = AppState.getSavedMac(context);        if (saved != null && !saved.isEmpty()) {
            try {
                BluetoothDevice device = adapter.getRemoteDevice(saved);
                if (device != null) {
                    connectTo(device, DeviceCatalog.displayName(safeName(device)), 0);
                    return;
                }
            } catch (IllegalArgumentException ignored) {
                AppState.clearSavedMac(context);
            }
        }
        // If the earbuds are already paired (and therefore may not advertise
        // while connected to the phone's audio profile), try the bonded list
        // once before falling back to a scan.
        if (!bondedTried) {
            bondedTried = true;
            try {
                for (BluetoothDevice device : adapter.getBondedDevices()) {
                    String name = safeName(device);
                    DiagLog.add("bonded " + name + " " + device.getAddress()
                            + " type=" + device.getType());
                    if (DeviceCatalog.looksLikeRose(name)) {
                        connectTo(device, DeviceCatalog.displayName(name), 0);
                        return;
                    }
                }
            } catch (SecurityException ignored) {
            }
        }
        scan();
    }

    private void scan() {
        if (!running || gatt != null || scanning || connecting || idleDisconnected) return;
        if (!hasScanPermission()) {
            callback.onPermissionMissing();
            return;
        }
        try {
            if (adapter == null || !adapter.isEnabled()) {
                callback.onBluetoothOff();
                return;
            }
        } catch (SecurityException e) {
            callback.onPermissionMissing();
            return;
        }
        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) {
            callback.onError("无法启动蓝牙扫描");
            return;
        }
        bestResult = null;
        bestScore = 0;
        scanning = true;
        DiagLog.add("scan start saved=" + AppState.getSavedMac(context));
        callback.onScanning(true);
        callback.onStatus("正在查找耳机", "请打开仓盖，靠近手机");

        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(scanFailures > 1
                        ? ScanSettings.SCAN_MODE_LOW_POWER
                        : ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setReportDelay(0)
                .build();

        scanCallback = new ScanCallback() {
            @Override
            public void onScanResult(int callbackType, ScanResult result) {
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        consider(result);
                    }
                });
            }

            @Override
            public void onBatchScanResults(List<ScanResult> results) {
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (results == null) return;
                        for (ScanResult result : results) consider(result);
                    }
                });
            }

            @Override
            public void onScanFailed(int errorCode) {
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        scanning = false;
                        callback.onScanning(false);
                        callback.onError("扫描失败（" + errorCode + "）");
                        main.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                if (running && gatt == null) scan();
                            }
                        }, 8_000L);
                    }
                });
            }
        };

        try {
            scanner.startScan(Collections.<ScanFilter>emptyList(), settings, scanCallback);
            main.postDelayed(scanTimeout, SCAN_TIMEOUT_MS);
        } catch (SecurityException e) {
            scanning = false;
            callback.onScanning(false);
            callback.onPermissionMissing();
        } catch (RuntimeException e) {
            scanning = false;
            callback.onScanning(false);
            callback.onError("无法开始扫描，请关闭再打开蓝牙");
        }
    }

    private void consider(ScanResult result) {
        if (!scanning || result == null || result.getDevice() == null) return;
        String mac = result.getDevice().getAddress();
        String name = scanName(result);
        boolean saved = mac != null && mac.equalsIgnoreCase(AppState.getSavedMac(context));
        boolean roseName = DeviceCatalog.looksLikeRose(name);
        boolean roseManufacturer = hasManufacturer(result, MANUFACTURER_ROSE);
        boolean rosePrefix = mac != null && mac.toUpperCase(Locale.US).startsWith("41:42");
        boolean roseService = hasService(result, 0x00FE);

        int score = 0;
        if (saved) score += 1000;
        if (roseName) score += 260;
        if (roseService) score += 220;
        if (roseManufacturer && rosePrefix) score += 220;
        else if (roseManufacturer) score += 60;
        if (rosePrefix) score += 35;
        score += Math.max(-90, Math.min(0, result.getRssi()));
        if (!saved && !roseName && !roseService && !(roseManufacturer && rosePrefix)) return;

        if (score > bestScore) {
            bestScore = score;
            bestResult = result;
            targetName = name;
            targetRssi = result.getRssi();
            DiagLog.add("scan hit " + mac + " name=" + name + " rssi=" + result.getRssi()
                    + " score=" + score);
            if (roseName) {
                callback.onStatus("发现 " + DeviceCatalog.shortModel(DeviceCatalog.displayName(name)),
                        "正在准备连接");
            }
        }
    }

    private void finishScanAndConnect() {
        if (!scanning) return;
        scanning = false;
        stopScan();
        callback.onScanning(false);
        ScanResult best = bestResult;
        if (best == null) {
            scanFailures++;
            callback.onStatus("未发现耳机", "打开仓盖后会自动重试");
            long delay = Math.min(15 * 60_000L, 30_000L * (1L << Math.min(5, scanFailures - 1)));
            main.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (running && gatt == null) scan();
                }
            }, delay);
            return;
        }
        scanFailures = 0;
        connectTo(best.getDevice(),
                DeviceCatalog.displayName(scanName(best)),
                best.getRssi());
    }

    private void connectTo(BluetoothDevice device, String name, int rssi) {
        if (!running) return;
        idleDisconnected = false;
        stopScan();
        if (spp != null) spp.close();
        sppConnected = false;
        sppConnecting = false;
        closeGatt();
        target = device;
        targetName = name;
        targetRssi = rssi;
        passiveBle = name != null && name.toLowerCase(Locale.US).contains("budsfeel");
        connecting = true;
        firstBatteryReadDone = false;
        splitNotifyReady = false;
        localNotifySet = false;
        notifyRetries = 0;
        totalCharacteristic = null;
        splitWriteCharacteristic = null;
        splitNotifyCharacteristic = null;
        protocol = "正在识别电量服务";
        if (device.getType() == BluetoothDevice.DEVICE_TYPE_CLASSIC) {
            connectSpp(device);
            return;
        }
        DiagLog.add("connect " + targetName + " mac=" + device.getAddress());
        callback.onConnecting(device);
        callback.onStatus("正在连接 " + DeviceCatalog.shortModel(name), "保持耳机在手机附近");
        try {
            gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
        } catch (SecurityException e) {
            connecting = false;
            callback.onPermissionMissing();
            return;
        } catch (RuntimeException e) {
            connecting = false;
            callback.onError("连接失败，请重新打开蓝牙");
            return;
        }
        if (gatt == null) {
            connecting = false;
            callback.onError("无法建立 BLE 连接");
            return;
        }
        main.postDelayed(connectTimeout, CONNECT_TIMEOUT_MS);
    }

    private void connectSpp(BluetoothDevice device) {
        sppMode = true;
        sppConnecting = true;
        connecting = true;
        callback.onConnecting(device);
        callback.onStatus("正在连接经典串口", "Furina / BudsFeel 使用经典蓝牙 SPP");
        if (spp == null) spp = new SppClient(sppCallback);
        spp.connect(device);
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!sppConnecting) return;
                sppConnecting = false;
                if (spp != null) spp.close();
                callback.onError("经典串口连接超时，请关闭官方 App 后重试");
                scheduleReconnect();
            }
        }, 12_000L);
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            main.post(new Runnable() {
                @Override
                public void run() {
                    if (g != gatt) return;
                    main.removeCallbacks(connectTimeout);
                    DiagLog.add("state status=" + status + " new=" + newState);
                    if (newState == BluetoothProfile.STATE_CONNECTED
                            && status == BluetoothGatt.GATT_SUCCESS) {
                        connecting = false;
                        everConnected = true;
                        reconnectAttempt = 0;
                        scanFailures = 0;
                        callback.onConnected(target, targetRssi);
                        callback.onStatus("已连接，正在读取电量", "首次读取通常需要 1 秒");
                        try {
                            g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_BALANCED);
                            g.discoverServices();
                        } catch (RuntimeException e) {
                            callback.onError("服务发现失败");
                            closeGatt();
                        }
                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                        boolean wasConnecting = connecting;
                        connecting = false;
                        closeGatt();
                        resetCharacteristics();
                        callback.onDisconnected(status == BluetoothGatt.GATT_SUCCESS
                                ? "连接已断开" : "连接中断（" + status + "）");
                        if (!running || intentionallyClosing) return;
                        if (wasConnecting && !everConnected) {
                            main.postDelayed(new Runnable() {
                                @Override
                                public void run() {
                                    if (running && gatt == null) scan();
                                }
                            }, 1_500L);
                        } else {
                            scheduleReconnect();
                        }
                    }
                }
            });
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            main.post(new Runnable() {
                @Override
                public void run() {
                    if (g != gatt) return;
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        callback.onError("发现服务失败（" + status + "）");
                        callback.onDisconnected("服务发现失败");
                        closeGatt();
                        scheduleReconnect();
                        return;
                    }
                    discoverCharacteristics(g);
                }
            });
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
            main.post(new Runnable() {
                @Override
                public void run() {
                    if (g == gatt) handleRead(c, c.getValue(), status);
                }
            });
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt g, BluetoothGattCharacteristic c,
                                         byte[] value, int status) {
            main.post(new Runnable() {
                @Override
                public void run() {
                    if (g == gatt) handleRead(c, value, status);
                }
            });
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
            main.post(new Runnable() {
                @Override
                public void run() {
                    if (g == gatt) handleNotify(c, c.getValue());
                }
            });
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c,
                                            byte[] value) {
            main.post(new Runnable() {
                @Override
                public void run() {
                    if (g == gatt) handleNotify(c, value);
                }
            });
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int status) {
            main.post(new Runnable() {
                @Override
                public void run() {
                    if (g != gatt) return;
                    if (d.getCharacteristic() != null && splitNotifyCharacteristic != null
                            && d.getCharacteristic().getUuid()
                            .equals(splitNotifyCharacteristic.getUuid())) {
                        DiagLog.add("cccd write status=" + status
                                + " char=" + splitNotifyCharacteristic.getUuid());
                        splitNotifyReady = status == BluetoothGatt.GATT_SUCCESS;
                        if (splitNotifyReady) {
                            main.postDelayed(new Runnable() {
                                @Override
                                public void run() {
                                    writeSplitQuery();
                                }
                            }, 180L);
                        } else {
                            callback.onStatus("已连接", "分耳电量通道未就绪，稍后自动重试");
                            if (notifyRetries++ < 2) {
                                main.postDelayed(new Runnable() {
                                    @Override
                                    public void run() {
                                        enableSplitNotify();
                                    }
                                }, 2_500L);
                            }
                        }
                    }
                }
            });
        }

        @Override
        public void onReadRemoteRssi(BluetoothGatt g, int rssi, int status) {
            main.post(new Runnable() {
                @Override
                public void run() {
                    if (g == gatt && status == BluetoothGatt.GATT_SUCCESS) {
                        targetRssi = rssi;
                        callback.onConnected(target, rssi);
                    }
                }
            });
        }
    };

    private void discoverCharacteristics(BluetoothGatt g) {
        DiagLog.add("services discovered count=" + g.getServices().size());
        for (android.bluetooth.BluetoothGattService service : g.getServices()) {
            UUID serviceUuid = service.getUuid();
            DiagLog.add("service " + serviceUuid + " chars=" + service.getCharacteristics().size());
            for (BluetoothGattCharacteristic c : service.getCharacteristics()) {
                UUID uuid = c.getUuid();
                DiagLog.add("  char " + uuid + " props=0x" + Integer.toHexString(c.getProperties()));
                if (CHAR_BATTERY_LEVEL.equals(uuid)) {
                    totalCharacteristic = c;
                }
                if (CHAR_PRIVATE_WRITE.equals(uuid)) {
                    splitWriteCharacteristic = c;
                }
                if (CHAR_PRIVATE_NOTIFY.equals(uuid)) {
                    splitNotifyCharacteristic = c;
                }
            }
            // Some firmware puts F1/F2 under a vendor service whose 16-bit alias
            // differs from 00FE. Only accept the pair inside a non-standard service.
            if (!SERVICE_PRIVATE.equals(serviceUuid) && !SERVICE_BATTERY.equals(serviceUuid)) {
                BluetoothGattCharacteristic maybeWrite = null;
                BluetoothGattCharacteristic maybeNotify = null;
                for (BluetoothGattCharacteristic c : service.getCharacteristics()) {
                    if (shortUuid(c.getUuid()) == 0x00F1) maybeWrite = c;
                    if (shortUuid(c.getUuid()) == 0x00F2) maybeNotify = c;
                }
                if (maybeWrite != null && splitWriteCharacteristic == null) {
                    splitWriteCharacteristic = maybeWrite;
                }
                if (maybeNotify != null && splitNotifyCharacteristic == null) {
                    splitNotifyCharacteristic = maybeNotify;
                }
            }
        }

        // Read-only compatibility path for BES-style AE02 notifications. We never
        // write an unknown AE01 command; if the firmware pushes a DD..040C frame,
        // it is parsed exactly like the validated OpenFeel path.
        if (splitNotifyCharacteristic == null) {
            outer:
            for (android.bluetooth.BluetoothGattService service : g.getServices()) {
                for (BluetoothGattCharacteristic c : service.getCharacteristics()) {
                    if (shortUuid(c.getUuid()) == 0xAE02
                            && (hasProperty(c, BluetoothGattCharacteristic.PROPERTY_NOTIFY)
                            || hasProperty(c, BluetoothGattCharacteristic.PROPERTY_INDICATE))) {
                        splitNotifyCharacteristic = c;
                        break outer;
                    }
                }
            }
        }

        if (target != null) {
            AppState.getSavedMac(context);
            String mac = target.getAddress();
            context.getSharedPreferences("battery_dock", Context.MODE_PRIVATE)
                    .edit()
                    .putString("address", mac)
                    .putString("ble_name", targetName == null ? "" : targetName)
                    .putString("model", DeviceCatalog.displayName(targetName))
                    .apply();
        }

        boolean hasTotal = totalCharacteristic != null;
        boolean hasSplitWrite = splitWriteCharacteristic != null;
        boolean hasSplitNotify = splitNotifyCharacteristic != null;
        if (hasSplitWrite && hasSplitNotify && target != null) {
            DeviceProfile.saveBle(context, target.getAddress(),
                    splitWriteCharacteristic.getService().getUuid().toString(),
                    splitWriteCharacteristic.getUuid().toString(),
                    splitNotifyCharacteristic.getUuid().toString());
        }
        if (hasTotal && hasSplitWrite && hasSplitNotify) {
            protocol = "标准电量 + OpenFeel 私有帧";
        } else if (hasTotal && hasSplitNotify) {
            protocol = "标准电量 + 厂商通知";
        } else if (hasTotal) {
            protocol = "标准 Battery Service";
        } else if (hasSplitWrite && hasSplitNotify) {
            protocol = "OpenFeel 私有电量帧";
        } else if (hasSplitNotify) {
            protocol = "厂商电量通知（只读）";
        } else {
            protocol = "已连接，未发现电量特征";
        }
        callback.onBattery(-1, -1, -1, -1, protocol);

        try {
            g.readRemoteRssi();
        } catch (RuntimeException ignored) {
        }

        if (hasTotal && hasRead(totalCharacteristic)) {
            pendingSplitAfterRead = hasSplitWrite && hasSplitNotify;
            firstBatteryReadDone = false;
            try {
                g.readCharacteristic(totalCharacteristic);
            } catch (RuntimeException e) {
                pendingSplitAfterRead = false;
                enableSplitNotify();
            }
        } else {
            pendingSplitAfterRead = false;
            enableSplitNotify();
        }

        if (!hasTotal && !hasSplitNotify) {
            callback.onStatus("已连接", "这个型号没有开放 BLE 电量特征，只能显示连接状态");
        }
        schedulePeriodic();
    }

    private void handleRead(BluetoothGattCharacteristic c, byte[] value, int status) {
        if (c == null || !CHAR_BATTERY_LEVEL.equals(c.getUuid())) return;
        int total = -1;
        boolean charging = false;
        if (status == BluetoothGatt.GATT_SUCCESS && value != null && value.length > 0) {
            int raw = value[0] & 0xFF;
            charging = (raw & 0x80) != 0;
            total = raw & 0x7F;
            if (total < 0 || total > 100) {
                total = -1;
                charging = false;
            }
        }
        if (total >= 0) {
            firstBatteryReadDone = true;
            DiagLog.add("total read=" + total + " charging=" + charging);
            callback.onCharging(charging ? 0x08 : 0, 0x08);
            callback.onBattery(-1, -1, -1, total, protocol);
        }
        if (pendingSplitAfterRead) {
            pendingSplitAfterRead = false;
            enableSplitNotify();
        }
    }

    private void enableSplitNotify() {
        if (gatt == null || splitNotifyCharacteristic == null) {
            return;
        }
        if (splitNotifyReady) {
            writeSplitQuery();
            return;
        }
        try {
            localNotifySet = gatt.setCharacteristicNotification(splitNotifyCharacteristic, true);
        } catch (RuntimeException e) {
            localNotifySet = false;
        }
        BluetoothGattDescriptor descriptor = splitNotifyCharacteristic.getDescriptor(CCCD);
        if (descriptor == null) {
            splitNotifyReady = localNotifySet;
            writeSplitQuery();
            return;
        }
        byte[] valueType = hasProperty(splitNotifyCharacteristic,
                BluetoothGattCharacteristic.PROPERTY_INDICATE)
                ? BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                : BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE;
        boolean queued;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                queued = gatt.writeDescriptor(descriptor, valueType) == 0;
            } else {
                descriptor.setValue(valueType);
                queued = gatt.writeDescriptor(descriptor);
            }
        } catch (RuntimeException e) {
            queued = false;
        }
        if (!queued) {
            splitNotifyReady = localNotifySet;
            if (localNotifySet) writeSplitQuery();
        }
    }

    private void writeSplitQuery() {
        if (passiveBle) return;
        if (gatt == null || splitWriteCharacteristic == null
                || splitNotifyCharacteristic == null) return;
        if (!hasWrite(splitWriteCharacteristic)) return;
        long now = System.currentTimeMillis();
        if (now - lastQueryAt < 1_200L) return;
        lastQueryAt = now;

        writeCharacteristic(gatt, splitWriteCharacteristic, QUERY_ONE);
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (gatt != null && splitWriteCharacteristic != null) {
                    writeCharacteristic(gatt, splitWriteCharacteristic, QUERY_TWO);
                }
            }
        }, 220L);
        main.removeCallbacks(splitRetry);
        main.postDelayed(splitRetry, QUERY_RETRY_MS);
    }

    private void queryNow(boolean manual) {
        if (sppConnected) {
            callback.onStatus("经典串口已连接", "等待耳机上报电量；无数据时请复制诊断");
            return;
        }
        if (gatt == null) {
            if (manual) callback.onStatus("正在连接耳机", "请稍候");
            connectOrScan();
            return;
        }
        if (manual) callback.onStatus("正在刷新电量", "读取左耳、右耳与充电仓");
        boolean readQueued = false;
        if (totalCharacteristic != null && hasRead(totalCharacteristic)) {
            try {
                readQueued = gatt.readCharacteristic(totalCharacteristic);
            } catch (RuntimeException ignored) {
            }
        }
        if (readQueued) {
            pendingSplitAfterRead = true;
            main.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (pendingSplitAfterRead) {
                        pendingSplitAfterRead = false;
                        enableSplitNotify();
                    }
                }
            }, 1_300L);
        } else {
            enableSplitNotify();
        }
        schedulePeriodic();
    }

    private void handleNotify(BluetoothGattCharacteristic c, byte[] value) {
        if (value == null || value.length == 0) return;
        DiagLog.add("notify " + c.getUuid() + " " + toHex(value, 40));
        try {
            bleBuffer.write(value);
        } catch (Exception ignored) {
        }
        byte[] buffered = bleBuffer.toByteArray();
        BatteryFrameParser.Frame frame = BatteryFrameParser.parseFrame(buffered);
        if (frame == null) {
            if (buffered.length > 256) bleBuffer.reset();
            return;
        }
        bleBuffer.reset();
        lastFrameAt = System.currentTimeMillis();
        int left = frame.left;
        int right = frame.right;
        int caseLevel = frame.caseLevel;
        int chargingMask = (frame.leftCharging ? 0x01 : 0)
                | (frame.rightCharging ? 0x02 : 0)
                | (frame.caseCharging ? 0x04 : 0);
        callback.onCharging(chargingMask, 0x07);
        callback.onBattery(left, right, caseLevel, -1, protocol);
        callback.onStatus("电量已更新", "左耳 " + left + "% · 右耳 " + right + "%"
                + (caseLevel > 0 ? " · 充电仓 " + caseLevel + "%" : ""));
    }

    private final SppClient.Callback sppCallback = new SppClient.Callback() {
        @Override
        public void onConnected() {
            if (!running) return;
            sppConnecting = false;
            sppConnected = true;
            connecting = false;
            everConnected = true;
            reconnectAttempt = 0;
            protocol = "经典蓝牙 SPP";
            sppBuffer.reset();
            DiagLog.add("spp session ready");
            callback.onConnected(target, targetRssi);
            callback.onBattery(-1, -1, -1, -1, protocol);
            callback.onStatus("经典串口已连接", "等待耳机电量帧；无数据时请复制诊断");
            int systemLevel = readSystemBattery(target);
            if (systemLevel >= 0) {
                DiagLog.add("system battery " + systemLevel);
                callback.onBattery(-1, -1, -1, systemLevel, protocol + " + 系统电量");
            }
            sppBatterySeen = false;
            String mac = target == null ? "" : target.getAddress();
            DeviceProfile.setProtocol(context, mac, "spp");
            String learned = DeviceProfile.queryMode(context, mac);
            boolean full = "full".equals(learned)
                    || (learned.isEmpty() && isFurinaName(targetName));
            sppQueryWasFull = full;
            sendSppQueries(full);
        }

        private void sendSppQueries(final boolean furina) {
            main.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (!sppConnected || spp == null) return;
                    spp.send(SPP_INIT_0);
                    main.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            if (!sppConnected || spp == null) return;
                            spp.send(SPP_INIT_1);
                            main.postDelayed(new Runnable() {
                                @Override
                                public void run() {
                                    if (!sppConnected || spp == null) return;
                                    spp.send(furina ? SPP_GET_ALL : SPP_GET_BATTERY);
                                    DiagLog.add(furina
                                            ? "spp init + get-all query sent"
                                            : "spp init + minimal battery query sent");
                                    if (!furina) {
                                        main.postDelayed(new Runnable() {
                                            @Override
                                            public void run() {
                                                if (sppConnected && spp != null
                                                        && !sppBatterySeen) {
                                                    sppQueryWasFull = true;
                                                    spp.send(SPP_GET_ALL);
                                                    DiagLog.add("spp get-all fallback sent");
                                                }
                                            }
                                        }, 1600L);
                                    }
                                }
                            }, 420L);
                        }
                    }, 420L);
                }
            }, 450L);
        }

        @Override
        public void onData(byte[] data) {
            if (data == null || data.length == 0) return;
            try {
                sppBuffer.write(data);
            } catch (Exception ignored) {
            }
            byte[] buffered = sppBuffer.toByteArray();
            BatteryFrameParser.Frame frame = BatteryFrameParser.parseFrame(buffered);
            if (frame != null) {
                sppBatterySeen = true;
                if (target != null) {
                    DeviceProfile.setQueryMode(context, target.getAddress(),
                            sppQueryWasFull ? "full" : "min");
                }
                sppBuffer.reset();
                int chargingMask = (frame.leftCharging ? 0x01 : 0)
                        | (frame.rightCharging ? 0x02 : 0)
                        | (frame.caseCharging ? 0x04 : 0);
                callback.onCharging(chargingMask, 0x07);
                callback.onBattery(frame.left, frame.right, frame.caseLevel, -1, protocol);
                callback.onStatus("电量已更新", "左耳 " + frame.left + "% · 右耳 " + frame.right
                        + "%" + (frame.caseLevel > 0 ? " · 仓 " + frame.caseLevel + "%" : ""));
            } else {
                if (buffered.length > 256) {
                    sppBuffer.reset();
                    try {
                        sppBuffer.write(buffered, buffered.length - 128, 128);
                    } catch (Exception ignored) {
                    }
                }
                callback.onStatus("收到经典串口数据", "正在等待可识别的电量帧");
            }
        }

        @Override
        public void onDisconnected(String detail) {
            if (!running) return;
            sppConnected = false;
            sppConnecting = false;
            sppBatterySeen = false;
            sppBuffer.reset();
            callback.onDisconnected(detail == null ? "经典串口已断开" : detail);
            scheduleReconnect();
        }
    };

    private int readSystemBattery(BluetoothDevice device) {
        if (device == null) return -1;
        try {
            java.lang.reflect.Method method =
                    BluetoothDevice.class.getDeclaredMethod("getBatteryLevel");
            method.setAccessible(true);
            Object value = method.invoke(device);
            if (value instanceof Integer) {
                int level = (Integer) value;
                return level >= 0 && level <= 100 ? level : -1;
            }
        } catch (Exception ignored) {
        }
        return -1;
    }

    /** Experimental, user-triggered read probe for classic SPP models. */
    public void sendSppProbe() {
        if (!sppConnected || spp == null) {
            callback.onStatus("尚未连接经典串口", "请先在「已配对设备」里选择耳机");
            return;
        }
        callback.onStatus("正在发送 SPP 查询", "先发 8 个状态项，再发一组兼容帧");
        sppBuffer.reset();
        final byte[][] handshake = new byte[][]{
                {0x00, 0x30, 0x01, 0x00, 0x01, 0x00},
                {0x00, 0x31, 0x01, 0x00, 0x01, 0x00},
                {0x00, 0x32, 0x01, 0x00, 0x01, 0x00},
                {0x00, 0x2A, 0x01, 0x00, 0x01, 0x00}
        };
        for (int i = 0; i < handshake.length; i++) {
            final byte[] frame = handshake[i];
            main.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (!sppConnected || spp == null) return;
                    spp.send(frame);
                }
            }, 120L * (i + 1));
        }
        for (int id = 1; id <= 8; id++) {
            final int commandId = id;
            main.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (!sppConnected || spp == null) return;
                    spp.send(new byte[]{0x00, 0x22, 0x01, 0x00, 0x03,
                            (byte) commandId, 0x01, 0x00});
                }
            }, 180L * id);
        }
        for (int id = 1; id <= 8; id++) {
            final int commandId = id;
            main.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (!sppConnected || spp == null) return;
                    spp.send(new byte[]{(byte) 0xFF, 0x00, 0x03, 0x01,
                            (byte) commandId, 0x00});
                }
            }, 1_800L + 180L * id);
        }
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!sppConnected || spp == null) return;
                // The OpenFeel query family uses the same FF 00 header. Some
                // newer firmware tunnels the same command layer over SPP.
                spp.send(QUERY_ONE);
            }
        }, 3_500L);
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!sppConnected || spp == null) return;
                spp.send(QUERY_TWO);
            }
        }, 3_720L);
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!sppConnected) return;
                callback.onStatus("SPP 查询已发送", "若无电量，请点「复制蓝牙诊断」发回原始字节");
            }
        }, 5_000L);
    }

    private void scheduleReconnect() {
        if (!running || idleDisconnected) return;
        int delay;
        int attempt = reconnectAttempt++;
        if (attempt == 0) delay = 3_000;
        else if (attempt == 1) delay = 8_000;
        else if (attempt == 2) delay = 20_000;
        else if (attempt == 3) delay = 60_000;
        else delay = 180_000;
        callback.onStatus("等待重新连接", "约 " + Math.max(1, delay / 1000) + " 秒后自动重试");
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!running || gatt != null) return;
                if (adapter == null || !adapter.isEnabled()) {
                    callback.onBluetoothOff();
                    return;
                }
                connectOrScan();
            }
        }, delay);
    }

    private void stopScan() {
        main.removeCallbacks(scanTimeout);
        ScanCallback cb = scanCallback;
        scanCallback = null;
        if (cb != null && scanner != null && hasScanPermission()) {
            try {
                scanner.stopScan(cb);
            } catch (RuntimeException ignored) {
            }
        }
        if (scanning) {
            scanning = false;
            callback.onScanning(false);
        }
    }

    private void closeGatt() {
        BluetoothGatt old = gatt;
        gatt = null;
        main.removeCallbacks(connectTimeout);
        if (old != null) {
            try {
                old.disconnect();
            } catch (RuntimeException ignored) {
            }
            try {
                old.close();
            } catch (RuntimeException ignored) {
            }
        }
    }

    private void resetCharacteristics() {
        totalCharacteristic = null;
        splitWriteCharacteristic = null;
        splitNotifyCharacteristic = null;
        bleBuffer.reset();
        splitNotifyReady = false;
        localNotifySet = false;
        pendingSplitAfterRead = false;
        firstBatteryReadDone = false;
        notifyRetries = 0;
    }

    private boolean writeCharacteristic(BluetoothGatt g, BluetoothGattCharacteristic c,
                                        byte[] value) {
        int type = hasProperty(c, BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)
                ? BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                : BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                return g.writeCharacteristic(c, value, type) == 0;
            }
            c.setWriteType(type);
            c.setValue(value);
            return g.writeCharacteristic(c);
        } catch (RuntimeException e) {
            Log.w(TAG, "write failed", e);
            return false;
        }
    }

    private static boolean hasRead(BluetoothGattCharacteristic c) {
        return hasProperty(c, BluetoothGattCharacteristic.PROPERTY_READ);
    }

    private static boolean hasWrite(BluetoothGattCharacteristic c) {
        return hasProperty(c, BluetoothGattCharacteristic.PROPERTY_WRITE)
                || hasProperty(c, BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE);
    }

    private static boolean hasProperty(BluetoothGattCharacteristic c, int property) {
        return c != null && (c.getProperties() & property) != 0;
    }

    private boolean hasConnectPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    private boolean hasScanPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private String safeName(BluetoothDevice device) {
        if (device == null) return "";
        try {
            String name = device.getName();
            if (name != null && !name.isEmpty()) return name;
        } catch (SecurityException ignored) {
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                String alias = device.getAlias();
                if (alias != null && !alias.isEmpty()) return alias;
            } catch (SecurityException ignored) {
            }
        }
        return "";
    }

    private String scanName(ScanResult result) {
        String name = "";
        ScanRecord record = result.getScanRecord();
        if (record != null) {
            String n = record.getDeviceName();
            if (n != null) name = n;
        }
        if (name.isEmpty()) name = safeName(result.getDevice());
        if (name.isEmpty() && result.getDevice().getAddress()
                .equalsIgnoreCase(AppState.getSavedMac(context))) {
            name = context.getSharedPreferences("battery_dock", Context.MODE_PRIVATE)
                    .getString("ble_name", "");
        }
        return name == null ? "" : name;
    }

    private static boolean hasManufacturer(ScanResult result, int id) {
        ScanRecord record = result.getScanRecord();
        return record != null && record.getManufacturerSpecificData(id) != null;
    }

    private static boolean hasService(ScanResult result, int shortId) {
        ScanRecord record = result.getScanRecord();
        if (record == null || record.getServiceUuids() == null) return false;
        for (android.os.ParcelUuid parcel : record.getServiceUuids()) {
            if (parcel != null && shortUuid(parcel.getUuid()) == shortId) return true;
        }
        return false;
    }

    private static int shortUuid(UUID uuid) {
        if (uuid == null) return -1;
        String s = uuid.toString().toLowerCase(Locale.US);
        if (!s.endsWith("-0000-1000-8000-00805f9b34fb")) return -1;
        String head = s.substring(0, s.indexOf('-'));
        if (head.length() != 8) return -1;
        try {
            return Integer.parseInt(head.substring(4), 16);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static byte[] hex(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    private static String toHex(byte[] value, int max) {
        if (value == null) return "null";
        int len = Math.min(value.length, max);
        StringBuilder sb = new StringBuilder(len * 3);
        for (int i = 0; i < len; i++) {
            if (i > 0) sb.append(' ');
            int v = value[i] & 0xFF;
            if (v < 16) sb.append('0');
            sb.append(Integer.toHexString(v).toUpperCase(Locale.US));
        }
        if (value.length > len) sb.append(" …(").append(value.length).append(')');
        return sb.toString();
    }
}
