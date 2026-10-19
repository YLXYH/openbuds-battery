package com.openbuds.batterydock;

/** Immutable-ish UI state shared by the foreground service and the activity. */
public final class BatterySnapshot {
    public boolean connected;
    public boolean connecting;
    public boolean scanning;
    public boolean bluetoothOff;
    public boolean permissionMissing;
    public boolean serviceRunning = true;

    public String modelName = "耳机";
    public String bleName = "";
    public String address = "";
    public String protocol = "等待连接";
    public String statusTitle = "等待连接";
    public String statusDetail = "请打开耳机仓盖，靠近手机";
    public String error = "";

    public int left = -1;
    public int right = -1;
    public int caseLevel = -1;
    public int caseActual = -1;
    public int total = -1;
    public boolean leftCharging;
    public boolean rightCharging;
    public boolean caseCharging;
    public boolean totalCharging;
    public boolean caseStale;
    public boolean caseEstimated;
    public boolean budsInCase;
    public int rssi = 0;
    public long updatedAt = 0L;

    public BatterySnapshot() {
    }

    public BatterySnapshot(BatterySnapshot o) {
        connected = o.connected;
        connecting = o.connecting;
        scanning = o.scanning;
        bluetoothOff = o.bluetoothOff;
        permissionMissing = o.permissionMissing;
        serviceRunning = o.serviceRunning;
        modelName = o.modelName;
        bleName = o.bleName;
        address = o.address;
        protocol = o.protocol;
        statusTitle = o.statusTitle;
        statusDetail = o.statusDetail;
        error = o.error;
        left = o.left;
        right = o.right;
        caseLevel = o.caseLevel;
        caseActual = o.caseActual;
        total = o.total;
        leftCharging = o.leftCharging;
        rightCharging = o.rightCharging;
        caseCharging = o.caseCharging;
        totalCharging = o.totalCharging;
        caseStale = o.caseStale;
        caseEstimated = o.caseEstimated;
        budsInCase = o.budsInCase;
        rssi = o.rssi;
        updatedAt = o.updatedAt;
    }

    public BatterySnapshot copy() {
        return new BatterySnapshot(this);
    }

    public boolean hasSplit() {
        return left >= 0 || right >= 0;
    }

    public boolean hasCase() {
        return caseLevel >= 0 && caseLevel <= 100;
    }

    public boolean isAnyCharging() {
        return leftCharging || rightCharging || caseCharging || totalCharging;
    }

    public int headlineLevel() {
        if (left >= 0 && right >= 0) return Math.min(left, right);
        if (left >= 0) return left;
        if (right >= 0) return right;
        if (total >= 0) return total;
        return -1;
    }

    public int lowestVisible() {
        int v = 101;
        if (left >= 0) v = Math.min(v, left);
        if (right >= 0) v = Math.min(v, right);
        if (hasCase()) v = Math.min(v, caseLevel);
        if (v == 101) v = total;
        return v;
    }
}
