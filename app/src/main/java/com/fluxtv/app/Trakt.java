package com.fluxtv.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

/**
 * Trakt sync, one Trakt account per Flux profile:
 * Favorites ⇄ Trakt watchlist (two-way, deletions included), resume points from Trakt's
 * playback progress, and scrobbling so what you watch lands in your Trakt history.
 *
 * Trakt needs an API app of your own (free): its Client ID goes in Settings (new apps have no secret).
 */
public final class Trakt {

    private static final String API = "https://api.trakt.tv";
    private static final String POSTER = "https://images.metahub.space/poster/medium/";
    private static final String BACKDROP = "https://images.metahub.space/background/medium/";

    private Trakt() { }

    static String api() {
        if (BuildConfig.TEST_BUILD && !Store.get("test_trakt").isEmpty()) return Store.get("test_trakt");
        return API;
    }

    public static String clientId() {
        return Store.get("trakt_client_id");
    }

    /** Only the Client ID is needed: Trakt stopped issuing secrets to new apps (Oct 2026). Older apps' secrets still work. */
    public static boolean configured() {
        return !clientId().isEmpty();
    }

    private static String secret() {
        return Store.get("trakt_client_secret");
    }

    /** Adds client_secret only for older apps that have one. */
    private static JSONObject withSecret(JSONObject body) throws Exception {
        if (!secret().isEmpty()) body.put("client_secret", secret());
        return body;
    }

    /**
     * Sign-in calls go to Trakt's auth host (current docs), falling back to the API host
     * (where they used to live) if that host isn't reachable or doesn't know the path.
     */
    private static String oauth(String path, JSONObject body) throws Exception {
        if (BuildConfig.TEST_BUILD && !Store.get("test_trakt").isEmpty()) return post(path, body, null);
        try {
            return Http.call("POST", "https://auth.trakt.tv" + path, headers(null), body.toString(), "application/json");
        } catch (Http.HttpException e) {
            if (e.code != 404 && e.code != 405) throw e;
        } catch (java.io.IOException ignored) {
            // auth host unreachable: try the API host
        }
        return post(path, body, null);
    }

    // PKCE: a random verifier for each sign-in; Trakt gets its SHA-256 hash first, then the verifier itself.
    private static String newVerifier() {
        byte[] b = new byte[48];
        new java.security.SecureRandom().nextBytes(b);
        return android.util.Base64.encodeToString(b, android.util.Base64.URL_SAFE | android.util.Base64.NO_PADDING | android.util.Base64.NO_WRAP);
    }

    private static String challenge(String verifier) throws Exception {
        byte[] h = java.security.MessageDigest.getInstance("SHA-256").digest(verifier.getBytes("US-ASCII"));
        return android.util.Base64.encodeToString(h, android.util.Base64.URL_SAFE | android.util.Base64.NO_PADDING | android.util.Base64.NO_WRAP);
    }

    public static void setApp(String id, String secret) {
        if (id != null && !id.trim().isEmpty()) {
            if (!id.trim().equals(clientId())) Store.put("trakt_client_secret", "");   // a new app: forget the old one's secret
            Store.put("trakt_client_id", id.trim());
        }
        if (secret != null && !secret.trim().isEmpty()) Store.put("trakt_client_secret", secret.trim());
    }

    // ------------------------------------------------------------ accounts (per profile)

    static JSONObject tokens(String profile) {
        try {
            String s = Store.profileValue("trakt_tokens", profile);
            return s.isEmpty() ? null : new JSONObject(s);
        } catch (Exception e) {
            return null;
        }
    }

    public static boolean signedIn(String profile) {
        return configured() && tokens(profile) != null;
    }

    public static boolean signedIn() {
        return signedIn(Store.profile());
    }

    /** Trakt username for the profile, or "" when not linked. */
    public static String user(String profile) {
        JSONObject t = tokens(profile);
        return t == null ? "" : t.optString("user");
    }

    public static void signOut(String profile) {
        Store.putProfileValue("trakt_tokens", profile, null);
        Store.putProfileValue("trakt_snap", profile, null);
        Store.putProfileValue("trakt_last", profile, null);
    }

    private static Map<String, String> headers(String bearer) {
        Map<String, String> h = new HashMap<>();
        h.put("trakt-api-version", "2");
        h.put("trakt-api-key", clientId());
        if (bearer != null) h.put("Authorization", "Bearer " + bearer);
        return h;
    }

