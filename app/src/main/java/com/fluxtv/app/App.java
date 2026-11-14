package com.fluxtv.app;

import android.app.Application;
import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class App extends Application {

    public static final Handler MAIN = new Handler(Looper.getMainLooper());
    public static final ExecutorService IO = Executors.newFixedThreadPool(4);

    public interface Ok<T> { void run(T value); }
    public interface Err { void run(Exception e); }

    @Override
    public void onCreate() {
        super.onCreate();
        Store.init(this);
    }

    /** Runs work on a background thread and delivers the result on the main thread. */
    public static <T> void async(Callable<T> work, Ok<T> ok, Err err) {
        IO.execute(() -> {
            try {
                T v = work.call();
                MAIN.post(() -> ok.run(v));
            } catch (Exception e) {
                MAIN.post(() -> { if (err != null) err.run(e); });
            }
        });
    }

    public static String message(Exception e) {
        String m = e.getMessage();
        return m == null || m.isEmpty() ? e.getClass().getSimpleName() : m;
    }
}
