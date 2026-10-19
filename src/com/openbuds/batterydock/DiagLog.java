package com.openbuds.batterydock;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

/** Small in-memory diagnostic ring. It never leaves the device unless the user copies it. */
public final class DiagLog {
    private static final int MAX_LINES = 120;
    private static final int DUMP_LINES = 35;
    private static final ArrayDeque<String> LINES = new ArrayDeque<>();
    private static final SimpleDateFormat TIME =
            new SimpleDateFormat("HH:mm:ss", Locale.CHINA);

    private DiagLog() {
    }

    public static void add(String line) {
        if (line == null) return;
        synchronized (LINES) {
            LINES.addLast(TIME.format(new Date()) + "  " + line);
            while (LINES.size() > MAX_LINES) LINES.removeFirst();
        }
    }

    public static String dump() {
        synchronized (LINES) {
            StringBuilder sb = new StringBuilder();
            int skip = Math.max(0, LINES.size() - DUMP_LINES);
            int i = 0;
            for (String line : LINES) {
                if (i++ >= skip) sb.append(line).append('\n');
            }
            return sb.toString();
        }
    }

    public static void clear() {
        synchronized (LINES) {
            LINES.clear();
        }
    }
}
