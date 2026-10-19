package com.openbuds.batterydock;

import android.content.Context;
import android.content.Intent;
import android.os.Build;

/** Small helpers shared by the main screen and the settings page. */
public final class Control {
    private Control() {
    }

    public static void start(Context c, String action) {
        Intent i = new Intent(c, BatteryService.class).setAction(action);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                c.startForegroundService(i);
            } else {
                c.startService(i);
            }
        } catch (RuntimeException ignored) {
        }
    }

    public static void refresh(Context c) {
        start(c, BatteryService.ACTION_REFRESH);
    }

    public static void reconnect(Context c) {
        start(c, BatteryService.ACTION_RECONNECT);
    }

    public static void forget(Context c) {
        start(c, BatteryService.ACTION_FORGET);
    }

    public static void probe(Context c) {
        start(c, BatteryService.ACTION_SPP_PROBE);
    }

    public static void applyEstimate(Context c) {
        start(c, BatteryService.ACTION_APPLY_ESTIMATE);
    }

    public static void appPaused(Context c) {
        Intent i = new Intent(c, BatteryService.class)
                .setAction(BatteryService.ACTION_APP_PAUSED);
        try {
            c.startService(i);
        } catch (RuntimeException ignored) {
        }
    }

    public static void connect(Context c, String mac, String name) {
        Intent i = new Intent(c, BatteryService.class)
                .setAction(BatteryService.ACTION_CONNECT_DEVICE)
                .putExtra(BatteryService.EXTRA_MAC, mac)
                .putExtra(BatteryService.EXTRA_NAME, name == null ? "" : name);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                c.startForegroundService(i);
            } else {
                c.startService(i);
            }
        } catch (RuntimeException ignored) {
        }
    }

    public static void stop(Context c) {
        try {
            c.stopService(new Intent(c, BatteryService.class));
        } catch (RuntimeException ignored) {
        }
    }

    public static void setServiceEnabled(Context c, boolean enabled) {
        AppState.setEnabled(c, enabled);
    }
}
