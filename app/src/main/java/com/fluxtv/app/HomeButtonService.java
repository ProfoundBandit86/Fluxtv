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
    private long lastLaunch;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        findStockHomes();
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
        if (!stockHomes.contains(pkg + "/" + cls)) {
            // Some launchers report a nested class; match on the package's home activity name prefix too.
            boolean match = false;
            for (String h : stockHomes) {
                int slash = h.indexOf('/');
                if (h.substring(0, slash).contentEquals(pkg) && cls.toString().startsWith(h.substring(slash + 1))) match = true;
            }
            if (!match) return;
        }
        android.util.Log.i("FluxHome", "Built-in home opened (" + pkg + "/" + cls + "), opening Flux");
        long now = SystemClock.elapsedRealtime();
        if (now - lastLaunch < 800) return;
        lastLaunch = now;
        Intent i = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NO_ANIMATION)
                .putExtra("section", "home");
        try {
            startActivity(i);
        } catch (Exception ignored) { }
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

    public static String status(Context c) {
        if (isDefaultHome(c)) return "Flux is already your TV's home screen.";
        if (running(c) && enabled(c)) return "On: Home opens Flux.";
        if (running(c)) return "The service is on, but Home takeover is switched off below.";
        return "Off: Home still opens the built-in home screen.";
    }
}
