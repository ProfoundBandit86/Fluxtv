package com.fluxtv.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/** Which apps may put items in Home's Continue watching row. */
public final class ContinueWatching {

    /** Flux's own items (add-ons, servers, Trakt). */
    public static final String FLUX = "flux";

    private ContinueWatching() { }

    public static Set<String> hidden() {
        Set<String> out = new HashSet<>();
        try {
            JSONArray a = new JSONArray(Store.get("cw_hidden").isEmpty() ? "[]" : Store.get("cw_hidden"));
            for (int i = 0; i < a.length(); i++) out.add(a.optString(i));
        } catch (Exception ignored) { }
        return out;
    }

    public static void setHidden(String key, boolean hide) {
        Set<String> h = hidden();
        if (hide) h.add(key); else h.remove(key);
        Store.put("cw_hidden", new JSONArray(new ArrayList<>(h)).toString());
        Store.bumpSources();
    }

    /** Remembers apps that have shown up, so they can be listed in the settings. */
    static synchronized void seen(String pkg, String label) {
        try {
            JSONObject s = new JSONObject(Store.get("cw_seen").isEmpty() ? "{}" : Store.get("cw_seen"));
            if (label.equals(s.optString(pkg))) return;
            s.put(pkg, label);
            Store.put("cw_seen", s.toString());
        } catch (Exception ignored) { }
    }

    /** [key, name] for Flux plus every app seen in Continue watching. */
    public static List<String[]> sources() {
        List<String[]> out = new ArrayList<>();
        out.add(new String[]{FLUX, "Flux (add-ons, media servers, Trakt)"});
        try {
            JSONObject s = new JSONObject(Store.get("cw_seen").isEmpty() ? "{}" : Store.get("cw_seen"));
            Iterator<String> it = s.keys();
            while (it.hasNext()) {
                String k = it.next();
                out.add(new String[]{k, s.optString(k)});
            }
        } catch (Exception ignored) { }
        return out;
    }
}
