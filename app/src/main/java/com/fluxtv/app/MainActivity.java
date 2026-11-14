package com.fluxtv.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/** Home screen and TV launcher: icon rail, a featured area that follows focus, and blended rows. */
public class MainActivity extends Activity {

    private LinearLayout rail, rows;
    private ScrollView scroll;
    private TextView clock, status, avatar;
    private ImageView heroImage;
    private TextView heroEyebrow, heroTitle, heroMeta, heroText;
    private View homeItem;
    private int loadedVersion = -1, themeVersion = -1;
    private String loadedProfile = "";
    private JSONObject heroItem;

    private final Runnable clockTick = new Runnable() {
        @Override public void run() {
            clock.setText(DateFormat.getTimeFormat(MainActivity.this).format(new Date()));
            App.MAIN.postDelayed(this, 20_000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        themeVersion = Theme.version();

        LinearLayout root = Ui.horizontal(this);
        root.setBackgroundColor(Ui.BG);
        buildRail();
        root.addView(rail, new LinearLayout.LayoutParams(Ui.dp(this, 76), ViewGroup.LayoutParams.MATCH_PARENT));

        FrameLayout main = new FrameLayout(this);
        root.addView(main, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        // Featured backdrop, top-right, fading into the background.
        heroImage = new ImageView(this);
        heroImage.setScaleType(ImageView.ScaleType.CENTER_CROP);
        FrameLayout.LayoutParams hp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 330), Gravity.TOP | Gravity.END);
        hp.setMargins(Ui.dp(this, 220), 0, 0, 0);
        main.addView(heroImage, hp);
        View scrimLeft = new View(this);
        scrimLeft.setBackground(Ui.scrim(GradientDrawable.Orientation.LEFT_RIGHT, Ui.BG, 1f));
        FrameLayout.LayoutParams sl = new FrameLayout.LayoutParams(Ui.dp(this, 520), Ui.dp(this, 330), Gravity.TOP | Gravity.START);
        sl.setMargins(Ui.dp(this, 220), 0, 0, 0);
        main.addView(scrimLeft, sl);
        View fillLeft = new View(this);
        fillLeft.setBackgroundColor(Ui.BG);
        main.addView(fillLeft, new FrameLayout.LayoutParams(Ui.dp(this, 221), Ui.dp(this, 330)));
        View scrimBottom = new View(this);
        scrimBottom.setBackground(Ui.scrim(GradientDrawable.Orientation.BOTTOM_TOP, Ui.BG, 1f));
        FrameLayout.LayoutParams sb = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 150), Gravity.TOP);
        sb.setMargins(0, Ui.dp(this, 182), 0, 0);
        main.addView(scrimBottom, sb);

