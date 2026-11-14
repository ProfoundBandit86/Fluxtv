package com.fluxtv.app;

import android.graphics.Color;

/** Colour presets chosen in Settings. Saved on the TV and applied to every screen. */
public final class Theme {

    public static final String[] ACCENT_NAMES = {"Violet", "Amber", "Sky", "Mint", "Rose", "Crimson", "Snow"};
    public static final String[] ACCENT_HEX = {"#8B7CFF", "#E5A44B", "#4FB3FF", "#3DD68C", "#FF6B9A", "#FF5A4E", "#E8E8EE"};

    public static final String[] BG_NAMES = {"Midnight", "OLED black", "Slate", "Plum"};
    /** background, panel, raised surface, border */
    private static final String[][] BG_HEX = {
            {"#0B0B10", "#111118", "#1E1E2A", "#22222E"},
            {"#000000", "#0A0A0D", "#1A1A20", "#1C1C22"},
            {"#0E1218", "#141A22", "#1F2731", "#26303B"},
            {"#110C16", "#18111F", "#251B30", "#2D2238"},
    };

    private static int version = 0;

    private Theme() { }

    public static int accentIndex() {
        return clamp(Store.get("theme_accent"), ACCENT_HEX.length);
    }

    public static int bgIndex() {
        return clamp(Store.get("theme_bg"), BG_HEX.length);
    }

    public static void setAccent(int i) {
        Store.put("theme_accent", String.valueOf(i));
        apply();
    }

    public static void setBackground(int i) {
        Store.put("theme_bg", String.valueOf(i));
        apply();
    }

    /** Bumps whenever colours change, so open screens know to rebuild. */
    public static int version() {
        return version;
    }

    public static void apply() {
        String[] bg = BG_HEX[bgIndex()];
        Ui.BG = Color.parseColor(bg[0]);
        Ui.PANEL = Color.parseColor(bg[1]);
        Ui.BUTTON = Color.parseColor(bg[2]);
        Ui.BORDER = Color.parseColor(bg[3]);
        Ui.ACCENT = Color.parseColor(ACCENT_HEX[accentIndex()]);
        version++;
    }

    private static int clamp(String v, int n) {
        try {
            int i = Integer.parseInt(v);
            return i >= 0 && i < n ? i : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
