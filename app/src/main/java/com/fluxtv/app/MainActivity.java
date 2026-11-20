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
    private LinearLayout rows;
    private ScrollView scroll;
    private TextView clock, status;
    private ImageView heroImage;
    private TextView heroEyebrow, heroTitle, heroMeta, heroText;
        private View lastContentFocus;

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
        if (getIntent().getStringExtra("section") != null) section = getIntent().getStringExtra("section");
        themeVersion = Theme.version();

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Ui.BG);
        contentRoot = new FrameLayout(this);
        root.addView(contentRoot, new FrameLayout.LayoutParams(-1, -1));
        buildContent();

        dock = new Dock(this, new Dock.Host() {
            @Override public String dockKey() { return section; }
            @Override public void dockGo(String key) { goDock(key); }
        }, contentRoot);
        root.addView(dock.view, dock.params());
        setContentView(root);
    }

    // ------------------------------------------------------------ the dock (Dock: always on screen; ← glides it open)

    /** Width of the always-visible dock on the left, and where Home's content starts. */
    private static final int RAIL_DP = Dock.RAIL_DP, START_DP = RAIL_DP + 44, TOP_DP = 64;
    private Dock dock;

    private void goDock(String key) {
        switch (key) {
            case "home": case "movie": case "series": case "favorites":
                showSection(key);
                break;
            case "profile": chooseProfile(); break;
            default: Dock.openScreen(this, key);
        }
    }

    // ------------------------------------------------------------ layout

    private void buildContent() {
        heroImage = new ImageView(this);
        heroImage.setScaleType(ImageView.ScaleType.CENTER_CROP);
        // Full-width backdrop, fading into the background on the left and at the bottom.
        FrameLayout.LayoutParams hp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 340), Gravity.TOP);
        contentRoot.addView(heroImage, hp);
        // A light, even shade behind the title text (no dark band down the left edge).
        View scrimLeft = new View(this);
        scrimLeft.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{Ui.alpha(Ui.BG, 0.45f), Ui.alpha(Ui.BG, 0.38f), Ui.alpha(Ui.BG, 0.15f), Color.TRANSPARENT}));
        contentRoot.addView(scrimLeft, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 340)));
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
        top.setPadding(Ui.dp(this, START_DP), Ui.dp(this, 28), Ui.dp(this, 230), 0);   // right: room for the clock badge
        status = Ui.single(Ui.text(this, "", 13, Ui.MUTED));
        top.addView(status, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        clock = Ui.heading(this, "", 15);   // the time now shows in the clock badge (top right)
        contentRoot.addView(top, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout hero = Ui.vertical(this);
        heroBox = hero;
        hero.setPadding(Ui.dp(this, START_DP), Ui.dp(this, 66), 0, 0);
        heroEyebrow = Ui.eyebrow(this, "");
        heroTitle = Ui.single(Ui.heading(this, "Flux", 40));
        heroTitle.setLetterSpacing(-0.02f);
        heroTitle.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 8));
        heroMeta = Ui.single(Ui.text(this, "", 13, Ui.MUTED));
        heroText = Ui.text(this, "", 14, Ui.alpha(Ui.TEXT, 0.82f));
        heroText.setMaxLines(2);
        heroText.setEllipsize(TextUtils.TruncateAt.END);
        heroText.setPadding(0, Ui.dp(this, 10), 0, 0);
        // Soft shadows keep the text readable over bright pictures now that the shade is lighter.
        for (TextView t : new TextView[]{heroEyebrow, heroTitle, heroMeta, heroText}) t.setShadowLayer(Ui.dp(this, 6), 0, Ui.dp(this, 1), 0xCC000000);
        hero.addView(heroEyebrow);
        hero.addView(heroTitle);
        hero.addView(heroMeta);
        hero.addView(heroText);
        contentRoot.addView(hero, new FrameLayout.LayoutParams(Ui.dp(this, 600 + START_DP), ViewGroup.LayoutParams.WRAP_CONTENT));

        scroll = new FocusScroll.Vertical(this);
        rows = Ui.vertical(this);
        rows.setClipChildren(false);
        rows.setClipToPadding(false);
        scroll.addView(rows);
        float heightDp = getResources().getDisplayMetrics().heightPixels / getResources().getDisplayMetrics().density;
        heroGapDp = (heightDp >= 520 ? 236 : 186) - TOP_DP;
        if (heroGapDp < 236 - TOP_DP) heroText.setMaxLines(1);
        // The rows' area starts just under the status line; on Home the first row sits lower (room for the
        // featured title above it). Browsing further down slides the rows up into that space.
        rows.setPadding(Ui.dp(this, START_DP), Ui.dp(this, heroGapDp), 0, Ui.dp(this, 400));
        FrameLayout.LayoutParams rp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        rp.setMargins(0, Ui.dp(this, TOP_DP), 0, 0);
        contentRoot.addView(scroll, rp);
        scroll.getViewTreeObserver().addOnGlobalFocusChangeListener((oldF, newF) -> onRowFocus(newF));
    }

    // ------------------------------------------------------------ rows slide up while browsing

    private View heroBox;
    private int heroGapDp;
    private boolean browsing;

    /** Home: the featured title shows while you're on the first row; further down the rows move up to fill the screen. */
    private void onRowFocus(View f) {
        if (f == null || dock.isOpen()) return;
        View top = null, row = null;
        for (View v = f; v != null && v.getParent() instanceof View; v = (View) v.getParent()) {
            if (row == null && "row".equals(v.getTag())) row = v;
            if (v.getParent() == rows) { top = v; break; }
        }
        if (top == null) return;
        boolean first = rows.indexOfChild(top) <= firstRowIndex();
        View target = row != null ? row : top;
        if (first) {
            setBrowsing(false);
            scroll.post(() -> scroll.smoothScrollTo(0, 0));
        } else {
            setBrowsing(true);
            int y = 0;
            for (View v = target; v != null && v != rows; v = (View) v.getParent()) y += v.getTop();
            int to = Math.max(0, y - Ui.dp(this, 4));
            scroll.post(() -> scroll.smoothScrollTo(0, to));
        }
    }

    /** Index of the first row that actually shows something (slots can be empty while loading). */
    private int firstRowIndex() {
        for (int i = 0; i < rows.getChildCount(); i++) {
            View c = rows.getChildAt(i);
            if (c.getVisibility() == View.VISIBLE && c.getHeight() > 0) return i;
        }
        return 0;
    }

    private void setBrowsing(boolean on) {
        boolean heroSection = "home".equals(section);
        float heroAlpha = heroSection && !on ? 1f : 0f;
        if (heroBox != null && heroBox.getAlpha() != heroAlpha) heroBox.animate().alpha(heroAlpha).setDuration(200).start();
        float imgAlpha = heroSection ? (on ? 0.35f : 1f) : 0.3f;
        if (heroImage.getAlpha() != imgAlpha) heroImage.animate().alpha(imgAlpha).setDuration(250).start();
        browsing = on;
    }

    private void openDrawer() {
        if (dock.isOpen()) return;
        lastContentFocus = getCurrentFocus();
        dock.open();
    }

    private void closeDrawer(boolean restoreFocus) {
        if (!dock.isOpen()) return;
        dock.close(false);
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
        if (e.getKeyCode() != KeyEvent.KEYCODE_DPAD_RIGHT && dock.onKey(e)) return true;
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
            if (dock.isOpen() && k == KeyEvent.KEYCODE_DPAD_RIGHT) {
                closeDrawer(true);
                return true;
            }
            if (!dock.isOpen() && k == KeyEvent.KEYCODE_DPAD_LEFT) {
                View f = getCurrentFocus();
                // The focused view can be outside the content area (or just removed by a refresh), and
                // FocusFinder throws for those, so only ask it about views that are really inside.
                boolean inside = false;
                for (android.view.ViewParent p = f == null ? null : f.getParent(); p != null; p = p.getParent()) {
                    if (p == contentRoot) { inside = true; break; }
                }
                View next = null;
                if (inside) {
                    try {
                        next = FocusFinder.getInstance().findNextFocus(contentRoot, f, View.FOCUS_LEFT);
                    } catch (IllegalArgumentException ignored) { }
                }
                if (f != null && !inside) {
                    focusContent();   // stale focus (e.g. a row that was just rebuilt): move back onto Home
                    return true;
                }
                if (f == null || Ui.leftLeavesRow(f, next)) {
                    openDrawer();
                    return true;
                }
            }
        }
        return super.dispatchKeyEvent(e);
    }

    /** The sidebar on other screens opens Home, Movies, TV Shows or Favorites here. */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        String s = intent.getStringExtra("section");
        if (s != null && home != null) {
            closeDrawer(false);
            showSection(s);
        } else if (s != null) {
            section = s;
        }
    }

    @Override
    public void onBackPressed() {
        // We may be the TV's home screen, so never close.
        if (dock.isOpen()) {
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
        dock.rebuildIfChanged();
        updateStatus();
        if (Store.addonsVersion() != loadedVersion || !name.equals(loadedProfile)) {
            extraRows.clear();
            load();
        } else if (Store.changes() != renderedChanges) {
            renderSection();
        } else if (System.currentTimeMillis() - loadedAt > 10 * 60 * 1000) {
            load();   // keeps showing the current rows and swaps in fresh ones
        } else if (section.equals("home")) {
            refreshExternal();
            refreshLiveRow();
        }
        App.MAIN.removeCallbacks(clockTick);
        clockTick.run();
        Trakt.syncIfDue(() -> { if (home != null) renderSection(); });
        ProfileSync.setOnMerged(() -> {
            if (isFinishing() || !hasWindowFocus()) return;   // picked up in onResume otherwise
            if (!Store.profiles().contains(loadedProfile)) { recreate(); return; }
            if (home != null && Store.changes() != renderedChanges) {
                boolean hadFocus = !dock.isOpen() && getCurrentFocus() != null;
                renderSection();
                if (hadFocus) rows.post(this::focusContent);
            }
        });
        ProfileSync.syncNow(null);
        Updater.check(this, false);
        showLastCrash();
        askWatchNextPermission();
        if (getCurrentFocus() == null && !dock.isOpen()) rows.post(this::focusContent);
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
        String hello = "Hi, " + Store.profile();
        status.setText(parts.isEmpty() ? hello : hello + "      " + TextUtils.join("     ", parts));
    }

    // ------------------------------------------------------------ data

    private int loadGen;
    private long loadedAt;

    /**
     * Loads Home. Shows the saved copy from last time straight away (or, the first time, Continue watching and
     * then each row as soon as its source answers), and swaps in fresh rows when everything has loaded,
     * keeping your place.
     */
    private void load() {
        boolean sameSources = Store.addonsVersion() == loadedVersion && Store.profile().equals(loadedProfile);
        loadedVersion = Store.addonsVersion();
        loadedProfile = Store.profile();
        loadedAt = System.currentTimeMillis();
        if (!sameSources) home = null;   // another profile or different add-ons: don't keep showing the old rows
        boolean showing = home != null;
        if (!showing) {
            Blend.Home saved = Blend.cached();
            if (saved != null) {
                home = saved;
                HomeLayout.setAvailable(allRows());
                renderSection();
                if (!dock.isOpen()) rows.post(this::focusContent);
                showing = true;
            }
        }
        if (!showing) {
            rows.removeAllViews();
            TextView loading = Ui.text(this, "Loading…", 14, Ui.MUTED);
            loading.setPadding(0, Ui.dp(this, 40), 0, 0);
            rows.addView(loading);
        }
        int gen = ++loadGen;
        boolean progressive = !showing;
        android.content.Context app = getApplicationContext();
        App.IO.execute(() -> {
            java.util.concurrent.Future<List<Tmdb.Service>> svc = Addons.POOL.submit(Tmdb::services);
            List<JSONObject> ext = WatchNext.load(app);
            List<Addons.Row> ch = WatchNext.channels(app);
            App.MAIN.post(() -> { if (gen == loadGen) { external = ext; appRows = ch; } });
            final boolean[] finished = {false};
            Blend.Home h;
            try {
                h = Blend.home(!progressive ? null : partial -> App.MAIN.post(() -> {
                    if (gen == loadGen && !finished[0] && !isFinishing()) applyHome(partial, ext, ch, services);
                }));
            } catch (Exception e) {
                App.MAIN.post(() -> {
                    if (gen != loadGen || home != null) return;
                    rows.removeAllViews();
                    rows.addView(Ui.text(this, "Couldn't load: " + App.message(e), 14, Ui.MUTED));
                });
                return;
            }
            List<Tmdb.Service> sv;
            try { sv = svc.get(30, java.util.concurrent.TimeUnit.SECONDS); } catch (Exception e) { sv = services; }
            List<Tmdb.Service> fsv = sv;
            Blend.Home fh = h;
            App.MAIN.post(() -> {
                if (gen != loadGen || isFinishing()) return;
                finished[0] = true;
                applyHome(fh, ext, ch, fsv);
            });
            Blend.save(h);
            prefetchPosters(h);
        });
    }

    /** Swaps in new Home data without moving your highlight (same row, same position). */
    private void applyHome(Blend.Home h, List<JSONObject> ext, List<Addons.Row> ch, List<Tmdb.Service> sv) {
        int[] pos = dock.isOpen() ? null : focusPosition();
        int y = scroll.getScrollY();
        boolean hadFocus = getCurrentFocus() != null && !dock.isOpen();
        home = h;
        external = ext;
        appRows = ch;
        if (sv != null) services = sv;
        HomeLayout.setAvailable(allRows());
        renderSection();
        if (dock.isOpen()) return;
        if (pos != null) {
            scroll.scrollTo(0, y);
            rows.post(() -> { if (!restoreFocus(pos)) focusContent(); scroll.scrollTo(0, y); });
        } else if (!hadFocus || getCurrentFocus() == null) {
            rows.post(this::focusContent);
        }
    }

    /** Every Home row block, top to bottom. */
    private void rowBlocks(ViewGroup g, List<ViewGroup> out) {
        for (int i = 0; i < g.getChildCount(); i++) {
            View c = g.getChildAt(i);
            if ("row".equals(c.getTag()) && c instanceof ViewGroup) out.add((ViewGroup) c);
            else if (c instanceof ViewGroup) rowBlocks((ViewGroup) c, out);
        }
    }

    private static ViewGroup itemsOf(ViewGroup block) {
        for (int i = 0; i < block.getChildCount(); i++) {
            View c = block.getChildAt(i);
            if (c instanceof android.widget.HorizontalScrollView) return (ViewGroup) ((ViewGroup) c).getChildAt(0);
        }
        return null;
    }

    /** {row, item} of the highlighted card, or null if the highlight isn't on a card in a row. */
    private int[] focusPosition() {
        View f = getCurrentFocus();
        if (f == null) return null;
        List<ViewGroup> blocks = new ArrayList<>();
        rowBlocks(rows, blocks);
        for (int r = 0; r < blocks.size(); r++) {
            ViewGroup items = itemsOf(blocks.get(r));
            if (items == null) continue;
            for (int k = 0; k < items.getChildCount(); k++) {
                View card = items.getChildAt(k);
                if (card == f || (card instanceof ViewGroup && isInside(f, (ViewGroup) card))) return new int[]{r, k};
            }
        }
        return null;
    }

    private static boolean isInside(View v, ViewGroup g) {
        for (android.view.ViewParent p = v.getParent(); p != null; p = p.getParent()) if (p == g) return true;
        return false;
    }

    private boolean restoreFocus(int[] pos) {
        List<ViewGroup> blocks = new ArrayList<>();
        rowBlocks(rows, blocks);
        if (blocks.isEmpty()) return false;
        ViewGroup items = itemsOf(blocks.get(Math.min(pos[0], blocks.size() - 1)));
        if (items == null || items.getChildCount() == 0) return false;
        for (int k = Math.min(pos[1], items.getChildCount() - 1); k >= 0; k--) {
            View c = items.getChildAt(k);
            if (c.getVisibility() == View.VISIBLE && (c.requestFocus() || c.requestFocus(View.FOCUS_DOWN))) return true;
        }
        return false;
    }

    /** Fetches the first posters of each row in the background so scrolling down shows them at once. */
    private static void prefetchPosters(Blend.Home h) {
        int rowsDone = 0;
        for (Addons.Row r : h.rows) {
            if (rowsDone++ >= 10) break;
            int n = 0;
            for (JSONObject p : r.items) {
                if (n++ >= 10) break;
                ImageLoader.prefetch(p.optString("poster"));
            }
        }
        for (JSONObject p : h.continueWatching) ImageLoader.prefetch(p.optString("background"));
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
            if (changed && section.equals("home") && home != null) {
                boolean hadFocus = !dock.isOpen() && getCurrentFocus() != null;
                renderSection();
                if (hadFocus || getCurrentFocus() == null) rows.post(this::focusContent);
            }
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
        dock.refresh();
        rows.post(this::focusContent);
    }

    private void renderSection() {
        renderedChanges = Store.changes();
        dock.refresh();
        if (home == null) return;
        sectionGen++;
        rows.removeAllViews();
        boolean homeSection = "home".equals(section);
        rows.setPadding(rows.getPaddingLeft(), homeSection ? Ui.dp(this, heroGapDp) : Ui.dp(this, 6), 0, rows.getPaddingBottom());
        setBrowsing(false);
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
        if (firstItem == null && !Boolean.FALSE.equals(Ratings.allowedNow(p)) && Ratings.allowedNow(p) != null) {
            firstItem = p;
            firstEyebrow = eyebrow;
        }
    }

    private void continueRow(String type) {
        List<JSONObject> list = new ArrayList<>();
        java.util.Set<String> hiddenApps = ContinueWatching.hidden();
        if (!hiddenApps.contains(ContinueWatching.FLUX)) {
            for (JSONObject p : home.continueWatching) if (type == null || type.equals(p.optString("type"))) list.add(p);
        }
        if (type == null && !Ratings.restricted()) {   // other apps' items have no rating to check
            for (JSONObject p : external) if (!hiddenApps.contains(p.optString("package"))) list.add(p);
            Collections.sort(list, (a, b) -> Long.compare(b.optLong("t"), a.optLong("t")));
        }
        if (list.isEmpty()) return;
        LinearLayout row = Ui.row(this, rows, "Continue watching");
        for (JSONObject p : list) {
            boolean ext = "external".equals(p.optString("type"));
            // Episode picture first, then the show's backdrop, then its poster: whichever loads.
            String[] img = {p.optString("still"), p.optString("background"), p.optString("poster")};
            View card = Ui.landscape(this, img, p.optString("name"), p.optString("sub"), p.optString("badge"),
                    p.optDouble("progress", 0), 196,
                    v -> { if (ext) WatchNext.open(this, p); else DetailActivity.open(this, p); },
                    () -> feature(p, ext ? "Continue in " + p.optString("badge") : "Continue watching"));
            row.addView(card);
            if (!ext) Ratings.gate(card, p);
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
        if (r.items.isEmpty() || Ratings.restricted()) return;
        LinearLayout row = Ui.row(this, rows, r.title, r.note);
        for (JSONObject p : r.items) {
            View.OnClickListener open = v -> WatchNext.open(this, p);
            Runnable focus = () -> feature(p, r.note);
            if (r.wide) {
                String[] img = {p.optString("still"), p.optString("background"), p.optString("poster")};
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
        liveRow();
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

    /** Live TV on Home: the guide, then favourite / recently watched channels (OK tunes straight in). */
    private LinearLayout liveSlot;
    private String liveSig = "";

    /** Which channels the Live TV row shows depends on these (watching a channel changes "recent"). */
    private static String liveSignature() {
        return Store.get("live_recent") + "#" + Store.get("live_favs");
    }

    private void liveRow() {
        if (Iptv.sources().isEmpty() || Ratings.restricted()) return;
        LinearLayout slot = Ui.vertical(this);
        slot.setClipChildren(false);
        rows.addView(slot);
        liveSlot = slot;
        fillLiveRow(slot, false);
    }

    /** Back on Home after watching Live TV: redo just the Live TV row (not the whole screen). */
    private void refreshLiveRow() {
        if (liveSlot == null || liveSlot.getParent() == null || liveSig.equals(liveSignature())) return;
        View focused = getCurrentFocus();
        boolean hadFocus = focused != null && isInside(focused, liveSlot);
        fillLiveRow(liveSlot, hadFocus);
    }

    private static boolean isInside(View v, View parent) {
        for (android.view.ViewParent p = v.getParent(); p != null; p = p.getParent()) if (p == parent) return true;
        return false;
    }

    private void fillLiveRow(LinearLayout slot, boolean focusAfter) {
        liveSig = liveSignature();
        int gen = sectionGen;
        App.async(LiveActivity::homeChannels, list -> {
            if (gen != sectionGen) return;
            slot.removeAllViews();
            LinearLayout row = Ui.row(this, slot, "Live TV", "Favorites and recent channels");
            LinearLayout guideTile = (LinearLayout) appTile("TV guide", v -> startActivity(new Intent(this, LiveActivity.class)));
            FrameLayout box = new FrameLayout(this);
            box.addView(Ui.icon(this, R.drawable.ic_live, 34, Ui.ACCENT), new FrameLayout.LayoutParams(Ui.dp(this, 34), Ui.dp(this, 34), Gravity.CENTER));
            guideTile.addView(box, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 84)));
            guideTile.addView((View) guideTile.getTag(), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            row.addView(guideTile);
            java.util.Set<String> favs = LiveActivity.favorites();
            for (GuideView.Entry e : list) {
                String grp = favs.contains(e.source.id + "|" + e.channel.id) ? "favorites" : "all";
                LinearLayout t = (LinearLayout) appTile((e.channel.num.isEmpty() ? "" : e.channel.num + "  ") + e.channel.name,
                        v -> startActivity(new Intent(this, LivePlayerActivity.class).putExtra("group", grp)
                                .putExtra("source", e.source.id).putExtra("channel", e.channel.id)));
                ImageView logo = new ImageView(this);
                logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
                logo.setPadding(Ui.dp(this, 10), Ui.dp(this, 8), Ui.dp(this, 10), Ui.dp(this, 8));
                ImageLoader.load(logo, e.channel.logo, Ui.dp(this, 160));
                t.addView(logo, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 84)));
                t.addView((View) t.getTag(), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                Iptv.Program p = GuideView.at(Iptv.programs(e.source, e.channel), System.currentTimeMillis());
                if (p != null) {
                    TextView now = Ui.single(Ui.text(this, p.title, 12, Ui.MUTED));
                    now.setGravity(Gravity.CENTER);
                    t.addView(now, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                }
                row.addView(t);
            }
            if (focusAfter && row.getChildCount() > 1) row.getChildAt(1).requestFocus();   // the channel just watched
        }, null);
    }

    private void renderType(String type) {
        genreButtons(type);   // genre boxes first, at the top of the screen
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

    /** A row of genre buttons; each opens a full page of that genre. */
    private void genreButtons(String type) {
        LinearLayout slot = Ui.vertical(this);
        slot.setClipChildren(false);
        rows.addView(slot);
        int gen = sectionGen;
        App.async(() -> Addons.genreNames(type), names -> {
            if (gen != sectionGen || names.isEmpty()) return;
            LinearLayout row = Ui.row(this, slot, "Genres");
            int[] tints = {0xFF2E3B47, 0xFF3A2F45, 0xFF45302A, 0xFF27394A, 0xFF2F4030, 0xFF433A26, 0xFF432A36};
            int i = 0;
            for (String g : names) {
                // A box per genre (bottom-left label), outlined in the highlight colour when selected.
                TextView b = Ui.heading(this, g, 15);
                b.setFocusable(true);
                b.setClickable(true);
                b.setGravity(Gravity.BOTTOM | Gravity.START);
                b.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 10), Ui.dp(this, 9));
                int tint = tints[i++ % tints.length];
                StateListDrawable bg = new StateListDrawable();
                bg.addState(new int[]{android.R.attr.state_focused}, Ui.rounded(this, tint, Ui.FOCUS, 12));
                bg.addState(new int[]{}, Ui.rounded(this, tint, 0, 12));
                b.setBackground(bg);
                b.setOnFocusChangeListener((v, has) -> v.animate().scaleX(has ? 1.05f : 1f).scaleY(has ? 1.05f : 1f).setDuration(120).start());
                b.setOnClickListener(v -> startActivity(new Intent(this, GenreActivity.class).putExtra("type", type).putExtra("genre", g)));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(this, 120), Ui.dp(this, 60));
                lp.setMargins(0, Ui.dp(this, 4), Ui.dp(this, 12), Ui.dp(this, 4));
                row.addView(b, lp);
            }
        }, null);
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
                if (i % 6 == 0) {
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

    /** Home app row tiles: the same rounded squares (banner + name) as the Apps screen. */
    private View appTile(String name, View.OnClickListener click) {
        LinearLayout t = Ui.vertical(this);
        t.setGravity(Gravity.CENTER_HORIZONTAL);
        t.setFocusable(true);
        t.setClickable(true);
        t.setOnClickListener(click);
        int p = Ui.dp(this, 8);
        t.setPadding(p, p, p, p);
        t.setBackground(Ui.focusable(this, Ui.PANEL, Ui.BUTTON, Ui.FOCUS));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(this, 170), ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, Ui.dp(this, 6), Ui.dp(this, 14), Ui.dp(this, 6));
        t.setLayoutParams(lp);
        TextView label = Ui.single(Ui.text(this, name, 14, Ui.TEXT));
        label.setGravity(Gravity.CENTER);
        t.setTag(label);
        t.setOnFocusChangeListener((v, has) -> v.animate().scaleX(has ? 1.06f : 1f).scaleY(has ? 1.06f : 1f).setDuration(140).start());
        return t;
    }

    private View appCircle(String pkg) {
        LinearLayout t = (LinearLayout) appTile(Apps.label(this, pkg), v -> Apps.launch(this, pkg));
        ImageView img = new ImageView(this);
        img.setScaleType(ImageView.ScaleType.FIT_CENTER);
        Drawable d = Apps.banner(this, pkg);
        if (d != null) img.setImageDrawable(d);
        t.addView(img, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 84)));
        t.addView((View) t.getTag(), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        t.setTag(t);   // focusApp() focuses the tile itself
        t.setContentDescription(Apps.label(this, pkg));
        t.setSelected(pkg.equals(movingApp));
        t.setOnLongClickListener(v -> {
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
        return t;
    }

    private View addCircle() {
        LinearLayout t = (LinearLayout) appTile("Add app", v -> pickApp());
        FrameLayout box = new FrameLayout(this);
        ImageView plus = Ui.icon(this, R.drawable.ic_plus, 30, Ui.MUTED);
        box.addView(plus, new FrameLayout.LayoutParams(Ui.dp(this, 30), Ui.dp(this, 30), Gravity.CENTER));
        t.addView(box, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 84)));
        t.addView((View) t.getTag(), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        t.setTag(t);
        return t;
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
        bg.addState(new int[]{android.R.attr.state_focused}, Ui.rounded(this, Ui.BUTTON, Ui.FOCUS, 14));
        bg.addState(new int[]{}, Ui.rounded(this, Ui.alpha(Color.WHITE, 0.07f), Ui.alpha(Color.WHITE, 0.08f), 14));
        tile.setBackground(bg);
        TextView fallback = Ui.heading(this, s.name, 20);
        fallback.setTypeface(android.graphics.Typeface.create("sans-serif-black", android.graphics.Typeface.BOLD));
        fallback.setGravity(Gravity.CENTER);
        tile.addView(fallback, new FrameLayout.LayoutParams(-1, -1));
        if (!s.wordmark.isEmpty()) {
            ImageView logo = new ImageView(this);
            logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1, -1);
            lp.setMargins(Ui.dp(this, 22), Ui.dp(this, 22), Ui.dp(this, 22), Ui.dp(this, 22));
            tile.addView(logo, lp);
            logo.setTag(s.wordmark);
            // Loaded with its transparency kept: the logo is tinted white, and a picture without transparency
            // would turn into a solid white box, so those keep showing the name instead.
            ImageLoader.bitmap(s.wordmark, Ui.dp(this, 140), b -> {
                if (!s.wordmark.equals(logo.getTag())) return;
                if (!Ui.hasTransparency(b)) { serviceIcon(tile, s, fallback); return; }
                logo.setImageBitmap(b);
                logo.setColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN);   // monochrome wordmark
                fallback.setVisibility(View.GONE);
            });
            App.MAIN.postDelayed(() -> { if (logo.getDrawable() == null) serviceIcon(tile, s, fallback); }, 8000);
        } else {
            serviceIcon(tile, s, fallback);
        }
        // No white wordmark: the name itself stands in (never the coloured app icon, so all tiles match).
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

    /** No usable white wordmark: the service's own app icon, centred, instead of just its name. */
    private void serviceIcon(FrameLayout tile, Tmdb.Service s, View fallback) {
        if (s.logo.isEmpty() || tile.findViewWithTag("svcIcon") != null) return;
        ImageView icon = Ui.image(this, Color.TRANSPARENT, 14);
        icon.setTag("svcIcon");
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        tile.addView(icon, new FrameLayout.LayoutParams(Ui.dp(this, 60), Ui.dp(this, 60), Gravity.CENTER));
        ImageLoader.load(icon, s.logo, Ui.dp(this, 60), () -> fallback.setVisibility(View.GONE));
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
        ImageLoader.loadFirst(heroImage, Ui.dp(this, 900), bg, p.optString("still"), p.optString("poster"));
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
        Ratings.switchProfile(this, name, () -> applyProfile(name));
    }

    private void applyProfile(String name) {
        extraRows.clear();
        ProfileSync.syncNow(null);
        updateStatus();
        dock.rebuildIfChanged();
        load();
    }
}