        View scrimTop = new View(this);
        scrimTop.setBackground(Ui.scrim(GradientDrawable.Orientation.TOP_BOTTOM, Ui.BG, 0.85f));
        main.addView(scrimTop, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 90), Gravity.TOP));

        // Top bar: greeting, sources, clock.
        LinearLayout top = Ui.horizontal(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(Ui.dp(this, 40), Ui.dp(this, 22), Ui.dp(this, 36), 0);
        status = Ui.text(this, "", 12, Ui.MUTED);
        top.addView(status, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        clock = Ui.heading(this, "", 15);
        top.addView(clock);
        main.addView(top, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Featured text block.
        LinearLayout hero = Ui.vertical(this);
        hero.setPadding(Ui.dp(this, 40), Ui.dp(this, 70), 0, 0);
        heroEyebrow = Ui.eyebrow(this, "");
        heroTitle = Ui.single(Ui.heading(this, "Flux", 38));
        heroTitle.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 8));
        heroMeta = Ui.single(Ui.text(this, "", 13, Ui.MUTED));
        heroText = Ui.text(this, "", 14, Ui.alpha(Ui.TEXT, 0.82f));
        heroText.setMaxLines(3);
        heroText.setEllipsize(TextUtils.TruncateAt.END);
        heroText.setPadding(0, Ui.dp(this, 10), 0, 0);
        hero.addView(heroEyebrow);
        hero.addView(heroTitle);
        hero.addView(heroMeta);
        hero.addView(heroText);
        main.addView(hero, new FrameLayout.LayoutParams(Ui.dp(this, 520), ViewGroup.LayoutParams.WRAP_CONTENT));

        // Rows, below the featured area.
        scroll = new ScrollView(this);
        scroll.setClipChildren(false);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setFillViewport(true);
        rows = Ui.vertical(this);
        rows.setClipChildren(false);
        rows.setPadding(Ui.dp(this, 40), 0, 0, Ui.dp(this, 160));
        scroll.addView(rows);
        FrameLayout.LayoutParams rp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        rp.setMargins(0, Ui.dp(this, 250), 0, 0);
        main.addView(scroll, rp);

        setContentView(root);
    }

    // ------------------------------------------------------------ rail

    private void buildRail() {
        rail = Ui.vertical(this);
        rail.setGravity(Gravity.CENTER_HORIZONTAL);
        rail.setBackgroundColor(Ui.PANEL);
        rail.setPadding(0, Ui.dp(this, 22), 0, Ui.dp(this, 18));

        TextView logo = Ui.heading(this, "▶", 16);
        logo.setGravity(Gravity.CENTER);
        logo.setTextColor(Ui.BG);
        logo.setBackground(Ui.rounded(this, Ui.ACCENT, 0, 10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(this, 36), Ui.dp(this, 36));
        lp.setMargins(0, 0, 0, Ui.dp(this, 22));
        rail.addView(logo, lp);

        homeItem = railItem(R.drawable.ic_home, "Home", true, v -> scroll.smoothScrollTo(0, 0));
        railItem(R.drawable.ic_search, "Search", false, v -> startActivity(new Intent(this, SearchActivity.class)));
        railItem(R.drawable.ic_addons, "Add-ons", false, v -> startActivity(new Intent(this, AddonsActivity.class)));
        railItem(R.drawable.ic_servers, "Servers", false, v -> startActivity(new Intent(this, ServersActivity.class)));
        railItem(R.drawable.ic_cloud, "Debrid", false, v -> startActivity(new Intent(this, AccountActivity.class)));
        railItem(R.drawable.ic_apps, "Apps", false, v -> startActivity(new Intent(this, AppsActivity.class)));
        View spacer = new View(this);
        rail.addView(spacer, new LinearLayout.LayoutParams(1, 0, 1f));
        railItem(R.drawable.ic_settings, "Settings", false, v -> startActivity(new Intent(this, SettingsActivity.class)));

        avatar = Ui.heading(this, "", 15);
        avatar.setGravity(Gravity.CENTER);
        avatar.setFocusable(true);
        avatar.setClickable(true);
        avatar.setContentDescription("Profile");
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_focused}, Ui.rounded(this, Ui.TEXT, Ui.ACCENT, 999));
        s.addState(new int[]{}, Ui.rounded(this, Ui.BUTTON, 0, 999));
        avatar.setBackground(s);
        avatar.setTextColor(Ui.onFocusDark(Ui.TEXT));
        avatar.setOnClickListener(v -> chooseProfile());
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(Ui.dp(this, 38), Ui.dp(this, 38));
        ap.setMargins(0, Ui.dp(this, 10), 0, 0);
        rail.addView(avatar, ap);
    }

    private View railItem(int icon, String label, boolean current, View.OnClickListener click) {
        LinearLayout item = Ui.vertical(this);
        item.setGravity(Gravity.CENTER);
        item.setFocusable(true);
        item.setClickable(true);
        item.setOnClickListener(click);
        item.setContentDescription(label);
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_focused}, Ui.rounded(this, Ui.TEXT, 0, 12));
        s.addState(new int[]{}, current ? Ui.rounded(this, Ui.BUTTON, 0, 12) : Ui.rounded(this, Color.TRANSPARENT, 0, 12));
        item.setBackground(s);
        ColorStateList tint = Ui.onFocusDark(current ? Ui.TEXT : Ui.DIM);
        ImageView ic = Ui.icon(this, icon, 20, Ui.TEXT);
        ic.setColorFilter(null);
        ic.setImageTintList(tint);
        ic.setImageTintMode(PorterDuff.Mode.SRC_IN);
        ic.setDuplicateParentStateEnabled(true);
        item.addView(ic);
        TextView t = Ui.text(this, label, 9, Ui.DIM);
        t.setGravity(Gravity.CENTER);
        t.setSingleLine(true);
        t.setTextColor(tint);
        t.setPadding(0, Ui.dp(this, 4), 0, 0);
        t.setDuplicateParentStateEnabled(true);
        item.addView(t, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(this, 60), Ui.dp(this, 54));
        lp.setMargins(0, Ui.dp(this, 3), 0, Ui.dp(this, 3));
        rail.addView(item, lp);
        return item;
    }

    // ------------------------------------------------------------ lifecycle

    @Override
    protected void onResume() {
        super.onResume();
        if (themeVersion != Theme.version()) {   // colours changed in Settings
            recreate();
            return;
        }
        String name = Store.profile();
        avatar.setText(name.isEmpty() ? "?" : name.substring(0, 1).toUpperCase());
        updateStatus();
        if (Store.addonsVersion() != loadedVersion || !name.equals(loadedProfile)) load();
        App.MAIN.removeCallbacks(clockTick);
        clockTick.run();
        Updater.check(this, false);   // automatic update check on every reload of the home screen
        showLastCrash();
        if (getCurrentFocus() == null) homeItem.post(homeItem::requestFocus);
    }

    @Override
    protected void onPause() {
        super.onPause();
        App.MAIN.removeCallbacks(clockTick);
    }

    @Override
    public void onBackPressed() {
        // We may be the TV's home screen, so never close; jump to the menu instead.
        homeItem.requestFocus();
        scroll.smoothScrollTo(0, 0);
    }

    private void updateStatus() {
        List<String> parts = new ArrayList<>();
        for (MediaServer s : Store.servers()) parts.add("● " + s.name);
        if (RealDebrid.isSignedIn()) parts.add("● Real-Debrid");
        String hello = "Hi, " + Store.profile();
        status.setText(parts.isEmpty() ? hello : hello + "      " + TextUtils.join("     ", parts));
    }

    // ------------------------------------------------------------ content

    private void load() {
        loadedVersion = Store.addonsVersion();
        loadedProfile = Store.profile();
        rows.removeAllViews();
        TextView loading = Ui.text(this, "Loading your movies and shows…", 14, Ui.MUTED);
        loading.setPadding(0, Ui.dp(this, 40), 0, 0);
        rows.addView(loading);
        App.async(Blend::home, this::render, e -> {
            rows.removeAllViews();
            rows.addView(Ui.text(this, "Couldn't load: " + App.message(e), 14, Ui.MUTED));
        });
    }

    private void render(Blend.Home home) {
        rows.removeAllViews();
        View firstCard = null;

        if (!home.continueWatching.isEmpty()) {
            LinearLayout row = Ui.row(this, rows, "Continue watching");
            for (JSONObject p : home.continueWatching) {
                String img = !p.optString("still").isEmpty() ? p.optString("still")
                        : !p.optString("background").isEmpty() ? p.optString("background") : p.optString("poster");
                View card = Ui.landscape(this, img, p.optString("name"), p.optString("sub"), p.optString("badge"),
                        p.optDouble("progress", 0), 196, v -> DetailActivity.open(this, p), () -> feature(p, "Continue watching"));
                row.addView(card);
                if (firstCard == null) firstCard = card;
            }
        }
        List<JSONObject> watchlist = Store.library();
        if (!watchlist.isEmpty()) {
            LinearLayout row = Ui.row(this, rows, "My watchlist");
            for (JSONObject p : watchlist) {
                View card = Ui.poster(this, p, v -> DetailActivity.open(this, p), () -> feature(p, "In your watchlist"));
                row.addView(card);
                if (firstCard == null) firstCard = card;
            }
        }
        for (Addons.Row r : home.rows) {
            LinearLayout row = Ui.row(this, rows, r.title, r.note);
            String eyebrow = r.title;
            for (JSONObject p : r.items) {
                View card = Ui.poster(this, p, v -> DetailActivity.open(this, p), () -> feature(p, eyebrow));
                row.addView(card);
                if (firstCard == null) firstCard = card;
            }
        }
        if (rows.getChildCount() == 0) {
            TextView t = Ui.text(this, "Nothing here yet. Add streaming add-ons or a Plex / Jellyfin / Emby server "
                    + "from the menu on the left.", 15, Ui.MUTED);
            t.setPadding(0, Ui.dp(this, 40), Ui.dp(this, 40), 0);
            rows.addView(t);
        }
        JSONObject first = !home.continueWatching.isEmpty() ? home.continueWatching.get(0)
                : !home.rows.isEmpty() && !home.rows.get(0).items.isEmpty() ? home.rows.get(0).items.get(0) : null;
        if (first != null) feature(first, home.continueWatching.isEmpty() ? home.rows.get(0).title : "Continue watching");
    }

    /** Shows the focused title in the featured area. */
    private void feature(JSONObject p, String eyebrow) {
        if (p == heroItem) return;
        heroItem = p;
        String label = p.optString("label");
        heroEyebrow.setText((eyebrow + (label.isEmpty() ? "" : " · " + label)).toUpperCase());
        heroTitle.setText(p.optString("name"));
        List<String> meta = new ArrayList<>();
        if (!p.optString("releaseInfo").isEmpty()) meta.add(p.optString("releaseInfo"));
        if (!p.optString("imdbRating").isEmpty()) meta.add("★ " + p.optString("imdbRating"));
        JSONArray genres = p.optJSONArray("genres");
        if (genres == null) genres = p.optJSONArray("genre");
        if (genres != null && genres.length() > 0) {
            List<String> g = new ArrayList<>();
            for (int i = 0; i < genres.length() && i < 3; i++) g.add(genres.optString(i));
            meta.add(TextUtils.join(", ", g));
        }
        if (!p.optString("badge").isEmpty()) meta.add("On your " + p.optString("badge"));
        heroMeta.setText(TextUtils.join("   ·   ", meta));
        heroText.setText(p.optString("description"));
        String bg = p.optString("background");
        heroImage.animate().cancel();
        heroImage.setAlpha(0.35f);
        ImageLoader.load(heroImage, bg.isEmpty() ? p.optString("poster") : bg, Ui.dp(this, 900));
        heroImage.animate().alpha(1f).setDuration(260).start();
    }

    // ------------------------------------------------------------ dialogs

    /** If Flux crashed last time, show the error so it can be reported. */
    private void showLastCrash() {
        String crash = Store.get("last_crash");
        if (crash.isEmpty()) return;
        Store.put("last_crash", "");
        TextView t = Ui.text(this, crash, 12, Ui.TEXT);
        t.setPadding(Ui.dp(this, 16), Ui.dp(this, 8), Ui.dp(this, 16), Ui.dp(this, 8));
        ScrollView sv = new ScrollView(this);
        sv.addView(t);
        new AlertDialog.Builder(this)
                .setTitle("Flux TV hit an error last time")
                .setView(sv)
                .setPositiveButton("OK", null)
                .show();
    }

    private void chooseProfile() {
        List<String> profiles = Store.profiles();
        String[] items = new String[profiles.size() + 1];
        for (int i = 0; i < profiles.size(); i++) items[i] = profiles.get(i);
        items[profiles.size()] = "+ Add profile";
        new AlertDialog.Builder(this)
                .setTitle("Who's watching?")
                .setItems(items, (d, which) -> {
                    if (which < profiles.size()) {
                        switchProfile(profiles.get(which));
                    } else {
                        EditText input = new EditText(this);
                        input.setSingleLine(true);
                        new AlertDialog.Builder(this)
                                .setTitle("Profile name")
                                .setView(input)
                                .setPositiveButton("Add", (d2, w2) -> {
                                    String name = input.getText().toString().trim();
                                    if (name.isEmpty()) return;
                                    Store.addProfile(name);
                                    switchProfile(name);
                                })
                                .setNegativeButton("Cancel", null)
                                .show();
                    }
                })
                .show();
    }

    private void switchProfile(String name) {
        Store.setProfile(name);
        avatar.setText(name.substring(0, 1).toUpperCase());
        updateStatus();
        load();
    }
}
