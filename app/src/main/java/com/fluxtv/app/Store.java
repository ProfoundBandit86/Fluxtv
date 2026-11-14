package com.fluxtv.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * All local data: add-ons, profiles, per-profile watchlist and watch progress,
 * and Real-Debrid credentials. Stored as JSON in SharedPreferences.
 */
public final class Store {

    public static final String CINEMETA = "https://v3-cinemeta.strem.io/manifest.json";

    private static SharedPreferences p;
    private static int addonsVersion = 0;
    private static int changes = 0;

    /** Bumps when favorites or watch progress change. */
    public static int changes() {
        return changes;
    }

    private Store() { }

    static void init(Context c) {
        p = c.getSharedPreferences("flux", Context.MODE_PRIVATE);
        if (!p.contains("addons")) {
            JSONArray a = new JSONArray();
            a.put(CINEMETA);
            p.edit().putString("addons", a.toString()).apply();
        }
    }

    // ------------------------------------------------------------ simple values

    public static String get(String key) {
        return p.getString(key, "");
    }

    public static void put(String key, String value) {
        p.edit().putString(key, value).apply();
    }

    /** Synchronous write, for use while the app is crashing. */
    public static void putNow(String key, String value) {
        p.edit().putString(key, value).commit();
    }

    // ------------------------------------------------------------ add-ons

    public static synchronized List<String> addonUrls() {
        List<String> out = new ArrayList<>();
        JSONArray a = array("addons");
        for (int i = 0; i < a.length(); i++) out.add(a.optString(i));
        return out;
    }

    public static synchronized void addAddon(String url) {
        List<String> urls = addonUrls();
        if (!urls.contains(url)) urls.add(url);
        saveAddons(urls);
    }

    public static synchronized void removeAddon(String url) {
        List<String> urls = addonUrls();
        urls.remove(url);
        saveAddons(urls);
    }

    public static synchronized void moveAddon(String url, int delta) {
        List<String> urls = addonUrls();
        int i = urls.indexOf(url);
        int j = i + delta;
        if (i < 0 || j < 0 || j >= urls.size()) return;
        Collections.swap(urls, i, j);
        saveAddons(urls);
    }

    private static void saveAddons(List<String> urls) {
        p.edit().putString("addons", new JSONArray(urls).toString()).apply();
        addonsVersion++;
    }

    /** Something that feeds the home screen changed (servers, IPTV, settings). */
    public static void bumpSources() {
        addonsVersion++;
    }

    public static int addonsVersion() {
        return addonsVersion;
    }

    public static String cachedManifest(String url) {
        return p.getString("manifest:" + url, null);
    }

    public static void cacheManifest(String url, String json) {
        p.edit().putString("manifest:" + url, json).apply();
    }

    // ------------------------------------------------------------ media servers

    public static synchronized List<MediaServer> servers() {
        List<MediaServer> out = new ArrayList<>();
        JSONArray a = array("servers");
        for (int i = 0; i < a.length(); i++) {
            JSONObject j = a.optJSONObject(i);
            if (j != null) out.add(MediaServer.fromJson(j));
        }
        return out;
    }

    public static synchronized MediaServer server(String kind, String id) {
        for (MediaServer s : servers()) if (s.kind.equals(kind) && s.id.equals(id)) return s;
        return null;
    }

    /** Adds the server, replacing an existing entry for the same server. */
    public static synchronized void saveServer(MediaServer server) {
        JSONArray out = new JSONArray();
        for (MediaServer s : servers()) {
            if (!(s.kind.equals(server.kind) && s.id.equals(server.id))) out.put(s.toJson());
        }
        out.put(server.toJson());
        p.edit().putString("servers", out.toString()).apply();
        addonsVersion++;
    }

    public static synchronized void removeServer(String kind, String id) {
        JSONArray out = new JSONArray();
        for (MediaServer s : servers()) {
            if (!(s.kind.equals(kind) && s.id.equals(id))) out.put(s.toJson());
        }
        p.edit().putString("servers", out.toString()).apply();
        addonsVersion++;
    }

    /** Stable random id for this TV, sent to Plex/Jellyfin/Emby. */
    public static synchronized String deviceId() {
        String id = p.getString("device_id", "");
        if (id.isEmpty()) {
            id = java.util.UUID.randomUUID().toString();
            p.edit().putString("device_id", id).apply();
        }
        return id;
    }

    // ------------------------------------------------------------ profiles

    public static String profile() {
        return p.getString("profile", "Default");
    }

    public static void setProfile(String name) {
        p.edit().putString("profile", name).apply();
    }

    public static synchronized List<String> profiles() {
        List<String> out = new ArrayList<>();
        JSONArray a = array("profiles");
        for (int i = 0; i < a.length(); i++) out.add(a.optString(i));
        if (out.isEmpty()) out.add("Default");
        return out;
    }

    public static synchronized void addProfile(String name) {
        List<String> all = profiles();
        if (!all.contains(name)) all.add(name);
        p.edit().putString("profiles", new JSONArray(all).toString()).apply();
    }

    /** Deletes a profile and its favorites, progress and Trakt link. */
    public static synchronized void removeProfile(String name) {
        List<String> all = profiles();
        all.remove(name);
        if (all.isEmpty()) all.add("Default");
        SharedPreferences.Editor e = p.edit().putString("profiles", new JSONArray(all).toString());
        for (String k : new String[]{"library", "progress", "trakt_tokens", "trakt_snap", "trakt_last"}) e.remove(k + ":" + name);
        if (name.equals(profile())) e.putString("profile", all.get(0));
        e.apply();
        changes++;
        addonsVersion++;
    }

