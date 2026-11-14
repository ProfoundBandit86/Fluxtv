package com.fluxtv.app;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/** Appearance (accent + background colours), sources, and system shortcuts. */
public class SettingsActivity extends Activity {

    private LinearLayout content;
    private int focusAccent = -1, focusBg = -1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        render(false);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!ConfigServer.running()) {
            ConfigServer.start(() -> {
                if (!isFinishing()) render();
            });
            render(false);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        ConfigServer.stop();
    }

    private void render() {
        render(true);
    }

    private void render(boolean keepFocus) {
        // Keep focus on the same control after rebuilding (e.g. after choosing a pill).
        int keep = -1;
        if (keepFocus && content != null && getCurrentFocus() != null && !(getCurrentFocus() instanceof android.widget.EditText)) keep = content.getFocusables(View.FOCUS_DOWN).indexOf(getCurrentFocus());
        content = Ui.page(this, "Settings");
        String addr = ConfigServer.running() ? ConfigServer.address() : null;
        if (addr != null) {
            TextView phone = Ui.text(this, "Every setting is also on your phone or computer: open " + addr
                    + " (same Wi-Fi) while this screen is open.", 14, Ui.ACCENT);
            phone.setPadding(0, 0, 0, Ui.dp(this, 6));
            content.addView(phone);
        }

        content.addView(section("Profiles & sync"));
        LinearLayout prof = Ui.horizontal(this);
        String user = Trakt.user(Store.profile());
        View profBtn = Ui.button(this, R.drawable.ic_account, "Profiles & Trakt · " + Store.profile()
                + (Trakt.signedIn() ? " (Trakt: " + (user.isEmpty() ? "linked" : user) + ")" : ""),
                v -> startActivity(new Intent(this, ProfilesActivity.class)));
        prof.addView(profBtn);
        content.addView(prof);

        content.addView(section("Appearance"));
        content.addView(label("Accent colour"));
        LinearLayout accents = Ui.horizontal(this);
        accents.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 4));
        View firstFocus = null;
        for (int i = 0; i < Theme.ACCENT_HEX.length; i++) {
            int idx = i;
            View sw = swatch(Color.parseColor(Theme.ACCENT_HEX[i]), Theme.ACCENT_NAMES[i], i == Theme.accentIndex(), v -> {
                Theme.setAccent(idx);
                focusAccent = idx;
                render();
            });
            accents.addView(sw);
            if (i == (focusAccent >= 0 ? focusAccent : Theme.accentIndex()) && focusBg < 0) firstFocus = sw;
        }
        content.addView(accents);
        TextView an = Ui.text(this, Theme.ACCENT_NAMES[Theme.accentIndex()], 13, Ui.MUTED);
        an.setPadding(Ui.dp(this, 4), 0, 0, Ui.dp(this, 14));
        content.addView(an);

        content.addView(label("Background"));
        LinearLayout bgs = Ui.horizontal(this);
        for (int i = 0; i < Theme.BG_NAMES.length; i++) {
            int idx = i;
            View b = Ui.pill(this, Theme.BG_NAMES[i], i == Theme.bgIndex(), v -> {
                Theme.setBackground(idx);
                focusBg = idx;
                focusAccent = -1;
                render();
            });
            bgs.addView(b);
            if (focusBg == i) firstFocus = b;
        }
        content.addView(bgs);

        content.addView(section("Player"));
        content.addView(label("Subtitles"));
        LinearLayout subs = Ui.horizontal(this);
        String mode = Store.get("player_subs");
        String[][] modes = {{"", "When the video says so"}, {"off", "Always off"}, {"on", "Always on"}};
        for (String[] m : modes) subs.addView(Ui.pill(this, m[1], m[0].equals(mode), v -> { Store.put("player_subs", m[0]); render(); }));
        content.addView(subs);
        content.addView(label("Subtitle size"));
        content.addView(choices(PlayerActivity.SUB_SIZES, "player_sub_size", 1));
        content.addView(label("Subtitle style"));
        content.addView(choices(PlayerActivity.SUB_STYLES, "player_sub_style", 0));
        content.addView(label("Picture size"));
        content.addView(choices(PlayerActivity.ZOOMS, "player_zoom", 0));
        String al = Store.get("player_audio_lang"), sl = Store.get("player_sub_lang");
        content.addView(label("Preferred languages · audio: " + (al.isEmpty() ? "none" : PlayerActivity.languageName(al))
                + " · subtitles: " + (sl.isEmpty() ? "none" : PlayerActivity.languageName(sl))
                + ". Flux remembers the language you pick while watching; you can also set them from your phone."));

        content.addView(section("Sources"));
        LinearLayout src = Ui.horizontal(this);
        src.addView(Ui.button(this, R.drawable.ic_addons, "Add-ons", v -> startActivity(new Intent(this, AddonsActivity.class))));
        src.addView(Ui.button(this, R.drawable.ic_servers, "Media servers", v -> startActivity(new Intent(this, ServersActivity.class))));
        src.addView(Ui.button(this, R.drawable.ic_cloud, "Real-Debrid", v -> startActivity(new Intent(this, AccountActivity.class))));
        src.addView(Ui.button(this, R.drawable.ic_live, "Live TV & IPTV", v -> startActivity(new Intent(this, IptvActivity.class))));
        content.addView(src);
        LinearLayout dl = Ui.horizontal(this);
        dl.addView(Ui.button(this, R.drawable.ic_download, "Downloads · Radarr, Sonarr, SABnzbd", v -> startActivity(new Intent(this, ArrSetupActivity.class))));
        if (Arr.anyConfigured()) dl.addView(Ui.button(this, "Open Downloads", v -> startActivity(new Intent(this, DownloadsActivity.class))));
        content.addView(dl);

        content.addView(section("Search & discovery"));
        content.addView(label("Gemini API key (smart voice search) · from aistudio.google.com"));
        content.addView(keyField("gemini_key", "Gemini API key"));
        content.addView(label("Gemini search speed"));
        LinearLayout gs = Ui.horizontal(this);
        gs.addView(Ui.pill(this, "Fast (Flash-Lite)", Gemini.fast(), v -> { Gemini.setFast(true); render(); }));
        gs.addView(Ui.pill(this, "Smarter (Flash)", !Gemini.fast(), v -> { Gemini.setFast(false); render(); }));
        content.addView(gs);
        content.addView(label("TMDB API key or read token (services, cast & crew) · from themoviedb.org/settings/api"));
        content.addView(keyField("tmdb_key", "TMDB API key"));
        content.addView(label("Region for streaming services"));
        LinearLayout regions = Ui.horizontal(this);
        String current = Tmdb.region();
        for (String r : new String[]{"US", "CA", "GB", "AU", "IE", "DE", "FR", "NL"}) {
            regions.addView(Ui.pill(this, r, r.equals(current), v -> {
                Store.put("region", r);
                Store.bumpSources();
                render();
            }));
        }
        content.addView(regions);
        LinearLayout homeRow = Ui.horizontal(this);
        homeRow.addView(Ui.button(this, R.drawable.ic_home, "Customize Home rows", v -> startActivity(new Intent(this, HomeRowsActivity.class))));
        content.addView(homeRow);

        content.addView(section("System"));
        LinearLayout sys = Ui.horizontal(this);
        sys.addView(Ui.button(this, R.drawable.ic_update, "Check for updates · " + Updater.currentName(this),
                v -> Updater.check(this, true)));
        sys.addView(Ui.button(this, R.drawable.ic_apps, "All apps", v -> startActivity(new Intent(this, AppsActivity.class))));
        sys.addView(Ui.button(this, R.drawable.ic_settings, "TV settings", v -> {
            try {
                startActivity(new Intent(Settings.ACTION_SETTINGS));
            } catch (ActivityNotFoundException e) {
                Toast.makeText(this, "Settings not available", Toast.LENGTH_SHORT).show();
            }
        }));
        content.addView(sys);

        View f = firstFocus;
        java.util.ArrayList<View> all = content.getFocusables(View.FOCUS_DOWN);
        if (keep >= 0 && keep < all.size()) f = all.get(keep);
        else if (!all.isEmpty()) f = all.get(0);
        View target = f;
        if (target != null) target.post(target::requestFocus);
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
