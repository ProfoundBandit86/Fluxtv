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
            card.addView(names, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            if (!name.equals(current)) {
                View use = Ui.button(this, "Switch to", v -> {
                    Store.setProfile(name);
                    Toast.makeText(this, "Now watching as " + name, Toast.LENGTH_SHORT).show();
                    render();
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
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void confirmRemove(String name) {
        new AlertDialog.Builder(this)
                .setTitle("Remove " + name + "?")
                .setMessage("Its Favorites and Continue watching on this TV are deleted. Nothing is removed from Trakt.")
                .setPositiveButton("Remove", (d, w) -> {
                    Store.removeProfile(name);
                    render();
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
