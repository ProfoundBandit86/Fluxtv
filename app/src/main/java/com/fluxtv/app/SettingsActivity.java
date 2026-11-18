package com.fluxtv.app;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Settings, in two columns: categories on the left, the chosen category's settings on the right. */
public class SettingsActivity extends FluxActivity {

    @Override
    protected String sidebarKey() {
        return "settings";
    }

    /** key → [title, one-line description] */
    private static final String[][] CATEGORIES = {
            {"profiles", "Profiles & Trakt", "Who's watching, watchlist sync"},
            {"home", "Home screen", "Home button, dock, weather"},
            {"categories", "Categories", "Rows on Home, MDBList"},
            {"appearance", "Appearance", "Themes and colours"},
            {"player", "Player", "Subtitles, picture, size limits"},
            {"sources", "Sources", "Add-ons, servers, Real-Debrid, IPTV"},
            {"live", "Live TV & DVR", "TV guide, recordings, network share"},
            {"smarthome", "Smart home", "Home Assistant, lights panel"},
            {"downloads", "Downloads", "Radarr, Sonarr, SABnzbd"},
            {"search", "Search", "Gemini and TMDB"},
            {"system", "System", "Updates, phone setup, TV settings"},
    };
    private static final int[] ICONS = {R.drawable.ic_account, R.drawable.ic_home, R.drawable.ic_list, R.drawable.ic_display, R.drawable.ic_play,
            R.drawable.ic_addons, R.drawable.ic_live, R.drawable.ic_bulb, R.drawable.ic_download, R.drawable.ic_search, R.drawable.ic_settings};

