package com.fluxtv.app;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads Android TV's shared "Watch Next" list, where apps such as Netflix or Disney+ publish
 * what you're in the middle of. Needs the TV listings permission; apps that don't publish
 * there simply won't appear.
 */
public final class WatchNext {

    public static final String PERMISSION = "com.android.providers.tv.permission.READ_EPG_DATA";
    private static final Uri URI = Uri.parse("content://android.media.tv/watch_next_program");

    private WatchNext() { }

    public static boolean allowed(Context c) {
        return Build.VERSION.SDK_INT < 23 || c.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED;
    }

    /** Cards (same shape as Continue watching) for other apps' in-progress items. */
    public static List<JSONObject> load(Context c) {
        List<JSONObject> out = new ArrayList<>();
        if (Build.VERSION.SDK_INT < 26 || !allowed(c)) return out;
        PackageManager pm = c.getPackageManager();
        try (Cursor cur = c.getContentResolver().query(URI, null, null, null, null)) {
            if (cur == null) return out;
            while (cur.moveToNext() && out.size() < 40) {
                String pkg = str(cur, "package_name");
                if (pkg.isEmpty() || pkg.equals(c.getPackageName())) continue;
                if ("0".equals(str(cur, "browsable"))) continue;
                JSONObject p = new JSONObject();
                String title = str(cur, "title");
                String episode = str(cur, "episode_title");
                String season = str(cur, "season_display_number"), ep = str(cur, "episode_display_number");
                String label = !season.isEmpty() && !ep.isEmpty() ? "S" + season + " E" + ep : "";
                long pos = lng(cur, "last_playback_position_millis"), dur = lng(cur, "duration_millis");
                String app = pkg;
                try {
                    app = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
                } catch (Exception ignored) { }
                p.put("id", "wn:" + pkg + ":" + str(cur, "_id"));
                p.put("type", "external");
                p.put("name", title);
                p.put("label", label);
                String thumb = str(cur, "thumbnail_uri");
                p.put("still", thumb.isEmpty() ? str(cur, "poster_art_uri") : thumb);
                p.put("background", str(cur, "poster_art_uri"));
                p.put("description", str(cur, "short_description"));
                p.put("badge", app);
                p.put("package", pkg);
                p.put("intent", str(cur, "intent_uri"));
                if (pos > 0 && dur > 0) p.put("progress", (double) pos / dur);
                String sub = label + (episode.isEmpty() ? "" : (label.isEmpty() ? "" : " · ") + episode);
                if (pos > 0 && dur > pos) sub = (sub.isEmpty() ? "" : sub + " · ") + Ui.left(pos, dur);
                p.put("sub", sub.isEmpty() ? "Continue in " + app : sub);
                p.put("t", lng(cur, "last_engagement_time_utc_millis"));
                out.add(p);
            }
        } catch (Exception ignored) {
            // No TV provider on this device, or access refused.
        }
        return out;
    }

    /** Opens the item in its own app (falls back to just launching the app). */
    public static void open(Context c, JSONObject p) {
        try {
            Intent i = Intent.parseUri(p.optString("intent"), Intent.URI_INTENT_SCHEME);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
            return;
        } catch (Exception ignored) { }
        Apps.launch(c, p.optString("package"));
    }

    private static String str(Cursor c, String col) {
        int i = c.getColumnIndex(col);
        if (i < 0 || c.isNull(i)) return "";
        String v = c.getString(i);
        return v == null ? "" : v;
    }

    private static long lng(Cursor c, String col) {
        int i = c.getColumnIndex(col);
        return i < 0 || c.isNull(i) ? 0 : c.getLong(i);
    }
}
