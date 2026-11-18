package com.fluxtv.app;

import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;

import org.json.JSONArray;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Live TV, laid out like TiviMate / Sparkle: what's selected and a live preview on top, the TV guide below,
 * channel groups on ←. OK on what's on now watches it; OK on a later programme records it.
 */
public class LiveActivity extends FluxActivity {

    @Override
    protected String sidebarKey() {
        return "live";
    }

    private GuideView guide;
    private TextView chan, progTitle, progTime, progDesc, badge, empty, numberBox;
    private ImageView chanLogo;
    private ProgressBar progBar;
    private FrameLayout previewBox;
    private PlayerView preview;
    private ExoPlayer previewPlayer;
    private String previewKey = "";
    private LinearLayout groupsPanel, groupsList;
    private ScrollView groupsScroll;
    private String group;
    private final StringBuilder typed = new StringBuilder();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (Ratings.restricted()) {
            Toast.makeText(this, "Live TV is off for this profile", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Ui.BG);
        LinearLayout col = Ui.vertical(this);
        root.addView(col, new FrameLayout.LayoutParams(-1, -1));

        // ---- top: info + preview
        LinearLayout topRow = Ui.horizontal(this);
        topRow.setPadding(Ui.dp(this, 36), Ui.dp(this, 22), Ui.dp(this, 28), Ui.dp(this, 10));
        LinearLayout info = Ui.vertical(this);
        LinearLayout chanRow = Ui.horizontal(this);
        chanRow.setGravity(Gravity.CENTER_VERTICAL);
        chanLogo = new ImageView(this);
        chanLogo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        chanRow.addView(chanLogo, new LinearLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 30)));
        chan = Ui.single(Ui.text(this, "Live TV", 15, Ui.MUTED));
        chan.setPadding(Ui.dp(this, 10), 0, 0, 0);
        chanRow.addView(chan);
        badge = Ui.text(this, "", 12, Color.WHITE);
        badge.setPadding(Ui.dp(this, 8), Ui.dp(this, 2), Ui.dp(this, 8), Ui.dp(this, 2));
        badge.setBackground(Ui.rounded(this, 0xFFE53935, 0, 6));
        badge.setVisibility(View.GONE);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-2, -2);
        blp.setMargins(Ui.dp(this, 10), 0, 0, 0);
        chanRow.addView(badge, blp);
        info.addView(chanRow);
        progTitle = Ui.single(Ui.heading(this, "", 28));
        progTitle.setPadding(0, Ui.dp(this, 8), 0, 0);
        info.addView(progTitle);
        progTime = Ui.text(this, "", 14, Ui.MUTED);
        progTime.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 6));
        info.addView(progTime);
        progBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progBar.setMax(1000);
        progBar.setProgressTintList(android.content.res.ColorStateList.valueOf(Ui.ACCENT));
        progBar.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(Ui.alpha(Ui.TEXT, 0.2f)));
        info.addView(progBar, new LinearLayout.LayoutParams(Ui.dp(this, 320), Ui.dp(this, 4)));
        progDesc = Ui.text(this, "", 14, Ui.DIM);
        progDesc.setMaxLines(3);
        progDesc.setEllipsize(TextUtils.TruncateAt.END);
        progDesc.setPadding(0, Ui.dp(this, 10), Ui.dp(this, 20), 0);
        info.addView(progDesc);
        guideStatus = Ui.single(Ui.text(this, "", 13, Ui.TEXT));
        guideStatus.setBackground(Ui.rounded(this, Ui.alpha(Ui.ACCENT, 0.22f), 0, 999));
        guideStatus.setPadding(Ui.dp(this, 12), Ui.dp(this, 5), Ui.dp(this, 14), Ui.dp(this, 5));
        guideStatus.setVisibility(View.GONE);
        LinearLayout.LayoutParams gsl = new LinearLayout.LayoutParams(-2, -2);
        gsl.setMargins(0, Ui.dp(this, 10), 0, 0);
        info.addView(guideStatus, gsl);
        topRow.addView(info, new LinearLayout.LayoutParams(0, -1, 1f));

        previewBox = new FrameLayout(this);
        previewBox.setBackground(Ui.rounded(this, Color.BLACK, 0, 12));
        previewBox.setClipToOutline(true);
        preview = new PlayerView(this);
        preview.setUseController(false);
        preview.setShutterBackgroundColor(Color.BLACK);
        previewBox.addView(preview, new FrameLayout.LayoutParams(-1, -1));
        topRow.addView(previewBox, new LinearLayout.LayoutParams(Ui.dp(this, 384), Ui.dp(this, 216)));
        col.addView(topRow, new LinearLayout.LayoutParams(-1, Ui.dp(this, 250)));

        // ---- the guide
        guide = new GuideView(this);
        if (android.os.Build.VERSION.SDK_INT >= 26) guide.setDefaultFocusHighlightEnabled(false);
        guide.setPadding(0, 0, 0, 0);
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(-1, 0, 1f);
        glp.setMargins(Ui.dp(this, 24), 0, Ui.dp(this, 16), Ui.dp(this, 8));
        col.addView(guide, glp);
        guide.setListener(new GuideView.Listener() {
            @Override public void onSelect(GuideView.Entry e, Iptv.Program p) { showInfo(e, p); schedulePreview(e); }
            @Override public void onClick(GuideView.Entry e, Iptv.Program p) { click(e, p); }
            @Override public void onLongClick(GuideView.Entry e, Iptv.Program p) { options(e, p); }
            @Override public void onLeftEdge() { openGroups(); }
        });

        empty = Ui.text(this, "Loading channels…", 16, Ui.MUTED);
        empty.setGravity(Gravity.CENTER);
        root.addView(empty, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER));

        numberBox = Ui.heading(this, "", 40);
        numberBox.setPadding(Ui.dp(this, 22), Ui.dp(this, 10), Ui.dp(this, 22), Ui.dp(this, 10));
        numberBox.setBackground(Ui.rounded(this, Ui.alpha(Ui.PANEL, 0.95f), 0, 14));
        numberBox.setVisibility(View.GONE);
        FrameLayout.LayoutParams nlp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        nlp.setMargins(0, Ui.dp(this, 30), Ui.dp(this, 40), 0);
        root.addView(numberBox, nlp);

        // ---- groups (← from the guide)
        groupsPanel = Ui.vertical(this);
        groupsPanel.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{Ui.PANEL, Ui.alpha(Ui.PANEL, 0.97f)}));
        groupsPanel.setElevation(Ui.dp(this, 10));
        groupsPanel.setVisibility(View.GONE);
        TextView gh = Ui.heading(this, "Live TV", 22);
        gh.setPadding(Ui.dp(this, 22), Ui.dp(this, 24), 0, Ui.dp(this, 10));
        groupsPanel.addView(gh);
        groupsScroll = new FocusScroll.Vertical(this);
        groupsScroll.setVerticalScrollBarEnabled(false);
        groupsList = Ui.vertical(this);
        groupsList.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 12), Ui.dp(this, 24));
        groupsScroll.addView(groupsList);
        groupsPanel.addView(groupsScroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        root.addView(groupsPanel, new FrameLayout.LayoutParams(Ui.dp(this, 300), -1));

        setContentView(root);
        group = Store.get("live_group").isEmpty() ? "all" : Store.get("live_group");
        // Opened for a particular group (e.g. "all", "favorites" or "sourceId|groupId").
        if (getIntent().getStringExtra("group") != null) group = getIntent().getStringExtra("group");
        load();
        tuneFromLink(getIntent());
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        tuneFromLink(i);
    }

    /** fluxtv://live/101 (or the "channel" extra) tunes straight to channel 101. */
    private void tuneFromLink(Intent i) {
        String num = i.getStringExtra("channel_number");
        Uri u = i.getData();
        if (num == null && u != null && u.getLastPathSegment() != null && u.getLastPathSegment().matches("\\d+")) num = u.getLastPathSegment();
        if (num == null) return;
        String n = num;
        App.async(() -> entries("all"), list -> {
            for (GuideView.Entry e : list) if (e.channel.num.equals(n)) {
                group = "all";
                watch(e);
                return;
            }
            Toast.makeText(this, "No channel " + n, Toast.LENGTH_SHORT).show();
        }, null);
    }

    // ------------------------------------------------------------ guide download indicator

    private TextView guideStatus;
    private boolean guideWasBusy;

    /** Shows "⟳ Updating the TV guide…" while a guide downloads; redraws the guide when it's done. */
    private final Runnable guideWatch = new Runnable() {
        @Override public void run() {
            if (guideStatus == null) return;
            boolean busy = Iptv.guideBusy();
            if (busy) guideStatus.setText("⟳  " + Iptv.guideStatus());
            guideStatus.setVisibility(busy ? View.VISIBLE : View.GONE);
            if (guideWasBusy && !busy && guide != null) {
                guide.invalidate();
                GuideView.Entry e = guide.selected();
                if (e != null) showInfo(e, GuideView.at(Iptv.programs(e.source, e.channel), System.currentTimeMillis()));
            }
            guideWasBusy = busy;
            App.MAIN.postDelayed(this, busy ? 1000 : 3000);
        }
    };

    // ------------------------------------------------------------ data

    private Map<String, String> groupNames = new LinkedHashMap<>();
    private Map<String, Integer> groupCounts = new LinkedHashMap<>();

    private void load() {
        List<Iptv.Source> sources = Iptv.sources();
        if (sources.isEmpty()) {
            empty.setText("No IPTV provider yet. Add one in Settings → Sources → IPTV.");
            View add = Ui.button(this, R.drawable.ic_plus, "Add an IPTV provider", v -> startActivity(new Intent(this, IptvActivity.class)));
            ((FrameLayout) empty.getParent()).addView(add, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM));
            add.requestFocus();
            return;
        }
        App.async(() -> {
            Map<String, String> names = new LinkedHashMap<>();
            Map<String, Integer> counts = new LinkedHashMap<>();
            for (Iptv.Source s : sources) {
                Map<String, String> cats = new LinkedHashMap<>();
                try { for (Iptv.Category c : Iptv.liveCategories(s)) cats.put(c.id, c.name); } catch (Exception ignored) { }
                for (Iptv.Channel c : Iptv.shownChannels(s)) {
                    String gid = s.id + "|" + groupOf(s, c);
                    String nm = cats.containsKey(groupOf(s, c)) ? cats.get(groupOf(s, c)) : groupOf(s, c);
                    names.put(gid, (sources.size() > 1 ? s.name + " · " : "") + nm);
                    Integer n = counts.get(gid);
                    counts.put(gid, n == null ? 1 : n + 1);
                }
            }
            return new Object[]{names, counts};
        }, r -> {
            @SuppressWarnings("unchecked") Map<String, String> n = (Map<String, String>) r[0];
            @SuppressWarnings("unchecked") Map<String, Integer> c = (Map<String, Integer>) r[1];
            groupNames = n;
            groupCounts = c;
            showGroup(group, true);
            // The full guide: from disk straight away, refreshed from the provider in the background.
            App.async(() -> {
                for (Iptv.Source s : sources) try { Iptv.loadGuide(s, false); } catch (Exception ignored) { }
                return true;
            }, ok -> { guide.invalidate(); GuideView.Entry e = guide.selected(); if (e != null) showInfo(e, GuideView.at(Iptv.programs(e.source, e.channel), System.currentTimeMillis())); }, null);
        }, e -> empty.setText("Couldn't load channels: " + App.message(e)));
    }

    static String groupOf(Iptv.Source s, Iptv.Channel c) {
        return Iptv.groupKey(s, c);
    }

    /** After hiding a channel or group: rebuild the group list and the guide. */
    private void reloadAfterFilter() {
        if (!"all".equals(group) && !"favorites".equals(group)) group = "all";
        load();
    }

    /** Channels in a group: "all", "favorites", or "sourceId|groupId". Blocking-safe (channels are cached). */
    static List<GuideView.Entry> entries(String group) {
        List<GuideView.Entry> out = new ArrayList<>();
        java.util.Set<String> favs = favorites();
        for (Iptv.Source s : Iptv.sources()) {
            List<Iptv.Channel> list;
            try { list = Iptv.channels(s); } catch (Exception e) { continue; }
            for (Iptv.Channel c : list) {
                // Favorites always show; elsewhere channels hidden in Settings (country, group, one by one) don't.
                boolean in = "favorites".equals(group) ? favs.contains(s.id + "|" + c.id)
                        : Iptv.shown(s, c) && ("all".equals(group) || group.equals(s.id + "|" + groupOf(s, c)));
                if (in) out.add(new GuideView.Entry(s, c));
            }
        }
        return out;
    }

    static java.util.Set<String> favorites() {
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        try {
            JSONArray a = new JSONArray(Store.get("live_favs").isEmpty() ? "[]" : Store.get("live_favs"));
            for (int i = 0; i < a.length(); i++) out.add(a.optString(i));
        } catch (Exception ignored) { }
        return out;
    }

    /** Recently watched channels, newest first (for the Live TV row on Home). */
    static void addRecent(GuideView.Entry e) {
        List<String> r = new ArrayList<>();
        r.add(e.source.id + "|" + e.channel.id);
        try {
            JSONArray a = new JSONArray(Store.get("live_recent").isEmpty() ? "[]" : Store.get("live_recent"));
            for (int i = 0; i < a.length() && r.size() < 12; i++) if (!r.contains(a.optString(i))) r.add(a.optString(i));
        } catch (Exception ignored) { }
        Store.put("live_recent", new JSONArray(r).toString());
    }

    /** Channels for the Home row: Favorites first, then recently watched. Blocking. */
    static List<GuideView.Entry> homeChannels() {
        List<String> keys = new ArrayList<>(favorites());
        try {
            JSONArray a = new JSONArray(Store.get("live_recent").isEmpty() ? "[]" : Store.get("live_recent"));
            for (int i = 0; i < a.length(); i++) if (!keys.contains(a.optString(i))) keys.add(a.optString(i));
        } catch (Exception ignored) { }
        List<GuideView.Entry> out = new ArrayList<>();
        Map<String, GuideView.Entry> all = new LinkedHashMap<>();
        for (Iptv.Source s : Iptv.sources()) {
            try { for (Iptv.Channel c : Iptv.channels(s)) all.put(s.id + "|" + c.id, new GuideView.Entry(s, c)); } catch (Exception ignored) { }
        }
        for (String k : keys) if (all.containsKey(k) && out.size() < 16) out.add(all.get(k));
        if (out.isEmpty()) for (GuideView.Entry e : all.values()) { if (out.size() >= 10) break; if (Iptv.shown(e.source, e.channel)) out.add(e); }
        return out;
    }

    static boolean toggleFavorite(GuideView.Entry e) {
        java.util.Set<String> f = favorites();
        String k = e.source.id + "|" + e.channel.id;
        boolean added = f.add(k);
        if (!added) f.remove(k);
        Store.put("live_favs", new JSONArray(new ArrayList<>(f)).toString());
        return added;
    }

    private void showGroup(String g, boolean first) {
        App.async(() -> entries(g), list -> {
            if (list.isEmpty() && !"all".equals(g)) { showGroup("all", first); return; }
            group = g;
            Store.put("live_group", g);
            int sel = 0;
            String last = Store.get("live_last");
            for (int i = 0; i < list.size(); i++) {
                GuideView.Entry e = list.get(i);
                if ((e.source.id + "|" + e.channel.id).equals(last)) { sel = i; break; }
            }
            guide.setEntries(list, sel);
            empty.setVisibility(list.isEmpty() ? View.VISIBLE : View.GONE);
            if (list.isEmpty()) empty.setText("No channels here.");
            closeGroups();
            guide.requestFocus();
        }, e -> empty.setText(App.message(e)));
    }

    // ------------------------------------------------------------ info + preview

    private final DateFormat tf = android.text.format.DateFormat.getTimeFormat(App.ctx);

    private void showInfo(GuideView.Entry e, Iptv.Program p) {
        chan.setText((e.channel.num.isEmpty() ? "" : e.channel.num + "  ·  ") + e.channel.name);
        ImageLoader.load(chanLogo, e.channel.logo, Ui.dp(this, 60));
        long now = System.currentTimeMillis();
        boolean recording = Dvr.recordingNow(e.source, e.channel);
        boolean scheduled = p != null && Dvr.find(e.source, e.channel, p.start) != null;
        badge.setVisibility(recording || scheduled ? View.VISIBLE : View.GONE);
        badge.setText(recording && (p == null || p.start <= now) ? "● REC" : "● Scheduled");
        if (p == null) {
            progTitle.setText(e.channel.name);
            progTime.setText(Iptv.guideReady(e.source) ? "No guide information for this channel" : "Loading the TV guide…");
            progBar.setVisibility(View.INVISIBLE);
            progDesc.setText("");
            return;
        }
        progTitle.setText(p.title);
        String t = tf.format(new Date(p.start)) + " – " + tf.format(new Date(p.end));
        if (p.start <= now && p.end > now) {
            long left = (p.end - now) / 60000;
            t += "   ·   " + (left < 1 ? "ending" : left + " min left");
            progBar.setVisibility(View.VISIBLE);
            progBar.setProgress((int) (1000 * (now - p.start) / Math.max(1, p.end - p.start)));
        } else if (p.end <= now) {
            boolean today = now - p.start < 18 * 3600_000L;
            t += "   ·   " + (today ? "earlier" : android.text.format.DateUtils.formatDateTime(this, p.start,
                    android.text.format.DateUtils.FORMAT_SHOW_WEEKDAY | android.text.format.DateUtils.FORMAT_ABBREV_WEEKDAY))
                    + (Iptv.canReplay(e.channel, p) ? "   ·   ↺ OK to replay" : "");
            progBar.setVisibility(View.INVISIBLE);
        } else {
            long in = (p.start - now) / 60000;
            t += "   ·   " + (in < 60 ? "in " + in + " min" : android.text.format.DateUtils.formatDateTime(this, p.start,
                    android.text.format.DateUtils.FORMAT_SHOW_WEEKDAY | android.text.format.DateUtils.FORMAT_ABBREV_WEEKDAY));
            progBar.setVisibility(View.INVISIBLE);
        }
        progTime.setText(t);
        progDesc.setText(p.desc);
    }

    private final Runnable startPreview = this::playPreview;

    private void schedulePreview(GuideView.Entry e) {
        App.MAIN.removeCallbacks(startPreview);
        if ("off".equals(Store.get("live_preview"))) return;
        App.MAIN.postDelayed(startPreview, 1200);   // only after you stop on a channel
    }

    private boolean triedHls;

    private void playPreview() {
        GuideView.Entry e = guide.selected();
        if (e == null || isFinishing()) return;
        String key = e.source.id + "|" + e.channel.id;
        if (key.equals(previewKey) && previewPlayer != null) return;
        previewKey = key;
        triedHls = false;
        if (previewPlayer == null) {
            previewPlayer = Players.create(this);
            preview.setPlayer(previewPlayer);
            previewPlayer.addListener(new Player.Listener() {
                @Override public void onPlayerError(PlaybackException err) {
                    GuideView.Entry cur = guide.selected();
                    if (!triedHls && cur != null && cur.source.xtream()) {
                        triedHls = true;
                        previewPlayer.setMediaItem(MediaItem.fromUri(Uri.parse(Iptv.liveUrl(cur.source, cur.channel, true))));
                        previewPlayer.prepare();
                        previewPlayer.play();
                    }
                }
            });
        }
        previewPlayer.setMediaItem(MediaItem.fromUri(Uri.parse(Iptv.liveUrl(e.source, e.channel, false))));
        previewPlayer.prepare();
        previewPlayer.play();
    }

    private void stopPreview() {
        App.MAIN.removeCallbacks(startPreview);
        if (previewPlayer != null) {
            previewPlayer.release();
            previewPlayer = null;
            preview.setPlayer(null);
        }
        previewKey = "";
    }

    // ------------------------------------------------------------ actions

    private void click(GuideView.Entry e, Iptv.Program p) {
        long now = System.currentTimeMillis();
        if (p != null && p.end <= now) {
            if (Iptv.canReplay(e.channel, p)) replay(e, p);
            else Toast.makeText(this, "This channel has no replays (catch-up) for that time", Toast.LENGTH_SHORT).show();
            return;
        }
        if (p == null || p.start <= now) {
            watch(e);
            return;
        }
        org.json.JSONObject rec = Dvr.find(e.source, e.channel, p.start);
        List<String> items = new ArrayList<>();
        items.add(rec == null ? "● Record this programme" : "Cancel this recording");
        items.add("Watch " + e.channel.name + " now");
        items.add("Recordings");
        new AlertDialog.Builder(this)
                .setTitle(p.title + "  ·  " + tf.format(new Date(p.start)) + " – " + tf.format(new Date(p.end)) + "  ·  " + e.channel.name)
                .setItems(items.toArray(new String[0]), (d, w) -> {
                    if (w == 0) {
                        if (rec == null) {
                            Dvr.schedule(this, e.source, e.channel, p.title, p.desc, p.start, p.end);
                            Toast.makeText(this, "Will record " + p.title + " · " + Dvr.targetLabel(this), Toast.LENGTH_LONG).show();
                        } else {
                            Dvr.cancel(this, rec.optString("id"));
                        }
                        guide.invalidate();
                        showInfo(e, p);
                    } else if (w == 1) {
                        watch(e);
                    } else {
                        startActivity(new Intent(this, RecordingsActivity.class));
                    }
                })
                .show();
    }

    private void options(GuideView.Entry e, Iptv.Program p) {
        long now = System.currentTimeMillis();
        boolean fav = favorites().contains(e.source.id + "|" + e.channel.id);
        boolean rec = Dvr.recordingNow(e.source, e.channel);
        boolean startOver = p != null && p.start <= now && Iptv.canReplay(e.channel, p);
        String[] items = {
                startOver ? (p.end <= now ? "Replay this programme" : "Watch from the start") : "Watch",
                rec ? "Stop recording" : (p != null && p.start > now ? "● Record this programme" : "● Record now"),
                fav ? "Remove from Favorites" : "Add to Favorites",
                "Recordings",
                "Multiview (watch up to 4 channels)",
                "Refresh the TV guide",
                "Hide this channel",
                "Hide the group \u201C" + Iptv.groupName(e.source, groupOf(e.source, e.channel)) + "\u201D",
                "Choose which channels show…",
        };
        new AlertDialog.Builder(this)
                .setTitle(e.channel.name)
                .setItems(items, (d, w) -> {
                    switch (w) {
                        case 0: if (startOver) replay(e, p); else watch(e); break;
                        case 1:
                            if (rec) {
                                for (org.json.JSONObject r : Dvr.all()) if (Dvr.RECORDING.equals(r.optString("status"))
                                        && r.optString("channel").equals(e.channel.id)) Dvr.cancel(this, r.optString("id"));
                            } else if (p != null && p.start > now) {
                                Dvr.schedule(this, e.source, e.channel, p.title, p.desc, p.start, p.end);
                            } else {
                                Dvr.recordNow(this, e.source, e.channel);
                                Toast.makeText(this, "Recording " + e.channel.name + " · " + Dvr.targetLabel(this), Toast.LENGTH_LONG).show();
                            }
                            guide.invalidate();
                            break;
                        case 2:
                            Toast.makeText(this, toggleFavorite(e) ? "Added to Favorites" : "Removed from Favorites", Toast.LENGTH_SHORT).show();
                            if ("favorites".equals(group)) showGroup(group, false);
                            break;
                        case 3: startActivity(new Intent(this, RecordingsActivity.class)); break;
                        case 4:
                            stopPreview();
                            startActivity(new Intent(this, MultiviewActivity.class).putExtra("group", group)
                                    .putExtra("source", e.source.id).putExtra("channel", e.channel.id));
                            break;
                        case 6:
                            Iptv.setChannelHidden(e.source, e.channel, true);
                            Toast.makeText(this, e.channel.name + " hidden. Show it again in Settings → Live TV → Channels.", Toast.LENGTH_LONG).show();
                            reloadAfterFilter();
                            break;
                        case 7:
                            Iptv.setGroupHidden(e.source, groupOf(e.source, e.channel), true);
                            Toast.makeText(this, "Group hidden. Show it again in Settings → Live TV → Channels.", Toast.LENGTH_LONG).show();
                            reloadAfterFilter();
                            break;
                        case 8:
                            stopPreview();
                            startActivity(new Intent(this, IptvFilterActivity.class));
                            break;
                        default:
                            Toast.makeText(this, "Updating the guide…", Toast.LENGTH_SHORT).show();
                            App.async(() -> { Iptv.loadGuide(e.source, true); return true; },
                                    ok -> { guide.invalidate(); Toast.makeText(this, "Guide updated", Toast.LENGTH_SHORT).show(); },
                                    err -> Toast.makeText(this, "Guide: " + App.message(err), Toast.LENGTH_LONG).show());
                    }
                })
                .show();
    }

    /** Catch-up: plays a past programme (or the current one from its start) with a normal seek bar. */
    private void replay(GuideView.Entry e, Iptv.Program p) {
        stopPreview();
        Toast.makeText(this, "Opening " + p.title + "…", Toast.LENGTH_SHORT).show();
        App.async(() -> Iptv.catchupUrl(e.source, e.channel, p), url -> {
            String vid = "dvr:cu:" + e.source.id + ":" + e.channel.id + ":" + p.start;
            PlayInfo pi = PlayInfo.of("movie", vid, vid, p.title, e.channel.logo,
                    e.channel.name + " · " + new java.text.SimpleDateFormat("EEE MMM d, h:mm a", java.util.Locale.getDefault()).format(new Date(p.start)));
            startActivity(pi.into(new Intent(this, PlayerActivity.class)).putExtra("url", url));
        }, err -> Toast.makeText(this, "Replay: " + App.message(err), Toast.LENGTH_LONG).show());
    }

    private void watch(GuideView.Entry e) {
        Store.put("live_last", e.source.id + "|" + e.channel.id);
        stopPreview();
        startActivity(new Intent(this, LivePlayerActivity.class)
                .putExtra("group", group)
                .putExtra("source", e.source.id)
                .putExtra("channel", e.channel.id));
    }

    // ------------------------------------------------------------ groups panel

    private void openGroups() {
        groupsList.removeAllViews();
        View focus = null;
        View first = groupRow("favorites", "★  Favorites", favorites().size(), null);
        focus = groupRow("all", "All channels", -1, null);
        if (!"all".equals(group)) focus = "favorites".equals(group) ? first : null;
        View recs = Ui.button(this, R.drawable.ic_record, "Recordings", v -> { closeGroups(); startActivity(new Intent(this, RecordingsActivity.class)); });
        groupsList.addView(recs);
        groupsList.addView(Ui.button(this, R.drawable.ic_sports, "Sports", v -> { closeGroups(); stopPreview(); startActivity(new Intent(this, SportsActivity.class)); }));
        TextView h = Ui.text(this, "GROUPS", 12, Ui.DIM);
        h.setPadding(Ui.dp(this, 10), Ui.dp(this, 14), 0, Ui.dp(this, 6));
        groupsList.addView(h);
        for (Map.Entry<String, String> g : groupNames.entrySet()) {
            Integer n = groupCounts.get(g.getKey());
            View r = groupRow(g.getKey(), g.getValue(), n == null ? 0 : n, null);
            if (g.getKey().equals(group)) focus = r;
        }
        if (focus == null) focus = first;
        groupsPanel.setVisibility(View.VISIBLE);
        guide.setFocusable(false);
        View f = focus;
        if (f != null) f.post(f::requestFocus);
    }

    private View groupRow(String key, String name, int count, View focus) {
        LinearLayout row = Ui.horizontal(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setFocusable(true);
        row.setClickable(true);
        row.setPadding(Ui.dp(this, 12), Ui.dp(this, 9), Ui.dp(this, 12), Ui.dp(this, 9));
        android.graphics.drawable.StateListDrawable bg = new android.graphics.drawable.StateListDrawable();
        bg.addState(new int[]{android.R.attr.state_focused}, Ui.rounded(this, Ui.FOCUS, 0, 10));
        bg.addState(new int[]{android.R.attr.state_selected}, Ui.rounded(this, Ui.BUTTON, 0, 10));
        bg.addState(new int[]{}, Ui.rounded(this, Color.TRANSPARENT, 0, 10));
        row.setBackground(bg);
        TextView t = Ui.single(Ui.text(this, name, 15, Ui.TEXT));
        t.setTextColor(new android.content.res.ColorStateList(new int[][]{{android.R.attr.state_focused}, {}}, new int[]{Ui.ON_FOCUS, Ui.TEXT}));
        t.setDuplicateParentStateEnabled(true);
        row.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
        if (count >= 0) {
            TextView c = Ui.text(this, String.valueOf(count), 12, Ui.DIM);
            c.setDuplicateParentStateEnabled(true);
            row.addView(c);
        }
        row.setSelected(key.equals(group));
        row.setOnClickListener(v -> showGroup(key, false));
        groupsList.addView(row, new LinearLayout.LayoutParams(-1, -2));
        if (key.equals(group)) return row;
        return focus == null ? row : focus;
    }

    private void closeGroups() {
        groupsPanel.setVisibility(View.GONE);
        guide.setFocusable(true);
    }

    private boolean groupsOpen() {
        return groupsPanel.getVisibility() == View.VISIBLE;
    }

    // ------------------------------------------------------------ keys

    private final Runnable jump = () -> {
        String n = typed.toString();
        typed.setLength(0);
        numberBox.setVisibility(View.GONE);
        List<GuideView.Entry> list = entries("all".equals(group) ? "all" : group);
        for (int pass = 0; pass < 2; pass++) {
            List<GuideView.Entry> in = pass == 0 ? list : entries("all");
            for (int i = 0; i < in.size(); i++) {
                if (in.get(i).channel.num.equals(n)) {
                    if (pass == 1) { group = "all"; guide.setEntries(in, i); } else guide.select(i);
                    return;
                }
            }
        }
        Toast.makeText(this, "No channel " + n, Toast.LENGTH_SHORT).show();
    };

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        if (e.getAction() == KeyEvent.ACTION_DOWN && !sidebarOpen()) {
            if (groupsOpen()) {
                if (k == KeyEvent.KEYCODE_BACK || k == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    closeGroups();
                    guide.requestFocus();
                    return true;
                }
            } else if (getCurrentFocus() == guide) {
                if (k == KeyEvent.KEYCODE_DPAD_LEFT) return guide.onKeyDown(k, e);   // the guide decides (groups at its edge)
                if (k == KeyEvent.KEYCODE_MENU) { options(guide.selected(), null); return true; }
                if (k >= KeyEvent.KEYCODE_0 && k <= KeyEvent.KEYCODE_9) {
                    typed.append((char) ('0' + k - KeyEvent.KEYCODE_0));
                    numberBox.setText(typed);
                    numberBox.setVisibility(View.VISIBLE);
                    App.MAIN.removeCallbacks(jump);
                    App.MAIN.postDelayed(jump, 1500);
                    return true;
                }
            }
        } else if (e.getAction() == KeyEvent.ACTION_UP && groupsOpen() && k == KeyEvent.KEYCODE_BACK) {
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            guide.tick();
            GuideView.Entry e = guide.selected();
            if (e != null) showInfo(e, GuideView.at(Iptv.programs(e.source, e.channel), Math.max(System.currentTimeMillis(), 0)));
            App.MAIN.postDelayed(this, 30000);
        }
    };

    @Override
    protected void onResume() {
        super.onResume();
        App.MAIN.removeCallbacks(guideWatch);
        guideWatch.run();
        if (guide != null) {
            guide.invalidate();
            App.MAIN.postDelayed(tick, 30000);
            GuideView.Entry e = guide.selected();
            if (e != null) schedulePreview(e);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        App.MAIN.removeCallbacks(tick);
        App.MAIN.removeCallbacks(guideWatch);
        stopPreview();
    }
}
