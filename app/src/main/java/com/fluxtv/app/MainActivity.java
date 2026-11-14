package com.fluxtv.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.util.Date;
import java.util.List;

/** Home screen and TV launcher: side menu + rows of titles. */
public class MainActivity extends Activity {

    private LinearLayout nav, localRows, catalogRows;
    private ScrollView scroll;
    private TextView clock;
    private Button profileButton, updateButton;
    private int loadedVersion = -1;

    private final Runnable clockTick = new Runnable() {
        @Override public void run() {
            clock.setText(DateFormat.getTimeFormat(MainActivity.this).format(new Date()));
            App.MAIN.postDelayed(this, 20_000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = Ui.horizontal(this);
        root.setBackgroundColor(Ui.BG);

        // ---- side menu
        nav = Ui.vertical(this);
        nav.setBackgroundColor(Ui.PANEL);
        nav.setPadding(Ui.dp(this, 20), Ui.dp(this, 28), Ui.dp(this, 20), Ui.dp(this, 20));
        TextView logo = Ui.heading(this, "Flux", 32);
        logo.setTextColor(Ui.ACCENT);
        nav.addView(logo);
        clock = Ui.text(this, "", 18, Ui.MUTED);
        clock.setPadding(0, 0, 0, Ui.dp(this, 20));
        nav.addView(clock);

        addNav("Home", v -> scroll.smoothScrollTo(0, 0));
        addNav("Search", v -> startActivity(new Intent(this, SearchActivity.class)));
        profileButton = addNav("Profile", v -> chooseProfile());
        addNav("Add-ons", v -> startActivity(new Intent(this, AddonsActivity.class)));
        addNav("Media servers", v -> startActivity(new Intent(this, ServersActivity.class)));
        addNav("Real-Debrid", v -> startActivity(new Intent(this, AccountActivity.class)));
        addNav("Apps", v -> startActivity(new Intent(this, AppsActivity.class)));
        updateButton = addNav("Check for updates", v -> Updater.check(this, true));
        addNav("TV settings", v -> {
            try {
                startActivity(new Intent(Settings.ACTION_SETTINGS));
            } catch (ActivityNotFoundException e) {
                Toast.makeText(this, "Settings not available", Toast.LENGTH_SHORT).show();
            }
        });
        root.addView(nav, new LinearLayout.LayoutParams(Ui.dp(this, 230), ViewGroup.LayoutParams.MATCH_PARENT));

        // ---- content
        scroll = new ScrollView(this);
        scroll.setClipChildren(false);
        LinearLayout content = Ui.vertical(this);
        content.setPadding(Ui.dp(this, 32), Ui.dp(this, 12), Ui.dp(this, 16), Ui.dp(this, 40));
        content.setClipChildren(false);
        localRows = Ui.vertical(this);
        catalogRows = Ui.vertical(this);
        content.addView(localRows);
        content.addView(catalogRows);
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        setContentView(root);
    }

    private Button addNav(String label, View.OnClickListener click) {
        Button b = Ui.listButton(this, label, click);
        nav.addView(b);
        return b;
    }

    @Override
    protected void onResume() {
        super.onResume();
        profileButton.setText("Profile: " + Store.profile());
        updateButton.setText("Updates · " + Updater.currentName(this));
        Updater.check(this, false);   // automatic update check on every reload of the home screen
        showLastCrash();
        if (getCurrentFocus() == null) {   // nothing selected yet: start on the menu so OK always does something
            View home = nav.getChildAt(2);
            home.post(home::requestFocus);
        }
        renderLocalRows();
        if (Store.addonsVersion() != loadedVersion) loadCatalogs();
        App.MAIN.removeCallbacks(clockTick);
        clockTick.run();
    }

    @Override
    protected void onPause() {
        super.onPause();
        App.MAIN.removeCallbacks(clockTick);
    }

    @Override
    public void onBackPressed() {
        // We may be the TV's home screen, so never close; jump to the menu instead.
        if (nav.getChildCount() > 2) nav.getChildAt(2).requestFocus();
        scroll.smoothScrollTo(0, 0);
    }

    private void renderLocalRows() {
        localRows.removeAllViews();
        List<JSONObject> cw = Store.continueWatching();
        if (!cw.isEmpty()) {
            LinearLayout row = Ui.row(this, localRows, "Continue watching");
            for (JSONObject e : cw) {
                String label = e.optString("label");
                String title = label.isEmpty() ? e.optString("name") : e.optString("name") + " · " + label;
                row.addView(Ui.poster(this, title, e.optString("poster"), v -> DetailActivity.open(this, e)));
            }
        }
        List<JSONObject> lib = Store.library();
        if (!lib.isEmpty()) {
            LinearLayout row = Ui.row(this, localRows, "My watchlist");
            for (JSONObject e : lib) {
                row.addView(Ui.poster(this, e.optString("name"), e.optString("poster"), v -> DetailActivity.open(this, e)));
            }
        }
    }

    private void loadCatalogs() {
        loadedVersion = Store.addonsVersion();
        catalogRows.removeAllViews();
        TextView loading = Ui.text(this, "Loading catalogs…", 18, Ui.MUTED);
        loading.setPadding(Ui.dp(this, 6), Ui.dp(this, 24), 0, 0);
        catalogRows.addView(loading);
        App.async(Addons::homeRows, rows -> {
            catalogRows.removeAllViews();
            if (rows.isEmpty()) {
                TextView t = Ui.text(this, "Nothing to show yet. Check your internet connection, install add-ons, or add a Plex/Jellyfin/Emby server.", 18, Ui.MUTED);
                t.setPadding(Ui.dp(this, 6), Ui.dp(this, 24), 0, 0);
                catalogRows.addView(t);
                return;
            }
            for (Addons.Row r : rows) {
                LinearLayout row = Ui.row(this, catalogRows, r.title);
                for (JSONObject m : r.items) {
                    row.addView(Ui.poster(this, m.optString("name"), m.optString("poster"), v -> DetailActivity.open(this, m)));
                }
            }
        }, e -> {
            catalogRows.removeAllViews();
            catalogRows.addView(Ui.text(this, "Couldn't load catalogs: " + App.message(e), 18, Ui.MUTED));
        });
    }

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
                        input.setGravity(Gravity.CENTER_VERTICAL);
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
        profileButton.setText("Profile: " + name);
        renderLocalRows();
    }
}
