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

    /** Receives Home while it's still loading (Continue watching first, then rows as each source answers). */
    public interface Progress {
        void partial(Home h);
    }

    public static Home home() {
        return home(null);
    }

    /** Flux's own Continue watching, straight from this TV (no network). */
    private static List<JSONObject> localContinue() {
        List<JSONObject> out = new ArrayList<>();
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
            if (seen.add(p.optString("id"))) out.add(p);
        }
        return out;
    }

    /** Rows from the catalogs that have answered so far, in the same order as the finished Home. */
    private static Home partialHome(List<JSONObject> cw, List<Future<Addons.Row>> catalogJobs) {
        Home h = new Home();
        h.continueWatching.addAll(cw);
        List<Addons.Row> done = new ArrayList<>();
        for (Future<Addons.Row> f : catalogJobs) {
            if (!f.isDone()) continue;
            try { Addons.Row r = f.get(); if (!r.items.isEmpty()) done.add(r); } catch (Exception ignored) { }
        }
        for (String type : new String[]{"movie", "series"}) for (Addons.Row r : done) if (type.equals(r.type)) h.rows.add(withTypeTitle(r));
        for (Addons.Row r : done) if (!r.type.equals("movie") && !r.type.equals("series")) h.rows.add(withTypeTitle(r));
        return h;
    }

    public static Home home(Progress progress) {
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

        if (progress != null) {
            // Show something straight away, then each row as soon as its add-on answers.
            List<JSONObject> cw = localContinue();
            progress.partial(partialHome(cw, catalogJobs));
            int shown = 0;
            long until = System.currentTimeMillis() + 40000;
            while (System.currentTimeMillis() < until) {
                int done = 0;
                for (Future<Addons.Row> f : catalogJobs) if (f.isDone()) done++;
                if (done != shown) {
                    shown = done;
                    progress.partial(partialHome(cw, catalogJobs));
                }
                if (done == catalogJobs.size()) break;
                try { Thread.sleep(250); } catch (InterruptedException e) { break; }
            }
        }

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

    // ------------------------------------------------------------ saved copy (Home shows instantly next time)

    private static java.io.File cacheFile() {
        return new java.io.File(App.ctx.getFilesDir(), "home_" + Store.profile().replaceAll("[^A-Za-z0-9_-]", "_") + ".json");
    }

    public static void save(Home h) {
        try {
            org.json.JSONObject o = new org.json.JSONObject();
            o.put("cw", new org.json.JSONArray(h.continueWatching));
            org.json.JSONArray rows = new org.json.JSONArray();
            for (Addons.Row r : h.rows) {
                rows.put(new org.json.JSONObject().put("title", r.title).put("type", r.type).put("note", r.note)
                        .put("wide", r.wide).put("items", new org.json.JSONArray(r.items)));
            }
            o.put("rows", rows);
            o.put("addons", Store.addonsVersionKey());
            java.io.File f = cacheFile(), tmp = new java.io.File(f.getPath() + ".tmp");
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(tmp)) {
                out.write(o.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            tmp.renameTo(f);
        } catch (Exception ignored) { }
    }

    /** The last Home for this profile (with today's Continue watching from this TV), or null. */
    public static Home cached() {
        java.io.File f = cacheFile();
        if (!f.exists()) return null;
        try {
            byte[] d = new byte[(int) f.length()];
            try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
                int off = 0, n;
                while (off < d.length && (n = in.read(d, off, d.length - off)) > 0) off += n;
            }
            org.json.JSONObject o = new org.json.JSONObject(new String(d, java.nio.charset.StandardCharsets.UTF_8));
            if (!Store.addonsVersionKey().equals(o.optString("addons"))) return null;   // add-ons or servers changed
            Home h = new Home();
            Set<String> seen = new HashSet<>();
            for (JSONObject p : localContinue()) if (seen.add(p.optString("id"))) h.continueWatching.add(p);
            org.json.JSONArray cw = o.optJSONArray("cw");
            if (cw != null) for (int i = 0; i < cw.length(); i++) {
                JSONObject p = cw.optJSONObject(i);
                // Items from your servers' "On deck" (Flux's own ones are already fresh above).
                if (p != null && p.optString("id").contains(":") && seen.add(p.optString("id"))) h.continueWatching.add(p);
            }
            org.json.JSONArray rows = o.optJSONArray("rows");
            if (rows != null) for (int i = 0; i < rows.length(); i++) {
                org.json.JSONObject r = rows.getJSONObject(i);
                List<JSONObject> items = new ArrayList<>();
                org.json.JSONArray a = r.optJSONArray("items");
                if (a != null) for (int k = 0; k < a.length(); k++) items.add(a.getJSONObject(k));
                Addons.Row row = new Addons.Row(r.optString("title"), items);
                row.type = r.optString("type");
                row.note = r.optString("note");
                row.wide = r.optBoolean("wide");
                h.rows.add(row);
            }
            return h;
        } catch (Exception e) {
            return null;
        }
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
