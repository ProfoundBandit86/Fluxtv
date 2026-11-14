package com.fluxtv.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.View;

/** Four rounded bars that bounce with your voice while Search is listening (and idle-breathe otherwise). */
public class VoiceWave extends View {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private float level = 0f, shown = 0f;
    private boolean active;
    private final int[] colors;

    public VoiceWave(Context c) {
        super(c);
        int a = Ui.ACCENT;
        colors = new int[]{a, Ui.alpha(a, 0.75f), Ui.TEXT, Ui.alpha(a, 0.55f)};
    }

    /** rmsDb from SpeechRecognizer (roughly −2…10). */
    public void setLevel(float rmsDb) {
        level = Math.max(0f, Math.min(1f, (rmsDb + 2f) / 12f));
    }

    public void setActive(boolean on) {
        active = on;
        if (!on) level = 0;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        shown += (level - shown) * 0.25f;
        float w = getWidth(), h = getHeight();
        float bar = Math.min(w / 7f, h / 2.2f);
        float gap = bar * 0.55f;
        float total = 4 * bar + 3 * gap;
        float x = (w - total) / 2f;
        double t = SystemClock.uptimeMillis() / 160.0;
        for (int i = 0; i < 4; i++) {
            float wobble = (float) (0.5 + 0.5 * Math.sin(t + i * 1.3));
            float amount = active ? 0.18f + 0.82f * Math.min(1f, shown * (0.55f + 0.6f * wobble)) : 0.12f + 0.06f * wobble;
            float bh = Math.max(bar, h * amount);
            r.set(x, (h - bh) / 2f, x + bar, (h + bh) / 2f);
            paint.setColor(colors[i]);
            canvas.drawRoundRect(r, bar / 2f, bar / 2f, paint);
            x += bar + gap;
        }
        if (isShown()) postInvalidateOnAnimation();
    }
}
