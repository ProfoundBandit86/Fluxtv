package com.fluxtv.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/**
 * Radarr (movies) and Sonarr (shows), API v3: add a title, see whether it's downloaded,
 * search again, the download queue, and what's coming up.
 */
public final class Arr {

    public static final Arr RADARR = new Arr("radarr", "Radarr");
    public static final Arr SONARR = new Arr("sonarr", "Sonarr");

    public final String key, name;

    private Arr(String key, String name) {
        this.key = key;
        this.name = name;
    }

    public static Arr forType(String type) {
        return "series".equals(type) ? SONARR : RADARR;
    }

    private boolean movies() {
        return this == RADARR;
    }

    // ------------------------------------------------------------ settings

    public String url() {
        return Store.get(key + "_url");
    }

    public String apiKey() {
        return Store.get(key + "_key");
    }

    public boolean configured() {
        return !url().isEmpty() && !apiKey().isEmpty();
    }

    public static boolean anyConfigured() {
        return RADARR.configured() || SONARR.configured() || Sab.configured();
    }

    /** Saves and tests the connection. Returns e.g. "Radarr 5.8.3". */
    public String save(String url, String apiKey) throws Exception {
        if (url != null && !url.trim().isEmpty()) Store.put(key + "_url", normalize(url));
        if (apiKey != null && !apiKey.trim().isEmpty()) Store.put(key + "_key", apiKey.trim());
        JSONObject s = new JSONObject(get("/system/status"));
        return name + " " + s.optString("version") + " connected";
    }

    public void remove() {
        for (String k : new String[]{"_url", "_key", "_profile", "_root"}) Store.put(key + k, "");
    }

    static String normalize(String url) {
        String u = url.trim();
        if (!u.startsWith("http://") && !u.startsWith("https://")) u = "http://" + u;
        return u.replaceAll("/+$", "");
    }

    private Map<String, String> headers() {
        return Http.map("X-Api-Key", apiKey(), "Accept", "application/json");
    }

    String get(String path) throws Exception {
        if (!configured()) throw new Exception(name + " isn't set up. Add it in Settings → Downloads.");
        return Http.call("GET", url() + "/api/v3" + path, headers(), null, null);
    }

    String post(String path, JSONObject body) throws Exception {
        return Http.call("POST", url() + "/api/v3" + path, headers(), body.toString(), "application/json");
    }

    // ------------------------------------------------------------ profiles & folders

    /** [id, name] pairs. */
    public List<String[]> profiles() throws Exception {
        List<String[]> out = new ArrayList<>();
        JSONArray a = new JSONArray(get("/qualityprofile"));
        for (int i = 0; i < a.length(); i++) out.add(new String[]{String.valueOf(a.optJSONObject(i).optInt("id")), a.optJSONObject(i).optString("name")});
        return out;
    }

    public List<String> rootFolders() throws Exception {
        List<String> out = new ArrayList<>();
        JSONArray a = new JSONArray(get("/rootfolder"));
        for (int i = 0; i < a.length(); i++) out.add(a.optJSONObject(i).optString("path"));
        return out;
    }

    public String profile() {
        return Store.get(key + "_profile");
    }

    public String root() {
        return Store.get(key + "_root");
    }

    public void setDefaults(String profileId, String root) {
        if (profileId != null) Store.put(key + "_profile", profileId);
        if (root != null) Store.put(key + "_root", root);
    }

    // ------------------------------------------------------------ titles

    /** What Radarr/Sonarr knows about a title. */
    public static class Status {
        public JSONObject item;          // lookup or library item
        public boolean added, downloaded, monitored;
        public int have, total;          // episodes (Sonarr)
        public String text = "";
    }

