package com.fluxtv.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Downloads: SABnzbd queue and history, Radarr/Sonarr queues, and what's coming up. Refreshes every few seconds. */
public class DownloadsActivity extends FluxActivity {

    @Override
    protected String sidebarKey() {
        return "downloads";
    }


    private LinearLayout content;
    private boolean visible;
    private int gen;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!visible) return;
            load(true);
            App.MAIN.postDelayed(this, 6000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        content = Ui.page(this, "Downloads");
    }

    @Override
    protected void onResume() {
        super.onResume();
        visible = true;
        load(false);
        App.MAIN.postDelayed(tick, 6000);
    }

    @Override
    protected void onPause() {
        super.onPause();
        visible = false;
        App.MAIN.removeCallbacks(tick);
    }

    /** Everything this screen shows, fetched in one go. */
    private static class Data {
        Sab.Queue sab;
        List<JSONObject> sabHistory = new ArrayList<>();
        List<JSONObject> radarrQueue = new ArrayList<>(), sonarrQueue = new ArrayList<>(), upcoming = new ArrayList<>();
        final List<String> errors = new ArrayList<>();
    }

    private void load(boolean keepFocus) {
        int my = ++gen;
        App.async(() -> {
            Data d = new Data();
            if (Sab.configured()) {
                try { d.sab = Sab.queue(); d.sabHistory = Sab.history(); } catch (Exception e) { d.errors.add(App.message(e)); }
            }
            if (Arr.RADARR.configured()) {
                try { d.radarrQueue = Arr.RADARR.queue(); d.upcoming.addAll(Arr.RADARR.upcoming()); } catch (Exception e) { d.errors.add("Radarr: " + App.message(e)); }
            }
            if (Arr.SONARR.configured()) {
                try { d.sonarrQueue = Arr.SONARR.queue(); d.upcoming.addAll(Arr.SONARR.upcoming()); } catch (Exception e) { d.errors.add("Sonarr: " + App.message(e)); }
            }
            java.util.Collections.sort(d.upcoming, (x, y) -> x.optString("when").compareTo(y.optString("when")));
            return d;
        }, d -> { if (my == gen && visible) render(d, keepFocus); }, null);
    }

    private void render(Data d, boolean keepFocus) {
        int keep = -1;
        View cur = getCurrentFocus();
        if (keepFocus && cur != null) keep = content.getFocusables(View.FOCUS_DOWN).indexOf(cur);
        while (content.getChildCount() > 1) content.removeViewAt(1);

        if (!Arr.anyConfigured()) {
            content.addView(para("Add Radarr, Sonarr or SABnzbd in Settings → Downloads (or from your phone) to see your downloads here."));
            LinearLayout b = Ui.horizontal(this);
            b.addView(Ui.button(this, R.drawable.ic_settings, "Open Settings", v -> startActivity(new Intent(this, SettingsActivity.class))));
            content.addView(b);
            b.getChildAt(0).requestFocus();
            return;
        }
        for (String err : d.errors) {
            TextView t = Ui.text(this, err, 14, Ui.WARN);
            t.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 4));
            content.addView(t);
        }

        if (d.sab != null) {
            String head = "SABnzbd  ·  " + (d.sab.paused ? "Paused" : d.sab.items.isEmpty() ? "Idle" : d.sab.speed + "B/s")
                    + (d.sab.items.isEmpty() ? "" : "  ·  " + d.sab.left + " left  ·  " + d.sab.timeLeft);
            content.addView(section(head));
            LinearLayout bar = Ui.horizontal(this);
            bar.addView(Ui.button(this, d.sab.paused ? "Resume all" : "Pause all", v -> act(() -> Sab.pauseAll(!d.sab.paused))));
            content.addView(bar);
            if (d.sab.items.isEmpty()) content.addView(para("Nothing downloading."));
            for (JSONObject e : d.sab.items) {
                content.addView(item(e.optString("title"), e.optString("sub"), e.optDouble("progress", -1), v -> sabMenu(e)));
            }
        }
        queue("Radarr queue", d.radarrQueue, Arr.RADARR.configured());
        queue("Sonarr queue", d.sonarrQueue, Arr.SONARR.configured());

        if (!d.upcoming.isEmpty()) {
            content.addView(section("Coming up"));
            for (JSONObject e : d.upcoming) {
                String imdb = e.optString("imdb");
                content.addView(item(e.optString("title"), e.optString("sub"), -1, imdb.startsWith("tt") ? v -> {
                    try {
                        DetailActivity.open(this, new JSONObject().put("id", imdb).put("type", e.optString("type"))
                                .put("name", e.optString("title").replaceAll("  ·  S\\d+ E\\d+$", "")).put("poster", e.optString("poster")));
                    } catch (Exception ignored) { }
                } : null));
            }
        }
        if (!d.sabHistory.isEmpty()) {
            content.addView(section("Recently finished"));
            for (JSONObject e : d.sabHistory) {
                View v = item(e.optString("title"), e.optString("sub"), -1, null);
                if (e.optBoolean("failed")) v.setAlpha(0.7f);
                content.addView(v);
            }
        }

        List<View> all = content.getFocusables(View.FOCUS_DOWN);
        View f = keep >= 0 && keep < all.size() ? all.get(keep) : (all.isEmpty() ? null : all.get(0));
        if (f != null && (getCurrentFocus() == null || !keepFocus || keep >= 0)) f.requestFocus();
    }

    private void queue(String title, List<JSONObject> items, boolean configured) {
        if (!configured) return;
        content.addView(section(title));
        if (items.isEmpty()) content.addView(para("Nothing in the queue."));
        for (JSONObject e : items) content.addView(item(e.optString("title"), e.optString("sub"), e.optDouble("progress", -1), null));
    }

    private void sabMenu(JSONObject e) {
        boolean paused = e.optBoolean("paused");
        String id = e.optString("id");
        new AlertDialog.Builder(this)
                .setTitle(e.optString("title"))
                .setItems(new String[]{paused ? "Resume" : "Pause", "Delete download"}, (dlg, which) -> {
                    if (which == 0) act(() -> Sab.pauseItem(id, !paused));
                    else new AlertDialog.Builder(this)
                            .setTitle("Delete this download?")
                            .setMessage("It's removed from SABnzbd along with any partly downloaded files.")
                            .setPositiveButton("Delete", (x, y) -> act(() -> Sab.deleteItem(id)))
                            .setNegativeButton("Cancel", null)
                            .show();
                })
                .show();
    }

    interface Action { void run() throws Exception; }

    private void act(Action a) {
        App.async(() -> { a.run(); return true; }, ok -> load(true),
                e -> Toast.makeText(this, App.message(e), Toast.LENGTH_LONG).show());
    }

    /** A focusable list row with an optional progress bar (progress < 0 = none). */
    private View item(String title, String sub, double progress, View.OnClickListener click) {
        LinearLayout row = Ui.vertical(this);
        row.setFocusable(true);
        row.setClickable(true);
        row.setBackground(Ui.focusable(this, Ui.PANEL, Ui.BUTTON, Ui.ACCENT));
        row.setPadding(Ui.dp(this, 18), Ui.dp(this, 11), Ui.dp(this, 18), Ui.dp(this, 11));
        if (click != null) row.setOnClickListener(click);
        row.addView(Ui.single(Ui.heading(this, title, 16)));
        if (!sub.isEmpty()) row.addView(Ui.single(Ui.text(this, sub, 13, Ui.MUTED)));
        if (progress >= 0) {
            FrameLayout track = new FrameLayout(this);
            track.setBackground(Ui.rounded(this, Ui.BORDER, 0, 99));
            View fill = new View(this);
            fill.setBackground(Ui.rounded(this, Ui.ACCENT, 0, 99));
            track.addView(fill, new FrameLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT));
            LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 5));
            tl.setMargins(0, Ui.dp(this, 8), 0, 0);
            row.addView(track, tl);
            double p = Math.max(0, Math.min(1, progress));
            track.post(() -> {
                ViewGroup.LayoutParams lp = fill.getLayoutParams();
                lp.width = (int) (track.getWidth() * p);
                fill.setLayoutParams(lp);
            });
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, Ui.dp(this, 4), 0, Ui.dp(this, 4));
        row.setLayoutParams(lp);
        row.setGravity(Gravity.CENTER_VERTICAL);
        return row;
    }

    private TextView section(String s) {
        TextView t = Ui.heading(this, s, 18);
        t.setPadding(0, Ui.dp(this, 22), 0, Ui.dp(this, 6));
        return t;
    }

    private TextView para(String s) {
        TextView t = Ui.text(this, s, 14, Ui.MUTED);
        t.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 8));
        return t;
    }
}
