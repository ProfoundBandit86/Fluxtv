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
            {"appearance", "Appearance", "Colours"},
            {"player", "Player", "Subtitles, picture, size limits"},
            {"sources", "Sources", "Add-ons, servers, Real-Debrid, IPTV"},
            {"downloads", "Downloads", "Radarr, Sonarr, SABnzbd"},
            {"search", "Search", "Gemini and TMDB"},
            {"system", "System", "Updates, phone setup, TV settings"},
    };
    private static final int[] ICONS = {R.drawable.ic_account, R.drawable.ic_home, R.drawable.ic_display, R.drawable.ic_play,
            R.drawable.ic_addons, R.drawable.ic_download, R.drawable.ic_search, R.drawable.ic_settings};

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
        row.setOnFocusChangeListener((v, has) -> { if (has && !key.equals(cat)) { cat = key; render(false); } });
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
        content.removeAllViews();
        int scroll = keep >= 0 ? paneScroll.getScrollY() : 0;
        switch (cat) {
            case "home": paneHome(); break;
            case "appearance": paneAppearance(); break;
            case "player": panePlayer(); break;
            case "sources": paneSources(); break;
            case "downloads": paneDownloads(); break;
            case "search": paneSearch(); break;
            case "system": paneSystem(); break;
            default: paneProfiles(); break;
        }
        paneScroll.scrollTo(0, scroll);
        if (keep >= 0) {
            ArrayList<View> all = content.getFocusables(View.FOCUS_DOWN);
            View f = keep < all.size() ? all.get(keep) : (all.isEmpty() ? null : all.get(all.size() - 1));
            if (f != null) f.post(f::requestFocus);
        }
    }

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
            if (running) {
                r.addView(Ui.pill(this, "Home opens Flux", HomeButtonService.enabled(this), v -> { HomeButtonService.setEnabled(true); render(); }));
                r.addView(Ui.pill(this, "Off", !HomeButtonService.enabled(this), v -> { HomeButtonService.setEnabled(false); render(); }));
            } else {
                content.addView(label("If the switch is greyed out: open Settings → Apps → Flux TV → ⋮ → Allow restricted settings, then try again."));
            }
        }
        content.addView(section("Rows"));
        content.addView(label("Choose and order the rows on Home, and which apps appear in Continue watching."));
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

    private void paneAppearance() {
        heading("Appearance", null);
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