    /** Looks the title up by IMDb/TMDB id (falls back to name + year). Null when not found. */
    public Status status(String id, String name, String year) throws Exception {
        JSONObject found = null;
        if (movies()) {
            String[] p = id.split(":");
            if (id.startsWith("tt")) found = firstOf(get("/movie/lookup/imdb?imdbId=" + Http.enc(p[0])));
            else if (id.startsWith("tmdb:movie:")) found = firstOf(get("/movie/lookup/tmdb?tmdbId=" + p[2]));
            if (found == null) found = best(new JSONArray(get("/movie/lookup?term=" + Http.enc(name))), name, year);
        } else {
            if (id.startsWith("tt")) {
                try {
                    found = best(new JSONArray(get("/series/lookup?term=" + Http.enc("imdb:" + id.split(":")[0]))), null, null);
                } catch (Exception ignored) { }
            }
            if (found == null) found = best(new JSONArray(get("/series/lookup?term=" + Http.enc(name))), name, year);
        }
        if (found == null) return null;
        Status s = new Status();
        s.item = found;
        s.added = found.optInt("id") > 0;
        if (s.added) {
            // Lookups can be stale; read the library copy.
            try {
                s.item = new JSONObject(get((movies() ? "/movie/" : "/series/") + found.optInt("id")));
            } catch (Exception ignored) { }
            s.monitored = s.item.optBoolean("monitored");
            if (movies()) {
                s.downloaded = s.item.optBoolean("hasFile");
                s.text = s.downloaded ? "Downloaded" : s.monitored ? "Wanted" : "Not monitored";
            } else {
                JSONObject st = s.item.optJSONObject("statistics");
                if (st != null) {
                    s.have = st.optInt("episodeFileCount");
                    s.total = st.optInt("episodeCount");
                }
                s.downloaded = s.total > 0 && s.have >= s.total;
                s.text = s.total > 0 ? s.have + " of " + s.total + " episodes" : s.monitored ? "Monitored" : "Not monitored";
            }
        }
        return s;
    }

    private static JSONObject firstOf(String json) {
        try {
            Object v = new org.json.JSONTokener(json).nextValue();
            if (v instanceof JSONObject) return ((JSONObject) v).has("title") ? (JSONObject) v : null;
            if (v instanceof JSONArray && ((JSONArray) v).length() > 0) return ((JSONArray) v).optJSONObject(0);
        } catch (Exception ignored) { }
        return null;
    }