    private String cat = "profiles";
    /** The right-hand list. Each section() starts a card in it, and content points at the card being filled. */
    private LinearLayout pane, content;
    private ScrollView paneScroll;
    private TextView phoneHint;
    private final Map<String, View> catRows = new LinkedHashMap<>();
    private boolean built;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getIntent().getStringExtra("category") != null) cat = getIntent().getStringExtra("category");
        if (savedInstanceState != null) {
            // Back after a theme change (the screen restarts to apply colours): same section, same option.
            cat = savedInstanceState.getString("cat", cat);
            pendingFocus = savedInstanceState.getInt("focus", -1);
            pendingScroll = savedInstanceState.getInt("scroll", 0);
        }
        build();
        Ratings.guard(this);
    }

    private int pendingFocus = -1, pendingScroll;

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putString("cat", cat);
        View f = getCurrentFocus();
        out.putInt("focus", f == null || pane == null ? -1 : pane.getFocusables(View.FOCUS_DOWN).indexOf(f));
        out.putInt("scroll", paneScroll == null ? 0 : paneScroll.getScrollY());
    }

    @Override
    protected void onResume() {
        super.onResume();
        liveShown = null;   // channel counts may have changed (hidden in the guide or the filter screen)
        if (!ConfigServer.running()) ConfigServer.start(() -> { if (!isFinishing()) render(); });
        updatePhoneHint();
        if (pendingFocus >= 0) {
            int idx = pendingFocus, y = pendingScroll;
            pendingFocus = -1;
            render(false);
            rebuilding = true;
            paneScroll.post(() -> {
                ArrayList<View> all = pane.getFocusables(View.FOCUS_DOWN);
                paneScroll.scrollTo(0, y);
                if (!all.isEmpty()) all.get(Math.min(idx, all.size() - 1)).requestFocus();
                paneScroll.post(() -> rebuilding = false);
            });
        } else {
            render(true);   // coming back from another screen: keep your place
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        ConfigServer.stop();
    }

    private void build() {
        LinearLayout root = Ui.horizontal(this);
        root.setBackgroundColor(Ui.BG);
        root.setBaselineAligned(false);

        LinearLayout left = Ui.vertical(this);
        left.setBackgroundColor(Ui.PANEL);
        left.setPadding(Ui.dp(this, 28), Ui.dp(this, 32), Ui.dp(this, 20), Ui.dp(this, 24));
        TextView title = Ui.heading(this, "Settings", 30);
        title.setLetterSpacing(-0.01f);
        title.setPadding(Ui.dp(this, 12), 0, 0, Ui.dp(this, 20));
        left.addView(title);
        for (int i = 0; i < CATEGORIES.length; i++) left.addView(catRow(ICONS[i], CATEGORIES[i]));
        left.addView(new View(this), new LinearLayout.LayoutParams(1, 0, 1f));
        phoneHint = Ui.text(this, "", 12, Ui.DIM);
        phoneHint.setPadding(Ui.dp(this, 12), Ui.dp(this, 10), 0, 0);
        left.addView(phoneHint);
        ScrollView leftScroll = new FocusScroll.Vertical(this);
        leftScroll.setFillViewport(true);
        leftScroll.addView(left);
        root.addView(leftScroll, new LinearLayout.LayoutParams(Ui.dp(this, 300), ViewGroup.LayoutParams.MATCH_PARENT));

        paneScroll = new FocusScroll.Vertical(this);
        paneScroll.setVerticalScrollBarEnabled(false);
        pane = Ui.vertical(this);
        pane.setPadding(Ui.dp(this, 48), Ui.dp(this, 36), Ui.dp(this, 64), Ui.dp(this, 60));
        paneScroll.addView(pane);
        content = pane;
        root.addView(paneScroll, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        setContentView(root);
        built = true;
        View first = catRows.get(cat);
        if (first != null) first.post(first::requestFocus);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        // Make sure something is focused when Settings opens, so the remote works straight away.
        if (hasFocus && built && getCurrentFocus() == null) {
            View first = catRows.get(cat);
            if (first != null) first.requestFocus();
        }
    }

    private void updatePhoneHint() {
        String addr = ConfigServer.running() ? ConfigServer.address() : null;
        if (phoneHint != null) phoneHint.setText(addr == null ? "" : "Also on your phone:\n" + addr);
    }

    private View catRow(int icon, String[] c) {
        LinearLayout row = Ui.horizontal(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setFocusable(true);
        row.setClickable(true);
        row.setPadding(Ui.dp(this, 14), Ui.dp(this, 9), Ui.dp(this, 12), Ui.dp(this, 9));
        StateListDrawable bg = new StateListDrawable();
        bg.addState(new int[]{android.R.attr.state_focused}, Ui.rounded(this, Ui.FOCUS, 0, 14));
        bg.addState(new int[]{android.R.attr.state_selected}, Ui.rounded(this, Ui.alpha(Color.WHITE, 0.08f), 0, 14));
        bg.addState(new int[]{}, Ui.rounded(this, Color.TRANSPARENT, 0, 14));
        row.setBackground(bg);
        ImageView ic = new ImageView(this);
        ic.setImageResource(icon);
        ic.setImageTintList(new ColorStateList(new int[][]{{android.R.attr.state_focused}, {android.R.attr.state_selected}, {}},
                new int[]{Ui.ON_FOCUS, Ui.ACCENT, Ui.MUTED}));
        ic.setImageTintMode(PorterDuff.Mode.SRC_IN);
        ic.setDuplicateParentStateEnabled(true);
        row.addView(ic, new LinearLayout.LayoutParams(Ui.dp(this, 20), Ui.dp(this, 20)));
        LinearLayout names = Ui.vertical(this);
        names.setPadding(Ui.dp(this, 14), 0, 0, 0);
        TextView t = Ui.single(Ui.text(this, c[1], 15, Ui.TEXT));
        t.setTextColor(new ColorStateList(new int[][]{{android.R.attr.state_focused}, {}}, new int[]{Ui.ON_FOCUS, Ui.TEXT}));
        t.setDuplicateParentStateEnabled(true);
        TextView d = Ui.single(Ui.text(this, c[2], 12, Ui.DIM));
        d.setTextColor(new ColorStateList(new int[][]{{android.R.attr.state_focused}, {}}, new int[]{Ui.alpha(Ui.ON_FOCUS, 0.7f), Ui.DIM}));
        d.setDuplicateParentStateEnabled(true);
        names.addView(t);
        names.addView(d);
        names.setDuplicateParentStateEnabled(true);
        row.addView(names);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, Ui.dp(this, 2), 0, Ui.dp(this, 2));
        row.setLayoutParams(lp);
        String key = c[0];
        row.setOnFocusChangeListener((v, has) -> { if (has && !rebuilding && !key.equals(cat)) { cat = key; render(false); } });
        row.setOnClickListener(v -> {
            if ("profiles".equals(key)) { startActivity(new Intent(this, ProfilesActivity.class)); return; }
            java.util.ArrayList<View> f = pane.getFocusables(View.FOCUS_DOWN);
            if (!f.isEmpty()) f.get(0).requestFocus();
        });
        catRows.put(key, row);
        return row;
    }

    private void render() {
        render(true);
    }

    /** Rebuilds the right-hand pane; keepFocus keeps focus on the same control (e.g. after choosing a pill). */
    private void render(boolean keepFocus) {
        if (!built) return;
        int keep = -1;
        View cur = getCurrentFocus();
        if (keepFocus && cur != null && !(cur instanceof android.widget.EditText)) keep = pane.getFocusables(View.FOCUS_DOWN).indexOf(cur);
        for (Map.Entry<String, View> e : catRows.entrySet()) e.getValue().setSelected(e.getKey().equals(cat));
        int scroll = keep >= 0 ? paneScroll.getScrollY() : 0;
        // While the pane is rebuilt the focused control disappears and Android moves focus to the nearest
        // view — often a category on the left, which would switch panes. Ignore that until we're done.
        rebuilding = true;
        pane.removeAllViews();
        content = pane;
        switch (cat) {
            case "home": paneHome(); break;
            case "categories": paneCategories(); break;
            case "appearance": paneAppearance(); break;
            case "player": panePlayer(); break;
            case "sources": paneSources(); break;
            case "downloads": paneDownloads(); break;
            case "live": paneLive(); break;
            case "smarthome": paneSmartHome(); break;
            case "search": paneSearch(); break;
            case "system": paneSystem(); break;
            default: paneProfiles(); break;
        }
        styleFields(pane);
        paneScroll.scrollTo(0, scroll);
        if (keep >= 0) {
            ArrayList<View> all = pane.getFocusables(View.FOCUS_DOWN);
            View f = keep < all.size() ? all.get(keep) : (all.isEmpty() ? null : all.get(all.size() - 1));
            if (f != null) {
                f.requestFocus();
                paneScroll.post(() -> { paneScroll.scrollTo(0, scroll); f.requestFocus(); rebuilding = false; });
                return;
            }
        }
        paneScroll.post(() -> rebuilding = false);
    }

    private boolean rebuilding;

    private void heading(String title, String sub) {
        content = pane;
        TextView t = Ui.heading(this, title, 30);
        t.setLetterSpacing(-0.01f);
        pane.addView(t);
        if (sub != null) {
            TextView s = Ui.text(this, sub, 15, Ui.MUTED);
            s.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 4));
            s.setMaxWidth(Ui.dp(this, 640));
            pane.addView(s);
        }
    }

    private LinearLayout buttonRow() {
        LinearLayout r = Ui.horizontal(this);
        r.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 4));
        content.addView(r);
        return r;
    }

    // ------------------------------------------------------------ panes

    private void paneProfiles() {
        heading("Profiles & Trakt", "Each profile has its own Favorites, Continue watching and Trakt account.");
        section("Who's watching");
        String user = Trakt.user(Store.profile());
        content.addView(label("Watching now: " + Store.profile() + (Trakt.signedIn() ? " · Trakt: " + (user.isEmpty() ? "linked" : user) : " · Trakt not linked")));
        buttonRow().addView(Ui.button(this, R.drawable.ic_account, "Manage profiles & Trakt", v -> startActivity(new Intent(this, ProfilesActivity.class))));
    }

    private void paneHome() {
        heading("Home screen", null);
        section("Home button");
        boolean isHome = HomeButtonService.isDefaultHome(this), running = HomeButtonService.running(this);
        TextView st = Ui.text(this, HomeButtonService.status(this), 15, isHome || (running && HomeButtonService.enabled(this)) ? Ui.GOOD : Ui.WARN);
        st.setPadding(0, 0, 0, Ui.dp(this, 6));
        content.addView(st);
        if (!isHome) {
            content.addView(label("Like Projectivy: no computer needed. Turn on \"Flux TV Home button\" in the TV's Accessibility "
                    + "settings, and Home (and turning the TV on) opens Flux. Holding Home opens Flux Quick Settings."));
            LinearLayout r = buttonRow();
            r.addView(Ui.button(this, R.drawable.ic_access, running ? "Accessibility settings" : "Turn on in Accessibility settings",
                    v -> HomeButtonService.openSettings(this)));
            if (running) content.addView(label(HomeButtonService.lastSeen()
                    + " Not working after an update? Switch Flux TV Home button off and on again in Accessibility."));
            if (running && !HomeButtonService.recentWindows().isEmpty()) {
                content.addView(label("Built-in home screens seen (for troubleshooting):\n" + HomeButtonService.recentWindows()));
            }
            if (running) {
                r.addView(Ui.pill(this, "Home opens Flux", HomeButtonService.enabled(this), v -> { HomeButtonService.setEnabled(true); render(); }));
                r.addView(Ui.pill(this, "Off", !HomeButtonService.enabled(this), v -> { HomeButtonService.setEnabled(false); render(); }));
            } else {
                if (android.os.Build.VERSION.SDK_INT >= 33) {
                    content.addView(label("Switch turns itself back off? Android 13 and newer (Google TV 14, onn 4K Pro) blocks this for apps "
                            + "installed from a downloaded file. Press Unlock: Flux reinstalls itself the way app stores do (your settings "
                            + "stay), then turn the switch on again."
                            + (Store.get("reinstalled_for_home").isEmpty() ? "" : "\nAlready reinstalled. If the switch still flips off, "
                            + "see the computer fix in the web app (System section).")));
                    buttonRow().addView(Ui.button(this, R.drawable.ic_update, "Unlock the Home button (reinstall Flux)", v -> Updater.reinstall(this)));
                } else {
                    content.addView(label("If the switch is greyed out: open Settings → Apps → Flux TV → ⋮ → Allow restricted settings, then try again."));
                }
            }
        }
        section("Continue watching shows items from");
        content.addView(label("Apps appear here once they've added something to Continue watching."));
        java.util.Set<String> hiddenApps = ContinueWatching.hidden();
        LinearLayout cwRow = null;
        int n = 0;
        for (String[] src : ContinueWatching.sources()) {
            if (n++ % 3 == 0) cwRow = buttonRow();
            boolean on = !hiddenApps.contains(src[0]);
            cwRow.addView(Ui.pill(this, (on ? "✓ " : "") + src[1], on, v -> { ContinueWatching.setHidden(src[0], on); render(); }));
        }
        section("Left dock");
        content.addView(label("Choose what shows in the dock on Home. Hidden items stay in the full menu (press ← on Home)."));
        java.util.Set<String> dockOff = MainActivity.dockHidden();
        for (String item : MainActivity.DOCK_ITEMS) {
            LinearLayout line = buttonRow();
            line.setGravity(Gravity.CENTER_VERTICAL);
            TextView name = Ui.text(this, item.equals("Smart") ? "Smart home" : item, 16, dockOff.contains(item) ? Ui.DIM : Ui.TEXT);
            name.setMinWidth(Ui.dp(this, 200));
            line.addView(name);
            if (item.equals("Home")) {
                line.addView(Ui.text(this, "Always shown", 14, Ui.DIM));
                continue;
            }
            boolean off = dockOff.contains(item);
            line.addView(Ui.pill(this, off ? "Hidden" : "Shown", !off, v -> {
                java.util.Set<String> now = MainActivity.dockHidden();
                if (off) now.remove(item); else now.add(item);
                Store.put("dock_hidden", android.text.TextUtils.join("|", now));
                render();
            }));
        }
        section("Weather");
        String wp = Weather.place(), wl = Weather.placeLabel();
        content.addView(label(wp.isEmpty() ? "Shows the weather next to the clock at the top right. Enter a ZIP code or a city."
                : "Weather for " + (wl.isEmpty() ? wp : wl) + " shows next to the clock."));
        LinearLayout wr = buttonRow();
        wr.setGravity(Gravity.CENTER_VERTICAL);
        android.widget.EditText place = new android.widget.EditText(this);
        place.setSingleLine(true);
        place.setHint("ZIP code or city, e.g. 60601 or Chicago");
        place.setText(wp);
        place.setTextColor(Ui.TEXT);
        place.setHintTextColor(Ui.DIM);
        place.setTextSize(15);
        wr.addView(place, new LinearLayout.LayoutParams(Ui.dp(this, 360), ViewGroup.LayoutParams.WRAP_CONTENT));
        wr.addView(Ui.button(this, "Save", v -> {
            String p = place.getText().toString().trim();
            Weather.setPlace(p);
            if (p.isEmpty()) { render(); return; }
            App.async(Weather::now, w -> {
                Toast.makeText(this, w == null ? "Saved" : Weather.describe(w) + " in " + w.place, Toast.LENGTH_LONG).show();
                refreshBadge();
                render();
            }, e -> Toast.makeText(this, "Couldn't find that place: " + App.message(e), Toast.LENGTH_LONG).show());
        }));
        if (!wp.isEmpty()) wr.addView(Ui.button(this, "Remove", v -> { Weather.setPlace(""); refreshBadge(); render(); }));
        LinearLayout units = buttonRow();
        units.addView(Ui.pill(this, "°F", Weather.fahrenheit(), v -> { Store.put("weather_unit", "f"); Weather.setPlace(Weather.place()); refreshBadge(); render(); }));
        units.addView(Ui.pill(this, "°C", !Weather.fahrenheit(), v -> { Store.put("weather_unit", "c"); Weather.setPlace(Weather.place()); refreshBadge(); render(); }));
        section("Region for streaming services");
        LinearLayout regions = buttonRow();
        String current = Tmdb.region();
        for (String r : new String[]{"US", "CA", "GB", "AU", "IE", "DE", "FR", "NL"}) {
            regions.addView(Ui.pill(this, r, r.equals(current), v -> {
                Store.put("region", r);
                Store.bumpSources();
                render();
            }));
        }
    }

    /** The clock badge picks up a new weather place straight away. */
    private void refreshBadge() {
        View b = getWindow().getDecorView().findViewWithTag(ClockBadge.TAG);
        if (b instanceof ClockBadge) ((ClockBadge) b).refreshWeather(true);
    }

    // ------------------------------------------------------------ categories (rows on Home) + MDBList

    private List<MdbList.ListInfo> mdbTop, mdbMine;
    private String mdbError;

    private void paneCategories() {
        heading("Categories", "The rows on Home, and lists you add from MDBList.");
        section("Rows on Home");
        content.addView(label("Continue watching, your apps and streaming services stay at the top. Then these, in this order."));
        List<String> order = new ArrayList<>();
        Map<String, Addons.Row> byKey = new LinkedHashMap<>();
        for (Addons.Row r : HomeLayout.ordered(HomeLayout.available)) {
            order.add(HomeLayout.key(r));
            byKey.put(HomeLayout.key(r), r);
        }
        java.util.Set<String> hidden = HomeLayout.hidden();
        if (order.isEmpty()) content.addView(label("Open Home once so Flux knows which rows there are."));
        for (int i = 0; i < order.size(); i++) {
            String key = order.get(i);
            Addons.Row r = byKey.get(key);
            int idx = i;
            boolean shown = !hidden.contains(key);
            LinearLayout line = buttonRow();
            line.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout names = Ui.vertical(this);
            TextView t = Ui.single(Ui.text(this, r.title, 16, shown ? Ui.TEXT : Ui.DIM));
            names.addView(t);
            if (!r.note.isEmpty()) names.addView(Ui.text(this, r.note, 12, Ui.DIM));
            line.addView(names, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            line.addView(Ui.pill(this, shown ? "Shown" : "Hidden", shown, v -> {
                if (hidden.contains(key)) hidden.remove(key); else hidden.add(key);
                HomeLayout.save(order, hidden);
                render();
            }));
            line.addView(Ui.button(this, "▲", v -> {
                if (idx == 0) return;
                java.util.Collections.swap(order, idx, idx - 1);
                HomeLayout.save(order, hidden);
                render();
            }));
            line.addView(Ui.button(this, "▼", v -> {
                if (idx >= order.size() - 1) return;
                java.util.Collections.swap(order, idx, idx + 1);
                HomeLayout.save(order, hidden);
                render();
            }));
        }

        section("MDBList");
        content.addView(label("Add any list from mdblist.com as a row on Home (movie lists also show in Movies, show lists in TV Shows). "
                + "Get a free API key at mdblist.com/preferences → API Access. Public lists also work without one."));
        content.addView(keyField("mdblist_key", "MDBList API key"));
        LinearLayout add = buttonRow();
        add.setGravity(Gravity.CENTER_VERTICAL);
        android.widget.EditText link = new android.widget.EditText(this);
        link.setSingleLine(true);
        link.setHint("List link, e.g. mdblist.com/lists/username/list-name");
        link.setTextColor(Ui.TEXT);
        link.setHintTextColor(Ui.DIM);
        link.setTextSize(15);
        add.addView(link, new LinearLayout.LayoutParams(Ui.dp(this, 460), ViewGroup.LayoutParams.WRAP_CONTENT));
        add.addView(Ui.button(this, "Add", v -> {
            String u = link.getText().toString();
            Toast.makeText(this, "Checking the list…", Toast.LENGTH_SHORT).show();
            App.async(() -> MdbList.add(u), name -> {
                Toast.makeText(this, "Added \u201C" + name + "\u201D to Home", Toast.LENGTH_LONG).show();
                render(true);
            }, e -> Toast.makeText(this, App.message(e), Toast.LENGTH_LONG).show());
        }));
        List<MdbList.ListInfo> saved = MdbList.saved();
        if (!saved.isEmpty()) content.addView(label("Your rows from MDBList"));
        for (MdbList.ListInfo l : saved) {
            LinearLayout line = buttonRow();
            line.setGravity(Gravity.CENTER_VERTICAL);
            line.addView(Ui.text(this, l.name, 16, Ui.TEXT), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            line.addView(Ui.button(this, "Remove", v -> { MdbList.remove(l.url); render(true); }));
        }
        if (MdbList.key().isEmpty()) return;
        if (mdbTop == null && mdbError == null) {
            content.addView(label("Loading popular lists…"));
            App.async(() -> new Object[]{MdbList.topLists(), MdbList.myLists()}, r -> {
                @SuppressWarnings("unchecked") List<MdbList.ListInfo> top = (List<MdbList.ListInfo>) r[0];
                @SuppressWarnings("unchecked") List<MdbList.ListInfo> mine = (List<MdbList.ListInfo>) r[1];
                mdbTop = top;
                mdbMine = mine;
                if ("categories".equals(cat)) render(true);
            }, e -> { mdbError = App.message(e); if ("categories".equals(cat)) render(true); });
            return;
        }
        if (mdbError != null) {
            content.addView(label("Couldn't load lists from MDBList: " + mdbError));
            buttonRow().addView(Ui.button(this, "Try again", v -> { mdbError = null; render(true); }));
            return;
        }
        listPills("Your MDBList lists", mdbMine, saved);
        listPills("Popular lists", mdbTop, saved);
    }

    private void listPills(String title, List<MdbList.ListInfo> lists, List<MdbList.ListInfo> saved) {
        if (lists == null || lists.isEmpty()) return;
        content.addView(label(title));
        LinearLayout row = null;
        int n = 0;
        for (MdbList.ListInfo l : lists) {
            boolean have = false;
            for (MdbList.ListInfo s2 : saved) if (s2.url.equals(l.url)) have = true;
            if (have) continue;
            if (n++ % 3 == 0) row = buttonRow();
            row.addView(Ui.pill(this, "+ " + l.name, false, v -> {
                MdbList.add(l);
                Toast.makeText(this, "Added \u201C" + l.name + "\u201D to Home", Toast.LENGTH_SHORT).show();
                render(true);
            }));
        }
    }

    private List<SmartHome.Device> shDevices;
    private String shError;

    private void paneSmartHome() {
        heading("Smart home", "A panel for your lights and devices that opens over anything you're watching.");
        section("Home Assistant");
        if (HomeAssistant.configured()) {
            content.addView(label("Connected to " + HomeAssistant.url()));
            LinearLayout b = buttonRow();
            b.addView(Ui.button(this, R.drawable.ic_bulb, "Open the panel", v -> startActivity(new Intent(this, SmartPanelActivity.class))));
            b.addView(Ui.button(this, "Disconnect", v -> { HomeAssistant.disconnect(); shDevices = null; render(); }));
        } else {
            content.addView(label("Your Home Assistant address (e.g. http://homeassistant.local:8123) and a long-lived access token: "
                    + "in Home Assistant open your profile → Security → Long-lived access tokens → Create token. "
                    + (ConfigServer.address() != null ? "Easier from your phone: " + ConfigServer.address() + " → Smart home." : "")));
            android.widget.EditText url = new android.widget.EditText(this);
            url.setSingleLine(true);
            url.setHint("http://homeassistant.local:8123");
            url.setText(Store.get("ha_url"));
            url.setTextColor(Ui.TEXT);
            url.setHintTextColor(Ui.DIM);
            content.addView(url, new LinearLayout.LayoutParams(Ui.dp(this, 480), ViewGroup.LayoutParams.WRAP_CONTENT));
            android.widget.EditText token = new android.widget.EditText(this);
            token.setSingleLine(true);
            token.setHint("Long-lived access token");
            token.setTextColor(Ui.TEXT);
            token.setHintTextColor(Ui.DIM);
            content.addView(token, new LinearLayout.LayoutParams(Ui.dp(this, 480), ViewGroup.LayoutParams.WRAP_CONTENT));
            buttonRow().addView(Ui.button(this, "Connect", v -> App.async(() -> HomeAssistant.connect(url.getText().toString(), token.getText().toString()),
                    n -> { Toast.makeText(this, "Connected: " + n, Toast.LENGTH_LONG).show(); shDevices = null; render(); },
                    e -> Toast.makeText(this, "Couldn't connect: " + App.message(e), Toast.LENGTH_LONG).show())));
        }
        section("Google Home (through the TV's Google Assistant)");
        content.addView(label("Add lights and switches by the exact names they have in the Google Home app. Flux sends the "
                + "same words you'd say to the Assistant (\"turn off Kitchen light\", \"set Lamp to 40 percent\"). "
                + "Try a command first to see if your TV carries it out."));
        android.widget.EditText test = new android.widget.EditText(this);
        test.setSingleLine(true);
        test.setHint("A command, e.g. turn off kitchen light");
        test.setTextColor(Ui.TEXT);
        test.setHintTextColor(Ui.DIM);
        content.addView(test, new LinearLayout.LayoutParams(Ui.dp(this, 480), ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout tr = buttonRow();
        String how = Store.get("gc_method");
        tr.addView(Ui.button(this, R.drawable.ic_mic, "Test", v -> {
            try { GoogleCommands.send(test.getText().toString().isEmpty() ? "what time is it" : test.getText().toString()); }
            catch (Exception e) { Toast.makeText(this, App.message(e), Toast.LENGTH_LONG).show(); }
        }));
        tr.addView(Ui.pill(this, "Way 1", !"web".equals(how), v -> { Store.put("gc_method", "search"); render(); }));
        tr.addView(Ui.pill(this, "Way 2", "web".equals(how), v -> { Store.put("gc_method", "web"); render(); }));
        content.addView(label("If Test only shows search results, switch to Way 2 and test again."));
        android.widget.EditText name = new android.widget.EditText(this);
        name.setSingleLine(true);
        name.setHint("Device name in Google Home, e.g. Living room lights");
        name.setTextColor(Ui.TEXT);
        name.setHintTextColor(Ui.DIM);
        content.addView(name, new LinearLayout.LayoutParams(Ui.dp(this, 480), ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout kinds = buttonRow();
        for (int k = 0; k < GoogleCommands.KINDS.length; k++) {
            String kind = GoogleCommands.KINDS[k];
            kinds.addView(Ui.pill(this, "+ " + GoogleCommands.KIND_NAMES[k], false, v -> {
                try {
                    GoogleCommands.add(name.getText().toString(), kind);
                    shDevices = null;
                    render();
                } catch (Exception e) { Toast.makeText(this, App.message(e), Toast.LENGTH_LONG).show(); }
            }));
        }
        for (org.json.JSONObject o : GoogleCommands.saved()) {
            LinearLayout r = buttonRow();
            r.setGravity(android.view.Gravity.CENTER_VERTICAL);
            String n = o.optString("name");
            String kn = "";
            for (int k = 0; k < GoogleCommands.KINDS.length; k++) if (GoogleCommands.KINDS[k].equals(o.optString("kind"))) kn = GoogleCommands.KIND_NAMES[k];
            r.addView(Ui.text(this, n + "  ·  " + kn, 15, Ui.TEXT));
            r.addView(Ui.pill(this, "Remove", false, v -> { GoogleCommands.remove(n); shDevices = null; render(); }));
        }

        section("Show over other apps");
        boolean acc = HomeButtonService.instance != null;
        boolean perm = android.os.Build.VERSION.SDK_INT >= 23 && android.provider.Settings.canDrawOverlays(this);
        content.addView(label(acc || perm ? "On: the panel slides in over whatever is playing."
                : "Off: the panel opens as its own screen. To show it over videos, turn on Flux TV Home button in Accessibility "
                + "(Settings → Home screen), or allow Flux to \"Display over other apps\"."));
        if (!acc && !perm) {
            buttonRow().addView(Ui.button(this, R.drawable.ic_access, "Display over other apps", v -> {
                try {
                    startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:" + getPackageName())));
                } catch (Exception e) {
                    Toast.makeText(this, "This TV has no such screen. Use the Accessibility option instead, or from a computer: "
                            + "adb shell appops set " + getPackageName() + " SYSTEM_ALERT_WINDOW allow", Toast.LENGTH_LONG).show();
                }
            }));
        }
        content.addView(label("Open it with: Quick Settings (hold Home) → Smart home, the sidebar, the \"Flux Smart Home\" app "
                + "(map it to a remote button with Button Mapper), or the link fluxtv://smarthome."));

        if (!SmartHome.configured()) return;
        section("Devices on the panel");
        if (shDevices == null) {
            content.addView(label(shError != null ? "Couldn't load devices: " + shError : "Loading devices…"));
            if (shError == null) App.async(SmartHome::all, l -> { shDevices = l; shError = null; render(); }, e -> { shError = App.message(e); render(); });
            else shError = null;
            return;
        }
        LinearLayout all = buttonRow();
        all.addView(Ui.pill(this, "Show all", false, v -> { SmartHome.setAllShown(shDevices, true); render(); }));
        all.addView(Ui.pill(this, "Hide all", false, v -> { SmartHome.setAllShown(shDevices, false); render(); }));
        all.addView(Ui.pill(this, "Reload", false, v -> { shDevices = null; render(); }));
        String room = null;
        LinearLayout row = null;
        int n = 0;
        for (SmartHome.Device d : shDevices) {
            String r = d.room.isEmpty() ? "Other" : d.room;
            if (!r.equals(room)) {
                room = r;
                TextView h = Ui.text(this, r, 13, Ui.DIM);
                h.setPadding(0, Ui.dp(this, 10), 0, 0);
                content.addView(h);
                n = 0;
            }
            if (n++ % 3 == 0) row = buttonRow();
            boolean on = SmartHome.shown(d);
            row.addView(Ui.pill(this, (on ? "✓ " : "") + d.name, on, v -> { SmartHome.setShown(shDevices, d.id, !on); render(); }));
        }
    }

    private void paneLive() {
        heading("Live TV & DVR", "TV guide, preview and recordings.");
        section("Providers");
        LinearLayout pr = buttonRow();
        pr.addView(Ui.button(this, R.drawable.ic_live, Iptv.sources().isEmpty() ? "Add an IPTV provider" : "IPTV providers ("
                + Iptv.sources().size() + ")", v -> startActivity(new Intent(this, IptvActivity.class))));
        pr.addView(Ui.button(this, R.drawable.ic_tv, "Open Live TV", v -> startActivity(new Intent(this, LiveActivity.class))));

        section("TV guide");
        StringBuilder g = new StringBuilder();
        for (Iptv.Source s : Iptv.sources()) {
            long age = Iptv.guideAge(s);
            g.append(s.name).append(": ").append(age == Long.MAX_VALUE ? "not loaded yet" : "updated " + (age / 60000 < 60 ? age / 60000 + " min ago" : age / 3600000 + " h ago"))
             .append(s.epg.isEmpty() ? (s.xtream() || s.stalker() ? " (from the provider)" : " (no EPG link)") : " (your EPG link)").append("\n");
        }
        content.addView(label(g.length() == 0 ? "Add a provider first." : g.toString().trim()
                + "\nChannels, the guide and logos are saved on this TV and refreshed in the background every few hours, so Live TV opens instantly."));
        LinearLayout gr = buttonRow();
        gr.setGravity(Gravity.CENTER_VERTICAL);
        boolean busy = Iptv.guideBusy();
        gr.addView(Ui.button(this, R.drawable.ic_update, busy ? "Downloading…" : "Download guides now", v -> {
            if (Iptv.guideBusy()) return;
            App.async(Iptv::downloadAllGuides,
                    msg -> { Toast.makeText(this, msg, Toast.LENGTH_LONG).show(); render(true); },
                    e -> Toast.makeText(this, "Guide: " + App.message(e), Toast.LENGTH_LONG).show());
            App.MAIN.postDelayed(() -> render(true), 300);
        }));
        // While a guide downloads (from here or in the background): a spinner and what it's doing.
        LinearLayout prog = Ui.horizontal(this);
        prog.setGravity(Gravity.CENTER_VERTICAL);
        android.widget.ProgressBar spin = new android.widget.ProgressBar(this);
        spin.setIndeterminate(true);
        spin.setIndeterminateTintList(ColorStateList.valueOf(Ui.ACCENT));
        prog.addView(spin, new LinearLayout.LayoutParams(Ui.dp(this, 22), Ui.dp(this, 22)));
        TextView pt = Ui.text(this, Iptv.guideStatus(), 14, Ui.TEXT);
        pt.setPadding(Ui.dp(this, 12), 0, 0, 0);
        prog.addView(pt);
        prog.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 4));
        prog.setVisibility(busy ? View.VISIBLE : View.GONE);
        content.addView(prog);
        guideProgress = pt;
        guideProgressRow = prog;
        App.MAIN.removeCallbacks(guideWatch);
        App.MAIN.postDelayed(guideWatch, 1000);
        String last = Store.get("epg_last");
        if (!last.isEmpty()) content.addView(label("Last download: " + last));
        content.addView(label("Download every"));
        content.addView(choices(new String[]{"3 hours", "6 hours", "12 hours", "Day"}, "epg_refresh", 0));

        section("Channels");
        paneLiveChannels();

        section("Extra guide sources");
        content.addView(label("XMLTV links (.xml or .xml.gz) that fill in channels your provider's guide is missing. "
                + "Channels are matched by guide id first, then by name. Used for every provider."));
        List<String> extra = Iptv.extraEpgUrls();
        for (String u : extra) {
            LinearLayout line = buttonRow();
            line.setGravity(Gravity.CENTER_VERTICAL);
            String st = Store.get("epg_status:" + u);
            TextView t = Ui.text(this, u + "\n" + (st.isEmpty() ? "Not downloaded yet" : st), 13,
                    st.startsWith("Failed") ? Color.parseColor("#F87171") : Ui.TEXT);
            line.addView(t, new LinearLayout.LayoutParams(Ui.dp(this, 560), ViewGroup.LayoutParams.WRAP_CONTENT));
            line.addView(Ui.button(this, "Remove", v -> {
                List<String> l = Iptv.extraEpgUrls();
                l.remove(u);
                Iptv.setExtraEpgUrls(l);
                render(true);
            }));
        }
        LinearLayout add = buttonRow();
        add.setGravity(Gravity.CENTER_VERTICAL);
        android.widget.EditText eu = new android.widget.EditText(this);
        eu.setSingleLine(true);
        eu.setHint("https://…/guide.xml.gz");
        eu.setTextColor(Ui.TEXT);
        eu.setHintTextColor(Ui.DIM);
        eu.setTextSize(15);
        add.addView(eu, new LinearLayout.LayoutParams(Ui.dp(this, 460), ViewGroup.LayoutParams.WRAP_CONTENT));
        add.addView(Ui.button(this, "Add", v -> {
            String u = eu.getText().toString().trim();
            if (!u.startsWith("http")) { Toast.makeText(this, "Enter a link starting with http", Toast.LENGTH_SHORT).show(); return; }
            addEpg(u);
        }));
        LinearLayout presets = null;
        String[][] known = {{"US (epgshare01)", "https://epgshare01.online/epgshare01/epg_ripper_US1.xml.gz"},
                {"UK (epgshare01)", "https://epgshare01.online/epgshare01/epg_ripper_UK1.xml.gz"},
                {"Canada (epgshare01)", "https://epgshare01.online/epgshare01/epg_ripper_CA1.xml.gz"}};
        for (String[] k : known) {
            if (extra.contains(k[1])) continue;
            if (presets == null) { content.addView(label("Free guides")); presets = buttonRow(); }
            presets.addView(Ui.pill(this, "+ " + k[0], false, v -> addEpg(k[1])));
        }
        section("Preview while browsing the guide");
        boolean prev = !"off".equals(Store.get("live_preview"));
        LinearLayout pv = buttonRow();
        pv.addView(Ui.pill(this, "On", prev, v -> { Store.put("live_preview", "on"); render(); }));
        pv.addView(Ui.pill(this, "Off", !prev, v -> { Store.put("live_preview", "off"); render(); }));
        content.addView(label("Turn off if your provider allows only one connection at a time."));

        section("Save recordings to");
        LinearLayout tg = buttonRow();
        String target = Dvr.target();
        for (String[] t : Dvr.localTargets(this)) {
            tg.addView(Ui.pill(this, t[1], t[0].equals(target), v -> { Dvr.setTarget(t[0]); render(); }));
        }
        tg.addView(Ui.pill(this, "Network share", "smb".equals(target), v -> {
            if (Smb.configured()) { Dvr.setTarget("smb"); render(); }
            else startActivity(new Intent(this, SmbBrowseActivity.class));
        }));
        content.addView(label("Now: " + Dvr.targetLabel(this)));
        LinearLayout sm = buttonRow();
        sm.addView(Ui.button(this, R.drawable.ic_folder, Smb.configured() ? "Change network folder" : "Choose a network folder",
                v -> startActivity(new Intent(this, SmbBrowseActivity.class))));
        sm.addView(Ui.button(this, R.drawable.ic_record, "Recordings", v -> startActivity(new Intent(this, RecordingsActivity.class))));

        section("Start early / end late");
        LinearLayout pad = buttonRow();
        int before = Dvr.padBeforeMin(), after = Dvr.padAfterMin();
        for (int m : new int[]{0, 1, 2, 5}) pad.addView(Ui.pill(this, m + " min early", m == before, v -> { Store.put("dvr_pad_before", String.valueOf(m)); render(); }));
        LinearLayout pad2 = buttonRow();
        for (int m : new int[]{0, 3, 5, 10, 15}) pad2.addView(Ui.pill(this, m + " min late", m == after, v -> { Store.put("dvr_pad_after", String.valueOf(m)); render(); }));
        if (!Dvr.exactAlarmsAllowed(this)) {
            content.addView(label("Android may start scheduled recordings a little late. Allow \"Alarms & reminders\" for Flux so they start on time."));
            buttonRow().addView(Ui.button(this, R.drawable.ic_clock, "Allow alarms", v -> {
                try {
                    startActivity(new Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                            android.net.Uri.parse("package:" + getPackageName())));
                } catch (Exception e) {
                    Toast.makeText(this, "This TV has no such setting; recordings may start up to a minute late.", Toast.LENGTH_LONG).show();
                }
            }));
        }
    }

    private TextView guideProgress;
    private View guideProgressRow;
    private boolean guideWasBusy;

    /** Keeps the guide download line up to date while Live TV settings are open. */
    private final Runnable guideWatch = new Runnable() {
        @Override public void run() {
            if (!"live".equals(cat) || guideProgress == null || isFinishing()) return;
            boolean busy = Iptv.guideBusy();
            guideProgress.setText(Iptv.guideStatus());
            guideProgressRow.setVisibility(busy ? View.VISIBLE : View.GONE);
            if (guideWasBusy && !busy) { guideWasBusy = false; render(true); return; }
            guideWasBusy = busy;
            App.MAIN.postDelayed(this, 1000);
        }
    };

    /** [code → channel count] across providers, and [shown, total]; worked out in the background. */
    private Map<String, Integer> liveRegions;
    private int[] liveShown;

    private void paneLiveChannels() {
        if (Iptv.sources().isEmpty()) {
            content.addView(label("Add a provider first."));
            return;
        }
        if (liveRegions == null || liveShown == null) {
            content.addView(label("Counting channels…"));
            App.async(() -> {
                Map<String, Integer> counts = new LinkedHashMap<>();
                int shown = 0, total = 0;
                for (Iptv.Source s : Iptv.sources()) {
                    for (Iptv.Channel c : Iptv.channels(s)) {
                        total++;
                        if (Iptv.shown(s, c)) shown++;
                        String r = Iptv.regionOf(s, c);
                        if (!r.isEmpty()) counts.put(r, counts.containsKey(r) ? counts.get(r) + 1 : 1);
                    }
                }
                List<Map.Entry<String, Integer>> sorted = new ArrayList<>(counts.entrySet());
                java.util.Collections.sort(sorted, (a, b) -> b.getValue() - a.getValue());
                Map<String, Integer> out = new LinkedHashMap<>();
                for (Map.Entry<String, Integer> e : sorted) out.put(e.getKey(), e.getValue());
                return new Object[]{out, new int[]{shown, total}};
            }, r -> {
                @SuppressWarnings("unchecked") Map<String, Integer> m = (Map<String, Integer>) r[0];
                liveRegions = m;
                liveShown = (int[]) r[1];
                if ("live".equals(cat)) render(true);
            }, e -> content.addView(label("Couldn't load channels: " + App.message(e))));
            return;
        }
        content.addView(label(String.format(java.util.Locale.getDefault(), "Live TV shows %,d of %,d channels.", liveShown[0], liveShown[1])));
        java.util.Set<String> chosen = Iptv.regions();
        if (!liveRegions.isEmpty()) {
            content.addView(label("Only show channels from these countries (read from the group and channel names, e.g. \"US| ESPN\"):"));
            LinearLayout row = buttonRow();
            row.addView(Ui.pill(this, "All countries", chosen.isEmpty(), v -> { Iptv.setRegions(new ArrayList<>()); liveShown = null; render(); }));
            int n = 1;
            for (Map.Entry<String, Integer> e : liveRegions.entrySet()) {
                if (n++ % 6 == 0) row = buttonRow();
                String code = e.getKey();
                boolean on = chosen.contains(code);
                String name = Iptv.COUNTRIES.containsKey(code) ? Iptv.COUNTRIES.get(code) : code;
                row.addView(Ui.pill(this, (on ? "✓ " : "") + name + " · " + String.format(java.util.Locale.getDefault(), "%,d", e.getValue()), on, v -> {
                    java.util.Set<String> now = Iptv.regions();
                    if (on) now.remove(code); else now.add(code);
                    Iptv.setRegions(now);
                    liveShown = null;
                    render();
                }));
            }
            if (!chosen.isEmpty()) {
                content.addView(label("Channels with no country in their name"));
                LinearLayout u = buttonRow();
                boolean hide = Iptv.hideUnknownRegion();
                u.addView(Ui.pill(this, "Show them", !hide, v -> { Iptv.setHideUnknownRegion(false); liveShown = null; render(); }));
                u.addView(Ui.pill(this, "Hide them", hide, v -> { Iptv.setHideUnknownRegion(true); liveShown = null; render(); }));
            }
        } else {
            content.addView(label("Your provider's channel names don't say which country they're from, so hide groups instead."));
        }
        LinearLayout b = buttonRow();
        b.addView(Ui.button(this, R.drawable.ic_list, "Hide groups and channels…", v -> startActivity(new Intent(this, IptvFilterActivity.class))));
        if (Iptv.hiddenCount() > 0 || !chosen.isEmpty()) {
            b.addView(Ui.button(this, "Show everything again", v -> { Iptv.showEverything(); liveShown = null; render(); }));
        }
        content.addView(label("Tip: in the TV guide, press ⋮ (or hold OK) on a channel to hide it or its whole group."));
    }

    private void paneAppearance() {
        heading("Appearance", null);
        section("Themes");
        LinearLayout grid = null;
        int current = Theme.themeIndex();
        for (int i = 0; i < Theme.THEMES.length; i++) {
            if (i % 4 == 0) {
                grid = Ui.horizontal(this);
                grid.setBaselineAligned(false);
                grid.setGravity(Gravity.TOP);
                content.addView(grid, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            int idx = i;
            grid.addView(themeTile(i, i == current, v -> {
                Theme.setTheme(idx);
                recreate();
            }));
        }
        content.addView(label(current >= 0 ? "Theme: " + Theme.THEMES[current][0] : "Custom colours"));
        section("Accent colour");
        LinearLayout accents = buttonRow();
        for (int i = 0; i < Theme.ACCENT_HEX.length; i++) {
            int idx = i;
            accents.addView(swatch(Color.parseColor(Theme.ACCENT_HEX[i]), Theme.ACCENT_NAMES[i], i == Theme.accentIndex(), v -> {
                Theme.setAccent(idx);
                recreate();
            }));
        }
        content.addView(label(Theme.ACCENT_NAMES[Theme.accentIndex()]));
        section("Highlight colour");
        content.addView(label("The colour that shows what the remote has selected."));
        LinearLayout highlightModes = buttonRow();
        highlightModes.addView(Ui.pill(this, "White", Theme.highlightIndex() == 0, v -> { Theme.setHighlight(0); recreate(); }));
        highlightModes.addView(Ui.pill(this, "Match theme", Theme.highlightIndex() == 1, v -> { Theme.setHighlight(1); recreate(); }));
        LinearLayout highlights = buttonRow();
        for (int i = 0; i < Theme.ACCENT_HEX.length; i++) {
            int idx = i + 2;
            highlights.addView(swatch(Color.parseColor(Theme.ACCENT_HEX[i]), Theme.ACCENT_NAMES[i], idx == Theme.highlightIndex(), v -> {
                Theme.setHighlight(idx);
                recreate();
            }));
        }
        content.addView(label("Highlight: " + Theme.highlightName()));
        section("Background");
        LinearLayout bgs = buttonRow();
        for (int i = 0; i < Theme.BG_NAMES.length; i++) {
            int idx = i;
            bgs.addView(Ui.pill(this, Theme.BG_NAMES[i], i == Theme.bgIndex(), v -> {
                Theme.setBackground(idx);
                recreate();
            }));
        }
    }

    private void panePlayer() {
        heading("Player", "Press OK or ↑ while watching for the controls. Subtitles and Audio are in the bar; everything else is under ⋮.");
        section("Subtitles");
        LinearLayout subs = buttonRow();
        String mode = Store.get("player_subs");
        String[][] modes = {{"", "When the video says so"}, {"off", "Always off"}, {"on", "Always on"}};
        for (String[] m : modes) subs.addView(Ui.pill(this, m[1], m[0].equals(mode), v -> { Store.put("player_subs", m[0]); render(); }));
        content.addView(label("Size"));
        content.addView(choices(PlayerActivity.SUB_SIZES, "player_sub_size", 1));
        content.addView(label("Style"));
        content.addView(choices(PlayerActivity.SUB_STYLES, "player_sub_style", 0));
        section("Picture size");
        content.addView(choices(PlayerActivity.ZOOMS, "player_zoom", 0));
        section("Skip intro & credits");
        content.addView(label("Times come from the community databases IntroDB and TheIntroDB, so shows without data get no button."));
        content.addView(choices(PlayerActivity.SKIP_MODES, "player_skip", 0));
        section("Play next episode");
        content.addView(label("Shows the next episode when the credits start (or 30 s before the end)."));
        content.addView(choices(PlayerActivity.NEXT_MODES, "player_next", 1));
        section("Stream size limits");
        content.addView(label("Bigger streams are hidden and never auto-played. Your own library is never limited."));
        for (int q = 0; q < SizeLimits.QUALITIES.length; q++) {
            String quality = SizeLimits.QUALITIES[q];
            LinearLayout line = buttonRow();
            line.setGravity(Gravity.CENTER_VERTICAL);
            TextView name = Ui.heading(this, quality, 14);
            name.setMinWidth(Ui.dp(this, 70));
            line.addView(name);
            double curGb = SizeLimits.max(quality);
            for (double gb : SizeLimits.CHOICES[q]) {
                line.addView(Ui.pill(this, SizeLimits.label(gb), Math.abs(curGb - gb) < 0.01, v -> { SizeLimits.set(quality, gb); render(); }));
            }
        }
        String al = Store.get("player_audio_lang"), sl = Store.get("player_sub_lang");
        section("Languages");
        content.addView(label("Audio: " + (al.isEmpty() ? "no preference" : PlayerActivity.languageName(al))
                + "   ·   Subtitles: " + (sl.isEmpty() ? "no preference" : PlayerActivity.languageName(sl))
                + "\nFlux remembers the language you pick while watching. You can also set them on the phone page."));
    }

    private void paneSources() {
        heading("Sources", "Where Flux finds things to watch.");
        section("Connected");
        sourceRow(R.drawable.ic_addons, "Add-ons", Store.addonUrls().size() + " installed", AddonsActivity.class);
        sourceRow(R.drawable.ic_servers, "Media servers", Store.servers().isEmpty() ? "Plex, Jellyfin or Emby" : Store.servers().size() + " connected", ServersActivity.class);
        sourceRow(R.drawable.ic_cloud, "Real-Debrid", RealDebrid.isSignedIn() ? "Signed in" : "Not signed in", AccountActivity.class);
        sourceRow(R.drawable.ic_live, "Live TV & IPTV", Iptv.sources().isEmpty() ? "Xtream Codes or M3U" : Iptv.sources().size() + " provider(s)", IptvActivity.class);
    }

    private void sourceRow(int icon, String title, String status, Class<?> screen) {
        android.widget.Button b = Ui.listButton(this, title + "\n" + status, v -> startActivity(new Intent(this, screen)));
        android.graphics.drawable.Drawable d = getResources().getDrawable(icon, getTheme()).mutate();
        d.setBounds(0, 0, Ui.dp(this, 22), Ui.dp(this, 22));
        b.setCompoundDrawables(d, null, null, null);
        b.setCompoundDrawablePadding(Ui.dp(this, 16));
        b.setCompoundDrawableTintList(Ui.onFocusDark(Ui.TEXT));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(this, 560), ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, Ui.dp(this, 4), 0, Ui.dp(this, 4));
        b.setLayoutParams(lp);
        content.addView(b);
    }

    private void paneDownloads() {
        heading("Downloads", "Radarr, Sonarr and SABnzbd.");
        section("Connections");
        LinearLayout r = buttonRow();
        r.addView(Ui.button(this, R.drawable.ic_download, Arr.anyConfigured() ? "Connections" : "Connect Radarr, Sonarr, SABnzbd",
                v -> startActivity(new Intent(this, ArrSetupActivity.class))));
        if (Arr.anyConfigured()) r.addView(Ui.button(this, "Open Downloads", v -> startActivity(new Intent(this, DownloadsActivity.class))));
        if (Arr.RADARR.configured() || Arr.SONARR.configured()) {
            section("When nothing can play");
            content.addView(label("If a title isn't on your servers or Real-Debrid"));
            LinearLayout fb = buttonRow();
            fb.addView(Ui.pill(this, "Send to Radarr / Sonarr automatically", Arr.fallbackOn(), v -> { Arr.setFallback(true); render(); }));
            fb.addView(Ui.pill(this, "Only when I press the button", !Arr.fallbackOn(), v -> { Arr.setFallback(false); render(); }));
        }
    }

    private void paneSearch() {
        heading("Search", null);
        section("Gemini (smart voice search)");
        content.addView(label("API key from aistudio.google.com"));
        content.addView(keyField("gemini_key", "Gemini API key"));
        LinearLayout gs = buttonRow();
        gs.addView(Ui.pill(this, "Fast (Flash-Lite)", Gemini.fast(), v -> { Gemini.setFast(true); render(); }));
        gs.addView(Ui.pill(this, "Smarter (Flash)", !Gemini.fast(), v -> { Gemini.setFast(false); render(); }));
        section("Voice search screen");
        LinearLayout vs = buttonRow();
        boolean sys = Store.get("voice_ui").equals("system");
        vs.addView(Ui.pill(this, "Flux's own", !sys, v -> { Store.put("voice_ui", ""); render(); }));
        vs.addView(Ui.pill(this, "The TV's built-in one", sys, v -> { Store.put("voice_ui", "system"); render(); }));
        section("TMDB (streaming services, cast & crew)");
        content.addView(label("API key or read token from themoviedb.org/settings/api"));
        content.addView(keyField("tmdb_key", "TMDB API key"));
    }

    private void paneSystem() {
        heading("System", null);
        String addr = ConfigServer.running() ? ConfigServer.address() : null;
        if (addr != null) {
            section("Set up from your phone");
            TextView a = Ui.heading(this, addr, 22);
            a.setTextColor(Ui.ACCENT);
            content.addView(a);
            content.addView(label("Open this on a phone or computer on the same Wi-Fi while Settings is open. Every setting is there."));
        }
        section("Pictures");
        content.addView(label(ImageLoader.report()));
        LinearLayout pics = buttonRow();
        pics.addView(Ui.button(this, R.drawable.ic_update, "Clear saved pictures", v -> {
            App.async(() -> { ImageLoader.clear(); return true; }, ok -> {
                Toast.makeText(this, "Pictures will download fresh", Toast.LENGTH_SHORT).show();
                render(true);
            }, null);
        }));
        pics.addView(Ui.button(this, "Refresh this report", v -> render(true)));
        section("Flux TV " + Updater.currentName(this));
        LinearLayout sys = buttonRow();
        sys.addView(Ui.button(this, R.drawable.ic_update, "Check for updates", v -> Updater.check(this, true)));
        sys.addView(Ui.button(this, R.drawable.ic_apps, "All apps", v -> startActivity(new Intent(this, AppsActivity.class))));
        sys.addView(Ui.button(this, R.drawable.ic_settings, "TV settings", v -> {
            try {
                startActivity(new Intent(Settings.ACTION_SETTINGS));
            } catch (ActivityNotFoundException e) {
                Toast.makeText(this, "Settings not available", Toast.LENGTH_SHORT).show();
            }
        }));
    }

    private View choices(String[] names, String key, int def) {
        LinearLayout row = Ui.horizontal(this);
        int cur = PlayerActivity.intPref(key, def);
        for (int i = 0; i < names.length; i++) {
            int idx = i;
            row.addView(Ui.pill(this, names[i], i == cur, v -> { Store.put(key, String.valueOf(idx)); render(); }));
        }
        return row;
    }

    private void addEpg(String url) {
        List<String> l = Iptv.extraEpgUrls();
        if (!l.contains(url)) l.add(url);
        Iptv.setExtraEpgUrls(l);
        Toast.makeText(this, "Added. Downloading guides…", Toast.LENGTH_SHORT).show();
        render(true);
        App.async(Iptv::downloadAllGuides, msg -> { Toast.makeText(this, msg, Toast.LENGTH_LONG).show(); render(true); },
                e -> Toast.makeText(this, "Guide: " + App.message(e), Toast.LENGTH_LONG).show());
    }

    /** A key field that shows only the last characters of a saved key. */
    private View keyField(String storeKey, String hint) {
        LinearLayout row = Ui.horizontal(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        android.widget.EditText e = new android.widget.EditText(this);
        e.setSingleLine(true);
        String saved = Store.get(storeKey);
        e.setHint(saved.isEmpty() ? hint + " (not set)" : "Saved ••••" + saved.substring(Math.max(0, saved.length() - 4)));
        e.setTextColor(Ui.TEXT);
        e.setHintTextColor(saved.isEmpty() ? Ui.DIM : Ui.GOOD);
        e.setTextSize(15);
        row.addView(e, new LinearLayout.LayoutParams(Ui.dp(this, 460), ViewGroup.LayoutParams.WRAP_CONTENT));
        row.addView(Ui.button(this, "Save", v -> {
            String k = e.getText().toString().trim();
            if (k.isEmpty()) return;
            Store.put(storeKey, k);
            Store.bumpSources();
            mdbTop = null;
            mdbError = null;
            Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show();
            render();
        }));
        if (!saved.isEmpty()) row.addView(Ui.button(this, "Remove", v -> {
            Store.put(storeKey, "");
            Store.bumpSources();
            render();
        }));
        return row;
    }

    /** Starts a new card titled s; what's added after it (content.addView, buttonRow…) goes inside the card. */
    private void section(String s) {
        LinearLayout card = Ui.vertical(this);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Ui.alpha(Color.WHITE, 0.035f));
        bg.setCornerRadius(Ui.dp(this, 18));
        bg.setStroke(Math.max(1, Ui.dp(this, 1)), Ui.alpha(Color.WHITE, 0.06f));
        card.setBackground(bg);
        card.setPadding(Ui.dp(this, 22), Ui.dp(this, 18), Ui.dp(this, 22), Ui.dp(this, 16));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, Ui.dp(this, 16), 0, 0);
        pane.addView(card, lp);
        TextView t = Ui.heading(this, s, 17);
        t.setPadding(0, 0, 0, Ui.dp(this, 6));
        card.addView(t);
        content = card;
    }

    private TextView label(String s) {
        TextView t = Ui.text(this, s, 14, Ui.MUTED);
        t.setLineSpacing(0, 1.15f);
        t.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 6));
        return t;
    }

    /** Text boxes as rounded fields (not bare underlines), outlined in the highlight colour when selected. */
    private void styleFields(ViewGroup g) {
        for (int i = 0; i < g.getChildCount(); i++) {
            View v = g.getChildAt(i);
            if (v instanceof android.widget.EditText) {
                StateListDrawable s = new StateListDrawable();
                s.addState(new int[]{android.R.attr.state_focused}, Ui.rounded(this, Ui.alpha(Color.WHITE, 0.08f), Ui.FOCUS, 12));
                s.addState(new int[]{}, Ui.rounded(this, Ui.alpha(Color.WHITE, 0.06f), 0, 12));
                v.setBackground(s);
                v.setPadding(Ui.dp(this, 16), Ui.dp(this, 11), Ui.dp(this, 16), Ui.dp(this, 11));
                ViewGroup.LayoutParams lp = v.getLayoutParams();
                if (lp instanceof ViewGroup.MarginLayoutParams) ((ViewGroup.MarginLayoutParams) lp).setMargins(0, Ui.dp(this, 4), Ui.dp(this, 10), Ui.dp(this, 4));
            } else if (v instanceof ViewGroup) {
                styleFields((ViewGroup) v);
            }
        }
    }

    /** A small preview of a theme: its background, a card, and its accent. */
    private View themeTile(int i, boolean selected, View.OnClickListener click) {
        int bg = Theme.bgColor((int) Theme.THEMES[i][2], 0), panel = Theme.bgColor((int) Theme.THEMES[i][2], 2);
        int accent = Color.parseColor(Theme.ACCENT_HEX[(int) Theme.THEMES[i][1]]);
        LinearLayout col = Ui.vertical(this);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        android.widget.FrameLayout tile = new android.widget.FrameLayout(this);
        tile.setFocusable(true);
        tile.setClickable(true);
        tile.setOnClickListener(click);
        tile.setContentDescription((String) Theme.THEMES[i][0]);
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_focused}, Ui.rounded(this, bg, Ui.FOCUS, 12));
        s.addState(new int[]{}, Ui.rounded(this, bg, selected ? accent : Ui.BORDER, 12));
        tile.setBackground(s);
        View card = new View(this);
        card.setBackground(Ui.rounded(this, panel, 0, 6));
        android.widget.FrameLayout.LayoutParams cl = new android.widget.FrameLayout.LayoutParams(Ui.dp(this, 40), Ui.dp(this, 26), Gravity.START | Gravity.CENTER_VERTICAL);
        cl.leftMargin = Ui.dp(this, 12);
        tile.addView(card, cl);
        View dot = new View(this);
        dot.setBackground(Ui.rounded(this, accent, 0, 99));
        android.widget.FrameLayout.LayoutParams dl = new android.widget.FrameLayout.LayoutParams(Ui.dp(this, 22), Ui.dp(this, 22), Gravity.END | Gravity.CENTER_VERTICAL);
        dl.rightMargin = Ui.dp(this, 12);
        tile.addView(dot, dl);
        tile.setOnFocusChangeListener((v, has) -> v.animate().scaleX(has ? 1.06f : 1f).scaleY(has ? 1.06f : 1f).setDuration(120).start());
        col.addView(tile, new LinearLayout.LayoutParams(Ui.dp(this, 100), Ui.dp(this, 60)));
        TextView n = Ui.text(this, (String) Theme.THEMES[i][0], 12, selected ? Ui.TEXT : Ui.MUTED);
        n.setPadding(0, Ui.dp(this, 6), 0, 0);
        col.addView(n);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, Ui.dp(this, 4), Ui.dp(this, 12), Ui.dp(this, 8));
        col.setLayoutParams(lp);
        return col;
    }

    /** Round colour swatch: white ring when focused, check-style inner ring when selected. */
    private View swatch(int color, String name, boolean selected, View.OnClickListener click) {
        View v = new View(this);
        v.setFocusable(true);
        v.setClickable(true);
        v.setContentDescription(name);
        v.setOnClickListener(click);
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_focused}, circle(color, Ui.FOCUS, 4));
        s.addState(new int[]{}, circle(color, selected ? Ui.alpha(Ui.TEXT, 0.6f) : 0, 2));
        v.setBackground(s);
        v.setOnFocusChangeListener((x, has) -> x.animate().scaleX(has ? 1.12f : 1f).scaleY(has ? 1.12f : 1f).setDuration(120).start());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 44));
        lp.setMargins(0, Ui.dp(this, 4), Ui.dp(this, 14), Ui.dp(this, 4));
        v.setLayoutParams(lp);
        return v;
    }

    private GradientDrawable circle(int color, int stroke, float strokeDp) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(color);
        if (stroke != 0) d.setStroke(Ui.dp(this, strokeDp), stroke);
        return d;
    }
}
