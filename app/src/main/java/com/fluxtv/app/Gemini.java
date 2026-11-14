package com.fluxtv.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Gemini-powered search: turns "funny 90s heist movies" into real titles, then finds each
 * one (TMDB when a key is set, otherwise Cinemeta) so it opens like any other title.
 * The newest Flash model available to your key is picked automatically.
 */
public final class Gemini {

    private static final String REAL_API = "https://generativelanguage.googleapis.com/v1beta/";

    private static String api() {
        return BuildConfig.TEST_BUILD && !Store.get("test_gemini").isEmpty() ? Store.get("test_gemini") : REAL_API;
    }
    private static final Pattern MODEL = Pattern.compile("^models/gemini-(\\d+(?:\\.\\d+)?)-flash(-lite)?(-preview[\\w.-]*|-latest|-\\d{3})?$");

    private Gemini() { }

    public static boolean enabled() {
        return !Store.get("gemini_key").trim().isEmpty();
    }

    private static Map<String, String> headers() {
        return Http.map("x-goog-api-key", Store.get("gemini_key").trim(), "Accept", "application/json");
    }

    /** "Fast" (default) uses Flash-Lite; "Smart" uses full Flash. Both skip long thinking. */
    public static boolean fast() {
        return !"smart".equals(Store.get("gemini_speed"));
    }

    public static void setFast(boolean fast) {
        Store.put("gemini_speed", fast ? "fast" : "smart");
        Store.put("gemini_model_t", "0");   // pick the model again
    }

    /** Opens the connection to Google ahead of time (e.g. while you're speaking), so the search starts faster. */
    public static void warmUp() {
        if (!enabled()) return;
        App.IO.execute(() -> {
            try {
                Http.call("GET", api() + "models/" + model(), headers(), null, null);
            } catch (Exception ignored) { }
        });
    }

    private static final java.util.Map<String, List<JSONObject>> CACHE = new java.util.LinkedHashMap<String, List<JSONObject>>() {
        @Override protected boolean removeEldestEntry(java.util.Map.Entry<String, List<JSONObject>> e) { return size() > 30; }
    };

    /** Asks for quick answers: thinking off (2.5) or minimal (3+). */
    private static JSONObject thinking(String model) throws Exception {
        Matcher m = Pattern.compile("gemini-(\\d+)").matcher(model);
        int major = m.find() ? Integer.parseInt(m.group(1)) : 0;
        if (major >= 3) return new JSONObject().put("thinkingLevel", "minimal");
        if (major == 2 && model.contains("2.5")) return new JSONObject().put("thinkingBudget", 0);
        return null;
    }

    /** Newest generally-available Flash model for this key, re-checked once a day. */
    static synchronized String model() {
        String cached = Store.get("gemini_model");
        long when = 0;
        try { when = Long.parseLong(Store.get("gemini_model_t")); } catch (NumberFormatException ignored) { }
        if (!cached.isEmpty() && System.currentTimeMillis() - when < 24 * 3600 * 1000L) return cached;
        String best = cached.isEmpty() ? "gemini-flash-latest" : cached;
        double bestScore = -1;
        try {
            JSONArray models = new JSONObject(Http.call("GET", api() + "models?pageSize=200", headers(), null, null)).optJSONArray("models");
            if (models != null) for (int i = 0; i < models.length(); i++) {
                JSONObject m = models.optJSONObject(i);
                if (m == null) continue;
                JSONArray methods = m.optJSONArray("supportedGenerationMethods");
                if (methods == null || !methods.toString().contains("generateContent")) continue;
                Matcher x = MODEL.matcher(m.optString("name"));
                if (!x.find()) continue;
                boolean lite = x.group(2) != null;
                double score = Double.parseDouble(x.group(1)) * 10
                        + (lite == fast() ? 1 : 0)                                       // Flash-Lite when "Fast"
                        - (x.group(3) != null && x.group(3).startsWith("-preview") ? 5 : 0);   // prefer stable
                if (score > bestScore) {
                    bestScore = score;
                    best = m.optString("name").substring("models/".length());
                }
            }
        } catch (Exception ignored) { }
        Store.put("gemini_model", best);
        Store.put("gemini_model_t", String.valueOf(System.currentTimeMillis()));
        return best;
    }

    /** Titles Gemini suggests for a request, resolved to cards. */
    public static List<JSONObject> search(String request) throws Exception {
        if (!enabled()) throw new Exception("Add a Gemini API key in Settings");
        String cacheKey = request.trim().toLowerCase(Locale.ROOT);
        synchronized (CACHE) {
            if (CACHE.containsKey(cacheKey)) return new ArrayList<>(CACHE.get(cacheKey));
        }
        List<JSONObject> found = ask(request);
        synchronized (CACHE) {
            if (!found.isEmpty()) CACHE.put(cacheKey, found);
        }
        return found;
    }

