package com.openbuds.batterydock;

import android.app.Activity;
import android.app.Dialog;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/** Spacious, audio-device-only picker. Avoids the cramped system AlertDialog list. */
public final class DevicePicker {
    public interface Listener {
        void onSelected(String mac, String name);
    }

    private DevicePicker() {
    }

    public static void show(final Activity activity, final Listener listener) {
        BluetoothManager manager =
                (BluetoothManager) activity.getSystemService(Context.BLUETOOTH_SERVICE);
        if (manager == null || manager.getAdapter() == null) {
            return;
        }
        final List<BluetoothDevice> devices = new ArrayList<>();
        try {
            Set<BluetoothDevice> bonded = manager.getAdapter().getBondedDevices();
            if (bonded != null) {
                for (BluetoothDevice d : bonded) {
                    if (isAudioDevice(d)) devices.add(d);
                }
            }
            if (devices.isEmpty() && bonded != null) devices.addAll(bonded);
        } catch (SecurityException ignored) {
            return;
        }
        Collections.sort(devices, new Comparator<BluetoothDevice>() {
            @Override
            public int compare(BluetoothDevice a, BluetoothDevice b) {
                boolean ra = DeviceCatalog.looksLikeRose(safeName(a));
                boolean rb = DeviceCatalog.looksLikeRose(safeName(b));
                if (ra != rb) return ra ? -1 : 1;
                return safeName(a).compareToIgnoreCase(safeName(b));
            }
        });

        final Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(Ui.dp(activity, 18), Ui.dp(activity, 16),
                Ui.dp(activity, 18), Ui.dp(activity, 16));
        root.setBackground(Ui.rectStroke(Ui.SURFACE, Ui.STROKE, 24, 1, activity));

        LinearLayout titleRow = new LinearLayout(activity);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = Ui.text(activity, "选择耳机", 17f, Ui.TEXT, true);
        titleRow.addView(title, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView close = Ui.text(activity, "✕", 15f, Ui.MUTED, false);
        close.setGravity(Gravity.CENTER);
        close.setPadding(Ui.dp(activity, 8), Ui.dp(activity, 4),
                Ui.dp(activity, 8), Ui.dp(activity, 4));
        close.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
            }
        });
        titleRow.addView(close);
        root.addView(titleRow);

        TextView hint = Ui.text(activity, "只显示已配对的音频设备", 9.5f, Ui.DIM, false);
        root.addView(hint, topMargin(activity, 3));

        if (devices.isEmpty()) {
            TextView empty = Ui.text(activity, "没有找到已配对的耳机\n请先在系统蓝牙中配对", 11f, Ui.MUTED, false);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, Ui.dp(activity, 28), 0, Ui.dp(activity, 28));
            root.addView(empty);
        } else {
            ScrollView scroll = new ScrollView(activity);
            scroll.setVerticalScrollBarEnabled(false);
            LinearLayout list = new LinearLayout(activity);
            list.setOrientation(LinearLayout.VERTICAL);
            list.setPadding(0, Ui.dp(activity, 8), 0, 0);
            for (final BluetoothDevice d : devices) {
                final String name = safeName(d);
                final String mac = d.getAddress();
                LinearLayout row = new LinearLayout(activity);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(Ui.dp(activity, 14), Ui.dp(activity, 12),
                        Ui.dp(activity, 12), Ui.dp(activity, 12));
                row.setBackground(Ui.ripple(Ui.rectStroke(0x0AFFFFFF, Ui.STROKE,
                        16, 1, activity), Ui.cardGlow()));

                LinearLayout texts = new LinearLayout(activity);
                texts.setOrientation(LinearLayout.VERTICAL);
                TextView n = Ui.text(activity,
                        TextUtils.isEmpty(name) ? "未命名设备" : name, 13f, Ui.TEXT, true);
                n.setMaxLines(1);
                n.setEllipsize(TextUtils.TruncateAt.END);
                TextView m = Ui.text(activity, mac, 9.5f, Ui.MUTED, false);
                m.setTypeface(android.graphics.Typeface.MONOSPACE);
                texts.addView(n);
                texts.addView(m, topMargin(activity, 2));
                row.addView(texts, new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

                TextView chevron = Ui.text(activity, "›", 20f, Ui.DIM, false);
                row.addView(chevron);
                row.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        dialog.dismiss();
                        listener.onSelected(mac, name);
                    }
                });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.bottomMargin = Ui.dp(activity, 10);
                list.addView(row, lp);
            }
            scroll.addView(list);
            int maxHeight = Ui.dp(activity, 400);
            root.addView(scroll, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, Math.min(maxHeight,
                    Math.max(Ui.dp(activity, 150), devices.size() * Ui.dp(activity, 82)))));
        }

        dialog.setContentView(root);
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setGravity(Gravity.CENTER);
            DisplayMetrics dm = activity.getResources().getDisplayMetrics();
            window.setLayout(Math.max(Ui.dp(activity, 280), dm.widthPixels - Ui.dp(activity, 48)),
                    WindowManager.LayoutParams.WRAP_CONTENT);
        }
    }

    private static boolean isAudioDevice(BluetoothDevice device) {
        try {
            BluetoothClass clazz = device.getBluetoothClass();
            return clazz != null
                    && clazz.getMajorDeviceClass() == BluetoothClass.Device.Major.AUDIO_VIDEO;
        } catch (SecurityException e) {
            return false;
        }
    }

    private static String safeName(BluetoothDevice device) {
        if (device == null) return "";
        try {
            String name = device.getName();
            if (name != null && !name.isEmpty()) return name;
        } catch (SecurityException ignored) {
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            try {
                String alias = device.getAlias();
                if (alias != null && !alias.isEmpty()) return alias;
            } catch (Exception ignored) {
            }
        }
        return "";
    }

    private static LinearLayout.LayoutParams topMargin(Context c, int dp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(c, dp);
        return lp;
    }
}
