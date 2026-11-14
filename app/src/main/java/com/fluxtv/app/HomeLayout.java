package com.fluxtv.app;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Which rows show on Home, and in what order (Customize Home). */
public final class HomeLayout {

    /** Every row the last Home load offered (for the editor). */
    static final List<Addons.Row> available = new ArrayList<>();

    private HomeLayout() { }

    public static String key(Addons.Row r) {
        return r.title + "|" + r.note;
    }

    static List<String> order() {
        return list("home_order");
    }

    static Set<String> hidden() {
        return new HashSet<>(list("home_hidden"));
    }

    private static List<String> list(String k) {
        List<String> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(Store.get(k).isEmpty() ? "[]" : Store.get(k));
            for (int i = 0; i < a.length(); i++) out.add(a.optString(i));
        } catch (Exception ignored) { }
        return out;
    }

    static void save(List<String> order, Set<String> hidden) {
        Store.put("home_order", new JSONArray(order).toString());
        Store.put("home_hidden", new JSONArray(new ArrayList<>(hidden)).toString());
        Store.bumpSources();
    }

    /** All rows in your order (new rows go after, in their default order). */
    static List<Addons.Row> ordered(List<Addons.Row> rows) {
        List<Addons.Row> out = new ArrayList<>();
        List<String> order = order();
        for (String k : order) for (Addons.Row r : rows) if (key(r).equals(k) && !out.contains(r)) out.add(r);
        for (Addons.Row r : rows) if (!out.contains(r)) out.add(r);
        return out;
    }

    /** The rows Home should show. */
    static List<Addons.Row> visible(List<Addons.Row> rows) {
        Set<String> hidden = hidden();
        List<Addons.Row> out = new ArrayList<>();
        for (Addons.Row r : ordered(rows)) if (!hidden.contains(key(r))) out.add(r);
        return out;
    }
}
