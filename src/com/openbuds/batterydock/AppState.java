package com.openbuds.batterydock;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.CopyOnWriteArrayList;

/** Single source of truth. Writes are cheap and listeners are always called on the main thread. */
public final class AppState {
    public interface Listener {
        void onState(BatterySnapshot snapshot);
    }

    private static final Object LOCK = new Object();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final CopyOnWriteArrayList<Listener> LISTENERS = new CopyOnWriteArrayList<>();
    private static BatterySnapshot current = new BatterySnapshot();
    private static SharedPreferences prefs;
    private static long lastPersistAt;

    private AppState() {
    }

    public static void init(Context context) {
        if (prefs != null) return;
        synchronized (LOCK) {
            if (prefs != null) return;
            prefs = context.getApplicationContext()
                    .getSharedPreferences("battery_dock", Context.MODE_PRIVATE);
            loadLocked();
        }
    }

    private static void loadLocked() {
        BatterySnapshot s = new BatterySnapshot();
        s.modelName = prefs.getString("model", s.modelName);
        s.bleName = prefs.getString("ble_name", "");
        s.address = prefs.getString("address", "");
        s.protocol = prefs.getString("protocol", "等待连接");
        s.left = prefs.getInt("left", -1);
        s.right = prefs.getInt("right", -1);
        s.caseLevel = prefs.getInt("case", -1);
        s.caseActual = prefs.getInt("case_actual", -1);
        s.caseStale = prefs.getBoolean("case_stale", false);
        s.caseEstimated = prefs.getBoolean("case_estimated", false);
        s.budsInCase = prefs.getBoolean("buds_in_case", false);
        s.total = prefs.getInt("total", -1);
        s.rssi = prefs.getInt("rssi", 0);
        s.updatedAt = prefs.getLong("updated_at", 0L);
        s.connected = false;
        s.statusTitle = "等待连接";
        s.statusDetail = s.address.isEmpty() ? "请打开耳机仓盖，靠近手机" : "正在恢复上一次的连接";
        current = s;
    }

    public static BatterySnapshot get() {
        synchronized (LOCK) {
            return current.copy();
        }
    }

    public static void set(BatterySnapshot next) {
        BatterySnapshot copy = next.copy();
        synchronized (LOCK) {
            BatterySnapshot old = current;
            current = copy;
            boolean changed = !copy.modelName.equals(old.modelName)
                    || !copy.bleName.equals(old.bleName)
                    || !copy.address.equals(old.address)
                    || !copy.protocol.equals(old.protocol)
                    || copy.left != old.left
                    || copy.right != old.right
                    || copy.caseLevel != old.caseLevel
                    || copy.caseActual != old.caseActual
                    || copy.caseStale != old.caseStale
                    || copy.caseEstimated != old.caseEstimated
                    || copy.budsInCase != old.budsInCase
                    || copy.total != old.total
                    || copy.rssi != old.rssi
                    || copy.updatedAt != old.updatedAt;
            long now = System.currentTimeMillis();
            if (prefs != null && (changed || now - lastPersistAt > 60_000L)) {
                lastPersistAt = now;
                SharedPreferences.Editor e = prefs.edit();
                e.putString("model", copy.modelName);
                e.putString("ble_name", copy.bleName);
                e.putString("address", copy.address);
                e.putString("protocol", copy.protocol);
                e.putInt("left", copy.left);
                e.putInt("right", copy.right);
                e.putInt("case", copy.caseLevel);
                e.putInt("case_actual", copy.caseActual);
                e.putBoolean("case_stale", copy.caseStale);
                e.putBoolean("case_estimated", copy.caseEstimated);
                e.putBoolean("buds_in_case", copy.budsInCase);
                e.putInt("total", copy.total);
                e.putInt("rssi", copy.rssi);
                e.putLong("updated_at", copy.updatedAt);
                e.apply();
            }
        }
        final BatterySnapshot delivered = copy;
        for (final Listener listener : LISTENERS) {
            MAIN.post(new Runnable() {
                @Override
                public void run() {
                    listener.onState(delivered);
                }
            });
        }
    }

    public static void addListener(Listener listener) {
        if (!LISTENERS.contains(listener)) LISTENERS.add(listener);
        final BatterySnapshot snapshot = get();
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                listener.onState(snapshot);
            }
        });
    }

    public static void removeListener(Listener listener) {
        LISTENERS.remove(listener);
    }

    public static boolean isEnabled(Context context) {
        return context.getSharedPreferences("battery_dock", Context.MODE_PRIVATE)
                .getBoolean("enabled", true);
    }

    public static void setEnabled(Context context, boolean enabled) {
        context.getSharedPreferences("battery_dock", Context.MODE_PRIVATE)
                .edit().putBoolean("enabled", enabled).apply();
    }

    public static String getSavedMac(Context context) {
        return context.getSharedPreferences("battery_dock", Context.MODE_PRIVATE)
                .getString("address", "");
    }

    public static void clearSavedMac(Context context) {
        context.getSharedPreferences("battery_dock", Context.MODE_PRIVATE)
                .edit().remove("address").remove("ble_name").apply();
    }
}