    private static String post(String path, JSONObject body, String bearer) throws Exception {
        return Http.call("POST", api() + path, headers(bearer), body.toString(), "application/json");
    }

    /** A valid access token for the profile (refreshes it when close to expiry). */
    private static synchronized String token(String profile) throws Exception {
        JSONObject t = tokens(profile);
        if (t == null) throw new Exception("Trakt isn't linked for " + profile);
        if (t.optLong("expires") > System.currentTimeMillis() + 120_000) return t.optString("access");
        JSONObject body = withSecret(new JSONObject()
                .put("refresh_token", t.optString("refresh"))
                .put("client_id", clientId())
                .put("redirect_uri", "urn:ietf:wg:oauth:2.0:oob")
                .put("grant_type", "refresh_token"));
        try {
            String raw;
            try {
                raw = oauth("/oauth/token", body);
            } catch (Http.HttpException e) {
                if (e.code != 400 && e.code != 401) throw e;
                body.put("redirect_uri", "http://localhost");   // newer apps check this redirect URI
                raw = oauth("/oauth/token", body);
            }
            JSONObject r = new JSONObject(raw);
            saveTokens(profile, r, t.optString("user"));
            return r.optString("access_token");
        } catch (Http.HttpException e) {
            if (e.code == 400 || e.code == 401) {
                signOut(profile);
                throw new Exception("Your Trakt sign-in expired. Link Trakt again in Profiles & Trakt.");
            }
            throw e;
        }
    }

    private static void saveTokens(String profile, JSONObject r, String user) throws Exception {
        long expires = r.optLong("created_at", System.currentTimeMillis() / 1000) * 1000 + r.optLong("expires_in", 86400) * 1000;
        JSONObject t = new JSONObject()
                .put("access", r.optString("access_token"))
                .put("refresh", r.optString("refresh_token"))
                .put("expires", expires)
                .put("user", user == null ? "" : user);
        Store.putProfileValue("trakt_tokens", profile, t.toString());
    }

    private static String get(String path, String profile) throws Exception {
        return Http.call("GET", api() + path, headers(token(profile)), null, null);
    }

    // ------------------------------------------------------------ device-code sign-in

    /** An in-progress sign-in. state: waiting, done, expired, error. Shared by the TV screen and the phone page. */
    public static class Login {
        public String profile = "", userCode = "", url = "", deviceCode = "", error = "";
        String verifier = "";
        public volatile String state = "waiting";
        int interval = 5;
        long deadline;
    }

    private static volatile Login login;

    public static Login current() {
        return login;
    }

    /** Starts a sign-in for the profile; a background thread waits for approval. */
    public static Login startLogin(String profile) throws Exception {
        if (!configured()) throw new Exception("Add your Trakt Client ID first");
        String verifier = newVerifier();
        JSONObject r = new JSONObject(oauth("/oauth/device/code", new JSONObject().put("client_id", clientId())
                .put("code_challenge", challenge(verifier)).put("code_challenge_method", "S256")));
        Login l = new Login();
        l.verifier = verifier;
        l.profile = profile;
        l.userCode = r.optString("user_code");
        l.url = r.optString("verification_url", "https://trakt.tv/activate");
        l.deviceCode = r.optString("device_code");
        l.interval = Math.max(1, r.optInt("interval", 5));
        l.deadline = System.currentTimeMillis() + r.optInt("expires_in", 600) * 1000L;
        login = l;
        Thread t = new Thread(() -> pollLogin(l), "trakt-login");
        t.setDaemon(true);
        t.start();
        return l;
    }

    public static void cancelLogin() {
        Login l = login;
        if (l != null && "waiting".equals(l.state)) l.state = "cancelled";
        login = null;
    }

