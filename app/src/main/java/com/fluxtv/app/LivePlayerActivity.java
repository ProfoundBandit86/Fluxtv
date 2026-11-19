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
import android.widget.Button;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Full-screen Live TV in the style of TiviMate / Sparkle:
 * CH+/− change channel (the arrows only show what's on) · OK shows the buttons: start over, rewind, pause,
 * skip ahead, back to live, guide, channels, categories, catch-up · number keys tune · MENU / hold OK for options
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
    private LinearLayout buttonsRow, sideList;
    private FrameLayout sidePanel;
    private TextView liveBadge, hint;
    private Button startBtn, rewBtn, playBtn, fwdBtn, liveBtn;
    /** While replaying (paused, rewound, started over): the clock time at the start of the replay stream; -1 = live. */
    private long shiftStart = -1;
    /** When live TV was paused on a channel without replay (0 = not paused). */
    private long pausedAt;
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

        // ---- info / controls panel (bottom, dark glass). OK opens it with buttons; a channel change shows it briefly.
        infoBar = Ui.vertical(this);
        infoBar.setBackground(Ui.rounded(this, Ui.alpha(0xFF06080C, 0.8f), 0, 22));
        infoBar.setPadding(Ui.dp(this, 18), Ui.dp(this, 16), Ui.dp(this, 18), Ui.dp(this, 12));
        LinearLayout r1 = Ui.horizontal(this);
        r1.setGravity(Gravity.CENTER_VERTICAL);
        logo = new ImageView(this);
        logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        r1.addView(logo, new LinearLayout.LayoutParams(Ui.dp(this, 76), Ui.dp(this, 46)));
        LinearLayout names = Ui.vertical(this);
        names.setPadding(Ui.dp(this, 14), 0, 0, 0);
        chanText = Ui.single(Ui.text(this, "", 13, Ui.MUTED));
        names.addView(chanText);
        nowTitle = Ui.single(Ui.heading(this, "", 22));
        names.addView(nowTitle);
        r1.addView(names, new LinearLayout.LayoutParams(0, -2, 1f));
        liveBadge = badge("LIVE", 0xFFE5484D);
        liveBadge.setVisibility(View.VISIBLE);
        r1.addView(liveBadge);
        rec = badge("● REC", 0xFFE53935);
        r1.addView(rec);
        quality = badge("", Ui.alpha(Color.WHITE, 0.18f));
        r1.addView(quality);
        infoBar.addView(r1);
        LinearLayout r2 = Ui.horizontal(this);
        r2.setGravity(Gravity.CENTER_VERTICAL);
        r2.setPadding(0, Ui.dp(this, 12), 0, 0);
        nowTime = Ui.text(this, "", 12, Ui.MUTED);
        r2.addView(nowTime);
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(1000);
        progress.setProgressTintList(android.content.res.ColorStateList.valueOf(Ui.ACCENT));
        progress.setSecondaryProgressTintList(android.content.res.ColorStateList.valueOf(Ui.alpha(Color.WHITE, 0.4f)));
        progress.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(Ui.alpha(Color.WHITE, 0.18f)));
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(0, Ui.dp(this, 4), 1f);
        plp.setMargins(Ui.dp(this, 12), 0, Ui.dp(this, 12), 0);
        r2.addView(progress, plp);
        nextText = Ui.single(Ui.text(this, "", 12, Ui.MUTED));
        r2.addView(nextText, new LinearLayout.LayoutParams(Ui.dp(this, 300), -2));
        infoBar.addView(r2);

        buttonsRow = Ui.horizontal(this);
        buttonsRow.setGravity(Gravity.CENTER_VERTICAL);
        buttonsRow.setPadding(0, Ui.dp(this, 14), 0, 0);
        buttonsRow.setClipChildren(false);
        LinearLayout left = Ui.horizontal(this);
        left.setGravity(Gravity.CENTER_VERTICAL);
        startBtn = pillButton(R.drawable.ic_start_over, "Start over", v -> startOver());
        left.addView(startBtn);
        rewBtn = roundButton(R.drawable.ic_rew, false, v -> jumpBy(-30_000));
        rewBtn.setContentDescription("Rewind 30 seconds");
        left.addView(rewBtn);
        playBtn = roundButton(R.drawable.ic_pause, true, v -> togglePause());
        playBtn.setContentDescription("Pause or play");
        left.addView(playBtn);
        fwdBtn = roundButton(R.drawable.ic_ffwd, false, v -> jumpBy(30_000));
        fwdBtn.setContentDescription("Skip ahead 30 seconds");
        left.addView(fwdBtn);
        liveBtn = pillButton(0, "●  Back to live", v -> goLive());
        liveBtn.setTextColor(Ui.onFocusDark(0xFFFF6B70));
        left.addView(liveBtn);
        buttonsRow.addView(left);
        buttonsRow.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        buttonsRow.addView(pillButton(R.drawable.ic_guide, "Guide", v -> openGuide()));
        buttonsRow.addView(pillButton(R.drawable.ic_list, "Channels", v -> { closeControls(); showList(); }));
        buttonsRow.addView(pillButton(R.drawable.ic_apps, "Categories", v -> showCategories()));
        buttonsRow.addView(pillButton(R.drawable.ic_catchup, "Catch-up", v -> showCatchup()));
        Button more = roundButton(R.drawable.ic_more, false, v -> options());
        more.setContentDescription("More: record, audio, subtitles");
        buttonsRow.addView(more);
        buttonsRow.setVisibility(View.GONE);
        infoBar.addView(buttonsRow);

        hint = Ui.text(this, "CH+ / CH−  change channel   ·   OK  buttons   ·   type a number to jump to a channel", 11, Ui.DIM);
        hint.setPadding(0, Ui.dp(this, 10), 0, 0);
        infoBar.addView(hint);
        FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        ilp.setMargins(Ui.dp(this, 24), 0, Ui.dp(this, 24), Ui.dp(this, 20));
        root.addView(infoBar, ilp);

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
        channelList.setSelector(Ui.rounded(this, Ui.FOCUS, 0, 10));
        channelList.setDrawSelectorOnTop(false);
        channelList.setItemsCanFocus(false);
        channelList.setPadding(Ui.dp(this, 24), Ui.dp(this, 24), Ui.dp(this, 24), Ui.dp(this, 24));
        channelList.setClipToPadding(false);
        channelList.setOnItemClickListener((p, v, pos, id) -> { savedList = null; listPanel.setVisibility(View.GONE); view.requestFocus(); tune(pos); });
        listPanel.addView(channelList, new FrameLayout.LayoutParams(Ui.dp(this, 520), -1));
        root.addView(listPanel, new FrameLayout.LayoutParams(Ui.dp(this, 620), -1));

        // ---- side list (Categories, Catch-up)
        sidePanel = new FrameLayout(this);
        sidePanel.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{0xF2101016, 0xE6101016, 0x00101016}));
        sidePanel.setVisibility(View.GONE);
        android.widget.ScrollView sideScroll = new android.widget.ScrollView(this);
        sideScroll.setVerticalScrollBarEnabled(false);
        sideScroll.setClipToPadding(false);
        sideScroll.setPadding(Ui.dp(this, 24), Ui.dp(this, 24), Ui.dp(this, 24), Ui.dp(this, 24));
        sideList = Ui.vertical(this);
        sideScroll.addView(sideList);
        sidePanel.addView(sideScroll, new FrameLayout.LayoutParams(Ui.dp(this, 460), -1));
        root.addView(sidePanel, new FrameLayout.LayoutParams(Ui.dp(this, 560), -1));

        setContentView(root);
        App.MAIN.postDelayed(tick, 1000);

        player = Players.create(this);
        view.setPlayer(player);
        player.addListener(new Player.Listener() {
            @Override public void onPlayerError(PlaybackException e) {
                GuideView.Entry cur = current();
                if (shiftStart >= 0) {   // the replay didn't play
                    shiftStart = -1;
                    Toast.makeText(LivePlayerActivity.this, "The provider didn't send a replay (" + App.message(e) + "). Back to live.", Toast.LENGTH_LONG).show();
                    if (cur != null) tune(index);
                    return;
                }
                if (cur != null && cur.source.xtream() && !triedHls) {
                    triedHls = true;   // MPEG-TS refused: try the HLS version
                    play(Iptv.liveUrl(cur.source, cur.channel, true));
                    return;
                }
                error.setText("This channel isn't playing (" + App.message(e) + ")\nOK to try again · CH+ / CH− another channel");
                error.setVisibility(View.VISIBLE);
            }

            @Override public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY) error.setVisibility(View.GONE);
                // A replay reached the end of what it covered: carry on from there (or rejoin live).
                if (state == Player.STATE_ENDED && shiftStart >= 0) replayFrom(watchingAt());
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
        shiftStart = -1;
        pausedAt = 0;
        replayToken++;
        fetched = new ArrayList<>();
        error.setVisibility(View.GONE);
        play(Iptv.liveUrl(e.source, e.channel, false));
        showInfo();
    }

    private void play(String url) {
        player.setMediaItem(MediaItem.fromUri(Uri.parse(url)));
        player.prepare();
        player.play();
    }

    private final Runnable hideInfo = () -> infoBar.animate().alpha(0f).setDuration(250).withEndAction(() -> {
        if (buttonsRow.getVisibility() == View.VISIBLE && !sideOpen() && !listOpen()) closeControls();
    }).start();

    /** Keeps the time line and the "behind live" badge moving while the panel shows. */
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (infoBar.getAlpha() > 0f) refreshTimeline();
            App.MAIN.postDelayed(this, 1000);
        }
    };

    private void showInfo() {
        GuideView.Entry e = current();
        if (e == null) return;
        logo.setImageDrawable(null);
        ImageLoader.load(logo, e.channel.logo, Ui.dp(this, 140));
        chanText.setText((e.channel.num.isEmpty() ? "" : e.channel.num + "   ") + e.channel.name);
        List<Iptv.Program> progs = Iptv.programs(e.source, e.channel);
        if (progs.isEmpty()) {
            App.async(() -> Iptv.nowNext(e.source, e.channel), got -> {
                if (current() == e && !got.isEmpty()) { fetched = got; refreshTimeline(); }
            }, null);
        }
        refreshTimeline();
        rec.setVisibility(Dvr.recordingNow(e.source, e.channel) ? View.VISIBLE : View.GONE);
        clock.setText(tf.format(new Date()));
        clock.setVisibility(View.VISIBLE);
        infoBar.animate().cancel();
        infoBar.setAlpha(1f);
        bumpHide();
        App.MAIN.removeCallbacks(hideClock);
        App.MAIN.postDelayed(hideClock, 5000);
    }

    /** Programmes fetched one by one (channels the full guide doesn't cover). */
    private List<Iptv.Program> fetched = new ArrayList<>();

    private void bumpHide() {
        App.MAIN.removeCallbacks(hideInfo);
        App.MAIN.postDelayed(hideInfo, controlsOpen() ? 8000 : 5000);
    }

    private final Runnable hideClock = () -> clock.setVisibility(View.GONE);

    /** The clock time of the picture on screen (live: now). */
    private long watchingAt() {
        if (shiftStart >= 0) return shiftStart + Math.max(0, player.getCurrentPosition());
        if (pausedAt > 0) return pausedAt;
        return System.currentTimeMillis();
    }

    private boolean behindLive() {
        return shiftStart >= 0 || pausedAt > 0;
    }

    private List<Iptv.Program> programmes() {
        GuideView.Entry e = current();
        if (e == null) return new ArrayList<>();
        List<Iptv.Program> p = Iptv.programs(e.source, e.channel);
        return p.isEmpty() ? fetched : p;
    }

    private void refreshTimeline() {
        GuideView.Entry e = current();
        if (e == null) return;
        long now = System.currentTimeMillis(), at = watchingAt();
        List<Iptv.Program> progs = programmes();
        Iptv.Program cur = GuideView.at(progs, at), next = null;
        for (Iptv.Program p : progs) if (p.start >= (cur != null ? cur.end : at)) { next = p; break; }
        if (cur == null) {
            nowTitle.setText(e.channel.name);
            nowTime.setText(behindLive() ? "" : "No guide information");
            progress.setVisibility(View.INVISIBLE);
        } else {
            nowTitle.setText(cur.title);
            nowTime.setText(tf.format(new Date(cur.start)) + " – " + tf.format(new Date(cur.end)));
            progress.setVisibility(View.VISIBLE);
            long len = Math.max(1, cur.end - cur.start);
            progress.setProgress((int) Math.max(0, Math.min(1000, 1000 * (at - cur.start) / len)));
            progress.setSecondaryProgress((int) Math.max(0, Math.min(1000, 1000 * (now - cur.start) / len)));
        }
        nextText.setText(next == null ? "" : "Next  " + tf.format(new Date(next.start)) + "  " + next.title);
        long behind = Math.max(0, now - at);
        if (behindLive() && behind >= 1000) {
            liveBadge.setText("−" + Ui.time(behind));
            liveBadge.setBackground(Ui.rounded(this, Ui.alpha(Color.WHITE, 0.18f), 0, 6));
        } else {
            liveBadge.setText("LIVE");
            liveBadge.setBackground(Ui.rounded(this, 0xFFE5484D, 0, 6));
        }
        boolean replay = canReplay();
        dim(startBtn, replay);
        dim(rewBtn, replay);
        dim(fwdBtn, shiftStart >= 0);
        liveBtn.setVisibility(behindLive() ? View.VISIBLE : View.GONE);
        boolean playing = player.getPlayWhenReady();
        playBtn.setCompoundDrawables(icon(playing ? R.drawable.ic_pause : R.drawable.ic_play, 26), null, null, null);
    }

    private static void dim(View v, boolean on) {
        v.setAlpha(on ? 1f : 0.4f);
    }

    private void updateQuality() {
        Format f = player.getVideoFormat();
        if (f == null || f.height <= 0) { quality.setVisibility(View.GONE); return; }
        String q = f.height >= 2000 ? "4K" : f.height >= 1000 ? "FHD" : f.height >= 700 ? "HD" : "SD";
        if (f.frameRate > 40) q += " · " + Math.round(f.frameRate) + "fps";
        quality.setText(q);
        quality.setVisibility(View.VISIBLE);
    }

    // ------------------------------------------------------------ buttons (OK)

    private android.graphics.drawable.Drawable icon(int res, int dp) {
        android.graphics.drawable.Drawable d = getResources().getDrawable(res, getTheme()).mutate();
        int is = Ui.dp(this, dp);
        d.setBounds(0, 0, is, is);
        return d;
    }

    private android.graphics.drawable.Drawable pillBg(int normal) {
        android.graphics.drawable.StateListDrawable st = new android.graphics.drawable.StateListDrawable();
        st.addState(new int[]{android.R.attr.state_focused}, Ui.rounded(this, Ui.FOCUS, 0, Ui.BTN_RADIUS));
        st.addState(new int[]{}, Ui.rounded(this, normal, 0, Ui.BTN_RADIUS));
        return st;
    }

    private Button pillButton(int iconRes, String label, View.OnClickListener click) {
        Button b = new Button(this);
        b.setText(label);
        Ui.caps(b, 0.1f);
        b.setTextSize(12);
        b.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        b.setTextColor(Ui.onFocusDark(Ui.TEXT));
        b.setBackground(pillBg(Ui.alpha(Color.WHITE, 0.1f)));
        b.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 14), 0);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        if (iconRes != 0) {
            b.setCompoundDrawables(icon(iconRes, 16), null, null, null);
            b.setCompoundDrawablePadding(Ui.dp(this, 6));
            b.setCompoundDrawableTintList(Ui.onFocusDark(Ui.TEXT));
        }
        b.setOnClickListener(click);
        b.setOnFocusChangeListener((v, has) -> { if (has) bumpHide(); });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, Ui.dp(this, 40));
        lp.setMargins(Ui.dp(this, 3), 0, Ui.dp(this, 3), 0);
        lp.gravity = Gravity.CENTER_VERTICAL;
        b.setLayoutParams(lp);
        return b;
    }

    private Button roundButton(int iconRes, boolean main, View.OnClickListener click) {
        Button b = new Button(this);
        int size = Ui.dp(this, main ? 50 : 40);
        int is = main ? 22 : 18;
        b.setCompoundDrawables(icon(iconRes, is), null, null, null);
        b.setPadding((size - Ui.dp(this, is)) / 2, 0, 0, 0);
        b.setCompoundDrawableTintList(main ? Ui.onFocusDark(Ui.BG) : Ui.onFocusDark(Ui.TEXT));
        android.graphics.drawable.StateListDrawable bg = new android.graphics.drawable.StateListDrawable();
        GradientDrawable focused = new GradientDrawable();
        focused.setShape(GradientDrawable.OVAL);
        focused.setColor(Ui.FOCUS);
        GradientDrawable normal = new GradientDrawable();
        normal.setShape(GradientDrawable.OVAL);
        normal.setColor(main ? Ui.TEXT : Ui.alpha(Color.WHITE, 0.1f));
        bg.addState(new int[]{android.R.attr.state_focused}, focused);
        bg.addState(new int[]{}, normal);
        b.setBackground(bg);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setOnClickListener(click);
        b.setOnFocusChangeListener((v, has) -> { if (has) bumpHide(); });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
        lp.setMargins(Ui.dp(this, 3), 0, Ui.dp(this, 3), 0);
        lp.gravity = Gravity.CENTER_VERTICAL;
        b.setLayoutParams(lp);
        return b;
    }

    private boolean controlsOpen() {
        return buttonsRow.getVisibility() == View.VISIBLE;
    }

    private void openControls() {
        buttonsRow.setVisibility(View.VISIBLE);
        hint.setText("CH+ / CH−  change channel   ·   ← →  move between buttons   ·   BACK  hide");
        showInfo();
        playBtn.requestFocus();
    }

    private void closeControls() {
        buttonsRow.setVisibility(View.GONE);
        hint.setText("CH+ / CH−  change channel   ·   OK  buttons   ·   type a number to jump to a channel");
        view.requestFocus();
        App.MAIN.removeCallbacks(hideInfo);
        App.MAIN.postDelayed(hideInfo, 2500);
    }

    // ------------------------------------------------------------ pause, rewind, start over (catch-up)

    /** Replay needs the provider's catch-up (archive) for this channel. */
    private boolean canReplay() {
        GuideView.Entry e = current();
        return e != null && e.channel.archiveDays > 0;
    }

    private void noReplay() {
        Toast.makeText(this, "This channel doesn't offer replay, so it can't go back", Toast.LENGTH_SHORT).show();
    }

    private void togglePause() {
        if (player.getPlayWhenReady()) {
            player.pause();
            if (shiftStart < 0) pausedAt = System.currentTimeMillis();
        } else if (pausedAt > 0) {
            long at = pausedAt, gone = System.currentTimeMillis() - at;
            pausedAt = 0;
            if (gone > 20_000 && canReplay()) replayFrom(at);
            else if (gone > 45_000) tune(index);      // the live stream has moved on: rejoin live
            else player.play();
        } else {
            player.play();
        }
        refreshTimeline();
        bumpHide();
    }

    private void jumpBy(long ms) {
        if (shiftStart >= 0) {
            long target = watchingAt() + ms;
            long dur = player.getDuration();
            if (target >= System.currentTimeMillis() - 15_000) { goLive(); return; }
            if (target < shiftStart || (dur != C.TIME_UNSET && target > shiftStart + dur)) { replayFrom(target); return; }
            player.seekTo(target - shiftStart);
            refreshTimeline();
        } else if (ms > 0) {
            return;   // already live
        } else if (!canReplay()) {
            noReplay();
        } else {
            replayFrom((pausedAt > 0 ? pausedAt : System.currentTimeMillis()) + ms);
        }
        bumpHide();
    }

    private void startOver() {
        android.util.Log.i("FluxLive", "start over (replay " + canReplay() + ", " + programmes().size() + " programmes)");
        if (!canReplay()) { noReplay(); return; }
        Iptv.Program p = GuideView.at(programmes(), watchingAt());
        if (p == null) { Toast.makeText(this, "No guide information for this show, so it can't tell where it started", Toast.LENGTH_SHORT).show(); return; }
        replayFrom(p.start);
        bumpHide();
    }

    private void goLive() {
        pausedAt = 0;
        shiftStart = -1;
        tune(index);
        if (controlsOpen()) playBtn.requestFocus();
    }

    private int replayToken;
    private List<GuideView.Entry> savedList;
    private int savedIndex;
    private String savedGroup;

    /** Plays the channel's catch-up from a clock time (rewind, start over, resume after a long pause). */
    private void replayFrom(long at) {
        GuideView.Entry e = current();
        if (e == null) return;
        long now = System.currentTimeMillis();
        at = Math.max(at, now - e.channel.archiveDays * 86_400_000L + 60_000);
        if (at >= now - 15_000) { goLive(); return; }
        Iptv.Program cur = GuideView.at(programmes(), at);
        Iptv.Program span = new Iptv.Program();
        // Start the replay at the show's start when the guide knows it (or a whole minute just before), up to now.
        span.start = cur != null && cur.start <= at && at - cur.start < 6 * 3600_000L ? cur.start : at - at % 60_000;
        span.end = now;
        span.id = cur != null && cur.start == span.start ? cur.id : "";
        span.title = cur != null ? cur.title : e.channel.name;
        long seek = at - span.start;
        int token = ++replayToken;
        android.util.Log.i("FluxLive", "replay from " + at + " (start " + span.start + ", seek " + seek + ")");
        Toast.makeText(this, "Going back…", Toast.LENGTH_SHORT).show();
        App.async(() -> Iptv.catchupUrl(e.source, e.channel, span), url -> {
            if (token != replayToken || current() != e) return;
            shiftStart = span.start;
            pausedAt = 0;
            error.setVisibility(View.GONE);
            player.setMediaItem(MediaItem.fromUri(Uri.parse(url)), seek);
            player.prepare();
            player.play();
            refreshTimeline();
        }, err -> {
            android.util.Log.w("FluxLive", "replay failed: " + App.message(err));
            Toast.makeText(this, "Replay: " + App.message(err), Toast.LENGTH_LONG).show();
        });
    }

    // ------------------------------------------------------------ guide, categories, catch-up

    private void openGuide() {
        GuideView.Entry e = current();
        android.content.Intent i = new android.content.Intent(this, LiveActivity.class).putExtra("group", group);
        if (e != null) Store.put("live_last", e.source.id + "|" + e.channel.id);
        startActivity(i.addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP | android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP));
        finish();
    }

    private boolean sideOpen() {
        return sidePanel.getVisibility() == View.VISIBLE;
    }

    private void hideSide() {
        sidePanel.setVisibility(View.GONE);
        if (controlsOpen()) { showInfo(); playBtn.requestFocus(); } else view.requestFocus();
    }

    private void openSide(String title) {
        sideList.removeAllViews();
        TextView h = Ui.heading(this, title, 20);
        h.setPadding(Ui.dp(this, 6), 0, 0, Ui.dp(this, 10));
        sideList.addView(h);
        sidePanel.setVisibility(View.VISIBLE);
        sidePanel.bringToFront();
        infoBar.setAlpha(0f);
    }

    private Button sideButton(CharSequence label, boolean selected, View.OnClickListener click) {
        Button b = Ui.listButton(this, label, click);
        b.setBackground(Ui.focusable(this, selected ? Ui.alpha(Color.WHITE, 0.12f) : Color.TRANSPARENT, Ui.FOCUS, 0));
        b.setTextColor(Ui.onFocusDark(Ui.TEXT));
        sideList.addView(b);
        return b;
    }

    private void showCategories() {
        openSide("Categories");
        TextView wait = Ui.text(this, "Loading…", 14, Ui.MUTED);
        sideList.addView(wait);
        App.async(() -> {
            Map<String, String> names = new LinkedHashMap<>();
            Map<String, Integer> counts = new LinkedHashMap<>();
            List<Iptv.Source> sources = Iptv.sources();
            for (Iptv.Source s : sources) {
                Map<String, String> cats = new LinkedHashMap<>();
                try { for (Iptv.Category c : Iptv.liveCategories(s)) cats.put(c.id, c.name); } catch (Exception ignored) { }
                for (Iptv.Channel c : Iptv.shownChannels(s)) {
                    String key = LiveActivity.groupOf(s, c), gid = s.id + "|" + key;
                    names.put(gid, (sources.size() > 1 ? s.name + " · " : "") + (cats.containsKey(key) ? cats.get(key) : key));
                    Integer n = counts.get(gid);
                    counts.put(gid, n == null ? 1 : n + 1);
                }
            }
            return new Object[]{names, counts};
        }, r -> {
            if (!sideOpen()) return;
            sideList.removeView(wait);
            @SuppressWarnings("unchecked") Map<String, String> names = (Map<String, String>) r[0];
            @SuppressWarnings("unchecked") Map<String, Integer> counts = (Map<String, Integer>) r[1];
            View focus = sideButton("★  Favorites", "favorites".equals(group), v -> pickGroup("favorites"));
            View all = sideButton("All channels", "all".equals(group), v -> pickGroup("all"));
            if ("all".equals(group)) focus = all;
            for (Map.Entry<String, String> g : names.entrySet()) {
                Integer n = counts.get(g.getKey());
                View b = sideButton(g.getValue() + (n == null ? "" : "   " + n), g.getKey().equals(group), v -> pickGroup(g.getKey()));
                if (g.getKey().equals(group)) focus = b;
            }
            View f = focus;
            f.post(f::requestFocus);
        }, err -> wait.setText("Couldn't load categories: " + App.message(err)));
    }

    /** A category was picked: its channels become the channel list (the current channel keeps playing). */
    private void pickGroup(String g) {
        App.async(() -> LiveActivity.entries(g), l -> {
            if (l.isEmpty()) { Toast.makeText(this, "No channels in this category", Toast.LENGTH_SHORT).show(); return; }
            GuideView.Entry cur = current();
            int at = -1;
            for (int i = 0; i < l.size(); i++) {
                if (cur != null && l.get(i).source.id.equals(cur.source.id) && l.get(i).channel.id.equals(cur.channel.id)) { at = i; break; }
            }
            if (at < 0) {   // the playing channel isn't in it: keep the old list until a channel is picked
                savedList = list;
                savedIndex = index;
                savedGroup = group;
            }
            group = g;
            list = l;
            index = at;
            previous = -1;
            sidePanel.setVisibility(View.GONE);
            closeControls();
            showList();
        }, null);
    }

    private void showCatchup() {
        GuideView.Entry e = current();
        if (e == null) return;
        if (!canReplay()) { noReplay(); return; }
        openSide("Catch-up · " + e.channel.name);
        long now = System.currentTimeMillis();
        List<Iptv.Program> past = new ArrayList<>();
        for (Iptv.Program p : programmes()) if (p.start < now && p.start >= now - e.channel.archiveDays * 86_400_000L) past.add(p);
        java.util.Collections.reverse(past);   // newest first
        if (past.isEmpty()) {
            sideList.addView(Ui.text(this, "The guide has nothing earlier on this channel yet.", 14, Ui.MUTED));
            Button back = sideButton("◀  Rewind 10 minutes instead", false, v -> { sidePanel.setVisibility(View.GONE); jumpBy(-600_000); });
            back.post(back::requestFocus);
            return;
        }
        java.text.SimpleDateFormat day = new java.text.SimpleDateFormat("EEE", java.util.Locale.getDefault());
        String today = day.format(new Date(now));
        View first = null;
        for (Iptv.Program p : past) {
            String d = day.format(new Date(p.start));
            android.text.SpannableStringBuilder label = new android.text.SpannableStringBuilder(p.title + "\n");
            int at = label.length();
            label.append((d.equals(today) ? "" : d + " ") + tf.format(new Date(p.start)) + " – " + tf.format(new Date(p.end))
                    + (p.end > now ? "   ·   on now, from the start" : ""));
            label.setSpan(new android.text.style.RelativeSizeSpan(0.8f), at, label.length(), 0);
            Button b = sideButton(label, false, v -> { sidePanel.setVisibility(View.GONE); closeControls(); replayFrom(p.start); });
            if (first == null) first = b;
        }
        View f = first;
        f.post(f::requestFocus);
    }

    // ------------------------------------------------------------ channel list

    private void showList() {
        listPanel.setVisibility(View.VISIBLE);
        listPanel.bringToFront();
        adapter.notifyDataSetChanged();
        channelList.setAdapter(adapter);
        channelList.setSelection(Math.max(0, index - 3));
        channelList.requestFocus();
        channelList.post(() -> { channelList.setSelection(Math.max(0, index)); channelList.requestFocus(); });
        infoBar.setAlpha(0f);
    }

    private void hideList() {
        listPanel.setVisibility(View.GONE);
        if (index < 0 && savedList != null) {   // left a category without picking a channel
            list = savedList;
            index = savedIndex;
            group = savedGroup;
        }
        savedList = null;
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
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        // These work whatever is open.
        switch (k) {
            case KeyEvent.KEYCODE_CHANNEL_UP:
            case KeyEvent.KEYCODE_CHANNEL_DOWN:
                if (down) {
                    if (listOpen()) hideList();
                    if (sideOpen()) sidePanel.setVisibility(View.GONE);
                    tune(k == KeyEvent.KEYCODE_CHANNEL_UP ? index - 1 : index + 1);
                }
                return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                if (down) {
                    boolean playing = player.getPlayWhenReady();
                    if (k == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || (k == KeyEvent.KEYCODE_MEDIA_PLAY) != playing) togglePause();
                    showInfo();
                }
                return true;
            case KeyEvent.KEYCODE_MEDIA_REWIND:
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                if (down) { jumpBy(k == KeyEvent.KEYCODE_MEDIA_REWIND ? -30_000 : 30_000); showInfo(); }
                return true;
            default:
                break;
        }
        if (listOpen()) {
            if (down && (k == KeyEvent.KEYCODE_BACK || k == KeyEvent.KEYCODE_DPAD_RIGHT)) {
                hideList();
                return true;
            }
            if (down && (k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_DPAD_DOWN)) {
                boolean r = super.dispatchKeyEvent(e);
                channelList.post(adapter::notifyDataSetChanged);
                return r;
            }
            return super.dispatchKeyEvent(e);
        }
        if (sideOpen()) {
            if (down && k == KeyEvent.KEYCODE_BACK) { hideSide(); return true; }
            return super.dispatchKeyEvent(e);
        }
        if (controlsOpen()) {
            if (k == KeyEvent.KEYCODE_BACK) { if (down) closeControls(); return true; }
            if (k == KeyEvent.KEYCODE_MENU) { if (down) options(); return true; }
            if (down) { infoBar.animate().cancel(); infoBar.setAlpha(1f); bumpHide(); }
            if (k >= KeyEvent.KEYCODE_0 && k <= KeyEvent.KEYCODE_9) closeControls();
            else return super.dispatchKeyEvent(e);
        }
        if (down) {
            switch (k) {
                case KeyEvent.KEYCODE_LAST_CHANNEL:
                    if (previous >= 0) tune(previous);
                    return true;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                    if (e.getRepeatCount() == 0) { e.startTracking(); longOk = false; }
                    else if (e.getRepeatCount() >= 8 && !longOk) { longOk = true; options(); }
                    return true;
                case KeyEvent.KEYCODE_MENU:
                    options();
                    return true;
                case KeyEvent.KEYCODE_INFO:
                // Only CH+ / CH− change channel; the arrows just show what's on.
                case KeyEvent.KEYCODE_DPAD_UP:
                case KeyEvent.KEYCODE_DPAD_DOWN:
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
                else openControls();
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
        App.MAIN.removeCallbacks(tick);
        if (player != null) player.release();
    }
}
