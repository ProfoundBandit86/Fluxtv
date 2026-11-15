package com.fluxtv.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * TMDB (themoviedb.org): streaming-service catalogs with their logos, cast & crew, and
 * people's filmographies. Titles from TMDB use ids like "tmdb:movie:603" / "tmdb:tv:1396"
 * and are switched to their IMDb id when opened, so streams and library matches work.
 */
public final class Tmdb {

    private static final String REAL_API = "https://api.themoviedb.org/3";

    private static String api() {
        return BuildConfig.TEST_BUILD && !Store.get("test_tmdb").isEmpty() ? Store.get("test_tmdb") : REAL_API;
    }
    public static final String IMG = "https://image.tmdb.org/t/p/";
    private static final long TTL = 30 * 60 * 1000L;
    private static final Map<String, JSONObject> cache = new ConcurrentHashMap<>();
    private static final Map<String, Long> cacheTime = new ConcurrentHashMap<>();

    /** key, display name, TMDB provider names (lower case), TMDB network id for the wordmark, app packages. */
    private static final String[][] SERVICES = {
            {"netflix", "Netflix", "netflix", "213", "com.netflix.ninja"},
            {"max", "HBO Max", "max|hbo max|max amazon channel", "49", "com.wbd.stream|com.hbo.hbonow"},
            {"disney", "Disney+", "disney plus|disney+", "2739", "com.disney.disneyplus"},
            {"prime", "Prime Video", "amazon prime video|prime video", "1024", "com.amazon.amazonvideo.livingroom"},
            {"apple", "Apple TV+", "apple tv+|apple tv plus|apple tv", "2552", "com.apple.atve.androidtv.appletv"},
            {"hulu", "Hulu", "hulu", "453", "com.hulu.livingroomplus|com.hulu.plus"},
            {"paramount", "Paramount+", "paramount+|paramount plus|paramount+ premium|paramount plus premium|paramount+ essential", "4330", "com.cbs.ott"},
            {"peacock", "Peacock", "peacock|peacock premium|peacock premium plus", "3353", "com.peacocktv.peacockandroid"},
            {"shudder", "Shudder", "shudder", "", "com.dramafever.shudder|com.shudder.app"},
            {"tubi", "Tubi", "tubi tv|tubi", "", "com.tubitv"},
    };

    /** [key, name] of every streaming service Flux has a page for. */
    public static List<String[]> serviceNames() {
        List<String[]> out = new ArrayList<>();
        for (String[] d : SERVICES) out.add(new String[]{d[0], d[1]});
        return out;
    }

    public static class Service {
        public String key, name, logo = "", wordmark = "";
        public int providerId;
        public String[] packages;
    }

    private Tmdb() { }

    public static boolean enabled() {
        return !Store.get("tmdb_key").trim().isEmpty();
    }

    public static String region() {
        String r = Store.get("region").trim();
        return r.isEmpty() ? "US" : r.toUpperCase(Locale.ROOT);
    }

    private static JSONObject get(String path, String query) throws Exception {
        String k = Store.get("tmdb_key").trim();
        if (k.isEmpty()) throw new Exception("Add a TMDB API key in Settings");
        boolean bearer = k.startsWith("eyJ") || k.length() > 60;   // v4 read token vs v3 key
        String url = api() + path + "?" + (query == null ? "" : query) + (bearer ? "" : "&api_key=" + Http.enc(k));
        Long t = cacheTime.get(url);
        JSONObject c = cache.get(url);
        if (c != null && t != null && System.currentTimeMillis() - t < TTL) return c;
        Map<String, String> h = Http.map("Accept", "application/json");
        if (bearer) h.put("Authorization", "Bearer " + k);
        JSONObject j;
        try {
            j = new JSONObject(Http.call("GET", url, h, null, null));
        } catch (Http.HttpException e) {
            if (e.code == 401) throw new Exception("TMDB didn't accept the API key. Check it in Settings.");
            throw e;
        }
        cache.put(url, j);
        cacheTime.put(url, System.currentTimeMillis());
        return j;
    }

    // ------------------------------------------------------------ services

