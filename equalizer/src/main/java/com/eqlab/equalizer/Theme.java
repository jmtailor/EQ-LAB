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

    /** 스펙트럼 팔레트: 저음에서 고음으로 갈수록 민트 → 하늘 → 파랑 → 보라 → 분홍 → 주황. */
    static final int[] SPECTRUM = {0xFF2DE2B6, 0xFF35C8F0, 0xFF5B8CFF, 0xFFA066FF, 0xFFFF5FC2, 0xFFFFB04A};

    /** 두 색을 f(0~1) 비율로 섞는다. */
    static int mix(int a, int b, float f) {
        int ar = (a >> 16) & 255;
        int ag = (a >> 8) & 255;
        int ab = a & 255;
        int br = (b >> 16) & 255;
        int bg = (b >> 8) & 255;
        int bb = b & 255;
        int r = Math.round(ar + (br - ar) * f);
        int g = Math.round(ag + (bg - ag) * f);
        int bl = Math.round(ab + (bb - ab) * f);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    /** 팔레트에서 t(0~1) 위치의 색. */
    static int spectrumAt(float t) {
        float p = Math.max(0f, Math.min(1f, t)) * (SPECTRUM.length - 1);
        int i = Math.min(SPECTRUM.length - 2, (int) p);
        return mix(SPECTRUM[i], SPECTRUM[i + 1], p - i);
    }

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
