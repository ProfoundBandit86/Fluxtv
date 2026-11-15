package com.fluxtv.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Home Assistant over its REST API: address (http://homeassistant.local:8123) + a long-lived access token
 * (Home Assistant → your profile → Security → Long-lived access tokens).
 */
public final class HomeAssistant {

    private HomeAssistant() { }

    public static String url() {
        String u = Store.get("ha_url").trim();
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        return u;
    }

    public static boolean configured() {
        return !url().isEmpty() && !Store.get("ha_token").isEmpty();
    }

    /** Saves and checks the connection. Returns Home Assistant's name/version line. */
    public static String connect(String url, String token) throws Exception {
        url = url.trim();
        if (!url.isEmpty() && !url.startsWith("http")) url = "http://" + url;
        if (!url.isEmpty() && !url.matches("https?://[^/]+:\\d+.*") && !url.startsWith("https")) url = url.replaceAll("/+$", "") + ":8123";
        String oldU = Store.get("ha_url"), oldT = Store.get("ha_token");
        Store.put("ha_url", url);
        if (token != null && !token.trim().isEmpty()) Store.put("ha_token", token.trim());
        try {
            JSONObject cfg = new JSONObject(call("GET", "/api/config", null));
            String name = cfg.optString("location_name", "Home Assistant");
            return name + " · Home Assistant " + cfg.optString("version");
        } catch (Exception e) {
            Store.put("ha_url", oldU);
            Store.put("ha_token", oldT);
            if (e instanceof Http.HttpException && e.getMessage() != null && e.getMessage().contains("401")) {
                throw new Exception("Home Assistant didn't accept the token");
            }
            throw e;
        }
    }

    public static void disconnect() {
        Store.put("ha_url", "");
        Store.put("ha_token", "");
    }

    private static String call(String method, String path, String body) throws Exception {
        Map<String, String> h = Http.map("Authorization", "Bearer " + Store.get("ha_token"));
        return Http.call(method, url() + path, h, body, body == null ? null : "application/json");
    }

    // ------------------------------------------------------------ devices

    private static long roomsAt;
    private static Map<String, String> rooms = new HashMap<>();

    /** entity → area name, from a template (the REST API has no areas). Refreshed every 10 minutes. */
    private static Map<String, String> rooms() {
        if (System.currentTimeMillis() - roomsAt < 10 * 60 * 1000 && !rooms.isEmpty()) return rooms;
        Map<String, String> out = new HashMap<>();
        try {
            String tpl = "{% set ids = states | map(attribute='entity_id') | list %}"
                    + "{{ dict(ids=ids, areas=(ids | map('area_name') | list)) | tojson }}";
            String r = call("POST", "/api/template", new JSONObject().put("template", tpl).toString());
            JSONObject o = new JSONObject(r.trim());
            JSONArray ids = o.optJSONArray("ids"), areas = o.optJSONArray("areas");
            if (ids != null && areas != null) for (int i = 0; i < ids.length() && i < areas.length(); i++) {
                String a = areas.isNull(i) ? "" : areas.optString(i);
                if (!a.isEmpty() && !"None".equals(a)) out.put(ids.optString(i), a);
            }
        } catch (Exception ignored) { }
        rooms = out;
        roomsAt = System.currentTimeMillis();
        return out;
    }

    public static List<SmartHome.Device> devices() throws Exception {
        JSONArray states = new JSONArray(call("GET", "/api/states", null));
        Map<String, String> areas = rooms();
        List<SmartHome.Device> out = new ArrayList<>();
        for (int i = 0; i < states.length(); i++) {
            JSONObject s = states.optJSONObject(i);
            if (s == null) continue;
            String id = s.optString("entity_id");
            String domain = id.contains(".") ? id.substring(0, id.indexOf('.')) : "";
            JSONObject a = s.optJSONObject("attributes");
            if (a == null) a = new JSONObject();
            if (a.optBoolean("hidden")) continue;
            SmartHome.Device d = new SmartHome.Device();
            d.id = id;
            d.name = a.optString("friendly_name", id);
            d.room = areas.containsKey(id) ? areas.get(id) : "";
            d.state = s.optString("state");
            d.available = !"unavailable".equals(d.state) && !"unknown".equals(d.state);
            switch (domain) {
                case "light": {
                    d.kind = SmartHome.LIGHT;
                    d.on = "on".equals(d.state);
                    JSONArray modes = a.optJSONArray("supported_color_modes");
                    boolean dim = a.has("brightness");
                    if (modes != null) for (int k = 0; k < modes.length(); k++) if (!"onoff".equals(modes.optString(k))) dim = true;
                    if (dim) d.level = d.on ? (int) Math.round(a.optDouble("brightness", 255) / 255.0 * 100) : 0;
                    break;
                }
                case "switch":
                case "input_boolean":
                    d.kind = SmartHome.SWITCH;
                    d.on = "on".equals(d.state);
                    break;
                case "fan":
                    d.kind = SmartHome.FAN;
                    d.on = "on".equals(d.state);
                    if (a.has("percentage") || a.has("percentage_step")) d.level = d.on ? a.optInt("percentage", 100) : 0;
                    break;
                case "climate":
                    d.kind = SmartHome.CLIMATE;
                    d.on = !"off".equals(d.state);
                    d.current = a.has("current_temperature") ? a.optDouble("current_temperature") : Double.NaN;
                    d.target = a.has("temperature") && !a.isNull("temperature") ? a.optDouble("temperature") : Double.NaN;
                    d.step = a.optDouble("target_temp_step", a.optString("temperature_unit", "").contains("F") ? 1 : 0.5);
                    d.unit = "°";
                    break;
                case "cover":
                    d.kind = SmartHome.COVER;
                    d.on = "open".equals(d.state) || "opening".equals(d.state);
                    if (a.has("current_position")) d.level = a.optInt("current_position");
                    break;
                case "lock":
                    d.kind = SmartHome.LOCK;
                    d.on = "locked".equals(d.state);
                    break;
                case "scene":
                case "script":
                    d.kind = SmartHome.SCENE;
                    break;
                default:
                    continue;   // sensors, automations, etc. aren't controls
            }
            out.add(d);
        }
        return out;
    }

    // ------------------------------------------------------------ commands

    private static void service(String domain, String service, JSONObject data) throws Exception {
        call("POST", "/api/services/" + domain + "/" + service, data.toString());
    }

    private static String domain(SmartHome.Device d) {
        return d.id.substring(0, d.id.indexOf('.'));
    }

    public static void setOn(SmartHome.Device d, boolean on) throws Exception {
        JSONObject data = new JSONObject().put("entity_id", d.id);
        String dom = domain(d);
        switch (d.kind) {
            case SmartHome.COVER: service("cover", on ? "open_cover" : "close_cover", data); return;
            case SmartHome.LOCK: service("lock", on ? "lock" : "unlock", data); return;
            case SmartHome.SCENE: service(dom, "turn_on", data); return;
            default: service(dom, on ? "turn_on" : "turn_off", data);
        }
    }

    public static void setLevel(SmartHome.Device d, int level) throws Exception {
        JSONObject data = new JSONObject().put("entity_id", d.id);
        switch (d.kind) {
            case SmartHome.LIGHT:
                if (level <= 0) service("light", "turn_off", data);
                else service("light", "turn_on", data.put("brightness_pct", level));
                return;
            case SmartHome.FAN:
                if (level <= 0) service("fan", "turn_off", data);
                else service("fan", "set_percentage", data.put("percentage", level));
                return;
            case SmartHome.COVER:
                service("cover", "set_cover_position", data.put("position", level));
                return;
            default:
                setOn(d, level > 0);
        }
    }

    public static void setTarget(SmartHome.Device d, double t) throws Exception {
        service("climate", "set_temperature", new JSONObject().put("entity_id", d.id).put("temperature", t));
    }
}