    /** The ten services, with TMDB provider ids and logos when a key is set. */
    /**
     * A service's wordmark from TMDB's companies (for services without a TMDB network, e.g. Shudder, Tubi).
     * Cached, since it never changes.
     */
    static String companyLogo(String name) {
        String key = "logo_company:" + name;
        String cached = Store.get(key);
        if (!cached.isEmpty()) return cached.equals("-") ? "" : cached;
        String found = "";
        try {
            JSONArray r = get("/search/company", "query=" + Http.enc(name)).optJSONArray("results");
            if (r != null) {
                for (int i = 0; i < r.length() && found.isEmpty(); i++) {
                    JSONObject c = r.optJSONObject(i);
                    if (c.optString("name").equalsIgnoreCase(name) && !c.optString("logo_path").isEmpty()) found = IMG + "w500" + c.optString("logo_path");
                }
                for (int i = 0; i < r.length() && found.isEmpty(); i++) {
                    JSONObject c = r.optJSONObject(i);
                    if (c.optString("name").toLowerCase(Locale.ROOT).startsWith(name.toLowerCase(Locale.ROOT))
                            && !c.optString("logo_path").isEmpty()) found = IMG + "w500" + c.optString("logo_path");
                }
            }
            Store.put(key, found.isEmpty() ? "-" : found);
        } catch (Exception ignored) { }
        return found;
    }

    public static List<Service> services() {
        List<Service> out = new ArrayList<>();
        Map<String, JSONObject> providers = new LinkedHashMap<>();
        if (enabled()) {
            for (String kind : new String[]{"movie", "tv"}) {
                try {
                    JSONArray r = get("/watch/providers/" + kind, "watch_region=" + region()).optJSONArray("results");
                    if (r != null) for (int i = 0; i < r.length(); i++) {
                        JSONObject p = r.optJSONObject(i);
                        if (p != null) providers.put(p.optString("provider_name").toLowerCase(Locale.ROOT), p);
                    }
                } catch (Exception ignored) { }
            }
        }
        for (String[] def : SERVICES) {
            Service s = new Service();
            s.key = def[0];
            s.name = def[1];
            s.packages = def[4].split("\\|");
            JSONObject best = null;
            for (String alias : def[2].split("\\|")) {
                JSONObject p = providers.get(alias);
                if (p != null && (best == null || p.optInt("display_priority", 99) < best.optInt("display_priority", 99))) best = p;
            }
            if (best != null) {
                s.providerId = best.optInt("provider_id");
                if (!best.optString("logo_path").isEmpty()) s.logo = IMG + "w300" + best.optString("logo_path");
            }
            if (!def[3].isEmpty() && enabled()) {
                try {
                    JSONObject n = get("/network/" + def[3], null);
                    String nn = n.optString("name").toLowerCase(Locale.ROOT);
                    boolean matches = false;
                    for (String w : (def[1] + "|" + def[2]).toLowerCase(Locale.ROOT).split("[|+ ]")) {
                        if (w.length() >= 3 && nn.contains(w)) matches = true;
                    }
                    if (matches && !n.optString("logo_path").isEmpty()) s.wordmark = IMG + "w500" + n.optString("logo_path");
                } catch (Exception ignored) { }
            }
            if (s.wordmark.isEmpty() && enabled()) s.wordmark = companyLogo(s.name);
            out.add(s);
        }
        return out;
    }

    public static Service service(String key) {
        for (Service s : services()) if (s.key.equals(key)) return s;
        return null;
    }

