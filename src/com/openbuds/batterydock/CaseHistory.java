package com.openbuds.batterydock;

import android.content.Context;
import android.content.SharedPreferences;

/** Learns separate case-discharge rates for "buds in case" and "buds out". */
public final class CaseHistory {
    private CaseHistory() {
    }

    private static String mode(boolean inCase) {
        return inCase ? "in" : "out";
    }

    private static String key(String mac, boolean inCase, String suffix) {
        return "case_" + mode(inCase) + "_" + suffix + "_"
                + (mac == null ? "" : mac.replace(':', '_'));
    }

    public static void record(Context c, String mac, int level, long at, boolean inCase) {
        if (c == null || mac == null || mac.isEmpty() || level < 0 || level > 100) return;
        SharedPreferences p = c.getSharedPreferences("battery_dock", Context.MODE_PRIVATE);
        int last = p.getInt(key(mac, inCase, "last"), -1);
        long lastAt = p.getLong(key(mac, inCase, "last_at"), 0L);
        float rate = p.getFloat(key(mac, inCase, "rate"), -1f);
        if (last >= 0 && lastAt > 0 && at > lastAt + 120_000L && level <= last) {
            double minutes = (at - lastAt) / 60_000.0;
            float sample = (float) ((last - level) / minutes);
            sample = Math.max(0f, Math.min(0.05f, sample));
            rate = rate < 0f ? sample : rate * 0.7f + sample * 0.3f;
        }
        SharedPreferences.Editor e = p.edit();
        e.putInt(key(mac, inCase, "last"), level);
        e.putLong(key(mac, inCase, "last_at"), at);
        if (rate >= 0f) e.putFloat(key(mac, inCase, "rate"), rate);
        e.apply();
    }

    /** Returns -1 when no learned rate is available for this in-case/out-case mode. */
    public static int estimate(Context c, String mac, int last, long now, boolean inCase) {
        if (c == null || mac == null || last < 0) return -1;
        SharedPreferences p = c.getSharedPreferences("battery_dock", Context.MODE_PRIVATE);
        float rate = p.getFloat(key(mac, inCase, "rate"), -1f);
        long lastAt = p.getLong(key(mac, inCase, "last_at"), 0L);
        if (rate <= 0f || lastAt <= 0 || now <= lastAt) return -1;
        double minutes = (now - lastAt) / 60_000.0;
        int value = (int) Math.round(last - rate * minutes);
        return Math.max(0, Math.min(last, value));
    }

    public static void clear(Context c, String mac) {
        if (c == null || mac == null || mac.isEmpty()) return;
        SharedPreferences p = c.getSharedPreferences("battery_dock", Context.MODE_PRIVATE);
        SharedPreferences.Editor e = p.edit();
        for (boolean inCase : new boolean[]{false, true}) {
            e.remove(key(mac, inCase, "last"))
                    .remove(key(mac, inCase, "last_at"))
                    .remove(key(mac, inCase, "rate"));
        }
        e.apply();
    }
}