    /** Closest match by title (and year when known). */
    private static JSONObject best(JSONArray a, String name, String year) {
        if (a == null || a.length() == 0) return null;
        if (name == null) return a.optJSONObject(0);
        String y = year == null ? "" : year.replaceAll("[^0-9].*$", "");
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o.optString("title").equalsIgnoreCase(name) && (y.isEmpty() || y.equals(String.valueOf(o.optInt("year"))))) return o;
        }
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o.optString("title").equalsIgnoreCase(name)) return o;
        }
        return a.optJSONObject(0);
    }

    /** Adds the title (monitored) and starts a search. */
    public void add(JSONObject lookup, String profileId, String root) throws Exception {
        JSONObject body = new JSONObject(lookup.toString());
        body.put("qualityProfileId", Integer.parseInt(profileId));
        body.put("rootFolderPath", root);
        body.put("monitored", true);
        if (movies()) {
            body.put("minimumAvailability", "released");
            body.put("addOptions", new JSONObject().put("searchForMovie", true));
        } else {
            body.put("seasonFolder", true);
            body.put("addOptions", new JSONObject().put("monitor", "all").put("searchForMissingEpisodes", true));
            try {   // Sonarr v3 still needs a language profile; v4 dropped it.
                JSONArray lp = new JSONArray(get("/languageprofile"));
                if (lp.length() > 0) body.put("languageProfileId", lp.optJSONObject(0).optInt("id"));
            } catch (Exception ignored) { }
        }
        post(movies() ? "/movie" : "/series", body);
    }

    /** Asks Radarr/Sonarr to search for the title again. */
    public void search(JSONObject item) throws Exception {
        JSONObject cmd = movies()
                ? new JSONObject().put("name", "MoviesSearch").put("movieIds", new JSONArray().put(item.optInt("id")))
                : new JSONObject().put("name", "SeriesSearch").put("seriesId", item.optInt("id"));
        post("/command", cmd);
    }

    // ------------------------------------------------------------ queue & calendar

    /** Downloads Radarr/Sonarr are tracking: title, sub, progress (0..1). */
    public List<JSONObject> queue() throws Exception {
        List<JSONObject> out = new ArrayList<>();
        JSONObject q = new JSONObject(get("/queue?pageSize=50&" + (movies() ? "includeMovie=true" : "includeSeries=true&includeEpisode=true")));
        JSONArray recs = q.optJSONArray("records");
        if (recs == null) return out;
        for (int i = 0; i < recs.length(); i++) {
            JSONObject r = recs.optJSONObject(i);
            JSONObject e = new JSONObject();
            String title = r.optString("title");
            if (movies() && r.optJSONObject("movie") != null) title = r.optJSONObject("movie").optString("title");
            if (!movies() && r.optJSONObject("series") != null) {
                JSONObject ep = r.optJSONObject("episode");
                title = r.optJSONObject("series").optString("title")
                        + (ep != null ? "  ·  S" + ep.optInt("seasonNumber") + " E" + ep.optInt("episodeNumber") : "");
            }
            double size = r.optDouble("size", 0), left = r.optDouble("sizeleft", 0);
            e.put("title", title);
            e.put("progress", size > 0 ? (size - left) / size : 0);
            String state = r.optString("trackedDownloadState", r.optString("status"));
            String sub = cap(state) + (r.optString("timeleft").isEmpty() ? "" : "  ·  " + r.optString("timeleft") + " left")
                    + (size > 0 ? "  ·  " + Sab.gb(size) : "");
            if ("warning".equals(r.optString("trackedDownloadStatus"))) sub += "  ·  needs attention";
            e.put("sub", sub);
            out.add(e);
        }
        return out;
    }

    /** Next two weeks: title, sub (date), poster. */
    public List<JSONObject> upcoming() throws Exception {
        SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        long now = System.currentTimeMillis();
        String path = "/calendar?start=" + iso.format(new Date(now - 86_400_000L)) + "&end=" + iso.format(new Date(now + 14 * 86_400_000L))
                + (movies() ? "" : "&includeSeries=true");
        JSONArray a = new JSONArray(get(path));
        List<JSONObject> out = new ArrayList<>();
        for (int i = 0; i < a.length() && out.size() < 40; i++) {
            JSONObject o = a.optJSONObject(i);
            JSONObject e = new JSONObject();
            String when;
            if (movies()) {
                e.put("title", o.optString("title"));
                when = firstNonEmpty(o.optString("digitalRelease"), o.optString("physicalRelease"), o.optString("inCinemas"));
                e.put("imdb", o.optString("imdbId"));
                e.put("type", "movie");
                e.put("poster", image(o, "poster"));
                e.put("done", o.optBoolean("hasFile"));
            } else {
                JSONObject series = o.optJSONObject("series");
                e.put("title", (series != null ? series.optString("title") : "") + "  ·  S" + o.optInt("seasonNumber") + " E" + o.optInt("episodeNumber"));
                when = o.optString("airDateUtc");
                e.put("imdb", series != null ? series.optString("imdbId") : "");
                e.put("type", "series");
                e.put("poster", series != null ? image(series, "poster") : "");
                e.put("done", o.optBoolean("hasFile"));
            }
            e.put("when", when);
            e.put("sub", friendlyDate(when) + (e.optBoolean("done") ? "  ·  Downloaded" : ""));
            out.add(e);
        }
        java.util.Collections.sort(out, (x, y) -> x.optString("when").compareTo(y.optString("when")));
        return out;
    }

    private static String image(JSONObject o, String type) {
        JSONArray imgs = o.optJSONArray("images");
        if (imgs == null) return "";
        for (int i = 0; i < imgs.length(); i++) {
            JSONObject im = imgs.optJSONObject(i);
            if (type.equals(im.optString("coverType"))) {
                String remote = im.optString("remoteUrl");
                return remote.isEmpty() ? "" : remote;
            }
        }
        return "";
    }

    private static String firstNonEmpty(String... v) {
        for (String s : v) if (s != null && !s.isEmpty()) return s;
        return "";
    }

    static String friendlyDate(String iso) {
        if (iso == null || iso.length() < 10) return "Date unknown";
        try {
            SimpleDateFormat in = new SimpleDateFormat(iso.length() >= 19 ? "yyyy-MM-dd'T'HH:mm:ss" : "yyyy-MM-dd", Locale.US);
            in.setTimeZone(TimeZone.getTimeZone("UTC"));
            Date d = in.parse(iso.length() >= 19 ? iso.substring(0, 19) : iso.substring(0, 10));
            return new SimpleDateFormat(iso.length() >= 19 ? "EEE MMM d, h:mm a" : "EEE MMM d", Locale.getDefault()).format(d);
        } catch (Exception e) {
            return iso.substring(0, 10);
        }
    }

    static String cap(String s) {
        if (s == null || s.isEmpty()) return "";
        return Character.toUpperCase(s.charAt(0)) + s.substring(1).replaceAll("([a-z])([A-Z])", "$1 $2").toLowerCase(Locale.US);
    }
}
