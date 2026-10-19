package com.openbuds.batterydock;

import android.content.Context;

/** Small preference helpers (poll interval lives in the same file as the saved device). */
public final class Prefs {
    public static final int DEFAULT_QUERY_SECONDS = 120;
    public static final int THEME_SYSTEM = 0;
    public static final int THEME_LIGHT = 1;
    public static final int THEME_DARK = 2;
    private static final String KEY_QUERY_INTERVAL = "query_interval_seconds";
    private static final String KEY_IDLE_DISCONNECT = "idle_disconnect_enabled";
    private static final String KEY_THEME = "theme_mode";
    private static final String KEY_CASE_ESTIMATE = "case_estimate_enabled";

    private Prefs() {
    }

    public static int queryIntervalSeconds(Context c) {
        int v = c.getSharedPreferences("battery_dock", Context.MODE_PRIVATE)
                .getInt(KEY_QUERY_INTERVAL, DEFAULT_QUERY_SECONDS);
        return v == 60 || v == 120 || v == 300 ? v : DEFAULT_QUERY_SECONDS;
    }

    public static void setQueryIntervalSeconds(Context c, int seconds) {
        if (seconds != 60 && seconds != 120 && seconds != 300) seconds = DEFAULT_QUERY_SECONDS;
        c.getSharedPreferences("battery_dock", Context.MODE_PRIVATE)
                .edit().putInt(KEY_QUERY_INTERVAL, seconds).apply();
    }

    public static String queryLabel(Context c) {
        return queryIntervalSeconds(c) / 60 + " 分钟";
    }

    /** Default on: disconnect the earbuds after 10 minutes with the screen off. */
    public static boolean idleDisconnectEnabled(Context c) {
        return c.getSharedPreferences("battery_dock", Context.MODE_PRIVATE)
                .getBoolean(KEY_IDLE_DISCONNECT, true);
    }

    public static void setIdleDisconnectEnabled(Context c, boolean enabled) {
        c.getSharedPreferences("battery_dock", Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_IDLE_DISCONNECT, enabled).apply();
    }

    public static int themeMode(Context c) {
        int v = c.getSharedPreferences("battery_dock", Context.MODE_PRIVATE)
                .getInt(KEY_THEME, THEME_SYSTEM);
        return v == THEME_LIGHT || v == THEME_DARK ? v : THEME_SYSTEM;
    }

    public static void setThemeMode(Context c, int mode) {
        if (mode != THEME_LIGHT && mode != THEME_DARK) mode = THEME_SYSTEM;
        c.getSharedPreferences("battery_dock", Context.MODE_PRIVATE)
                .edit().putInt(KEY_THEME, mode).apply();
    }

    public static String themeLabel(Context c) {
        switch (themeMode(c)) {
            case THEME_LIGHT:
                return "浅色";
            case THEME_DARK:
                return "深色";
            default:
                return "跟随系统";
        }
    }

    /** Off by default; the estimate is clearly marked in UI and notification. */
    public static boolean caseEstimateEnabled(Context c) {
        return c.getSharedPreferences("battery_dock", Context.MODE_PRIVATE)
                .getBoolean(KEY_CASE_ESTIMATE, false);
    }

    public static void setCaseEstimateEnabled(Context c, boolean enabled) {
        c.getSharedPreferences("battery_dock", Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_CASE_ESTIMATE, enabled).apply();
    }
}
