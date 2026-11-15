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

    /** Application context, for helpers without one (e.g. loading content:// pictures). */
    public static android.content.Context ctx;

    public interface Ok<T> { void run(T value); }
    public interface Err { void run(Exception e); }

    @Override
    public void onCreate() {
        super.onCreate();
        ctx = this;
        Store.init(this);
        Theme.apply();
        ProfileSync.start(this);   // TV-to-TV sync of profiles (does nothing until TVs are linked)
        Dvr.rearm(this);
        // Every screen: draw edge to edge (no strip at the screen edges), no system bars, theme-coloured window.
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override public void onActivityCreated(android.app.Activity a, android.os.Bundle b) { fullScreen(a); }
            @Override public void onActivityStarted(android.app.Activity a) { }
            @Override public void onActivityResumed(android.app.Activity a) { fullScreen(a); }
            @Override public void onActivityPaused(android.app.Activity a) { }
            @Override public void onActivityStopped(android.app.Activity a) { }
            @Override public void onActivitySaveInstanceState(android.app.Activity a, android.os.Bundle b) { }
            @Override public void onActivityDestroyed(android.app.Activity a) { }
        });           // scheduled recordings survive restarts and updates
        // Pre-load Live TV (channels, full guide, logos) shortly after start, then every 3 hours.
        MAIN.postDelayed(new Runnable() {
            @Override public void run() {
                IO.execute(Iptv::prefetchAll);
                MAIN.postDelayed(this, 3 * 3600 * 1000L);
            }
        }, 8000);
        // Save any crash so the home screen can show it next time (TVs hide crash dialogs).
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            try {
                java.io.StringWriter sw = new java.io.StringWriter();
                error.printStackTrace(new java.io.PrintWriter(sw));
                Store.putNow("last_crash", Updater.currentName(this) + "\n" + sw);
            } catch (Throwable ignored) { }
            if (previous != null) previous.uncaughtException(thread, error);
        });
    }

    static void fullScreen(android.app.Activity a) {
        android.view.Window w = a.getWindow();
        if (w == null) return;
        w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(
                a instanceof PlayerActivity || a instanceof LivePlayerActivity ? android.graphics.Color.BLACK : Ui.BG));
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            android.view.WindowManager.LayoutParams lp = w.getAttributes();
            lp.layoutInDisplayCutoutMode = android.os.Build.VERSION.SDK_INT >= 30 ? 3 /* ALWAYS */
                    : android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            w.setAttributes(lp);
        }
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            w.setDecorFitsSystemWindows(false);
            android.view.WindowInsetsController c = w.getInsetsController();
            if (c != null) {
                c.hide(android.view.WindowInsets.Type.systemBars() | android.view.WindowInsets.Type.displayCutout());
                c.setSystemBarsBehavior(android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            w.getDecorView().setSystemUiVisibility(android.view.View.SYSTEM_UI_FLAG_FULLSCREEN | android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
        }
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
