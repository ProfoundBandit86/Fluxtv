package com.fluxtv.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MDBList (mdblist.com): add any list as a row on Home (and in Movies / TV Shows), from Settings → Categories.
 * Uses the owner's free API key (mdblist.com/preferences → API Access); public lists also load without one.
 */
final class MdbList {

    static final class ListInfo {
        String url = "", name = "", user = "", slug = "", mediatype = "";
        int items;
    }

    private static final String API = "https://api.mdblist.com";
    private static final Map<String, Addons.Row> cache = new ConcurrentHashMap<>();
    private static final Map<String, Long> cacheTime = new ConcurrentHashMap<>();

    private MdbList() { }

    static String key() {
        return Store.get("mdblist_key").trim();
    }

    private static String api() {
        String t = BuildConfig.TEST_BUILD ? Store.get("test_mdblist") : "";
        return t.isEmpty() ? API : t;
    }

    /** Lists added in Settings, as [{url, name}] in the order shown. */
    static List<ListInfo> saved() {
        List<ListInfo> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(Store.get("mdblist_lists").isEmpty() ? "[]" : Store.get("mdblist_lists"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                ListInfo l = parse(o.optString("url"));
                if (l == null) continue;
                l.name = o.optString("name", l.slug);
                out.add(l);
            }
        } catch (Exception ignored) { }
        return out;
    }

    private static void save(List<ListInfo> lists) {
        JSONArray a = new JSONArray();
        for (ListInfo l : lists) {
            try { a.put(new JSONObject().put("url", l.url).put("name", l.name)); } catch (Exception ignored) { }
        }
        Store.put("mdblist_lists", a.toString());
        Store.bumpSources();
    }

    /** "https://mdblist.com/lists/user/slug" (or "user/slug") → its parts, or null. */
    static ListInfo parse(String raw) {
        if (raw == null) return null;
        String u = raw.trim().replaceAll("[?#].*$", "").replaceAll("/+$", "");
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?:mdblist\\.com/lists/)?([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+)$").matcher(u);
        if (!m.find()) return null;
        ListInfo l = new ListInfo();
        l.user = m.group(1);
        l.slug = m.group(2);
        l.url = "https://mdblist.com/lists/" + l.user + "/" + l.slug;
        l.name = prettify(l.slug);
        return l;
    }

    private static String prettify(String slug) {
        StringBuilder sb = new StringBuilder();
        for (String w : slug.split("[-_]+")) {
            if (w.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return sb.toString();
    }

    /** Adds a list by its mdblist.com link; checks it loads first. Blocking. Returns its name. */
    static String add(String url) throws Exception {
        ListInfo l = parse(url);
        if (l == null) throw new Exception("Paste a link like mdblist.com/lists/username/list-name");
        List<ListInfo> all = saved();
        for (ListInfo x : all) if (x.url.equals(l.url)) return x.name;
        Addons.Row r = fetch(l);
        if (r.items.isEmpty()) throw new Exception("That list is empty or private");
        all.add(l);
        save(all);
        return l.name;
    }

    static void add(ListInfo l) {
        List<ListInfo> all = saved();
        for (ListInfo x : all) if (x.url.equals(l.url)) return;
        all.add(l);
        save(all);
    }

    static void remove(String url) {
        List<ListInfo> all = saved();
        for (int i = all.size() - 1; i >= 0; i--) if (all.get(i).url.equals(url)) all.remove(i);
        save(all);
        cache.remove(url);
    }

    /** A list's titles as a Home row (cached for 6 hours). Blocking. */
    static Addons.Row fetch(ListInfo l) throws Exception {
        Addons.Row c = cache.get(l.url);
        Long t = cacheTime.get(l.url);
        if (c != null && t != null && System.currentTimeMillis() - t < 6 * 3600_000L) return c;
        String body;
        String k = key();
        if (!k.isEmpty()) {
            body = Http.get(api() + "/lists/" + Http.enc(l.user) + "/" + Http.enc(l.slug) + "/items?limit=100&apikey=" + Http.enc(k), null);
        } else {
            body = Http.get(l.url + "/json", null);   // public lists, no key needed
        }
        List<JSONObject> items = new ArrayList<>();
        String type = "";
        Object j = new org.json.JSONTokener(body).nextValue();
        List<JSONArray> parts = new ArrayList<>();
        if (j instanceof JSONArray) parts.add((JSONArray) j);
        else if (j instanceof JSONObject) {
            JSONObject o = (JSONObject) j;
            if (o.has("error")) throw new Exception("MDBList: " + o.optString("error"));
            if (o.optJSONArray("movies") != null) parts.add(o.optJSONArray("movies"));
            if (o.optJSONArray("shows") != null) parts.add(o.optJSONArray("shows"));
            if (o.optJSONArray("items") != null) parts.add(o.optJSONArray("items"));
        }
        java.util.Set<String> seen = new java.util.HashSet<>();
        int movies = 0, shows = 0;
        for (JSONArray a : parts) {
            for (int i = 0; i < a.length() && items.size() < 100; i++) {
                JSONObject it = a.optJSONObject(i);
                if (it == null) continue;
                String imdb = it.optString("imdb_id");
                if (!imdb.startsWith("tt") || !seen.add(imdb)) continue;
                boolean show = it.optString("mediatype").toLowerCase(Locale.ROOT).startsWith("show");
                if (show) shows++; else movies++;
                JSONObject m = new JSONObject();
                m.put("id", imdb);
                m.put("type", show ? "series" : "movie");
                m.put("name", it.optString("title"));
                m.put("poster", "https://images.metahub.space/poster/medium/" + imdb + "/img");
                m.put("background", "https://images.metahub.space/background/medium/" + imdb + "/img");
                if (it.optInt("release_year") > 0) m.put("releaseInfo", String.valueOf(it.optInt("release_year")));
                items.add(m);
            }
        }
        if (movies > 0 && shows == 0) type = "movie";
        else if (shows > 0 && movies == 0) type = "series";
        Addons.Row r = new Addons.Row(l.name, items);
        r.type = type;
        r.note = "MDBList";
        cache.put(l.url, r);
        cacheTime.put(l.url, System.currentTimeMillis());
        return r;
    }

    /** Popular lists on MDBList (needs the API key). Blocking. */
    static List<ListInfo> topLists() throws Exception {
        return lists("/lists/top");
    }

    /** The owner's own lists on MDBList (needs the API key). Blocking. */
    static List<ListInfo> myLists() throws Exception {
        return lists("/lists/user");
    }

    private static List<ListInfo> lists(String path) throws Exception {
        String k = key();
        if (k.isEmpty()) throw new Exception("Add your MDBList API key first");
        JSONArray a = new JSONArray(Http.get(api() + path + "?apikey=" + Http.enc(k), null));
        List<ListInfo> out = new ArrayList<>();
        for (int i = 0; i < a.length() && out.size() < 24; i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            String user = o.optString("user_name", o.optString("username"));
            String slug = o.optString("slug");
            if (user.isEmpty() || slug.isEmpty()) continue;
            ListInfo l = new ListInfo();
            l.user = user;
            l.slug = slug;
            l.url = "https://mdblist.com/lists/" + user + "/" + slug;
            l.name = o.optString("name", prettify(slug));
            l.mediatype = o.optString("mediatype");
            l.items = o.optInt("items");
            out.add(l);
        }
        return out;
    }
}