    /** Latest / Most popular / Top rated movies and shows on one service. */
    public static List<Addons.Row> serviceRows(Service s) throws Exception {
        if (!enabled()) throw new Exception("Add a TMDB API key in Settings to browse services");
        if (s.providerId == 0) throw new Exception(s.name + " isn't listed for your region");
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(new Date());
        String base = "watch_region=" + region() + "&with_watch_providers=" + s.providerId + "&include_adult=false";
        String[][] rows = {
                {"Latest movies", "movie", base + "&sort_by=primary_release_date.desc&primary_release_date.lte=" + today + "&vote_count.gte=5"},
                {"Latest shows", "tv", base + "&sort_by=first_air_date.desc&first_air_date.lte=" + today + "&vote_count.gte=5"},
                {"Most popular movies", "movie", base + "&sort_by=popularity.desc"},
                {"Most popular shows", "tv", base + "&sort_by=popularity.desc"},
                {"Top rated movies", "movie", base + "&sort_by=vote_average.desc&vote_count.gte=300"},
                {"Top rated shows", "tv", base + "&sort_by=vote_average.desc&vote_count.gte=200"},
        };
        List<Future<Addons.Row>> jobs = new ArrayList<>();
        for (String[] r : rows) {
            jobs.add(Addons.POOL.submit(() -> {
                JSONArray res = get("/discover/" + r[1], r[2]).optJSONArray("results");
                List<JSONObject> items = new ArrayList<>();
                if (res != null) for (int i = 0; i < res.length(); i++) {
                    JSONObject p = preview(res.optJSONObject(i), r[1]);
                    if (p != null) {
                        p.put("service", s.key);
                        items.add(p);
                    }
                }
                Addons.Row row = new Addons.Row(r[0], items);
                row.type = r[1].equals("tv") ? "series" : "movie";
                row.note = s.name;
                return row;
            }));
        }
        List<Addons.Row> out = new ArrayList<>();
        for (Future<Addons.Row> f : jobs) {
            try {
                Addons.Row r = f.get(40, TimeUnit.SECONDS);
                if (!r.items.isEmpty()) out.add(r);
            } catch (Exception ignored) { }
        }
        return out;
    }

    static JSONObject preview(JSONObject r, String mediaType) throws Exception {
        if (r == null) return null;
        String mt = r.has("media_type") ? r.optString("media_type") : mediaType;
        if (mt == null || (!mt.equals("movie") && !mt.equals("tv"))) return null;
        JSONObject p = new JSONObject();
        p.put("id", "tmdb:" + mt + ":" + r.optInt("id"));
        p.put("type", mt.equals("tv") ? "series" : "movie");
        p.put("name", r.optString(mt.equals("tv") ? "name" : "title"));
        if (!r.optString("poster_path").isEmpty() && !r.isNull("poster_path")) p.put("poster", IMG + "w342" + r.optString("poster_path"));
        if (!r.optString("backdrop_path").isEmpty() && !r.isNull("backdrop_path")) p.put("background", IMG + "w1280" + r.optString("backdrop_path"));
        p.put("description", r.optString("overview"));
        String date = r.optString(mt.equals("tv") ? "first_air_date" : "release_date");
        if (date.length() >= 4) p.put("releaseInfo", date.substring(0, 4));
        if (r.optDouble("vote_average", 0) > 0) p.put("rating", String.format(Locale.ROOT, "%.1f", r.optDouble("vote_average")));
        return p;
    }

    // ------------------------------------------------------------ resolving titles

    private static String[] parse(String id) {
        return id.split(":");   // tmdb, movie|tv, number
    }