    private static void pollLogin(Login l) {
        while ("waiting".equals(l.state)) {
            try {
                Thread.sleep(l.interval * 1000L);
            } catch (InterruptedException e) {
                return;
            }
            if (!"waiting".equals(l.state)) return;
            if (System.currentTimeMillis() > l.deadline) {
                l.state = "expired";
                return;
            }
            try {
                JSONObject body = withSecret(new JSONObject().put("code", l.deviceCode).put("client_id", clientId())
                        .put("code_verifier", l.verifier));
                JSONObject r = new JSONObject(oauth("/oauth/device/token", body));
                String user = "";
                try {
                    JSONObject settings = new JSONObject(Http.call("GET", api() + "/users/settings",
                            headers(r.optString("access_token")), null, null));
                    user = settings.optJSONObject("user").optString("username");
                } catch (Exception ignored) { }
                saveTokens(l.profile, r, user);
                Store.putProfileValue("trakt_snap", l.profile, null);
                l.state = "done";
                if (l.profile.equals(Store.profile())) {
                    try { sync(); } catch (Exception ignored) { }
                }
                Store.bumpSources();
                return;
            } catch (Http.HttpException e) {
                if (e.code == 400) continue;                 // not approved yet
                if (e.code == 429) { l.interval++; continue; }
                l.state = e.code == 418 ? "error" : "expired";
                l.error = e.code == 418 ? "Sign-in was denied on Trakt" : "The code expired";
                return;
            } catch (Exception e) {
                // network hiccup: keep waiting
            }
        }
    }

    // ------------------------------------------------------------ id mapping

    /** {"kind": "movie"|"show", "ids": {...}} for a Flux id, or null when Trakt can't know it. */
    static JSONObject traktIds(String id, String type) {
        try {
            boolean show = "series".equals(type) || id.startsWith("tmdb:tv:");
            JSONObject ids = new JSONObject();
            String[] p = id.split(":");
            if (id.startsWith("tt")) ids.put("imdb", p[0]);
            else if (id.startsWith("tmdb:") && p.length >= 3) ids.put("tmdb", Integer.parseInt(p[2]));
            else return null;
            return new JSONObject().put("kind", show ? "show" : "movie").put("ids", ids);
        } catch (Exception e) {
            return null;
        }
    }

    /** Flux id for a Trakt movie/show object. */
    private static String fluxId(JSONObject item, boolean show) {
        JSONObject ids = item.optJSONObject("ids");
        if (ids == null) return "";
        String imdb = ids.optString("imdb", "");
        if (imdb.startsWith("tt")) return imdb;
        int tmdb = ids.optInt("tmdb", 0);
        return tmdb > 0 ? "tmdb:" + (show ? "tv" : "movie") + ":" + tmdb : "";
    }

    private static JSONObject preview(JSONObject item, boolean show, String id) throws Exception {
        JSONObject p = new JSONObject();
        p.put("id", id);
        p.put("type", show ? "series" : "movie");
        p.put("name", item.optString("title"));
        if (id.startsWith("tt")) {
            p.put("poster", POSTER + id + "/img");
            p.put("background", BACKDROP + id + "/img");
        }
        if (item.optInt("year") > 0) p.put("releaseInfo", String.valueOf(item.optInt("year")));
        p.put("description", item.optString("overview", ""));
        return p;
    }

    // ------------------------------------------------------------ watchlist sync

    private static Set<String> snapshot(String profile) {
        Set<String> out = new HashSet<>();
        try {
            JSONArray a = new JSONArray(Store.profileValue("trakt_snap", profile).isEmpty() ? "[]"
                    : Store.profileValue("trakt_snap", profile));
            for (int i = 0; i < a.length(); i++) out.add(a.optString(i));
        } catch (Exception ignored) { }
        return out;
    }

