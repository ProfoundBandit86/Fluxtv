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
            {"home", "Home screen", "Home button, rows, region"},
            {"appearance", "Appearance", "Themes and colours"},
            {"player", "Player", "Subtitles, picture, size limits"},
            {"sources", "Sources", "Add-ons, servers, Real-Debrid, IPTV"},
            {"live", "Live TV & DVR", "TV guide, recordings, network share"},
            {"smarthome", "Smart home", "Home Assistant, lights panel"},
            {"downloads", "Downloads", "Radarr, Sonarr, SABnzbd"},
            {"search", "Search", "Gemini and TMDB"},
            {"system", "System", "Updates, phone setup, TV settings"},
    };
    private static final int[] ICONS = {R.drawable.ic_account, R.drawable.ic_home, R.drawable.ic_display, R.drawable.ic_play,
            R.drawable.ic_addons, R.drawable.ic_live, R.drawable.ic_bulb, R.drawable.ic_download, R.drawable.ic_search, R.drawable.ic_settings};

    private String cat = "profiles";
    private LinearLayout content;
    private ScrollView paneScroll;
    private TextView phoneHint;
    private final Map<String, View> catRows = new LinkedHashMap<>();
    private boolean built;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getIntent().getStringExtra("category") != null) cat = getIntent().getStringExtra("category");
        build();
        Ratings.guard(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!ConfigServer.running()) ConfigServer.start(() -> { if (!isFinishing()) render(); });
        updatePhoneHint();
        render(false);
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
        left.setPadding(Ui.dp(this, 28), Ui.dp(this, 36), Ui.dp(this, 20), Ui.dp(this, 24));
        TextView title = Ui.heading(this, "Settings", 28);
        title.setPadding(Ui.dp(this, 12), 0, 0, Ui.dp(this, 18));
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
        content = Ui.vertical(this);
        content.setPadding(Ui.dp(this, 48), Ui.dp(this, 36), Ui.dp(this, 48), Ui.dp(this, 60));
        paneScroll.addView(content);
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
        row.setPadding(Ui.dp(this, 12), Ui.dp(this, 8), Ui.dp(this, 12), Ui.dp(this, 8));
        StateListDrawable bg = new StateListDrawable();
        bg.addState(new int[]{android.R.attr.state_focused}, Ui.rounded(this, Ui.TEXT, 0, 12));
        bg.addState(new int[]{android.R.attr.state_selected}, Ui.rounded(this, Ui.BUTTON, 0, 12));
        bg.addState(new int[]{}, Ui.rounded(this, Color.TRANSPARENT, 0, 12));
        row.setBackground(bg);
        ImageView ic = new ImageView(this);
        ic.setImageResource(icon);
        ic.setImageTintList(new ColorStateList(new int[][]{{android.R.attr.state_focused}, {android.R.attr.state_selected}, {}},
                new int[]{Ui.BG, Ui.ACCENT, Ui.MUTED}));
        ic.setImageTintMode(PorterDuff.Mode.SRC_IN);
        ic.setDuplicateParentStateEnabled(true);
        row.addView(ic, new LinearLayout.LayoutParams(Ui.dp(this, 20), Ui.dp(this, 20)));
        LinearLayout names = Ui.vertical(this);
        names.setPadding(Ui.dp(this, 14), 0, 0, 0);
        TextView t = Ui.single(Ui.text(this, c[1], 15, Ui.TEXT));
        t.setTextColor(new ColorStateList(new int[][]{{android.R.attr.state_focused}, {}}, new int[]{Ui.BG, Ui.TEXT}));
        t.setDuplicateParentStateEnabled(true);
        TextView d = Ui.single(Ui.text(this, c[2], 11, Ui.DIM));
        d.setTextColor(new ColorStateList(new int[][]{{android.R.attr.state_focused}, {}}, new int[]{Ui.alpha(Ui.BG, 0.7f), Ui.DIM}));
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
            java.util.ArrayList<View> f = content.getFocusables(View.FOCUS_DOWN);
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
        if (keepFocus && cur != null && !(cur instanceof android.widget.EditText)) keep = content.getFocusables(View.FOCUS_DOWN).indexOf(cur);
        for (Map.Entry<String, View> e : catRows.entrySet()) e.getValue().setSelected(e.getKey().equals(cat));
        int scroll = keep >= 0 ? paneScroll.getScrollY() : 0;
        // While the pane is rebuilt the focused control disappears and Android moves focus to the nearest
        // view — often a category on the left, which would switch panes. Ignore that until we're done.
        rebuilding = true;
        content.removeAllViews();
        switch (cat) {
            case "home": paneHome(); break;
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
        paneScroll.scrollTo(0, scroll);
        if (keep >= 0) {
            ArrayList<View> all = content.getFocusables(View.FOCUS_DOWN);
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
        TextView t = Ui.heading(this, title, 26);
        content.addView(t);
        if (sub != null) {
            TextView s = Ui.text(this, sub, 14, Ui.MUTED);
            s.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 6));
            content.addView(s);
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
        String user = Trakt.user(Store.profile());
        content.addView(label("Watching now: " + Store.profile() + (Trakt.signedIn() ? " · Trakt: " + (user.isEmpty() ? "linked" : user) : " · Trakt not linked")));
        buttonRow().addView(Ui.button(this, R.drawable.ic_account, "Manage profiles & Trakt", v -> startActivity(new Intent(this, ProfilesActivity.class))));
    }

    private void paneHome() {
        heading("Home screen", null);
        content.addView(section("Home button"));
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
                content.addView(label("If the switch is greyed out: open Settings → Apps → Flux TV → ⋮ → Allow restricted settings, then try again."));
            }
        }
        content.addView(section("Continue watching shows items from"));
        content.addView(label("Apps appear here once they've added something to Continue watching."));
        java.util.Set<String> hiddenApps = ContinueWatching.hidden();
        LinearLayout cwRow = null;
        int n = 0;
        for (String[] src : ContinueWatching.sources()) {
            if (n++ % 3 == 0) cwRow = buttonRow();
            boolean on = !hiddenApps.contains(src[0]);
            cwRow.addView(Ui.pill(this, (on ? "✓ " : "") + src[1], on, v -> { ContinueWatching.setHidden(src[0], on); render(); }));
        }
        content.addView(section("Rows"));
        content.addView(label("Choose which rows show on Home, and their order."));
        buttonRow().addView(Ui.button(this, R.drawable.ic_home, "Customize Home", v -> startActivity(new Intent(this, HomeRowsActivity.class))));
        content.addView(section("Region for streaming services"));
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

    private List<SmartHome.Device> shDevices;
    private String shError;

    private void paneSmartHome() {
        heading("Smart home", "A panel for your lights and devices that opens over anything you're watching.");
        content.addView(section("Home Assistant"));
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
        content.addView(label("Google Home: Google doesn't let TV apps control Google Home devices yet. Most of those devices can be "
                + "added to Home Assistant (Hue, TP-Link/Kasa, Nest, Matter…) and then show here."));

        content.addView(section("Show over other apps"));
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
        content.addView(section("Devices on the panel"));
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
        content.addView(section("Providers"));
        LinearLayout pr = buttonRow();
        pr.addView(Ui.button(this, R.drawable.ic_live, Iptv.sources().isEmpty() ? "Add an IPTV provider" : "IPTV providers ("
                + Iptv.sources().size() + ")", v -> startActivity(new Intent(this, IptvActivity.class))));
        pr.addView(Ui.button(this, R.drawable.ic_tv, "Open Live TV", v -> startActivity(new Intent(this, LiveActivity.class))));

        content.addView(section("TV guide"));
        StringBuilder g = new StringBuilder();
        for (Iptv.Source s : Iptv.sources()) {
            long age = Iptv.guideAge(s);
            g.append(s.name).append(": ").append(age == Long.MAX_VALUE ? "not loaded yet" : "updated " + (age / 60000 < 60 ? age / 60000 + " min ago" : age / 3600000 + " h ago"))
             .append(s.epg.isEmpty() ? (s.xtream() ? " (from the provider)" : " (no EPG link)") : " (your EPG link)").append("\n");
        }
        content.addView(label(g.length() == 0 ? "Add a provider first." : g.toString().trim()
                + "\nChannels, the guide and logos are saved on this TV and refreshed in the background every few hours, so Live TV opens instantly."));
        LinearLayout gr = buttonRow();
        gr.addView(Ui.button(this, R.drawable.ic_update, "Update the guide now", v -> {
            Toast.makeText(this, "Updating the TV guide…", Toast.LENGTH_SHORT).show();
            App.async(() -> { for (Iptv.Source s : Iptv.sources()) Iptv.loadGuide(s, true); return true; },
                    ok -> { Toast.makeText(this, "TV guide updated", Toast.LENGTH_SHORT).show(); render(); },
                    e -> Toast.makeText(this, "Guide: " + App.message(e), Toast.LENGTH_LONG).show());
        }));
        content.addView(section("Preview while browsing the guide"));
        boolean prev = !"off".equals(Store.get("live_preview"));
        LinearLayout pv = buttonRow();
        pv.addView(Ui.pill(this, "On", prev, v -> { Store.put("live_preview", "on"); render(); }));
        pv.addView(Ui.pill(this, "Off", !prev, v -> { Store.put("live_preview", "off"); render(); }));
        content.addView(label("Turn off if your provider allows only one connection at a time."));

        content.addView(section("Save recordings to"));
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

        content.addView(section("Start early / end late"));
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

    private void paneAppearance() {
        heading("Appearance", null);
        content.addView(section("Themes"));
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
        content.addView(section("Accent colour"));
        LinearLayout accents = buttonRow();
        for (int i = 0; i < Theme.ACCENT_HEX.length; i++) {
            int idx = i;
            accents.addView(swatch(Color.parseColor(Theme.ACCENT_HEX[i]), Theme.ACCENT_NAMES[i], i == Theme.accentIndex(), v -> {
                Theme.setAccent(idx);
                recreate();
            }));
        }
        content.addView(label(Theme.ACCENT_NAMES[Theme.accentIndex()]));
        content.addView(section("Background"));
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
        heading("Player", "Press OK or ↑ while watching for the controls. Audio, Subtitles, Quality, Speed and Picture are in the bar.");
        content.addView(section("Subtitles"));
        LinearLayout subs = buttonRow();
        String mode = Store.get("player_subs");
        String[][] modes = {{"", "When the video says so"}, {"off", "Always off"}, {"on", "Always on"}};
        for (String[] m : modes) subs.addView(Ui.pill(this, m[1], m[0].equals(mode), v -> { Store.put("player_subs", m[0]); render(); }));
        content.addView(label("Size"));
        content.addView(choices(PlayerActivity.SUB_SIZES, "player_sub_size", 1));
        content.addView(label("Style"));
        content.addView(choices(PlayerActivity.SUB_STYLES, "player_sub_style", 0));
        content.addView(section("Picture size"));
        content.addView(choices(PlayerActivity.ZOOMS, "player_zoom", 0));
        content.addView(section("Stream size limits"));
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
        content.addView(section("Languages"));
        content.addView(label("Audio: " + (al.isEmpty() ? "no preference" : PlayerActivity.languageName(al))
                + "   ·   Subtitles: " + (sl.isEmpty() ? "no preference" : PlayerActivity.languageName(sl))
                + "\nFlux remembers the language you pick while watching. You can also set them on the phone page."));
    }

    private void paneSources() {
        heading("Sources", "Where Flux finds things to watch.");
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
        LinearLayout r = buttonRow();
        r.addView(Ui.button(this, R.drawable.ic_download, Arr.anyConfigured() ? "Connections" : "Connect Radarr, Sonarr, SABnzbd",
                v -> startActivity(new Intent(this, ArrSetupActivity.class))));
        if (Arr.anyConfigured()) r.addView(Ui.button(this, "Open Downloads", v -> startActivity(new Intent(this, DownloadsActivity.class))));
        if (Arr.RADARR.configured() || Arr.SONARR.configured()) {
            content.addView(section("When nothing can play"));
            content.addView(label("If a title isn't on your servers or Real-Debrid"));
            LinearLayout fb = buttonRow();
            fb.addView(Ui.pill(this, "Send to Radarr / Sonarr automatically", Arr.fallbackOn(), v -> { Arr.setFallback(true); render(); }));
            fb.addView(Ui.pill(this, "Only when I press the button", !Arr.fallbackOn(), v -> { Arr.setFallback(false); render(); }));
        }
    }

    private void paneSearch() {
        heading("Search", null);
        content.addView(section("Gemini (smart voice search)"));
        content.addView(label("API key from aistudio.google.com"));
        content.addView(keyField("gemini_key", "Gemini API key"));
        LinearLayout gs = buttonRow();
        gs.addView(Ui.pill(this, "Fast (Flash-Lite)", Gemini.fast(), v -> { Gemini.setFast(true); render(); }));
        gs.addView(Ui.pill(this, "Smarter (Flash)", !Gemini.fast(), v -> { Gemini.setFast(false); render(); }));
        content.addView(section("Voice search screen"));
        LinearLayout vs = buttonRow();
        boolean sys = Store.get("voice_ui").equals("system");
        vs.addView(Ui.pill(this, "Flux's own", !sys, v -> { Store.put("voice_ui", ""); render(); }));
        vs.addView(Ui.pill(this, "The TV's built-in one", sys, v -> { Store.put("voice_ui", "system"); render(); }));
        content.addView(section("TMDB (streaming services, cast & crew)"));
        content.addView(label("API key or read token from themoviedb.org/settings/api"));
        content.addView(keyField("tmdb_key", "TMDB API key"));
    }

    private void paneSystem() {
        heading("System", null);
        String addr = ConfigServer.running() ? ConfigServer.address() : null;
        if (addr != null) {
            content.addView(section("Set up from your phone"));
            TextView a = Ui.heading(this, addr, 22);
            a.setTextColor(Ui.ACCENT);
            content.addView(a);
            content.addView(label("Open this on a phone or computer on the same Wi-Fi while Settings is open. Every setting is there."));
        }
        content.addView(section("Flux TV " + Updater.currentName(this)));
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

    private TextView section(String s) {
        TextView t = Ui.heading(this, s, 18);
        t.setPadding(0, Ui.dp(this, 26), 0, Ui.dp(this, 8));
        return t;
    }

    private TextView label(String s) {
        TextView t = Ui.text(this, s, 13, Ui.DIM);
        t.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 6));
        return t;
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
        s.addState(new int[]{android.R.attr.state_focused}, Ui.rounded(this, bg, Ui.TEXT, 12));
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
        s.addState(new int[]{android.R.attr.state_focused}, circle(color, Ui.TEXT, 4));
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
