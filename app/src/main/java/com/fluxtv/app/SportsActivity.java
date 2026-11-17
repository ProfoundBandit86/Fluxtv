package com.fluxtv.app;

import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Sports: today's games with live scores, and which of your channels shows each one.
 * OK watches the best match · hold OK (or MENU) for "Watch on…" and the league table.
 */
public class SportsActivity extends FluxActivity {

    @Override
    protected String sidebarKey() {
        return "sports";
    }

    private LinearLayout content, days, leagues, list;
    private TextView status;
    private int dayOffset;
    private String filter = "";   // "" = every followed league
    private List<Sports.Event> events = new ArrayList<>();
    private int loadSeq;
    private final DateFormat tf = android.text.format.DateFormat.getTimeFormat(App.ctx);

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        content = Ui.page(this, "Sports");
        content.addView(Ui.text(this, "Live scores and where to watch on your channels. OK to watch · hold OK for more channels and the table.", 13, Ui.MUTED));
        days = row();
        leagues = row();
        status = Ui.text(this, "Loading games…", 14, Ui.MUTED);
        status.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 6));
        content.addView(status);
        list = Ui.vertical(this);
        list.setClipChildren(false);
        content.addView(list);
        renderDays();
        renderLeagues();
        load(true);
    }

    private LinearLayout row() {
        LinearLayout r = Ui.horizontal(this);
        r.setPadding(0, Ui.dp(this, 8), 0, 0);
        android.widget.HorizontalScrollView hs = new FocusScroll.Horizontal(this);
        hs.addView(r);
        content.addView(hs);
        return r;
    }

    private void renderDays() {
        days.removeAllViews();
        SimpleDateFormat f = new SimpleDateFormat("EEE MMM d", Locale.getDefault());
        for (int d = -1; d <= 5; d++) {
            int off = d;
            String name = d == -1 ? "Yesterday" : d == 0 ? "Today" : d == 1 ? "Tomorrow"
                    : f.format(new Date(System.currentTimeMillis() + d * 86_400_000L));
            days.addView(Ui.pill(this, name, d == dayOffset, v -> { dayOffset = off; renderDays(); load(true); }));
        }
    }

    private void renderLeagues() {
        leagues.removeAllViews();
        leagues.addView(Ui.pill(this, "All my leagues", filter.isEmpty(), v -> { filter = ""; renderLeagues(); show(); }));
        for (String k : Sports.followed()) {
            Sports.League l = Sports.league(k);
            leagues.addView(Ui.pill(this, l.name, k.equals(filter), v -> { filter = k; renderLeagues(); show(); }));
        }
        leagues.addView(Ui.pill(this, "＋ Leagues", false, v -> chooseLeagues()));
    }

    private void chooseLeagues() {
        String[] names = new String[Sports.LEAGUES.length];
        boolean[] on = new boolean[names.length];
        List<String> f = Sports.followed();
        for (int i = 0; i < names.length; i++) {
            names[i] = Sports.LEAGUES[i].name;
            on[i] = f.contains(Sports.LEAGUES[i].key);
        }
        new AlertDialog.Builder(this).setTitle("Leagues to follow")
                .setMultiChoiceItems(names, on, (d, w, checked) -> on[w] = checked)
                .setPositiveButton("Save", (d, w) -> {
                    List<String> keys = new ArrayList<>();
                    for (int i = 0; i < names.length; i++) if (on[i]) keys.add(Sports.LEAGUES[i].key);
                    if (keys.isEmpty()) keys.add("nfl");
                    Sports.setFollowed(keys);
                    filter = "";
                    renderLeagues();
                    load(true);
                })
                .setNegativeButton("Cancel", null).show();
    }

    // ------------------------------------------------------------ loading

    private void load(boolean showLoading) {
        int seq = ++loadSeq;
        if (showLoading) status.setText("Loading games…");
        String date = Sports.dateKey(System.currentTimeMillis() + dayOffset * 86_400_000L);
        List<String> keys = Sports.followed();
        App.async(() -> {
            List<Sports.Event> ev = Sports.day(keys, date);
            Sports.match(ev);
            return ev;
        }, ev -> {
            if (seq != loadSeq) return;
            events = ev;
            show();
        }, e -> status.setText("Couldn't load games: " + App.message(e)));
    }

    private void show() {
        View f = getCurrentFocus();
        String focusId = f != null && f.getTag() instanceof String && list.findViewWithTag(f.getTag()) != null ? (String) f.getTag() : null;
        list.removeAllViews();
        int shown = 0, live = 0, matched = 0;
        for (Sports.Event e : events) {
            if (!filter.isEmpty() && !filter.equals(e.league)) continue;
            list.addView(card(e));
            shown++;
            if (e.live()) live++;
            if (!e.matches.isEmpty()) matched++;
        }
        status.setText(shown == 0 ? "No games " + (dayOffset == 0 ? "today" : "that day") + " in these leagues."
                : shown + " games" + (live > 0 ? " · " + live + " live" : "") + " · " + matched + " on your channels"
                + (Iptv.sources().isEmpty() ? " (add an IPTV provider to watch)" : ""));
        if (focusId != null) {
            View v = list.findViewWithTag(focusId);
            if (v != null) v.requestFocus();
        }
    }

    private View card(Sports.Event e) {
        LinearLayout c = Ui.horizontal(this);
        c.setTag("ev:" + e.id);
        c.setFocusable(true);
        c.setGravity(Gravity.CENTER_VERTICAL);
        c.setBackground(Ui.focusable(this, Ui.GLASS, Ui.alpha(Color.WHITE, 0.16f), Ui.ACCENT));
        c.setPadding(Ui.dp(this, 18), Ui.dp(this, 12), Ui.dp(this, 18), Ui.dp(this, 12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, Ui.dp(this, 5), 0, Ui.dp(this, 5));
        c.setLayoutParams(lp);

        // Status
        LinearLayout st = Ui.vertical(this);
        TextView when = Ui.heading(this, e.live() ? "● LIVE" : e.done() ? "Final" : tf.format(new Date(e.start)), 15);
        when.setTextColor(e.live() ? Color.parseColor("#F87171") : Ui.TEXT);
        st.addView(when);
        Sports.League l = Sports.league(e.league);
        TextView sub = Ui.single(Ui.text(this, (e.live() || e.done() ? e.detail + " · " : "") + (l == null ? "" : l.name), 12, Ui.MUTED));
        st.addView(sub);
        c.addView(st, new LinearLayout.LayoutParams(Ui.dp(this, 170), -2));

        // Teams
        LinearLayout teams = Ui.vertical(this);
        teams.addView(teamRow(e.away, e));
        teams.addView(teamRow(e.home, e));
        c.addView(teams, new LinearLayout.LayoutParams(0, -2, 1f));

        // Where to watch
        LinearLayout w = Ui.vertical(this);
        w.setGravity(Gravity.END);
        if (!e.matches.isEmpty()) {
            Sports.Match m = e.matches.get(0);
            w.addView(Ui.single(Ui.heading(this, "▶ " + m.entry.channel.name, 15)));
            w.addView(Ui.single(Ui.text(this, e.matches.size() > 1 ? "+" + (e.matches.size() - 1) + " more channels" : "on your channels", 12, Ui.MUTED)));
        } else {
            w.addView(Ui.single(Ui.text(this, e.networks.isEmpty() ? "No channel found" : android.text.TextUtils.join(", ", e.networks), 14, Ui.MUTED)));
            w.addView(Ui.single(Ui.text(this, e.networks.isEmpty() ? "" : "not found on your channels", 12, Ui.DIM)));
        }
        c.addView(w, new LinearLayout.LayoutParams(Ui.dp(this, 300), -2));

        c.setOnClickListener(v -> watch(e));
        c.setOnLongClickListener(v -> { more(e); return true; });
        c.setOnKeyListener((v, k, ev) -> {
            if (k == KeyEvent.KEYCODE_MENU && ev.getAction() == KeyEvent.ACTION_UP) { more(e); return true; }
            return false;
        });
        return c;
    }

    private View teamRow(Sports.Team t, Sports.Event e) {
        LinearLayout r = Ui.horizontal(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, Ui.dp(this, 2), 0, Ui.dp(this, 2));
        ImageView logo = new ImageView(this);
        logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        if (!t.logo.isEmpty()) ImageLoader.load(logo, t.logo, Ui.dp(this, 28));
        r.addView(logo, new LinearLayout.LayoutParams(Ui.dp(this, 28), Ui.dp(this, 28)));
        boolean dim = e.done() && !t.winner;
        TextView name = Ui.single(Ui.text(this, (t.name.isEmpty() ? t.shortName : t.name) + (t.record.isEmpty() ? "" : "  (" + t.record + ")"), 16,
                dim ? Ui.MUTED : Ui.TEXT));
        name.setPadding(Ui.dp(this, 12), 0, 0, 0);
        r.addView(name, new LinearLayout.LayoutParams(0, -2, 1f));
        if (e.live() || e.done()) {
            TextView score = Ui.heading(this, t.score, 18);
            score.setTextColor(dim ? Ui.MUTED : Ui.TEXT);
            score.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 24), 0);
            r.addView(score);
        }
        return r;
    }

    // ------------------------------------------------------------ actions

    private void watch(Sports.Event e) {
        if (e.matches.isEmpty()) { more(e); return; }
        play(e.matches.get(0));
    }

    private void play(Sports.Match m) {
        LiveActivity.addRecent(m.entry);
        startActivity(new Intent(this, LivePlayerActivity.class).putExtra("group", "all")
                .putExtra("source", m.entry.source.id).putExtra("channel", m.entry.channel.id));
    }

    private void more(Sports.Event e) {
        List<String> items = new ArrayList<>();
        for (Sports.Match m : e.matches) {
            String p = m.program == null ? "" : "  ·  " + m.program.title + "  " + tf.format(new Date(m.program.start));
            items.add("▶ " + m.entry.channel.name + p);
        }
        Sports.League l = Sports.league(e.league);
        int tableAt = items.size();
        if (l != null) items.add("Table · " + l.name);
        new AlertDialog.Builder(this)
                .setTitle(e.away.shortName + " at " + e.home.shortName + (e.networks.isEmpty() ? "" : "  ·  " + android.text.TextUtils.join(", ", e.networks)))
                .setItems(items.toArray(new String[0]), (d, w) -> {
                    if (w < tableAt) play(e.matches.get(w));
                    else table(l);
                }).show();
    }

    private void table(Sports.League l) {
        Toast.makeText(this, "Loading the " + l.name + " table…", Toast.LENGTH_SHORT).show();
        App.async(() -> Sports.standings(l), rows -> {
            ScrollView sv = new FocusScroll.Vertical(this);
            LinearLayout box = Ui.vertical(this);
            box.setPadding(Ui.dp(this, 24), Ui.dp(this, 12), Ui.dp(this, 24), Ui.dp(this, 12));
            sv.addView(box);
            int pos = 0;
            for (String[] r : rows) {
                if (!r[0].isEmpty()) {
                    pos = 0;
                    TextView h = Ui.heading(this, r[0], 16);
                    h.setPadding(0, Ui.dp(this, 12), 0, Ui.dp(this, 4));
                    box.addView(h);
                    continue;
                }
                LinearLayout line = Ui.horizontal(this);
                line.setFocusable(true);
                line.setBackground(Ui.focusable(this, Color.TRANSPARENT, Ui.alpha(Color.WHITE, 0.12f), 0));
                line.setPadding(Ui.dp(this, 6), Ui.dp(this, 3), Ui.dp(this, 6), Ui.dp(this, 3));
                line.addView(Ui.text(this, String.valueOf(++pos), 14, Ui.DIM), new LinearLayout.LayoutParams(Ui.dp(this, 32), -2));
                line.addView(Ui.single(Ui.text(this, r[1], 14, Ui.TEXT)), new LinearLayout.LayoutParams(0, -2, 1f));
                line.addView(Ui.text(this, r[2], 14, Ui.TEXT), new LinearLayout.LayoutParams(Ui.dp(this, 150), -2));
                line.addView(Ui.text(this, r[3], 14, Ui.MUTED), new LinearLayout.LayoutParams(Ui.dp(this, 80), -2));
                box.addView(line);
            }
            if (rows.isEmpty()) box.addView(Ui.text(this, "No table for this league right now.", 14, Ui.MUTED));
            new AlertDialog.Builder(this).setTitle(l.name).setView(sv).setPositiveButton("Close", null).show();
        }, err -> Toast.makeText(this, "Table: " + App.message(err), Toast.LENGTH_LONG).show());
    }

    // ------------------------------------------------------------ live refresh

    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            boolean anyLive = false;
            long now = System.currentTimeMillis();
            for (Sports.Event e : events) if (e.live() || (!e.done() && e.start < now + 300_000 && e.start > now - 6 * 3600_000L)) anyLive = true;
            if (anyLive && dayOffset == 0) load(false);
            App.MAIN.postDelayed(this, 30_000);
        }
    };

    @Override
    protected void onResume() {
        super.onResume();
        App.MAIN.postDelayed(refresh, 30_000);
    }

    @Override
    protected void onPause() {
        super.onPause();
        App.MAIN.removeCallbacks(refresh);
    }
}
