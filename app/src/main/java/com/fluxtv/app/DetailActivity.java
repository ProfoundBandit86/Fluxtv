package com.fluxtv.app;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
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

/**
 * Title page: full-screen backdrop, title art, rating and genre chips, Play/Resume,
 * Favorite and Trailer, then Episodes, More like this, and an About section.
 */
public class DetailActivity extends Activity {

    private String type, id, serverId, badge;
    private JSONObject meta;
    private LinearLayout buttons, seasonsBar, episodes, chips, similarRow, about;
    private ImageView backdrop, logo;
    private TextView title, description, credits;
    private View episodesHeader, similarHeader, aboutHeader, dimmer;
    private int selectedSeason = Integer.MIN_VALUE;
    private boolean similarLoaded;

    public static void open(Context c, JSONObject m) {
        c.startActivity(new Intent(c, DetailActivity.class)
                .putExtra("type", m.optString("type"))
                .putExtra("id", m.optString("id"))
                .putExtra("serverId", m.optString("serverId"))
                .putExtra("badge", m.optString("badge"))
                .putExtra("service", m.optString("service"))
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
        badge = getIntent().getStringExtra("badge");

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Ui.BG);

        // Full-screen backdrop with cinematic fades.
        backdrop = new ImageView(this);
        backdrop.setScaleType(ImageView.ScaleType.CENTER_CROP);
        root.addView(backdrop, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        View sLeft = new View(this);
        sLeft.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{Ui.BG, Ui.alpha(Ui.BG, 0.92f), Ui.alpha(Ui.BG, 0.45f), Ui.alpha(Ui.BG, 0f)}));
        root.addView(sLeft, new FrameLayout.LayoutParams(Ui.dp(this, 760), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START));
        View sBottom = new View(this);
        sBottom.setBackground(Ui.scrim(GradientDrawable.Orientation.BOTTOM_TOP, Ui.BG, 1f));
        root.addView(sBottom, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 240), Gravity.BOTTOM));
        dimmer = new View(this);
        dimmer.setBackgroundColor(Ui.BG);
        dimmer.setAlpha(0f);
        root.addView(dimmer, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        String bg = getIntent().getStringExtra("background");
        ImageLoader.load(backdrop, bg == null || bg.isEmpty() ? getIntent().getStringExtra("poster") : bg, Ui.dp(this, 1100));

        ScrollView scroll = new FocusScroll.Vertical(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipChildren(false);
        LinearLayout content = Ui.vertical(this);
        content.setClipChildren(false);
        content.setPadding(Ui.dp(this, 64), 0, 0, Ui.dp(this, 70));
        scroll.addView(content);
        root.addView(scroll, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        // Backdrop dims as you scroll down to episodes and details.
        scroll.setOnScrollChangeListener((v, x, y, ox, oy) -> dimmer.setAlpha(Math.min(0.75f, y / (float) Ui.dp(this, 380))));
        setContentView(root);

        // ---- hero block, sitting in the lower-left of the first screen
        LinearLayout hero = Ui.vertical(this);
        hero.setGravity(Gravity.BOTTOM);
        hero.setPadding(0, 0, Ui.dp(this, 64), Ui.dp(this, 28));
        hero.setMinimumHeight(Math.max(Ui.dp(this, 300), getResources().getDisplayMetrics().heightPixels - Ui.dp(this, 120)));

        if (badge != null && !badge.isEmpty()) {
            TextView own = Ui.eyebrow(this, "In your " + badge + " library");
            own.setPadding(0, 0, 0, Ui.dp(this, 10));
            hero.addView(own);
        }
        logo = new ImageView(this);
        logo.setAdjustViewBounds(true);
        logo.setScaleType(ImageView.ScaleType.FIT_START);
        logo.setMaxHeight(Ui.dp(this, 96));
        logo.setMaxWidth(Ui.dp(this, 380));
        logo.setVisibility(View.GONE);
        hero.addView(logo, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        title = Ui.heading(this, getIntent().getStringExtra("name"), 42);
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        hero.addView(title);

        chips = Ui.horizontal(this);
        chips.setGravity(Gravity.CENTER_VERTICAL);
        chips.setPadding(0, Ui.dp(this, 14), 0, Ui.dp(this, 14));
        hero.addView(chips);

        description = Ui.text(this, "Loading…", 15, Ui.alpha(Ui.TEXT, 0.86f));
        description.setMaxLines(4);
        description.setMaxWidth(Ui.dp(this, 640));
        description.setEllipsize(TextUtils.TruncateAt.END);
        description.setLineSpacing(0, 1.3f);
        hero.addView(description);
        credits = Ui.text(this, "", 12, Ui.MUTED);
        credits.setPadding(0, Ui.dp(this, 12), 0, 0);
        credits.setMaxLines(2);
        hero.addView(credits);

        buttons = Ui.horizontal(this);
        buttons.setPadding(0, Ui.dp(this, 22), Ui.dp(this, 40), Ui.dp(this, 6));
        android.widget.HorizontalScrollView buttonScroll = new FocusScroll.Horizontal(this);
        buttonScroll.addView(buttons);
        hero.addView(buttonScroll);
        content.addView(hero, new LinearLayout.LayoutParams(Ui.dp(this, 860), ViewGroup.LayoutParams.WRAP_CONTENT));

        // ---- episodes
        LinearLayout epGroup = Ui.vertical(this);
        epGroup.setTag("group");
        epGroup.setClipChildren(false);
        content.addView(epGroup);
        episodesHeader = sectionTitle("Episodes");
        epGroup.addView(episodesHeader);
        seasonsBar = Ui.horizontal(this);
        epGroup.addView(seasonsBar);
        episodes = Ui.row(this, epGroup, "");

        // ---- more like this
        LinearLayout simGroup = Ui.vertical(this);
        simGroup.setTag("group");
        simGroup.setClipChildren(false);
        content.addView(simGroup);
        similarHeader = sectionTitle("More like this");
        simGroup.addView(similarHeader);
        similarRow = Ui.row(this, simGroup, "");

        // ---- about
        aboutHeader = sectionTitle("About");
        content.addView(aboutHeader);
        about = Ui.vertical(this);
        content.addView(about, new LinearLayout.LayoutParams(Ui.dp(this, 760), ViewGroup.LayoutParams.WRAP_CONTENT));

        load();
    }

    private TextView sectionTitle(String s) {
        TextView t = Ui.heading(this, s, 18);
        t.setPadding(0, Ui.dp(this, 30), 0, Ui.dp(this, 6));
        t.setVisibility(View.GONE);
        return t;
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (meta != null) {   // refresh resume / watched markers and favorite state
            renderButtons();
            if (isSeries()) renderSeasons();
        }
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
        if (!bg.isEmpty()) ImageLoader.load(backdrop, bg, Ui.dp(this, 1100));
        String logoUrl = meta.optString("logo");
        if (!logoUrl.isEmpty()) {
            ImageLoader.load(logo, logoUrl, Ui.dp(this, 380), () -> {
                logo.setVisibility(View.VISIBLE);
                title.setVisibility(View.GONE);
            });
        }

        // Chips: IMDb rating, year, runtime, seasons, genres.
        chips.removeAllViews();
        if (!meta.optString("imdbRating").isEmpty()) {
            LinearLayout r = Ui.horizontal(this);
            r.setGravity(Gravity.CENTER_VERTICAL);
            TextView imdb = Ui.chip(this, "IMDb", Color.parseColor("#F5C518"), Color.BLACK);
            imdb.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
            r.addView(imdb);
            TextView score = Ui.heading(this, meta.optString("imdbRating"), 15);
            score.setPadding(0, 0, Ui.dp(this, 14), 0);
            r.addView(score);
            chips.addView(r);
        }
        List<String> bits = new ArrayList<>();
        String year = meta.optString("releaseInfo", meta.optString("year"));
        if (!year.isEmpty()) bits.add(year);
        if (!meta.optString("runtime").isEmpty()) bits.add(meta.optString("runtime"));
        if (isSeries()) {
            int n = 0;
            for (int k : seasons().keySet()) if (k > 0) n++;
            if (n > 0) bits.add(n + (n == 1 ? " season" : " seasons"));
        }
        TextView facts = Ui.text(this, TextUtils.join("   ·   ", bits), 14, Ui.TEXT);
        facts.setPadding(0, 0, Ui.dp(this, 14), 0);
        chips.addView(facts);
        JSONArray genres = genres();
        if (genres != null) for (int i = 0; i < genres.length() && i < 3; i++) {
            chips.addView(Ui.chip(this, genres.optString(i), Ui.alpha(Color.WHITE, 0.12f), Ui.TEXT));
        }

        description.setText(meta.optString("description"));
        JSONArray castArr = meta.optJSONArray("cast");
        StringBuilder people = new StringBuilder();
        if (castArr != null && castArr.length() > 0) people.append("Starring  ").append(join(castArr, ", ", 4));
        JSONArray dir = meta.optJSONArray("director");
        if (dir != null && dir.length() > 0) people.append(people.length() > 0 ? "      " : "").append("Director  ").append(join(dir, ", ", 2));
        credits.setText(people);

        renderButtons();
        if (isSeries()) renderSeasons();
        renderAbout();
        loadSimilar();
    }

    private JSONArray genres() {
        JSONArray g = meta.optJSONArray("genres");
        return g != null ? g : meta.optJSONArray("genre");
    }

    // ------------------------------------------------------------ Radarr / Sonarr

    private Arr.Status arrStatus;
    private boolean arrLoading, arrMissing;

    private String arrLabel(Arr arr) {
        if (arrLoading && arrStatus == null) return arr.name + "…";
        if (arrMissing) return arr.name + ": not found";
        if (arrStatus == null) return "Add to " + arr.name;
        if (!arrStatus.added) return "Add to " + arr.name;
        return arr.name + " · " + arrStatus.text;
    }

    private void loadArrStatus(Arr arr) {
        arrLoading = true;
        String mid = meta.optString("id", id), name = meta.optString("name"), year = meta.optString("releaseInfo");
        App.async(() -> {
            Arr.Status st = arr.status(mid, name, year);
            return st == null ? new Arr.Status() : st;
        }, st -> {
            arrLoading = false;
            arrMissing = st.item == null;
            arrStatus = st.item == null ? null : st;
            View b = buttons.findViewWithTag("arr");
            if (b instanceof android.widget.Button) ((android.widget.Button) b).setText(arrLabel(arr));
        }, e -> {
            arrLoading = false;
            View b = buttons.findViewWithTag("arr");
            if (b instanceof android.widget.Button) ((android.widget.Button) b).setText(arr.name + ": can't connect");
        });
    }

    private void arrClicked(Arr arr) {
        if (arrLoading) return;
        if (arrStatus == null) {
            arrStatus = null;
            arrMissing = false;
            loadArrStatus(arr);
            return;
        }
        if (!arrStatus.added) {
            chooseArrDefaults(arr, () -> {
                Toast.makeText(this, "Adding to " + arr.name + "…", Toast.LENGTH_SHORT).show();
                Arr.Status st = arrStatus;
                App.async(() -> { arr.add(st.item, arr.profile(), arr.root()); return true; }, ok -> {
                    Toast.makeText(this, "Added to " + arr.name + ". It's searching for downloads now.", Toast.LENGTH_LONG).show();
                    arrStatus = null;
                    loadArrStatus(arr);
                }, e -> Toast.makeText(this, arr.name + ": " + App.message(e), Toast.LENGTH_LONG).show());
            });
            return;
        }
        Arr.Status st = arrStatus;
        new android.app.AlertDialog.Builder(this)
                .setTitle(meta.optString("name") + " in " + arr.name)
                .setMessage(st.text + (st.monitored ? " · monitored" : " · not monitored"))
                .setPositiveButton("Search again", (d, w) -> App.async(() -> { arr.search(st.item); return true; },
                        ok -> Toast.makeText(this, arr.name + " is searching", Toast.LENGTH_SHORT).show(),
                        e -> Toast.makeText(this, arr.name + ": " + App.message(e), Toast.LENGTH_LONG).show()))
                .setNeutralButton("Downloads", (d, w) -> startActivity(new Intent(this, DownloadsActivity.class)))
                .setNegativeButton("Close", null)
                .show();
    }

    /** Makes sure a quality profile and folder are picked (asks once, then remembers). */
    private void chooseArrDefaults(Arr arr, Runnable then) {
        if (!arr.profile().isEmpty() && !arr.root().isEmpty()) {
            then.run();
            return;
        }
        App.async(() -> new Object[]{arr.profiles(), arr.rootFolders()}, r -> {
            @SuppressWarnings("unchecked") java.util.List<String[]> profiles = (java.util.List<String[]>) r[0];
            @SuppressWarnings("unchecked") java.util.List<String> roots = (java.util.List<String>) r[1];
            if (profiles.isEmpty() || roots.isEmpty()) {
                Toast.makeText(this, arr.name + " needs a quality profile and a root folder first", Toast.LENGTH_LONG).show();
                return;
            }
            Runnable pickRoot = () -> {
                if (roots.size() == 1 || !arr.root().isEmpty()) {
                    if (arr.root().isEmpty()) arr.setDefaults(null, roots.get(0));
                    then.run();
                    return;
                }
                new android.app.AlertDialog.Builder(this).setTitle("Save " + (isSeries() ? "shows" : "movies") + " to")
                        .setItems(roots.toArray(new String[0]), (d, w) -> { arr.setDefaults(null, roots.get(w)); then.run(); })
                        .show();
            };
            if (profiles.size() == 1 || !arr.profile().isEmpty()) {
                if (arr.profile().isEmpty()) arr.setDefaults(profiles.get(0)[0], null);
                pickRoot.run();
                return;
            }
            String[] names = new String[profiles.size()];
            for (int i = 0; i < names.length; i++) names[i] = profiles.get(i)[1];
            new android.app.AlertDialog.Builder(this).setTitle("Quality (you can change this in Settings)")
                    .setItems(names, (d, w) -> { arr.setDefaults(profiles.get(w)[0], null); pickRoot.run(); })
                    .show();
        }, e -> Toast.makeText(this, arr.name + ": " + App.message(e), Toast.LENGTH_LONG).show());
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

        boolean fav = Store.inLibrary(meta.optString("id", id));
        buttons.addView(Ui.button(this, fav ? R.drawable.ic_heart_filled : R.drawable.ic_heart, fav ? "Favorited" : "Favorite", v -> {
            JSONObject m = meta;
            try {
                if (badge != null && !badge.isEmpty()) m.put("badge", badge);
                if (serverId != null && !serverId.isEmpty()) m.put("serverId", serverId);
            } catch (Exception ignored) { }
            Trakt.favoriteChanged(m, Store.toggleLibrary(m));
            renderButtons();
            View b = buttons.getChildAt(isSeries() && nextEpisode() == null ? 0 : 1);
            if (b != null) b.requestFocus();
        }));

        String metaId = meta.optString("id", id);
        if (Tmdb.canCredit(metaId)) {
            String t = isSeries() ? "series" : "movie";
            buttons.addView(Ui.button(this, R.drawable.ic_people, "Cast & Crew",
                    v -> CreditsActivity.open(this, metaId, t, meta.optString("name"))));
        }
        String serviceKey = getIntent().getStringExtra("service");
        if (serviceKey != null && !serviceKey.isEmpty()) {
            App.async(() -> Tmdb.service(serviceKey), s -> {
                if (s == null || buttons.findViewWithTag("svc") != null) return;
                for (String pkg : s.packages) {
                    if (Apps.isInstalled(this, pkg)) {
                        View b = Ui.button(this, R.drawable.ic_apps, "Open " + s.name, v -> Apps.launch(this, pkg));
                        b.setTag("svc");
                        buttons.addView(b);
                        break;
                    }
                }
            }, null);
        }

        Arr arr = Arr.forType(isSeries() ? "series" : "movie");
        if (arr.configured()) {
            android.widget.Button ab = Ui.button(this, R.drawable.ic_download, arrLabel(arr), v -> arrClicked(arr));
            ab.setTag("arr");
            buttons.addView(ab);
            if (arrStatus == null && !arrLoading) loadArrStatus(arr);
        }

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

    // ------------------------------------------------------------ about + similar

    private void renderAbout() {
        about.removeAllViews();
        List<String[]> rows = new ArrayList<>();
        String released = meta.optString("released");
        if (released.length() >= 10) rows.add(new String[]{"Released", released.substring(0, 10)});
        else if (!meta.optString("releaseInfo").isEmpty()) rows.add(new String[]{"Released", meta.optString("releaseInfo")});
        if (!meta.optString("runtime").isEmpty()) rows.add(new String[]{"Runtime", meta.optString("runtime")});
        JSONArray g = genres();
        if (g != null && g.length() > 0) rows.add(new String[]{"Genres", join(g, ", ", 6)});
        JSONArray dir = meta.optJSONArray("director");
        if (dir != null && dir.length() > 0) rows.add(new String[]{"Director", join(dir, ", ", 4)});
        JSONArray writer = meta.optJSONArray("writer");
        if (writer != null && writer.length() > 0) rows.add(new String[]{"Writers", join(writer, ", ", 4)});
        JSONArray cast = meta.optJSONArray("cast");
        if (cast != null && cast.length() > 0) rows.add(new String[]{"Cast", join(cast, ", ", 10)});
        if (!meta.optString("country").isEmpty()) rows.add(new String[]{"Country", meta.optString("country")});
        if (!meta.optString("awards").isEmpty()) rows.add(new String[]{"Awards", meta.optString("awards")});
        if (badge != null && !badge.isEmpty()) rows.add(new String[]{"Library", "Available on your " + badge + " server"});
        for (String[] r : rows) {
            LinearLayout line = Ui.horizontal(this);
            line.setPadding(0, Ui.dp(this, 7), 0, Ui.dp(this, 7));
            TextView k = Ui.text(this, r[0], 13, Ui.DIM);
            line.addView(k, new LinearLayout.LayoutParams(Ui.dp(this, 120), ViewGroup.LayoutParams.WRAP_CONTENT));
            TextView v = Ui.text(this, r[1], 13, Ui.TEXT);
            line.addView(v, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            about.addView(line);
            View rule = new View(this);
            rule.setBackgroundColor(Ui.alpha(Color.WHITE, 0.07f));
            about.addView(rule, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));
        }
        aboutHeader.setVisibility(rows.isEmpty() ? View.GONE : View.VISIBLE);
        // A focusable spacer so the remote can scroll down to read this section.
        if (!rows.isEmpty()) {
            Button top = Ui.button(this, "Back to top", v -> {
                View b = buttons.getChildAt(0);
                if (b != null) b.requestFocus();
            });
            ((LinearLayout.LayoutParams) top.getLayoutParams()).setMargins(0, Ui.dp(this, 18), 0, 0);
            about.addView(top);
        }
    }

    private void loadSimilar() {
        if (similarLoaded) return;
        similarLoaded = true;
        JSONArray g = genres();
        String genre = g != null && g.length() > 0 ? g.optString(0) : "";
        String t = isSeries() ? "series" : "movie";
        String self = meta.optString("id", id);
        App.async(() -> Addons.similar(t, genre, self), list -> {
            similarRow.removeAllViews();
            for (JSONObject m : list) similarRow.addView(Ui.poster(this, m, v -> open(this, m), null));
            similarHeader.setVisibility(list.isEmpty() ? View.GONE : View.VISIBLE);
        }, null);
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
            String epBadge = done ? "Watched" : ep.optString("id").equals(nextId) ? "Up next" : "";
            String released = ep.optString("released");
            String sub = p != null && !done && p.optLong("pos") > 30000 ? Ui.left(p.optLong("pos"), p.optLong("dur"))
                    : released.length() >= 10 ? released.substring(0, 10) : "";
            int n = ep.optInt("episode", ep.optInt("number"));
            String name = ep.optString("name", ep.optString("title"));
            String img = ep.optString("thumbnail");
            if (img.isEmpty()) img = meta.optString("background", meta.optString("poster"));
            episodes.addView(Ui.landscape(this, img, n + " · " + (name.isEmpty() ? "Episode " + n : name), sub, epBadge,
                    progress, 220, v -> openStreams(ep.optString("id"), episodeLabel(ep)), null));
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
