package com.fluxtv.app;

import android.accessibilityservice.AccessibilityService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.accessibility.AccessibilityEvent;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Makes Home open Flux without ADB, like Projectivy: when the TV's own home screen comes up
 * (Home button, waking up, after boot), Flux opens on top of it. Only the opened screen's
 * app and class names are looked at; nothing on screen is read.
 */
public class HomeButtonService extends AccessibilityService {

    private final Set<String> stockHomes = new HashSet<>();
    private final Set<String> stockPackages = new HashSet<>();
    /** Windows of the built-in home app that are panels on top of things, not its home screen. */
    private static final String[] NOT_HOME = {"dashboard", "smarthome", "panel", "settings", "search", "profile", "account",
            "notification", "assistant", "dialog", "popup", "toast", "menu", "picker", "input", "voice"};
    private long lastLaunch;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        findStockHomes();
        Store.put("home_service_seen", String.valueOf(System.currentTimeMillis()));
    }

    /** Every other app that can be the home screen (e.g. Google TV's launcherx). */
    private void findStockHomes() {
        stockHomes.clear();
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        List<ResolveInfo> list = getPackageManager().queryIntentActivities(home, PackageManager.MATCH_ALL);
        for (ResolveInfo ri : list) {
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(getPackageName()) || pkg.equals("com.android.settings") || pkg.contains("fallbackhome")
                    || pkg.equals("com.android.tv.settings")) continue;
            stockHomes.add(pkg + "/" + ri.activityInfo.name);
            stockPackages.add(pkg);
            android.util.Log.i("FluxHome", "Watching for " + pkg + "/" + ri.activityInfo.name);
            if (ri.activityInfo.targetActivity != null) stockHomes.add(pkg + "/" + ri.activityInfo.targetActivity);
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e == null || e.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        if (!enabled(this)) return;
        CharSequence pkg = e.getPackageName(), cls = e.getClassName();
        if (pkg == null || cls == null || getPackageName().contentEquals(pkg)) return;
        if (!stockPackages.contains(pkg.toString())) return;
        String c = cls.toString();
        rememberWindow(pkg + "/" + c);
        if (!stockHomes.contains(pkg + "/" + c)) {
            // Google TV's home app shows its home screen in activities other than the one it registers for Home,
            // so any of its activities counts, except panels (Google Home, the long-press dashboard, search…).
            if (!c.contains(".") || c.startsWith("android.")) return;
            String low = c.toLowerCase(java.util.Locale.ROOT);
            for (String n : NOT_HOME) if (low.contains(n)) return;
        }
        android.util.Log.i("FluxHome", "Built-in home opened (" + pkg + "/" + cls + "), opening Flux");
        long now = SystemClock.elapsedRealtime();
        if (now - lastLaunch < 1500) return;
        lastLaunch = now;
        Store.put("home_last_screen", String.valueOf(System.currentTimeMillis()));
        launch(new Intent(this, MainActivity.class).putExtra("section", "home"));
    }

    // ------------------------------------------------------------ the Home key itself

    private boolean homeDown, longFired;
    private final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable longPress = () -> {
        longFired = true;
        Store.put("home_last_key", String.valueOf(System.currentTimeMillis()));
        // Holding Home: open Flux's Quick Settings (Google's own panel can't be reached once the key is taken).
        launch(new Intent(this, QuickSettingsActivity.class));
    };

    /**
     * Catches Home before the TV acts on it (like Projectivy and Button Mapper). Catching it here avoids
     * Android's few-second block on opening apps right after Home is pressed.
     */
    @Override
    protected boolean onKeyEvent(android.view.KeyEvent e) {
        if (e.getKeyCode() != android.view.KeyEvent.KEYCODE_HOME || !enabled(this) || isDefaultHome(this)) return false;
        android.util.Log.i("FluxHome", "Home key " + (e.getAction() == android.view.KeyEvent.ACTION_DOWN ? "down" : "up"));
        if (e.getAction() == android.view.KeyEvent.ACTION_DOWN) {
            if (e.getRepeatCount() == 0) {
                homeDown = true;
                longFired = false;
                handler.postDelayed(longPress, 600);
            }
            return true;
        }
        if (e.getAction() == android.view.KeyEvent.ACTION_UP) {
            handler.removeCallbacks(longPress);
            if (homeDown && !longFired) {
                Store.put("home_last_key", String.valueOf(System.currentTimeMillis()));
                lastLaunch = SystemClock.elapsedRealtime();
                launch(new Intent(this, MainActivity.class).putExtra("section", "home"));
            }
            homeDown = false;
            return true;
        }
        return true;
    }

    private void launch(Intent i) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        try {
            startActivity(i);
        } catch (Exception ex) {
            android.util.Log.w("FluxHome", "Couldn't open Flux", ex);
        }
    }

    @Override
    public void onInterrupt() { }

    // ------------------------------------------------------------ status for Settings

    /** The user's switch inside Flux (on unless turned off). */
    public static boolean enabled(Context c) {
        return !"off".equals(Store.get("home_override"));
    }

    public static void setEnabled(boolean on) {
        Store.put("home_override", on ? "on" : "off");
    }

    /** True when the accessibility service is switched on in the TV's settings. */
    public static boolean running(Context c) {
        String list = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (TextUtils.isEmpty(list)) return false;
        ComponentName me = new ComponentName(c, HomeButtonService.class);
        for (String s : list.split(":")) {
            ComponentName cn = ComponentName.unflattenFromString(s);
            if (me.equals(cn)) return true;
        }
        return false;
    }

    /** True when Flux is already the TV's default home app (then nothing else is needed). */
    public static boolean isDefaultHome(Context c) {
        ResolveInfo ri = c.getPackageManager().resolveActivity(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY);
        if (ri == null || ri.activityInfo == null) return false;
        // With no default chosen Android returns its chooser ("android"); only a real match counts.
        return c.getPackageName().equals(ri.activityInfo.packageName);
    }

    /** Opens the TV's accessibility settings, where the user turns the service on. */
    public static void openSettings(Context c) {
        try {
            c.startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            try {
                c.startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception ignored) { }
        }
    }

    /** The last few windows the built-in home app opened (shown in Settings, for troubleshooting). */
    private static void rememberWindow(String w) {
        String old = Store.get("home_windows");
        java.util.List<String> list = new java.util.ArrayList<>(java.util.Arrays.asList(old.isEmpty() ? new String[0] : old.split("\n")));
        list.remove(w);
        list.add(0, w);
        while (list.size() > 4) list.remove(list.size() - 1);
        Store.put("home_windows", android.text.TextUtils.join("\n", list));
    }

    public static String recentWindows() {
        return Store.get("home_windows");
    }

    /** e.g. "Home button last caught 2 min ago" (helps tell whether the TV sends Home to Flux at all). */
    public static String lastSeen() {
        long k = 0, w = 0;
        try { k = Long.parseLong(Store.get("home_last_key")); } catch (Exception ignored) { }
        try { w = Long.parseLong(Store.get("home_last_screen")); } catch (Exception ignored) { }
        long t = Math.max(k, w);
        if (t == 0) return "Flux hasn't caught a Home press yet. Press Home once to test.";
        long min = (System.currentTimeMillis() - t) / 60000;
        return "Last Home press caught " + (min < 1 ? "just now" : min + " min ago")
                + (k >= w ? " (Home key)" : " (built-in home screen opened)") + ".";
    }

    public static String status(Context c) {
        if (isDefaultHome(c)) return "Flux is already your TV's home screen.";
        if (running(c) && enabled(c)) return "On: Home opens Flux.";
        if (running(c)) return "The service is on, but Home takeover is switched off below.";
        return "Off: Home still opens the built-in home screen.";
    }
}
