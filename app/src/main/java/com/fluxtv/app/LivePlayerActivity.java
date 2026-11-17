package com.fluxtv.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.Tracks;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * Full-screen Live TV in the style of TiviMate / Sparkle:
 * ↑↓ (or CH+/−) change channel · OK shows the channel list · number keys tune · MENU / hold OK for options
 * (record, audio, subtitles, picture size) · Back returns to the guide.
 */
public class LivePlayerActivity extends Activity {

    private ExoPlayer player;
    private PlayerView view;
    private List<GuideView.Entry> list = new ArrayList<>();
    private int index, previous = -1;
    private String group;
    private boolean triedHls;

    private LinearLayout infoBar;
    private ImageView logo;
    private TextView chanText, nowTitle, nowTime, nextText, clock, rec, quality, numberBox, error;
    private ProgressBar progress;
    private FrameLayout listPanel;
    private ListView channelList;
    private final DateFormat tf = android.text.format.DateFormat.getTimeFormat(App.ctx);
    private final StringBuilder typed = new StringBuilder();

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        view = new PlayerView(this);
        view.setUseController(false);
        view.setShutterBackgroundColor(Color.BLACK);
        view.setKeepContentOnPlayerReset(true);
        root.addView(view, new FrameLayout.LayoutParams(-1, -1));

