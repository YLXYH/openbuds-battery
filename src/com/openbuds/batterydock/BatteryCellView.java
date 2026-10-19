package com.openbuds.batterydock;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/** Rounded battery card with a segmented level strip. */
final class BatteryCellView extends View {
    private final Paint card = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint valuePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pctPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint subPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint barBg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint barFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint chargePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint chargeBg = new Paint(Paint.ANTI_ALIAS_FLAG);

    private String label = "";
    private String sub = "";
    private int level = -1;
    private int accent = Ui.COPPER;
    private boolean active = true;
    private boolean charging;

    BatteryCellView(Context context) {
        super(context);
        labelPaint.setTextSize(Ui.sp(context, 11f));
        labelPaint.setColor(Ui.MUTED);
        labelPaint.setTypeface(Ui.medium());
        labelPaint.setLetterSpacing(0.03f);

        valuePaint.setTextSize(Ui.sp(context, 29f));
        valuePaint.setTypeface(Ui.medium());
        valuePaint.setFontFeatureSettings("tnum");

        pctPaint.setTextSize(Ui.sp(context, 12f));
        pctPaint.setTypeface(Ui.medium());

        subPaint.setTextSize(Ui.sp(context, 9f));
        subPaint.setColor(Ui.DIM);

        barBg.setColor(Ui.barBackground());
        barFill.setStyle(Paint.Style.FILL);
        dot.setStyle(Paint.Style.FILL);
        chargePaint.setTextSize(Ui.sp(context, 7.5f));
        chargePaint.setTypeface(Ui.medium());
        chargePaint.setTextAlign(Paint.Align.CENTER);
        chargeBg.setStyle(Paint.Style.FILL);
        setMinimumHeight(Ui.dp(context, 116));
    }

    void setData(String label, int level, int accent, String sub, boolean active) {
        setData(label, level, accent, sub, active, false);
    }

    void setData(String label, int level, int accent, String sub, boolean active, boolean charging) {
        if (this.level == level && this.accent == accent && this.active == active
                && this.charging == charging && this.label.equals(label)
                && this.sub.equals(sub == null ? "" : sub)) {
            return;
        }
        this.label = label;
        this.level = level;
        this.accent = accent;
        this.sub = sub == null ? "" : sub;
        this.active = active;
        this.charging = charging;
        setContentDescription(label + (level >= 0 ? " " + level + "%" : " 暂无数据")
                + (charging ? " 充电中" : ""));
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth();
        float h = getHeight();
        float pad = Ui.dp(getContext(), 13);
        float radius = Ui.dp(getContext(), 18);

        card.setStyle(Paint.Style.FILL);
        card.setColor(Ui.SURFACE);
        canvas.drawRoundRect(new RectF(0, 0, w, h), radius, radius, card);
        card.setStyle(Paint.Style.STROKE);
        card.setStrokeWidth(Ui.dp(getContext(), 1));
        card.setColor(active ? Ui.STROKE : 0xFF1D242D);
        canvas.drawRoundRect(new RectF(0.5f, 0.5f, w - 0.5f, h - 0.5f), radius, radius, card);

        labelPaint.setAlpha(active ? 255 : 150);
        canvas.drawText(label, pad, Ui.dp(getContext(), 24), labelPaint);

        dot.setColor(level >= 0 ? accent : 0xFF3A424E);
        dot.setAlpha(active ? 255 : 140);
        if (charging) {
            float pillW = Ui.dp(getContext(), 28);
            float pillH = Ui.dp(getContext(), 15);
            float pillLeft = w - pad - pillW;
            float pillTop = Ui.dp(getContext(), 11);
            chargeBg.setColor(accent);
            chargeBg.setAlpha(active ? 42 : 24);
            canvas.drawRoundRect(new RectF(pillLeft, pillTop, pillLeft + pillW,
                    pillTop + pillH), pillH / 2, pillH / 2, chargeBg);
            chargePaint.setColor(accent);
            chargePaint.setAlpha(active ? 255 : 160);
            Paint.FontMetrics fm = chargePaint.getFontMetrics();
            float baseline = pillTop + pillH / 2 - (fm.ascent + fm.descent) / 2;
            canvas.drawText("充电", pillLeft + pillW / 2, baseline, chargePaint);
        } else {
            canvas.drawCircle(w - pad, Ui.dp(getContext(), 19), Ui.dp(getContext(), 3), dot);
        }

        if (level >= 0) {
            String value = String.valueOf(level);
            valuePaint.setColor(Ui.TEXT);
            valuePaint.setAlpha(active ? 255 : 185);
            float baseline = h * 0.615f;
            canvas.drawText(value, pad, baseline, valuePaint);
            float vw = valuePaint.measureText(value);
            pctPaint.setColor(accent);
            pctPaint.setAlpha(active ? 255 : 185);
            canvas.drawText("%", pad + vw + Ui.dp(getContext(), 1.5f),
                    baseline - valuePaint.getTextSize() * 0.28f, pctPaint);
        } else {
            valuePaint.setColor(Ui.MUTED);
            valuePaint.setAlpha(active ? 210 : 130);
            canvas.drawText("—", pad, h * 0.58f, valuePaint);
            subPaint.setAlpha(active ? 190 : 120);
            canvas.drawText(sub, pad, h * 0.73f, subPaint);
        }

        float barY = h - Ui.dp(getContext(), 17);
        float barW = w - pad * 2;
        RectF bg = new RectF(pad, barY, pad + barW, barY + Ui.dp(getContext(), 4));
        canvas.drawRoundRect(bg, Ui.dp(getContext(), 2), Ui.dp(getContext(), 2), barBg);
        if (level > 0) {
            float width = Math.max(Ui.dp(getContext(), 4), barW * Math.min(100, level) / 100f);
            barFill.setColor(accent);
            barFill.setAlpha(active ? 255 : 160);
            canvas.drawRoundRect(new RectF(pad, barY, pad + width, barY + Ui.dp(getContext(), 4)),
                    Ui.dp(getContext(), 2), Ui.dp(getContext(), 2), barFill);
        }
    }
}
