package com.fluxtv.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** Talks to installed add-ons. All methods block; call from background threads. */
public final class Addons {

    private static final Map<String, Addon> cache = new ConcurrentHashMap<>();
    private static final ExecutorService POOL = Executors.newFixedThreadPool(8);
    private static final int ROW_LIMIT = 30;

    public static class Row {
        public final String title;
        public final List<JSONObject> items;

        public Row(String title, List<JSONObject> items) {
            this.title = title;
            this.items = items;
        }
    }

    public static class Stream {
        /** Add-on or server name shown in the stream list. */
        public final String source;
        public final JSONObject json;

        public Stream(String source, JSONObject json) {
            this.source = source;
            this.json = json;
        }
    }

    private Addons() { }

    public static List<Addon> all() {
        List<Addon> out = new ArrayList<>();
        for (String url : Store.addonUrls()) {
            Addon a = load(url);
            if (a != null) out.add(a);
        }
        return out;
    }

    public static Addon load(String url) {
        Addon a = cache.get(url);
        if (a != null) return a;
        try {
            String s = Http.get(url, null);
            a = new Addon(url, new JSONObject(s));
            Store.cacheManifest(url, s);
        } catch (Exception e) {
            String cached = Store.cachedManifest(url);
            if (cached == null) return null;
            try {
                a = new Addon(url, new JSONObject(cached));
            } catch (Exception x) {
                return null;
            }
        }
        cache.put(url, a);
        return a;
    }

    public static Addon install(String raw) throws Exception {
        if (raw == null || raw.trim().isEmpty()) throw new Exception("Enter an add-on URL");
        String url = Addon.normalize(raw);
        String s = Http.get(url, null);
        JSONObject m = new JSONObject(s);
        if (!m.has("id") || !m.has("resources")) throw new Exception("That URL isn't an add-on manifest");
        Addon a = new Addon(url, m);
        Store.cacheManifest(url, s);
        cache.put(url, a);
        Store.addAddon(url);
        return a;
    }

    public static void uninstall(String url) {
        cache.remove(url);
        Store.removeAddon(url);
    }

    // ------------------------------------------------------------ catalogs

    /** Media-server rows first (your own library), then add-on catalogs. */
    public static List<Row> homeRows() {
        List<Future<List<Row>>> jobs = new ArrayList<>();
        for (MediaServer s : Store.servers()) jobs.add(POOL.submit(() -> Sources.rows(s)));
        for (Addon a : all()) {
            for (JSONObject cat : a.catalogs()) {
                if (!Addon.isHomeCatalog(cat)) continue;
                jobs.add(POOL.submit(() -> Collections.singletonList(fetchRow(a, cat, null, catalogTitle(cat)))));
            }
        }
        return collect(jobs);
    }

    public static List<Row> search(String query) {
        List<Future<List<Row>>> jobs = new ArrayList<>();
        for (MediaServer s : Store.servers()) jobs.add(POOL.submit(() -> Sources.search(s, query)));
        for (Addon a : all()) {
            for (JSONObject cat : a.catalogs()) {
                if (!Addon.isSearchCatalog(cat)) continue;
                String title = capitalize(cat.optString("type")) + " · " + a.name();
                jobs.add(POOL.submit(() -> Collections.singletonList(
                        fetchRow(a, cat, "search=" + Http.enc(query), title))));
            }
        }
        return collect(jobs);
    }

    private static Row fetchRow(Addon a, JSONObject cat, String extra, String title) throws Exception {
        String url = a.resourceUrl("catalog", cat.optString("type"), cat.optString("id"), extra);
        JSONArray metas = new JSONObject(Http.get(url, null)).optJSONArray("metas");
        List<JSONObject> items = new ArrayList<>();
        if (metas != null) for (int i = 0; i < metas.length() && items.size() < ROW_LIMIT; i++) {
            JSONObject m = metas.optJSONObject(i);
            if (m == null) continue;
            if (!m.has("type")) m.put("type", cat.optString("type"));
            items.add(m);
        }
        return new Row(title, items);
    }

    private static List<Row> collect(List<Future<List<Row>>> jobs) {
        List<Row> out = new ArrayList<>();
        for (Future<List<Row>> f : jobs) {
            try {
                for (Row r : f.get(40, TimeUnit.SECONDS)) if (!r.items.isEmpty()) out.add(r);
            } catch (Exception ignored) { }
        }
        return out;
    }

    private static String catalogTitle(JSONObject cat) {
        String name = cat.optString("name", cat.optString("id"));
        return name + " · " + capitalize(cat.optString("type"));
    }

    static String capitalize(String s) {
        return s == null || s.isEmpty() ? "" : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    // ------------------------------------------------------------ meta + streams

    public static JSONObject meta(String type, String id) throws Exception {
        if (Sources.isServerId(id)) return Sources.meta(id);
        Exception last = null;
        for (Addon a : all()) {
            if (!a.has("meta", type, id)) continue;
            try {
                JSONObject m = new JSONObject(Http.get(a.resourceUrl("meta", type, id, null), null)).optJSONObject("meta");
                if (m != null) return m;
            } catch (Exception e) {
                last = e;
            }
        }
        if (last != null) throw last;
        throw new Exception("No installed add-on has details for this title");
    }

    public static List<Stream> streams(String type, String videoId) throws Exception {
        if (Sources.isServerId(videoId)) return Sources.streams(videoId);

        // Same title on your Plex/Jellyfin/Emby servers (matched by IMDb id) is listed first.
        List<Future<List<Stream>>> serverJobs = new ArrayList<>();
        for (MediaServer s : Store.servers()) serverJobs.add(POOL.submit(() -> Sources.matchStreams(s, videoId)));

        List<Addon> addons = new ArrayList<>();
        List<Future<JSONArray>> jobs = new ArrayList<>();
        for (Addon a : all()) {
            if (!a.has("stream", type, videoId)) continue;
            addons.add(a);
            jobs.add(POOL.submit(() ->
                    new JSONObject(Http.get(a.resourceUrl("stream", type, videoId, null), null)).optJSONArray("streams")));
        }
        List<Stream> out = new ArrayList<>();
        for (Future<List<Stream>> f : serverJobs) {
            try {
                out.addAll(f.get(60, TimeUnit.SECONDS));
            } catch (Exception ignored) { }
        }
        for (int i = 0; i < jobs.size(); i++) {
            try {
                JSONArray arr = jobs.get(i).get(40, TimeUnit.SECONDS);
                if (arr == null) continue;
                for (int j = 0; j < arr.length(); j++) {
                    JSONObject s = arr.optJSONObject(j);
                    if (s != null) out.add(new Stream(addons.get(i).name(), s));
                }
            } catch (Exception ignored) { }
        }
        return out;
    }
}
