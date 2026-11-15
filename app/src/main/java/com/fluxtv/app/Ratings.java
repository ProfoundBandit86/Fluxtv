package com.fluxtv.app;

import android.view.View;
import android.view.ViewGroup;

import org.json.JSONObject;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Age-restricted profiles. A profile can have a highest allowed rating; titles above it, and titles
 * whose rating can't be found, are hidden everywhere (Home, Movies, TV Shows, Search, genres…).
 *
 * Ratings come from TMDB (US certificates: G / PG / PG-13 / R and TV-Y … TV-MA) and are remembered.
 */
public final class Ratings {

    /** Choices for a profile, lowest first. Index = level; "" = no limit. */
    public static final String[] LIMITS = {"G", "PG", "PG-13", "R"};
    public static final String[] LIMIT_NAMES = {"G · TV-Y, TV-G", "PG · TV-PG", "PG-13 · TV-14", "R · TV-MA"};

    private static final int UNKNOWN = -1, ADULT = 99;
    private static final Map<String, Integer> MEM = new ConcurrentHashMap<>();
    private static final Map<String, Boolean> PENDING = new ConcurrentHashMap<>();
    private static final ExecutorService POOL = Executors.newFixedThreadPool(6);

    private Ratings() { }

    // ------------------------------------------------------------ the profile's limit

    /** "" (no limit) or one of LIMITS. */
    public static String limit(String profile) {
        return Store.profileValue("max_rating", profile);
    }

    public static String limit() {
        return limit(Store.profile());
    }

    public static boolean restricted() {
        return !limit().isEmpty();
    }

    public static boolean restricted(String profile) {
        return !limit(profile).isEmpty();
    }

    public static void setLimit(String profile, String limit) {
        Store.putProfileValue("max_rating", profile, limit == null || limit.isEmpty() ? null : limit);
        Store.touchProfileSettings(profile);
    }

    /** A 4-digit PIN that guards leaving a restricted profile and opening Settings (optional). */
    public static String pin(String profile) {
        return Store.profileValue("pin", profile);
    }

    public static void setPin(String profile, String pin) {
        Store.putProfileValue("pin", profile, pin == null || pin.isEmpty() ? null : pin);
        Store.touchProfileSettings(profile);
    }

    // ------------------------------------------------------------ PIN prompts

    private static long unlockedUntil;
    private static String unlockedProfile = "";

    private static boolean locked() {
        String prof = Store.profile();
        if (!restricted(prof) || pin(prof).isEmpty()) return false;
        return !(prof.equals(unlockedProfile) && System.currentTimeMillis() < unlockedUntil);
    }

    /** Asks for the PIN if needed; runs ok when allowed, cancelled otherwise. */
    public static void askPin(android.app.Activity a, Runnable ok, Runnable cancelled) {
        if (!locked()) { ok.run(); return; }
        String prof = Store.profile();
        android.widget.EditText in = new android.widget.EditText(a);
        in.setSingleLine(true);
        in.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        in.setHint("PIN");
        new android.app.AlertDialog.Builder(a)
                .setTitle("Enter the PIN for " + prof)
                .setView(in)
                .setPositiveButton("OK", (d, w) -> {
                    if (in.getText().toString().trim().equals(pin(prof))) {
                        unlockedProfile = prof;
                        unlockedUntil = System.currentTimeMillis() + 10 * 60 * 1000;
                        ok.run();
                    } else {
                        android.widget.Toast.makeText(a, "Wrong PIN", android.widget.Toast.LENGTH_SHORT).show();
                        if (cancelled != null) cancelled.run();
                    }
                })
                .setNegativeButton("Cancel", (d, w) -> { if (cancelled != null) cancelled.run(); })
                .setOnCancelListener(d -> { if (cancelled != null) cancelled.run(); })
                .show();
    }

    /** For Settings / Profiles screens: closes the screen unless the PIN is entered. */
    public static void guard(android.app.Activity a) {
        if (locked()) askPin(a, () -> { }, a::finish);
    }

    /** Switches profile; leaving a PIN-protected restricted profile needs the PIN. */
    public static void switchProfile(android.app.Activity a, String name, Runnable done) {
        askPin(a, () -> {
            Store.setProfile(name);
            unlockedProfile = "";
            done.run();
        }, null);
    }

    private static int maxLevel() {
        String l = limit();
        for (int i = 0; i < LIMITS.length; i++) if (LIMITS[i].equals(l)) return i;
        return Integer.MAX_VALUE;
    }

    // ------------------------------------------------------------ checking titles

