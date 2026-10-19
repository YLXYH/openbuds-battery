package com.openbuds.batterydock;

import android.Manifest;
import android.app.AlertDialog;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Single screen, hand-drawn dashboard. No AndroidX, no network permission, no ads.
 * The activity is excluded from recents while the foreground service keeps the
 * notification alive after the task is swiped away.
 */
public final class MainActivity extends android.app.Activity implements AppState.Listener {
    private static final int REQUEST_PERMISSIONS = 41;

    private GaugeView gauge;
    private BatteryCellView cellLeft;
    private BatteryCellView cellRight;
    private BatteryCellView cellCase;
    private TextView statusChip;
    private TextView titleName;
    private TextView bleNameText;
    private TextView statusText;
    private TextView detailText;
    private TextView protocolChip;
    private TextView rssiChip;

    private int bannerMode;
    private TextView warningButton;
    private String warningTitle = "";
    private String warningDetail = "";
    private String warningAction = "";

    private TextView notifAction;
    private TextView themeAction;
    private TextView intervalAction;
    private ToggleView serviceSwitch;
    private ToggleView idleSwitch;
    private ToggleView estimateSwitch;
    private boolean updatingSwitch;
    private boolean updatingIdleSwitch;
    private boolean updatingEstimateSwitch;
    private FrameLayout pageHost;
    private ScrollView homePage;
    private ScrollView settingsPage;
    private BottomNav bottomNav;
    private int currentPage;
    private static final String STATE_PAGE = "page";

    private boolean permissionRequested;
    private long lastAutoRequestAt;
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
        if (savedInstanceState != null) {
            currentPage = savedInstanceState.getInt(STATE_PAGE, 0);
        }
        AppState.init(this);
        Ui.init(this);
        configureWindow();
        buildUi();
        AppState.addListener(this);

