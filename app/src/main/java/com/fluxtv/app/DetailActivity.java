package com.fluxtv.app;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Title page: backdrop, info, Play/Resume, watchlist, trailer, seasons and episode cards. */
public class DetailActivity extends Activity {

    private String type, id, serverId;
    private JSONObject meta;
    private LinearLayout buttons, seasonsBar, episodes, info;
    private ImageView backdrop;
    private TextView title, metaLine, description, cast;
    private View episodesHeader;
    private int selectedSeason = Integer.MIN_VALUE;

    public static void open(Context c, JSONObject m) {
        c.startActivity(new Intent(c, DetailActivity.class)
                .putExtra("type", m.optString("type"))
                .putExtra("id", m.optString("id"))
                .putExtra("serverId", m.optString("serverId"))
                .putExtra("name", m.optString("name"))
                .putExtra("poster", m.optString("poster"))
                .putExtra("background", m.optString("background")));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        type = getIntent().getStringExtra("type");
        id = getIntent().getStringExtra("id");
        serverId = getIntent().getStringExtra("serverId");

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Ui.BG);

        backdrop = new ImageView(this);
        backdrop.setScaleType(ImageView.ScaleType.CENTER_CROP);
        root.addView(backdrop, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 380), Gravity.TOP));
        View sLeft = new View(this);
        sLeft.setBackground(Ui.scrim(GradientDrawable.Orientation.LEFT_RIGHT, Ui.BG, 1f));
        root.addView(sLeft, new FrameLayout.LayoutParams(Ui.dp(this, 640), Ui.dp(this, 380), Gravity.TOP | Gravity.START));
        View sBottom = new View(this);
        sBottom.setBackground(Ui.scrim(GradientDrawable.Orientation.BOTTOM_TOP, Ui.BG, 1f));
        FrameLayout.LayoutParams sb = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 200), Gravity.TOP);
        sb.setMargins(0, Ui.dp(this, 182), 0, 0);
        root.addView(sBottom, sb);
        String bg = getIntent().getStringExtra("background");
        ImageLoader.load(backdrop, bg == null || bg.isEmpty() ? getIntent().getStringExtra("poster") : bg, Ui.dp(this, 900));

        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipChildren(false);
        LinearLayout content = Ui.vertical(this);
        content.setClipChildren(false);
        content.setPadding(Ui.dp(this, 56), Ui.dp(this, 48), 0, Ui.dp(this, 60));
        scroll.addView(content);
        root.addView(scroll, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);

        info = Ui.vertical(this);
        info.setPadding(0, 0, Ui.dp(this, 56), 0);
        title = Ui.heading(this, getIntent().getStringExtra("name"), 40);
        title.setMaxLines(2);
        metaLine = Ui.text(this, "", 13, Ui.MUTED);
        metaLine.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 12));
        description = Ui.text(this, "Loading…", 14, Ui.alpha(Ui.TEXT, 0.82f));
        description.setMaxLines(3);
        description.setEllipsize(TextUtils.TruncateAt.END);
        cast = Ui.text(this, "", 12, Ui.DIM);
        cast.setPadding(0, Ui.dp(this, 10), 0, 0);
        cast.setMaxLines(2);
        buttons = Ui.horizontal(this);
        buttons.setPadding(0, Ui.dp(this, 18), 0, 0);
        info.addView(title);
        info.addView(metaLine);
        info.addView(description);
        info.addView(cast);
        info.addView(buttons);
        content.addView(info, new LinearLayout.LayoutParams(Ui.dp(this, 620), ViewGroup.LayoutParams.WRAP_CONTENT));

        episodesHeader = Ui.heading(this, "Episodes", 17);
        episodesHeader.setPadding(0, Ui.dp(this, 34), 0, Ui.dp(this, 2));
        episodesHeader.setVisibility(View.GONE);
        content.addView(episodesHeader);
        seasonsBar = Ui.horizontal(this);
        content.addView(seasonsBar);
        LinearLayout epHolder = Ui.vertical(this);
        epHolder.setClipChildren(false);
        content.addView(epHolder);
        episodes = Ui.row(this, epHolder, "");

        load();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (meta != null) render();   // refresh resume / watched markers
    }

    private void load() {
        App.async(() -> {
            try {
                return Addons.meta(type, id);
            } catch (Exception e) {
                // Library title whose streaming info isn't available: use the server's own page.
                if (serverId != null && !serverId.isEmpty()) return Addons.meta(type, serverId);
                throw e;
            }
        }, m -> {
            meta = m;
            if (!meta.has("type")) try { meta.put("type", type); } catch (Exception ignored) { }
            render();
            View first = buttons.getChildAt(0);
            if (first != null) first.post(first::requestFocus);
        }, e -> {
            description.setText("Couldn't load details: " + App.message(e));
            buttons.removeAllViews();
            Button retry = Ui.button(this, "Retry", v -> load());
            buttons.addView(retry);
            retry.post(retry::requestFocus);
        });
    }

    private void render() {
        title.setText(meta.optString("name"));
        String bg = meta.optString("background");
        if (!bg.isEmpty()) ImageLoader.load(backdrop, bg, Ui.dp(this, 900));

        List<String> bits = new ArrayList<>();
        if (!meta.optString("imdbRating").isEmpty()) bits.add("★ " + meta.optString("imdbRating"));
        String year = meta.optString("releaseInfo", meta.optString("year"));
        if (!year.isEmpty()) bits.add(year);
        if (!meta.optString("runtime").isEmpty()) bits.add(meta.optString("runtime"));
        if (isSeries()) {
            int n = seasons().size();
            if (n > 0) bits.add(n + (n == 1 ? " season" : " seasons"));
        }
        JSONArray genres = meta.optJSONArray("genres");
        if (genres == null) genres = meta.optJSONArray("genre");
        if (genres != null) bits.add(join(genres, ", ", 3));
        metaLine.setText(TextUtils.join("   ·   ", bits));
        description.setText(meta.optString("description"));

        JSONArray castArr = meta.optJSONArray("cast");
        StringBuilder people = new StringBuilder();
        if (castArr != null && castArr.length() > 0) people.append("Starring ").append(join(castArr, ", ", 4));
        JSONArray dir = meta.optJSONArray("director");
        if (dir != null && dir.length() > 0) people.append(people.length() > 0 ? "   ·   " : "").append("Directed by ").append(join(dir, ", ", 2));
        cast.setText(people);

        renderButtons();
        if (isSeries()) renderSeasons();
    }

    private boolean isSeries() {
        return "series".equals(type) || hasEpisodes();
    }

    private void renderButtons() {
        View focused = getCurrentFocus();
        int focusedIndex = focused != null && focused.getParent() == buttons ? buttons.indexOfChild(focused) : -1;
        buttons.removeAllViews();

        if (!isSeries()) {
            JSONObject hints = meta.optJSONObject("behaviorHints");
            String videoId = hints != null && !hints.optString("defaultVideoId").isEmpty()
                    ? hints.optString("defaultVideoId") : meta.optString("id", id);
            JSONObject p = Store.progress(videoId);
            String label = p != null && !Store.isFinished(p) && p.optLong("pos") > 30000
                    ? "Resume · " + Ui.left(p.optLong("pos"), p.optLong("dur")) : "Play";
            buttons.addView(Ui.button(this, R.drawable.ic_play, label, v -> openStreams(videoId, "")));
        } else {
            JSONObject next = nextEpisode();
            if (next != null) {
                JSONObject p = Store.progress(next.optString("id"));
                String verb = p != null && !Store.isFinished(p) && p.optLong("pos") > 30000 ? "Resume " : "Play ";
                buttons.addView(Ui.button(this, R.drawable.ic_play, verb + shortLabel(next),
                        v -> openStreams(next.optString("id"), episodeLabel(next))));
            }
        }

        boolean inLib = Store.inLibrary(id);
        buttons.addView(Ui.button(this, inLib ? R.drawable.ic_check : R.drawable.ic_plus, inLib ? "In watchlist" : "Watchlist", v -> {
            Store.toggleLibrary(meta);
            renderButtons();
            View b = buttons.getChildAt(1);
            if (b != null) b.requestFocus();
        }));

        String trailer = trailerId();
        if (trailer != null) {
            buttons.addView(Ui.button(this, R.drawable.ic_trailer, "Trailer", v -> {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=" + trailer)));
                } catch (ActivityNotFoundException e) {
                    Toast.makeText(this, "YouTube isn't installed", Toast.LENGTH_SHORT).show();
                }
            }));
        }
        if (focusedIndex >= 0 && focusedIndex < buttons.getChildCount()) buttons.getChildAt(focusedIndex).requestFocus();
    }

    // ------------------------------------------------------------ series

    private boolean hasEpisodes() {
        JSONArray v = meta.optJSONArray("videos");
        if (v == null) return false;
        for (int i = 0; i < v.length(); i++) {
            JSONObject e = v.optJSONObject(i);
            if (e != null && e.has("season")) return true;
        }
        return false;
    }

    private TreeMap<Integer, List<JSONObject>> seasons() {
        TreeMap<Integer, List<JSONObject>> map = new TreeMap<>();
        JSONArray v = meta == null ? null : meta.optJSONArray("videos");
        if (v != null) for (int i = 0; i < v.length(); i++) {
            JSONObject e = v.optJSONObject(i);
            if (e == null) continue;
            int s = e.optInt("season", 1);
            List<JSONObject> list = map.get(s);
            if (list == null) map.put(s, list = new ArrayList<>());
            list.add(e);
        }
        for (List<JSONObject> list : map.values()) {
            Collections.sort(list, (a, b) -> Integer.compare(a.optInt("episode", a.optInt("number")), b.optInt("episode", b.optInt("number"))));
        }
        return map;
    }

    /** The episode after the last one watched, or the first one. */
    private JSONObject nextEpisode() {
        List<JSONObject> ordered = new ArrayList<>();
        for (Map.Entry<Integer, List<JSONObject>> e : seasons().entrySet()) {
            if (e.getKey() > 0) ordered.addAll(e.getValue());
        }
        if (ordered.isEmpty()) return null;
        JSONObject best = null;
        long bestT = -1;
        int bestIdx = -1;
        for (int i = 0; i < ordered.size(); i++) {
            JSONObject p = Store.progress(ordered.get(i).optString("id"));
            if (p != null && p.optLong("t") > bestT) {
                bestT = p.optLong("t");
                bestIdx = i;
                best = p;
            }
        }
        if (best == null) return ordered.get(0);
        if (Store.isFinished(best)) return bestIdx + 1 < ordered.size() ? ordered.get(bestIdx + 1) : null;
        return ordered.get(bestIdx);
    }

    private void renderSeasons() {
        TreeMap<Integer, List<JSONObject>> map = seasons();
        if (map.isEmpty()) return;
        episodesHeader.setVisibility(View.VISIBLE);
        JSONObject next = nextEpisode();
        if (!map.containsKey(selectedSeason)) {
            selectedSeason = next != null ? next.optInt("season", 1)
                    : (map.ceilingKey(1) != null ? map.ceilingKey(1) : map.firstKey());
        }
        View focused = getCurrentFocus();
        boolean seasonFocused = focused != null && focused.getParent() == seasonsBar;

        seasonsBar.removeAllViews();
        List<Integer> order = new ArrayList<>(map.keySet());
        if (order.size() > 1 && order.get(0) == 0) order.add(order.remove(0));   // specials last
        View selectedPill = null;
        for (int s : order) {
            TextView pill = Ui.pill(this, s == 0 ? "Specials" : "Season " + s, s == selectedSeason, v -> {
                selectedSeason = s;
                renderSeasons();
            });
            seasonsBar.addView(pill);
            if (s == selectedSeason) selectedPill = pill;
        }
        if (seasonFocused && selectedPill != null) selectedPill.requestFocus();

        episodes.removeAllViews();
        List<JSONObject> list = map.get(selectedSeason);
        if (list == null) return;
        String nextId = next == null ? "" : next.optString("id");
        for (JSONObject ep : list) {
            JSONObject p = Store.progress(ep.optString("id"));
            boolean done = Store.isFinished(p);
            double progress = done ? 1 : p != null && p.optLong("dur") > 0 ? (double) p.optLong("pos") / p.optLong("dur") : 0;
            String badge = done ? "Watched" : ep.optString("id").equals(nextId) ? "Up next" : "";
            String released = ep.optString("released");
            String sub = p != null && !done && p.optLong("pos") > 30000 ? Ui.left(p.optLong("pos"), p.optLong("dur"))
                    : released.length() >= 10 ? released.substring(0, 10) : "";
            int n = ep.optInt("episode", ep.optInt("number"));
            String name = ep.optString("name", ep.optString("title"));
            String img = ep.optString("thumbnail");
            if (img.isEmpty()) img = meta.optString("background", meta.optString("poster"));
            episodes.addView(Ui.landscape(this, img, n + " · " + (name.isEmpty() ? "Episode " + n : name), sub, badge,
                    progress, 210, v -> openStreams(ep.optString("id"), episodeLabel(ep)), null));
        }
    }

    private static String shortLabel(JSONObject ep) {
        return "S" + ep.optInt("season") + " E" + ep.optInt("episode", ep.optInt("number"));
    }

    private static String episodeLabel(JSONObject ep) {
        String name = ep.optString("name", ep.optString("title"));
        return shortLabel(ep) + (name.isEmpty() ? "" : " · " + name);
    }

    // ------------------------------------------------------------ helpers

    private void openStreams(String videoId, String label) {
        PlayInfo pi = PlayInfo.of(type, meta.optString("id", id), videoId, meta.optString("name"), meta.optString("poster"), label);
        pi.background = meta.optString("background");
        Intent i = pi.into(new Intent(this, StreamsActivity.class));
        // Library fallback: if this is a streaming page for a title you own, the server copy is matched by IMDb id.
        startActivity(i);
    }

    private String trailerId() {
        JSONArray t = meta.optJSONArray("trailerStreams");
        if (t != null && t.length() > 0 && t.optJSONObject(0) != null && !t.optJSONObject(0).optString("ytId").isEmpty()) {
            return t.optJSONObject(0).optString("ytId");
        }
        t = meta.optJSONArray("trailers");
        if (t != null && t.length() > 0 && t.optJSONObject(0) != null && !t.optJSONObject(0).optString("source").isEmpty()) {
            return t.optJSONObject(0).optString("source");
        }
        return null;
    }

    private static String join(JSONArray a, String sep, int max) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < a.length() && i < max; i++) out.add(a.optString(i));
        return TextUtils.join(sep, out);
    }
}
