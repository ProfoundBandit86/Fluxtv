package com.fluxtv.app;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Smart home devices for the panel. Devices come from a provider (Home Assistant today); the panel only uses this
 * class, so other providers (e.g. Google Home, if Google opens its SDK to TVs) can be added alongside.
 */
public final class SmartHome {

    public static final String LIGHT = "light", SWITCH = "switch", FAN = "fan", CLIMATE = "climate", COVER = "cover",
            LOCK = "lock", SCENE = "scene", OTHER = "other";

    public static final class Device {
        public String id = "", name = "", room = "", kind = OTHER, state = "";
        public boolean on, available = true;
        /** 0–100, or -1 when it can't be dimmed / set. (Lights: brightness · fans: speed · covers: position.) */
        public int level = -1;
        /** Thermostats: current and target temperature, unit. */
        public double current = Double.NaN, target = Double.NaN, step = 1;
        public String unit = "";

        public boolean dimmable() { return level >= 0; }

        public boolean toggleable() {
            return LIGHT.equals(kind) || SWITCH.equals(kind) || FAN.equals(kind) || COVER.equals(kind) || LOCK.equals(kind) || SCENE.equals(kind);
        }
    }

    private SmartHome() { }

    public static boolean configured() {
        return HomeAssistant.configured();
    }

    /** All controllable devices, sorted by room then name. Blocking. */
    public static List<Device> all() throws Exception {
        List<Device> out = new ArrayList<>();
        if (HomeAssistant.configured()) out.addAll(HomeAssistant.devices());
        Collections.sort(out, (a, b) -> {
            int r = roomKey(a).compareToIgnoreCase(roomKey(b));
            return r != 0 ? r : a.name.compareToIgnoreCase(b.name);
        });
        for (Device d : out) LAST.put(d.id, d);
        return out;
    }

    private static String roomKey(Device d) {
        return d.room.isEmpty() ? "￿" : d.room;   // devices without a room last
    }

    /** Last known state (for instant redraws while a command is on its way). */
    static final Map<String, Device> LAST = new ConcurrentHashMap<>();

    // ------------------------------------------------------------ which devices show on the panel

    /** Chosen device ids, or null when nothing was chosen yet (then lights, switches, fans and thermostats show). */
    public static Set<String> chosen() {
        String s = Store.get("sh_shown");
        if (s.isEmpty()) return null;
        Set<String> out = new LinkedHashSet<>();
        try {
            JSONArray a = new JSONArray(s);
            for (int i = 0; i < a.length(); i++) out.add(a.optString(i));
        } catch (Exception ignored) { }
        return out;
    }

    public static boolean shown(Device d) {
        Set<String> c = chosen();
        if (c == null) return LIGHT.equals(d.kind) || SWITCH.equals(d.kind) || FAN.equals(d.kind) || CLIMATE.equals(d.kind);
        return c.contains(d.id);
    }

    public static void setShown(List<Device> all, String id, boolean show) {
        Set<String> c = chosen();
        if (c == null) {
            c = new LinkedHashSet<>();
            for (Device d : all) if (shown(d)) c.add(d.id);
        }
        if (show) c.add(id); else c.remove(id);
        Store.put("sh_shown", new JSONArray(new ArrayList<>(c)).toString());
    }

    public static void setAllShown(List<Device> all, boolean show) {
        List<String> ids = new ArrayList<>();
        if (show) for (Device d : all) ids.add(d.id);
        Store.put("sh_shown", new JSONArray(ids).toString());
    }

    // ------------------------------------------------------------ commands (blocking)

    public static void setOn(Device d, boolean on) throws Exception {
        HomeAssistant.setOn(d, on);
        d.on = on;
        d.state = on ? "on" : "off";
    }

    public static void setLevel(Device d, int level) throws Exception {
        level = Math.max(0, Math.min(100, level));
        HomeAssistant.setLevel(d, level);
        d.level = level;
        if (!COVER.equals(d.kind)) d.on = level > 0;
    }

    public static void setTarget(Device d, double t) throws Exception {
        HomeAssistant.setTarget(d, t);
        d.target = t;
    }

    public static String describe(Device d) {
        if (!d.available) return "Unavailable";
        switch (d.kind) {
            case CLIMATE:
                String cur = Double.isNaN(d.current) ? "" : fmt(d.current) + d.unit + " now";
                String tgt = Double.isNaN(d.target) ? "" : "set to " + fmt(d.target) + d.unit;
                return (d.state.isEmpty() ? "" : cap(d.state) + " · ") + cur + (cur.isEmpty() || tgt.isEmpty() ? "" : " · ") + tgt;
            case COVER:
                return cap(d.state) + (d.level >= 0 ? " · " + d.level + "%" : "");
            case LOCK:
                return cap(d.state);
            case SCENE:
                return "Press OK to run";
            default:
                return d.on ? (d.level >= 0 ? "On · " + d.level + "%" : "On") : "Off";
        }
    }

    static String fmt(double v) {
        return v == Math.floor(v) ? String.valueOf((long) v) : String.format(java.util.Locale.US, "%.1f", v);
    }

    private static String cap(String s) {
        if (s == null || s.isEmpty()) return "";
        s = s.replace('_', ' ');
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
