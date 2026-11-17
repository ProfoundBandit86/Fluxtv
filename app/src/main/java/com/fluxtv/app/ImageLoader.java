package com.fluxtv.app;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.LruCache;
import android.widget.ImageView;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Tiny async image loader with an in-memory cache. */
public final class ImageLoader {

    private static final LruCache<String, Bitmap> CACHE =
            new LruCache<String, Bitmap>((int) (Runtime.getRuntime().maxMemory() / 8)) {
                @Override
                protected int sizeOf(String key, Bitmap b) {
                    return b.getByteCount();
                }
            };
    /**
     * Newest request first: when scrolling fast through rows, the posters now on screen load before the ones
     * already scrolled past.
     */
    private static final ExecutorService POOL = new java.util.concurrent.ThreadPoolExecutor(5, 5, 30, java.util.concurrent.TimeUnit.SECONDS,
            new java.util.concurrent.LinkedBlockingDeque<Runnable>() {
                @Override public boolean offer(Runnable r) { return super.offerFirst(r); }
            });
    /** One download per picture even when several views ask for it at once. */
    private static final java.util.Map<String, java.util.List<Runnable>> INFLIGHT = new java.util.HashMap<>();

    private ImageLoader() { }

    public static void load(ImageView view, String url, int targetWidthPx) {
        load(view, url, targetWidthPx, null);
    }

    /** onLoaded runs on the main thread once the picture is shown (not if it fails). */
    public static void load(ImageView view, String url, int targetWidthPx, Runnable onLoaded) {
        view.setTag(url);
        if (url == null || url.isEmpty()) {
            view.setImageDrawable(null);
            return;
        }
        Bitmap cached = CACHE.get(url);
        if (cached != null) {
            view.setImageBitmap(cached);
            if (onLoaded != null) onLoaded.run();
            return;
        }
        view.setImageDrawable(null);
        Runnable show = () -> {
            Bitmap b = CACHE.get(url);
            if (b != null && url.equals(view.getTag())) {
                view.setImageBitmap(b);
                if (onLoaded != null) onLoaded.run();
            }
        };
        synchronized (INFLIGHT) {
            java.util.List<Runnable> waiting = INFLIGHT.get(url);
            if (waiting != null) { waiting.add(show); return; }
            waiting = new java.util.ArrayList<>();
            waiting.add(show);
            INFLIGHT.put(url, waiting);
        }
        POOL.execute(() -> {
            java.util.List<Runnable> waiting;
            // Scrolled away before its turn came: skip the work (it is asked for again if it comes back).
            boolean wanted = url.equals(view.getTag());
            synchronized (INFLIGHT) {
                if (!wanted && INFLIGHT.get(url) != null && INFLIGHT.get(url).size() <= 1) { INFLIGHT.remove(url); return; }
            }
            Bitmap b = fetch(url, targetWidthPx);
            if (b != null) CACHE.put(url, b);
            synchronized (INFLIGHT) { waiting = INFLIGHT.remove(url); }
            if (b == null || waiting == null) return;
            App.MAIN.post(() -> { for (Runnable r : waiting) r.run(); });
        });
    }

    public interface Got { void run(Bitmap b); }

    /** A bitmap with transparency kept (channel logos). Calls back on the main thread; null if it failed. */
    public static Bitmap cached(String url) {
        return url == null || url.isEmpty() ? null : CACHE.get("a|" + url);
    }

    public static void bitmap(String url, int targetWidthPx, Got got) {
        if (url == null || url.isEmpty()) return;
        Bitmap c = CACHE.get("a|" + url);
        if (c != null) { got.run(c); return; }
        POOL.execute(() -> {
            Bitmap b = fetch(url, targetWidthPx, true);
            if (b == null) return;
            CACHE.put("a|" + url, b);
            App.MAIN.post(() -> got.run(b));
        });
    }

    /** Downloads into the disk cache only (pre-loading channel logos in the background). */
    public static void prefetch(String url) {
        if (url == null || url.isEmpty() || !url.startsWith("http") || diskFile(url).exists()) return;
        try { bytes(url); } catch (Exception ignored) { }
    }

    // ------------------------------------------------------------ disk cache (pictures survive restarts)

    private static java.io.File dir;

    private static java.io.File diskFile(String url) {
        if (dir == null) {
            dir = new java.io.File(App.ctx.getCacheDir(), "img");
            dir.mkdirs();
        }
        return new java.io.File(dir, Integer.toHexString(url.hashCode()) + "_" + url.length());
    }

    private static byte[] bytes(String url) throws Exception {
        java.io.File f = diskFile(url);
        if (f.exists() && f.length() > 0) {
            byte[] d = new byte[(int) f.length()];
            try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
                int off = 0, n;
                while (off < d.length && (n = in.read(d, off, d.length - off)) > 0) off += n;
            }
            return d;
        }
        byte[] d = Http.bytes(url);
        if (d != null && d.length > 0 && d.length < 3 * 1024 * 1024) {
            java.io.File tmp = new java.io.File(f.getPath() + ".tmp");
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(tmp)) { out.write(d); }
            tmp.renameTo(f);
            trimDisk();
        }
        return d;
    }

    private static long lastTrim;

    /** Keeps the picture cache under ~150 MB, oldest first. */
    private static void trimDisk() {
        if (System.currentTimeMillis() - lastTrim < 10 * 60 * 1000) return;
        lastTrim = System.currentTimeMillis();
        java.io.File[] all = dir.listFiles();
        if (all == null) return;
        long total = 0;
        for (java.io.File f : all) total += f.length();
        if (total < 150L * 1024 * 1024) return;
        java.util.Arrays.sort(all, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        for (java.io.File f : all) {
            if (total < 100L * 1024 * 1024) break;
            total -= f.length();
            f.delete();
        }
    }

    private static Bitmap fetch(String url, int targetWidth) {
        return fetch(url, targetWidth, false);
    }

    private static Bitmap fetch(String url, int targetWidth, boolean alpha) {
        try {
            byte[] data;
            if (url.startsWith("content:") || url.startsWith("android.resource:") || url.startsWith("file:")) {
                // Pictures other apps publish for their Home rows are often content:// links.
                try (java.io.InputStream in = App.ctx.getContentResolver().openInputStream(android.net.Uri.parse(url))) {
                    java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
                    data = b.toByteArray();
                }
            } else {
                data = bytes(url);
            }
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
            int sample = 1;
            while (targetWidth > 0 && bounds.outWidth / (sample * 2) >= targetWidth) sample *= 2;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            o.inPreferredConfig = alpha ? Bitmap.Config.ARGB_8888 : Bitmap.Config.RGB_565;
            return BitmapFactory.decodeByteArray(data, 0, data.length, o);
        } catch (Exception | OutOfMemoryError e) {
            return null;
        }
    }
}
