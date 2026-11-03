package com.eqlab.equalizer;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

/**
 * 스펙트럼 막대(원음·적용 후·피크), EQ 곡선, 대역별 조절점을 그리는 뷰.
 * 가로는 로그 주파수(20Hz~16kHz)다. 점을 끌어 게인을 바꾸고, 빈 곳을 길게 누르면 그 주파수에 점을 추가한다.
 * 추가한 점은 좌우로도 옮길 수 있고, 길게 누르면 삭제한다. 기본 대역은 위아래로만 움직인다.
 */
public class SpectrumView extends View {
    interface Callback {
        void onBand(int band, int gainDb);

        /** 추가한 점의 주파수를 옮길 때. */
        void onMoveFreq(int band, int hz);

        /** 빈 곳을 길게 눌렀을 때. 새 점의 번호를 돌려주고, 실패하면 음수. */
        int onAdd(int hz);

        /** 점을 길게 눌렀을 때(기본 대역이면 화면 쪽에서 안내만 한다). */
        void onRemove(int band);
    }

    private static final double LN_MIN = Math.log(EqEngine.MIN_HZ);
    private static final double LN_SPAN = Math.log(EqEngine.MAX_HZ) - LN_MIN;
    /** 막대 32개의 첫 중심 주파수와 간격 비(CaptureService와 같은 값). */
    private static final double BAR_FIRST_HZ = 31.0;
    private static final double BAR_RATIO = Math.pow(16000.0 / 31.0, 1.0 / (SpectrumState.BARS - 1));
    private static final int[] GRID_HZ = {100, 1000, 5000};
    private static final String[] GRID_LABEL = {"100", "1k", "5k"};

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rect = new RectF();
    private final int slop;

    private final float[] peaks = new float[SpectrumState.BARS];
    private final long[] peakTime = new long[SpectrumState.BARS];
    private final int[] barColors = new int[SpectrumState.BARS];
    private final LinearGradient[] barShaders = new LinearGradient[SpectrumState.BARS];
    private LinearGradient curveShader;
    private long lastFrame = 0;

    private EqEngine.Model m = EqEngine.model();
    private boolean eqOn = true;
    private boolean showOrig = true;
    private boolean showPost = true;
    private int active = 0;
    private int dragging = -1;
    private float grabDx = 0f;
    private float grabDy = 0f;
    private float downX = 0f;
    private float downY = 0f;
    private boolean moved = false;
    private Callback callback;

    private final Runnable longPress = new Runnable() {
        @Override
        public void run() {
            onLongPress();
        }
    };

    public SpectrumView(Context c) {
        super(c);
        slop = ViewConfiguration.get(c).getScaledTouchSlop();
        line.setStyle(Paint.Style.STROKE);
        text.setTextSize(dp(11));
        for (int i = 0; i < SpectrumState.BARS; i++) {
            double fc = BAR_FIRST_HZ * Math.pow(BAR_RATIO, i);
            barColors[i] = Theme.spectrumAt((float) ((Math.log(fc) - LN_MIN) / LN_SPAN));
        }
    }

    void setCallback(Callback cb) {
        callback = cb;
    }

