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

    /** Lets a launcher read the rows and Watch Next items that other apps publish (asked for at runtime). */
    public static final String PERMISSION = "android.permission.READ_TV_LISTINGS";
    private static final Uri URI = Uri.parse("content://android.media.tv/watch_next_program");

    /** Row type for rows other apps publish (Android TV "channels"). */
    public static final String APP_ROW = "apps";
    private static final Uri CHANNELS = Uri.parse("content://android.media.tv/channel");
    private static final Uri PREVIEW = Uri.parse("content://android.media.tv/preview_program");

    private WatchNext() { }

    /**
     * The rows other apps publish for the Android TV home screen (e.g. YouTube "Recommended",
     * Plex "On Deck"). Android lets launchers read them with the TV listings permission.
     */
    public static List<Addons.Row> channels(Context c) {
        List<Addons.Row> out = new ArrayList<>();
        if (Build.VERSION.SDK_INT < 26 || !allowed(c)) return out;
        PackageManager pm = c.getPackageManager();
        List<String[]> chans = new ArrayList<>();   // id, name, package
        try (Cursor cur = c.getContentResolver().query(CHANNELS, null, null, null, null)) {
            if (cur == null) return out;
            while (cur.moveToNext()) {
                String pkg = str(cur, "package_name");
                if (pkg.isEmpty() || (pkg.equals(c.getPackageName()) && !BuildConfig.TEST_BUILD)) continue;
                if (!"TYPE_PREVIEW".equals(str(cur, "type"))) continue;
                chans.add(new String[]{str(cur, "_id"), str(cur, "display_name"), pkg});
            }
        } catch (Exception e) {
            return out;
        }
        for (String[] ch : chans) {
            if (out.size() >= 25) break;
            String app = ch[2];
            try {
                app = pm.getApplicationLabel(pm.getApplicationInfo(ch[2], 0)).toString();
            } catch (Exception ignored) { }
            List<JSONObject> items = new ArrayList<>();
            int wide = 0;
            Uri u = PREVIEW.buildUpon().appendQueryParameter("channel", ch[0]).build();
            try (Cursor cur = c.getContentResolver().query(u, null, null, null, null)) {
                if (cur == null) continue;
                while (cur.moveToNext() && items.size() < 30) {
                    if ("0".equals(str(cur, "browsable"))) continue;
                    String title = str(cur, "title");
                    if (title.isEmpty()) continue;
                    JSONObject p = new JSONObject();
                    String poster = str(cur, "poster_art_uri"), thumb = str(cur, "thumbnail_uri");
                    String aspect = str(cur, "poster_art_aspect_ratio");
                    // 0 = 16:9, 1 = 3:2, 2 = 4:3 are landscape; 3 = 1:1, 4 = 2:3, 5 = movie poster
                    if (aspect.isEmpty() || "0".equals(aspect) || "1".equals(aspect) || "2".equals(aspect)) wide++;
                    String season = str(cur, "season_display_number"), ep = str(cur, "episode_display_number");
                    String episode = str(cur, "episode_title");
                    String sub = (!season.isEmpty() && !ep.isEmpty() ? "S" + season + " E" + ep : "")
                            + (episode.isEmpty() ? "" : (season.isEmpty() ? "" : " · ") + episode);
                    long pos = lng(cur, "last_playback_position_millis"), dur = lng(cur, "duration_millis");
                    p.put("id", "pp:" + ch[2] + ":" + str(cur, "_id"));
                    p.put("type", "external");
                    p.put("name", title);
                    p.put("poster", poster.isEmpty() ? thumb : poster);
                    p.put("still", thumb.isEmpty() ? poster : thumb);
                    p.put("background", poster.isEmpty() ? thumb : poster);
                    p.put("description", str(cur, "short_description"));
                    p.put("releaseInfo", str(cur, "release_date"));
                    p.put("badge", app);
                    p.put("package", ch[2]);
                    p.put("intent", str(cur, "intent_uri"));
                    p.put("sub", sub.isEmpty() ? app : sub);
                    if (pos > 0 && dur > 0) p.put("progress", (double) pos / dur);
                    items.add(p);
                }
            } catch (Exception ignored) {
                continue;
            }
            if (items.isEmpty()) continue;
            Addons.Row r = new Addons.Row(ch[1].isEmpty() ? app : ch[1], items);
            r.note = app;
            r.type = APP_ROW;
            r.wide = wide * 2 >= items.size();
            out.add(r);
        }
        return out;
    }

    /** Test builds only: publishes a Home row the way another app would, so the emulator test can see one. */
    static String insertTestChannel(Context c) {
        android.content.ContentValues ch = new android.content.ContentValues();
        ch.put("type", "TYPE_PREVIEW");
        ch.put("display_name", "Shell Picks");
        Uri chUri = c.getContentResolver().insert(CHANNELS, ch);
        long id = Long.parseLong(chUri.getLastPathSegment());
        for (String t : new String[]{"Test Movie One", "Test Movie Two", "Test Movie Three"}) {
            android.content.ContentValues p = new android.content.ContentValues();
            p.put("channel_id", id);
            p.put("type", 0);
            p.put("title", t);
            p.put("poster_art_aspect_ratio", 0);
            p.put("intent_uri", "intent:#Intent;action=android.settings.SETTINGS;end");
            c.getContentResolver().insert(PREVIEW, p);
        }
        return "Test channel " + id;
    }

    /** True when two lists of app rows have the same rows and items (to skip needless redraws). */
    public static boolean sameRows(List<Addons.Row> a, List<Addons.Row> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            Addons.Row x = a.get(i), y = b.get(i);
            if (!x.title.equals(y.title) || x.items.size() != y.items.size()) return false;
            for (int j = 0; j < x.items.size(); j++) {
                if (!x.items.get(j).optString("id").equals(y.items.get(j).optString("id"))) return false;
            }
        }
        return true;
    }

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
                if (pkg.isEmpty() || (pkg.equals(c.getPackageName()) && !BuildConfig.TEST_BUILD)) continue;
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
                ContinueWatching.seen(pkg, app);
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
