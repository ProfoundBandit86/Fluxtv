package com.fluxtv.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stalker / Ministra "MAC portal" providers: the TV signs in as a MAG set-top box with the MAC address the
 * provider registered. Source.url = portal address, Source.user = MAC, Source.pass = the API endpoint found at sign-in.
 */
final class Stalker {
    private Stalker() { }

    private static final String UA = "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) MAG200 stbapp ver: 2 rev: 250 Safari/533.3";
    private static final Map<String, String> tokens = new ConcurrentHashMap<>();
    private static final Map<String, Long> tokenTime = new ConcurrentHashMap<>();

    /** "aa-bb-cc…" or "aabbcc…" → "AA:BB:CC:DD:EE:FF". */
    static String normalizeMac(String raw) {
        String hex = raw == null ? "" : raw.replaceAll("[^0-9A-Fa-f]", "").toUpperCase(Locale.ROOT);
        if (hex.length() != 12) return "";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 12; i += 2) b.append(i == 0 ? "" : ":").append(hex, i, i + 2);
        return b.toString();
    }

    /** "host:port/c/" → "http://host:port" (plus any path before /c). */
    static String root(String raw) {
        String u = raw == null ? "" : raw.trim();
        if (!u.startsWith("http://") && !u.startsWith("https://")) u = "http://" + u;
        int q = u.indexOf('?');
        if (q >= 0) u = u.substring(0, q);
        u = u.replaceAll("/+$", "");
        for (String tail : new String[]{"/c/index.html", "/c", "/portal.php", "/server/load.php"}) {
            if (u.endsWith(tail)) u = u.substring(0, u.length() - tail.length());
        }
        return u.replaceAll("/+$", "");
    }

    static Iptv.Source login(String portal, String macRaw) throws Exception {
        String mac = normalizeMac(macRaw);
        if (mac.isEmpty()) throw new Exception("Enter the MAC address, e.g. 00:1A:79:12:34:56");
        String r = root(portal);
        if (r.length() < 10) throw new Exception("Enter the portal address");
        List<String> endpoints = new ArrayList<>();
        endpoints.add(r + "/portal.php");
        endpoints.add(r + "/server/load.php");
        if (!r.endsWith("/stalker_portal")) {
            endpoints.add(r + "/stalker_portal/server/load.php");
            endpoints.add(r + "/stalker_portal/portal.php");
        }
        Iptv.Source s = new Iptv.Source();
        s.kind = Iptv.STALKER;
        s.url = r;
        s.user = mac;
        Exception last = null;
        for (String ep : endpoints) {
            s.pass = ep;
            s.id = Integer.toHexString((r + "|" + mac).hashCode());
            tokens.remove(s.id);
            try {
                handshake(s);
                JSONObject prof = profile(s);
                if (prof != null && prof.optInt("status", 0) == 1 && !prof.optString("msg").isEmpty())
                    throw new Exception(prof.optString("msg"));   // portal says why (expired, not registered…)
                String host;
                try { host = new URI(r).getHost(); } catch (Exception e) { host = r; }
                s.name = host + " · " + mac.substring(9);
                return s;
            } catch (Exception e) {
                last = e;
            }
        }
        throw new Exception(last == null || last.getMessage() == null ? "That portal didn't answer like a MAC portal"
                : "Portal: " + last.getMessage());
    }

    // ------------------------------------------------------------ requests

    private static Map<String, String> headers(Iptv.Source s, boolean auth) {
        String tz = TimeZone.getDefault().getID();
        Map<String, String> h = new HashMap<>();
        h.put("User-Agent", UA);
        h.put("X-User-Agent", "Model: MAG250; Link: WiFi");
        h.put("Cookie", "mac=" + Http.enc(s.user) + "; stb_lang=en; timezone=" + Http.enc(tz));
        h.put("Referer", s.url + "/c/");
        h.put("Accept", "*/*");
        if (auth && tokens.get(s.id) != null) h.put("Authorization", "Bearer " + tokens.get(s.id));
        return h;
    }

    private static Object call(Iptv.Source s, String query) throws Exception {
        if (tokens.get(s.id) == null || System.currentTimeMillis() - tokenTime.getOrDefault(s.id, 0L) > 3 * 3600_000L) {
            handshake(s);
            profile(s);
        }
        for (int attempt = 0; attempt < 2; attempt++) {
            String body;
            try {
                body = Http.call("GET", s.pass + "?" + query + "&JsHttpRequest=1-xml", headers(s, true), null, null).trim();
            } catch (Http.HttpException e) {
                if (attempt == 0 && (e.code == 401 || e.code == 403)) { handshake(s); profile(s); continue; }
                throw e;
            }
            if (body.contains("Authorization failed") && attempt == 0) { handshake(s); profile(s); continue; }
            if (!body.startsWith("{")) throw new Exception("The portal sent something unexpected");
            return new JSONObject(body).opt("js");
        }
        throw new Exception("The portal didn't accept this MAC address");
    }

    private static void handshake(Iptv.Source s) throws Exception {
        String body = Http.call("GET", s.pass + "?type=stb&action=handshake&token=&prehash=0&JsHttpRequest=1-xml",
                headers(s, false), null, null).trim();
        if (!body.startsWith("{")) throw new Exception("not a MAC portal");
        JSONObject js = new JSONObject(body).optJSONObject("js");
        String token = js == null ? "" : js.optString("token");
        if (token.isEmpty()) throw new Exception("The portal didn't accept this MAC address");
        tokens.put(s.id, token);
        tokenTime.put(s.id, System.currentTimeMillis());
    }

    private static JSONObject profile(Iptv.Source s) {
        try {
            String serial = Integer.toHexString(s.user.hashCode()).toUpperCase(Locale.ROOT);
            String body = Http.call("GET", s.pass + "?type=stb&action=get_profile&hd=1&ver=ImageDescription%3A%200.2.18-r23-250&num_banks=2"
                    + "&sn=" + serial + "&stb_type=MAG250&image_version=218&video_out=hdmi&auth_second_step=1&hw_version=1.7-BD-00"
                    + "&not_valid_token=0&JsHttpRequest=1-xml", headers(s, true), null, null).trim();
            return body.startsWith("{") ? new JSONObject(body).optJSONObject("js") : null;
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------ channels

    static List<Iptv.Category> genres(Iptv.Source s) throws Exception {
        List<Iptv.Category> out = new ArrayList<>();
        Object js = call(s, "type=itv&action=get_genres");
        if (js instanceof JSONArray) {
            JSONArray a = (JSONArray) js;
            for (int i = 0; i < a.length(); i++) {
                JSONObject g = a.optJSONObject(i);
                if (g == null || "*".equals(g.optString("id"))) continue;
                out.add(new Iptv.Category(g.optString("id"), g.optString("title")));
            }
        }
        return out;
    }

    static List<Iptv.Channel> channels(Iptv.Source s) throws Exception {
        List<Iptv.Channel> out = new ArrayList<>();
        Object js = call(s, "type=itv&action=get_all_channels");
        JSONArray a = js instanceof JSONObject ? ((JSONObject) js).optJSONArray("data") : js instanceof JSONArray ? (JSONArray) js : null;
        if (a == null) return out;
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            Iptv.Channel c = new Iptv.Channel();
            c.id = o.optString("id");
            c.name = o.optString("name");
            c.logo = logo(s, o.optString("logo"));
            c.group = o.optString("tv_genre_id");
            c.epgId = o.optString("xmltv_id");
            c.num = o.optString("number");
            c.url = o.optString("cmd");
            if (o.optInt("tv_archive", 0) == 1 || o.optInt("enable_tv_archive", 0) == 1) {
                int hours = o.optInt("tv_archive_duration", 0);
                c.archiveDays = Math.max(1, hours > 0 ? (hours + 23) / 24 : 3);
            }
            out.add(c);
        }
        return out;
    }

    private static String logo(Iptv.Source s, String l) {
        if (l == null || l.isEmpty()) return "";
        if (l.startsWith("http")) return l;
        String r = s.url.replaceAll("/stalker_portal$", "");
        return r + (l.startsWith("/") ? "" : "/stalker_portal/misc/logos/320/") + l;
    }

    /** A playable address for a channel (the portal hands out short-lived links). Blocking. */
    static String link(Iptv.Source s, Iptv.Channel c) throws Exception {
        Object js = call(s, "type=itv&action=create_link&cmd=" + Http.enc(c.url) + "&series=&forced_storage=undefined&disable_ad=0&download=0");
        String cmd = js instanceof JSONObject ? ((JSONObject) js).optString("cmd") : "";
        String url = strip(cmd);
        if (url.isEmpty()) url = strip(c.url);
        if (url.isEmpty() || url.contains("localhost")) throw new Exception("The portal didn't give a stream for " + c.name);
        return url;
    }

    /** Catch-up link for a past programme (its guide id). Blocking. */
    static String archiveLink(Iptv.Source s, String programmeId) throws Exception {
        Object js = call(s, "type=tv_archive&action=create_link&cmd=" + Http.enc("auto /media/" + programmeId + ".mpg")
                + "&series=&forced_storage=&disable_ad=0&download=0");
        String url = strip(js instanceof JSONObject ? ((JSONObject) js).optString("cmd") : "");
        if (url.isEmpty()) throw new Exception("This programme isn't available to replay");
        return url;
    }

    private static String strip(String cmd) {
        if (cmd == null) return "";
        String u = cmd.trim();
        int sp = u.indexOf(' ');
        if (sp > 0 && !u.startsWith("http")) u = u.substring(sp + 1).trim();   // "ffmpeg http://…", "auto http://…"
        return u.startsWith("http") ? u : "";
    }

    // ------------------------------------------------------------ guide

    /** The portal's own guide for the next hours, keyed "#<channel id>". */
    static Map<String, List<Iptv.Program>> guide(Iptv.Source s, int hours) throws Exception {
        Map<String, List<Iptv.Program>> out = new HashMap<>();
        Object js = call(s, "type=itv&action=get_epg_info&period=" + hours);
        JSONObject data = js instanceof JSONObject ? ((JSONObject) js).optJSONObject("data") : null;
        if (data == null) return out;
        JSONArray names = data.names();
        if (names == null) return out;
        for (int i = 0; i < names.length(); i++) {
            String ch = names.optString(i);
            JSONArray a = data.optJSONArray(ch);
            if (a == null) continue;
            List<Iptv.Program> list = new ArrayList<>();
            for (int k = 0; k < a.length(); k++) {
                Iptv.Program p = program(a.optJSONObject(k));
                if (p != null) list.add(p);
            }
            if (!list.isEmpty()) out.put("#" + ch, list);
        }
        return out;
    }

    /** Past and upcoming programmes of one channel (for catch-up), newest week. */
    static List<Iptv.Program> week(Iptv.Source s, Iptv.Channel c) throws Exception {
        List<Iptv.Program> out = new ArrayList<>();
        Object js = call(s, "type=itv&action=get_short_epg&ch_id=" + Http.enc(c.id) + "&size=200");
        if (js instanceof JSONArray) {
            JSONArray a = (JSONArray) js;
            for (int i = 0; i < a.length(); i++) {
                Iptv.Program p = program(a.optJSONObject(i));
                if (p != null) out.add(p);
            }
        }
        return out;
    }

    private static Iptv.Program program(JSONObject o) {
        if (o == null) return null;
        Iptv.Program p = new Iptv.Program();
        p.id = o.optString("id");
        p.title = o.optString("name");
        p.desc = o.optString("descr");
        p.start = o.optLong("start_timestamp") * 1000L;
        p.end = o.optLong("stop_timestamp") * 1000L;
        return p.start > 0 && p.end > p.start ? p : null;
    }
}
