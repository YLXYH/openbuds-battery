package com.openbuds.batterydock;

/** Pure-Java parser for the validated Buds split battery frame. */
public final class BatteryFrameParser {
    private BatteryFrameParser() {
    }

    public static final class Frame {
        public int left = -1;
        public int right = -1;
        public int caseLevel = -1;
        public boolean leftCharging;
        public boolean rightCharging;
        public boolean caseCharging;
    }

    /**
     * Searches for DD seq 04 0C LL RR CC AA inside a notification payload.
     * Bit 7 of each level byte is the charging flag; the lower 7 bits are the
     * percentage. 0x7F means "not reported".
     */
    public static Frame parseFrame(byte[] value) {
        if (value == null || value.length < 5) return null;
        // Some firmware sends a standalone DD 00 04 0C LL RR CC AA, while
        // BudsFeel MK2 sends the same property inside a larger TLV list:
        //   04 0C LL RR CC
        // Accept both by looking for the property marker, not just the DD frame.
        for (int i = 0; i <= value.length - 5; i++) {
            if ((value[i] & 0xFF) != 0x04) continue;
            if ((value[i + 1] & 0xFF) != 0x0C) continue;

            int leftRaw = value[i + 2] & 0xFF;
            int rightRaw = value[i + 3] & 0xFF;
            int caseRaw = value[i + 4] & 0xFF;
            int left = leftRaw & 0x7F;
            int right = rightRaw & 0x7F;
            int caseLevel = caseRaw & 0x7F;
            if (left > 100 && right > 100 && caseLevel > 100) continue;

            Frame f = new Frame();
            f.left = left;
            f.right = right;
            f.caseLevel = caseLevel;
            f.leftCharging = (leftRaw & 0x80) != 0;
            f.rightCharging = (rightRaw & 0x80) != 0;
            f.caseCharging = (caseRaw & 0x80) != 0;
            // In the observed frames 0 means "not in the ear / not reported",
            // not a real empty battery. Show a dash instead of 0%.
            if (f.left == 0) {
                f.left = -1;
                f.leftCharging = false;
            }
            if (f.right == 0) {
                f.right = -1;
                f.rightCharging = false;
            }
            if (f.caseLevel == 0) {
                f.caseLevel = -1;
                f.caseCharging = false;
            }
            return f;
        }
        return null;
    }

    /** Legacy level-only helper. */
    public static int[] parseSplit(byte[] value) {
        Frame f = parseFrame(value);
        return f == null ? null : new int[]{f.left, f.right, f.caseLevel};
    }
}
