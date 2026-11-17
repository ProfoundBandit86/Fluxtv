package com.fluxtv.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Games, scores and tables from ESPN's public scoreboard feeds, matched to the user's own IPTV channels
 * through the TV guide (programme titles naming the teams) and the networks the game is shown on.
 */
final class Sports {
    private Sports() { }

    static final class League {
        final String key, name, sport, path;

        League(String key, String name, String sport, String path) {
            this.key = key;
            this.name = name;
            this.sport = sport;
            this.path = path;
        }
    }

    static final League[] LEAGUES = {
            new League("nfl", "NFL", "football", "football/nfl"),
            new League("cfb", "College football", "football", "football/college-football"),
            new League("nba", "NBA", "basketball", "basketball/nba"),
            new League("wnba", "WNBA", "basketball", "basketball/wnba"),
            new League("cbb", "College basketball", "basketball", "basketball/mens-college-basketball"),
            new League("nhl", "NHL", "hockey", "hockey/nhl"),
            new League("mlb", "MLB", "baseball", "baseball/mlb"),
            new League("epl", "Premier League", "soccer", "soccer/eng.1"),
            new League("ucl", "Champions League", "soccer", "soccer/uefa.champions"),
            new League("mls", "MLS", "soccer", "soccer/usa.1"),
            new League("laliga", "LaLiga", "soccer", "soccer/esp.1"),
            new League("seriea", "Serie A", "soccer", "soccer/ita.1"),
            new League("bund", "Bundesliga", "soccer", "soccer/ger.1"),
            new League("ligamx", "Liga MX", "soccer", "soccer/mex.1"),
    };

    static League league(String key) {
        for (League l : LEAGUES) if (l.key.equals(key)) return l;
        return null;
    }

    static final class Team {
        String name = "", shortName = "", abbr = "", nick = "", place = "", logo = "", score = "", record = "";
        boolean winner;
    }

    static final class Event {
        String id = "", league = "", title = "", detail = "", state = "";   // state: pre, in, post
        long start;
        Team home = new Team(), away = new Team();
        final List<String> networks = new ArrayList<>();
        /** Matched channels, best first. */
        final List<Match> matches = new ArrayList<>();

        boolean live() { return "in".equals(state); }
        boolean done() { return "post".equals(state); }
    }

    static final class Match {
        final GuideView.Entry entry;
        final Iptv.Program program;   // may be null (matched by network only)
        final int score;

        Match(GuideView.Entry entry, Iptv.Program program, int score) {
            this.entry = entry;
            this.program = program;
            this.score = score;
        }
    }

    // ------------------------------------------------------------ followed leagues

    static List<String> followed() {
        List<String> out = new ArrayList<>();
        String s = Store.get("sports_leagues");
        if (s.isEmpty()) s = "nfl,cfb,nba,nhl,mlb,epl,mls,ucl";
        for (String k : s.split(",")) if (league(k.trim()) != null) out.add(k.trim());
        return out;
    }

    static void setFollowed(List<String> keys) {
        Store.put("sports_leagues", android.text.TextUtils.join(",", keys));
    }

    // ------------------------------------------------------------ scoreboards

    private static final Map<String, Object[]> cache = new ConcurrentHashMap<>();

    private static String base() {
        String t = BuildConfig.TEST_BUILD ? Store.get("test_sports") : "";
        return t.isEmpty() ? "https://site.api.espn.com" : t;
    }

    /** Games for one day (yyyyMMdd) across the given leagues. Live days are cached 30 s, others 10 min. Blocking. */
    static List<Event> day(List<String> leagues, String date) {
        List<Event> out = Collections.synchronizedList(new ArrayList<>());
        List<Thread> threads = new ArrayList<>();
        for (String key : leagues) {
            League l = league(key);
            if (l == null) continue;
            Thread t = new Thread(() -> {
                try { out.addAll(scoreboard(l, date)); } catch (Exception ignored) { }
            });
            t.start();
            threads.add(t);
        }
        for (Thread t : threads) try { t.join(20000); } catch (InterruptedException ignored) { }
        List<Event> list = new ArrayList<>(out);
        Collections.sort(list, (a, b) -> {
            int ra = a.live() ? 0 : a.done() ? 2 : 1, rb = b.live() ? 0 : b.done() ? 2 : 1;
            return ra != rb ? Integer.compare(ra, rb) : Long.compare(a.start, b.start);
        });
        return list;
    }

