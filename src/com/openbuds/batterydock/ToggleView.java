package com.openbuds.batterydock;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/** Compact pill switch with a three-bar brand dot; no "已开启" text needed. */
public final class ToggleView extends View {
    public interface Listener {
        void onChanged(ToggleView view, boolean checked);
    }

    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thumb = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bars = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF trackRect = new RectF();
    private boolean checked;
    private float position;
    private Listener listener;
    private ValueAnimator animator;

    public ToggleView(Context context) {
        super(context);
        setClickable(true);
        setFocusable(true);
        setContentDescription("开关");
        setMinimumWidth(Ui.dp(context, 54));
        setMinimumHeight(Ui.dp(context, 32));
    }

    public void setOnChangedListener(Listener listener) {
        this.listener = listener;
    }

    public boolean isChecked() {
        return checked;
    }

    public void setChecked(boolean checked) {
        if (this.checked == checked) return;
        this.checked = checked;
        if (animator != null) animator.cancel();
        float target = checked ? 1f : 0f;
        animator = ValueAnimator.ofFloat(position, target);
        animator.setDuration(140L);
        animator.setInterpolator(new DecelerateInterpolator());
        animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                position = (float) animation.getAnimatedValue();
                invalidate();
            }
        });
        animator.start();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth();
        float h = getHeight();
        float inset = Ui.dp(getContext(), 2);
        float radius = h / 2f;
        trackRect.set(inset, inset, w - inset, h - inset);

        track.setStyle(Paint.Style.FILL);
        track.setColor(checked ? Ui.COPPER : Ui.SURFACE_2);
        canvas.drawRoundRect(trackRect, radius, radius, track);
        track.setStyle(Paint.Style.STROKE);
        track.setStrokeWidth(Math.max(1, Ui.dp(getContext(), 0.7f)));
        track.setColor(Ui.STROKE);
        canvas.drawRoundRect(trackRect, radius, radius, track);

        float thumbR = (h - Ui.dp(getContext(), 8)) / 2f;
        float margin = Ui.dp(getContext(), 4);
        float cx = margin + thumbR + position * (w - 2 * (margin + thumbR));
        float cy = h / 2f;
        thumb.setStyle(Paint.Style.FILL);
        thumb.setColor(Ui.SURFACE);
        canvas.drawCircle(cx, cy, thumbR, thumb);

        bars.setStyle(Paint.Style.FILL);
        bars.setColor(checked ? Ui.COPPER : Ui.MUTED);
        float bw = Math.max(1.5f, Ui.dp(getContext(), 1.8f));
        float gap = Ui.dp(getContext(), 1.4f);
        float[] hs = new float[]{Ui.dp(getContext(), 5), Ui.dp(getContext(), 9),
                Ui.dp(getContext(), 7)};
        float total = 3 * bw + 2 * gap;
        float bx = cx - total / 2f;
        for (int i = 0; i < 3; i++) {
            float top = cy - hs[i] / 2f;
            canvas.drawRoundRect(new RectF(bx, top, bx + bw, top + hs[i]),
                    bw / 2f, bw / 2f, bars);
            bx += bw + gap;
        }
    }

    @Override
    public boolean performClick() {
        super.performClick();
        boolean next = !checked;
        setChecked(next);
        if (listener != null) listener.onChanged(this, next);
        return true;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        setMeasuredDimension(Ui.dp(getContext(), 54), Ui.dp(getContext(), 32));
    }

    @Override
    protected void onDetachedFromWindow() {
        if (animator != null) animator.cancel();
        super.onDetachedFromWindow();
    }
}
