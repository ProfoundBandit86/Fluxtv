package com.fluxtv.app;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.widget.Toast;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Installed apps and the reorderable app row on Home. */
public final class Apps {

    private Apps() { }

    public static List<ResolveInfo> installed(Context c) {
        PackageManager pm = c.getPackageManager();
        List<ResolveInfo> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        seen.add(c.getPackageName());
        for (String cat : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            for (ResolveInfo ri : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(cat), 0)) {
                if (seen.add(ri.activityInfo.packageName)) out.add(ri);
            }
        }
        Collections.sort(out, (a, b) -> a.loadLabel(pm).toString().compareToIgnoreCase(b.loadLabel(pm).toString()));
        return out;
    }

    public static boolean isInstalled(Context c, String pkg) {
        try {
            c.getPackageManager().getApplicationInfo(pkg, 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static String label(Context c, String pkg) {
        try {
            PackageManager pm = c.getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (Exception e) {
            return pkg;
        }
    }

    public static Drawable icon(Context c, String pkg) {
        try {
            return c.getPackageManager().getApplicationIcon(pkg);
        } catch (Exception e) {
            return null;
        }
    }

    public static void launch(Context c, String pkg) {
        PackageManager pm = c.getPackageManager();
        Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) i = pm.getLaunchIntentForPackage(pkg);
        if (i == null) {
            Toast.makeText(c, "Can't open this app", Toast.LENGTH_SHORT).show();
            return;
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        c.startActivity(i);
    }

    // ------------------------------------------------------------ the Home app row

    /** Apps in the Home row, in your order. First run: installed streaming apps first. */
    public static List<String> dock(Context c) {
        List<String> out = new ArrayList<>();
        String saved = Store.get("dock_apps");
        if (!saved.isEmpty()) {
            try {
                JSONArray a = new JSONArray(saved);
                for (int i = 0; i < a.length(); i++) if (isInstalled(c, a.optString(i))) out.add(a.optString(i));
                return out;
            } catch (Exception ignored) { }
        }
        String[] preferred = {"com.netflix.ninja", "com.google.android.youtube.tv", "com.disney.disneyplus", "com.wbd.stream",
                "com.amazon.amazonvideo.livingroom", "com.hulu.livingroomplus", "com.apple.atve.androidtv.appletv",
                "com.cbs.ott", "com.peacocktv.peacockandroid", "com.tubitv", "com.plexapp.android", "org.jellyfin.androidtv",
                "com.spotify.tv.android"};
        for (String p : preferred) if (isInstalled(c, p)) out.add(p);
        for (ResolveInfo ri : installed(c)) {
            if (out.size() >= 10) break;
            if (!out.contains(ri.activityInfo.packageName) && !ri.activityInfo.packageName.startsWith("com.android")
                    && !ri.activityInfo.packageName.startsWith("com.google.android.tv")) out.add(ri.activityInfo.packageName);
        }
        saveDock(out);
        return out;
    }

    public static void saveDock(List<String> pkgs) {
        Store.put("dock_apps", new JSONArray(pkgs).toString());
    }
}