    void setModel(EqEngine.Model model, boolean on, int activeBand) {
        m = model;
        eqOn = on;
        active = Math.max(0, Math.min(m.n - 1, activeBand));
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

    private static float level(float db) {
        return Math.max(0f, Math.min(1f, (db + 90f) / 70f));
    }

    // 그리기 영역
    private float plotL() { return dp(20); }
    private float plotR() { return getWidth() - dp(20); }
    private float plotT() { return dp(38); }
    private float plotB() { return getHeight() - dp(30); }

    private float xOf(double hz) {
        double t = (Math.log(hz) - LN_MIN) / LN_SPAN;
        return plotL() + (float) t * (plotR() - plotL());
    }

    private double hzOf(float x) {
        double t = (x - plotL()) / (plotR() - plotL());
        t = Math.max(0.0, Math.min(1.0, t));
        return Math.exp(LN_MIN + t * LN_SPAN);
    }

    private float midY() {
        return plotT() + (plotB() - plotT()) / 2f;
    }

    private float amp() {
        return (plotB() - plotT()) / 2f * 0.88f;
    }

    private float yOfGain(float gain) {
        return midY() - gain / 12f * amp();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        float t = plotT();
        float b = plotB();
        for (int i = 0; i < SpectrumState.BARS; i++) {
            int top = Theme.mix(barColors[i], Theme.TEXT, 0.35f);
            int bottom = Theme.mix(barColors[i], Theme.BG, 0.45f);
            barShaders[i] = new LinearGradient(0, t, 0, b, top, bottom, Shader.TileMode.CLAMP);
        }
        curveShader = new LinearGradient(plotL(), 0, plotR(), 0, Theme.SPECTRUM, null, Shader.TileMode.CLAMP);
    }

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
        float midY = midY();
        float amp = amp();
        int bars = SpectrumState.BARS;

        // 격자: 주파수 세로선과 ±6, ±12dB 가로선
        line.setShader(null);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(dp(1));
        line.setColor(Theme.BORDER);
        for (int hz : GRID_HZ) {
            float gx = xOf(hz);
            canvas.drawLine(gx, t, gx, b, line);
        }
        int[] marks = {-12, -6, 6, 12};
        for (int mk : marks) {
            float gy = midY - mk / 12f * amp;
            canvas.drawLine(l, gy, r, gy, line);
        }

        // 막대: 주파수 위치에 맞춰 놓고, 주파수별 색과 세로 그라데이션으로 칠한다
        float step = xOf(BAR_FIRST_HZ * BAR_RATIO) - xOf(BAR_FIRST_HZ);
        float bw = step - dp(2.5f);
        for (int i = 0; i < bars; i++) {
            double fc = BAR_FIRST_HZ * Math.pow(BAR_RATIO, i);
            float x = xOf(fc) - bw / 2f;
            float g = eqOn ? m.at(fc) : 0f;
            float orig = live ? level(db[i]) : 0f;
            float post = live ? level(db[i] + g) : 0f;
            if (showOrig && orig > 0) {
                fill.setShader(null);
                fill.setColor(Theme.mix(barColors[i], Theme.BG, 0.72f));
                rect.set(x, b - orig * h, x + bw, b);
                canvas.drawRoundRect(rect, dp(2), dp(2), fill);
            }
            if (showPost && post > 0) {
                fill.setShader(barShaders[i]);
                fill.setAlpha(g < -0.5f ? 150 : 255);
                rect.set(x + bw * 0.15f, b - post * h, x + bw * 0.85f, b);
                canvas.drawRoundRect(rect, dp(2), dp(2), fill);
                fill.setShader(null);
                fill.setAlpha(255);
            }
            // 피크 홀드: 최고점에 잠시 머물렀다가 천천히 내려온다
            if (post >= peaks[i]) {
                peaks[i] = post;
                peakTime[i] = now;
            } else if (now - peakTime[i] > 450) {
                peaks[i] = Math.max(post, peaks[i] - 0.5f * dt);
            }
            if (showPost && live && peaks[i] > 0.02f) {
                fill.setColor(Theme.mix(barColors[i], Theme.TEXT, 0.65f));
                float py = b - peaks[i] * h;
                canvas.drawRect(x + bw * 0.1f, py - dp(1), x + bw * 0.9f, py + dp(1), fill);
            }
        }

        // 0dB 기준선
        line.setColor(Theme.OUTLINE);
        line.setStrokeWidth(dp(1));
        canvas.drawLine(l, midY, r, midY, line);

        // EQ 곡선: 바깥으로 번지는 빛 두 겹 + 가운데 흰 선
        path.reset();
        int steps = 120;
        for (int k = 0; k <= steps; k++) {
            float x = l + (k / (float) steps) * w;
            double hz = Math.exp(LN_MIN + (k / (double) steps) * LN_SPAN);
            float y = midY - (eqOn ? m.at(hz) : 0f) / 12f * amp;
            if (k == 0) path.moveTo(x, y);
            else path.lineTo(x, y);
        }
        line.setStrokeJoin(Paint.Join.ROUND);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setShader(curveShader);
        line.setColor(Theme.TEXT);
        line.setStrokeWidth(dp(9));
        line.setAlpha(40);
        canvas.drawPath(path, line);
        line.setStrokeWidth(dp(5));
        line.setAlpha(95);
        canvas.drawPath(path, line);
        line.setShader(null);
        line.setColor(Theme.TEXT);
        line.setStrokeWidth(dp(2));
        canvas.drawPath(path, line);

        // 조절점: 기본 대역은 속이 빈 점, 추가한 점은 색이 채워진 점
        for (int i = 0; i < m.n; i++) {
            float x = xOf(m.freq[i]);
            float y = midY - (eqOn ? m.gain[i] : 0) / 12f * amp;
            int hue = Theme.spectrumAt((x - l) / w);
            boolean act = i == active;
            float rad = dp(act ? 8 : 5.5f);
            if (m.extra[i]) {
                fill.setColor(hue);
                line.setColor(act ? Theme.TEXT : Theme.mix(hue, Theme.TEXT, 0.55f));
            } else {
                fill.setColor(act ? hue : Theme.TRACK);
                line.setColor(act ? Theme.TEXT : hue);
            }
            canvas.drawCircle(x, y, rad, fill);
            line.setStrokeWidth(dp(2));
            canvas.drawCircle(x, y, rad, line);
        }

        // 글자
        text.setColor(Theme.MUTED);
        text.setTextAlign(Paint.Align.LEFT);
        canvas.drawText("20", l, getHeight() - dp(9), text);
        text.setTextAlign(Paint.Align.CENTER);
        for (int i = 0; i < GRID_HZ.length; i++) {
            canvas.drawText(GRID_LABEL[i], xOf(GRID_HZ[i]), getHeight() - dp(9), text);
        }
        text.setTextAlign(Paint.Align.RIGHT);
        canvas.drawText("16k Hz", r, getHeight() - dp(9), text);

        text.setTextAlign(Paint.Align.LEFT);
        canvas.drawText("점을 끌어 조절 · 빈 곳 길게 눌러 추가", dp(14), dp(24), text);

        String label = m.label(active) + " Hz  " + format(m.gain[active]) + " dB";
        text.setTextAlign(Paint.Align.CENTER);
        float tw = text.measureText(label) + dp(20);
        rect.set(getWidth() - dp(12) - tw, dp(9), getWidth() - dp(12), dp(31));
        fill.setColor(Theme.SURFACE);
        canvas.drawRoundRect(rect, dp(11), dp(11), fill);
        text.setColor(Theme.spectrumAt((xOf(m.freq[active]) - l) / w));
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

    // ------------------------------------------------------------------ 터치

    /** 손가락 가까이에 점이 있으면 그 번호, 없으면 -1. */
    private int pointNear(float x, float y) {
        int best = -1;
        float bestDist = dp(20);
        for (int i = 0; i < m.n; i++) {
            float px = xOf(m.freq[i]);
            float py = yOfGain(eqOn ? m.gain[i] : 0);
            float d = (float) Math.hypot(x - px, y - py);
            if (d < bestDist) {
                bestDist = d;
                best = i;
            }
        }
        return best;
    }

    /** x 가 가장 가까운 점의 번호. */
    private int columnNear(float x) {
        int best = 0;
        float bestDist = Float.MAX_VALUE;
        for (int i = 0; i < m.n; i++) {
            float d = Math.abs(x - xOf(m.freq[i]));
            if (d < bestDist) {
                bestDist = d;
                best = i;
            }
        }
        return best;
    }

    private void startDrag(int band, float x, float y) {
        dragging = band;
        grabDx = xOf(m.freq[band]) - x;
        grabDy = yOfGain(eqOn ? m.gain[band] : 0) - y;
        getParent().requestDisallowInterceptTouchEvent(true);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                if (e.getY() < plotT() - dp(14) || e.getY() > plotB() + dp(6)) return false;
                downX = e.getX();
                downY = e.getY();
                moved = false;
                dragging = -1;
                int hit = pointNear(downX, downY);
                if (hit >= 0) startDrag(hit, downX, downY);
                // 점 위든 빈 곳이든, 길게 누르면 삭제 또는 추가
                postDelayed(longPress, ViewConfiguration.getLongPressTimeout());
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                float x = e.getX();
                float y = e.getY();
                if (!moved && (Math.abs(x - downX) > slop || Math.abs(y - downY) > slop)) {
                    moved = true;
                    removeCallbacks(longPress);
                    // 끌기 시작 순간의 손가락 위치를 기준으로 다시 잡아서, 점이 튀지 않게 한다.
                    // 점이 없는 곳에서 세로로 끌기 시작하면 가장 가까운 대역을 잡는다.
                    if (dragging >= 0) {
                        startDrag(dragging, x, y);
                    } else if (Math.abs(y - downY) > Math.abs(x - downX)) {
                        startDrag(columnNear(downX), x, y);
                    }
                }
                if (moved && dragging >= 0) move(x, y);
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                removeCallbacks(longPress);
                dragging = -1;
                return true;
            default:
                return true;
        }
    }

    private void onLongPress() {
        if (moved || callback == null) return;
        if (dragging >= 0) {
            int band = dragging;
            dragging = -1;
            callback.onRemove(band);
        } else {
            int idx = callback.onAdd((int) Math.round(hzOf(downX)));
            if (idx >= 0 && idx < m.n) {
                // 만든 점을 손가락이 그대로 이어서 끌 수 있게 한다
                startDrag(idx, downX, downY);
            }
        }
    }

    private void move(float x, float y) {
        if (dragging < 0 || dragging >= m.n || callback == null) return;
        int g = Math.round((midY() - (y + grabDy)) / amp() * 12f);
        g = Math.max(-12, Math.min(12, g));
        callback.onBand(dragging, g);
        if (dragging < m.n && m.extra[dragging]) {
            callback.onMoveFreq(dragging, (int) Math.round(hzOf(x + grabDx)));
        }
    }
}
