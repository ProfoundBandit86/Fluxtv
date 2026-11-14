package com.fluxtv.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** SABnzbd: queue, history, pause/resume and delete. */
public final class Sab {

    private Sab() { }

    public static String url() {
        return Store.get("sab_url");
    }

    public static boolean configured() {
        return !url().isEmpty() && !Store.get("sab_key").isEmpty();
    }

    public static String save(String url, String apiKey) throws Exception {
        if (url != null && !url.trim().isEmpty()) Store.put("sab_url", Arr.normalize(url));
        if (apiKey != null && !apiKey.trim().isEmpty()) Store.put("sab_key", apiKey.trim());
        JSONObject v = new JSONObject(call("version", ""));
        return "SABnzbd " + v.optString("version") + " connected";
    }

    public static void remove() {
        Store.put("sab_url", "");
        Store.put("sab_key", "");
    }

    private static String call(String mode, String extra) throws Exception {
        if (!configured()) throw new Exception("SABnzbd isn't set up. Add it in Settings → Downloads.");
        String r = Http.get(url() + "/api?mode=" + mode + "&output=json&apikey=" + Http.enc(Store.get("sab_key")) + extra, null);
        if (r.contains("\"error\"")) {
            JSONObject j = new JSONObject(r);
            if (!j.optString("error").isEmpty()) throw new Exception("SABnzbd: " + j.optString("error"));
        }
        return r;
    }

    public static class Queue {
        public boolean paused;
        public String speed = "", left = "", timeLeft = "";
        public final List<JSONObject> items = new ArrayList<>();
    }

    public static Queue queue() throws Exception {
        JSONObject q = new JSONObject(call("queue", "&limit=50")).optJSONObject("queue");
        Queue out = new Queue();
        if (q == null) return out;
        out.paused = q.optBoolean("paused");
        out.speed = q.optString("speed").trim();
        out.left = q.optString("sizeleft");
        out.timeLeft = q.optString("timeleft");
        JSONArray slots = q.optJSONArray("slots");
        if (slots != null) for (int i = 0; i < slots.length(); i++) {
            JSONObject s = slots.optJSONObject(i);
            JSONObject e = new JSONObject();
            e.put("id", s.optString("nzo_id"));
            e.put("title", s.optString("filename"));
            e.put("progress", s.optDouble("percentage", 0) / 100.0);
            e.put("paused", "Paused".equalsIgnoreCase(s.optString("status")));
            e.put("sub", s.optString("status") + "  ·  " + s.optString("sizeleft") + " of " + s.optString("size")
                    + ("0:00:00".equals(s.optString("timeleft")) ? "" : "  ·  " + s.optString("timeleft") + " left"));
            out.items.add(e);
        }
        return out;
    }

    /** Recently finished or failed downloads. */
    public static List<JSONObject> history() throws Exception {
        JSONObject h = new JSONObject(call("history", "&limit=15")).optJSONObject("history");
        List<JSONObject> out = new ArrayList<>();
        JSONArray slots = h == null ? null : h.optJSONArray("slots");
        if (slots != null) for (int i = 0; i < slots.length(); i++) {
            JSONObject s = slots.optJSONObject(i);
            JSONObject e = new JSONObject();
            e.put("title", s.optString("name"));
            String status = s.optString("status");
            e.put("failed", "Failed".equalsIgnoreCase(status));
            e.put("sub", status + (s.optString("size").isEmpty() ? "" : "  ·  " + s.optString("size"))
                    + (s.optString("fail_message").isEmpty() ? "" : "  ·  " + s.optString("fail_message")));
            out.add(e);
        }
        return out;
    }

    public static void pauseAll(boolean pause) throws Exception {
        call(pause ? "pause" : "resume", "");
    }

    public static void pauseItem(String id, boolean pause) throws Exception {
        call("queue", "&name=" + (pause ? "pause" : "resume") + "&value=" + Http.enc(id));
    }

    public static void deleteItem(String id) throws Exception {
        call("queue", "&name=delete&del_files=1&value=" + Http.enc(id));
    }

    static String gb(double bytes) {
        if (bytes >= 1e9) return String.format(Locale.US, "%.1f GB", bytes / 1e9);
        return String.format(Locale.US, "%.0f MB", bytes / 1e6);
    }
}
