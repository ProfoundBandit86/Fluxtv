package com.fluxtv.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.view.FocusFinder;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Home screen and TV launcher. A sidebar that hides off-screen (press ← at the left edge),
 * a featured area that follows focus, and sections: Home, Movies, TV Shows, Favorites.
 * Home: Continue watching (everything), your apps, streaming services, then your rows.
 */
public class MainActivity extends Activity {

    private FrameLayout contentRoot;
    private LinearLayout drawer, rows;
    private View dim;
    private ScrollView scroll;
    private TextView clock, status, avatarText;
    private ImageView heroImage;
    private TextView heroEyebrow, heroTitle, heroMeta, heroText;
    private boolean drawerOpen;
    private View lastContentFocus;
    private final Map<String, View> drawerItems = new HashMap<>();

    private String section = "home";
    private Blend.Home home;
    private List<JSONObject> external = new ArrayList<>();
    private List<Addons.Row> appRows = new ArrayList<>();
    private List<Tmdb.Service> services = new ArrayList<>();
    private final Map<String, List<Addons.Row>> extraRows = new HashMap<>();
    private int loadedVersion = -1, themeVersion = -1, renderedChanges = -1, sectionGen = 0;
    private String loadedProfile = "";
    private JSONObject heroItem, firstItem;
    private String firstEyebrow = "";

