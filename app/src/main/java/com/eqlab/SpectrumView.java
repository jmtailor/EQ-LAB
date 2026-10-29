package com.eqlab;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

/** 10개 대역 막대를 그리는 간단한 스펙트럼 표시. */
public class SpectrumView extends View {
    private final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bg = new Paint();

    public SpectrumView(Context c) {
        super(c);
        bar.setColor(0xFFFFB547);
        text.setColor(0xFF555555);
        text.setTextSize(28f);
        text.setTextAlign(Paint.Align.CENTER);
        bg.setColor(0xFF1B1F26);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        canvas.drawRect(0, 0, w, h, bg);
        float[] lv = CaptureState.levels;
        int n = 10;
        float gap = 10f;
        float bw = (w - gap * (n + 1)) / n;
        float labelH = 40f;
        float usable = h - labelH - gap;
        for (int i = 0; i < n; i++) {
            float x = gap + i * (bw + gap);
            float v = Math.max(0.02f, Math.min(1f, lv[i]));
            float top = gap + usable * (1 - v);
            canvas.drawRect(x, top, x + bw, gap + usable, bar);
            text.setColor(0xFFB8C0CA);
            canvas.drawText(EffectEngine.LABEL[i], x + bw / 2, h - 8f, text);
        }
    }
}
