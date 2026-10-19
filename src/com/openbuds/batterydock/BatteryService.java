package com.openbuds.batterydock;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.text.TextUtils;

import java.util.Locale;

/**
 * Foreground service that owns the single BLE connection and the ongoing
 * notification. It is intentionally small: one connection, one notification,
 * no wakelocks and no polling while the screen is off beyond the 60 s fallback.
 */
public final class BatteryService extends Service implements BleBatteryClient.Callback {
    public static final String ACTION_START = "com.openbuds.batterydock.START";
    public static final String ACTION_REFRESH = "com.openbuds.batterydock.REFRESH";
    public static final String ACTION_RECONNECT = "com.openbuds.batterydock.RECONNECT";
    public static final String ACTION_FORGET = "com.openbuds.batterydock.FORGET";
    public static final String ACTION_STOP = "com.openbuds.batterydock.STOP";
    public static final String ACTION_CONNECT_DEVICE = "com.openbuds.batterydock.CONNECT_DEVICE";
    public static final String EXTRA_MAC = "mac";
    public static final String EXTRA_NAME = "name";
    public static final String ACTION_SPP_PROBE = "com.openbuds.batterydock.SPP_PROBE";
    public static final String ACTION_APPLY_IDLE = "com.openbuds.batterydock.APPLY_IDLE";
    public static final String ACTION_APPLY_ESTIMATE = "com.openbuds.batterydock.APPLY_ESTIMATE";
    public static final String ACTION_APP_PAUSED = "com.openbuds.batterydock.APP_PAUSED";

    private static final String ACTION_HIDDEN_BATTERY =
            "android.bluetooth.device.action.BATTERY_LEVEL_CHANGED";
    private static final String EXTRA_HIDDEN_BATTERY =
            "android.bluetooth.device.extra.BATTERY_LEVEL";
    private static final long IDLE_DISCONNECT_MS = 10 * 60_000L;

    private final Runnable idleDisconnect = new Runnable() {
        @Override
        public void run() {
            if (stopping || isScreenOn() || !Prefs.idleDisconnectEnabled(BatteryService.this)) {
                return;
            }
            if (ble != null && ble.isConnected()) {
                ble.disconnectForIdle();
            }
        }
    };

    private static final String CHANNEL_ID = "battery_status";
    private static final int NOTIFICATION_ID = 0x5A17;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable foregroundCleanup = new Runnable() {
        @Override
        public void run() {
            if (!latest.connected) removeForeground();
        }
    };
    private final Runnable caseEstimateTick = new Runnable() {
        @Override
        public void run() {
            if (stopping || !latest.connected || !latest.caseStale
                    || !Prefs.caseEstimateEnabled(BatteryService.this)) {
                return;
            }
            int est = CaseHistory.estimate(BatteryService.this, latest.address,
                    latest.caseActual, System.currentTimeMillis(), latest.budsInCase);
            if (est >= 0) {
                BatterySnapshot s = latest.copy();
                s.caseLevel = est;
                s.caseEstimated = true;
                publish(s, true);
            }
            main.postDelayed(this, 60_000L);
        }
    };
    private NotificationManager notificationManager;
    private BleBatteryClient ble;
    private BatterySnapshot latest = new BatterySnapshot();
    private int lastNotificationHash;
    private long lastNotificationAt;
    private boolean foregroundStarted;
    private boolean serviceReady;
    private boolean stopping;