    @SuppressWarnings("unchecked")
    static List<Event> scoreboard(League l, String date) throws Exception {
        String key = l.key + "|" + date;
        Object[] c = cache.get(key);
        if (c != null) {
            List<Event> ev = (List<Event>) c[1];
            boolean anyLive = false;
            for (Event e : ev) if (e.live() || (!e.done() && e.start < System.currentTimeMillis())) anyLive = true;
            if (System.currentTimeMillis() - (long) c[0] < (anyLive ? 30_000 : 600_000)) return ev;
        }
        JSONObject j = new JSONObject(Http.get(base() + "/apis/site/v2/sports/" + l.path + "/scoreboard?dates=" + date
                + ("cfb".equals(l.key) || "cbb".equals(l.key) ? "&groups=80&limit=300" : "&limit=300"), null));
        List<Event> out = new ArrayList<>();
        JSONArray events = j.optJSONArray("events");
        if (events != null) for (int i = 0; i < events.length(); i++) {
            Event e = parse(l, events.optJSONObject(i));
            if (e != null) out.add(e);
        }
        cache.put(key, new Object[]{System.currentTimeMillis(), out});
        return out;
    }

    private static Event parse(League l, JSONObject o) {
        if (o == null) return null;
        Event e = new Event();
        e.id = o.optString("id");
        e.league = l.key;
        e.title = o.optString("name");
        e.start = isoTime(o.optString("date"));
        JSONObject st = o.optJSONObject("status");
        JSONObject type = st == null ? null : st.optJSONObject("type");
        if (type != null) {
            e.state = type.optString("state");
            e.detail = type.optString("shortDetail", type.optString("detail"));
        }
        JSONArray comps = o.optJSONArray("competitions");
        JSONObject comp = comps == null ? null : comps.optJSONObject(0);
        if (comp == null) return e;
        JSONArray cs = comp.optJSONArray("competitors");
        if (cs != null) for (int i = 0; i < cs.length(); i++) {
            JSONObject t = cs.optJSONObject(i);
            if (t == null) continue;
            Team team = "home".equals(t.optString("homeAway")) ? e.home : e.away;
            JSONObject tm = t.optJSONObject("team");
            if (tm != null) {
                team.name = tm.optString("displayName");
                team.shortName = tm.optString("shortDisplayName");
                team.abbr = tm.optString("abbreviation");
                team.nick = tm.optString("name");
                team.place = tm.optString("location");
                team.logo = tm.optString("logo");
            }
            team.score = t.optString("score");
            team.winner = t.optBoolean("winner");
            JSONArray rec = t.optJSONArray("records");
            if (rec != null && rec.optJSONObject(0) != null) team.record = rec.optJSONObject(0).optString("summary");
        }
        JSONArray bc = comp.optJSONArray("broadcasts");
        if (bc != null) for (int i = 0; i < bc.length(); i++) {
            JSONObject b = bc.optJSONObject(i);
            JSONArray names = b == null ? null : b.optJSONArray("names");
            if (names != null) for (int k = 0; k < names.length(); k++) if (!e.networks.contains(names.optString(k))) e.networks.add(names.optString(k));
        }
        JSONArray geo = comp.optJSONArray("geoBroadcasts");
        if (geo != null) for (int i = 0; i < geo.length(); i++) {
            JSONObject g = geo.optJSONObject(i);
            JSONObject media = g == null ? null : g.optJSONObject("media");
            String n = media == null ? "" : media.optString("shortName");
            if (!n.isEmpty() && !e.networks.contains(n)) e.networks.add(n);
        }
        return e;
    }

    static long isoTime(String s) {
        if (s == null || s.isEmpty()) return 0;
        String[] fmts = {"yyyy-MM-dd'T'HH:mm'Z'", "yyyy-MM-dd'T'HH:mm:ss'Z'", "yyyy-MM-dd'T'HH:mmX", "yyyy-MM-dd'T'HH:mm:ssX"};
        for (String f : fmts) {
            try {
                SimpleDateFormat d = new SimpleDateFormat(f, Locale.ROOT);
                d.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
                return d.parse(s).getTime();
            } catch (Exception ignored) { }
        }
        return 0;
    }

    static String dateKey(long t) {
        return new SimpleDateFormat("yyyyMMdd", Locale.ROOT).format(new java.util.Date(t));
    }

    // ------------------------------------------------------------ standings

    /** Rows: {group or "", team, record / points, extra}. Blocking. */
    static List<String[]> standings(League l) throws Exception {
        JSONObject j = new JSONObject(Http.get(base() + "/apis/v2/sports/" + l.path + "/standings", null));
        List<String[]> out = new ArrayList<>();
        collectStandings(j, "", out, l);
        return out;
    }