    private static List<JSONObject> ask(String request) throws Exception {
        String prompt = "You are the search box of a TV app for movies and TV series. The viewer said: \""
                + request.replace("\"", "'") + "\". Reply with ONLY a JSON array (no other text) of up to 15 "
                + "movies or TV series that best match. If they named a specific title, put it first. Each item: "
                + "{\"title\": English title, \"year\": release year as a number, \"type\": \"movie\" or \"series\"}.";
        JSONObject body = new JSONObject();
        JSONArray contents = new JSONArray();
        JSONObject turn = new JSONObject();
        turn.put("role", "user");
        JSONArray parts = new JSONArray();
        parts.put(new JSONObject().put("text", prompt));
        turn.put("parts", parts);
        contents.put(turn);
        body.put("contents", contents);
        String model = model();
        JSONObject gen = new JSONObject().put("responseMimeType", "application/json").put("maxOutputTokens", 1200);
        JSONObject think = thinking(model);
        if (think != null) gen.put("thinkingConfig", think);
        body.put("generationConfig", gen);
        String reply;
        try {
            try {
                reply = Http.call("POST", api() + "models/" + model + ":generateContent", headers(), body.toString(), "application/json");
            } catch (Http.HttpException e) {
                if (e.code != 400 || !gen.has("thinkingConfig")) throw e;
                gen.remove("thinkingConfig");   // this model doesn't take that thinking setting
                reply = Http.call("POST", api() + "models/" + model + ":generateContent", headers(), body.toString(), "application/json");
            }
        } catch (Http.HttpException e) {
            if (e.code == 400 && body.has("generationConfig")) {   // older model without JSON mode
                body.remove("generationConfig");
                reply = Http.call("POST", api() + "models/" + model + ":generateContent", headers(), body.toString(), "application/json");
            } else if (e.code == 404) {
                Store.put("gemini_model_t", "0");   // model retired: pick again next time
                throw new Exception("Gemini model unavailable, try again");
            } else if (e.code == 400 || e.code == 401 || e.code == 403) {
                throw new Exception("Gemini didn't accept the API key. Check it in Settings.");
            } else if (e.code == 429) {
                throw new Exception("Gemini's free limit is used up for now. Try again later.");
            } else {
                throw e;
            }
        }
        StringBuilder text = new StringBuilder();
        JSONArray cands = new JSONObject(reply).optJSONArray("candidates");
        if (cands != null && cands.length() > 0) {
            JSONObject content = cands.optJSONObject(0).optJSONObject("content");
            JSONArray ps = content == null ? null : content.optJSONArray("parts");
            if (ps != null) for (int i = 0; i < ps.length(); i++) text.append(ps.optJSONObject(i).optString("text"));
        }
        String t = text.toString();
        int a = t.indexOf('['), b = t.lastIndexOf(']');
        if (a < 0 || b <= a) return new ArrayList<>();
        JSONArray picks = new JSONArray(t.substring(a, b + 1));

        List<Future<JSONObject>> jobs = new ArrayList<>();
        for (int i = 0; i < picks.length() && i < 15; i++) {
            JSONObject p = picks.optJSONObject(i);
            if (p == null) continue;
            String title = p.optString("title");
            int year = p.optInt("year");
            String type = p.optString("type").toLowerCase(Locale.ROOT).startsWith("s") || p.optString("type").contains("tv") ? "series" : "movie";
            jobs.add(Addons.POOL.submit(() -> Tmdb.enabled() ? Tmdb.find(title, year, type) : cinemeta(title, year, type)));
        }
        List<JSONObject> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Future<JSONObject> f : jobs) {
            try {
                JSONObject r = f.get(8, TimeUnit.SECONDS);
                if (r != null && seen.add(r.optString("id"))) out.add(r);
            } catch (Exception ignored) { }
        }
        return out;
    }

    private static JSONObject cinemeta(String title, int year, String type) {
        try {
            JSONArray metas = new JSONObject(Http.get("https://v3-cinemeta.strem.io/catalog/" + type + "/top/search="
                    + Http.enc(title) + ".json", null)).optJSONArray("metas");
            if (metas == null || metas.length() == 0) return null;
            for (int i = 0; i < metas.length(); i++) {
                JSONObject m = metas.optJSONObject(i);
                if (year > 0 && m.optString("releaseInfo").startsWith(String.valueOf(year))) return m;
            }
            return metas.optJSONObject(0);
        } catch (Exception e) {
            return null;
        }
    }
}
