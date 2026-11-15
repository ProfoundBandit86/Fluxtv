package com.fluxtv.app;

import android.app.SearchManager;
import android.content.Context;
import android.content.Intent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Google Home lights and switches without any sign-in: Flux sends the same words you'd say to the TV's own
 * Google Assistant ("turn off Kitchen light", "set Living room lamp to 40 percent").
 * You add the devices by the names they have in the Google Home app.
 */
public final class GoogleCommands {

    public static final String[] KINDS = {"dimmable", "light", "fan", "switch"};
    public static final String[] KIND_NAMES = {"Dimmable light", "Light (on/off)", "Ceiling fan", "Switch / plug"};

    private GoogleCommands() { }

    // ------------------------------------------------------------ your devices

    public static List<JSONObject> saved() {
        List<JSONObject> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(Store.get("gc_devices").isEmpty() ? "[]" : Store.get("gc_devices"));
            for (int i = 0; i < a.length(); i++) out.add(a.getJSONObject(i));
        } catch (Exception ignored) { }
        return out;
    }

    public static boolean any() {
        return !saved().isEmpty();
    }

    public static void add(String name, String kind) throws Exception {
        name = name.trim();
        if (name.isEmpty()) throw new Exception("Type the device's name as it is in the Google Home app");
        List<JSONObject> list = saved();
        for (JSONObject o : list) if (o.optString("name").equalsIgnoreCase(name)) throw new Exception(name + " is already added");
        list.add(new JSONObject().put("name", name).put("kind", kind).put("on", false).put("level", 100));
        Store.put("gc_devices", new JSONArray(list).toString());
        // Show it on the panel straight away, even if devices were hand-picked before.
        java.util.Set<String> chosen = SmartHome.chosen();
        if (chosen != null) {
            chosen.add("gc:" + name);
            Store.put("sh_shown", new JSONArray(new ArrayList<>(chosen)).toString());
        }
    }

    public static void remove(String name) {
        List<JSONObject> list = saved();
        for (int i = list.size() - 1; i >= 0; i--) if (list.get(i).optString("name").equals(name)) list.remove(i);
        Store.put("gc_devices", new JSONArray(list).toString());
    }

    private static void remember(String name, boolean on, int level) {
        List<JSONObject> list = saved();
        try {
            for (JSONObject o : list) if (o.optString("name").equals(name)) { o.put("on", on); if (level >= 0) o.put("level", level); }
        } catch (Exception ignored) { }
        Store.put("gc_devices", new JSONArray(list).toString());
    }

    /** For the panel. State is what Flux last asked for (Google doesn't report it back this way). */
    public static List<SmartHome.Device> devices() {
        List<SmartHome.Device> out = new ArrayList<>();
        for (JSONObject o : saved()) {
            SmartHome.Device d = new SmartHome.Device();
            d.id = "gc:" + o.optString("name");
            d.name = o.optString("name");
            d.room = "Google Home";
            String k = o.optString("kind");
            d.kind = "fan".equals(k) ? SmartHome.FAN : "switch".equals(k) ? SmartHome.SWITCH : SmartHome.LIGHT;
            d.on = o.optBoolean("on");
            d.state = d.on ? "on" : "off";
            if ("dimmable".equals(k) || "fan".equals(k)) d.level = d.on ? o.optInt("level", 100) : 0;
            out.add(d);
        }
        return out;
    }

    // ------------------------------------------------------------ commands

    public static void setOn(SmartHome.Device d, boolean on) throws Exception {
        send((on ? "turn on " : "turn off ") + d.name);
        remember(d.name, on, -1);
    }

    public static void setLevel(SmartHome.Device d, int level) throws Exception {
        if (level <= 0) { setOn(d, false); return; }
        send("set " + d.name + " to " + level + " percent");
        remember(d.name, true, level);
    }

    /**
     * Hands the words to the TV's Google Assistant. Method "search" (default) uses Google TV's assistant/search
     * app; "web" uses the general web-search intent (some TVs route that to the Assistant instead).
     */
    public static void send(String words) throws Exception {
        Context c = App.ctx;
        String how = Store.get("gc_method");
        Intent i;
        if ("web".equals(how)) {
            i = new Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, words);
        } else {
            i = new Intent("android.search.action.GLOBAL_SEARCH").putExtra(SearchManager.QUERY, words)
                    .putExtra("query", words);
            if (installed(c, "com.google.android.katniss")) i.setPackage("com.google.android.katniss");
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            c.startActivity(i);
        } catch (Exception e) {
            throw new Exception("This TV has no Google Assistant that accepts typed commands");
        }
    }

    private static boolean installed(Context c, String pkg) {
        try { c.getPackageManager().getPackageInfo(pkg, 0); return true; } catch (Exception e) { return false; }
    }
}
