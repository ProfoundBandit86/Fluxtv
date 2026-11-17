package com.fluxtv.app;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

/**
 * Intro / recap / credits times for an episode or movie, from the community databases
 * IntroDB (introdb.app, by IMDb id) and TheIntroDB (theintrodb.org, by TMDB id).
 * Results are cached per video id.
 */
public final class Segments {
    private Segments() { }

    public static final class Seg {
        public final String kind;   // "intro", "recap", "credits", "preview"
        public final long start, end; // ms; end < 0 = until the end of the video

        Seg(String kind, long start, long end) {
            this.kind = kind;
            this.start = start;
            this.end = end;
        }

        public String button() {
            switch (kind) {
                case "recap": return "Skip recap";
                case "credits": return "Skip credits";
                case "preview": return "Skip preview";
                default: return "Skip intro";
            }
        }
    }

    /** videoId: "tt123" for a movie, "tt123:1:2" for an episode. Never throws. */
    public static List<Seg> lookup(String type, String videoId) {
        List<Seg> out = new ArrayList<>();
        if (videoId == null || !videoId.startsWith("tt")) return out;
        String cacheKey = "segs:" + videoId;
        String cached = Store.get(cacheKey);
        if (!cached.isEmpty()) {
            try {
                JSONObject c = new JSONObject(cached);
                if (System.currentTimeMillis() - c.optLong("t") < (c.optJSONArray("s").length() > 0 ? 30L : 3L) * 86_400_000L) {
                    JSONArray a = c.optJSONArray("s");
                    for (int i = 0; i < a.length(); i++) {
                        JSONObject o = a.optJSONObject(i);
                        out.add(new Seg(o.optString("k"), o.optLong("s"), o.optLong("e")));
                    }
                    return out;
                }
            } catch (Exception ignored) { }
        }
        String[] p = videoId.split(":");
        String imdb = p[0];
        String season = p.length >= 3 ? p[1] : null, episode = p.length >= 3 ? p[2] : null;
        try {
            String base = BuildConfig.TEST_BUILD && !Store.get("test_introdb").isEmpty() ? Store.get("test_introdb") : "https://api.introdb.app";
            String url = base + "/segments?imdb_id=" + enc(imdb)
                    + (season != null ? "&season=" + season + "&episode=" + episode : "");
            collect(new JSONTokener(Http.get(url, null)).nextValue(), null, out);
        } catch (Exception ignored) { }
        if (out.isEmpty() && Tmdb.enabled()) {
            try {
                boolean tv = season != null;
                int n = Tmdb.tmdbNumber(imdb, tv ? "series" : "movie");
                String url = "https://api.theintrodb.org/v1/media?tmdb_id=" + n
                        + (tv ? "&season=" + season + "&episode=" + episode : "");
                collect(new JSONTokener(Http.get(url, null)).nextValue(), null, out);
            } catch (Exception ignored) { }
        }
        dedupe(out);
        try {
            JSONArray a = new JSONArray();
            for (Seg s : out) a.put(new JSONObject().put("k", s.kind).put("s", s.start).put("e", s.end));
            Store.put(cacheKey, new JSONObject().put("t", System.currentTimeMillis()).put("s", a).toString());
        } catch (Exception ignored) { }
        return out;
    }

    // ---------------------------------------------------------------- parsing (shape-tolerant)

    /** Walks any JSON shape and picks up objects that carry a start/end pair. */
    private static void collect(Object node, String keyKind, List<Seg> out) {
        if (node instanceof JSONArray) {
            JSONArray a = (JSONArray) node;
            for (int i = 0; i < a.length(); i++) collect(a.opt(i), keyKind, out);
        } else if (node instanceof JSONObject) {
            JSONObject o = (JSONObject) node;
            String kind = kind(firstString(o, "segment_type", "type", "kind", "category", "segment"));
            if (kind == null) kind = keyKind;
            long[] se = startEnd(o);
            if (kind != null && se != null) {
                out.add(new Seg(kind, se[0], se[1]));
                return;
            }
            JSONArray names = o.names();
            if (names == null) return;
            for (int i = 0; i < names.length(); i++) {
                String k = names.optString(i);
                String kk = kind(k);
                collect(o.opt(k), kk != null ? kk : keyKind, out);
            }
        }
    }

    private static String kind(String s) {
        if (s == null) return null;
        s = s.toLowerCase();
        if (s.contains("intro") || s.equals("opening") || s.equals("op")) return "intro";
        if (s.contains("recap")) return "recap";
        if (s.contains("credit") || s.contains("outro") || s.equals("ending") || s.equals("ed")) return "credits";
        if (s.contains("preview")) return "preview";
        return null;
    }

    private static long[] startEnd(JSONObject o) {
        Long s = null, e = null;
        String[][] starts = {{"start_ms", "ms"}, {"startMs", "ms"}, {"start_sec", "s"}, {"start", "s"}, {"startTime", "s"}, {"start_time", "s"}};
        String[][] ends = {{"end_ms", "ms"}, {"endMs", "ms"}, {"end_sec", "s"}, {"end", "s"}, {"endTime", "s"}, {"end_time", "s"}};
        boolean hasEnd = false;
        for (String[] k : starts) if (o.has(k[0])) { s = ms(o.opt(k[0]), k[1]); break; }
        for (String[] k : ends) if (o.has(k[0])) { hasEnd = true; e = o.isNull(k[0]) ? Long.valueOf(-1) : ms(o.opt(k[0]), k[1]); break; }
        if (s == null || !hasEnd || e == null) return null;
        if (e >= 0 && e <= s) return null;
        return new long[]{s, e};
    }

    private static Long ms(Object v, String unit) {
        if (v == null || v == JSONObject.NULL) return null;
        if (v instanceof Number) {
            double d = ((Number) v).doubleValue();
            return (long) ("ms".equals(unit) ? d : d * 1000);
        }
        String t = v.toString().trim();
        try {
            if (t.contains(":")) {
                String[] parts = t.split(":");
                double secs = 0;
                for (String part : parts) secs = secs * 60 + Double.parseDouble(part);
                return (long) (secs * 1000);
            }
            double d = Double.parseDouble(t);
            return (long) ("ms".equals(unit) ? d : d * 1000);
        } catch (Exception ex) {
            return null;
        }
    }

    private static String firstString(JSONObject o, String... keys) {
        for (String k : keys) if (o.opt(k) instanceof String) return o.optString(k);
        return null;
    }

    private static void dedupe(List<Seg> out) {
        List<Seg> keep = new ArrayList<>();
        for (Seg s : out) {
            boolean dup = false;
            for (Seg k : keep) if (k.kind.equals(s.kind)) { dup = true; break; }
            if (!dup) keep.add(s);
        }
        out.clear();
        out.addAll(keep);
    }

    private static String enc(String s) {
        try { return URLEncoder.encode(s, "UTF-8"); } catch (Exception e) { return s; }
    }
}