    /** A per-profile value (e.g. Trakt sign-in) for any profile, not just the current one. */
    public static String profileValue(String base, String profile) {
        return p.getString(base + ":" + profile, "");
    }

    public static void putProfileValue(String base, String profile, String value) {
        if (value == null) p.edit().remove(base + ":" + profile).apply();
        else p.edit().putString(base + ":" + profile, value).apply();
    }

    private static String key(String base) {
        return base + ":" + profile();
    }

    // ------------------------------------------------------------ watchlist

    public static synchronized boolean inLibrary(String id) {
        return object(key("library")).has(id);
    }

    /** Adds or removes the title. Returns true if it is now in the watchlist. */
    public static synchronized boolean toggleLibrary(JSONObject meta) {
        JSONObject lib = object(key("library"));
        String id = meta.optString("id");
        boolean added;
        if (lib.has(id)) {
            lib.remove(id);
            added = false;
        } else {
            JSONObject e = preview(meta);
            try { e.put("t", System.currentTimeMillis()); } catch (JSONException ignored) { }
            try { lib.put(id, e); } catch (JSONException ignored) { }
            added = true;
        }
        p.edit().putString(key("library"), lib.toString()).apply();
        changes++;
        return added;
    }

    /** Adds without toggling (used by Trakt sync). */
    public static synchronized void addToLibrary(JSONObject preview) {
        JSONObject lib = object(key("library"));
        String id = preview.optString("id");
        if (id.isEmpty() || lib.has(id)) return;
        try {
            if (!preview.has("t")) preview.put("t", System.currentTimeMillis());
            lib.put(id, preview);
        } catch (JSONException ignored) { }
        p.edit().putString(key("library"), lib.toString()).apply();
        changes++;
    }

    public static synchronized void removeFromLibrary(String id) {
        JSONObject lib = object(key("library"));
        if (lib.remove(id) == null) return;
        p.edit().putString(key("library"), lib.toString()).apply();
        changes++;
    }

    public static synchronized List<JSONObject> library() {
        return sortedValues(object(key("library")));
    }

    // ------------------------------------------------------------ progress

    public static synchronized JSONObject progress(String videoId) {
        return object(key("progress")).optJSONObject(videoId);
    }

    public static void saveProgress(PlayInfo info, long pos, long dur) {
        putProgress(info, pos, dur, System.currentTimeMillis(), true);
    }

    /** Copies progress reported by a media server or Trakt, unless this TV has something newer. */
    public static void importProgress(PlayInfo info, long pos, long dur, long when) {
        putProgress(info, pos, dur, when, false);
    }

    private static synchronized void putProgress(PlayInfo info, long pos, long dur, long when, boolean overwrite) {
        JSONObject all = object(key("progress"));
        if (!overwrite && all.has(info.videoId) && all.optJSONObject(info.videoId).optLong("t") >= when) return;
        JSONObject e = new JSONObject();
        try {
            e.put("id", info.metaId);
            e.put("type", info.type);
            e.put("name", info.name);
            e.put("poster", info.poster);
            e.put("videoId", info.videoId);
            e.put("label", info.label);
            e.put("background", info.background);
            e.put("pos", pos);
            e.put("dur", dur);
            e.put("t", when);
            all.put(info.videoId, e);
        } catch (JSONException ignored) { }
        p.edit().putString(key("progress"), all.toString()).apply();
        changes++;
    }

    /** Most recent unfinished item per title, newest first. */
    public static synchronized List<JSONObject> continueWatching() {
        Map<String, Boolean> seen = new HashMap<>();
        List<JSONObject> out = new ArrayList<>();
        for (JSONObject e : sortedValues(object(key("progress")))) {
            if (seen.put(e.optString("id"), Boolean.TRUE) != null) continue;   // only newest per title
            double ratio = e.optLong("dur") > 0 ? (double) e.optLong("pos") / e.optLong("dur") : 0;
            if (e.optLong("pos") > 30000 && ratio < 0.92) out.add(e);
        }
        return out;
    }

    public static boolean isFinished(JSONObject progress) {
        return progress != null && progress.optLong("dur") > 0
                && (double) progress.optLong("pos") / progress.optLong("dur") >= 0.92;
    }

    // ------------------------------------------------------------ helpers

    public static JSONObject preview(JSONObject meta) {
        JSONObject e = new JSONObject();
        try {
            e.put("id", meta.optString("id"));
            e.put("type", meta.optString("type"));
            e.put("name", meta.optString("name"));
            e.put("poster", meta.optString("poster"));
            e.put("background", meta.optString("background"));
            e.put("description", meta.optString("description"));
            e.put("releaseInfo", meta.optString("releaseInfo"));
            if (!meta.optString("badge").isEmpty()) e.put("badge", meta.optString("badge"));
            if (!meta.optString("serverId").isEmpty()) e.put("serverId", meta.optString("serverId"));
        } catch (JSONException ignored) { }
        return e;
    }

    private static List<JSONObject> sortedValues(JSONObject o) {
        List<JSONObject> out = new ArrayList<>();
        Iterator<String> it = o.keys();
        while (it.hasNext()) {
            JSONObject v = o.optJSONObject(it.next());
            if (v != null) out.add(v);
        }
        Collections.sort(out, (a, b) -> Long.compare(b.optLong("t"), a.optLong("t")));
        return out;
    }

    private static JSONObject object(String k) {
        try {
            return new JSONObject(p.getString(k, "{}"));
        } catch (JSONException e) {
            return new JSONObject();
        }
    }

    private static JSONArray array(String k) {
        try {
            return new JSONArray(p.getString(k, "[]"));
        } catch (JSONException e) {
            return new JSONArray();
        }
    }
}
