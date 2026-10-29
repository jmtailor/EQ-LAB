package com.eqlab.equalizer;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

/** 대역 하나의 굵은 알약형 세로 슬라이더. 위로 올리면 민트, 내리면 청록으로 채워진다. */
final class BandColumn extends View {
    interface Callback {
        void onUser(int band, int gainDb);
    }

    private final int band;
    private final Callback callback;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path clip = new Path();
    private final RectF track = new RectF();
    private final RectF knob = new RectF();
    private final int slop;

    private int gain = 0;
    private boolean eqOn = true;
    private boolean active = false;

    private float downX;
    private float downY;
    private boolean tracking = false;

    BandColumn(Context c, int band, Callback cb) {
        super(c);
        this.band = band;
        this.callback = cb;
        this.slop = ViewConfiguration.get(c).getScaledTouchSlop();
        textPaint.setTextAlign(Paint.Align.CENTER);
        setContentDescription(EqEngine.LABEL[band] + " 헤르츠 대역");
    }

    void set(int gainDb, boolean on, boolean isActive) {
        gain = gainDb;
        eqOn = on;
        active = isActive;
        invalidate();
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    static int heightPx(Context c) {
        return Theme.dp(c, 24 + 150 + 28);
    }

    private float trackTop() {
        return dp(24);
    }

    private float trackHeight() {
        return dp(150);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float cx = getWidth() / 2f;
        float tw = dp(44);
        float tt = trackTop();
        float th = trackHeight();
        track.set(cx - tw / 2, tt, cx + tw / 2, tt + th);
        float mid = tt + th / 2f;
        float unit = (th / 2f) / 12f;
        int shown = eqOn ? gain : 0;

        // 위 숫자
        textPaint.setTextSize(dp(13));
        textPaint.setFakeBoldText(false);
        textPaint.setColor(gain > 0 ? Theme.ACCENT : gain < 0 ? Theme.CUT_TEXT : Theme.MUTED);
        String label = gain > 0 ? "+" + gain : gain < 0 ? "−" + Math.abs(gain) : "0";
        canvas.drawText(label, cx, dp(16), textPaint);

        // 막대 바탕
        paint.setColor(Theme.TRACK);
        canvas.drawRoundRect(track, tw / 2, tw / 2, paint);

        // 채움 (둥근 바탕 모양 안쪽으로 잘라 그림)
        canvas.save();
        clip.reset();
        clip.addRoundRect(track, tw / 2, tw / 2, Path.Direction.CW);
        canvas.clipPath(clip);
        paint.setColor(Theme.OUTLINE);
        canvas.drawRect(track.left, mid - dp(1), track.right, mid + dp(1), paint);
        if (shown > 0) {
            paint.setColor(Theme.ACCENT);
            canvas.drawRect(track.left, mid - shown * unit, track.right, mid, paint);
        } else if (shown < 0) {
            paint.setColor(Theme.CUT);
            canvas.drawRect(track.left, mid, track.right, mid - shown * unit, paint);
        }
        canvas.restore();

        // 손잡이
        float ky = mid - shown * unit;
        knob.set(track.left + dp(7), ky - dp(3), track.right - dp(7), ky + dp(3));
        paint.setColor(active ? 0xFFFFFFFF : 0xFFCFE7E3);
        canvas.drawRoundRect(knob, dp(3), dp(3), paint);

        // 아래 주파수
        textPaint.setTextSize(dp(12));
        textPaint.setColor(Theme.MUTED);
        canvas.drawText(EqEngine.LABEL[band], cx, tt + th + dp(20), textPaint);
    }

    private int gainFromY(float y) {
        float tt = trackTop();
        float th = trackHeight();
        float frac = (y - tt) / th;
        int g = Math.round(12f - frac * 24f);
        return Math.max(-12, Math.min(12, g));
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getX();
                downY = e.getY();
                tracking = false;
                return true;
            case MotionEvent.ACTION_MOVE: {
                float dx = Math.abs(e.getX() - downX);
                float dy = Math.abs(e.getY() - downY);
                if (!tracking && dy > slop && dy > dx) {
                    tracking = true;
                    getParent().requestDisallowInterceptTouchEvent(true);
                }
                if (tracking) callback.onUser(band, gainFromY(e.getY()));
                return true;
            }
            case MotionEvent.ACTION_UP:
                if (!tracking && Math.abs(e.getX() - downX) < slop && Math.abs(e.getY() - downY) < slop) {
                    callback.onUser(band, gainFromY(e.getY()));
                }
                tracking = false;
                return true;
            case MotionEvent.ACTION_CANCEL:
                tracking = false;
                return true;
            default:
                return true;
        }
    }
}
