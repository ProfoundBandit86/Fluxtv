package com.fluxtv.app;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Mixes your Plex / Jellyfin / Emby libraries into the same Movies and Shows rows as the
 * streaming add-ons. Titles are matched by IMDb ID: a library title with an IMDb ID opens
 * the same page as the streaming version (your copy is listed first in its streams), and
 * catalog titles you own get a "Plex" / "Jellyfin" / "Emby" badge.
 */
public final class Blend {

    public static class Home {
        public final List<JSONObject> continueWatching = new ArrayList<>();
        public final List<Addons.Row> rows = new ArrayList<>();
    }

    /** Ownership across all servers. */
    private static class Library {
        final Map<String, String> serverToImdb = new HashMap<>();   // "plex:abc:123" → "tt0111161"
        final Map<String, String> badge = new HashMap<>();          // "tt0111161" → "Plex"
    }

    private Blend() { }

    private static Library library(List<MediaServer> servers) {
        Library lib = new Library();
        List<Future<Map<String, String>>> jobs = new ArrayList<>();
        for (MediaServer s : servers) jobs.add(Addons.POOL.submit(() -> Sources.index(s)));
        for (int i = 0; i < jobs.size(); i++) {
            MediaServer s = servers.get(i);
            try {
                for (Map.Entry<String, String> e : jobs.get(i).get(90, TimeUnit.SECONDS).entrySet()) {
                    lib.serverToImdb.put(s.prefix() + e.getValue(), e.getKey());
                    if (!lib.badge.containsKey(e.getKey())) lib.badge.put(e.getKey(), s.kindLabel());
                }
            } catch (Exception ignored) { }
        }
        return lib;
    }

    /** Switches a library item to its IMDb id (keeping the server id as a fallback) and badges owned titles. */
    private static JSONObject unify(JSONObject p, Library lib) {
        try {
            String id = p.optString("id");
            String imdb = lib.serverToImdb.get(id);
            if (imdb != null) {
                p.put("serverId", id);
                p.put("id", imdb);
                id = imdb;
            }
            if (p.optString("badge").isEmpty() && lib.badge.containsKey(id)) p.put("badge", lib.badge.get(id));
        } catch (Exception ignored) { }
        return p;
    }

    private static <T> List<T> all(List<Future<List<T>>> jobs) {
        List<T> out = new ArrayList<>();
        for (Future<List<T>> f : jobs) {
            try {
                out.addAll(f.get(60, TimeUnit.SECONDS));
            } catch (Exception ignored) { }
        }
        return out;
    }

    private static List<Addons.Row> rowsOf(List<Future<Addons.Row>> jobs) {
        List<Addons.Row> out = new ArrayList<>();
        for (Future<Addons.Row> f : jobs) {
            try {
                Addons.Row r = f.get(40, TimeUnit.SECONDS);
                if (!r.items.isEmpty()) out.add(r);
            } catch (Exception ignored) { }
        }
        return out;
    }

    // ------------------------------------------------------------ home