    private final BroadcastReceiver bluetoothReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || intent.getAction() == null) return;
            if (ACTION_HIDDEN_BATTERY.equals(intent.getAction())) {
                BluetoothDevice device = intent.getParcelableExtra(
                        android.bluetooth.BluetoothDevice.EXTRA_DEVICE);
                int battery = intent.getIntExtra(EXTRA_HIDDEN_BATTERY, -1);
                if (device != null && battery >= 0 && battery <= 100) {
                    String addr = safeAddress(device);
                    String name = safeDeviceName(device);
                    String saved = AppState.getSavedMac(BatteryService.this);
                    if ((!saved.isEmpty() && saved.equalsIgnoreCase(addr))
                            || DeviceCatalog.looksLikeRose(name)) {
                        // The system often keeps reporting the last battery value
                        // for a paired device that is switched off. It is not proof
                        // of a live connection and must not revive the notification.
                        if (!latest.connected) return;
                        BatterySnapshot s = latest.copy();
                        s.total = battery;
                        s.updatedAt = System.currentTimeMillis();
                        DiagLog.add("hidden battery " + addr + " " + battery);
                        publish(s, true);
                    }
                }
                return;
            }
            if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                if (ble != null) ble.pausePolling();
                scheduleIdleDisconnect();
                return;
            }
            if (Intent.ACTION_SCREEN_ON.equals(intent.getAction())
                    || Intent.ACTION_USER_PRESENT.equals(intent.getAction())) {
                cancelIdleDisconnect();
                if (ble != null) {
                    if (ble.isIdle()) {
                        ble.resumeFromIdle();
                    } else if (ble.isConnected()
                            && System.currentTimeMillis() - latest.updatedAt > 30_000L) {
                        ble.refresh();
                    }
                }
                return;
            }
            if (android.bluetooth.BluetoothDevice.ACTION_ACL_CONNECTED
                    .equals(intent.getAction())) {
                BluetoothDevice device = intent.getParcelableExtra(
                        android.bluetooth.BluetoothDevice.EXTRA_DEVICE);
                if (device != null && ble != null) {
                    String saved = AppState.getSavedMac(BatteryService.this);
                    String name = safeDeviceName(device);
                    if ((!saved.isEmpty() && saved.equalsIgnoreCase(safeAddress(device)))
                            || DeviceCatalog.looksLikeRose(name)) {
                        final String addr = safeAddress(device);
                        final String devName = name;
                        main.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                // Connect to the device that just appeared, not to a
                                // possibly stale saved MAC. This avoids a failed scan.
                                if (ble != null && !ble.isConnected()) {
                                    ble.connectToAddress(addr, devName);
                                }
                            }
                        }, 700L);
                    }
                }
                return;
            }
            if (!BluetoothAdapterState.isStateChange(intent)) return;
            int state = intent.getIntExtra(android.bluetooth.BluetoothAdapter.EXTRA_STATE,
                    android.bluetooth.BluetoothAdapter.ERROR);
            if (state == android.bluetooth.BluetoothAdapter.STATE_ON) {
                BatterySnapshot s = latest.copy();
                s.bluetoothOff = false;
                publish(s, true);
                if (ble != null) ble.refresh();
            } else if (state == android.bluetooth.BluetoothAdapter.STATE_TURNING_OFF
                    || state == android.bluetooth.BluetoothAdapter.STATE_OFF) {
                removeForeground();
                BatterySnapshot s = latest.copy();
                s.bluetoothOff = true;
                s.connected = false;
                s.connecting = false;
                s.scanning = false;
                clearLevels(s);
                s.statusTitle = "蓝牙已关闭";
                s.statusDetail = "打开蓝牙后自动恢复";
                publish(s, true);
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        AppState.init(this);
        latest = AppState.get();
        notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        createChannel();
        Notification notification = buildNotification(latest);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
            lastNotificationHash = hash(latest);
            lastNotificationAt = System.currentTimeMillis();
            foregroundStarted = true;
            serviceReady = true;
        } catch (RuntimeException e) {
            // A missing BLUETOOTH_CONNECT permission can make the typed FGS fail on
            // Android 14. Stop cleanly; the activity will ask for permission again.
            stopSelf();
            return;
        }
        ble = new BleBatteryClient(this, this);
        scheduleForegroundCleanup();
        IntentFilter filter = new IntentFilter(android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED);
        filter.addAction(android.bluetooth.BluetoothDevice.ACTION_ACL_CONNECTED);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_USER_PRESENT);
        filter.addAction(ACTION_HIDDEN_BATTERY);
        try {
            registerReceiver(bluetoothReceiver, filter);
        } catch (RuntimeException ignored) {
            // Some OEM builds restrict dynamic receivers; BLE still works.
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        AppState.init(this);
        if (!serviceReady) {
            stopSelf();
            return START_NOT_STICKY;
        }
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            AppState.setEnabled(this, false);
            stopping = true;
            if (ble != null) ble.stop();
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_APP_PAUSED.equals(action)) {
            if (!latest.connected) removeForeground();
            return START_STICKY;
        }
        boolean manualConnect = ACTION_CONNECT_DEVICE.equals(action);
        if (!AppState.isEnabled(this) && !manualConnect) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ble == null) {
            ble = new BleBatteryClient(this, this);
        }
        if (!ACTION_CONNECT_DEVICE.equals(action)) {
            ensureForeground();
            scheduleForegroundCleanup();
        }
        if (ACTION_REFRESH.equals(action)) {
            ble.refresh();
        } else if (ACTION_RECONNECT.equals(action)) {
            ble.reconnect();
        } else if (ACTION_FORGET.equals(action)) {
            AppState.clearSavedMac(this);
            BatterySnapshot fresh = latest.copy();
            fresh.modelName = "耳机";
            fresh.bleName = "";
            fresh.address = "";
            fresh.left = -1;
            fresh.right = -1;
            fresh.caseLevel = -1;
            fresh.total = -1;
            fresh.updatedAt = 0L;
            fresh.protocol = "等待连接";
            fresh.statusTitle = "正在重新查找";
            fresh.statusDetail = "请打开耳机仓盖，靠近手机";
            publish(fresh, true);
            ble.forgetAndScan();
        } else if (ACTION_SPP_PROBE.equals(action)) {
            ble.sendSppProbe();
        } else if (ACTION_APPLY_IDLE.equals(action)) {
            if (!Prefs.idleDisconnectEnabled(this)) {
                cancelIdleDisconnect();
                if (ble.isIdle()) ble.resumeFromIdle();
            } else if (isScreenOn()) {
                cancelIdleDisconnect();
            } else {
                scheduleIdleDisconnect();
            }
        } else if (ACTION_APPLY_ESTIMATE.equals(action)) {
            if (!Prefs.caseEstimateEnabled(this)) {
                main.removeCallbacks(caseEstimateTick);
                if (latest.caseStale && latest.caseActual >= 0) {
                    BatterySnapshot s = latest.copy();
                    s.caseLevel = s.caseActual;
                    s.caseEstimated = false;
                    publish(s, true);
                }
            } else {
                scheduleCaseEstimate();
            }
        } else if (manualConnect) {
            String mac = intent.getStringExtra(EXTRA_MAC);
            String name = intent.getStringExtra(EXTRA_NAME);
            if (mac == null || mac.trim().isEmpty()) {
                BatterySnapshot err = latest.copy();
                err.statusTitle = "连接失败";
                err.statusDetail = "没有选择有效的设备地址";
                publish(err, true);
            } else {
                AppState.setEnabled(this, true);
                BatterySnapshot target = latest.copy();
                target.address = mac.trim().toUpperCase(Locale.US);
                target.bleName = name == null ? "" : name;
                target.modelName = DeviceCatalog.displayName(name);
                target.left = -1;
                target.right = -1;
                target.caseLevel = -1;
                target.total = -1;
                target.updatedAt = 0L;
                target.protocol = "正在识别电量服务";
                target.connected = false;
                target.connecting = true;
                target.scanning = false;
                target.bluetoothOff = false;
                target.permissionMissing = false;
                target.statusTitle = "正在连接 " + DeviceCatalog.shortModel(target.modelName);
                target.statusDetail = "手动连接已配对设备";
                publish(target, true);
                getSharedPreferences("battery_dock", MODE_PRIVATE).edit()
                        .putString("address", target.address)
                        .putString("ble_name", target.bleName)
                        .putString("model", target.modelName)
                        .apply();
                ble.connectToAddress(target.address, target.bleName);
            }
        } else {
            ble.start();
        }
        if (isScreenOn()) {
            cancelIdleDisconnect();
        } else {
            scheduleIdleDisconnect();
        }
        BatterySnapshot s = latest.copy();
        if (s.statusTitle == null || s.statusTitle.isEmpty() || "等待连接".equals(s.statusTitle)) {
            s.statusTitle = "正在启动";
            s.statusDetail = "准备连接耳机";
            publish(s, false);
        }
        updateNotification(true);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopping = true;
        cancelIdleDisconnect();
        main.removeCallbacks(caseEstimateTick);
        foregroundStarted = false;
        if (ble != null) ble.stop();
        try {
            unregisterReceiver(bluetoothReceiver);
        } catch (RuntimeException ignored) {
        }
        if (notificationManager != null) {
            try {
                notificationManager.cancel(NOTIFICATION_ID);
            } catch (RuntimeException ignored) {
            }
        }
        BatterySnapshot s = latest.copy();
        s.serviceRunning = false;
        s.connected = false;
        s.connecting = false;
        s.scanning = false;
        clearLevels(s);
        s.statusTitle = "后台服务已关闭";
        s.statusDetail = "打开应用可重新开启";
        AppState.set(s);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // Keep the notification and BLE link alive after the task is swiped away.
        super.onTaskRemoved(rootIntent);
    }

    // ------------------------------------------------------------------ BLE callbacks

    @Override
    public void onScanning(boolean scanning) {
        BatterySnapshot s = latest.copy();
        s.scanning = scanning;
        if (scanning) {
            s.connecting = false;
            s.statusTitle = "正在查找耳机";
            s.statusDetail = "请打开充电仓盖，靠近手机";
        }
        publish(s, true);
    }

    @Override
    public void onConnecting(BluetoothDevice device) {
        BatterySnapshot s = latest.copy();
        s.connecting = true;
        s.connected = false;
        s.scanning = false;
        s.bluetoothOff = false;
        s.permissionMissing = false;
        String nextAddress = device == null ? s.address : safeAddress(device);
        if (device != null && !nextAddress.isEmpty() && !nextAddress.equalsIgnoreCase(s.address)) {
            s.left = -1;
            s.right = -1;
            s.caseLevel = -1;
            s.total = -1;
            s.updatedAt = 0L;
            s.leftCharging = false;
            s.rightCharging = false;
            s.caseCharging = false;
            s.totalCharging = false;
            s.caseStale = false;
            s.caseEstimated = false;
        }
        s.address = nextAddress;
        clearLevels(s);
        s.statusTitle = "正在连接";
        s.statusDetail = "保持耳机在手机附近";
        publish(s, true);
    }

    @Override
    public void onConnected(BluetoothDevice device, int rssi) {
        ensureForeground();
        BatterySnapshot s = latest.copy();
        s.connected = true;
        s.connecting = false;
        s.scanning = false;
        s.bluetoothOff = false;
        s.permissionMissing = false;
        s.rssi = rssi;
        if (device != null) {
            s.address = safeAddress(device);
            String name = safeDeviceName(device);
            if (!name.isEmpty()) {
                s.bleName = name;
                s.modelName = DeviceCatalog.displayName(name);
            }
        }
        if ("等待连接".equals(s.statusTitle) || "正在连接".equals(s.statusTitle)) {
            s.statusTitle = "已连接";
            s.statusDetail = "正在读取电量";
        }
        publish(s, true);
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (ble != null && latest.connected) ble.refresh();
            }
        }, 800L);
        if (isScreenOn()) cancelIdleDisconnect();
        else scheduleIdleDisconnect();
    }

    @Override
    public void onIdleDisconnected() {
        cancelIdleDisconnect();
        main.removeCallbacks(caseEstimateTick);
        removeForeground();
        BatterySnapshot s = latest.copy();
        s.connected = false;
        s.connecting = false;
        s.scanning = false;
        clearLevels(s);
        s.statusTitle = "息屏省电";
        s.statusDetail = "已断开连接，亮屏后自动重连";
        publish(s, true);
    }

    @Override
    public void onDisconnected(String detail) {
        cancelIdleDisconnect();
        main.removeCallbacks(caseEstimateTick);
        removeForeground();
        BatterySnapshot s = latest.copy();
        s.connected = false;
        s.connecting = false;
        s.scanning = false;
        clearLevels(s);
        s.statusTitle = "连接已断开";
        s.statusDetail = detail == null ? "正在自动重连" : detail;
        publish(s, true);
    }

    @Override
    public void onBattery(int left, int right, int caseLevel, int total, String protocol) {
        BatterySnapshot s = latest.copy();
        boolean changed = false;
        boolean any = false;
        boolean splitFrame = left >= 0 || right >= 0 || caseLevel >= 0;
        if (splitFrame) {
            // A split frame explicitly reports which buds are present. A parsed
            // 0 is converted to -1 by the parser, so write it through as a dash.
            s.left = left >= 0 ? Math.min(100, left) : -1;
            s.right = right >= 0 ? Math.min(100, right) : -1;
            s.budsInCase = s.left < 0 && s.right < 0;
            any = true;
        }
        if (caseLevel >= 0) {
            s.caseLevel = Math.min(100, caseLevel);
            s.caseActual = s.caseLevel;
            s.caseStale = false;
            s.caseEstimated = false;
            if (!s.caseCharging && s.address != null && !s.address.isEmpty()) {
                CaseHistory.record(this, s.address, s.caseLevel,
                        System.currentTimeMillis(), s.budsInCase);
            }
            any = true;
        } else if (s.caseLevel >= 0) {
            // Case closed/not reporting: keep the last value, mark it stale and
            // optionally extrapolate it from the learned discharge rate.
            if (s.caseActual < 0) s.caseActual = s.caseLevel;
            s.caseStale = true;
            s.caseEstimated = false;
            s.caseLevel = s.caseActual;
            if (Prefs.caseEstimateEnabled(this) && !s.caseCharging
                    && s.address != null && !s.address.isEmpty()) {
                int est = CaseHistory.estimate(this, s.address, s.caseActual,
                        System.currentTimeMillis(), s.budsInCase);
                if (est >= 0) {
                    s.caseLevel = est;
                    s.caseEstimated = true;
                }
            }
        }
        if (total >= 0) {
            s.total = Math.min(100, total);
            any = true;
        }
        if (protocol != null && !protocol.isEmpty() && !"等待连接".equals(protocol)) {
            s.protocol = protocol;
        }
        if (any) {
            // A system-only total (T) must not fake a live earbud connection.
            if (splitFrame || s.connected) {
                ensureForeground();
                s.connected = true;
                s.connecting = false;
                s.scanning = false;
                s.permissionMissing = false;
                if (!"已连接".equals(s.statusTitle) && !"电量已更新".equals(s.statusTitle)) {
                    s.statusTitle = "已连接";
                }
            }
            s.updatedAt = System.currentTimeMillis();
            changed = true;
        }
        publish(s, changed);
        scheduleCaseEstimate();
    }

    private void scheduleCaseEstimate() {        main.removeCallbacks(caseEstimateTick);
        if (latest.connected && latest.caseStale && latest.caseLevel >= 0
                && Prefs.caseEstimateEnabled(this)) {
            main.postDelayed(caseEstimateTick, 60_000L);
        }
    }

    @Override
    public void onCharging(int chargingMask, int knownMask) {
        BatterySnapshot s = latest.copy();
        boolean changed = false;
        if ((knownMask & 0x01) != 0) {
            s.leftCharging = (chargingMask & 0x01) != 0;
            changed = true;
        }
        if ((knownMask & 0x02) != 0) {
            s.rightCharging = (chargingMask & 0x02) != 0;
            changed = true;
        }
        if ((knownMask & 0x04) != 0) {
            s.caseCharging = (chargingMask & 0x04) != 0;
            changed = true;
        }
        if ((knownMask & 0x08) != 0) {
            s.totalCharging = (chargingMask & 0x08) != 0;
            changed = true;
        }
        if (changed) {
            if (s.connected) ensureForeground();
            publish(s, true);
        }
    }

    @Override
    public void onStatus(String title, String detail) {
        BatterySnapshot s = latest.copy();
        s.statusTitle = title == null ? s.statusTitle : title;
        s.statusDetail = detail == null ? s.statusDetail : detail;
        if (!s.connected && s.statusTitle != null
                && (s.statusTitle.contains("未发现") || s.statusTitle.contains("连接超时")
                || s.statusTitle.contains("等待重新连接")
                || s.statusTitle.contains("正在重新查找"))) {
            clearLevels(s);
            removeForeground();
        }
        publish(s, true);
    }

    @Override
    public void onError(String detail) {
        removeForeground();
        BatterySnapshot s = latest.copy();
        s.error = detail == null ? "未知错误" : detail;
        s.statusTitle = "连接遇到问题";
        s.statusDetail = s.error;
        s.connecting = false;
        s.scanning = false;
        clearLevels(s);
        publish(s, true);
    }

    @Override
    public void onBluetoothOff() {
        removeForeground();
        BatterySnapshot s = latest.copy();
        s.bluetoothOff = true;
        s.connected = false;
        s.connecting = false;
        s.scanning = false;
        clearLevels(s);
        s.statusTitle = "蓝牙未开启";
        s.statusDetail = "打开蓝牙后会自动连接";
        publish(s, true);
    }

    @Override
    public void onPermissionMissing() {
        removeForeground();
        BatterySnapshot s = latest.copy();
        s.permissionMissing = true;
        s.connected = false;
        s.connecting = false;
        s.scanning = false;
        clearLevels(s);
        s.statusTitle = "需要蓝牙权限";
        s.statusDetail = "点按打开应用完成授权";
        publish(s, true);
    }

    // ------------------------------------------------------------------ notification

    private void publish(BatterySnapshot snapshot, boolean force) {
        latest = snapshot;
        AppState.set(snapshot);
        updateNotification(force);
    }

    private static void clearLevels(BatterySnapshot s) {
        s.left = -1;
        s.right = -1;
        s.caseLevel = -1;
        s.caseActual = -1;
        s.total = -1;
        s.updatedAt = 0L;
        s.leftCharging = false;
        s.rightCharging = false;
        s.caseCharging = false;
        s.totalCharging = false;
        s.caseStale = false;
        s.caseEstimated = false;
        s.budsInCase = false;
    }

    private boolean isScreenOn() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        return pm == null || pm.isInteractive();
    }

    private void scheduleIdleDisconnect() {
        main.removeCallbacks(idleDisconnect);
        if (!Prefs.idleDisconnectEnabled(this) || isScreenOn()) return;
        if (ble == null || !ble.isConnected()) return;
        main.postDelayed(idleDisconnect, IDLE_DISCONNECT_MS);
    }

    private void cancelIdleDisconnect() {
        main.removeCallbacks(idleDisconnect);
    }

    private void scheduleForegroundCleanup() {
        main.removeCallbacks(foregroundCleanup);
        main.postDelayed(foregroundCleanup, 10_000L);
    }

    private void ensureForeground() {        if (foregroundStarted || stopping) return;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, buildNotification(latest),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
            } else {
                startForeground(NOTIFICATION_ID, buildNotification(latest));
            }
            foregroundStarted = true;
        } catch (RuntimeException ignored) {
        }
    }

    /** Called on disconnect: the shade entry disappears immediately. */
    private void removeForeground() {
        foregroundStarted = false;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE);
            } else {
                stopForeground(true);
            }
        } catch (RuntimeException ignored) {
        }
        try {
            notificationManager.cancel(NOTIFICATION_ID);
        } catch (RuntimeException ignored) {
        }
    }

    private void updateNotification(boolean force) {
        if (stopping || !foregroundStarted) return;
        long now = System.currentTimeMillis();
        int h = hash(latest);
        if (!force && h == lastNotificationHash && now - lastNotificationAt < 1_000L) return;
        if (h == lastNotificationHash && now - lastNotificationAt < 300L) return;
        lastNotificationHash = h;
        lastNotificationAt = now;
        try {
            notificationManager.notify(NOTIFICATION_ID, buildNotification(latest));
        } catch (RuntimeException ignored) {
            // POST_NOTIFICATIONS denied: the service still runs, the UI explains it.
        }
    }

    private Notification buildNotification(BatterySnapshot s) {
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) piFlags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent content = PendingIntent.getActivity(this, 1, open, piFlags);

        String title = TextUtils.isEmpty(s.modelName) ? "耳机" : s.modelName;
        String summary;
        if (s.hasSplit()) {
            StringBuilder sb = new StringBuilder("左 ");
            sb.append(pct(s.left, s.leftCharging)).append("   右 ")
                    .append(pct(s.right, s.rightCharging));
            if (s.caseLevel > 0) {
                sb.append("   仓 ").append(pct(s.caseLevel, s.caseCharging));
                if (s.caseEstimated) sb.append("(估算)");
                else if (s.caseStale) sb.append("(关盖前)");
            }
            summary = sb.toString();
        } else if (s.total >= 0) {
            summary = "总电量 " + pct(s.total, s.totalCharging);
        } else {
            summary = "等待连接";
        }

        Notification.Builder b = new Notification.Builder(this, CHANNEL_ID);
        b.setSmallIcon(R.drawable.ic_notification)
                .setColor(0xFFE7B27A)
                .setContentTitle(title)
                .setContentText(summary)
                .setContentIntent(content)
                .setOngoing(true)
                .setSound(null, null)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setVisibility(Notification.VISIBILITY_SECRET);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        }
        return b.build();
    }

    private static String pct(int v, boolean charging) {
        return (v >= 0 ? v + "%" : "--") + (charging ? "⚡" : "");
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(getString(R.string.channel_desc));
        channel.setShowBadge(false);
        channel.setSound(null, null);
        channel.enableVibration(false);
        channel.setLockscreenVisibility(Notification.VISIBILITY_SECRET);
        notificationManager.createNotificationChannel(channel);
    }

    private static int hash(BatterySnapshot s) {
        int h = 17;
        h = 31 * h + (s.connected ? 1 : 0);
        h = 31 * h + s.left;
        h = 31 * h + s.right;
        h = 31 * h + s.caseLevel;
        h = 31 * h + s.total;
        h = 31 * h + (s.leftCharging ? 1 : 0);
        h = 31 * h + (s.rightCharging ? 2 : 0);
        h = 31 * h + (s.caseCharging ? 4 : 0);
        h = 31 * h + (s.totalCharging ? 8 : 0);
        h = 31 * h + (s.caseStale ? 16 : 0);
        h = 31 * h + (s.caseEstimated ? 32 : 0);
        h = 31 * h + (s.budsInCase ? 64 : 0);
        h = 31 * h + String.valueOf(s.modelName).hashCode();
        return h;
    }

    private static String safeDeviceName(BluetoothDevice device) {
        if (device == null) return "";
        try {
            String n = device.getName();
            if (n != null && !n.isEmpty()) return n;
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

    private static String safeAddress(BluetoothDevice device) {
        try {
            String a = device.getAddress();
            return a == null ? "" : a;
        } catch (SecurityException e) {
            return "";
        }
    }

    static boolean hasBlePermissions(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)
                    == PackageManager.PERMISSION_GRANTED
                    && context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    /** Tiny adapter helper keeps the receiver readable. */
    private static final class BluetoothAdapterState {
        static boolean isStateChange(Intent intent) {
            return android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED
                    .equals(intent.getAction());
        }
    }
}
