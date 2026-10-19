package com.openbuds.batterydock;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.content.res.ColorStateList;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Hand-rolled view helpers keep the APK free of AndroidX / Material dependencies. */
final class Ui {
    static boolean dark = true;
    static int INK = 0xFF0B0E12;
    static int SURFACE = 0xFF141920;
    static int SURFACE_2 = 0xFF19202A;
    static int STROKE = 0xFF252E3A;
    static int COPPER = 0xFFE7B27A;
    static int LEFT = 0xFFF2B36B;
    static int RIGHT = 0xFF7ED6C1;
    static int CASE = 0xFF8CA8FF;
    static int TEXT = 0xFFF4F1EA;
    static int MUTED = 0xFF8B94A3;
    static int DIM = 0xFF5C6675;
    static int RED = 0xFFFF8A80;
    static int GREEN = 0xFF7ED6A5;

    private Ui() {
    }

    static void init(Context c) {
        int theme = Prefs.themeMode(c);
        int mode = c.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK;
        dark = theme == Prefs.THEME_DARK
                || (theme == Prefs.THEME_SYSTEM && mode == Configuration.UI_MODE_NIGHT_YES);
        if (dark) {
            INK = 0xFF0B0E12;
            SURFACE = 0xFF141920;
            SURFACE_2 = 0xFF19202A;
            STROKE = 0xFF252E3A;
            COPPER = 0xFFE7B27A;
            LEFT = 0xFFF2B36B;
            RIGHT = 0xFF7ED6C1;
            CASE = 0xFF8CA8FF;
            TEXT = 0xFFF4F1EA;
            MUTED = 0xFF8B94A3;
            DIM = 0xFF5C6675;
            RED = 0xFFFF8A80;
            GREEN = 0xFF7ED6A5;
        } else {
            INK = 0xFFF6F2EB;
            SURFACE = 0xFFFFFEFB;
            SURFACE_2 = 0xFFF0EBE3;
            STROKE = 0xFFE2D9CD;
            COPPER = 0xFFB06A28;
            LEFT = 0xFFB96C16;
            RIGHT = 0xFF238A75;
            CASE = 0xFF536FBE;
            TEXT = 0xFF20242A;
            MUTED = 0xFF6C7480;
            DIM = 0xFF9AA1AB;
            RED = 0xFFB83A2E;
            GREEN = 0xFF2C7A52;
        }
    }

    static int barBackground() {
        return dark ? 0xFF2A333F : 0xFFE6DED3;
    }

    static int cardGlow() {
        return dark ? 0x14FFFFFF : 0x10B06A28;
    }

    static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                c.getResources().getDisplayMetrics()));
    }

    static float sp(Context c, float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v,
                c.getResources().getDisplayMetrics());
    }

    static Typeface regular() {
        return Typeface.create("sans-serif", Typeface.NORMAL);
    }

    static Typeface medium() {
        return Typeface.create("sans-serif-medium", Typeface.NORMAL);
    }

    static Drawable rect(int fill, float radiusDp, Context c) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(c, radiusDp));
        return d;
    }

    static Drawable rectStroke(int fill, int stroke, float radiusDp, float widthDp, Context c) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(c, radiusDp));
        d.setStroke(dp(c, widthDp), stroke);
        return d;
    }

    static Drawable ripple(Drawable content, int color) {
        return new RippleDrawable(ColorStateList.valueOf(color), content, null);
    }

    static TextView text(Context c, CharSequence value, float sizeSp, int color, boolean medium) {
        TextView t = new TextView(c);
        t.setText(value);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
        t.setTextColor(color);
        t.setTypeface(medium ? medium() : regular());
        t.setIncludeFontPadding(false);
        return t;
    }

    static TextView number(Context c, CharSequence value, float sizeSp, int color, boolean medium) {
        TextView t = text(c, value, sizeSp, color, medium);
        t.setFontFeatureSettings("tnum");
        return t;
    }

    static LinearLayout.LayoutParams lp(int w, int h) {
        return new LinearLayout.LayoutParams(w, h);
    }

    static LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    static LinearLayout.LayoutParams weight(float weight) {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight);
    }

    static LinearLayout.LayoutParams topMargin(Context c, int dp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(c, dp);
        return lp;
    }

    static void margins(View v, int l, int t, int r, int b) {
        ViewGroup.LayoutParams lp = v.getLayoutParams();
        if (!(lp instanceof ViewGroup.MarginLayoutParams)) return;
        ViewGroup.MarginLayoutParams m = (ViewGroup.MarginLayoutParams) lp;
        m.setMargins(l, t, r, b);
        v.setLayoutParams(m);
    }

    static void padding(View v, int l, int t, int r, int b) {
        v.setPadding(l, t, r, b);
    }

    static View divider(Context c) {
        View v = new View(c);
        v.setBackgroundColor(0x1AFFFFFF);
        v.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                Math.max(1, dp(c, 0.6f))));
        return v;
    }

    static TextView pill(Context c, String text, int textColor, int fill, int stroke) {
        TextView t = Ui.text(c, text, 10.5f, textColor, true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(c, 10), dp(c, 5), dp(c, 10), dp(c, 5));
        t.setBackground(rectStroke(fill, stroke, 999, 1, c));
        return t;
    }

    static TextView sectionTitle(Context c, String text) {
        TextView t = text(c, text, 13f, TEXT, true);
        t.setLetterSpacing(0.02f);
        return t;
    }
}
