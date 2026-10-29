package com.eqlab.equalizer;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;

/**
 * 스펙트럼 막대(원음·적용 후·피크), EQ 곡선, 대역별 조절점(11개)을 그리는 뷰.
 * 조절점 근처를 위아래로 끌어 해당 대역의 게인을 바꿀 수 있다.
 * 가로 위치는 대역 번호에 비례한다(0번 20Hz ... 마지막 16kHz). 막대는 31Hz(1번)부터 16kHz까지만 분석한다.
 */
public class SpectrumView extends View {
    interface Callback {
        void onBand(int band, int gainDb);
    }

    private static final int LAST = EqEngine.BANDS - 1;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rect = new RectF();

    private final float[] peaks = new float[SpectrumState.BARS];
    private final long[] peakTime = new long[SpectrumState.BARS];
    private long lastFrame = 0;

    private int[] gains = new int[EqEngine.BANDS];
    private boolean eqOn = true;
    private boolean showOrig = true;
    private boolean showPost = true;
    private int active = 0;
    private int dragging = -1;
    private Callback callback;

    public SpectrumView(Context c) {
        super(c);
        line.setStyle(Paint.Style.STROKE);
        text.setTextSize(dp(11));
    }

    void setCallback(Callback cb) {
        callback = cb;
    }

    void setModel(int[] g, boolean on, int activeBand) {
        gains = g.clone();
        eqOn = on;
        active = activeBand;
        invalidate();
    }