    public static String imdbFor(String tmdbId) {
        try {
            String[] p = parse(tmdbId);
            String imdb = get("/" + p[1] + "/" + p[2] + "/external_ids", null).optString("imdb_id");
            return imdb.startsWith("tt") ? imdb : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Opens a TMDB title: via its IMDb id when possible (full streams), else from TMDB alone. */
    public static JSONObject resolve(String tmdbId) throws Exception {
        String[] p = parse(tmdbId);
        String type = p[1].equals("tv") ? "series" : "movie";
        String imdb = imdbFor(tmdbId);
        if (imdb != null) {
            try {
                JSONObject m = Addons.meta(type, imdb);
                if (!m.has("type")) m.put("type", type);
                return m;
            } catch (Exception ignored) { }
        }
        JSONObject r = get("/" + p[1] + "/" + p[2], "append_to_response=credits");
        JSONObject m = preview(r, p[1]);
        if (imdb != null) m.put("id", imdb);
        JSONArray genres = new JSONArray();
        JSONArray g = r.optJSONArray("genres");
        if (g != null) for (int i = 0; i < g.length(); i++) genres.put(g.optJSONObject(i).optString("name"));
        m.put("genres", genres);
        if (r.optInt("runtime") > 0) m.put("runtime", r.optInt("runtime") + " min");
        return m;
    }

    /** TMDB numeric id for an IMDb ("tt…") or TMDB ("tmdb:…") id. */
    public static int tmdbNumber(String id, String type) throws Exception {
        if (id.startsWith("tmdb:")) return Integer.parseInt(parse(id)[2]);
        JSONObject f = get("/find/" + id, "external_source=imdb_id");
        JSONArray a = f.optJSONArray("series".equals(type) ? "tv_results" : "movie_results");
        if (a == null || a.length() == 0) a = f.optJSONArray("series".equals(type) ? "movie_results" : "tv_results");
        if (a == null || a.length() == 0) throw new Exception("TMDB doesn't know this title");
        return a.optJSONObject(0).optInt("id");
    }

    public static boolean canCredit(String id) {
        return enabled() && id != null && (id.startsWith("tt") || id.startsWith("tmdb:"));
    }

    // ------------------------------------------------------------ cast & crew

    private static final List<String> CREW_JOBS = java.util.Arrays.asList(
            "Director", "Creator", "Screenplay", "Writer", "Novel", "Story", "Executive Producer", "Producer",
            "Original Music Composer", "Director of Photography", "Editor");

    /** [cast list, crew list]; each person: personId, name, role, photo. */
    public static List<List<JSONObject>> credits(String id, String type) throws Exception {
        boolean tv = "series".equals(type) || id.startsWith("tmdb:tv");
        int n = tmdbNumber(id, tv ? "series" : "movie");
        List<JSONObject> cast = new ArrayList<>(), crew = new ArrayList<>();
        if (tv) {
            JSONObject show = get("/tv/" + n, null);
            JSONArray creators = show.optJSONArray("created_by");
            if (creators != null) for (int i = 0; i < creators.length(); i++) {
                JSONObject c = creators.optJSONObject(i);
                crew.add(person(c.optInt("id"), c.optString("name"), "Creator", c.optString("profile_path")));
            }
            JSONObject ac = get("/tv/" + n + "/aggregate_credits", null);
            JSONArray a = ac.optJSONArray("cast");
            if (a != null) for (int i = 0; i < a.length() && cast.size() < 40; i++) {
                JSONObject c = a.optJSONObject(i);
                JSONArray roles = c.optJSONArray("roles");
                String role = roles != null && roles.length() > 0 ? roles.optJSONObject(0).optString("character") : "";
                int eps = c.optInt("total_episode_count");
                cast.add(person(c.optInt("id"), c.optString("name"), role + (eps > 0 ? "  ·  " + eps + " ep" : ""), c.optString("profile_path")));
            }
            addCrew(crew, ac.optJSONArray("crew"), true);
        } else {
            JSONObject cr = get("/movie/" + n + "/credits", null);
            JSONArray a = cr.optJSONArray("cast");
            if (a != null) for (int i = 0; i < a.length() && cast.size() < 40; i++) {
                JSONObject c = a.optJSONObject(i);
                cast.add(person(c.optInt("id"), c.optString("name"), c.optString("character"), c.optString("profile_path")));
            }
            addCrew(crew, cr.optJSONArray("crew"), false);
        }
        List<List<JSONObject>> out = new ArrayList<>();
        out.add(cast);
        out.add(crew);
        return out;
    }

    private static void addCrew(List<JSONObject> crew, JSONArray a, boolean tv) throws Exception {
        if (a == null) return;
        List<JSONObject> picked = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            JSONObject c = a.optJSONObject(i);
            if (c == null) continue;
            String job = tv && c.optJSONArray("jobs") != null && c.optJSONArray("jobs").length() > 0
                    ? c.optJSONArray("jobs").optJSONObject(0).optString("job") : c.optString("job");
            if (!CREW_JOBS.contains(job)) continue;
            JSONObject p = person(c.optInt("id"), c.optString("name"), job, c.optString("profile_path"));
            p.put("rank", CREW_JOBS.indexOf(job));
            picked.add(p);
        }
        Collections.sort(picked, (x, y) -> x.optInt("rank") - y.optInt("rank"));
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        for (JSONObject c : crew) seen.add(c.optInt("personId"));
        for (JSONObject p : picked) if (seen.add(p.optInt("personId")) && crew.size() < 30) crew.add(p);
    }

    private static JSONObject person(int id, String name, String role, String photo) throws Exception {
        JSONObject p = new JSONObject();
        p.put("personId", id);
        p.put("name", name);
        p.put("role", role);
        if (photo != null && !photo.isEmpty() && !photo.equals("null")) p.put("photo", IMG + "w185" + photo);
        return p;
    }

    /** A person's details and everything they worked on, newest first. */
    public static JSONObject personPage(int id) throws Exception {
        JSONObject r = get("/person/" + id, "append_to_response=combined_credits");
        JSONObject out = new JSONObject();
        out.put("name", r.optString("name"));
        out.put("bio", r.optString("biography"));
        out.put("known", r.optString("known_for_department"));
        out.put("birthday", r.optString("birthday"));
        out.put("place", r.optString("place_of_birth"));
        if (!r.isNull("profile_path") && !r.optString("profile_path").isEmpty()) out.put("photo", IMG + "h632" + r.optString("profile_path"));

        Map<String, JSONObject> byTitle = new LinkedHashMap<>();
        JSONObject cc = r.optJSONObject("combined_credits");
        for (String part : new String[]{"cast", "crew"}) {
            JSONArray a = cc == null ? null : cc.optJSONArray(part);
            if (a == null) continue;
            for (int i = 0; i < a.length(); i++) {
                JSONObject c = a.optJSONObject(i);
                if (c == null) continue;
                JSONObject p = preview(c, c.optString("media_type"));
                if (p == null) continue;
                String role = part.equals("cast") ? c.optString("character") : c.optString("job");
                String date = c.optString("release_date", c.optString("first_air_date"));
                if (date.isEmpty()) date = c.optString("first_air_date");
                JSONObject prev = byTitle.get(p.optString("id"));
                if (prev != null) {
                    if (!role.isEmpty() && !prev.optString("role").contains(role)) {
                        prev.put("role", prev.optString("role").isEmpty() ? role : prev.optString("role") + ", " + role);
                    }
                    continue;
                }
                p.put("role", role);
                p.put("date", date);
                if (part.equals("cast") && c.optInt("episode_count") > 0) p.put("episodes", c.optInt("episode_count"));
                byTitle.put(p.optString("id"), p);
            }
        }
        List<JSONObject> list = new ArrayList<>(byTitle.values());
        // Newest first; undated (announced) titles go first too, as they're upcoming.
        Collections.sort(list, (a, b) -> {
            String da = a.optString("date"), db = b.optString("date");
            if (da.isEmpty() && db.isEmpty()) return 0;
            if (da.isEmpty()) return -1;
            if (db.isEmpty()) return 1;
            return db.compareTo(da);
        });
        out.put("credits", new JSONArray(list));
        return out;
    }

    // ------------------------------------------------------------ search helper (for Gemini)

    public static JSONObject find(String title, int year, String type) {
        try {
            boolean tv = "series".equals(type);
            String q = "query=" + Http.enc(title) + "&include_adult=false" + (year > 0 ? (tv ? "&first_air_date_year=" : "&year=") + year : "");
            JSONArray r = get(tv ? "/search/tv" : "/search/movie", q).optJSONArray("results");
            if ((r == null || r.length() == 0) && year > 0) {
                r = get(tv ? "/search/tv" : "/search/movie", "query=" + Http.enc(title) + "&include_adult=false").optJSONArray("results");
            }
            if (r == null || r.length() == 0) {
                r = get("/search/multi", "query=" + Http.enc(title) + "&include_adult=false").optJSONArray("results");
                if (r != null) for (int i = 0; i < r.length(); i++) {
                    JSONObject p = preview(r.optJSONObject(i), null);
                    if (p != null) return p;
                }
                return null;
            }
            return preview(r.optJSONObject(0), tv ? "tv" : "movie");
        } catch (Exception e) {
            return null;
        }
    }
}
