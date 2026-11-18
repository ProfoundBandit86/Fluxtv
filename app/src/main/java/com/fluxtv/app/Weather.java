package com.fluxtv.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;

/**
 * Current weather for the clock badge, from Open-Meteo (free, no key). The place is set in Settings → Home screen
 * (a US ZIP code or a city name); until then the badge shows just the clock.
 */
final class Weather {

    static final class Now {
        int temp;
        int code;
        boolean day = true;
        String place = "";
    }

    private static Now cached;
    private static long cachedAt;
    private static String cachedFor = "";

    private Weather() { }

    static String place() {
        return Store.get("weather_place").trim();
    }

    /** Fahrenheit in the US (by the region setting) unless chosen otherwise. */
    static boolean fahrenheit() {
        String u = Store.get("weather_unit");
        if (u.equals("f")) return true;
        if (u.equals("c")) return false;
        return "US".equals(Tmdb.region());
    }

    static void setPlace(String p) {
        Store.put("weather_place", p == null ? "" : p.trim());
        Store.put("weather_where", "");
        cached = null;
    }

    /** The resolved place name, e.g. "Chicago, Illinois" (empty until the first lookup). */
    static String placeLabel() {
        String w = Store.get("weather_where");
        String[] p = w.split("\\|");
        return p.length >= 4 && p[0].equals(place()) ? p[3] : "";
    }

    /** Cached for 20 minutes. Call off the main thread; null when no place is set. */
    static Now now() throws Exception {
        String p = place();
        if (p.isEmpty()) return null;
        String key = p + "|" + fahrenheit();
        if (cached != null && key.equals(cachedFor) && System.currentTimeMillis() - cachedAt < 20 * 60 * 1000) return cached;
        String[] where = locate(p);
        String url = forecastBase() + "/v1/forecast?latitude=" + where[1] + "&longitude=" + where[2]
                + "&current=temperature_2m,weather_code,is_day" + (fahrenheit() ? "&temperature_unit=fahrenheit" : "");
        JSONObject cur = new JSONObject(Http.get(url, null)).getJSONObject("current");
        Now n = new Now();
        n.temp = (int) Math.round(cur.optDouble("temperature_2m"));
        n.code = cur.optInt("weather_code");
        n.day = cur.optInt("is_day", 1) == 1;
        n.place = where[3];
        cached = n;
        cachedAt = System.currentTimeMillis();
        cachedFor = key;
        return n;
    }

    /** [place, lat, lon, label], looked up once per place and kept. */
    static String[] locate(String p) throws Exception {
        String[] w = Store.get("weather_where").split("\\|");
        if (w.length >= 4 && w[0].equals(p)) return w;
        String lat, lon, label;
        if (p.matches("\\d{5}") && !testing()) {
            JSONObject z = new JSONObject(Http.get("https://api.zippopotam.us/us/" + p, null));
            JSONObject pl = z.getJSONArray("places").getJSONObject(0);
            lat = pl.getString("latitude");
            lon = pl.getString("longitude");
            label = pl.optString("place name") + ", " + pl.optString("state abbreviation");
        } else {
            JSONArray r = new JSONObject(Http.get(geoBase() + "/v1/search?count=1&language=en&name=" + Http.enc(p), null))
                    .optJSONArray("results");
            if (r == null || r.length() == 0) throw new Exception("Couldn't find \"" + p + "\"");
            JSONObject g = r.getJSONObject(0);
            lat = String.valueOf(g.getDouble("latitude"));
            lon = String.valueOf(g.getDouble("longitude"));
            String admin = g.optString("admin1");
            label = g.optString("name") + (admin.isEmpty() ? "" : ", " + admin);
        }
        String[] out = {p, lat, lon, label.replace("|", " ")};
        Store.put("weather_where", out[0] + "|" + out[1] + "|" + out[2] + "|" + out[3]);
        return out;
    }

    static int icon(Now n) {
        int c = n.code;
        if (c == 0) return n.day ? R.drawable.ic_wx_sun : R.drawable.ic_wx_moon;
        if (c == 1 || c == 2) return n.day ? R.drawable.ic_wx_partly : R.drawable.ic_wx_moon;
        if (c == 3) return R.drawable.ic_wx_cloud;
        if (c == 45 || c == 48) return R.drawable.ic_wx_fog;
        if ((c >= 71 && c <= 77) || c == 85 || c == 86) return R.drawable.ic_wx_snow;
        if (c >= 95) return R.drawable.ic_wx_storm;
        if (c >= 51) return R.drawable.ic_wx_rain;
        return R.drawable.ic_wx_cloud;
    }

    static String describe(Now n) {
        int c = n.code;
        String what = c == 0 ? "Clear" : c <= 2 ? "Partly cloudy" : c == 3 ? "Cloudy" : c == 45 || c == 48 ? "Fog"
                : (c >= 71 && c <= 77) || c == 85 || c == 86 ? "Snow" : c >= 95 ? "Thunderstorms" : c >= 51 ? "Rain" : "Cloudy";
        return String.format(Locale.ROOT, "%d°%s · %s", n.temp, fahrenheit() ? "F" : "C", what);
    }

    private static boolean testing() {
        return BuildConfig.TEST_BUILD && !Store.get("test_weather").isEmpty();
    }

    private static String forecastBase() {
        return testing() ? Store.get("test_weather") : "https://api.open-meteo.com";
    }

    private static String geoBase() {
        return testing() ? Store.get("test_weather") : "https://geocoding-api.open-meteo.com";
    }
}
