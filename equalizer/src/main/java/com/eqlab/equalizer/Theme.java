package com.eqlab.equalizer;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;

/** 디자인 초안의 청록·민트 색상과 공통 도우미. */
final class Theme {
    private Theme() {}

    static final int BG = 0xFF0B1112;
    static final int SURFACE = 0xFF121C1D;
    static final int BORDER = 0xFF1F2F30;
    static final int TEXT = 0xFFE8F4F2;
    static final int MUTED = 0xFF8FA8A6;
    static final int ACCENT = 0xFF5FE3C8;
    static final int CUT = 0xFF4B9CB5;
    static final int CUT_TEXT = 0xFF8CC4D8;
    static final int TRACK = 0xFF0A1516;
    static final int ORIG = 0xFF233A3C;
    static final int ON_ACCENT = 0xFF06201B;
    static final int SELECTED = 0xFF12302B;
    static final int OUTLINE = 0xFF2B4143;

    static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    static GradientDrawable box(Context c, int fill, int stroke, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(c, radiusDp));
        if (stroke != 0) d.setStroke(Math.max(1, dp(c, 1)), stroke);
        return d;
    }
}
