package com.openbuds.batterydock;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.SweepGradient;
import android.view.View;

/** Circular battery gauge with a warm copper -> mint sweep. */
final class GaugeView extends View {
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint number = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint percent = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint caption = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bounds = new RectF();
    private final Matrix shaderMatrix = new Matrix();

    private float display = -1f;
    private int target = -1;
    private ValueAnimator animator;

    GaugeView(Context context) {
        super(context);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeCap(Paint.Cap.ROUND);
        glow.setStyle(Paint.Style.STROKE);
        glow.setStrokeCap(Paint.Cap.ROUND);
        number.setTextAlign(Paint.Align.CENTER);
        number.setTypeface(Ui.medium());
        number.setColor(Ui.TEXT);
        number.setFontFeatureSettings("tnum");
        percent.setTextAlign(Paint.Align.LEFT);
        percent.setTypeface(Ui.medium());
        percent.setColor(Ui.COPPER);
        caption.setTextAlign(Paint.Align.CENTER);
        caption.setTypeface(Ui.regular());
        caption.setColor(Ui.MUTED);
        caption.setLetterSpacing(0.08f);
        setContentDescription("综合电量");
    }

    void setValue(int value, boolean animate) {
        if (value == target) return;
        target = value;
        if (animator != null) animator.cancel();
        if (!animate) {
            display = value;
            invalidate();
            return;
        }
        float start = display < -0.5f ? 0f : display;
        float end = value < 0 ? 0f : value;
        animator = ValueAnimator.ofFloat(start, end);
        animator.setDuration(520L);
        animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                display = (float) animation.getAnimatedValue();
                invalidate();
            }
        });
        animator.start();
        if (value < 0) display = -1f;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth();
        float h = getHeight();
        float size = Math.min(w, h);
        float stroke = size * 0.075f;
        float cx = w / 2f;
        float cy = h / 2f;
        float r = size / 2f - stroke * 1.35f;
        bounds.set(cx - r, cy - r, cx + r, cy + r);

        ring.setStrokeWidth(stroke);
        ring.setColor(Ui.barBackground());
        canvas.drawArc(bounds, 135f, 270f, false, ring);

        int shown = target;
        float ratio = shown < 0 ? 0f : Math.max(0f, Math.min(1f, shown / 100f));
        if (shown >= 0) {
            SweepGradient gradient = new SweepGradient(cx, cy,
                    new int[]{0xFFE7B27A, 0xFFF2B36B, 0xFF7ED6C1, 0xFF8CA8FF, 0xFFE7B27A},
                    null);
            shaderMatrix.setRotate(135f, cx, cy);
            gradient.setLocalMatrix(shaderMatrix);
            glow.setShader(gradient);
            glow.setStrokeWidth(stroke * 1.55f);
            glow.setAlpha(42);
            canvas.drawArc(bounds, 135f, 270f * ratio, false, glow);
            ring.setShader(gradient);
            ring.setAlpha(255);
            canvas.drawArc(bounds, 135f, 270f * ratio, false, ring);
            ring.setShader(null);
        }

        number.setTextSize(size * 0.285f);
        percent.setTextSize(size * 0.105f);
        caption.setTextSize(size * 0.075f);
        String value = shown < 0 ? "--" : String.valueOf(Math.round(shown < 0 ? 0 : shown));
        float numberWidth = number.measureText(value);
        float percentWidth = percent.measureText("%");
        float totalWidth = numberWidth + percentWidth * 0.86f;
        float baseY = cy + number.getTextSize() * 0.34f;
        canvas.drawText(value, cx - totalWidth / 2f + numberWidth / 2f, baseY, number);
        canvas.drawText("%", cx - totalWidth / 2f + numberWidth + 1f,
                baseY - number.getTextSize() * 0.06f, percent);
        canvas.drawText(shown < 0 ? "等待数据" : "综合电量", cx, cy + size * 0.265f, caption);

        if (shown >= 0 && display > -0.5f) {
            float angle = (float) Math.toRadians(135f + 270f * Math.max(0f, Math.min(1f, display / 100f)));
            float ex = cx + (float) Math.cos(angle) * r;
            float ey = cy + (float) Math.sin(angle) * r;
            Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
            dot.setColor(Ui.TEXT);
            canvas.drawCircle(ex, ey, stroke * 0.42f, dot);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        if (animator != null) animator.cancel();
        super.onDetachedFromWindow();
    }
}
