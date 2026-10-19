package com.openbuds.batterydock;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Low-frequency device information and diagnostics, kept out of the main settings page. */
public final class AdvancedActivity extends android.app.Activity implements AppState.Listener {
    private TextView infoModel;
    private TextView infoName;
    private TextView infoAddress;
    private TextView infoProtocol;
    private TextView infoRssi;
    private TextView infoUpdated;
    private int appliedThemeMode;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        appliedThemeMode = Prefs.themeMode(this);
        if (appliedThemeMode == Prefs.THEME_LIGHT) {
            setTheme(R.style.AppTheme_Light);
        } else if (appliedThemeMode == Prefs.THEME_DARK) {
            setTheme(R.style.AppTheme_Dark);
        }
        super.onCreate(savedInstanceState);
        Ui.init(this);
        configureWindow();
        buildUi();
        AppState.init(this);
        AppState.addListener(this);
        refreshUi(AppState.get());
    }

    private void configureWindow() {
        Window w = getWindow();
        w.setStatusBarColor(Ui.INK);
        w.setNavigationBarColor(Ui.INK);
        w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            int flags = 0;
            if (!Ui.dark) {
                flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
                }
            }
            w.getDecorView().setSystemUiVisibility(flags);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (Prefs.themeMode(this) != appliedThemeMode) {
            recreate();
            return;
        }
        refreshUi(AppState.get());
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Ui.INK);
        root.addView(new BackdropView(this), new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(Ui.dp(this, 20), Ui.dp(this, 14), Ui.dp(this, 20), Ui.dp(this, 28));
        scroll.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);

        content.addView(buildHeader(), Ui.topMargin(this, 2));
        content.addView(buildSection("设备信息"), Ui.topMargin(this, 20));
        content.addView(buildInfoCard(), Ui.topMargin(this, 8));
        content.addView(buildSection("连接工具"), Ui.topMargin(this, 20));
        content.addView(buildToolsCard(), Ui.topMargin(this, 8));

        TextView footer = Ui.text(this, "这些选项通常只在排查连接问题时使用", 9.5f, Ui.DIM, false);
        footer.setGravity(Gravity.CENTER);
        content.addView(footer, Ui.topMargin(this, 24));
    }

    private View buildHeader() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = Ui.text(this, "‹", 30f, Ui.COPPER, false);
        back.setGravity(Gravity.CENTER);
        back.setPadding(Ui.dp(this, 4), 0, Ui.dp(this, 10), Ui.dp(this, 2));
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        row.addView(back);
        row.addView(Ui.text(this, "更多设置", 19f, Ui.TEXT, true),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return row;
    }

    private View buildSection(String text) {
        TextView t = Ui.text(this, text, 13f, Ui.TEXT, true);
        t.setLetterSpacing(0.02f);
        return t;
    }

    private LinearLayout buildInfoCard() {
        LinearLayout card = newCard();
        infoModel = addInfoRow(card, "型号", "—", false);
        infoName = addInfoRow(card, "蓝牙名称", "—", false);
        infoAddress = addInfoRow(card, "地址", "—", true);
        infoProtocol = addInfoRow(card, "电量协议", "等待连接", false);
        infoRssi = addInfoRow(card, "信号", "—", false);
        infoUpdated = addInfoRow(card, "最近更新", "—", false);
        return card;
    }

    private LinearLayout newCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Ui.rectStroke(Ui.SURFACE, Ui.STROKE, 20, 1, this));
        card.setPadding(0, Ui.dp(this, 3), 0, Ui.dp(this, 3));
        return card;
    }

    private TextView addInfoRow(LinearLayout card, String label, String value, boolean mono) {
        if (card.getChildCount() > 0) card.addView(Ui.divider(this));
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Ui.dp(this, 14), Ui.dp(this, 10),
                Ui.dp(this, 14), Ui.dp(this, 10));
        row.addView(Ui.text(this, label, 10.5f, Ui.MUTED, false),
                new LinearLayout.LayoutParams(Ui.dp(this, 68), ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView val = Ui.text(this, value, 11f, Ui.TEXT, false);
        val.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        val.setMaxLines(2);
        val.setEllipsize(TextUtils.TruncateAt.END);
        if (mono) val.setTypeface(Typeface.MONOSPACE);
        row.addView(val, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        card.addView(row);
        return val;
    }

    private LinearLayout buildToolsCard() {
        LinearLayout card = newCard();
        addActionRow(card, "手动输入 MAC", "系统列表中看不到时可手动指定地址", "输入",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        showManualMac();
                    }
                });
        addActionRow(card, "复制蓝牙诊断", "反馈连接或电量问题时使用", "复制",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        copyDiagnostics();
                    }
                });
        addActionRow(card, "重新查找耳机", "清除当前记录并重新扫描", "扫描",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        Control.forget(AdvancedActivity.this);
                        Toast.makeText(AdvancedActivity.this, "正在重新查找耳机",
                                Toast.LENGTH_SHORT).show();
                    }
                });
        addActionRow(card, "关闭后台服务", "移除通知栏常驻与后台连接", "关闭",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        confirmStop();
                    }
                });
        return card;
    }

    private void addActionRow(LinearLayout card, String title, String desc, String action,
                              View.OnClickListener listener) {
        if (card.getChildCount() > 0) card.addView(Ui.divider(this));
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Ui.dp(this, 14), Ui.dp(this, 10), Ui.dp(this, 11), Ui.dp(this, 10));
        row.setClickable(true);
        row.setFocusable(true);
        row.setBackground(Ui.ripple(Ui.rect(0x00FFFFFF, 0, this), Ui.cardGlow()));
        row.setOnClickListener(listener);
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(Ui.text(this, title, 12f, Ui.TEXT, true));
        texts.addView(Ui.text(this, desc, 9.5f, Ui.MUTED, false), Ui.topMargin(this, 2));
        row.addView(texts, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView pill = Ui.pill(this, action, Ui.COPPER, 0x1FE7B27A, 0x44E7B27A);
        row.addView(pill);
        card.addView(row);
    }

    private void showManualMac() {
        final EditText input = new EditText(this);
        input.setHint("AA:BB:CC:DD:EE:FF");
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setSingleLine(true);
        input.setTextColor(Ui.TEXT);
        input.setHintTextColor(Ui.DIM);
        new AlertDialog.Builder(this)
                .setTitle("手动输入 MAC")
                .setView(input)
                .setPositiveButton("连接", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        String mac = input.getText().toString().trim().toUpperCase(Locale.US);
                        if (!mac.matches("^([0-9A-F]{2}:){5}[0-9A-F]{2}$")) {
                            Toast.makeText(AdvancedActivity.this,
                                    "格式应为 AA:BB:CC:DD:EE:FF", Toast.LENGTH_LONG).show();
                            return;
                        }
                        Control.connect(AdvancedActivity.this, mac, "");
                        Toast.makeText(AdvancedActivity.this, "正在连接 " + mac,
                                Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void copyDiagnostics() {
        BatterySnapshot s = AppState.get();
        String text = "Buds Battery Dock v2.0.1\n"
                + "型号: " + s.modelName + "\n"
                + "蓝牙名: " + s.bleName + "\n"
                + "地址: " + s.address + "\n"
                + "协议: " + s.protocol + "\n"
                + "状态: " + s.statusTitle + " / " + s.statusDetail + "\n"
                + "电量: L=" + s.left + " R=" + s.right + " C=" + s.caseLevel
                + " T=" + s.total + "\n"
                + "仓状态: " + (s.caseEstimated ? "估算" : s.caseStale ? "关盖前" : "实时") + "\n"
                + "--- 蓝牙诊断 ---\n" + DiagLog.dump();
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("Buds电量诊断", text));
        Toast.makeText(this, "诊断信息已复制", Toast.LENGTH_LONG).show();
    }

    private void confirmStop() {
        new AlertDialog.Builder(this)
                .setTitle("关闭后台服务")
                .setMessage("关闭后通知栏电量会消失，应用也不再自动连接耳机。")
                .setPositiveButton("关闭", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        AppState.setEnabled(AdvancedActivity.this, false);
                        BatterySnapshot s = AppState.get();
                        s.connected = false;
                        s.connecting = false;
                        s.scanning = false;
                        s.left = -1;
                        s.right = -1;
                        s.caseLevel = -1;
                        s.caseActual = -1;
                        s.total = -1;
                        s.caseStale = false;
                        s.caseEstimated = false;
                        s.updatedAt = 0L;
                        s.statusTitle = "后台服务已关闭";
                        s.statusDetail = "可在设置页重新开启";
                        AppState.set(s);
                        Control.stop(AdvancedActivity.this);
                        Toast.makeText(AdvancedActivity.this, "后台服务已关闭",
                                Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void refreshUi(BatterySnapshot s) {
        if (infoModel == null) return;
        infoModel.setText(TextUtils.isEmpty(s.modelName) ? "—" : s.modelName);
        infoName.setText(TextUtils.isEmpty(s.bleName) ? "—" : s.bleName);
        infoAddress.setText(TextUtils.isEmpty(s.address) ? "—" : s.address);
        infoProtocol.setText(TextUtils.isEmpty(s.protocol) ? "—" : s.protocol);
        infoRssi.setText(s.rssi < 0 ? s.rssi + " dBm" : "—");
        infoUpdated.setText(s.updatedAt <= 0 ? "—"
                : new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date(s.updatedAt)));
    }

    @Override
    public void onState(BatterySnapshot snapshot) {
        refreshUi(snapshot);
    }

    @Override
    public void onDestroy() {
        AppState.removeListener(this);
        super.onDestroy();
    }
}