    private static void collectStandings(JSONObject node, String group, List<String[]> out, League l) {
        String name = node.optString("name", group);
        JSONObject st = node.optJSONObject("standings");
        if (st != null && st.optJSONArray("entries") != null) {
            out.add(new String[]{name, "", "", ""});
            JSONArray en = st.optJSONArray("entries");
            for (int i = 0; i < en.length(); i++) {
                JSONObject e = en.optJSONObject(i);
                if (e == null) continue;
                JSONObject team = e.optJSONObject("team");
                Map<String, String> stats = new HashMap<>();
                JSONArray sa = e.optJSONArray("stats");
                if (sa != null) for (int k = 0; k < sa.length(); k++) {
                    JSONObject s = sa.optJSONObject(k);
                    if (s != null) stats.put(s.optString("name"), s.optString("displayValue"));
                }
                String rec = "soccer".equals(l.sport)
                        ? stats.getOrDefault("points", "") + " pts · " + stats.getOrDefault("wins", "0") + "-" + stats.getOrDefault("ties", "0") + "-" + stats.getOrDefault("losses", "0")
                        : stats.getOrDefault("wins", "") + "-" + stats.getOrDefault("losses", "")
                          + ("hockey".equals(l.sport) && stats.containsKey("otLosses") ? "-" + stats.get("otLosses") : "");
                String extra = stats.containsKey("gamesBehind") ? "GB " + stats.get("gamesBehind")
                        : stats.containsKey("winPercent") ? stats.get("winPercent") : stats.getOrDefault("pointDifferential", "");
                out.add(new String[]{"", team == null ? "" : team.optString("displayName"), rec, extra});
            }
        }
        JSONArray children = node.optJSONArray("children");
        if (children != null) for (int i = 0; i < children.length(); i++) {
            JSONObject ch = children.optJSONObject(i);
            if (ch != null) collectStandings(ch, ch.optString("name"), out, l);
        }
    }

    // ------------------------------------------------------------ matching games to channels

    /**
     * Finds the user's channels showing each game: guide programmes around kick-off that name the teams score
     * highest, then channels named after the networks carrying it. Blocking.
     */
    static void match(List<Event> events) {
        List<GuideView.Entry> all = new ArrayList<>();
        for (Iptv.Source s : Iptv.sources()) {
            try {
                Iptv.loadGuide(s, false);
                for (Iptv.Channel c : Iptv.channels(s)) all.add(new GuideView.Entry(s, c));
            } catch (Exception ignored) { }
        }
        if (all.isEmpty()) return;
        // Channel names once, normalised.
        String[] chanNorm = new String[all.size()];
        for (int i = 0; i < all.size(); i++) chanNorm[i] = Iptv.norm(all.get(i).channel.name);
        for (Event e : events) {
            e.matches.clear();
            Map<String, Match> best = new HashMap<>();
            long from = e.start - 45 * 60_000L, to = e.start + 3 * 3600_000L;
            String[] homeKeys = keys(e.home), awayKeys = keys(e.away);
            for (int i = 0; i < all.size(); i++) {
                GuideView.Entry en = all.get(i);
                for (Iptv.Program p : Iptv.programs(en.source, en.channel)) {
                    if (p.end <= from || p.start >= to) continue;
                    String text = (" " + p.title + " " + p.desc + " ").toLowerCase(Locale.ROOT);
                    boolean h = mentions(text, homeKeys), a = mentions(text, awayKeys);
                    if (!h && !a) continue;
                    int score = (h && a ? 100 : 30) - (int) Math.min(30, Math.abs(p.start - e.start) / 600_000L);
                    if (text.contains(" live")) score += 10;
                    if (text.contains("replay") || text.contains("highlights") || text.contains("classic")) score -= 40;
                    put(best, new Match(en, p, score));
                }
            }
            // Networks (ESPN, FOX, TNT, Peacock…) matched to channel names.
            for (String n : e.networks) {
                String nn = Iptv.norm(n);
                if (nn.length() < 2) continue;
                for (int i = 0; i < all.size(); i++) {
                    String cn = chanNorm[i];
                    if (cn.equals(nn) || (cn.startsWith(nn) && cn.length() <= nn.length() + 4)) {
                        GuideView.Entry en = all.get(i);
                        Iptv.Program now = GuideView.at(Iptv.programs(en.source, en.channel), Math.max(e.start, System.currentTimeMillis()));
                        put(best, new Match(en, now, cn.equals(nn) ? 45 : 35));
                    }
                }
            }
            List<Match> list = new ArrayList<>(best.values());
            Collections.sort(list, (x, y) -> Integer.compare(y.score, x.score));
            for (Match m : list) if (m.score > 0 && e.matches.size() < 12) e.matches.add(m);
        }
    }

    private static void put(Map<String, Match> best, Match m) {
        String k = m.entry.source.id + "|" + m.entry.channel.id;
        Match old = best.get(k);
        if (old == null || old.score < m.score) best.put(k, m);
    }

    /** Words that identify a team in a programme title: "Chiefs", "Kansas City Chiefs", "KC Chiefs"… */
    private static String[] keys(Team t) {
        List<String> k = new ArrayList<>();
        for (String s : new String[]{t.name, t.nick, t.shortName}) {
            if (s != null && s.length() >= 4 && !k.contains(s.toLowerCase(Locale.ROOT))) k.add(s.toLowerCase(Locale.ROOT));
        }
        return k.toArray(new String[0]);
    }

    private static boolean mentions(String text, String[] keys) {
        for (String k : keys) {
            int i = text.indexOf(k);
            if (i > 0 && !Character.isLetter(text.charAt(i - 1))
                    && (i + k.length() >= text.length() || !Character.isLetter(text.charAt(i + k.length())))) return true;
        }
        return false;
    }
}