    /** Synchronous answer when known: TRUE/FALSE, or null while it's being looked up. */
    public static Boolean allowedNow(JSONObject item) {
        if (!restricted()) return Boolean.TRUE;
        String id = idOf(item);
        if (id == null) return Boolean.FALSE;
        Integer lvl = cached(id);
        if (lvl == null) return null;
        return lvl != UNKNOWN && lvl <= maxLevel();
    }

    /** Blocking check (worker threads only). */
    public static boolean allowed(JSONObject item) {
        if (!restricted()) return true;
        String id = idOf(item);
        if (id == null) return false;
        int lvl = level(id, typeOf(item));
        return lvl != UNKNOWN && lvl <= maxLevel();
    }

    /**
     * Hides a card until its rating is known to be within the profile's limit. Rows whose cards are all
     * hidden disappear too. Does nothing for unrestricted profiles.
     */
    public static void gate(View card, JSONObject item) {
        if (!restricted()) return;
        Boolean now = allowedNow(item);
        if (now != null) {
            card.setVisibility(now ? View.VISIBLE : View.GONE);
            card.post(() -> tidyRow(card));
            return;
        }
        card.setVisibility(View.GONE);
        card.post(() -> tidyRow(card));
        String id = idOf(item), type = typeOf(item);
        POOL.execute(() -> {
            level(id, type);
            App.MAIN.post(() -> {
                Boolean ok = allowedNow(item);
                card.setVisibility(Boolean.TRUE.equals(ok) ? View.VISIBLE : View.GONE);
                tidyRow(card);
            });
        });
    }

    /** A Home/Search row (Ui.row) with no visible cards is hidden. */
    private static void tidyRow(View card) {
        if (!(card.getParent() instanceof ViewGroup)) return;
        ViewGroup items = (ViewGroup) card.getParent();
        View block = items;
        for (int i = 0; i < 3 && block != null; i++) {
            if ("row".equals(block.getTag())) break;
            block = block.getParent() instanceof View ? (View) block.getParent() : null;
        }
        if (block == null || !"row".equals(block.getTag())) return;
        boolean any = false;
        for (int i = 0; i < items.getChildCount(); i++) if (items.getChildAt(i).getVisibility() == View.VISIBLE) { any = true; break; }
        block.setVisibility(any ? View.VISIBLE : View.GONE);
    }

    private static String idOf(JSONObject item) {
        String id = item.optString("id");
        if (id.startsWith("tt")) {
            int c = id.indexOf(':');
            return c > 0 ? id.substring(0, c) : id;
        }
        if (id.startsWith("tmdb:")) return id;
        return null;   // IPTV, other apps, unmatched server items: no rating to go on
    }

    private static String typeOf(JSONObject item) {
        String t = item.optString("type");
        return "series".equals(t) || item.optString("id").startsWith("tmdb:tv") ? "series" : "movie";
    }

    private static Integer cached(String id) {
        Integer m = MEM.get(id);
        if (m != null) return m;
        String s = Store.get("rating:" + id);
        if (s.isEmpty()) return null;
        try {
            String[] p = s.split("\\|");
            int lvl = Integer.parseInt(p[0]);
            long at = Long.parseLong(p[1]);
            if (lvl == UNKNOWN && System.currentTimeMillis() - at > 7L * 24 * 3600 * 1000) return null;   // look again
            MEM.put(id, lvl);
            return lvl;
        } catch (Exception e) {
            return null;
        }
    }

    private static int level(String id, String type) {
        Integer c = cached(id);
        if (c != null) return c;
        if (PENDING.putIfAbsent(id, Boolean.TRUE) != null) {
            for (int i = 0; i < 100 && PENDING.containsKey(id); i++) {
                try { Thread.sleep(100); } catch (InterruptedException e) { break; }
            }
            Integer again = cached(id);
            return again == null ? UNKNOWN : again;
        }
        try {
            String cert = Tmdb.certification(id, type);
            int lvl = cert == null ? UNKNOWN : toLevel(cert);
            MEM.put(id, lvl);
            Store.put("rating:" + id, lvl + "|" + System.currentTimeMillis());
            return lvl;
        } catch (Exception e) {
            return UNKNOWN;   // offline etc.: hidden for now, not remembered
        } finally {
            PENDING.remove(id);
        }
    }

    static int toLevel(String cert) {
        switch (cert.trim().toUpperCase()) {
            case "ADULT": return ADULT;
            case "G": case "TV-Y": case "TV-Y7": case "TV-Y7-FV": case "TV-G": return 0;
            case "PG": case "TV-PG": return 1;
            case "PG-13": case "TV-14": return 2;
            case "R": case "TV-MA": return 3;
            case "NC-17": return 4;
            default: return UNKNOWN;
        }
    }
}