    public static Home home() {
        List<MediaServer> servers = Store.servers();
        Future<Library> libJob = Addons.POOL.submit(() -> library(servers));
        List<Future<List<JSONObject>>> deckJobs = new ArrayList<>(), recentJobs = new ArrayList<>();
        for (MediaServer s : servers) {
            deckJobs.add(Addons.POOL.submit(() -> Sources.deck(s)));
            recentJobs.add(Addons.POOL.submit(() -> Sources.recent(s)));
        }
        List<Future<Addons.Row>> catalogJobs = Addons.catalogJobs();
        Future<List<Addons.Row>> iptvMovies = Addons.POOL.submit(() -> Iptv.vodRows("movie", 1));
        Future<List<Addons.Row>> iptvShows = Addons.POOL.submit(() -> Iptv.vodRows("series", 1));

        Library lib;
        try {
            lib = libJob.get(100, TimeUnit.SECONDS);
        } catch (Exception e) {
            lib = new Library();
        }
        Home h = new Home();

        // Continue watching: what you watched in Flux, then in-progress items from your servers.
        Set<String> seen = new HashSet<>();
        for (JSONObject e : Store.continueWatching()) {
            JSONObject p = new JSONObject();
            try {
                p.put("id", e.optString("id"));
                p.put("type", e.optString("type"));
                p.put("name", e.optString("name"));
                p.put("poster", e.optString("poster"));
                p.put("background", e.optString("background"));
                p.put("label", e.optString("label"));
                p.put("progress", (double) e.optLong("pos") / Math.max(1, e.optLong("dur")));
                String label = e.optString("label");
                p.put("sub", (label.isEmpty() ? "" : label + " · ") + Ui.left(e.optLong("pos"), e.optLong("dur")));
                p.put("t", e.optLong("t"));
            } catch (Exception ignored) { }
            unify(p, lib);
            if (seen.add(p.optString("id"))) h.continueWatching.add(p);
        }
        List<JSONObject> deck = all(deckJobs);
        Collections.sort(deck, (a, b) -> Long.compare(b.optLong("t"), a.optLong("t")));
        for (JSONObject p : deck) {
            unify(p, lib);
            if (seen.add(p.optString("id"))) h.continueWatching.add(p);
        }

        // Newest additions to your libraries, per type.
        List<JSONObject> recent = all(recentJobs);
        Collections.sort(recent, (a, b) -> Long.compare(b.optLong("added"), a.optLong("added")));
        Map<String, List<JSONObject>> recentByType = new HashMap<>();
        Set<String> recentSeen = new HashSet<>();
        for (JSONObject p : recent) {
            unify(p, lib);
            if (!recentSeen.add(p.optString("id"))) continue;
            List<JSONObject> list = recentByType.get(p.optString("type"));
            if (list == null) recentByType.put(p.optString("type"), list = new ArrayList<>());
            if (list.size() < 30) list.add(p);
        }

        // Add-on catalogs, with titles you own badged.
        List<Addons.Row> catalogs = rowsOf(catalogJobs);
        for (Addons.Row r : catalogs) for (JSONObject m : r.items) unify(m, lib);

        // Movies first, then shows, then anything else; your library leads each section.
        for (String type : new String[]{"movie", "series"}) {
            List<JSONObject> mine = recentByType.get(type);
            if (mine != null && !mine.isEmpty()) {
                Addons.Row r = new Addons.Row(type.equals("movie") ? "New in your library · Movies" : "New in your library · Shows", mine);
                r.type = type;
                h.rows.add(r);
            }
            for (Addons.Row r : catalogs) {
                if (!type.equals(r.type)) continue;
                h.rows.add(withTypeTitle(r));
            }
            try {
                for (Addons.Row r : (type.equals("movie") ? iptvMovies : iptvShows).get(60, TimeUnit.SECONDS)) {
                    h.rows.add(withTypeTitle(r));
                }
            } catch (Exception ignored) { }
        }
        for (Addons.Row r : catalogs) {
            if (!r.type.equals("movie") && !r.type.equals("series")) h.rows.add(withTypeTitle(r));
        }
        return h;
    }

    private static Addons.Row withTypeTitle(Addons.Row r) {
        String t = r.type.equals("movie") ? "Movies" : r.type.equals("series") ? "Shows" : Addons.capitalize(r.type);
        Addons.Row out = new Addons.Row(r.title + " · " + t, r.items);
        out.type = r.type;
        out.note = r.note;
        return out;
    }

    // ------------------------------------------------------------ search

    /** One Movies row and one Shows row: your library's matches first, then streaming results. */
    public static List<Addons.Row> search(String q) {
        List<MediaServer> servers = Store.servers();
        Future<Library> libJob = Addons.POOL.submit(() -> library(servers));
        List<Future<List<JSONObject>>> serverJobs = new ArrayList<>();
        for (MediaServer s : servers) serverJobs.add(Addons.POOL.submit(() -> Sources.search(s, q)));
        List<Future<Addons.Row>> catalogJobs = Addons.searchJobs(q);
        serverJobs.add(Addons.POOL.submit(() -> Iptv.search(q)));

        Library lib;
        try {
            lib = libJob.get(100, TimeUnit.SECONDS);
        } catch (Exception e) {
            lib = new Library();
        }
        Map<String, List<JSONObject>> byType = new HashMap<>();
        List<String> order = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        List<JSONObject> combined = new ArrayList<>(all(serverJobs));
        for (Addons.Row r : rowsOf(catalogJobs)) combined.addAll(r.items);
        for (JSONObject p : combined) {
            unify(p, lib);
            if (!seen.add(p.optString("id"))) continue;
            String type = p.optString("type", "movie");
            List<JSONObject> list = byType.get(type);
            if (list == null) {
                byType.put(type, list = new ArrayList<>());
                order.add(type);
            }
            list.add(p);
        }
        Collections.sort(order, (a, b) -> rank(a) - rank(b));
        List<Addons.Row> rows = new ArrayList<>();
        for (String type : order) {
            Addons.Row r = new Addons.Row(type.equals("movie") ? "Movies" : type.equals("series") ? "Shows"
                    : Addons.capitalize(type), byType.get(type));
            r.type = type;
            rows.add(r);
        }
        return rows;
    }

    private static int rank(String type) {
        return type.equals("movie") ? 0 : type.equals("series") ? 1 : 2;
    }
}
