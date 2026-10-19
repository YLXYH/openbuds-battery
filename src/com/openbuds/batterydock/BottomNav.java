package com.openbuds.batterydock;

import android.app.Activity;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Fixed two-tab bar. The bar itself never animates; only page content moves. */
public final class BottomNav {
    public interface Listener {
        void onTabSelected(int index);
    }

    private final LinearLayout wrap;
    private final View[] indicators = new View[2];
    private final TextView[] labels = new TextView[2];
    private final LinearLayout[] tabs = new LinearLayout[2];

    private BottomNav(Activity activity, FrameLayout root, int selected, final Listener listener) {
        wrap = new LinearLayout(activity);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setBackgroundColor(Ui.SURFACE);
        wrap.setElevation(Ui.dp(activity, 8));

        View line = new View(activity);
        line.setBackgroundColor(Ui.STROKE);
        wrap.addView(line, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, Ui.dp(activity, 0.7f))));

        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        row.setPadding(Ui.dp(activity, 18), Ui.dp(activity, 7),
                Ui.dp(activity, 18), Ui.dp(activity, 7));
        wrap.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(activity, 58)));

        tabs[0] = makeTab(activity, "主页", 0, listener);
        tabs[1] = makeTab(activity, "设置", 1, listener);
        row.addView(tabs[0], new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        View gap = new View(activity);
        row.addView(gap, new LinearLayout.LayoutParams(Ui.dp(activity, 8), 1));
        row.addView(tabs[1], new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.BOTTOM;
        root.addView(wrap, lp);
        setSelected(selected);
    }

    public static BottomNav attach(Activity activity, FrameLayout root, int selected,
                                   Listener listener) {
        return new BottomNav(activity, root, selected, listener);
    }

    private LinearLayout makeTab(final Activity activity, String label, final int index,
                                 final Listener listener) {
        LinearLayout tab = new LinearLayout(activity);
        tab.setOrientation(LinearLayout.VERTICAL);
        tab.setGravity(Gravity.CENTER);
        tab.setClickable(true);
        tab.setFocusable(true);
        tab.setContentDescription(label);

        View indicator = new View(activity);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(
                Ui.dp(activity, 20), Ui.dp(activity, 3));
        ilp.bottomMargin = Ui.dp(activity, 4);
        tab.addView(indicator, ilp);
        indicators[index] = indicator;

        TextView text = Ui.text(activity, label, 13f, Ui.MUTED, true);
        text.setGravity(Gravity.CENTER);
        tab.addView(text);
        labels[index] = text;

        tab.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (listener != null) listener.onTabSelected(index);
            }
        });
        return tab;
    }

    public void setSelected(int selected) {
        for (int i = 0; i < 2; i++) {
            boolean active = i == selected;
            indicators[i].setBackgroundColor(active ? Ui.COPPER : 0x00000000);
            labels[i].setTextColor(active ? Ui.COPPER : Ui.MUTED);
            tabs[i].setBackground(active ? Ui.rect(0x1FE7B27A, 15, wrap.getContext()) : null);
        }
    }
}
