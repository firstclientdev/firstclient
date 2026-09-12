package com.firstclient.screen.theme;

/**
 * Shared dark-theme palette and animation timings for the FirstClient UI.
 * Accent blue is reserved for active elements, toggles and the "Client" half
 * of the title.
 */
public final class Theme {
    private Theme() {
    }

    // Backgrounds (ARGB)
    public static final int OVERLAY = 0x99000000;
    public static final int PANEL = 0xF2141820;
    public static final int PANEL_BORDER = 0xFF2A2F3A;
    public static final int SIDEBAR = 0xF20F1218;
    public static final int CARD = 0xFF1C212B;
    public static final int CARD_HOVER = 0xFF232A38;
    public static final int CARD_SELECTED_BORDER = 0xFF2E7CF6;
    public static final int FIELD_BG = 0xFF10141B;
    public static final int FIELD_BORDER = 0xFF2C3340;
    public static final int FIELD_DISABLED = 0xFF171B23;

    // Text
    public static final int TEXT_PRIMARY = 0xFFFFFFFF;
    public static final int TEXT_SECONDARY = 0xFF9AA3B2;
    public static final int TEXT_MUTED = 0xFF6B7484;

    // Accent
    public static final int ACCENT = 0xFF2E7CF6;
    public static final int ACCENT_HOVER = 0xFF4A90FF;
    public static final int ACCENT_DIM = 0x402E7CF6;
    public static final int SUCCESS = 0xFF34C77B;
    public static final int DANGER = 0xFFF0524F;

    // Toggle
    public static final int TOGGLE_ON = 0xFF2E7CF6;
    public static final int TOGGLE_OFF = 0xFF3A4252;
    public static final int TOGGLE_KNOB = 0xFFFFFFFF;

    // Animations (ms)
    public static final long OPEN_MS = 220L;
    public static final long CLOSE_MS = 150L;
    public static final int SLIDE_PX = 14;

    /** Ease-out cubic for open/hover motion. */
    public static float easeOutCubic(float t) {
        if (t <= 0.0F) return 0.0F;
        if (t >= 1.0F) return 1.0F;
        float u = 1.0F - t;
        return 1.0F - u * u * u;
    }

    /** Returns the color with its alpha channel scaled by [0,1]. */
    public static int withAlpha(int argb, float alpha) {
        if (alpha <= 0.0F) return argb & 0x00FFFFFF;
        if (alpha >= 1.0F) return argb;
        int a = Math.round(((argb >>> 24) & 0xFF) * alpha);
        return (a << 24) | (argb & 0x00FFFFFF);
    }
}