        // ---- info bar (bottom)
        infoBar = Ui.vertical(this);
        infoBar.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{0xF0000000, 0xC0000000, 0x00000000}));
        infoBar.setPadding(Ui.dp(this, 48), Ui.dp(this, 70), Ui.dp(this, 48), Ui.dp(this, 30));
        LinearLayout r1 = Ui.horizontal(this);
        r1.setGravity(Gravity.CENTER_VERTICAL);
        logo = new ImageView(this);
        logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        r1.addView(logo, new LinearLayout.LayoutParams(Ui.dp(this, 96), Ui.dp(this, 60)));
        LinearLayout names = Ui.vertical(this);
        names.setPadding(Ui.dp(this, 18), 0, 0, 0);
        chanText = Ui.single(Ui.text(this, "", 15, Ui.MUTED));
        names.addView(chanText);
        nowTitle = Ui.single(Ui.heading(this, "", 26));
        names.addView(nowTitle);
        r1.addView(names, new LinearLayout.LayoutParams(0, -2, 1f));
        rec = badge("● REC", 0xFFE53935);
        r1.addView(rec);
        quality = badge("", Ui.alpha(Color.WHITE, 0.18f));
        r1.addView(quality);
        infoBar.addView(r1);
        LinearLayout r2 = Ui.horizontal(this);
        r2.setGravity(Gravity.CENTER_VERTICAL);
        r2.setPadding(Ui.dp(this, 114), Ui.dp(this, 8), 0, 0);
        nowTime = Ui.text(this, "", 13, Ui.MUTED);
        r2.addView(nowTime);
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(1000);
        progress.setProgressTintList(android.content.res.ColorStateList.valueOf(Ui.ACCENT));
        progress.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(Ui.alpha(Color.WHITE, 0.25f)));
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(0, Ui.dp(this, 4), 1f);
        plp.setMargins(Ui.dp(this, 14), 0, Ui.dp(this, 14), 0);
        r2.addView(progress, plp);
        nextText = Ui.single(Ui.text(this, "", 13, Ui.MUTED));
        r2.addView(nextText, new LinearLayout.LayoutParams(Ui.dp(this, 360), -2));
        infoBar.addView(r2);
        TextView hint = Ui.text(this, "↑↓ channels   ·   OK channel list   ·   MENU or hold OK: record, audio, subtitles", 12, Ui.DIM);
        hint.setPadding(Ui.dp(this, 114), Ui.dp(this, 10), 0, 0);
        infoBar.addView(hint);
        root.addView(infoBar, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));

        clock = Ui.heading(this, "", 22);
        clock.setShadowLayer(8, 0, 0, Color.BLACK);
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        clp.setMargins(0, Ui.dp(this, 26), Ui.dp(this, 44), 0);
        root.addView(clock, clp);

        numberBox = Ui.heading(this, "", 44);
        numberBox.setPadding(Ui.dp(this, 24), Ui.dp(this, 10), Ui.dp(this, 24), Ui.dp(this, 10));
        numberBox.setBackground(Ui.rounded(this, 0xE0101018, 0, 14));
        numberBox.setVisibility(View.GONE);
        FrameLayout.LayoutParams nlp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        nlp.setMargins(Ui.dp(this, 44), Ui.dp(this, 30), 0, 0);
        root.addView(numberBox, nlp);

        error = Ui.text(this, "", 16, Color.WHITE);
        error.setGravity(Gravity.CENTER);
        error.setPadding(Ui.dp(this, 24), Ui.dp(this, 14), Ui.dp(this, 24), Ui.dp(this, 14));
        error.setBackground(Ui.rounded(this, 0xE0101018, 0, 14));
        error.setVisibility(View.GONE);
        root.addView(error, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));

        // ---- channel list (OK)
        listPanel = new FrameLayout(this);
        listPanel.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{0xF2101016, 0xE6101016, 0x00101016}));
        listPanel.setVisibility(View.GONE);
        channelList = new ListView(this);
        channelList.setDivider(null);
        channelList.setSelector(Ui.rounded(this, Ui.TEXT, 0, 10));
        channelList.setDrawSelectorOnTop(false);
        channelList.setItemsCanFocus(false);
        channelList.setPadding(Ui.dp(this, 24), Ui.dp(this, 24), Ui.dp(this, 24), Ui.dp(this, 24));
        channelList.setClipToPadding(false);
        channelList.setOnItemClickListener((p, v, pos, id) -> { hideList(); tune(pos); });
        listPanel.addView(channelList, new FrameLayout.LayoutParams(Ui.dp(this, 520), -1));
        root.addView(listPanel, new FrameLayout.LayoutParams(Ui.dp(this, 620), -1));

        setContentView(root);

        player = Players.create(this);
        view.setPlayer(player);
        player.addListener(new Player.Listener() {
            @Override public void onPlayerError(PlaybackException e) {
                GuideView.Entry cur = current();
                if (cur != null && cur.source.xtream() && !triedHls) {
                    triedHls = true;   // MPEG-TS refused: try the HLS version
                    play(Iptv.liveUrl(cur.source, cur.channel, true));
                    return;
                }
                error.setText("This channel isn't playing (" + App.message(e) + ")\nOK to try again · ↑↓ another channel");
                error.setVisibility(View.VISIBLE);
            }

            @Override public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY) error.setVisibility(View.GONE);
            }

            @Override public void onTracksChanged(Tracks tracks) {
                updateQuality();
            }
        });

        group = getIntent().getStringExtra("group");
        if (group == null) group = "all";
        String src = getIntent().getStringExtra("source"), ch = getIntent().getStringExtra("channel");
        App.async(() -> LiveActivity.entries(group), l -> {
            list = l;
            int at = 0;
            for (int i = 0; i < list.size(); i++) {
                GuideView.Entry e = list.get(i);
                if (e.source.id.equals(src) && e.channel.id.equals(ch)) { at = i; break; }
            }
            if (list.isEmpty()) { finish(); return; }
            channelList.setAdapter(adapter);
            tune(at);
        }, e -> finish());
    }

    private TextView badge(String text, int color) {
        TextView t = Ui.text(this, text, 13, Color.WHITE);
        t.setPadding(Ui.dp(this, 9), Ui.dp(this, 3), Ui.dp(this, 9), Ui.dp(this, 3));
        t.setBackground(Ui.rounded(this, color, 0, 6));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.setMargins(Ui.dp(this, 8), 0, 0, 0);
        t.setLayoutParams(lp);
        t.setVisibility(View.GONE);
        return t;
    }

    private GuideView.Entry current() {
        return index >= 0 && index < list.size() ? list.get(index) : null;
    }

    // ------------------------------------------------------------ tuning

    private void tune(int i) {
        if (list.isEmpty()) return;
        i = (i % list.size() + list.size()) % list.size();
        if (i != index) previous = index;
        index = i;
        GuideView.Entry e = list.get(i);
        Store.put("live_last", e.source.id + "|" + e.channel.id);
        LiveActivity.addRecent(e);
        triedHls = false;
        error.setVisibility(View.GONE);
        play(Iptv.liveUrl(e.source, e.channel, false));
        showInfo();
    }

    private void play(String url) {
        player.setMediaItem(MediaItem.fromUri(Uri.parse(url)));
        player.prepare();
        player.play();
    }

    private final Runnable hideInfo = () -> infoBar.animate().alpha(0f).setDuration(250).start();

    private void showInfo() {
        GuideView.Entry e = current();
        if (e == null) return;
        logo.setImageDrawable(null);
        ImageLoader.load(logo, e.channel.logo, Ui.dp(this, 140));
        chanText.setText((e.channel.num.isEmpty() ? "" : e.channel.num + "   ") + e.channel.name);
        long now = System.currentTimeMillis();
        List<Iptv.Program> progs = Iptv.programs(e.source, e.channel);
        Iptv.Program cur = GuideView.at(progs, now), next = null;
        if (progs.isEmpty()) {
            List<Iptv.Program> nn = new ArrayList<>();
            App.async(() -> Iptv.nowNext(e.source, e.channel), got -> {
                if (current() == e && !got.isEmpty()) fillProgrammes(got.get(0), got.size() > 1 ? got.get(1) : null);
            }, null);
        } else {
            for (Iptv.Program p : progs) if (p.start >= (cur != null ? cur.end : now)) { next = p; break; }
        }
        fillProgrammes(cur, next);
        rec.setVisibility(Dvr.recordingNow(e.source, e.channel) ? View.VISIBLE : View.GONE);
        clock.setText(tf.format(new Date()));
        clock.setVisibility(View.VISIBLE);
        infoBar.animate().cancel();
        infoBar.setAlpha(1f);
        App.MAIN.removeCallbacks(hideInfo);
        App.MAIN.postDelayed(hideInfo, 5000);
        App.MAIN.removeCallbacks(hideClock);
        App.MAIN.postDelayed(hideClock, 5000);
    }

    private final Runnable hideClock = () -> clock.setVisibility(View.GONE);

    private void fillProgrammes(Iptv.Program cur, Iptv.Program next) {
        long now = System.currentTimeMillis();
        if (cur == null) {
            nowTitle.setText(current() != null ? current().channel.name : "");
            nowTime.setText("No guide information");
            progress.setVisibility(View.INVISIBLE);
        } else {
            nowTitle.setText(cur.title);
            nowTime.setText(tf.format(new Date(cur.start)) + " – " + tf.format(new Date(cur.end)));
            progress.setVisibility(View.VISIBLE);
            progress.setProgress((int) (1000 * (now - cur.start) / Math.max(1, cur.end - cur.start)));
        }
        nextText.setText(next == null ? "" : "Next  " + tf.format(new Date(next.start)) + "  " + next.title);
    }

    private void updateQuality() {
        Format f = player.getVideoFormat();
        if (f == null || f.height <= 0) { quality.setVisibility(View.GONE); return; }
        String q = f.height >= 2000 ? "4K" : f.height >= 1000 ? "FHD" : f.height >= 700 ? "HD" : "SD";
        if (f.frameRate > 40) q += " · " + Math.round(f.frameRate) + "fps";
        quality.setText(q);
        quality.setVisibility(View.VISIBLE);
    }

    // ------------------------------------------------------------ channel list

    private void showList() {
        listPanel.setVisibility(View.VISIBLE);
        adapter.notifyDataSetChanged();
        channelList.setSelection(Math.max(0, index - 3));
        channelList.requestFocus();
        channelList.post(() -> { channelList.setSelection(index); channelList.requestFocus(); });
        infoBar.setAlpha(0f);
    }

    private void hideList() {
        listPanel.setVisibility(View.GONE);
        view.requestFocus();
    }

    private boolean listOpen() {
        return listPanel.getVisibility() == View.VISIBLE;
    }

    private final BaseAdapter adapter = new BaseAdapter() {
        @Override public int getCount() { return list.size(); }
        @Override public Object getItem(int p) { return list.get(p); }
        @Override public long getItemId(int p) { return p; }

        @Override
        public View getView(int pos, View convert, ViewGroup parent) {
            LinearLayout row;
            if (convert instanceof LinearLayout) {
                row = (LinearLayout) convert;
            } else {
                row = Ui.horizontal(LivePlayerActivity.this);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(Ui.dp(LivePlayerActivity.this, 12), Ui.dp(LivePlayerActivity.this, 8), Ui.dp(LivePlayerActivity.this, 12), Ui.dp(LivePlayerActivity.this, 8));
                TextView n = Ui.text(LivePlayerActivity.this, "", 14, Ui.MUTED);
                row.addView(n, new LinearLayout.LayoutParams(Ui.dp(LivePlayerActivity.this, 44), -2));
                ImageView lg = new ImageView(LivePlayerActivity.this);
                lg.setScaleType(ImageView.ScaleType.FIT_CENTER);
                row.addView(lg, new LinearLayout.LayoutParams(Ui.dp(LivePlayerActivity.this, 56), Ui.dp(LivePlayerActivity.this, 36)));
                LinearLayout txt = Ui.vertical(LivePlayerActivity.this);
                txt.setPadding(Ui.dp(LivePlayerActivity.this, 12), 0, 0, 0);
                TextView name = Ui.single(Ui.text(LivePlayerActivity.this, "", 16, Ui.TEXT));
                TextView prog = Ui.single(Ui.text(LivePlayerActivity.this, "", 13, Ui.MUTED));
                txt.addView(name);
                txt.addView(prog);
                row.addView(txt, new LinearLayout.LayoutParams(0, -2, 1f));
            }
            GuideView.Entry e = list.get(pos);
            boolean selected = pos == channelList.getSelectedItemPosition();
            TextView n = (TextView) row.getChildAt(0);
            ImageView lg = (ImageView) row.getChildAt(1);
            LinearLayout txt = (LinearLayout) row.getChildAt(2);
            TextView name = (TextView) txt.getChildAt(0), prog = (TextView) txt.getChildAt(1);
            n.setText(e.channel.num);
            ImageLoader.load(lg, e.channel.logo, Ui.dp(LivePlayerActivity.this, 80));
            name.setText((pos == index ? "▶  " : "") + e.channel.name);
            Iptv.Program p = GuideView.at(Iptv.programs(e.source, e.channel), System.currentTimeMillis());
            prog.setText(p == null ? "" : p.title + "  ·  " + tf.format(new Date(p.start)) + "–" + tf.format(new Date(p.end)));
            int fg = selected ? Ui.BG : Ui.TEXT;
            name.setTextColor(fg);
            n.setTextColor(selected ? Ui.BG : Ui.MUTED);
            prog.setTextColor(selected ? Ui.alpha(Ui.BG, 0.7f) : Ui.MUTED);
            return row;
        }
    };

    // ------------------------------------------------------------ options

    private void options() {
        GuideView.Entry e = current();
        if (e == null) return;
        boolean recording = Dvr.recordingNow(e.source, e.channel);
        boolean fav = LiveActivity.favorites().contains(e.source.id + "|" + e.channel.id);
        String[] items = {
                recording ? "■ Stop recording" : "● Record what's on now",
                "Audio",
                "Subtitles",
                "Picture size",
                fav ? "Remove from Favorites" : "Add to Favorites",
                "TV guide",
                "Recordings",
                "Multiview (watch up to 4 channels)",
        };
        new AlertDialog.Builder(this).setTitle(e.channel.name).setItems(items, (d, w) -> {
            switch (w) {
                case 0:
                    if (recording) {
                        for (org.json.JSONObject r : Dvr.all()) if (Dvr.RECORDING.equals(r.optString("status"))
                                && r.optString("channel").equals(e.channel.id)) Dvr.cancel(this, r.optString("id"));
                        Toast.makeText(this, "Recording stopped", Toast.LENGTH_SHORT).show();
                    } else {
                        org.json.JSONObject r = Dvr.recordNow(this, e.source, e.channel);
                        Toast.makeText(this, "Recording " + r.optString("title") + " · " + Dvr.targetLabel(this), Toast.LENGTH_LONG).show();
                    }
                    App.MAIN.postDelayed(this::showInfo, 800);
                    break;
                case 1: tracks(C.TRACK_TYPE_AUDIO, "Audio"); break;
                case 2: tracks(C.TRACK_TYPE_TEXT, "Subtitles"); break;
                case 3: zoom(); break;
                case 4:
                    Toast.makeText(this, LiveActivity.toggleFavorite(e) ? "Added to Favorites" : "Removed from Favorites", Toast.LENGTH_SHORT).show();
                    break;
                case 5: finish(); break;
                case 6: startActivity(new android.content.Intent(this, RecordingsActivity.class)); break;
                default:
                    startActivity(new android.content.Intent(this, MultiviewActivity.class).putExtra("group", group)
                            .putExtra("source", e.source.id).putExtra("channel", e.channel.id));
                    finish();
            }
        }).show();
    }

    private void tracks(int type, String title) {
        List<String> names = new ArrayList<>();
        List<Tracks.Group> groups = new ArrayList<>();
        List<Integer> idx = new ArrayList<>();
        if (type == C.TRACK_TYPE_TEXT) { names.add("Off"); groups.add(null); idx.add(-1); }
        for (Tracks.Group g : player.getCurrentTracks().getGroups()) {
            if (g.getType() != type) continue;
            for (int i = 0; i < g.length; i++) {
                if (!g.isTrackSupported(i)) continue;
                Format f = g.getTrackFormat(i);
                String n = f.label != null ? f.label : f.language != null ? new java.util.Locale(f.language).getDisplayLanguage() : title + " " + (names.size() + 1);
                if (type == C.TRACK_TYPE_AUDIO && f.channelCount > 0) n += f.channelCount >= 6 ? "  ·  5.1" : f.channelCount == 2 ? "  ·  Stereo" : "";
                names.add((g.isTrackSelected(i) ? "✓ " : "") + n);
                groups.add(g);
                idx.add(i);
            }
        }
        if (names.isEmpty() || (type == C.TRACK_TYPE_TEXT && names.size() == 1)) {
            Toast.makeText(this, "No other " + title.toLowerCase() + " on this channel", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this).setTitle(title).setItems(names.toArray(new String[0]), (d, w) -> {
            if (groups.get(w) == null) {
                player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon().setTrackTypeDisabled(type, true).build());
            } else {
                player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                        .setTrackTypeDisabled(type, false)
                        .setOverrideForType(new TrackSelectionOverride(groups.get(w).getMediaTrackGroup(), idx.get(w))).build());
            }
        }).show();
    }

    private void zoom() {
        String[] modes = {"Fit", "Fill (stretch)", "Zoom (crop)"};
        int[] values = {AspectRatioFrameLayout.RESIZE_MODE_FIT, AspectRatioFrameLayout.RESIZE_MODE_FILL, AspectRatioFrameLayout.RESIZE_MODE_ZOOM};
        new AlertDialog.Builder(this).setTitle("Picture size").setItems(modes, (d, w) -> {
            view.setResizeMode(values[w]);
            Store.put("live_zoom", String.valueOf(w));
        }).show();
    }

    // ------------------------------------------------------------ keys

    private final Runnable jump = () -> {
        String n = typed.toString();
        typed.setLength(0);
        numberBox.setVisibility(View.GONE);
        for (int i = 0; i < list.size(); i++) if (list.get(i).channel.num.equals(n)) { tune(i); return; }
        // Not in this group: look through every channel.
        App.async(() -> LiveActivity.entries("all"), all -> {
            for (int i = 0; i < all.size(); i++) {
                if (all.get(i).channel.num.equals(n)) {
                    list = all;
                    group = "all";
                    adapter.notifyDataSetChanged();
                    index = -1;
                    tune(i);
                    return;
                }
            }
            Toast.makeText(this, "No channel " + n, Toast.LENGTH_SHORT).show();
        }, null);
    };

    private boolean longOk;

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        if (listOpen()) {
            if (e.getAction() == KeyEvent.ACTION_DOWN && (k == KeyEvent.KEYCODE_BACK || k == KeyEvent.KEYCODE_DPAD_RIGHT)) {
                hideList();
                return true;
            }
            if (e.getAction() == KeyEvent.ACTION_DOWN && (k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_DPAD_DOWN)) {
                boolean r = super.dispatchKeyEvent(e);
                channelList.post(adapter::notifyDataSetChanged);
                return r;
            }
            return super.dispatchKeyEvent(e);
        }
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            switch (k) {
                case KeyEvent.KEYCODE_DPAD_UP:
                case KeyEvent.KEYCODE_CHANNEL_UP:
                    tune(index - 1);
                    return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                case KeyEvent.KEYCODE_CHANNEL_DOWN:
                    tune(index + 1);
                    return true;
                case KeyEvent.KEYCODE_LAST_CHANNEL:
                    if (previous >= 0) tune(previous);
                    return true;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                    if (e.getRepeatCount() == 0) { e.startTracking(); longOk = false; }
                    else if (e.getRepeatCount() >= 8 && !longOk) { longOk = true; options(); }
                    return true;
                case KeyEvent.KEYCODE_MENU:
                case KeyEvent.KEYCODE_INFO:
                    if (k == KeyEvent.KEYCODE_INFO) showInfo(); else options();
                    return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    showInfo();
                    return true;
                case KeyEvent.KEYCODE_MEDIA_RECORD:
                case KeyEvent.KEYCODE_PROG_RED: {
                    GuideView.Entry c = current();
                    if (c != null && !Dvr.recordingNow(c.source, c.channel)) {
                        org.json.JSONObject r = Dvr.recordNow(this, c.source, c.channel);
                        Toast.makeText(this, "Recording " + r.optString("title"), Toast.LENGTH_LONG).show();
                        App.MAIN.postDelayed(this::showInfo, 800);
                    }
                    return true;
                }
                default:
                    if (k >= KeyEvent.KEYCODE_0 && k <= KeyEvent.KEYCODE_9) {
                        typed.append((char) ('0' + k - KeyEvent.KEYCODE_0));
                        numberBox.setText(typed);
                        numberBox.setVisibility(View.VISIBLE);
                        App.MAIN.removeCallbacks(jump);
                        App.MAIN.postDelayed(jump, 1500);
                        return true;
                    }
            }
        } else if (e.getAction() == KeyEvent.ACTION_UP && (k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER)) {
            if (!longOk) {
                if (error.getVisibility() == View.VISIBLE) tune(index);
                else showList();
            }
            longOk = false;
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    @Override
    protected void onResume() {
        super.onResume();
        try {
            int z = Integer.parseInt(Store.get("live_zoom"));
            view.setResizeMode(z == 1 ? AspectRatioFrameLayout.RESIZE_MODE_FILL : z == 2 ? AspectRatioFrameLayout.RESIZE_MODE_ZOOM : AspectRatioFrameLayout.RESIZE_MODE_FIT);
        } catch (Exception ignored) { }
        if (player != null && current() != null && !player.isPlaying()) tune(index);
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (player != null) player.stop();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        App.MAIN.removeCallbacks(hideInfo);
        App.MAIN.removeCallbacks(hideClock);
        if (player != null) player.release();
    }
}
