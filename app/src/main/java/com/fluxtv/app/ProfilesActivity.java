package com.fluxtv.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/** Profiles (add, switch, remove) and each profile's Trakt link. */
public class ProfilesActivity extends FluxActivity {

    private LinearLayout content;
    private int session;
    private View focusAfter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        content = Ui.page(this, "Profiles & Trakt");
        Ratings.guard(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        ConfigServer.start(this::render);
        render();
    }

    @Override
    protected void onPause() {
        super.onPause();
        session++;
        ConfigServer.stop();
    }

    private void render() {
        session++;
        // Keep the page title (first child), rebuild the rest.
        while (content.getChildCount() > 1) content.removeViewAt(1);
        View first = null;

        String addr = ConfigServer.address();
        content.addView(para("Each profile has its own Favorites, Continue watching and Trakt account."
                + (addr != null ? "  Everything here can also be set from your phone at " + addr : "")));

        Trakt.Login l = Trakt.current();
        if (l != null && "waiting".equals(l.state)) {
            showCode(l);
            return;
        }

        if (!Trakt.configured()) {
            content.addView(section("Connect Trakt"));
            content.addView(para("Trakt needs a free API app of your own. On your phone or computer, open "
                    + "developer.trakt.tv, sign in, go to My Apps and create an app with any name (if it asks for a "
                    + "Redirect URI, use http://localhost). Then paste its Client ID"
                    + (addr != null ? " at " + addr + " (easier) or here." : " here.")
                    + " New Trakt apps have no Client Secret; only fill that in for an older app that has one."));
            EditText id = field("Client ID"), secret = field("Client Secret (older apps only)");
            content.addView(id, new LinearLayout.LayoutParams(Ui.dp(this, 520), ViewGroup.LayoutParams.WRAP_CONTENT));
            content.addView(secret, new LinearLayout.LayoutParams(Ui.dp(this, 560), ViewGroup.LayoutParams.WRAP_CONTENT));
            LinearLayout b = Ui.horizontal(this);
            b.addView(Ui.button(this, "Save", v -> {
                Trakt.setApp(id.getText().toString(), secret.getText().toString());
                render();
            }));
            content.addView(b);
        }

        content.addView(section("Profiles"));
        List<String> profiles = Store.profiles();
        String current = Store.profile();
        for (String name : profiles) {
            LinearLayout card = Ui.horizontal(this);
            card.setGravity(Gravity.CENTER_VERTICAL);
            card.setBackground(Ui.rounded(this, Ui.PANEL, 0, 14));
            card.setPadding(Ui.dp(this, 18), Ui.dp(this, 10), Ui.dp(this, 10), Ui.dp(this, 10));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, Ui.dp(this, 5), 0, Ui.dp(this, 5));
            LinearLayout names = Ui.vertical(this);
            names.addView(Ui.heading(this, name + (name.equals(current) ? "   · watching now" : ""), 18));
            String user = Trakt.user(name);
            names.addView(Ui.text(this, Trakt.signedIn(name) ? "Trakt: " + (user.isEmpty() ? "linked" : user)
                    : "Trakt: not linked", 13, Trakt.signedIn(name) ? Ui.GOOD : Ui.DIM));
            String lim = Ratings.limit(name);
            names.addView(Ui.text(this, lim.isEmpty() ? "No age limit" : "Age limit: " + lim + " and under"
                    + (Ratings.pin(name).isEmpty() ? "" : " · PIN set"), 13, lim.isEmpty() ? Ui.DIM : Ui.WARN));
            card.addView(names, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            card.addView(Ui.button(this, "Age limit", v -> chooseLimit(name)));

            if (!name.equals(current)) {
                View use = Ui.button(this, "Switch to", v -> {
                    Ratings.switchProfile(this, name, () -> {
                        Toast.makeText(this, "Now watching as " + name, Toast.LENGTH_SHORT).show();
                        render();
                    });
                });
                card.addView(use);
            }
            if (Trakt.signedIn(name)) {
                if (name.equals(current)) {
                    View sync = Ui.button(this, R.drawable.ic_update, "Sync now", v -> syncNow());
                    card.addView(sync);
                    if (first == null) first = sync;
                }
                card.addView(Ui.button(this, "Unlink Trakt", v -> {
                    Trakt.signOut(name);
                    render();
                }));
            } else if (Trakt.configured()) {
                View link = Ui.button(this, "Link Trakt", v -> startLink(name));
                card.addView(link);
                if (first == null && name.equals(current)) first = link;
            }
            if (profiles.size() > 1) card.addView(Ui.button(this, "Remove", v -> confirmRemove(name)));
            content.addView(card, lp);
        }
        LinearLayout add = Ui.horizontal(this);
        add.setPadding(0, Ui.dp(this, 8), 0, 0);
        View addBtn = Ui.button(this, R.drawable.ic_plus, "Add profile", v -> addProfile());
        add.addView(addBtn);
        content.addView(add);
        if (first == null) first = addBtn;

        renderTvSync();

        if (Trakt.configured()) {
            content.addView(section("What syncs"));
            content.addView(para("Favorites ⇄ your Trakt watchlist (adding or removing on either side carries over). "
                    + "Resume points from Trakt show in Continue watching. What you watch in Flux is scrobbled, so "
                    + "finished movies and episodes land in your Trakt history. Sync runs when Home opens (every 10 minutes at most)."));
            LinearLayout b = Ui.horizontal(this);
            b.addView(Ui.button(this, "Change Trakt API app", v -> {
                Store.put("trakt_client_secret", "");
                Store.put("trakt_client_id", "");
                render();
            }));
            content.addView(b);
        }

        View f = focusAfter != null && focusAfter.isAttachedToWindow() ? focusAfter : first;
        focusAfter = null;
        if (f != null) f.post(f::requestFocus);
    }