    /**
     * Two-way sync for the current profile. A title that was on both sides at the last sync and is now
     * missing on one side was removed there, so it's removed from the other side too.
     * Returns a short summary.
     */
    public static synchronized String sync() throws Exception {
        String profile = Store.profile();
        if (!signedIn(profile)) return "Trakt isn't linked";
        Map<String, JSONObject> remote = new HashMap<>();
        for (String kind : new String[]{"movies", "shows"}) {
            boolean show = kind.equals("shows");
            JSONArray a = new JSONArray(get("/sync/watchlist/" + kind, profile));
            for (int i = 0; i < a.length(); i++) {
                JSONObject item = a.optJSONObject(i).optJSONObject(show ? "show" : "movie");
                if (item == null) continue;
                String id = fluxId(item, show);
                if (!id.isEmpty()) remote.put(id, preview(item, show, id));
            }
        }
        Map<String, JSONObject> local = new HashMap<>();
        for (JSONObject e : Store.library()) {
            if (traktIds(e.optString("id"), e.optString("type")) != null) local.put(e.optString("id"), e);
        }
        Set<String> snap = snapshot(profile);
        int added = 0, removed = 0;
        JSONArray pushAdd = new JSONArray(), pushRemove = new JSONArray();
        for (Map.Entry<String, JSONObject> r : remote.entrySet()) {
            if (local.containsKey(r.getKey())) continue;
            if (snap.contains(r.getKey())) {
                pushRemove.put(local(r.getKey(), r.getValue().optString("type")));   // removed on this TV
            } else {
                Store.addToLibrary(r.getValue());
                added++;
            }
        }
        for (Map.Entry<String, JSONObject> l : local.entrySet()) {
            if (remote.containsKey(l.getKey())) continue;
            if (snap.contains(l.getKey())) {
                Store.removeFromLibrary(l.getKey());                                   // removed on Trakt
                removed++;
            } else {
                pushAdd.put(local(l.getKey(), l.getValue().optString("type")));
            }
        }
        if (pushAdd.length() > 0) post("/sync/watchlist", body(pushAdd), token(profile));
        if (pushRemove.length() > 0) post("/sync/watchlist/remove", body(pushRemove), token(profile));

        Set<String> now = new HashSet<>();
        for (JSONObject e : Store.library()) if (traktIds(e.optString("id"), e.optString("type")) != null) now.add(e.optString("id"));
        Store.putProfileValue("trakt_snap", profile, new JSONArray(now).toString());

        int resumed = importPlayback(profile);
        Store.putProfileValue("trakt_last", profile, String.valueOf(System.currentTimeMillis()));
        return "Synced: " + added + " added, " + removed + " removed, " + pushAdd.length() + " sent to Trakt"
                + (resumed > 0 ? ", " + resumed + " resume points" : "");
    }

    private static JSONObject local(String id, String type) {
        return traktIds(id, type);
    }

    /** Groups {"kind","ids"} entries into Trakt's {"movies": [...], "shows": [...]} body. */
    private static JSONObject body(JSONArray entries) throws Exception {
        JSONArray movies = new JSONArray(), shows = new JSONArray();
        for (int i = 0; i < entries.length(); i++) {
            JSONObject e = entries.optJSONObject(i);
            if (e == null) continue;
            JSONObject o = new JSONObject().put("ids", e.optJSONObject("ids"));
            if ("show".equals(e.optString("kind"))) shows.put(o); else movies.put(o);
        }
        return new JSONObject().put("movies", movies).put("shows", shows);
    }

    /** Syncs in the background when it's been a while. Calls done (main thread) if anything changed. */
    public static void syncIfDue(Runnable done) {
        String profile = Store.profile();
        if (!signedIn(profile)) return;
        long last = 0;
        try { last = Long.parseLong(Store.profileValue("trakt_last", profile)); } catch (Exception ignored) { }
        if (System.currentTimeMillis() - last < 10 * 60_000) return;
        Store.putProfileValue("trakt_last", profile, String.valueOf(System.currentTimeMillis()));
        int before = Store.changes();
        App.async(Trakt::sync, s -> { if (Store.changes() != before && done != null) done.run(); }, null);
    }

    /** Called when Favorite is pressed: mirrors the change on Trakt right away. */
    public static void favoriteChanged(JSONObject meta, boolean added) {
        String profile = Store.profile();
        JSONObject ids = traktIds(meta.optString("id"), meta.optString("type"));
        if (!signedIn(profile) || ids == null) return;
        App.IO.execute(() -> {
            try {
                post(added ? "/sync/watchlist" : "/sync/watchlist/remove", body(new JSONArray().put(ids)), token(profile));
                Set<String> snap = snapshot(profile);
                if (added) snap.add(meta.optString("id")); else snap.remove(meta.optString("id"));
                Store.putProfileValue("trakt_snap", profile, new JSONArray(snap).toString());
            } catch (Exception ignored) {
                // The next full sync will catch up.
            }
        });
    }

    // ------------------------------------------------------------ playback progress & scrobbling

