package com.openbuds.batterydock;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/** Restores the ongoing notification after a reboot when the user left it enabled. */
public final class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) return;
        if (!AppState.isEnabled(context)) return;
        if (!BatteryService.hasBlePermissions(context)) return;
        Intent service = new Intent(context, BatteryService.class)
                .setAction(BatteryService.ACTION_START);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(service);
            } else {
                context.startService(service);
            }
        } catch (RuntimeException ignored) {
            // Some OEMs block FGS starts from BOOT_COMPLETED. Opening the app still works.
        }
    }
}