    // ------------------------------------------------------------ age limits

    private void chooseLimit(String name) {
        String[] items = new String[Ratings.LIMITS.length + 1];
        items[0] = "No limit";
        for (int i = 0; i < Ratings.LIMITS.length; i++) items[i + 1] = Ratings.LIMIT_NAMES[i] + " and under";
        new AlertDialog.Builder(this)
                .setTitle("Age limit for " + name)
                .setItems(items, (d, which) -> {
                    String lim = which == 0 ? "" : Ratings.LIMITS[which - 1];
                    Ratings.setLimit(name, lim);
                    if (!lim.isEmpty()) askPin(name);
                    else { Ratings.setPin(name, ""); render(); }
                    ProfileSync.syncNow(null);
                })
                .show();
    }

    private void askPin(String name) {
        EditText input = field("4-digit PIN (leave empty for none)");
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        new AlertDialog.Builder(this)
                .setTitle("PIN for " + name)
                .setMessage("With a PIN, leaving this profile and opening Settings need the PIN. Titles above the limit, and "
                        + "titles without a rating, are hidden. Live TV is hidden too.")
                .setView(input)
                .setPositiveButton("Save", (d, w) -> {
                    Ratings.setPin(name, input.getText().toString().trim());
                    render();
                    ProfileSync.syncNow(null);
                })
                .setNegativeButton("No PIN", (d, w) -> { Ratings.setPin(name, ""); render(); })
                .show();
    }

    // ------------------------------------------------------------ TV to TV sync

