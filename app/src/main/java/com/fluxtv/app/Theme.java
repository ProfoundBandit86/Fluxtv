package com.fluxtv.app;

import android.graphics.Color;

/** Colour presets chosen in Settings. Saved on the TV and applied to every screen. */
public final class Theme {

    public static final String[] ACCENT_NAMES = {"Violet", "Amber", "Sky", "Mint", "Rose", "Crimson", "Snow",
            "Teal", "Coral", "Gold", "Lavender", "Lime"};
    public static final String[] ACCENT_HEX = {"#8B7CFF", "#E5A44B", "#4FB3FF", "#3DD68C", "#FF6B9A", "#FF5A4E", "#E8E8EE",
            "#2CC6B5", "#FF7F66", "#F2C14E", "#B9A5FF", "#A6E05A"};

    public static final String[] BG_NAMES = {"Midnight", "OLED black", "Slate", "Plum", "Ocean", "Forest", "Ember", "Graphite", "Mocha"};
    /** background, panel, raised surface, border */
    private static final String[][] BG_HEX = {
            {"#0B0B10", "#111118", "#1E1E2A", "#22222E"},
            {"#000000", "#0A0A0D", "#1A1A20", "#1C1C22"},
            {"#0E1218", "#141A22", "#1F2731", "#26303B"},
            {"#110C16", "#18111F", "#251B30", "#2D2238"},
            {"#07131F", "#0C1B2A", "#16283A", "#1C3045"},
            {"#0A120E", "#0F1A14", "#1A2A21", "#203226"},
            {"#140C0A", "#1C1210", "#2C1D19", "#36241F"},
            {"#121212", "#1A1A1A", "#262626", "#2E2E2E"},
            {"#15100D", "#1D1713", "#2B221C", "#342920"},
    };

    /** Ready-made looks: name, accent index, background index. */
    public static final Object[][] THEMES = {
            {"Flux", 0, 0}, {"OLED", 6, 1}, {"Ocean", 2, 4}, {"Forest", 3, 5}, {"Sunset", 8, 6},
            {"Royal", 9, 3}, {"Graphite", 7, 7}, {"Mocha", 1, 8}, {"Neon", 4, 1}, {"Lavender", 10, 2},
            {"Lime", 11, 7}, {"Crimson", 5, 0},
    };

    /** Index into THEMES of the current colours, or -1 when they're a custom mix. */
    public static int themeIndex() {
        for (int i = 0; i < THEMES.length; i++) {
            if ((int) THEMES[i][1] == accentIndex() && (int) THEMES[i][2] == bgIndex()) return i;
        }
        return -1;
    }

    public static void setTheme(int i) {
        Store.put("theme_accent", String.valueOf(THEMES[i][1]));
        Store.put("theme_bg", String.valueOf(THEMES[i][2]));
        apply();
    }

    public static int bgColor(int bgIndex, int part) {
        return Color.parseColor(BG_HEX[bgIndex][part]);
    }

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