    private LinearLayout appRow;
    private String movingApp;   // package being moved in the app row, or null

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

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Ui.BG);
        contentRoot = new FrameLayout(this);
        root.addView(contentRoot, new FrameLayout.LayoutParams(-1, -1));
        buildContent();

        dim = new View(this);
        dim.setBackgroundColor(Ui.alpha(Color.BLACK, 0.55f));
        dim.setAlpha(0f);
        dim.setVisibility(View.GONE);
        root.addView(dim, new FrameLayout.LayoutParams(-1, -1));
        buildDrawer();
        root.addView(drawer, new FrameLayout.LayoutParams(Ui.dp(this, 250), ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);
    }

    // ------------------------------------------------------------ layout

    private void buildContent() {
        heroImage = new ImageView(this);
        heroImage.setScaleType(ImageView.ScaleType.CENTER_CROP);
        FrameLayout.LayoutParams hp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 340), Gravity.TOP | Gravity.END);
        hp.setMargins(Ui.dp(this, 260), 0, 0, 0);
        contentRoot.addView(heroImage, hp);
        View scrimLeft = new View(this);
        scrimLeft.setBackground(Ui.scrim(GradientDrawable.Orientation.LEFT_RIGHT, Ui.BG, 1f));
        FrameLayout.LayoutParams sl = new FrameLayout.LayoutParams(Ui.dp(this, 520), Ui.dp(this, 340));
        sl.setMargins(Ui.dp(this, 260), 0, 0, 0);
        contentRoot.addView(scrimLeft, sl);
        View fillLeft = new View(this);
        fillLeft.setBackgroundColor(Ui.BG);
        contentRoot.addView(fillLeft, new FrameLayout.LayoutParams(Ui.dp(this, 261), Ui.dp(this, 340)));
        View scrimBottom = new View(this);
        scrimBottom.setBackground(Ui.scrim(GradientDrawable.Orientation.BOTTOM_TOP, Ui.BG, 1f));
        FrameLayout.LayoutParams sb = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 160), Gravity.TOP);
        sb.setMargins(0, Ui.dp(this, 181), 0, 0);
        contentRoot.addView(scrimBottom, sb);
        View scrimTop = new View(this);
        scrimTop.setBackground(Ui.scrim(GradientDrawable.Orientation.TOP_BOTTOM, Ui.BG, 0.85f));
        contentRoot.addView(scrimTop, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 90), Gravity.TOP));

        LinearLayout top = Ui.horizontal(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(Ui.dp(this, 56), Ui.dp(this, 22), Ui.dp(this, 40), 0);
        status = Ui.text(this, "", 12, Ui.MUTED);
        top.addView(status, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        clock = Ui.heading(this, "", 15);
        top.addView(clock);
        contentRoot.addView(top, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout hero = Ui.vertical(this);
        hero.setPadding(Ui.dp(this, 56), Ui.dp(this, 64), 0, 0);
        heroEyebrow = Ui.eyebrow(this, "");
        heroTitle = Ui.single(Ui.heading(this, "Flux", 36));
        heroTitle.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 8));
        heroMeta = Ui.single(Ui.text(this, "", 13, Ui.MUTED));
        heroText = Ui.text(this, "", 14, Ui.alpha(Ui.TEXT, 0.82f));
        heroText.setMaxLines(2);
        heroText.setEllipsize(TextUtils.TruncateAt.END);
        heroText.setPadding(0, Ui.dp(this, 10), 0, 0);
        hero.addView(heroEyebrow);
        hero.addView(heroTitle);
        hero.addView(heroMeta);
        hero.addView(heroText);
        contentRoot.addView(hero, new FrameLayout.LayoutParams(Ui.dp(this, 600), ViewGroup.LayoutParams.WRAP_CONTENT));

        scroll = new FocusScroll.Vertical(this);
        rows = Ui.vertical(this);
        rows.setClipChildren(false);
        rows.setPadding(Ui.dp(this, 56), 0, 0, Ui.dp(this, 200));
        scroll.addView(rows);
        float heightDp = getResources().getDisplayMetrics().heightPixels / getResources().getDisplayMetrics().density;
        int rowsTop = heightDp >= 520 ? 236 : 186;
        if (rowsTop < 236) heroText.setMaxLines(1);
        FrameLayout.LayoutParams rp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        rp.setMargins(0, Ui.dp(this, rowsTop), 0, 0);
        contentRoot.addView(scroll, rp);
    }

    // ------------------------------------------------------------ sidebar

    private void buildDrawer() {
        drawer = Ui.vertical(this);
        drawer.setPadding(Ui.dp(this, 16), Ui.dp(this, 26), Ui.dp(this, 16), Ui.dp(this, 20));
        drawer.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{Ui.PANEL, Ui.alpha(Ui.PANEL, 0.97f)}));
        drawer.setElevation(Ui.dp(this, 12));
        drawer.setTranslationX(-Ui.dp(this, 250));
        drawer.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);

        TextView logo = Ui.heading(this, "Flux", 22);
        logo.setTextColor(Ui.ACCENT);
        logo.setPadding(Ui.dp(this, 14), 0, 0, Ui.dp(this, 18));
        drawer.addView(logo);

        drawerItem(R.drawable.ic_search, "Search", null, () -> startActivity(new Intent(this, SearchActivity.class).putExtra("voice", true)));
        drawerItem(R.drawable.ic_home, "Home", "home", () -> showSection("home"));
        drawerItem(R.drawable.ic_movie, "Movies", "movie", () -> showSection("movie"));
        drawerItem(R.drawable.ic_tv, "TV Shows", "series", () -> showSection("series"));
        drawerItem(R.drawable.ic_live, "Live TV", null, () -> startActivity(new Intent(this, LiveActivity.class)));
        drawerItem(R.drawable.ic_heart, "Favorites", "favorites", () -> showSection("favorites"));
        drawerItem(R.drawable.ic_apps, "Apps", null, () -> startActivity(new Intent(this, AppsActivity.class)));
        drawer.addView(new View(this), new LinearLayout.LayoutParams(1, 0, 1f));
        drawerItem(R.drawable.ic_sliders, "Quick settings", null, () -> startActivity(new Intent(this, QuickSettingsActivity.class)));
        if (new Intent(QuickSettingsActivity.SMART_HOME).resolveActivity(getPackageManager()) != null) {
            drawerItem(R.drawable.ic_ghome, "Google Home", null, () -> {
                try {
                    startActivity(new Intent(QuickSettingsActivity.SMART_HOME));
                } catch (Exception ignored) { }
            });
        }
        drawerItem(R.drawable.ic_settings, "Settings", null, () -> startActivity(new Intent(this, SettingsActivity.class)));

        LinearLayout profile = drawerRow();
        avatarText = Ui.heading(this, "", 13);
        avatarText.setGravity(Gravity.CENTER);
        avatarText.setBackground(Ui.rounded(this, Ui.ACCENT, 0, 999));
        avatarText.setTextColor(Ui.BG);
        profile.addView(avatarText, new LinearLayout.LayoutParams(Ui.dp(this, 26), Ui.dp(this, 26)));
        TextView pl = label("Profile");
        pl.setTag("profileLabel");
        profile.addView(pl);
        profile.setOnClickListener(v -> chooseProfile());
        drawer.addView(profile);
    }

    private LinearLayout drawerRow() {
        LinearLayout item = Ui.horizontal(this);
        item.setGravity(Gravity.CENTER_VERTICAL);
        item.setFocusable(true);
        item.setClickable(true);
        item.setPadding(Ui.dp(this, 14), 0, Ui.dp(this, 14), 0);
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_focused}, Ui.rounded(this, Ui.TEXT, 0, 12));
        s.addState(new int[]{android.R.attr.state_selected}, Ui.rounded(this, Ui.BUTTON, 0, 12));
        s.addState(new int[]{}, Ui.rounded(this, Color.TRANSPARENT, 0, 12));
        item.setBackground(s);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 44));
        lp.setMargins(0, Ui.dp(this, 2), 0, Ui.dp(this, 2));
        item.setLayoutParams(lp);
        return item;
    }

    private ColorStateList tint() {
        return new ColorStateList(new int[][]{{android.R.attr.state_focused}, {android.R.attr.state_selected}, {}},
                new int[]{Ui.BG, Ui.TEXT, Ui.MUTED});
    }

    private TextView label(String s) {
        TextView t = Ui.single(Ui.text(this, s, 15, Ui.MUTED));
        t.setTextColor(tint());
        t.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        t.setPadding(Ui.dp(this, 14), 0, 0, 0);
        t.setDuplicateParentStateEnabled(true);
        return t;
    }

    private void drawerItem(int icon, String text, String sectionKey, Runnable action) {
        LinearLayout item = drawerRow();
        ImageView ic = new ImageView(this);
        ic.setImageResource(icon);
        ic.setImageTintList(new ColorStateList(new int[][]{{android.R.attr.state_focused}, {android.R.attr.state_selected}, {}},
                new int[]{Ui.BG, Ui.ACCENT, Ui.MUTED}));
        ic.setImageTintMode(PorterDuff.Mode.SRC_IN);
        ic.setDuplicateParentStateEnabled(true);
        item.addView(ic, new LinearLayout.LayoutParams(Ui.dp(this, 20), Ui.dp(this, 20)));
        item.addView(label(text));
        item.setOnClickListener(v -> {
            closeDrawer(sectionKey != null);
            action.run();
        });
        if (sectionKey != null) drawerItems.put(sectionKey, item);
        drawer.addView(item);
    }

    private void openDrawer() {
        if (drawerOpen) return;
        drawerOpen = true;
        lastContentFocus = getCurrentFocus();
        drawer.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        contentRoot.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);   // focus stays in the menu
        drawer.animate().translationX(0).setDuration(220).setInterpolator(new DecelerateInterpolator()).start();
        dim.setVisibility(View.VISIBLE);
        dim.animate().alpha(1f).setDuration(220).start();
        View target = drawerItems.get(section);
        (target != null ? target : drawer.getChildAt(1)).requestFocus();
    }

    private void closeDrawer(boolean restoreFocus) {
        if (!drawerOpen) return;
        drawerOpen = false;
        drawer.animate().translationX(-Ui.dp(this, 250)).setDuration(200).setInterpolator(new DecelerateInterpolator()).start();
        dim.animate().alpha(0f).setDuration(200).withEndAction(() -> dim.setVisibility(View.GONE)).start();
        drawer.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        contentRoot.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        if (restoreFocus) focusContent();
    }

    private void focusContent() {
        View f = lastContentFocus;
        if (f != null && f.isAttachedToWindow() && f.isShown() && f.requestFocus()) return;
        View first = FocusFinder.getInstance().findNextFocus(rows, null, View.FOCUS_DOWN);
        if (first != null) first.requestFocus();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            int k = e.getKeyCode();
            if (movingApp != null) {
                if (k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    moveApp(k == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1);
                    return true;
                }
                if (k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER || k == KeyEvent.KEYCODE_BACK
                        || k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_DPAD_DOWN) {
                    stopMoving();
                    return true;
                }
            }
            if (drawerOpen && k == KeyEvent.KEYCODE_DPAD_RIGHT) {
                closeDrawer(true);
                return true;
            }
            if (drawerOpen && (k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_DPAD_DOWN)) {
                List<View> items = new ArrayList<>();
                for (int i = 0; i < drawer.getChildCount(); i++) if (drawer.getChildAt(i).isFocusable()) items.add(drawer.getChildAt(i));
                int at = items.indexOf(getCurrentFocus());
                int next = Math.max(0, Math.min(items.size() - 1, at + (k == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1)));
                if (!items.isEmpty()) items.get(at < 0 ? 0 : next).requestFocus();
                return true;
            }
            if (!drawerOpen && k == KeyEvent.KEYCODE_DPAD_LEFT) {
                View f = getCurrentFocus();
                if (f == null || FocusFinder.getInstance().findNextFocus(contentRoot, f, View.FOCUS_LEFT) == null) {
                    openDrawer();
                    return true;
                }
            }
        }
        return super.dispatchKeyEvent(e);
    }

    @Override
    public void onBackPressed() {
        // We may be the TV's home screen, so never close.
        if (drawerOpen) {
            closeDrawer(true);
        } else if (!section.equals("home")) {
            showSection("home");
        } else if (scroll.getScrollY() > 0) {
            scroll.smoothScrollTo(0, 0);
            lastContentFocus = null;
            focusContent();
        } else {
            openDrawer();
        }
    }

    // ------------------------------------------------------------ lifecycle

    @Override
    protected void onResume() {
        super.onResume();
        if (themeVersion != Theme.version()) {
            recreate();
            return;
        }
        String name = Store.profile();
        avatarText.setText(name.isEmpty() ? "?" : name.substring(0, 1).toUpperCase());
        View pl = drawer.findViewWithTag("profileLabel");
        if (pl instanceof TextView) ((TextView) pl).setText(name);
        updateStatus();
        if (Store.addonsVersion() != loadedVersion || !name.equals(loadedProfile)) {
            extraRows.clear();
            load();
        } else if (Store.changes() != renderedChanges) {
            renderSection();
        } else if (section.equals("home")) {
            refreshExternal();
        }
        App.MAIN.removeCallbacks(clockTick);
        clockTick.run();
        Trakt.syncIfDue(() -> { if (home != null) renderSection(); });
        Updater.check(this, false);
        showLastCrash();
        askWatchNextPermission();
        if (getCurrentFocus() == null && !drawerOpen) rows.post(this::focusContent);
    }

    @Override
    protected void onPause() {
        super.onPause();
        App.MAIN.removeCallbacks(clockTick);
        stopMoving();
    }

    private boolean askedPermission;

    private void askWatchNextPermission() {
        if (askedPermission || Build.VERSION.SDK_INT < 26 || WatchNext.allowed(this)) return;
        askedPermission = true;
        try {
            requestPermissions(new String[]{WatchNext.PERMISSION}, 7);
        } catch (Exception ignored) { }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        if (code == 7) refreshExternal();
    }

    private void updateStatus() {
        List<String> parts = new ArrayList<>();
        for (MediaServer s : Store.servers()) parts.add("● " + s.name);
        if (RealDebrid.isSignedIn()) parts.add("● Real-Debrid");
        if (!Iptv.sources().isEmpty()) parts.add("● IPTV");
        String hello = "Hi, " + Store.profile() + "   ·   ← for menu";
        status.setText(parts.isEmpty() ? hello : hello + "      " + TextUtils.join("     ", parts));
    }

    // ------------------------------------------------------------ data

    private void load() {
        loadedVersion = Store.addonsVersion();
        loadedProfile = Store.profile();
        rows.removeAllViews();
        TextView loading = Ui.text(this, "Loading…", 14, Ui.MUTED);
        loading.setPadding(0, Ui.dp(this, 40), 0, 0);
        rows.addView(loading);
        android.content.Context app = getApplicationContext();
        App.async(() -> {
            Blend.Home h = Blend.home();
            external = WatchNext.load(app);
            appRows = WatchNext.channels(app);
            services = Tmdb.services();
            return h;
        }, h -> {
            home = h;
            HomeLayout.setAvailable(allRows());
            renderSection();
            if (!drawerOpen) rows.post(this::focusContent);
        }, e -> {
            rows.removeAllViews();
            rows.addView(Ui.text(this, "Couldn't load: " + App.message(e), 14, Ui.MUTED));
        });
    }

    private void refreshExternal() {
        android.content.Context app = getApplicationContext();
        App.async(() -> {
            List<Addons.Row> ch = WatchNext.channels(app);
            return new Object[]{WatchNext.load(app), ch};
        }, r -> {
            @SuppressWarnings("unchecked") List<JSONObject> list = (List<JSONObject>) r[0];
            @SuppressWarnings("unchecked") List<Addons.Row> ch = (List<Addons.Row>) r[1];
            boolean changed = list.size() != external.size() || !WatchNext.sameRows(ch, appRows);
            external = list;
            appRows = ch;
            if (home != null) HomeLayout.setAvailable(allRows());
            if (changed && section.equals("home") && home != null) renderSection();
        }, null);
    }

    private void showSection(String key) {
        if (!key.equals(section)) {
            section = key;
            scroll.scrollTo(0, 0);
            heroItem = null;
            lastContentFocus = null;
            renderSection();
        }
        for (Map.Entry<String, View> e : drawerItems.entrySet()) e.getValue().setSelected(e.getKey().equals(section));
        rows.post(this::focusContent);
    }

    private void renderSection() {
        renderedChanges = Store.changes();
        for (Map.Entry<String, View> e : drawerItems.entrySet()) e.getValue().setSelected(e.getKey().equals(section));
        if (home == null) return;
        sectionGen++;
        rows.removeAllViews();
        firstItem = null;
        firstEyebrow = "";
        switch (section) {
            case "favorites": renderFavorites(); break;
            case "movie":
            case "series": renderType(section); break;
            default: renderHome(); break;
        }
        if (rows.getChildCount() == 0) {
            TextView t = Ui.text(this, section.equals("favorites")
                    ? "No favorites yet. Open any movie or show and press ♥ Favorite to keep it here."
                    : "Nothing here yet. Add sources in Settings.", 15, Ui.MUTED);
            t.setPadding(0, Ui.dp(this, 40), Ui.dp(this, 40), 0);
            rows.addView(t);
        }
        if (firstItem != null) {
            feature(firstItem, firstEyebrow);
        } else {
            heroTitle.setText(section.equals("favorites") ? "Favorites" : section.equals("movie") ? "Movies"
                    : section.equals("series") ? "TV Shows" : "Flux");
            heroEyebrow.setText("");
            heroMeta.setText("");
            heroText.setText("");
            heroImage.setImageDrawable(null);
        }
    }

    // ------------------------------------------------------------ rows

    private void note(JSONObject p, String eyebrow) {
        if (firstItem == null) {
            firstItem = p;
            firstEyebrow = eyebrow;
        }
    }

    private void continueRow(String type) {
        List<JSONObject> list = new ArrayList<>();
        for (JSONObject p : home.continueWatching) if (type == null || type.equals(p.optString("type"))) list.add(p);
        if (type == null) {
            list.addAll(external);
            Collections.sort(list, (a, b) -> Long.compare(b.optLong("t"), a.optLong("t")));
        }
        if (list.isEmpty()) return;
        LinearLayout row = Ui.row(this, rows, "Continue watching");
        for (JSONObject p : list) {
            boolean ext = "external".equals(p.optString("type"));
            String img = !p.optString("still").isEmpty() ? p.optString("still")
                    : !p.optString("background").isEmpty() ? p.optString("background") : p.optString("poster");
            row.addView(Ui.landscape(this, img, p.optString("name"), p.optString("sub"), p.optString("badge"),
                    p.optDouble("progress", 0), 196,
                    v -> { if (ext) WatchNext.open(this, p); else DetailActivity.open(this, p); },
                    () -> feature(p, ext ? "Continue in " + p.optString("badge") : "Continue watching")));
            note(p, "Continue watching");
        }
    }

    /** Every row Home can show: catalogs, media servers, IPTV, and other apps' own rows. */
    private List<Addons.Row> allRows() {
        List<Addons.Row> all = new ArrayList<>(home.rows);
        all.addAll(appRows);
        return all;
    }

    /** A row another app publishes for the Android TV home screen (YouTube, Netflix, Plex…). */
    private void appRow(Addons.Row r) {
        if (r.items.isEmpty()) return;
        LinearLayout row = Ui.row(this, rows, r.title, r.note);
        for (JSONObject p : r.items) {
            View.OnClickListener open = v -> WatchNext.open(this, p);
            Runnable focus = () -> feature(p, r.note);
            if (r.wide) {
                String img = !p.optString("still").isEmpty() ? p.optString("still") : p.optString("poster");
                row.addView(Ui.landscape(this, img, p.optString("name"), p.optString("sub"), "",
                        p.optDouble("progress", 0), 196, open, focus));
            } else {
                row.addView(Ui.poster(this, p, open, focus));
            }
        }
    }

    private void posterRow(ViewGroup target, String title, String note, List<JSONObject> items) {
        if (items.isEmpty()) return;
        LinearLayout row = Ui.row(this, target, title, note);
        for (JSONObject p : items) {
            row.addView(Ui.poster(this, p, v -> DetailActivity.open(this, p), () -> feature(p, title)));
            note(p, title);
        }
    }

    private List<JSONObject> favorites(String type) {
        List<JSONObject> out = new ArrayList<>();
        for (JSONObject p : Store.library()) if (type == null || type.equals(p.optString("type"))) out.add(p);
        return out;
    }

    private void renderHome() {
        continueRow(null);
        renderAppRow();
        renderServices();
        posterRow(rows, "Favorites", null, favorites(null));
        for (Addons.Row r : HomeLayout.visible(allRows())) {
            if (WatchNext.APP_ROW.equals(r.type)) appRow(r); else posterRow(rows, r.title, r.note, r.items);
        }
        LinearLayout tail = Ui.horizontal(this);
        tail.setPadding(0, Ui.dp(this, 22), 0, 0);
        tail.addView(Ui.button(this, R.drawable.ic_settings, "Customize Home", v -> startActivity(new Intent(this, HomeRowsActivity.class))));
        rows.addView(tail);
    }

    private void renderType(String type) {
        continueRow(type);
        posterRow(rows, "Favorites", null, favorites(type));
        for (Addons.Row r : home.rows) {
            if (!type.equals(r.type) || "IPTV".equals(r.note)) continue;
            posterRow(rows, r.title.replace(" · Movies", "").replace(" · Shows", ""), r.note, r.items);
        }
        LinearLayout iptvSlot = Ui.vertical(this), genreSlot = Ui.vertical(this);
        iptvSlot.setClipChildren(false);
        genreSlot.setClipChildren(false);
        rows.addView(iptvSlot);
        rows.addView(genreSlot);
        int gen = sectionGen;
        List<Addons.Row> iptv = extraRows.get("iptv:" + type);
        if (iptv != null) {
            for (Addons.Row r : iptv) posterRow(iptvSlot, r.title, "IPTV", r.items);
        } else if (Iptv.vodEnabled() && !Iptv.sources().isEmpty()) {
            App.async(() -> Iptv.vodRows(type, 8), list -> {
                extraRows.put("iptv:" + type, list);
                if (gen == sectionGen) for (Addons.Row r : list) posterRow(iptvSlot, r.title, "IPTV", r.items);
            }, null);
        }
        List<Addons.Row> genres = extraRows.get(type);
        if (genres != null) {
            for (Addons.Row r : genres) posterRow(genreSlot, r.title, null, r.items);
        } else {
            App.async(() -> Addons.genreRows(type, 10), list -> {
                extraRows.put(type, list);
                if (gen == sectionGen) for (Addons.Row r : list) posterRow(genreSlot, r.title, null, r.items);
            }, null);
        }
    }

    private void renderFavorites() {
        for (String type : new String[]{"movie", "series"}) {
            List<JSONObject> list = favorites(type);
            if (list.isEmpty()) continue;
            LinearLayout block = Ui.vertical(this);
            block.setClipChildren(false);
            rows.addView(block);
            TextView h = Ui.heading(this, type.equals("movie") ? "Movies" : "TV Shows", 17);
            h.setPadding(0, Ui.dp(this, 18), 0, Ui.dp(this, 4));
            block.addView(h);
            LinearLayout line = null;
            for (int i = 0; i < list.size(); i++) {
                if (i % 7 == 0) {
                    line = Ui.horizontal(this);
                    line.setClipChildren(false);
                    line.setTag("row");
                    block.addView(line);
                }
                JSONObject p = list.get(i);
                line.addView(Ui.poster(this, p, v -> DetailActivity.open(this, p), () -> feature(p, "Favorites")));
                note(p, "Favorites");
            }
        }
    }

    // ------------------------------------------------------------ app row (circles)

    private void renderAppRow() {
        appRow = Ui.row(this, rows, "Your apps", "Hold OK to move or remove");
        fillAppRow();
    }

    private void fillAppRow() {
        appRow.removeAllViews();
        for (String pkg : Apps.dock(this)) appRow.addView(appCircle(pkg));
        appRow.addView(addCircle());
    }

    private View circleShell(String name, View.OnClickListener click) {
        LinearLayout col = Ui.vertical(this);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(Ui.dp(this, 84), ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.setMargins(0, Ui.dp(this, 6), Ui.dp(this, 8), Ui.dp(this, 6));
        col.setLayoutParams(clp);
        FrameLayout circle = new FrameLayout(this);
        circle.setFocusable(true);
        circle.setClickable(true);
        circle.setOnClickListener(click);
        StateListDrawable bg = new StateListDrawable();
        bg.addState(new int[]{android.R.attr.state_selected}, oval(Ui.BUTTON, Ui.ACCENT));
        bg.addState(new int[]{android.R.attr.state_focused}, oval(Ui.BUTTON, Ui.TEXT));
        bg.addState(new int[]{}, oval(Ui.alpha(Color.WHITE, 0.07f), 0));
        circle.setBackground(bg);
        col.addView(circle, new LinearLayout.LayoutParams(Ui.dp(this, 66), Ui.dp(this, 66)));
        TextView t = Ui.single(Ui.text(this, name, 11, Ui.DIM));
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, Ui.dp(this, 6), 0, 0);
        col.addView(t, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        circle.setOnFocusChangeListener((v, has) -> {
            v.animate().scaleX(has ? 1.1f : 1f).scaleY(has ? 1.1f : 1f).setDuration(140).start();
            t.setTextColor(has ? Ui.TEXT : Ui.DIM);
        });
        col.setTag(circle);
        return col;
    }

    private GradientDrawable oval(int fill, int stroke) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(fill);
        if (stroke != 0) d.setStroke(Ui.dp(this, 3), stroke);
        return d;
    }

    private View appCircle(String pkg) {
        View col = circleShell(Apps.label(this, pkg), v -> Apps.launch(this, pkg));
        FrameLayout circle = (FrameLayout) col.getTag();
        ImageView img = new ImageView(this);
        Drawable d = Apps.icon(this, pkg);
        if (d != null) img.setImageDrawable(d);
        circle.addView(img, new FrameLayout.LayoutParams(Ui.dp(this, 40), Ui.dp(this, 40), Gravity.CENTER));
        circle.setContentDescription(Apps.label(this, pkg));
        circle.setSelected(pkg.equals(movingApp));
        circle.setOnLongClickListener(v -> {
            new AlertDialog.Builder(this)
                    .setTitle(Apps.label(this, pkg))
                    .setItems(new String[]{"Move", "Remove from row", "Open"}, (dlg, which) -> {
                        if (which == 0) {
                            movingApp = pkg;
                            fillAppRow();
                            focusApp(pkg);
                        } else if (which == 1) {
                            List<String> list = Apps.dock(this);
                            list.remove(pkg);
                            Apps.saveDock(list);
                            fillAppRow();
                            if (appRow.getChildCount() > 0) ((View) appRow.getChildAt(0).getTag()).requestFocus();
                        } else {
                            Apps.launch(this, pkg);
                        }
                    })
                    .show();
            return true;
        });
        return col;
    }

    private View addCircle() {
        View col = circleShell("Add app", v -> pickApp());
        FrameLayout circle = (FrameLayout) col.getTag();
        ImageView plus = Ui.icon(this, R.drawable.ic_plus, 26, Ui.MUTED);
        circle.addView(plus, new FrameLayout.LayoutParams(Ui.dp(this, 26), Ui.dp(this, 26), Gravity.CENTER));
        return col;
    }

    private void focusApp(String pkg) {
        List<String> list = Apps.dock(this);
        int i = list.indexOf(pkg);
        if (i >= 0 && i < appRow.getChildCount()) ((View) appRow.getChildAt(i).getTag()).requestFocus();
    }

    private void moveApp(int delta) {
        List<String> list = Apps.dock(this);
        int i = list.indexOf(movingApp), j = i + delta;
        if (i < 0 || j < 0 || j >= list.size()) return;
        Collections.swap(list, i, j);
        Apps.saveDock(list);
        fillAppRow();
        focusApp(movingApp);
    }

    private void stopMoving() {
        if (movingApp == null) return;
        String pkg = movingApp;
        movingApp = null;
        if (appRow != null && appRow.isAttachedToWindow()) {
            fillAppRow();
            focusApp(pkg);
        }
    }

    private void pickApp() {
        List<String> dock = Apps.dock(this);
        List<ResolveInfo> apps = new ArrayList<>();
        for (ResolveInfo ri : Apps.installed(this)) if (!dock.contains(ri.activityInfo.packageName)) apps.add(ri);
        String[] names = new String[apps.size()];
        for (int i = 0; i < apps.size(); i++) names[i] = apps.get(i).loadLabel(getPackageManager()).toString();
        new AlertDialog.Builder(this)
                .setTitle("Add an app")
                .setItems(names, (d, which) -> {
                    dock.add(apps.get(which).activityInfo.packageName);
                    Apps.saveDock(dock);
                    fillAppRow();
                    focusApp(apps.get(which).activityInfo.packageName);
                })
                .show();
    }

    // ------------------------------------------------------------ streaming services

    private void renderServices() {
        if (services.isEmpty()) return;
        LinearLayout row = Ui.row(this, rows, "Streaming services",
                Tmdb.enabled() ? null : "Add a TMDB key in Settings to browse them");
        for (Tmdb.Service s : services) row.addView(serviceTile(s));
    }

    private View serviceTile(Tmdb.Service s) {
        LinearLayout col = Ui.vertical(this);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(Ui.dp(this, 176), ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.setMargins(0, Ui.dp(this, 6), Ui.dp(this, 14), Ui.dp(this, 6));
        col.setLayoutParams(clp);
        FrameLayout tile = new FrameLayout(this);
        tile.setFocusable(true);
        tile.setClickable(true);
        tile.setContentDescription(s.name);
        StateListDrawable bg = new StateListDrawable();
        bg.addState(new int[]{android.R.attr.state_focused}, Ui.rounded(this, Ui.BUTTON, Ui.ACCENT, 14));
        bg.addState(new int[]{}, Ui.rounded(this, Ui.alpha(Color.WHITE, 0.07f), Ui.alpha(Color.WHITE, 0.08f), 14));
        tile.setBackground(bg);
        TextView fallback = Ui.heading(this, s.name, 17);
        fallback.setGravity(Gravity.CENTER);
        tile.addView(fallback, new FrameLayout.LayoutParams(-1, -1));
        if (!s.wordmark.isEmpty()) {
            ImageView logo = new ImageView(this);
            logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
            logo.setColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN);   // monochrome wordmark
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1, -1);
            lp.setMargins(Ui.dp(this, 22), Ui.dp(this, 22), Ui.dp(this, 22), Ui.dp(this, 22));
            tile.addView(logo, lp);
            ImageLoader.load(logo, s.wordmark, Ui.dp(this, 140), () -> fallback.setVisibility(View.GONE));
        } else if (!s.logo.isEmpty()) {
            ImageView logo = Ui.image(this, Color.TRANSPARENT, 12);
            tile.addView(logo, new FrameLayout.LayoutParams(Ui.dp(this, 54), Ui.dp(this, 54), Gravity.CENTER));
            ImageLoader.load(logo, s.logo, Ui.dp(this, 54), () -> fallback.setVisibility(View.GONE));
        }
        col.addView(tile, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 98)));
        TextView t = Ui.single(Ui.text(this, s.name, 12, Ui.DIM));
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, Ui.dp(this, 7), 0, 0);
        col.addView(t);
        tile.setOnFocusChangeListener((v, has) -> {
            v.animate().scaleX(has ? 1.05f : 1f).scaleY(has ? 1.05f : 1f).setDuration(140).start();
            t.setTextColor(has ? Ui.TEXT : Ui.DIM);
        });
        tile.setOnClickListener(v -> startActivity(new Intent(this, ServiceActivity.class).putExtra("service", s.key)));
        return col;
    }

    // ------------------------------------------------------------ featured area

    private void feature(JSONObject p, String eyebrow) {
        if (p == heroItem) return;
        heroItem = p;
        String label = p.optString("label");
        heroEyebrow.setText((eyebrow + (label.isEmpty() ? "" : " · " + label)).toUpperCase());
        heroTitle.setText(p.optString("name"));
        List<String> meta = new ArrayList<>();
        if (!p.optString("releaseInfo").isEmpty()) meta.add(p.optString("releaseInfo"));
        if (!p.optString("imdbRating").isEmpty()) meta.add("★ " + p.optString("imdbRating"));
        else if (!p.optString("rating").isEmpty()) meta.add("★ " + p.optString("rating"));
        JSONArray genres = p.optJSONArray("genres");
        if (genres == null) genres = p.optJSONArray("genre");
        if (genres != null && genres.length() > 0) {
            List<String> g = new ArrayList<>();
            for (int i = 0; i < genres.length() && i < 3; i++) g.add(genres.optString(i));
            meta.add(TextUtils.join(", ", g));
        }
        if (!p.optString("badge").isEmpty()) meta.add("external".equals(p.optString("type")) ? p.optString("badge") : "On your " + p.optString("badge"));
        heroMeta.setText(TextUtils.join("   ·   ", meta));
        heroText.setText(p.optString("description"));
        String bg = p.optString("background");
        heroImage.animate().cancel();
        heroImage.setAlpha(0.35f);
        ImageLoader.load(heroImage, bg.isEmpty() ? p.optString("poster") : bg, Ui.dp(this, 900));
        heroImage.animate().alpha(1f).setDuration(260).start();
    }

    // ------------------------------------------------------------ dialogs

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
        String[] items = new String[profiles.size() + 2];
        for (int i = 0; i < profiles.size(); i++) items[i] = profiles.get(i) + (Trakt.signedIn(profiles.get(i)) ? "   · Trakt" : "");
        items[profiles.size()] = "+ Add profile";
        items[profiles.size() + 1] = "Manage profiles & Trakt";
        new AlertDialog.Builder(this)
                .setTitle("Who's watching?")
                .setItems(items, (d, which) -> {
                    if (which < profiles.size()) {
                        switchProfile(profiles.get(which));
                    } else if (which == profiles.size() + 1) {
                        startActivity(new Intent(this, ProfilesActivity.class));
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
        extraRows.clear();
        Store.setProfile(name);
        avatarText.setText(name.substring(0, 1).toUpperCase());
        View pl = drawer.findViewWithTag("profileLabel");
        if (pl instanceof TextView) ((TextView) pl).setText(name);
        updateStatus();
        load();
    }
}
