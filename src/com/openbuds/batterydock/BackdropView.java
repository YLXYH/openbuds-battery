package com.openbuds.batterydock;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.view.View;

/** Static ambient background. Drawn only when the window is invalidated. */
final class BackdropView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int w;
    private int h;

    BackdropView(Context context) {
        super(context);
        setWillNotDraw(false);
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldw, int oldh) {
        super.onSizeChanged(width, height, oldw, oldh);
        w = width;
        h = height;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        canvas.drawColor(Ui.INK);
        if (w <= 0 || h <= 0) return;

        if (Ui.dark) {
            paint.setShader(new RadialGradient(w * 0.88f, h * 0.06f, w * 0.82f,
                    new int[]{0x38E7B27A, 0x12E7B27A, 0x000B0E12},
                    new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
            canvas.drawCircle(w * 0.88f, h * 0.06f, w * 0.82f, paint);
            paint.setShader(new RadialGradient(w * 0.02f, h * 0.78f, w * 0.72f,
                    new int[]{0x207ED6C1, 0x087ED6C1, 0x000B0E12},
                    new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
            canvas.drawCircle(w * 0.02f, h * 0.78f, w * 0.72f, paint);
            paint.setShader(new LinearGradient(0, 0, 0, h,
                    new int[]{0x000B0E12, 0x000B0E12, 0x660B0E12},
                    new float[]{0f, 0.7f, 1f}, Shader.TileMode.CLAMP));
        } else {
            paint.setShader(new RadialGradient(w * 0.9f, h * 0.04f, w * 0.75f,
                    new int[]{0x22B06A28, 0x0AB06A28, 0x00F6F2EB},
                    new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
            canvas.drawCircle(w * 0.9f, h * 0.04f, w * 0.75f, paint);
            paint.setShader(new RadialGradient(w * 0.02f, h * 0.82f, w * 0.65f,
                    new int[]{0x14238A75, 0x06238A75, 0x00F6F2EB},
                    new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
            canvas.drawCircle(w * 0.02f, h * 0.82f, w * 0.65f, paint);
            paint.setShader(new LinearGradient(0, 0, 0, h,
                    new int[]{0x00F6F2EB, 0x00F6F2EB, 0x22F6F2EB},
                    new float[]{0f, 0.7f, 1f}, Shader.TileMode.CLAMP));
        }
        canvas.drawRect(0, 0, w, h, paint);
        paint.setShader(null);
    }
}
