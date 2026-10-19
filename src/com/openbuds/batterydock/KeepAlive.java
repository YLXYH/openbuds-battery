package com.openbuds.batterydock;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;

/** One-tap shortcuts for the OEM background-management screens that matter on OPPO/vivo. */
public final class KeepAlive {
    private KeepAlive() {
    }

    public static boolean isIgnoringBatteryOptimizations(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true;
        PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        return pm != null && pm.isIgnoringBatteryOptimizations(context.getPackageName());
    }

    public static void requestIgnoreBatteryOptimizations(Activity activity) {
        if (activity == null) return;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return;
        try {
            Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            intent.setData(Uri.parse("package:" + activity.getPackageName()));
            activity.startActivity(intent);
            return;
        } catch (RuntimeException ignored) {
        }
        openBatterySettings(activity);
    }

    public static void openBatterySettings(Activity activity) {
        try {
            Intent intent = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
            activity.startActivity(intent);
        } catch (RuntimeException e) {
            openAppDetails(activity);
        }
    }

    public static void openNotificationSettings(Activity activity) {
        try {
            Intent intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
            intent.putExtra(Settings.EXTRA_APP_PACKAGE, activity.getPackageName());
            activity.startActivity(intent);
        } catch (RuntimeException e) {
            openAppDetails(activity);
        }
    }

    public static void openAppDetails(Activity activity) {
        try {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.parse("package:" + activity.getPackageName()));
            activity.startActivity(intent);
        } catch (RuntimeException ignored) {
        }
    }

    /** Tries the known ColorOS / OriginOS autostart pages, then falls back to app details. */
    public static void openAutostart(Activity activity) {
        String[][] pages = new String[][]{
                {"com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"},
                {"com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"},
                {"com.oplus.safecenter", "com.oplus.safecenter.permission.startup.StartupAppListActivity"},
                {"com.oplus.safecenter", "com.oplus.safecenter.startupapp.StartupAppListActivity"},
                {"com.coloros.oppoguardelf", "com.coloros.powermanager.fuelgaue.PowerUsageModelActivity"},
                {"com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"},
                {"com.iqoo.secure", "com.iqoo.secure.safeguard.PurviewTabActivity"},
                {"com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"},
                {"com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"},
                {"com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"},
                {"com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"},
                {"com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"},
                {"com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"}
        };
        for (String[] page : pages) {
            try {
                Intent intent = new Intent();
                intent.setComponent(new ComponentName(page[0], page[1]));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                activity.startActivity(intent);
                return;
            } catch (RuntimeException ignored) {
            }
        }
        openAppDetails(activity);
    }

    public static String manufacturerHint() {
        String maker = Build.MANUFACTURER == null ? "" : Build.MANUFACTURER.toLowerCase();
        if (maker.contains("oppo") || maker.contains("oneplus") || maker.contains("realme")) {
            return "ColorOS：设置 > 电池 > 应用耗电管理，允许「Buds Battery Dock」后台运行并开启自启动。";
        }
        if (maker.contains("vivo") || maker.contains("iqoo")) {
            return "OriginOS：设置 > 电池 > 后台耗电管理，允许「Buds Battery Dock」后台高耗电；并在 i 管家 > 自启动中开启。";
        }
        return "请在系统设置中允许本应用自启动，并把电池策略设为「不限制」。";
    }
}