    void setShow(boolean orig, boolean post) {
        showOrig = orig;
        showPost = post;
        invalidate();
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    private float gainAt(double pos) {
        int lo = Math.max(0, Math.min(LAST - 1, (int) Math.floor(pos)));
        double f = pos - lo;
        double t = (1 - Math.cos(Math.PI * f)) / 2;
        float a = eqOn ? gains[lo] : 0;
        float b = eqOn ? gains[lo + 1] : 0;
        return (float) (a + (b - a) * t);
    }

    private static float level(float db) {
        return Math.max(0f, Math.min(1f, (db + 90f) / 70f));
    }

    // 그리기 영역
    private float plotL() { return dp(20); }
    private float plotR() { return getWidth() - dp(20); }
    private float plotT() { return dp(38); }
    private float plotB() { return getHeight() - dp(30); }

    @Override
    protected void onDraw(Canvas canvas) {
        float l = plotL();
        float r = plotR();
        float t = plotT();
        float b = plotB();
        float w = r - l;
        float h = b - t;
        long now = SystemClock.uptimeMillis();
        float dt = lastFrame == 0 ? 0.016f : Math.min(0.1f, (now - lastFrame) / 1000f);
        lastFrame = now;
        boolean live = SpectrumState.running;
        float[] db = SpectrumState.db;

        // 막대 (31Hz 위치부터 오른쪽 끝까지)
        int n = SpectrumState.BARS;
        float gap = dp(3);
        float barL = l + w / LAST;
        float bw = (r - barL - gap * (n - 1)) / n;
        for (int i = 0; i < n; i++) {
            float x = barL + i * (bw + gap);
            float g = gainAt(1 + i / (double) (n - 1) * (LAST - 1));
            float orig = live ? level(db[i]) : 0f;
            float post = live ? level(db[i] + g) : 0f;
            if (showOrig && orig > 0) {
                fill.setColor(Theme.ORIG);
                rect.set(x, b - orig * h, x + bw, b);
                canvas.drawRoundRect(rect, dp(2), dp(2), fill);
            }
            if (showPost && post > 0) {
                fill.setColor(g > 0.5f ? Theme.ACCENT : Theme.CUT);
                rect.set(x + bw * 0.2f, b - post * h, x + bw * 0.8f, b);
                canvas.drawRoundRect(rect, dp(2), dp(2), fill);
            }
            // 피크 홀드: 최고점에 잠시 머물렀다가 천천히 내려온다
            if (post >= peaks[i]) {
                peaks[i] = post;
                peakTime[i] = now;
            } else if (now - peakTime[i] > 450) {
                peaks[i] = Math.max(post, peaks[i] - 0.5f * dt);
            }
            if (showPost && live && peaks[i] > 0.02f) {
                fill.setColor(Theme.TEXT);
                float py = b - peaks[i] * h;
                canvas.drawRect(x + bw * 0.1f, py - dp(1), x + bw * 0.9f, py + dp(1), fill);
            }
        }

        // 0dB 기준선
        float midY = t + h / 2f;
        line.setColor(Theme.OUTLINE);
        line.setStrokeWidth(dp(1));
        canvas.drawLine(l, midY, r, midY, line);

        // EQ 곡선
        path.reset();
        float amp = h / 2f * 0.88f;
        for (int k = 0; k <= 60; k++) {
            float x = l + (k / 60f) * w;
            float y = midY - gainAt(k / 60.0 * LAST) / 12f * amp;
            if (k == 0) path.moveTo(x, y);
            else path.lineTo(x, y);
        }
        line.setColor(Theme.TEXT);
        line.setStrokeWidth(dp(2.5f));
        line.setStrokeJoin(Paint.Join.ROUND);
        canvas.drawPath(path, line);

        // 조절점
        for (int i = 0; i < EqEngine.BANDS; i++) {
            float x = l + i / (float) LAST * w;
            float y = midY - (eqOn ? gains[i] : 0) / 12f * amp;
            boolean act = i == active;
            float rad = dp(act ? 8 : 5);
            fill.setColor(act ? Theme.ACCENT : Theme.TRACK);
            canvas.drawCircle(x, y, rad, fill);
            line.setColor(act ? Theme.TEXT : Theme.ACCENT);
            line.setStrokeWidth(dp(2));
            canvas.drawCircle(x, y, rad, line);
        }

        // 글자
        text.setColor(Theme.MUTED);
        text.setTextAlign(Paint.Align.LEFT);
        canvas.drawText("20", l, getHeight() - dp(9), text);
        text.setTextAlign(Paint.Align.CENTER);
        String[] marks = {"31", "250", "1k", "4k"};
        int[] idx = {1, 4, 6, 8};
        for (int i = 0; i < marks.length; i++) {
            canvas.drawText(marks[i], l + idx[i] / (float) LAST * w, getHeight() - dp(9), text);
        }
        text.setTextAlign(Paint.Align.RIGHT);
        canvas.drawText("16k Hz", r, getHeight() - dp(9), text);

        text.setTextAlign(Paint.Align.LEFT);
        canvas.drawText("점을 끌어 조절", dp(14), dp(24), text);

        String label = EqEngine.LABEL[active] + " Hz  " + format(gains[active]) + " dB";
        text.setTextAlign(Paint.Align.CENTER);
        float tw = text.measureText(label) + dp(20);
        rect.set(getWidth() - dp(12) - tw, dp(9), getWidth() - dp(12), dp(31));
        fill.setColor(Theme.SURFACE);
        canvas.drawRoundRect(rect, dp(11), dp(11), fill);
        text.setColor(Theme.ACCENT);
        canvas.drawText(label, rect.centerX(), rect.centerY() + dp(4), text);

        if (!live) {
            text.setColor(Theme.MUTED);
            canvas.drawText("스펙트럼을 켜면 소리가 막대로 보여요", getWidth() / 2f, b - dp(10), text);
        } else {
            postInvalidateOnAnimation();
        }
    }

    private static String format(int v) {
        return v > 0 ? "+" + v : v < 0 ? "−" + Math.abs(v) : "0";
    }

    // 터치: 가장 가까운 조절점을 잡고 위아래로 끈다
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        float l = plotL();
        float w = plotR() - l;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                if (e.getY() < plotT() - dp(14) || e.getY() > plotB() + dp(6)) return false;
                int best = -1;
                float bestDist = Float.MAX_VALUE;
                for (int i = 0; i < EqEngine.BANDS; i++) {
                    float d = Math.abs(e.getX() - (l + i / (float) LAST * w));
                    if (d < bestDist) {
                        bestDist = d;
                        best = i;
                    }
                }
                if (best < 0 || bestDist > w / LAST * 0.6f) return false;
                dragging = best;
                getParent().requestDisallowInterceptTouchEvent(true);
                move(e.getY());
                return true;
            }
            case MotionEvent.ACTION_MOVE:
                if (dragging >= 0) {
                    move(e.getY());
                    return true;
                }
                return false;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                dragging = -1;
                return true;
            default:
                return true;
        }
    }

    private void move(float y) {
        float t = plotT();
        float h = plotB() - t;
        float midY = t + h / 2f;
        float amp = h / 2f * 0.88f;
        int g = Math.round((midY - y) / amp * 12f);
        g = Math.max(-12, Math.min(12, g));
        if (callback != null) callback.onBand(dragging, g);
    }
}
