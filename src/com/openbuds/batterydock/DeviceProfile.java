package com.openbuds.batterydock;

import android.content.Context;
import android.content.SharedPreferences;

/** Per-device protocol hints remembered after the first successful connection. */
public final class DeviceProfile {
    private DeviceProfile() {
    }

    private static String key(String mac, String field) {
        return "profile_" + field + "_" + (mac == null ? "" : mac.replace(':', '_'));
    }

    private static SharedPreferences p(Context c) {
        return c.getSharedPreferences("battery_dock", Context.MODE_PRIVATE);
    }

    public static String protocol(Context c, String mac) {
        return p(c).getString(key(mac, "protocol"), "");
    }

    public static void setProtocol(Context c, String mac, String protocol) {
        if (mac == null || mac.isEmpty()) return;
        p(c).edit().putString(key(mac, "protocol"), protocol).apply();
    }

    /** "full" = 29-property query, "min" = single 0C battery query. */
    public static String queryMode(Context c, String mac) {
        return p(c).getString(key(mac, "query"), "");
    }

    public static void setQueryMode(Context c, String mac, String mode) {
        if (mac == null || mac.isEmpty()) return;
        p(c).edit().putString(key(mac, "query"), mode).apply();
    }

    public static void saveBle(Context c, String mac, String service, String write, String notify) {
        if (mac == null || mac.isEmpty()) return;
        p(c).edit()
                .putString(key(mac, "protocol"), "ble")
                .putString(key(mac, "service"), service == null ? "" : service)
                .putString(key(mac, "write"), write == null ? "" : write)
                .putString(key(mac, "notify"), notify == null ? "" : notify)
                .apply();
    }

    public static String bleService(Context c, String mac) {
        return p(c).getString(key(mac, "service"), "");
    }

    public static String bleWrite(Context c, String mac) {
        return p(c).getString(key(mac, "write"), "");
    }

    public static String bleNotify(Context c, String mac) {
        return p(c).getString(key(mac, "notify"), "");
    }
}
