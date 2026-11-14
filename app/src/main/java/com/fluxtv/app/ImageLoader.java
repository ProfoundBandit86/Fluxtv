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
    private static final ExecutorService POOL = Executors.newFixedThreadPool(4);

    private ImageLoader() { }

    public static void load(ImageView view, String url, int targetWidthPx) {
        view.setTag(url);
        if (url == null || url.isEmpty()) {
            view.setImageDrawable(null);
            return;
        }
        Bitmap cached = CACHE.get(url);
        if (cached != null) {
            view.setImageBitmap(cached);
            return;
        }
        view.setImageDrawable(null);
        POOL.execute(() -> {
            Bitmap b = fetch(url, targetWidthPx);
            if (b == null) return;
            CACHE.put(url, b);
            App.MAIN.post(() -> {
                if (url.equals(view.getTag())) view.setImageBitmap(b);
            });
        });
    }

    private static Bitmap fetch(String url, int targetWidth) {
        try {
            byte[] data = Http.bytes(url);
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
            int sample = 1;
            while (targetWidth > 0 && bounds.outWidth / (sample * 2) >= targetWidth) sample *= 2;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            o.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeByteArray(data, 0, data.length, o);
        } catch (Exception | OutOfMemoryError e) {
            return null;
        }
    }
}