        if (AppState.isEnabled(this) && BatteryService.hasBlePermissions(this)) {
            startBatteryService(BatteryService.ACTION_START);
        }
        getWindow().getDecorView().postDelayed(new Runnable() {
            @Override
            public void run() {
                if (isFinishing()) return;
                if (!hasAllPermissions()) requestNeededPermissions(false);
            }
        }, 650L);
    }

    private void configureWindow() {
        Window w = getWindow();
        w.setStatusBarColor(Ui.INK);
        w.setNavigationBarColor(Ui.INK);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            w.setNavigationBarDividerColor(Ui.INK);
        }
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
        if (AppState.isEnabled(this) && BatteryService.hasBlePermissions(this)) {
            startBatteryService(BatteryService.ACTION_START);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (AppState.isEnabled(this)) {
            Control.appPaused(this);
        }
    }

    @Override
    protected void onDestroy() {
        AppState.removeListener(this);
        super.onDestroy();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(STATE_PAGE, currentPage);
    }

    @Override
    public void onBackPressed() {
        moveTaskToBack(true);
    }

    @Override
    public void onState(BatterySnapshot snapshot) {
        refreshUi(snapshot);
    }

    // ------------------------------------------------------------------ layout

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Ui.INK);
        root.addView(new BackdropView(this),
                new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));

        pageHost = new FrameLayout(this);
        pageHost.setClipChildren(true);
        root.addView(pageHost, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        homePage = buildHomePage();
        settingsPage = buildSettingsPage();
        pageHost.addView(homePage, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        pageHost.addView(settingsPage, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        settingsPage.setVisibility(View.GONE);

        setContentView(root);

        TextView fixedFooter = Ui.text(this, "非官方工具 · 不联网  v2.0.1", 9f, Ui.DIM, false);
        fixedFooter.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams footerLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 22));
        footerLp.gravity = Gravity.BOTTOM;
        footerLp.bottomMargin = Ui.dp(this, 70);
        root.addView(fixedFooter, footerLp);

        bottomNav = BottomNav.attach(this, root, currentPage, new BottomNav.Listener() {
            @Override
            public void onTabSelected(int index) {
                showPage(index, true);
            }
        });
        showPage(currentPage, false);
    }

    private ScrollView buildHomePage() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(Ui.dp(this, 20), Ui.dp(this, 14), Ui.dp(this, 20), Ui.dp(this, 118));
        scroll.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        content.addView(buildHeader(), topMargin(2));
        content.addView(buildHero(), topMargin(18));
        content.addView(buildActions(), topMargin(10));
        content.addView(buildSection("耳机电量", "实时同步"), topMargin(24));
        content.addView(buildCells(), topMargin(10));

        warningButton = Ui.text(this, "!", 13f, Ui.COPPER, true);
        warningButton.setGravity(Gravity.CENTER);
        warningButton.setContentDescription("查看提醒");
        warningButton.setBackground(Ui.rectStroke(0x1FE7B27A, 0x66E7B27A, 999, 1, this));
        warningButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showWarningDialog();
            }
        });
        warningButton.setVisibility(View.GONE);
        LinearLayout.LayoutParams warningLp = new LinearLayout.LayoutParams(
                Ui.dp(this, 26), Ui.dp(this, 26));
        warningLp.topMargin = Ui.dp(this, 12);
        warningLp.gravity = Gravity.CENTER_HORIZONTAL;
        content.addView(warningButton, warningLp);
        return scroll;
    }

    private ScrollView buildSettingsPage() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(Ui.dp(this, 20), Ui.dp(this, 14), Ui.dp(this, 20), Ui.dp(this, 118));
        scroll.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        content.addView(buildSettingsHeader(), topMargin(2));
        content.addView(buildSection("常用", "外观与电量"), topMargin(18));
        content.addView(buildSettingsCard(), topMargin(8));
        content.addView(buildSection("后台运行", "权限与省电"), topMargin(20));
        content.addView(buildBackgroundCard(), topMargin(8));
        content.addView(buildAdvancedCard(), topMargin(20));
        TextView footer = Ui.text(this, "非官方工具 · 数据仅在本机处理 · 不联网", 9.5f, Ui.DIM, false);
        footer.setGravity(Gravity.CENTER);
        content.addView(footer, topMargin(24));
        return scroll;
    }

    private View buildSettingsHeader() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.app_mark);
        logo.setScaleType(ImageView.ScaleType.CENTER_CROP);
        logo.setBackground(Ui.rectStroke(0xFFFFFFFF, Ui.STROKE, 11, 1, this));
        logo.setClipToOutline(true);
        row.addView(logo, new LinearLayout.LayoutParams(Ui.dp(this, 38), Ui.dp(this, 38)));
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.leftMargin = Ui.dp(this, 11);
        row.addView(titles, lp);
        titles.addView(Ui.text(this, "设置", 18f, Ui.TEXT, true));
        TextView sub = Ui.text(this, "偏好  ·  设备  ·  通知", 9f, Ui.MUTED, false);
        sub.setLetterSpacing(0.08f);
        titles.addView(sub, topMargin(2));
        return row;
    }

    private void showPage(int page, boolean animate) {
        page = page == 1 ? 1 : 0;
        ScrollView in = page == 0 ? homePage : settingsPage;
        ScrollView out = page == 0 ? settingsPage : homePage;
        int width = pageHost.getWidth();
        if (width <= 0) width = getResources().getDisplayMetrics().widthPixels;
        currentPage = page;
        if (bottomNav != null) bottomNav.setSelected(page);
        in.animate().cancel();
        out.animate().cancel();
        if (!animate) {
            in.setVisibility(View.VISIBLE);
            in.setTranslationX(0);
            out.setVisibility(View.GONE);
            out.setTranslationX(0);
            return;
        }
        if (in.getVisibility() == View.VISIBLE && in.getTranslationX() == 0f
                && out.getVisibility() == View.GONE) {
            return;
        }
        in.setVisibility(View.VISIBLE);
        in.setTranslationX(page == 0 ? -width : width);
        in.animate().translationX(0f).setDuration(230L).start();
        out.setVisibility(View.VISIBLE);
        out.animate().translationX(page == 0 ? width : -width).setDuration(230L)
                .withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        out.setVisibility(View.GONE);
                        out.setTranslationX(0f);
                    }
                }).start();
    }

    private View buildHeader() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.app_mark);
        logo.setScaleType(ImageView.ScaleType.CENTER_CROP);
        logo.setBackground(Ui.rectStroke(0xFFFFFFFF, Ui.STROKE, 12, 1, this));
        logo.setClipToOutline(true);
        logo.setContentDescription("Buds电量");
        row.addView(logo, new LinearLayout.LayoutParams(
                Ui.dp(this, 42), Ui.dp(this, 42)));

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams titlesLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        titlesLp.leftMargin = Ui.dp(this, 11);
        row.addView(titles, titlesLp);
        titleName = Ui.text(this, "耳机", 18f, Ui.TEXT, true);
        titleName.setMaxLines(1);
        titleName.setEllipsize(TextUtils.TruncateAt.END);
        titleName.setLetterSpacing(0.01f);
        titles.addView(titleName);
        TextView sub = Ui.text(this, "电量坞  ·  BATTERY DOCK", 9f, Ui.MUTED, false);
        sub.setLetterSpacing(0.16f);
        titles.addView(sub, topMargin(2));

        statusChip = Ui.pill(this, "● 等待连接", Ui.MUTED, 0x14141A22, 0x332A333F);
        statusChip.setContentDescription("连接状态");
        row.addView(statusChip);
        return row;
    }

    private View buildHero() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(Ui.dp(this, 14), Ui.dp(this, 14),
                Ui.dp(this, 16), Ui.dp(this, 14));
        card.setBackground(Ui.rectStroke(Ui.SURFACE, Ui.STROKE, 24, 1, this));
        card.setElevation(Ui.dp(this, 2));

        gauge = new GaugeView(this);
        card.addView(gauge, new LinearLayout.LayoutParams(Ui.dp(this, 128), Ui.dp(this, 128)));

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams infoLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        infoLp.leftMargin = Ui.dp(this, 15);
        card.addView(info, infoLp);

        bleNameText = Ui.text(this, "蓝牙名称 —", 10.5f, Ui.MUTED, false);
        bleNameText.setMaxLines(1);
        bleNameText.setEllipsize(TextUtils.TruncateAt.END);
        info.addView(bleNameText);

        LinearLayout statusRow = new LinearLayout(this);
        statusRow.setOrientation(LinearLayout.HORIZONTAL);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);
        View dot = new View(this);
        dot.setBackground(Ui.rect(0xFF5C6675, 4, this));
        statusRow.addView(dot, new LinearLayout.LayoutParams(Ui.dp(this, 7), Ui.dp(this, 7)));
        statusText = Ui.text(this, "等待连接", 12f, Ui.COPPER, true);
        LinearLayout.LayoutParams statusLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        statusLp.leftMargin = Ui.dp(this, 6);
        statusRow.addView(statusText, statusLp);
        info.addView(statusRow, topMargin(10));

        detailText = Ui.text(this, "请打开耳机仓盖，靠近手机", 10f, Ui.MUTED, false);
        detailText.setMaxLines(2);
        detailText.setLineSpacing(Ui.dp(this, 2), 1f);
        info.addView(detailText, topMargin(3));

        LinearLayout chips = new LinearLayout(this);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        protocolChip = Ui.pill(this, "BLE", Ui.COPPER, 0x14E7B27A, 0x33E7B27A);
        chips.addView(protocolChip);
        rssiChip = Ui.pill(this, "信号 —", Ui.MUTED, 0x0F8B94A3, 0x1F8B94A3);
        LinearLayout.LayoutParams rssiLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rssiLp.leftMargin = Ui.dp(this, 6);
        chips.addView(rssiChip, rssiLp);
        info.addView(chips, topMargin(9));
        return card;
    }

    private View buildActions() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);

        TextView refresh = Ui.text(this, "立即刷新", 13f, 0xFF17120C, true);
        refresh.setGravity(Gravity.CENTER);
        refresh.setClickable(true);
        refresh.setFocusable(true);
        refresh.setBackground(Ui.ripple(Ui.rect(Ui.COPPER, 16, this), 0x55FFFFFF));
        refresh.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onRefreshClicked();
            }
        });
        row.addView(refresh, new LinearLayout.LayoutParams(0, Ui.dp(this, 48), 1f));

        TextView find = Ui.text(this, "切换耳机", 12f, Ui.TEXT, true);
        find.setGravity(Gravity.CENTER);
        find.setClickable(true);
        find.setFocusable(true);
        find.setBackground(Ui.ripple(Ui.rectStroke(0x00FFFFFF, Ui.STROKE, 16, 1, this),
                0x22FFFFFF));
        find.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!BatteryService.hasBlePermissions(MainActivity.this)) {
                    requestBlePermissionsOrSettings();
                    return;
                }
                DevicePicker.show(MainActivity.this, new DevicePicker.Listener() {
                    @Override
                    public void onSelected(String mac, String name) {
                        connectToMac(mac, name);
                    }
                });
            }
        });
        LinearLayout.LayoutParams findLp = new LinearLayout.LayoutParams(Ui.dp(this, 104),
                Ui.dp(this, 48));
        findLp.leftMargin = Ui.dp(this, 10);
        row.addView(find, findLp);
        return row;
    }

    private View buildSection(String title, String trailing) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(Ui.sectionTitle(this, title),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView t = Ui.text(this, trailing, 9f, Ui.DIM, true);
        t.setLetterSpacing(0.08f);
        row.addView(t);
        return row;
    }

    private View buildCells() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        int h = Ui.dp(this, 118);

        cellLeft = new BatteryCellView(this);
        cellRight = new BatteryCellView(this);
        cellCase = new BatteryCellView(this);

        LinearLayout.LayoutParams p1 = new LinearLayout.LayoutParams(0, h, 1f);
        LinearLayout.LayoutParams p2 = new LinearLayout.LayoutParams(0, h, 1f);
        LinearLayout.LayoutParams p3 = new LinearLayout.LayoutParams(0, h, 1f);
        p2.leftMargin = Ui.dp(this, 7);
        p3.leftMargin = Ui.dp(this, 7);
        row.addView(cellLeft, p1);
        row.addView(cellRight, p2);
        row.addView(cellCase, p3);
        return row;
    }

    private View buildSettingsCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Ui.rectStroke(Ui.SURFACE, Ui.STROKE, 20, 1, this));
        card.setPadding(0, Ui.dp(this, 3), 0, Ui.dp(this, 3));

        themeAction = addActionRow(card, "外观", "浅色 / 深色 / 跟随系统",
                Prefs.themeLabel(this), new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        showThemeDialog();
                    }
                });
        card.addView(Ui.divider(this));

        LinearLayout switchRow = new LinearLayout(this);
        switchRow.setOrientation(LinearLayout.HORIZONTAL);
        switchRow.setGravity(Gravity.CENTER_VERTICAL);
        switchRow.setPadding(Ui.dp(this, 14), Ui.dp(this, 10),
                Ui.dp(this, 10), Ui.dp(this, 10));

        LinearLayout switchTexts = new LinearLayout(this);
        switchTexts.setOrientation(LinearLayout.VERTICAL);
        switchTexts.addView(Ui.text(this, "通知栏常驻", 12f, Ui.TEXT, true));
        TextView switchSub = Ui.text(this, "关闭后停止后台连接与通知栏显示", 9.5f, Ui.MUTED, false);
        switchSub.setMaxLines(2);
        switchTexts.addView(switchSub, topMargin(2));
        switchRow.addView(switchTexts, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        serviceSwitch = new ToggleView(this);
        serviceSwitch.setOnChangedListener(new ToggleView.Listener() {
            @Override
            public void onChanged(ToggleView view, boolean isChecked) {
                if (updatingSwitch) return;
                onServiceToggle(isChecked);
            }
        });
        switchRow.addView(serviceSwitch);
        card.addView(switchRow);
        card.addView(Ui.divider(this));

        LinearLayout idleRow = new LinearLayout(this);
        idleRow.setOrientation(LinearLayout.HORIZONTAL);
        idleRow.setGravity(Gravity.CENTER_VERTICAL);
        idleRow.setPadding(Ui.dp(this, 14), Ui.dp(this, 10),
                Ui.dp(this, 10), Ui.dp(this, 10));
        LinearLayout idleTexts = new LinearLayout(this);
        idleTexts.setOrientation(LinearLayout.VERTICAL);
        idleTexts.addView(Ui.text(this, "息屏省电", 12f, Ui.TEXT, true));
        TextView idleSub = Ui.text(this, "息屏 10 分钟后断开，亮屏自动重连", 9.5f, Ui.MUTED, false);
        idleSub.setMaxLines(2);
        idleTexts.addView(idleSub, topMargin(2));
        idleRow.addView(idleTexts, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        idleSwitch = new ToggleView(this);
        idleSwitch.setOnChangedListener(new ToggleView.Listener() {
            @Override
            public void onChanged(ToggleView view, boolean isChecked) {
                if (updatingIdleSwitch) return;
                Prefs.setIdleDisconnectEnabled(MainActivity.this, isChecked);
                Control.start(MainActivity.this, BatteryService.ACTION_APPLY_IDLE);
            }
        });
        idleRow.addView(idleSwitch);
        card.addView(idleRow);
        card.addView(Ui.divider(this));

        LinearLayout estimateRow = new LinearLayout(this);
        estimateRow.setOrientation(LinearLayout.HORIZONTAL);
        estimateRow.setGravity(Gravity.CENTER_VERTICAL);
        estimateRow.setPadding(Ui.dp(this, 14), Ui.dp(this, 10),
                Ui.dp(this, 10), Ui.dp(this, 10));
        LinearLayout estimateTexts = new LinearLayout(this);
        estimateTexts.setOrientation(LinearLayout.VERTICAL);
        estimateTexts.addView(Ui.text(this, "估算仓电量", 12f, Ui.TEXT, true));
        TextView estimateSub = Ui.text(this, "按历史耗电估算，仅供参考，以实际为准", 9.5f, Ui.MUTED, false);
        estimateSub.setMaxLines(2);
        estimateTexts.addView(estimateSub, topMargin(2));
        estimateRow.addView(estimateTexts, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        estimateSwitch = new ToggleView(this);
        estimateSwitch.setOnChangedListener(new ToggleView.Listener() {
            @Override
            public void onChanged(ToggleView view, boolean isChecked) {
                if (updatingEstimateSwitch) return;
                Prefs.setCaseEstimateEnabled(MainActivity.this, isChecked);
                Control.applyEstimate(MainActivity.this);
            }
        });
        estimateRow.addView(estimateSwitch);
        card.addView(estimateRow);
        card.addView(Ui.divider(this));

        intervalAction = addActionRow(card, "定时查询", "间隔越长越省电",
                Prefs.queryLabel(this), new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        showIntervalDialog();
                    }
                });

        addActionRow(card, "连接已配对设备", "从系统蓝牙音频设备中选择", "选择",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (!BatteryService.hasBlePermissions(MainActivity.this)) {
                            requestBlePermissionsOrSettings();
                            return;
                        }
                        DevicePicker.show(MainActivity.this, new DevicePicker.Listener() {
                            @Override
                            public void onSelected(String mac, String name) {
                                connectToMac(mac, name);
                            }
                        });
                    }
                });
        return card;
    }

    private LinearLayout newCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Ui.rectStroke(Ui.SURFACE, Ui.STROKE, 20, 1, this));
        card.setPadding(0, Ui.dp(this, 3), 0, Ui.dp(this, 3));
        return card;
    }

    private View buildBackgroundCard() {
        LinearLayout card = newCard();
        notifAction = addActionRow(card, "通知权限", "通知栏电量与实时状态",
                "去开启", new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                                && !hasNotificationPermission()) {
                            requestNeededPermissions(true);
                        } else {
                            KeepAlive.openNotificationSettings(MainActivity.this);
                        }
                    }
                });
        addActionRow(card, "电池优化", "允许后台持续保持连接",
                "去设置", new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        KeepAlive.requestIgnoreBatteryOptimizations(MainActivity.this);
                    }
                });
        addActionRow(card, "自启动权限", "重启手机后自动恢复通知",
                "去设置", new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        KeepAlive.openAutostart(MainActivity.this);
                    }
                });
        return card;
    }

    private View buildAdvancedCard() {
        LinearLayout card = newCard();
        addActionRow(card, "更多设置", "设备信息、诊断、重新查找、关闭服务", "打开",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        startActivity(new Intent(MainActivity.this, AdvancedActivity.class));
                    }
                });
        return card;
    }

    private TextView addActionRow(LinearLayout card, String title, String desc,
                                  String action, View.OnClickListener listener) {
        if (card.getChildCount() > 0) card.addView(Ui.divider(this));
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Ui.dp(this, 14), Ui.dp(this, 10),
                Ui.dp(this, 11), Ui.dp(this, 10));
        row.setClickable(true);
        row.setFocusable(true);
        row.setBackground(Ui.ripple(Ui.rect(0x00FFFFFF, 0, this), 0x14FFFFFF));
        row.setOnClickListener(listener);

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(Ui.text(this, title, 11.5f, Ui.TEXT, true));
        TextView d = Ui.text(this, desc, 9.5f, Ui.MUTED, false);
        d.setMaxLines(2);
        texts.addView(d, topMargin(1));
        row.addView(texts, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView actionView = Ui.pill(this, action, Ui.COPPER, 0x14E7B27A, 0x33E7B27A);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = Ui.dp(this, 8);
        row.addView(actionView, lp);
        card.addView(row);
        return actionView;
    }

    private LinearLayout.LayoutParams topMargin(int dp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(this, dp);
        return lp;
    }

    // ------------------------------------------------------------------ refresh

    private void refreshUi(BatterySnapshot s) {
        if (s == null) s = new BatterySnapshot();
        int accent = statusColor(s);
        statusChip.setText(statusLabel(s));
        statusChip.setTextColor(accent);
        statusChip.setBackground(Ui.rectStroke(withAlpha(accent, 0.12f),
                withAlpha(accent, 0.32f), 999, 1, this));

        titleName.setText(TextUtils.isEmpty(s.modelName) ? "耳机" : s.modelName);
        bleNameText.setText(TextUtils.isEmpty(s.bleName) ? "蓝牙名称 —" : "蓝牙名称 · " + s.bleName);
        statusText.setText(TextUtils.isEmpty(s.statusTitle) ? "等待连接" : s.statusTitle);
        statusText.setTextColor(accent);
        detailText.setText(TextUtils.isEmpty(s.statusDetail) ? "请打开耳机仓盖，靠近手机" : s.statusDetail);

        int headline = s.headlineLevel();
        gauge.setValue(headline, true);

        cellLeft.setData("左耳", s.left, Ui.LEFT,
                s.left < 0 ? "未连接" : "L", s.connected, s.leftCharging);
        cellRight.setData("右耳", s.right, Ui.RIGHT,
                s.right < 0 ? "未连接" : "R", s.connected, s.rightCharging);
        boolean caseKnown = s.caseLevel >= 0;
        String caseSub;
        if (!caseKnown) {
            caseSub = s.connected
                    ? (Prefs.caseEstimateEnabled(this) && s.caseStale ? "估算中" : "开盖后显示")
                    : "未连接";
        } else {
            caseSub = s.caseEstimated ? "估算" : s.caseStale ? "关盖前" : "C";
        }
        cellCase.setData("充电仓", caseKnown ? s.caseLevel : -1, Ui.CASE,
                caseSub, s.connected, caseKnown && s.caseCharging);

        protocolChip.setText(protocolShort(s.protocol));
        protocolChip.setTextColor(Ui.COPPER);
        rssiChip.setText(s.rssi < 0 ? s.rssi + " dBm" : "信号 —");
        rssiChip.setTextColor(s.rssi < 0 ? Ui.RIGHT : Ui.MUTED);

        if (serviceSwitch != null) {
            updatingSwitch = true;
            serviceSwitch.setChecked(AppState.isEnabled(this));
            updatingSwitch = false;
            updatingIdleSwitch = true;
            idleSwitch.setChecked(Prefs.idleDisconnectEnabled(this));
            updatingIdleSwitch = false;
            updatingEstimateSwitch = true;
            estimateSwitch.setChecked(Prefs.caseEstimateEnabled(this));
            updatingEstimateSwitch = false;
            themeAction.setText(Prefs.themeLabel(this));
            intervalAction.setText(Prefs.queryLabel(this));
            notifAction.setText(hasNotificationPermission() ? "已开启" : "去开启");
        }

        updateBanner(s, accent);
    }

    private void updateBanner(BatterySnapshot s, int accent) {
        boolean bleMissing = !BatteryService.hasBlePermissions(this);
        boolean notifMissing = !hasNotificationPermission();
        bannerMode = 0;
        if (bleMissing) {
            bannerMode = 1;
            warningTitle = "需要蓝牙权限";
            warningDetail = "授予「附近设备」权限后才能读取耳机左右耳与充电仓电量";
            warningAction = "去授权";
        } else if (notifMissing) {
            bannerMode = 2;
            warningTitle = "通知栏显示未开启";
            warningDetail = "Android 13 及以上需要通知权限，电量才会常驻通知栏";
            warningAction = "去开启";
        } else if (s.bluetoothOff) {
            bannerMode = 3;
            warningTitle = "蓝牙未开启";
            warningDetail = "打开蓝牙并保持耳机在附近，应用会自动连接";
            warningAction = "去打开";
        }
        if (warningButton != null) {
            warningButton.setVisibility(bannerMode == 0 ? View.GONE : View.VISIBLE);
        }
    }

    private void showWarningDialog() {
        if (bannerMode == 0) return;
        new AlertDialog.Builder(this)
                .setTitle(warningTitle)
                .setMessage(warningDetail)
                .setPositiveButton(warningAction, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        if (bannerMode == 1) {
                            requestBlePermissionsOrSettings();
                        } else if (bannerMode == 2) {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                                    && !hasNotificationPermission()) {
                                requestNeededPermissions(true);
                            } else {
                                KeepAlive.openNotificationSettings(MainActivity.this);
                            }
                        } else if (bannerMode == 3) {
                            try {
                                startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));
                            } catch (Exception ignored) {
                            }
                        }
                    }
                })
                .setNegativeButton("知道了", null)
                .show();
    }

    private static String statusLabel(BatterySnapshot s) {
        if (s.permissionMissing || s.bluetoothOff) return "● 需要处理";
        if (s.connected) return "● 已连接";
        if (s.connecting || s.scanning) return "● 正在连接";
        return "● 等待连接";
    }

    private static int statusColor(BatterySnapshot s) {
        if (s.permissionMissing || s.bluetoothOff) return Ui.RED;
        if (s.connected) return Ui.GREEN;
        if (s.connecting || s.scanning) return Ui.COPPER;
        return Ui.MUTED;
    }

    private static String protocolShort(String protocol) {
        if (protocol == null) return "BLE";
        if (protocol.contains("SPP")) return "经典 SPP";
        if (protocol.contains("OpenFeel")) return "OpenFeel 协议";
        if (protocol.contains("标准")) return "BLE 标准";
        return "BLE";
    }

    private static int withAlpha(int color, float alpha) {
        int a = Math.round(255 * Math.max(0f, Math.min(1f, alpha)));
        return (a << 24) | (color & 0x00FFFFFF);
    }

    // ------------------------------------------------------------------ actions

    // ------------------------------------------------------------------ manual pairing

    private void showThemeDialog() {
        final String[] labels = new String[]{"跟随系统", "浅色", "深色"};
        final int[] values = new int[]{Prefs.THEME_SYSTEM, Prefs.THEME_LIGHT, Prefs.THEME_DARK};
        int mode = Prefs.themeMode(this);
        int checked = mode == Prefs.THEME_LIGHT ? 1 : mode == Prefs.THEME_DARK ? 2 : 0;
        new AlertDialog.Builder(this)
                .setTitle("外观")
                .setSingleChoiceItems(labels, checked,
                        new android.content.DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(android.content.DialogInterface dialog, int which) {
                                Prefs.setThemeMode(MainActivity.this, values[which]);
                                dialog.dismiss();
                                recreate();
                            }
                        })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showIntervalDialog() {
        final String[] labels = new String[]{"1 分钟", "2 分钟", "5 分钟"};
        final int[] values = new int[]{60, 120, 300};
        int current = Prefs.queryIntervalSeconds(this);
        int checked = current == 60 ? 0 : current == 300 ? 2 : 1;
        new AlertDialog.Builder(this)
                .setTitle("定时查询间隔")
                .setSingleChoiceItems(labels, checked,
                        new android.content.DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(android.content.DialogInterface dialog, int which) {
                                Prefs.setQueryIntervalSeconds(MainActivity.this, values[which]);
                                intervalAction.setText(Prefs.queryLabel(MainActivity.this));
                                Control.refresh(MainActivity.this);
                                dialog.dismiss();
                            }
                        })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showBondedDevicePicker() {
        if (!BatteryService.hasBlePermissions(this)) {
            requestBlePermissionsOrSettings();
            return;
        }
        BluetoothManager manager = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        if (manager == null || manager.getAdapter() == null) {
            Toast.makeText(this, "此手机不支持蓝牙", Toast.LENGTH_SHORT).show();
            return;
        }
        final List<BluetoothDevice> devices = new ArrayList<>();
        try {
            Set<BluetoothDevice> bonded = manager.getAdapter().getBondedDevices();
            if (bonded != null) devices.addAll(bonded);
        } catch (SecurityException e) {
            requestBlePermissionsOrSettings();
            return;
        }
        if (devices.isEmpty()) {
            showManualMacDialog("系统里没有已配对设备，可以直接输入 MAC 地址");
            return;
        }
        Collections.sort(devices, new Comparator<BluetoothDevice>() {
            @Override
            public int compare(BluetoothDevice a, BluetoothDevice b) {
                return safeName(a).compareToIgnoreCase(safeName(b));
            }
        });
        final String[] labels = new String[devices.size()];
        for (int i = 0; i < devices.size(); i++) {
            BluetoothDevice d = devices.get(i);
            String name = safeName(d);
            labels[i] = (name.isEmpty() ? "未命名设备" : name) + "  (" + d.getAddress() + ")";
        }
        new AlertDialog.Builder(this)
                .setTitle("选择已配对设备")
                .setItems(labels, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        BluetoothDevice d = devices.get(which);
                        connectToMac(d.getAddress(), safeName(d));
                    }
                })
                .setNeutralButton("手动输入 MAC", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        showManualMacDialog("输入系统蓝牙详情里的设备 MAC 地址");
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showManualMacDialog(String hint) {
        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setTextColor(Ui.TEXT);
        input.setHintTextColor(Ui.MUTED);
        input.setTextSize(14f);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        input.setHint("AA:BB:CC:DD:EE:FF");
        input.setPadding(Ui.dp(this, 14), Ui.dp(this, 10),
                Ui.dp(this, 14), Ui.dp(this, 10));
        input.setBackground(Ui.rectStroke(Ui.SURFACE_2, Ui.STROKE, 14, 1, this));
        String saved = AppState.getSavedMac(this);
        if (saved != null && !saved.isEmpty()) input.setText(saved);

        new AlertDialog.Builder(this)
                .setTitle("手动连接 MAC")
                .setMessage(hint)
                .setView(input)
                .setPositiveButton("连接", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        String mac = input.getText().toString().trim().toUpperCase(Locale.US);
                        if (!mac.matches("([0-9A-F]{2}:){5}[0-9A-F]{2}")) {
                            Toast.makeText(MainActivity.this, "MAC 格式应为 AA:BB:CC:DD:EE:FF",
                                    Toast.LENGTH_LONG).show();
                            return;
                        }
                        connectToMac(mac, "");
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void connectToMac(String mac, String name) {
        if (!BatteryService.hasBlePermissions(this)) {
            requestBlePermissionsOrSettings();
            return;
        }
        AppState.setEnabled(this, true);
        Control.connect(this, mac, name);
        Toast.makeText(this, "正在连接 " + (TextUtils.isEmpty(name) ? mac : name),
                Toast.LENGTH_SHORT).show();
    }

    private void sendSppProbe() {
        if (!BatteryService.hasBlePermissions(this)) {
            requestBlePermissionsOrSettings();
            return;
        }
        Intent intent = new Intent(this, BatteryService.class)
                .setAction(BatteryService.ACTION_SPP_PROBE);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent);
            } else {
                startService(intent);
            }
            Toast.makeText(this, "已发送 SPP 查询，随后复制诊断查看结果",
                    Toast.LENGTH_LONG).show();
        } catch (RuntimeException e) {
            Toast.makeText(this, "发送失败，请重新连接", Toast.LENGTH_SHORT).show();
        }
    }

    private void copyDiagnostics() {        BatterySnapshot s = AppState.get();
        String text = "Buds Battery Dock v2.0.1\n"
                + "型号: " + s.modelName + "\n"
                + "蓝牙名: " + s.bleName + "\n"
                + "地址: " + s.address + "\n"
                + "协议: " + s.protocol + "\n"
                + "状态: " + s.statusTitle + " / " + s.statusDetail + "\n"
                + "电量: L=" + s.left + " R=" + s.right + " C=" + s.caseLevel + " T=" + s.total + "\n"
                + "--- 蓝牙诊断 ---\n" + DiagLog.dump();
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("Buds电量诊断", text));
                Toast.makeText(this, "诊断信息已复制，可直接发给开发者", Toast.LENGTH_LONG).show();
            }
        } catch (RuntimeException e) {
            Toast.makeText(this, "复制失败", Toast.LENGTH_SHORT).show();
        }
    }

    private String safeName(BluetoothDevice device) {
        if (device == null) return "";
        try {
            String name = device.getName();
            if (name != null && !name.isEmpty()) return name;
        } catch (SecurityException ignored) {
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                String alias = device.getAlias();
                if (alias != null && !alias.isEmpty()) return alias;
            } catch (SecurityException ignored) {
            }
        }
        return "";
    }

    private void onRefreshClicked() {        if (!BatteryService.hasBlePermissions(this)) {
            requestBlePermissionsOrSettings();
            return;
        }
        if (!hasNotificationPermission()) {
            requestNeededPermissions(true);
        }
        startBatteryService(BatteryService.ACTION_REFRESH);
        Toast.makeText(this, "正在读取耳机电量…", Toast.LENGTH_SHORT).show();
    }

    private void onFindClicked() {
        if (!BatteryService.hasBlePermissions(this)) {
            requestBlePermissionsOrSettings();
            return;
        }
        AppState.clearSavedMac(this);
        startBatteryService(BatteryService.ACTION_FORGET);
        Toast.makeText(this, "正在重新查找耳机，请打开仓盖", Toast.LENGTH_SHORT).show();
    }

    private void onServiceToggle(boolean enabled) {
        if (enabled) {
            AppState.setEnabled(this, true);
            if (!BatteryService.hasBlePermissions(this)) {
                requestNeededPermissions(true);
            } else {
                startBatteryService(BatteryService.ACTION_START);
            }
        } else {
            updatingSwitch = true;
            serviceSwitch.setChecked(true);
            updatingSwitch = false;
            confirmStopService();
        }
    }

    private void confirmStopService() {
        new AlertDialog.Builder(this)
                .setTitle("关闭后台常驻？")
                .setMessage("关闭后通知栏电量会消失，应用也不再自动连接耳机。之后仍可从桌面图标重新打开。")
                .setPositiveButton("关闭", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        AppState.setEnabled(MainActivity.this, false);
                        updatingSwitch = true;
                        serviceSwitch.setChecked(false);
                        updatingSwitch = false;
                        BatterySnapshot stopped = AppState.get();
                        stopped.connected = false;
                        stopped.connecting = false;
                        stopped.scanning = false;
                        stopped.statusTitle = "后台服务已关闭";
                        stopped.statusDetail = "打开应用可重新开启";
                        AppState.set(stopped);
                        stopService(new Intent(MainActivity.this, BatteryService.class));
                        Toast.makeText(MainActivity.this, "后台服务已关闭", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void requestBlePermissionsOrSettings() {
        if (permissionRequested && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && !BatteryService.hasBlePermissions(this)
                && !shouldShowRequestPermissionRationale(Manifest.permission.BLUETOOTH_SCAN)
                && !shouldShowRequestPermissionRationale(Manifest.permission.BLUETOOTH_CONNECT)) {
            KeepAlive.openAppDetails(this);
            return;
        }
        requestNeededPermissions(true);
    }

    private void startBatteryService(String action) {
        if (!BatteryService.hasBlePermissions(this) && !BatteryService.ACTION_STOP.equals(action)) {
            return;
        }
        Intent intent = new Intent(this, BatteryService.class).setAction(action);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent);
            } else {
                startService(intent);
            }
        } catch (RuntimeException e) {
            Toast.makeText(this, "后台服务启动失败，请重试", Toast.LENGTH_SHORT).show();
        }
    }

    private boolean hasAllPermissions() {
        return BatteryService.hasBlePermissions(this) && hasNotificationPermission();
    }

    private boolean hasNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true;
        return checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void requestNeededPermissions(boolean force) {
        if (!force && permissionRequested
                && System.currentTimeMillis() - lastAutoRequestAt < 4_000L) return;
        permissionRequested = true;
        lastAutoRequestAt = System.currentTimeMillis();

        List<String> missing = new ArrayList<>();
        if (!BatteryService.hasBlePermissions(this)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                missing.add(Manifest.permission.BLUETOOTH_SCAN);
                missing.add(Manifest.permission.BLUETOOTH_CONNECT);
            } else {
                missing.add(Manifest.permission.ACCESS_FINE_LOCATION);
            }
        }
        if (!hasNotificationPermission()
                && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            missing.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (missing.isEmpty()) return;
        try {
            requestPermissions(missing.toArray(new String[0]), REQUEST_PERMISSIONS);
        } catch (RuntimeException e) {
            KeepAlive.openAppDetails(this);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_PERMISSIONS) return;
        refreshUi(AppState.get());
        if (BatteryService.hasBlePermissions(this)) {
            if (AppState.isEnabled(this)) startBatteryService(BatteryService.ACTION_REFRESH);
        } else {
            Toast.makeText(this, "没有蓝牙权限，无法读取耳机电量", Toast.LENGTH_LONG).show();
        }
        if (!hasNotificationPermission()) {
            Toast.makeText(this, "通知权限未开启，通知栏不会显示电量", Toast.LENGTH_LONG).show();
        }
    }
}
