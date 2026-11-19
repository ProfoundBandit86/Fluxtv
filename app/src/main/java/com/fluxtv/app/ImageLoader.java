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
            new LruCache<String, Bitmap>((int) (Runtime.getRuntime().maxMemory() / 5)) {
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
    /** One download per picture even when several views ask for it at once: [on success, on failure] per view. */
    private static final java.util.Map<String, java.util.List<Runnable[]>> INFLIGHT = new java.util.HashMap<>();

    private ImageLoader() { }

    public static void load(ImageView view, String url, int targetWidthPx) {
        load(view, url, targetWidthPx, null);
    }

    /** onLoaded runs on the main thread once the picture is shown (not if it fails). */
    public static void load(ImageView view, String url, int targetWidthPx, Runnable onLoaded) {
        load(view, new String[]{url}, 0, targetWidthPx, onLoaded);
    }

    /** Tries each picture in turn (e.g. episode still, then backdrop, then poster) until one loads. */
    public static void loadFirst(ImageView view, int targetWidthPx, String... urls) {
        load(view, urls, 0, targetWidthPx, null);
    }

    private static void load(ImageView view, String[] urls, int i, int targetWidthPx, Runnable onLoaded) {
        while (i < urls.length && (urls[i] == null || urls[i].isEmpty())) i++;
        String key = java.util.Arrays.toString(urls);
        view.setTag(key);
        if (i >= urls.length) {
            view.setImageDrawable(null);
            return;
        }
        String url = Plex.fixImageUrl(urls[i]);
        Bitmap cached = CACHE.get(url);
        if (cached != null) {
            view.setImageBitmap(cached);
            if (onLoaded != null) onLoaded.run();
            return;
        }
        if (i == 0) view.setImageDrawable(null);
        int next = i + 1;
        Runnable show = () -> {
            Bitmap b = CACHE.get(url);
            if (b != null && key.equals(view.getTag())) {
                view.setImageBitmap(b);
                if (onLoaded != null) onLoaded.run();
            }
        };
        Runnable fail = () -> { if (key.equals(view.getTag()) && next < urls.length) load(view, urls, next, targetWidthPx, onLoaded); };
        synchronized (INFLIGHT) {
            java.util.List<Runnable[]> waiting = INFLIGHT.get(url);
            if (waiting != null) { waiting.add(new Runnable[]{show, fail}); return; }
            waiting = new java.util.ArrayList<>();
            waiting.add(new Runnable[]{show, fail});
            INFLIGHT.put(url, waiting);
        }
        POOL.execute(() -> {
            java.util.List<Runnable[]> waiting;
            // Scrolled away before its turn came: skip the work (it is asked for again if it comes back).
            boolean wanted = key.equals(view.getTag());
            synchronized (INFLIGHT) {
                if (!wanted && INFLIGHT.get(url) != null && INFLIGHT.get(url).size() <= 1) { INFLIGHT.remove(url); return; }
            }
            Bitmap b = fetch(url, targetWidthPx);
            if (b == null) {   // one more try after a moment (busy servers, a dropped connection)
                try { Thread.sleep(1500); } catch (InterruptedException ignored) { }
                b = fetch(url, targetWidthPx);
            }
            if (b != null) CACHE.put(url, b);
            synchronized (INFLIGHT) { waiting = INFLIGHT.remove(url); }
            if (waiting == null) return;
            boolean ok = b != null;
            java.util.List<Runnable[]> w = waiting;
            Bitmap got = b;
            App.MAIN.post(() -> {
                // Many pictures arrive at once when Home is rebuilt (e.g. back from the player): the memory cache can
                // drop this one before its views are told, which left blank cards. Put it back first.
                if (ok && CACHE.get(url) == null) CACHE.put(url, got);
                for (Runnable[] r : w) (ok ? r[0] : r[1]).run();
            });
        });
    }

    // ------------------------------------------------------------ what went wrong (Settings → System → Pictures)

    private static final java.util.LinkedList<String> FAILS = new java.util.LinkedList<>();
    private static int failCount, okCount;

    private static void failed(String url, String why) {
        String host;
        try { host = new java.net.URI(url).getHost(); } catch (Exception e) { host = null; }
        if (host == null) host = url.length() > 40 ? url.substring(0, 40) : url;
        String line = new java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault()).format(new java.util.Date())
                + "  " + host + "  " + why;
        synchronized (FAILS) {
            failCount++;
            FAILS.addFirst(line);
            while (FAILS.size() > 25) FAILS.removeLast();
        }
    }

    /** Forgets every saved picture (memory and disk), so they all download fresh. */
    public static void clear() {
        CACHE.evictAll();
        if (dir == null) diskFile("x");
        java.io.File[] all = dir.listFiles();
        if (all != null) for (java.io.File f : all) f.delete();
        synchronized (FAILS) { FAILS.clear(); failCount = 0; okCount = 0; }
    }

    /** A short report of pictures that didn't load since Flux started. */
    public static String report() {
        synchronized (FAILS) {
            if (failCount == 0) return okCount + " pictures loaded, none failed since Flux started.";
            StringBuilder sb = new StringBuilder(okCount + " loaded · " + failCount + " didn't load since Flux started. Latest:");
            for (String l : FAILS) sb.append("\n").append(l);
            return sb.toString();
        }
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
            Bitmap b = BitmapFactory.decodeByteArray(data, 0, data.length, o);
            if (b == null) {
                failed(url, "not a picture Android can show (" + data.length + " bytes" + (url.contains(".svg") ? ", SVG" : "") + ")");
                diskFile(url).delete();   // don't keep serving a bad copy
            } else {
                synchronized (FAILS) { okCount++; }
            }
            return b;
        } catch (Http.HttpException e) {
            failed(url, "server said HTTP " + e.code + (e.code == 404 ? " (not found)" : e.code == 401 || e.code == 403 ? " (not allowed)" : ""));
            return null;
        } catch (java.net.SocketTimeoutException e) {
            failed(url, "timed out");
            return null;
        } catch (java.net.UnknownHostException e) {
            failed(url, "address not found");
            return null;
        } catch (OutOfMemoryError e) {
            failed(url, "too big");
            return null;
        } catch (Exception e) {
            failed(url, e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage()));
            return null;
        }
    }
}
