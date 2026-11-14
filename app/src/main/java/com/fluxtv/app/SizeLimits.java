package com.fluxtv.app;

/** Optional maximum file size per resolution for streams from add-ons (your own library is never limited). */
public final class SizeLimits {

    public static final String[] QUALITIES = {"4K", "1080p", "720p"};
    public static final double[][] CHOICES = {
            {0, 10, 20, 30, 50, 80},
            {0, 2, 4, 8, 15, 25},
            {0, 1, 2, 4, 8},
    };

    private SizeLimits() { }

    static String key(String quality) {
        return "max_gb_" + quality.toLowerCase(java.util.Locale.ROOT);
    }

    /** 0 = no limit. */
    public static double max(String quality) {
        String q = "SD".equals(quality) ? "720p" : quality;
        try {
            return Double.parseDouble(Store.get(key(q)));
        } catch (Exception e) {
            return 0;
        }
    }

    public static void set(String quality, double gb) {
        Store.put(key(quality), gb <= 0 ? "" : String.valueOf(gb));
    }

    /** True when the stream's known size is over the limit for its resolution. */
    public static boolean tooBig(String quality, double sizeGb) {
        double m = max(quality);
        return m > 0 && sizeGb > 0 && sizeGb > m;
    }

    public static String label(double gb) {
        return gb <= 0 ? "No limit" : (gb == Math.floor(gb) ? String.valueOf((int) gb) : String.valueOf(gb)) + " GB";
    }
}