    private void renderTvSync() {
        content.addView(section("Sync between TVs"));
        content.addView(para("Link your Flux TVs on the same home network and they share profiles, age limits, Favorites "
                + "and watch history. No account or internet needed."));
        TextView st = Ui.text(this, ProfileSync.status(), 14, ProfileSync.peers().isEmpty() ? Ui.DIM : Ui.GOOD);
        content.addView(st);
        for (org.json.JSONObject p : ProfileSync.peers()) {
            LinearLayout r = Ui.horizontal(this);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 4));
            r.addView(Ui.text(this, "Linked: " + p.optString("name") + "  (" + p.optString("host") + ")", 15, Ui.TEXT),
                    new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            r.addView(Ui.button(this, "Copy its settings here", v -> confirmCopy(p)));
            r.addView(Ui.button(this, "Unlink", v -> { ProfileSync.unlink(p.optString("id")); render(); }));
            content.addView(r);
        }
        LinearLayout b = Ui.horizontal(this);
        b.setPadding(0, Ui.dp(this, 6), 0, 0);
        b.addView(Ui.button(this, R.drawable.ic_plus, "Link another TV", v -> showLink()));
        if (!ProfileSync.peers().isEmpty()) {
            b.addView(Ui.button(this, R.drawable.ic_update, "Sync now", v -> {
                Toast.makeText(this, "Syncing…", Toast.LENGTH_SHORT).show();
                ProfileSync.syncForced(() -> { Toast.makeText(this, ProfileSync.status(), Toast.LENGTH_LONG).show(); render(); });
            }));
        }
        content.addView(b);
    }

    private void confirmCopy(org.json.JSONObject p) {
        new AlertDialog.Builder(this)
                .setTitle("Copy settings from " + p.optString("name") + "?")
                .setMessage("Add-ons, Real-Debrid, Plex/Jellyfin/Emby, IPTV, API keys (TMDB, Gemini, Trakt app), Radarr/Sonarr/"
                        + "SABnzbd, theme and player options are copied to this TV, replacing what's set here. "
                        + "Your Home app row and Home button setup stay as they are.")
                .setPositiveButton("Copy", (d, w) -> App.async(() -> ProfileSync.copySettingsFrom(p.optString("id")), n -> {
                    Toast.makeText(this, "Copied " + n + " settings from " + p.optString("name"), Toast.LENGTH_LONG).show();
                    render();
                }, e -> Toast.makeText(this, "Couldn't copy: " + App.message(e), Toast.LENGTH_LONG).show()))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showLink() {
        int my = ++session;
        while (content.getChildCount() > 1) content.removeViewAt(1);
        content.addView(section("Link another TV"));
        content.addView(para("Either type this TV's code on the other TV, or pick the other TV below and type the code it shows."));
        TextView name = Ui.text(this, "This TV: " + ProfileSync.deviceName(), 15, Ui.TEXT);
        content.addView(name);
        TextView code = Ui.heading(this, ProfileSync.newPairCode(), 56);
        code.setTextColor(Ui.ACCENT);
        code.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 6));
        content.addView(code);
        content.addView(section("Flux TVs on your network"));
        LinearLayout list = Ui.vertical(this);
        content.addView(list);
        LinearLayout b = Ui.horizontal(this);
        b.setPadding(0, Ui.dp(this, 8), 0, 0);
        View manual = Ui.button(this, "Enter address instead", v -> askCode(null, null));
        b.addView(manual);
        View done = Ui.button(this, "Done", v -> render());
        b.addView(done);
        content.addView(b);
        done.post(done::requestFocus);
        ProfileSync.onPaired = () -> {
            if (my != session) return;
            Toast.makeText(this, "Linked. Syncing…", Toast.LENGTH_LONG).show();
            ProfileSync.syncForced(this::render);
        };
        fillNearby(list, my, "");
    }

    private void fillNearby(LinearLayout list, int my, String shown) {
        if (my != session) return;
        StringBuilder sig = new StringBuilder();
        List<org.json.JSONObject> tvs = ProfileSync.nearby();
        for (org.json.JSONObject t : tvs) sig.append(t.optString("id")).append(t.optString("host"));
        if (!sig.toString().equals(shown) || list.getChildCount() == 0) {
            list.removeAllViews();
            if (tvs.isEmpty()) list.addView(para("Looking… Open Flux on the other TV (it must be on the same Wi-Fi or network)."));
            for (org.json.JSONObject t : tvs) {
                LinearLayout r = Ui.horizontal(this);
                r.setGravity(Gravity.CENTER_VERTICAL);
                r.addView(Ui.text(this, t.optString("name") + "  (" + t.optString("host") + ")", 15, Ui.TEXT),
                        new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                r.addView(Ui.button(this, "Link", v -> askCode(t.optString("host") + ":" + t.optInt("port"), t.optString("name"))));
                list.addView(r);
            }
        }
        String now = sig.toString();
        App.MAIN.postDelayed(() -> fillNearby(list, my, now), 2000);
    }

    private void askCode(String host, String tvName) {
        LinearLayout box = Ui.vertical(this);
        box.setPadding(Ui.dp(this, 20), 0, Ui.dp(this, 20), 0);
        EditText addr = field("Other TV's address, e.g. 192.168.1.20");
        if (host == null) box.addView(addr);
        EditText code = field("6-digit code shown on the other TV");
        code.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        box.addView(code);
        new AlertDialog.Builder(this)
                .setTitle(tvName == null ? "Link another TV" : "Link with " + tvName)
                .setMessage("On the other TV open Settings → Profiles → Link another TV to see its code.")
                .setView(box)
                .setPositiveButton("Link", (d, w) -> {
                    String h = host != null ? host : addr.getText().toString().trim();
                    String c = code.getText().toString().trim();
                    if (h.isEmpty() || c.isEmpty()) return;
                    App.async(() -> ProfileSync.link(h, c), n -> {
                        Toast.makeText(this, "Linked with " + n + ". " + ProfileSync.status(), Toast.LENGTH_LONG).show();
                        render();
                    }, e -> Toast.makeText(this, "Couldn't link: " + App.message(e), Toast.LENGTH_LONG).show());
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void syncNow() {
        Toast.makeText(this, "Syncing with Trakt…", Toast.LENGTH_SHORT).show();
        App.async(Trakt::sync, s -> {
            Toast.makeText(this, s, Toast.LENGTH_LONG).show();
            render();
        }, e -> Toast.makeText(this, "Trakt: " + App.message(e), Toast.LENGTH_LONG).show());
    }

    private void startLink(String profile) {
        App.async(() -> Trakt.startLogin(profile), l -> render(),
                e -> Toast.makeText(this, "Trakt: " + App.message(e), Toast.LENGTH_LONG).show());
    }

    private void showCode(Trakt.Login l) {
        int my = session;
        content.addView(section("Link Trakt for " + l.profile));
        content.addView(para("On your phone or computer, go to " + l.url + " and enter:"));
        TextView code = Ui.heading(this, l.userCode, 56);
        code.setTextColor(Ui.ACCENT);
        code.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 10));
        content.addView(code);
        content.addView(para("Waiting for approval…"));
        LinearLayout b = Ui.horizontal(this);
        View cancel = Ui.button(this, "Cancel", v -> {
            Trakt.cancelLogin();
            render();
        });
        b.addView(cancel);
        content.addView(b);
        cancel.post(cancel::requestFocus);
        watch(l, my);
    }

    private void watch(Trakt.Login l, int my) {
        App.MAIN.postDelayed(() -> {
            if (my != session) return;
            if ("waiting".equals(l.state)) {
                watch(l, my);
                return;
            }
            if ("done".equals(l.state)) Toast.makeText(this, "Trakt linked for " + l.profile, Toast.LENGTH_LONG).show();
            else if (!l.error.isEmpty()) Toast.makeText(this, l.error, Toast.LENGTH_LONG).show();
            render();
        }, 1000);
    }

    private void addProfile() {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        new AlertDialog.Builder(this)
                .setTitle("Profile name")
                .setView(input)
                .setPositiveButton("Add", (d, w) -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) return;
                    Store.addProfile(name);
                    render();
                    ProfileSync.syncNow(null);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void confirmRemove(String name) {
        new AlertDialog.Builder(this)
                .setTitle("Remove " + name + "?")
                .setMessage("Its Favorites and Continue watching are deleted (on linked TVs too). Nothing is removed from Trakt.")
                .setPositiveButton("Remove", (d, w) -> {
                    Store.removeProfile(name);
                    render();
                    ProfileSync.syncForced(null);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private EditText field(String hint) {
        EditText e = new EditText(this);
        e.setSingleLine(true);
        e.setHint(hint);
        e.setTextColor(Ui.TEXT);
        e.setHintTextColor(Ui.DIM);
        return e;
    }

    private TextView section(String s) {
        TextView t = Ui.heading(this, s, 18);
        t.setPadding(0, Ui.dp(this, 22), 0, Ui.dp(this, 6));
        return t;
    }

    private TextView para(String s) {
        TextView t = Ui.text(this, s, 14, Ui.MUTED);
        t.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 8));
        t.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return t;
    }
}
