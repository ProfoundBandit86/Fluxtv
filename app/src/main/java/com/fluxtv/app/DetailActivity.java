package com.fluxtv.app;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
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

/** Title page: info, play/resume, watchlist, trailer, cast, seasons and episodes. */
public class DetailActivity extends Activity {

    private String type, id;
    private JSONObject meta;
    private LinearLayout content, buttons, seasonsBar, episodes;
    private ImageView background, poster;
    private TextView title, info, description, cast;
    private int selectedSeason = Integer.MIN_VALUE;

    public static void open(Context c, JSONObject m) {
        c.startActivity(new Intent(c, DetailActivity.class)
                .putExtra("type", m.optString("type"))
                .putExtra("id", m.optString("id"))
                .putExtra("name", m.optString("name"))
                .putExtra("poster", m.optString("poster")));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        type = getIntent().getStringExtra("type");
        id = getIntent().getStringExtra("id");

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Ui.BG);
        background = new ImageView(this);
        background.setScaleType(ImageView.ScaleType.CENTER_CROP);
        background.setAlpha(0.28f);
        root.addView(background, new FrameLayout.LayoutParams(-1, -1));

        ScrollView scroll = new ScrollView(this);
        content = Ui.vertical(this);
        int pad = Ui.dp(this, 48);
        content.setPadding(pad, Ui.dp(this, 36), pad, pad);
        scroll.addView(content);
        root.addView(scroll, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout top = Ui.horizontal(this);
        poster = new ImageView(this);
        poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
        poster.setBackgroundColor(Ui.BUTTON);
        top.addView(poster, new LinearLayout.LayoutParams(Ui.dp(this, 200), Ui.dp(this, 300)));
        ImageLoader.load(poster, getIntent().getStringExtra("poster"), Ui.dp(this, 200));

        LinearLayout side = Ui.vertical(this);
        side.setPadding(Ui.dp(this, 32), 0, 0, 0);
        title = Ui.heading(this, getIntent().getStringExtra("name"), 34);
        info = Ui.text(this, "", 17, Ui.MUTED);
        info.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 10));
        description = Ui.text(this, "Loading…", 17, Color.parseColor("#DDD8F0"));
        description.setMaxLines(6);
        description.setEllipsize(TextUtils.TruncateAt.END);
        buttons = Ui.horizontal(this);
        buttons.setPadding(0, Ui.dp(this, 14), 0, 0);
        cast = Ui.text(this, "", 15, Ui.MUTED);
        cast.setPadding(0, Ui.dp(this, 10), 0, 0);
        side.addView(title);
        side.addView(info);
        side.addView(description);
        side.addView(buttons);
        side.addView(cast);
        top.addView(side, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        content.addView(top);

        seasonsBar = Ui.row(this, content, "");
        episodes = Ui.vertical(this);
        content.addView(episodes);

        load();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (meta != null) render();   // refresh resume / watched markers
    }

    private void load() {
        App.async(() -> Addons.meta(type, id), m -> {
            meta = m;
            if (!meta.has("type")) try { meta.put("type", type); } catch (Exception ignored) { }
            render();
            View first = buttons.getChildAt(0);
            if (first != null) first.requestFocus();
        }, e -> {
            description.setText("Couldn't load details: " + App.message(e));
            buttons.removeAllViews();
            buttons.addView(Ui.button(this, "Retry", v -> load()));
        });
    }

    private void render() {
        title.setText(meta.optString("name"));
        String bg = meta.optString("background");
        if (!bg.isEmpty()) ImageLoader.load(background, bg, Ui.dp(this, 960));
        if (!meta.optString("poster").isEmpty()) ImageLoader.load(poster, meta.optString("poster"), Ui.dp(this, 200));

        List<String> bits = new ArrayList<>();
        String year = meta.optString("releaseInfo", meta.optString("year"));
        if (!year.isEmpty()) bits.add(year);
        if (!meta.optString("runtime").isEmpty()) bits.add(meta.optString("runtime"));
        JSONArray genres = meta.optJSONArray("genres");
        if (genres == null) genres = meta.optJSONArray("genre");
        if (genres != null) bits.add(join(genres, ", ", 3));
        if (!meta.optString("imdbRating").isEmpty()) bits.add("★ " + meta.optString("imdbRating"));
        info.setText(TextUtils.join("  ·  ", bits));
        description.setText(meta.optString("description"));

        JSONArray castArr = meta.optJSONArray("cast");
        StringBuilder people = new StringBuilder();
        if (castArr != null && castArr.length() > 0) people.append("Cast: ").append(join(castArr, ", ", 6));
        JSONArray dir = meta.optJSONArray("director");
        if (dir != null && dir.length() > 0) people.append(people.length() > 0 ? "\n" : "").append("Director: ").append(join(dir, ", ", 3));
        cast.setText(people);

        renderButtons();
        if ("series".equals(type) || hasEpisodes()) renderSeasons();
    }

    private void renderButtons() {
        View focused = getCurrentFocus();
        boolean hadFocus = focused != null && focused.getParent() == buttons;
        buttons.removeAllViews();

        if (!("series".equals(type) || hasEpisodes())) {
            JSONObject hints = meta.optJSONObject("behaviorHints");
            String videoId = hints != null && !hints.optString("defaultVideoId").isEmpty()
                    ? hints.optString("defaultVideoId") : id;
            JSONObject p = Store.progress(videoId);
            String label = p != null && !Store.isFinished(p) && p.optLong("pos") > 30000
                    ? "▶  Resume " + Ui.time(p.optLong("pos")) : "▶  Play";
            buttons.addView(Ui.button(this, label, v -> openStreams(videoId, "")));
        } else {
            JSONObject next = nextEpisode();
            if (next != null) {
                String label = "▶  " + episodeLabel(next);
                buttons.addView(Ui.button(this, label, v -> openStreams(next.optString("id"), episodeLabel(next))));
            }
        }

        boolean inLib = Store.inLibrary(id);
        buttons.addView(Ui.button(this, inLib ? "✓ In watchlist" : "+ Watchlist", v -> {
            boolean added = Store.toggleLibrary(meta);
            ((Button) v).setText(added ? "✓ In watchlist" : "+ Watchlist");
        }));

        String trailer = trailerId();
        if (trailer != null) {
            buttons.addView(Ui.button(this, "Trailer", v -> {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=" + trailer)));
                } catch (ActivityNotFoundException e) {
                    Toast.makeText(this, "YouTube isn't installed", Toast.LENGTH_SHORT).show();
                }
            }));
        }
        if (hadFocus && buttons.getChildCount() > 0) buttons.getChildAt(0).requestFocus();
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
        JSONArray v = meta.optJSONArray("videos");
        if (v != null) for (int i = 0; i < v.length(); i++) {
            JSONObject e = v.optJSONObject(i);
            if (e == null) continue;
            int s = e.optInt("season", 1);
            List<JSONObject> list = map.get(s);
            if (list == null) {
                list = new ArrayList<>();
                map.put(s, list);
            }
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
        if (!map.containsKey(selectedSeason)) {
            JSONObject next = nextEpisode();
            selectedSeason = next != null ? next.optInt("season", 1)
                    : (map.ceilingKey(1) != null ? map.ceilingKey(1) : map.firstKey());
        }
        seasonsBar.removeAllViews();
        List<Integer> order = new ArrayList<>(map.keySet());
        if (order.size() > 1 && order.get(0) == 0) order.add(order.remove(0));   // specials last
        for (int s : order) {
            String label = s == 0 ? "Specials" : "Season " + s;
            Button b = Ui.button(this, s == selectedSeason ? "● " + label : label, v -> {
                selectedSeason = s;
                renderSeasons();
            });
            seasonsBar.addView(b);
        }
        episodes.removeAllViews();
        List<JSONObject> list = map.get(selectedSeason);
        if (list == null) return;
        for (JSONObject ep : list) {
            String label = episodeLabel(ep);
            JSONObject p = Store.progress(ep.optString("id"));
            String state = "";
            if (Store.isFinished(p)) state = "   ✓ watched";
            else if (p != null && p.optLong("dur") > 0) state = "   " + (100 * p.optLong("pos") / p.optLong("dur")) + "%";
            String released = ep.optString("released");
            String date = released.length() >= 10 ? "   " + released.substring(0, 10) : "";
            episodes.addView(Ui.listButton(this, label + state + date, v -> openStreams(ep.optString("id"), label)));
        }
    }

    private static String episodeLabel(JSONObject ep) {
        int s = ep.optInt("season"), e = ep.optInt("episode", ep.optInt("number"));
        String name = ep.optString("name", ep.optString("title"));
        return "S" + s + " E" + e + (name.isEmpty() ? "" : " · " + name);
    }

    // ------------------------------------------------------------ helpers

    private void openStreams(String videoId, String label) {
        PlayInfo pi = PlayInfo.of(type, id, videoId, meta.optString("name"), meta.optString("poster"), label);
        startActivity(pi.into(new Intent(this, StreamsActivity.class)));
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