    /** Imports Trakt's paused-playback list as resume points. Returns how many were new. */
    private static int importPlayback(String profile) throws Exception {
        JSONArray a = new JSONArray(get("/sync/playback?extended=full", profile));
        int n = 0;
        for (int i = 0; i < a.length() && i < 60; i++) {
            JSONObject e = a.optJSONObject(i);
            if (e == null) continue;
            double pct = e.optDouble("progress", 0);
            long when = parseTime(e.optString("paused_at"));
            PlayInfo info;
            long dur;
            if ("episode".equals(e.optString("type"))) {
                JSONObject show = e.optJSONObject("show"), ep = e.optJSONObject("episode");
                if (show == null || ep == null) continue;
                String sid = fluxId(show, true);
                if (sid.isEmpty()) continue;
                String label = "S" + ep.optInt("season") + " E" + ep.optInt("number");
                info = PlayInfo.of("series", sid, sid + ":" + ep.optInt("season") + ":" + ep.optInt("number"),
                        show.optString("title"), sid.startsWith("tt") ? POSTER + sid + "/img" : "", label);
                if (sid.startsWith("tt")) info.background = BACKDROP + sid + "/img";
                dur = ep.optInt("runtime", show.optInt("runtime", 45)) * 60_000L;
            } else {
                JSONObject movie = e.optJSONObject("movie");
                if (movie == null) continue;
                String mid = fluxId(movie, false);
                if (mid.isEmpty()) continue;
                info = PlayInfo.of("movie", mid, mid, movie.optString("title"), mid.startsWith("tt") ? POSTER + mid + "/img" : "", "");
                if (mid.startsWith("tt")) info.background = BACKDROP + mid + "/img";
                dur = movie.optInt("runtime", 100) * 60_000L;
            }
            if (dur <= 0) dur = 45 * 60_000L;
            JSONObject before = Store.progress(info.videoId);
            Store.importProgress(info, (long) (dur * pct / 100.0), dur, when);
            if (before == null && Store.progress(info.videoId) != null) n++;
        }
        return n;
    }

    private static long parseTime(String iso) {
        try {
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
            f.setTimeZone(TimeZone.getTimeZone("UTC"));
            return f.parse(iso.length() > 19 ? iso.substring(0, 19) : iso).getTime();
        } catch (Exception e) {
            return System.currentTimeMillis();
        }
    }

    /**
     * Tells Trakt what's playing. action: start, pause or stop. Trakt adds the title to your history
     * when you stop at 80% or more, and keeps a resume point on pause.
     */
    public static void scrobble(PlayInfo info, String action, double percent) {
        String profile = Store.profile();
        if (info == null || info.videoId.isEmpty() || !signedIn(profile)) return;
        App.IO.execute(() -> {
            try {
                JSONObject body = scrobbleBody(info);
                if (body == null) return;
                body.put("progress", Math.max(0, Math.min(100, percent)));
                post("/scrobble/" + action, body, token(profile));
            } catch (Exception ignored) { }
        });
    }

    static JSONObject scrobbleBody(PlayInfo info) throws Exception {
        String v = info.videoId;
        String[] p = v.split(":");
        if (v.startsWith("tt")) {
            if (p.length >= 3) {
                return new JSONObject().put("show", new JSONObject().put("ids", new JSONObject().put("imdb", p[0])))
                        .put("episode", new JSONObject().put("season", Integer.parseInt(p[1])).put("number", Integer.parseInt(p[2])));
            }
            return new JSONObject().put("movie", new JSONObject().put("ids", new JSONObject().put("imdb", p[0])));
        }
        if (v.startsWith("tmdb:") && p.length >= 3) {
            JSONObject ids = new JSONObject().put("tmdb", Integer.parseInt(p[2]));
            if (p[1].equals("tv") && p.length >= 5) {
                return new JSONObject().put("show", new JSONObject().put("ids", ids))
                        .put("episode", new JSONObject().put("season", Integer.parseInt(p[3])).put("number", Integer.parseInt(p[4])));
            }
            if (p[1].equals("movie")) return new JSONObject().put("movie", new JSONObject().put("ids", ids));
        }
        // Media-server items: fall back to the title's id when it's an IMDb/TMDB one.
        if (!info.metaId.equals(v) && (info.metaId.startsWith("tt") || info.metaId.startsWith("tmdb:"))
                && "movie".equals(info.type)) {
            PlayInfo m = PlayInfo.of(info.type, info.metaId, info.metaId, info.name, "", "");
            return scrobbleBody(m);
        }
        return null;
    }

    /** Linked profiles, for status lines. */
    public static int linkedCount(List<String> profiles) {
        int n = 0;
        for (String p : profiles) if (signedIn(p)) n++;
        return n;
    }
}
