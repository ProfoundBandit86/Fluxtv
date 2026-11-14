package com.fluxtv.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.common.Tracks;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.CaptionStyleCompat;
import androidx.media3.ui.PlayerView;
import androidx.media3.ui.SubtitleView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Built-in player (Media3/ExoPlayer). Resumes where you left off, saves progress, scrobbles to Trakt,
 * and has an options panel: audio track, subtitles (built-in or from add-ons), subtitle size and style,
 * speed, picture size and video quality.
 */
public class PlayerActivity extends Activity {

    static final String[] SUB_SIZES = {"Small", "Normal", "Large", "Extra large"};
    static final float[] SUB_SCALE = {0.8f, 1f, 1.3f, 1.65f};
    static final String[] SUB_STYLES = {"White, outlined", "White on black", "Yellow, outlined"};
    static final String[] ZOOMS = {"Fit", "Fill (crop)", "Stretch"};
    static final float[] SPEEDS = {0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f};

    private ExoPlayer player;
    private PlayerView view;
    private PlayInfo info;
    private String url;
    private boolean reported, scrobbling;

    private FrameLayout root;
    private LinearLayout optionsBar;
    private ScrollView panel;
    private LinearLayout panelList;
    private String panelPage = null;
    private final List<MediaItem.SubtitleConfiguration> extraSubs = new ArrayList<>();
    private List<JSONObject> addonSubs = null;
    private String pendingSubLabel = null;

    private final Runnable saver = new Runnable() {
        @Override public void run() {
            saveProgress();
            App.MAIN.postDelayed(this, 10_000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        info = PlayInfo.from(getIntent());
        url = getIntent().getStringExtra("url");

        live = getIntent().getBooleanExtra("live", false);
        root = new FrameLayout(this);
        view = new PlayerView(this);
        view.setBackgroundColor(Color.BLACK);
        view.setKeepScreenOn(true);
        root.addView(view);
        if (live) {
            banner = Ui.vertical(this);
            banner.setBackground(Ui.rounded(this, Ui.alpha(Color.BLACK, 0.72f), 0, 14));
            banner.setPadding(Ui.dp(this, 20), Ui.dp(this, 14), Ui.dp(this, 22), Ui.dp(this, 14));
            bannerTitle = Ui.heading(this, "", 20);
            bannerNow = Ui.text(this, "", 14, Ui.MUTED);
            bannerNow.setPadding(0, Ui.dp(this, 6), 0, 0);
            banner.addView(bannerTitle);
            banner.addView(bannerNow);
            FrameLayout.LayoutParams bl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START);
            bl.setMargins(Ui.dp(this, 40), Ui.dp(this, 32), 0, 0);
            root.addView(banner, bl);
            liveSource = Iptv.source(getIntent().getStringExtra("live_source"));
            liveCategory = getIntent().getStringExtra("live_category");
            liveIndex = getIntent().getIntExtra("live_index", 0);
            view.setControllerShowTimeoutMs(2500);
        } else {
            view.setControllerShowTimeoutMs(5000);
        }
        buildOptionsBar();
        if (!live) buildControls();
        buildPanel();
        setContentView(root);

        // Follow http→https and cross-host redirects (common with IPTV load balancers and debrid links).
        androidx.media3.datasource.DefaultHttpDataSource.Factory http = new androidx.media3.datasource.DefaultHttpDataSource.Factory()
                .setAllowCrossProtocolRedirects(true)
                .setUserAgent("FluxTV/1.0 (Android TV)")
                .setConnectTimeoutMs(15000)
                .setReadTimeoutMs(20000);
        androidx.media3.datasource.DefaultDataSource.Factory data = new androidx.media3.datasource.DefaultDataSource.Factory(this, http);
        player = new ExoPlayer.Builder(this)
                .setMediaSourceFactory(new androidx.media3.exoplayer.source.DefaultMediaSourceFactory(this).setDataSourceFactory(data))
                .build();
        view.setPlayer(player);
        applyTrackPreferences();
        applySubtitleStyle();
        view.setResizeMode(zoomMode(intPref("player_zoom", 0)));
        view.setControllerVisibilityListener((PlayerView.ControllerVisibilityListener) visibility -> {
            boolean show = visibility == View.VISIBLE || (optionsBar.hasFocus());
            optionsBar.setVisibility(show && !panelOpen() ? View.VISIBLE : View.GONE);
        });
        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_ENDED) {
                    saveProgress();
                    reportToServer();
                    scrobble("stop");
                    reported = true;
                    finish();
                }
            }

            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                if (isPlaying && !scrobbling) {
                    scrobbling = true;
                    scrobble("start");
                } else if (!isPlaying && scrobbling && player != null && !player.getPlayWhenReady()) {
                    scrobbling = false;
                    scrobble("pause");
                }
            }

            @Override
            public void onTracksChanged(Tracks tracks) {
                selectPendingSubtitle(tracks);
                if (!live) { updateBadges(); refreshChips(); }
                if (panelOpen() && panelPage != null && !"main".equals(panelPage)) showPage(panelPage);
            }

            @Override
            public void onPlayerError(PlaybackException error) {
                // Live IPTV: if MPEG-TS doesn't play, try the HLS version once.
                if (live && !triedHls && url != null && url.endsWith(".ts")) {
                    triedHls = true;
                    url = url.substring(0, url.length() - 3) + ".m3u8";
                    playUrl(url);
                    return;
                }
                showError(error);
            }
        });

        if (live) {
            playUrl(url);
            App.async(() -> Iptv.channelsIn(liveSource, liveCategory), list -> {
                liveChannels = list;
                showBanner();
            }, null);
            view.requestFocus();
            return;
        }
        MediaItem item = new MediaItem.Builder()
                .setUri(Uri.parse(url))
                .setMediaMetadata(new MediaMetadata.Builder().setTitle(info.title()).build())
                .build();
        player.setMediaItem(item);

        JSONObject p = Store.progress(info.videoId);
        if (p != null && !Store.isFinished(p) && p.optLong("pos") > 30000) {
            player.seekTo(p.optLong("pos"));
            Toast.makeText(this, "Resuming from " + Ui.time(p.optLong("pos")), Toast.LENGTH_SHORT).show();
        }
        player.prepare();
        player.setPlayWhenReady(true);
        root.setFocusable(true);
        root.requestFocus();
        root.post(() -> showControls(playPause));
    }

    // ------------------------------------------------------------ Flux's own controls (movies & episodes)

    private FrameLayout controls;
    private View played, buffered, knob;
    private FrameLayout track;
    private TextView timeNow, timeLeft, clock;
    private LinearLayout badges;
    private android.widget.Button playPause, lastChip;
    private final java.util.Map<String, android.widget.Button> chips = new java.util.LinkedHashMap<>();

    private final Runnable autoHide = () -> {
        if (player != null && player.isPlaying() && !panelOpen()) hideControls();
    };
    private final Runnable progressTick = new Runnable() {
        @Override public void run() {
            updateProgress();
            if (controls != null && controls.getVisibility() == View.VISIBLE) App.MAIN.postDelayed(this, 500);
        }
    };

    private void buildControls() {
        view.setUseController(false);
        view.setFocusable(false);
        controls = new FrameLayout(this);
        controls.setVisibility(View.GONE);

        View topScrim = new View(this);
        topScrim.setBackground(new android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Ui.alpha(Color.BLACK, 0.85f), Color.TRANSPARENT}));
        controls.addView(topScrim, new FrameLayout.LayoutParams(-1, Ui.dp(this, 170), Gravity.TOP));
        View bottomScrim = new View(this);
        bottomScrim.setBackground(new android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{Ui.alpha(Color.BLACK, 0.95f), Ui.alpha(Color.BLACK, 0.6f), Color.TRANSPARENT}));
        controls.addView(bottomScrim, new FrameLayout.LayoutParams(-1, Ui.dp(this, 300), Gravity.BOTTOM));

        // Top: title on the left, format badges and the clock on the right.
        LinearLayout top = Ui.horizontal(this);
        top.setGravity(Gravity.TOP);
        LinearLayout titles = Ui.vertical(this);
        TextView eyebrow = Ui.text(this, info.label.isEmpty() ? "" : info.name.toUpperCase(Locale.ROOT), 13, Ui.ACCENT);
        eyebrow.setLetterSpacing(0.08f);
        titles.addView(eyebrow);
        titles.addView(Ui.single(Ui.heading(this, info.label.isEmpty() ? info.name : info.label, 26)));
        top.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        badges = Ui.horizontal(this);
        badges.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(badges);
        clock = Ui.heading(this, "", 20);
        clock.setPadding(Ui.dp(this, 14), 0, 0, 0);
        top.addView(clock);
        FrameLayout.LayoutParams tl = new FrameLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP);
        tl.setMargins(Ui.dp(this, 56), Ui.dp(this, 36), Ui.dp(this, 56), 0);
        controls.addView(top, tl);

        // Bottom: progress, times, then the buttons and options in one bar.
        LinearLayout bottom = Ui.vertical(this);
        track = new FrameLayout(this);
        track.setClipChildren(false);
        View base = new View(this);
        base.setBackground(Ui.rounded(this, Ui.alpha(Color.WHITE, 0.18f), 0, 99));
        track.addView(base, new FrameLayout.LayoutParams(-1, Ui.dp(this, 6), Gravity.CENTER_VERTICAL));
        buffered = new View(this);
        buffered.setBackground(Ui.rounded(this, Ui.alpha(Color.WHITE, 0.3f), 0, 99));
        track.addView(buffered, new FrameLayout.LayoutParams(0, Ui.dp(this, 6), Gravity.CENTER_VERTICAL));
        played = new View(this);
        played.setBackground(Ui.rounded(this, Ui.ACCENT, 0, 99));
        track.addView(played, new FrameLayout.LayoutParams(0, Ui.dp(this, 6), Gravity.CENTER_VERTICAL));
        knob = new View(this);
        knob.setBackground(Ui.rounded(this, Color.WHITE, 0, 99));
        track.addView(knob, new FrameLayout.LayoutParams(Ui.dp(this, 16), Ui.dp(this, 16), Gravity.CENTER_VERTICAL));
        bottom.addView(track, new LinearLayout.LayoutParams(-1, Ui.dp(this, 18)));
        LinearLayout times = Ui.horizontal(this);
        timeNow = Ui.heading(this, "0:00", 15);
        timeLeft = Ui.heading(this, "", 15);
        timeLeft.setGravity(Gravity.END);
        times.addView(timeNow, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        times.addView(timeLeft, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        times.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 12));
        bottom.addView(times);

        LinearLayout bar = Ui.horizontal(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBaselineAligned(false);
        playPause = roundButton(R.drawable.ic_pause, true, v -> togglePlay());
        bar.addView(playPause);
        bar.addView(roundButton(R.drawable.ic_back10, false, v -> skip(-10_000)));
        bar.addView(roundButton(R.drawable.ic_fwd30, false, v -> skip(30_000)));
        bar.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        chip("audio", "Audio");
        chip("subs", "Subtitles");
        chip("quality", "Quality");
        chip("speed", "Speed");
        chip("zoom", "Picture");
        chip("more", "More");
        for (android.widget.Button b : chips.values()) bar.addView(b);
        bottom.addView(bar);
        TextView hint = Ui.text(this, "← → skip 10 s   ·   OK pause   ·   Back hides controls", 12, Ui.DIM);
        hint.setPadding(0, Ui.dp(this, 10), 0, 0);
        bottom.addView(hint);
        FrameLayout.LayoutParams bl = new FrameLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        bl.setMargins(Ui.dp(this, 56), 0, Ui.dp(this, 56), Ui.dp(this, 22));
        controls.addView(bottom, bl);
        root.addView(controls, new FrameLayout.LayoutParams(-1, -1));
    }

    private android.widget.Button roundButton(int icon, boolean main, View.OnClickListener click) {
        android.widget.Button b = new android.widget.Button(this);
        int size = Ui.dp(this, main ? 56 : 48);
        android.graphics.drawable.Drawable d = getResources().getDrawable(icon, getTheme()).mutate();
        int is = Ui.dp(this, main ? 26 : 24);
        d.setBounds(0, 0, is, is);
        b.setCompoundDrawables(d, null, null, null);
        b.setPadding((size - is) / 2, 0, 0, 0);
        b.setCompoundDrawableTintList(main ? android.content.res.ColorStateList.valueOf(Ui.BG) : Ui.onFocusDark(Ui.TEXT));
        android.graphics.drawable.StateListDrawable bg = new android.graphics.drawable.StateListDrawable();
        android.graphics.drawable.GradientDrawable focused = new android.graphics.drawable.GradientDrawable();
        focused.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        focused.setColor(Color.WHITE);
        focused.setStroke(Ui.dp(this, 3), Ui.ACCENT);
        android.graphics.drawable.GradientDrawable normal = new android.graphics.drawable.GradientDrawable();
        normal.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        normal.setColor(main ? Color.parseColor("#E9E9EF") : Ui.alpha(Color.WHITE, 0.14f));
        bg.addState(new int[]{android.R.attr.state_focused}, focused);
        bg.addState(new int[]{}, normal);
        b.setBackground(bg);
        b.setStateListAnimator(null);
        b.setFocusable(true);
        b.setOnClickListener(click);
        b.setOnFocusChangeListener((v, has) -> { if (has) bumpHide(); });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
        lp.setMargins(0, 0, Ui.dp(this, 12), 0);
        b.setLayoutParams(lp);
        return b;
    }

    private void chip(String page, String label) {
        android.widget.Button b = Ui.button(this, label, v -> {
            lastChip = (android.widget.Button) v;
            showPopover(page, v);
        });
        b.setAllCaps(false);
        b.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        b.setBackground(Ui.focusable(this, Ui.alpha(Color.WHITE, 0.12f), Color.WHITE, 0));
        b.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 12), 0);
        b.setTextSize(13);
        b.setMaxWidth(Ui.dp(this, 150));
        b.setTag(label);
        b.setOnFocusChangeListener((v, has) -> { if (has) { lastChip = (android.widget.Button) v; bumpHide(); } });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(this, 52));
        lp.setMargins(Ui.dp(this, 6), 0, 0, 0);
        b.setLayoutParams(lp);
        chips.put(page, b);
    }

    /** Each option shows its current setting under its name, e.g. "SUBTITLES / English". */
    private void refreshChips() {
        if (controls == null || player == null) return;
        float sp = player.getPlaybackParameters().speed;
        String src = getIntent().getStringExtra("source");
        String[][] vals = {
                {"audio", currentLabel(C.TRACK_TYPE_AUDIO)},
                {"subs", currentLabel(C.TRACK_TYPE_TEXT)},
                {"quality", countTracks(C.TRACK_TYPE_VIDEO) > 1 ? currentLabel(C.TRACK_TYPE_VIDEO) : null},
                {"speed", sp == 1f ? "1×" : (sp + "×").replace(".0×", "×")},
                {"zoom", ZOOMS[Math.max(0, Math.min(2, intPref("player_zoom", 0)))]},
                {"more", src == null || src.isEmpty() ? "All options" : src},
        };
        for (String[] v : vals) {
            android.widget.Button b = chips.get(v[0]);
            if (b == null) continue;
            b.setVisibility(v[1] == null ? View.GONE : View.VISIBLE);
            if (v[1] == null) continue;
            String name = String.valueOf(b.getTag()).toUpperCase(Locale.ROOT);
            android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder(name);
            sb.setSpan(new android.text.style.RelativeSizeSpan(0.72f), 0, sb.length(), 0);
            sb.append("\n").append(v[1].length() > 16 ? v[1].substring(0, 15) + "…" : v[1]);
            b.setText(sb);
        }
    }

    private void updateBadges() {
        if (badges == null || player == null) return;
        badges.removeAllViews();
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        for (Tracks.Group g : player.getCurrentTracks().getGroups()) {
            for (int i = 0; i < g.length; i++) {
                if (!g.isTrackSelected(i)) continue;
                Format f = g.getTrackFormat(i);
                if (g.getType() == C.TRACK_TYPE_VIDEO) {
                    if (f.height >= 2000 || f.width >= 3800) out.add("4K");
                    else if (f.height >= 1000) out.add("HD");
                    String codecs = f.codecs == null ? "" : f.codecs;
                    if (MimeTypes.VIDEO_DOLBY_VISION.equals(f.sampleMimeType) || codecs.startsWith("dv")) out.add("Dolby Vision");
                    else if (f.colorInfo != null && (f.colorInfo.colorTransfer == C.COLOR_TRANSFER_ST2084
                            || f.colorInfo.colorTransfer == C.COLOR_TRANSFER_HLG)) out.add("HDR");
                } else if (g.getType() == C.TRACK_TYPE_AUDIO) {
                    if (MimeTypes.AUDIO_E_AC3_JOC.equals(f.sampleMimeType) || MimeTypes.AUDIO_TRUEHD.equals(f.sampleMimeType) && f.channelCount > 8) out.add("Atmos");
                    else if (f.channelCount >= 6) out.add(f.channelCount >= 8 ? "7.1" : "5.1");
                }
            }
        }
        for (String b : out) {
            TextView t = Ui.heading(this, b, 12);
            t.setBackground(Ui.rounded(this, Ui.alpha(Color.WHITE, 0.14f), 0, 6));
            t.setPadding(Ui.dp(this, 9), Ui.dp(this, 4), Ui.dp(this, 9), Ui.dp(this, 4));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(Ui.dp(this, 6), 0, 0, 0);
            badges.addView(t, lp);
        }
    }

    private void updateProgress() {
        if (player == null || track == null) return;
        long dur = player.getDuration(), pos = player.getCurrentPosition(), buf = player.getBufferedPosition();
        int w = track.getWidth();
        double f = dur > 0 ? Math.min(1, (double) pos / dur) : 0, fb = dur > 0 ? Math.min(1, (double) buf / dur) : 0;
        setWidth(played, (int) (w * f));
        setWidth(buffered, (int) (w * fb));
        knob.setTranslationX((float) (w * f) - Ui.dp(this, 8));
        timeNow.setText(Ui.time(pos));
        timeLeft.setText(dur > 0 ? "−" + Ui.time(Math.max(0, dur - pos)) : "");
        clock.setText(android.text.format.DateFormat.getTimeFormat(this).format(new java.util.Date()));
        playPause.setCompoundDrawables(icon(player.getPlayWhenReady() ? R.drawable.ic_pause : R.drawable.ic_play, 26), null, null, null);
    }

    private android.graphics.drawable.Drawable icon(int res, int dp) {
        android.graphics.drawable.Drawable d = getResources().getDrawable(res, getTheme()).mutate();
        d.setBounds(0, 0, Ui.dp(this, dp), Ui.dp(this, dp));
        return d;
    }

    private static void setWidth(View v, int w) {
        ViewGroup.LayoutParams lp = v.getLayoutParams();
        if (lp.width != w) {
            lp.width = Math.max(0, w);
            v.setLayoutParams(lp);
        }
    }

    private boolean controlsShown() {
        return controls != null && controls.getVisibility() == View.VISIBLE;
    }

    private void showControls(View focus) {
        if (controls == null) return;
        boolean was = controlsShown();
        controls.setVisibility(View.VISIBLE);
        if (!was) {
            controls.setAlpha(0f);
            controls.animate().alpha(1f).setDuration(160).start();
            refreshChips();
            updateBadges();
            App.MAIN.removeCallbacks(progressTick);
            progressTick.run();
        }
        if (focus != null) focus.requestFocus();
        bumpHide();
    }

    private void hideControls() {
        if (controls == null) return;
        controls.animate().alpha(0f).setDuration(200).withEndAction(() -> controls.setVisibility(View.GONE)).start();
        App.MAIN.removeCallbacks(autoHide);
        root.requestFocus();
    }

    private void bumpHide() {
        App.MAIN.removeCallbacks(autoHide);
        App.MAIN.postDelayed(autoHide, 6000);
    }

    private void togglePlay() {
        if (player == null) return;
        player.setPlayWhenReady(!player.getPlayWhenReady());
        updateProgress();
        bumpHide();
    }

    /** Option lists open as a card just above the option you picked. */
    private void showPopover(String page, View anchor) {
        if (anchor == null) anchor = chips.get(page.equals("main") ? "more" : page);
        if (anchor != null && anchor.getVisibility() != View.VISIBLE) anchor = chips.get("more");
        showControls(null);
        App.MAIN.removeCallbacks(autoHide);
        panel.setBackground(Ui.rounded(this, Ui.alpha(Ui.PANEL, 0.97f), 0, 18));
        panelList.setPadding(Ui.dp(this, 14), Ui.dp(this, 16), Ui.dp(this, 14), Ui.dp(this, 16));
        panel.setVisibility(View.VISIBLE);
        showPage(page.equals("more") ? "main" : page);
        int width = Ui.dp(this, 400);
        int[] loc = new int[2];
        int screenW = root.getWidth();
        int left = Ui.dp(this, 56);
        if (anchor != null) {
            anchor.getLocationInWindow(loc);
            left = Math.max(Ui.dp(this, 24), Math.min(loc[0] + anchor.getWidth() - width, screenW - width - Ui.dp(this, 24)));
        }
        int maxH = Math.max(Ui.dp(this, 200), root.getHeight() - Ui.dp(this, 220));
        panelList.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED);
        int h = Math.min(maxH, panelList.getMeasuredHeight());
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(width, h, Gravity.BOTTOM | Gravity.START);
        lp.leftMargin = left;
        lp.bottomMargin = Ui.dp(this, 150);
        panel.setLayoutParams(lp);
        panel.bringToFront();
    }

    /** Remote keys for movies and episodes. Returns null to let the normal handling run. */
    private Boolean vodKey(KeyEvent e) {
        int k = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        switch (k) {
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_HEADSETHOOK:
                if (down) { togglePlay(); showControls(controlsShown() ? null : playPause); }
                return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY:
                if (down && player != null) { player.setPlayWhenReady(true); showControls(null); }
                return true;
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                if (down && player != null) { player.setPlayWhenReady(false); showControls(playPause); }
                return true;
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                if (down) skip(30_000);
                return true;
            case KeyEvent.KEYCODE_MEDIA_REWIND:
                if (down) skip(-10_000);
                return true;
            default:
                break;
        }
        if (!controlsShown()) {
            switch (k) {
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (down) skip(k == KeyEvent.KEYCODE_DPAD_RIGHT ? 10_000 : -10_000);
                    return true;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                    if (down) { togglePlay(); showControls(playPause); }
                    return true;
                case KeyEvent.KEYCODE_DPAD_UP:
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    if (down) showControls(playPause);
                    return true;
                case KeyEvent.KEYCODE_MENU:
                case KeyEvent.KEYCODE_SETTINGS:
                    if (down) showPopover("more", null);
                    return true;
                case KeyEvent.KEYCODE_CAPTIONS:
                    if (down) showPopover("subs", null);
                    return true;
                case KeyEvent.KEYCODE_MEDIA_AUDIO_TRACK:
                    if (down) showPopover("audio", null);
                    return true;
                default:
                    return null;
            }
        }
        // Controls showing.
        if (down) bumpHide();
        if (k == KeyEvent.KEYCODE_BACK || k == KeyEvent.KEYCODE_ESCAPE) {
            if (down) hideControls();
            return true;
        }
        if (k == KeyEvent.KEYCODE_MENU && down) {
            showPopover("more", null);
            return true;
        }
        if (down && (k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_DPAD_DOWN)) return true;   // one row
        return null;
    }

    // ------------------------------------------------------------ skipping

    private TextView skipHint;
    private long skipTotal;
    private final Runnable hideSkip = () -> {
        if (skipHint != null) skipHint.animate().alpha(0f).setDuration(250).start();
        skipTotal = 0;
    };

    /** Skips by deltaMs; repeated presses add up ("+30 s") in a small label at the top. */
    private void skip(long deltaMs) {
        if (player == null) return;
        long dur = player.getDuration();
        long target = Math.max(0, player.getCurrentPosition() + deltaMs);
        if (dur > 0) target = Math.min(target, dur - 1000);
        player.seekTo(target);
        if ((skipTotal > 0) != (deltaMs > 0)) skipTotal = 0;
        skipTotal += deltaMs;
        if (skipHint == null) {
            skipHint = Ui.heading(this, "", 22);
            skipHint.setBackground(Ui.rounded(this, Ui.alpha(Color.BLACK, 0.7f), 0, 999));
            skipHint.setPadding(Ui.dp(this, 22), Ui.dp(this, 10), Ui.dp(this, 22), Ui.dp(this, 10));
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
            lp.topMargin = Ui.dp(this, 40);
            root.addView(skipHint, lp);
        }
        skipHint.setText((skipTotal > 0 ? "» +" : "« −") + Math.abs(skipTotal / 1000) + " s    " + Ui.time(target)
                + (dur > 0 ? " / " + Ui.time(dur) : ""));
        skipHint.animate().cancel();
        skipHint.setAlpha(1f);
        App.MAIN.removeCallbacks(hideSkip);
        App.MAIN.postDelayed(hideSkip, 1500);
    }

    // ------------------------------------------------------------ preferences

    static int intPref(String key, int def) {
        try {
            return Integer.parseInt(Store.get(key));
        } catch (Exception e) {
            return def;
        }
    }

    private static int zoomMode(int i) {
        return i == 1 ? AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                : i == 2 ? AspectRatioFrameLayout.RESIZE_MODE_FILL : AspectRatioFrameLayout.RESIZE_MODE_FIT;
    }

    /** Preferred audio/subtitle languages and subtitle mode from Settings. */
    private void applyTrackPreferences() {
        TrackSelectionParameters.Builder b = player.getTrackSelectionParameters().buildUpon();
        String audio = Store.get("player_audio_lang"), subs = Store.get("player_sub_lang"), mode = Store.get("player_subs");
        if (!audio.isEmpty()) b.setPreferredAudioLanguage(audio);
        if (!subs.isEmpty()) b.setPreferredTextLanguage(subs);
        if ("off".equals(mode)) {
            b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true);
        } else if ("on".equals(mode)) {
            if (subs.isEmpty()) b.setPreferredTextLanguage(Locale.getDefault().getLanguage());
            b.setSelectUndeterminedTextLanguage(true);
        }
        player.setTrackSelectionParameters(b.build());
    }

    private void applySubtitleStyle() {
        SubtitleView sv = view.getSubtitleView();
        if (sv == null) return;
        int size = Math.max(0, Math.min(SUB_SCALE.length - 1, intPref("player_sub_size", 1)));
        int style = Math.max(0, Math.min(SUB_STYLES.length - 1, intPref("player_sub_style", 0)));
        sv.setApplyEmbeddedStyles(false);
        sv.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * SUB_SCALE[size]);
        int fg = style == 2 ? Color.parseColor("#FFE14D") : Color.WHITE;
        CaptionStyleCompat cs = style == 1
                ? new CaptionStyleCompat(fg, Ui.alpha(Color.BLACK, 0.8f), Color.TRANSPARENT, CaptionStyleCompat.EDGE_TYPE_NONE,
                        Color.BLACK, Typeface.DEFAULT_BOLD)
                : new CaptionStyleCompat(fg, Color.TRANSPARENT, Color.TRANSPARENT, CaptionStyleCompat.EDGE_TYPE_OUTLINE,
                        Color.BLACK, Typeface.DEFAULT_BOLD);
        sv.setStyle(cs);
    }

    // ------------------------------------------------------------ options bar + panel

    private void buildOptionsBar() {
        optionsBar = Ui.horizontal(this);
        optionsBar.setGravity(Gravity.CENTER_VERTICAL);
        optionsBar.setPadding(Ui.dp(this, 10), Ui.dp(this, 6), Ui.dp(this, 10), Ui.dp(this, 6));
        optionsBar.setBackground(Ui.rounded(this, Ui.alpha(Color.BLACK, 0.6f), 0, 999));
        optionsBar.addView(barButton("Audio", "audio"));
        optionsBar.addView(barButton("Subtitles", "subs"));
        optionsBar.addView(barButton("Speed", "speed"));
        optionsBar.addView(barButton("Options", "main"));
        optionsBar.setVisibility(View.GONE);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
        lp.setMargins(0, Ui.dp(this, 26), Ui.dp(this, 36), 0);
        root.addView(optionsBar, lp);
    }

    private View barButton(String label, String page) {
        android.widget.Button b = Ui.button(this, label, v -> showPanel(page));
        b.setTextSize(14);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(Ui.dp(this, 3), 0, Ui.dp(this, 3), 0);
        b.setLayoutParams(lp);
        b.setOnFocusChangeListener((v, has) -> { if (has) view.showController(); });
        return b;
    }

    private void buildPanel() {
        panel = new FocusScroll.Vertical(this);
        panel.setBackground(new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{Ui.alpha(Ui.PANEL, 0.94f), Ui.alpha(Ui.PANEL, 0.99f)}));
        panelList = Ui.vertical(this);
        panelList.setPadding(Ui.dp(this, 26), Ui.dp(this, 30), Ui.dp(this, 26), Ui.dp(this, 30));
        panel.addView(panelList);
        panel.setVisibility(View.GONE);
        root.addView(panel, new FrameLayout.LayoutParams(Ui.dp(this, 430), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END));
    }

    private boolean panelOpen() {
        return panel.getVisibility() == View.VISIBLE;
    }

    private void showPanel(String page) {
        if (!live) {
            showPopover(page, null);
            return;
        }
        view.hideController();
        optionsBar.setVisibility(View.GONE);
        panel.setVisibility(View.VISIBLE);
        showPage(page);
    }

    private void closePanel() {
        panel.setVisibility(View.GONE);
        panelPage = null;
        if (!live) {
            refreshChips();
            showControls(lastChip != null ? lastChip : playPause);
            return;
        }
        view.requestFocus();
    }

    private void afterPick() {
        if (live) showPage("main");
        else closePanel();
    }

    private void backInPanel() {
        if (live && panelPage != null && !"main".equals(panelPage)) showPage("main");
        else closePanel();
    }

    private void showPage(String page) {
        String previous = panelPage;
        panelPage = page;
        panelList.removeAllViews();
        View focus = null;
        switch (page) {
            case "audio":
                header("Audio");
                focus = trackOptions(C.TRACK_TYPE_AUDIO);
                break;
            case "subs":
                header("Subtitles");
                focus = subtitleOptions();
                break;
            case "subsize":
                header("Subtitle size");
                focus = choiceList(SUB_SIZES, intPref("player_sub_size", 1), i -> {
                    Store.put("player_sub_size", String.valueOf(i));
                    applySubtitleStyle();
                });
                break;
            case "substyle":
                header("Subtitle style");
                focus = choiceList(SUB_STYLES, intPref("player_sub_style", 0), i -> {
                    Store.put("player_sub_style", String.valueOf(i));
                    applySubtitleStyle();
                });
                break;
            case "speed": {
                header("Playback speed");
                String[] names = new String[SPEEDS.length];
                int cur = 2;
                for (int i = 0; i < SPEEDS.length; i++) {
                    names[i] = SPEEDS[i] == 1f ? "Normal" : (SPEEDS[i] + "×").replace(".0×", "×");
                    if (Math.abs(player.getPlaybackParameters().speed - SPEEDS[i]) < 0.01f) cur = i;
                }
                focus = choiceList(names, cur, i -> player.setPlaybackSpeed(SPEEDS[i]));
                break;
            }
            case "zoom":
                header("Picture size");
                focus = choiceList(ZOOMS, intPref("player_zoom", 0), i -> {
                    Store.put("player_zoom", String.valueOf(i));
                    view.setResizeMode(zoomMode(i));
                });
                break;
            case "quality":
                header("Video quality");
                focus = trackOptions(C.TRACK_TYPE_VIDEO);
                break;
            default:
                panelPage = "main";
                header(info.title().isEmpty() ? "Options" : info.title());
                focus = item("Audio", currentLabel(C.TRACK_TYPE_AUDIO), false, v -> showPage("audio"));
                item("Subtitles", currentLabel(C.TRACK_TYPE_TEXT), false, v -> showPage("subs"));
                item("Subtitle size", SUB_SIZES[Math.max(0, Math.min(3, intPref("player_sub_size", 1)))], false, v -> showPage("subsize"));
                item("Subtitle style", SUB_STYLES[Math.max(0, Math.min(2, intPref("player_sub_style", 0)))], false, v -> showPage("substyle"));
                float sp = player.getPlaybackParameters().speed;
                item("Playback speed", sp == 1f ? "Normal" : sp + "×", false, v -> showPage("speed"));
                item("Picture size", ZOOMS[Math.max(0, Math.min(2, intPref("player_zoom", 0)))], false, v -> showPage("zoom"));
                if (countTracks(C.TRACK_TYPE_VIDEO) > 1) item("Video quality", currentLabel(C.TRACK_TYPE_VIDEO), false, v -> showPage("quality"));
                item("Play in another app", "VLC, MX Player, Just Player…", false, v -> openExternally());
                if (previous != null && !"main".equals(previous)) focus = focusFor(previous, focus);
                break;
        }
        if (focus == null && panelList.getChildCount() > 1) focus = panelList.getChildAt(1);
        View f = focus;
        if (f != null) f.post(f::requestFocus);
    }

    /** When going back to the main page, focus the entry you came from. */
    private View focusFor(String page, View fallback) {
        String[] order = {"audio", "subs", "subsize", "substyle", "speed", "zoom", "quality"};
        for (int i = 0; i < order.length; i++) {
            if (order[i].equals(page) && i + 1 < panelList.getChildCount()) return panelList.getChildAt(i + 1);
        }
        return fallback;
    }

    private void header(String s) {
        TextView t = Ui.single(Ui.heading(this, s, 20));
        t.setPadding(Ui.dp(this, 4), 0, 0, Ui.dp(this, 12));
        panelList.addView(t);
    }

    private View item(String title, String value, boolean selected, View.OnClickListener click) {
        String text = (selected ? "✓  " : "") + title;
        android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder(text);
        if (value != null && !value.isEmpty()) {
            int start = sb.length();
            sb.append("\n").append(value);
            sb.setSpan(new android.text.style.RelativeSizeSpan(0.8f), start, sb.length(), 0);
            sb.setSpan(new android.text.style.ForegroundColorSpan(Ui.MUTED), start, sb.length(), 0);
        }
        android.widget.Button b = Ui.listButton(this, sb, click);
        b.setTextSize(16);
        b.setAllCaps(false);
        if (selected) b.setTextColor(Ui.onFocusDark(Ui.ACCENT));
        panelList.addView(b);
        return b;
    }

    interface Pick { void run(int i); }

    private View choiceList(String[] names, int selected, Pick pick) {
        View focus = null;
        for (int i = 0; i < names.length; i++) {
            int idx = i;
            View b = item(names[i], null, i == selected, v -> {
                pick.run(idx);
                afterPick();
            });
            if (i == selected) focus = b;
        }
        return focus;
    }

    // ------------------------------------------------------------ tracks

    private int countTracks(int type) {
        int n = 0;
        for (Tracks.Group g : player.getCurrentTracks().getGroups()) {
            if (g.getType() == type) for (int i = 0; i < g.length; i++) if (g.isTrackSupported(i)) n++;
        }
        return n;
    }

    private String currentLabel(int type) {
        for (Tracks.Group g : player.getCurrentTracks().getGroups()) {
            if (g.getType() != type) continue;
            for (int i = 0; i < g.length; i++) if (g.isTrackSelected(i)) return describe(g.getTrackFormat(i), type);
        }
        if (type == C.TRACK_TYPE_TEXT) return "Off";
        if (type == C.TRACK_TYPE_VIDEO) return "Auto";
        return countTracks(type) == 0 ? "None" : "Default";
    }

    /** Audio, video or subtitle choices for the panel; returns the selected entry. */
    private View trackOptions(int type) {
        View focus = null;
        boolean overridden = !player.getTrackSelectionParameters().overrides.isEmpty();
        if (type == C.TRACK_TYPE_VIDEO) {
            boolean auto = true;
            for (TrackSelectionOverride o : player.getTrackSelectionParameters().overrides.values()) {
                if (o.getType() == C.TRACK_TYPE_VIDEO) auto = false;
            }
            focus = item("Auto", "Adjusts to your connection", auto, v -> {
                player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                        .clearOverridesOfType(C.TRACK_TYPE_VIDEO).build());
                afterPick();
            });
        }
        int count = 0;
        for (Tracks.Group g : player.getCurrentTracks().getGroups()) {
            if (g.getType() != type) continue;
            for (int i = 0; i < g.length; i++) {
                Format f = g.getTrackFormat(i);
                boolean supported = g.isTrackSupported(i);
                boolean sel = g.isTrackSelected(i) && (type != C.TRACK_TYPE_VIDEO || overridden);
                int idx = i;
                Tracks.Group group = g;
                View b = item(describe(f, type), supported ? detail(f, type) : "Not supported on this TV", sel, v -> {
                    if (!supported) {
                        Toast.makeText(this, "This TV can't play that track. Try \"Play in another app\".", Toast.LENGTH_LONG).show();
                        return;
                    }
                    selectTrack(group, idx, type);
                    afterPick();
                });
                if (sel) focus = b;
                count++;
            }
        }
        if (count == 0) {
            panelList.addView(Ui.text(this, type == C.TRACK_TYPE_AUDIO ? "No audio tracks found yet."
                    : "This video has only one quality.", 14, Ui.MUTED));
        }
        return focus;
    }

    private void selectTrack(Tracks.Group g, int index, int type) {
        player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                .setTrackTypeDisabled(type, false)
                .setOverrideForType(new TrackSelectionOverride(g.getMediaTrackGroup(), index))
                .build());
        String lang = g.getTrackFormat(index).language;
        // Remember the language for next time.
        if (type == C.TRACK_TYPE_AUDIO && lang != null && !lang.equals("und")) Store.put("player_audio_lang", lang);
        if (type == C.TRACK_TYPE_TEXT) {
            Store.put("player_subs", "on");
            if (lang != null && !lang.equals("und")) Store.put("player_sub_lang", lang);
        }
    }

    private View subtitleOptions() {
        boolean anyOn = false;
        for (Tracks.Group g : player.getCurrentTracks().getGroups()) if (g.getType() == C.TRACK_TYPE_TEXT && g.isSelected()) anyOn = true;
        boolean off = !anyOn;
        View focus = item("Off", null, off, v -> {
            player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                    .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build());
            Store.put("player_subs", "off");
            afterPick();
        });
        View sel = trackOptions(C.TRACK_TYPE_TEXT);
        if (!off && sel != null) focus = sel;
        if (panelList.getChildAt(panelList.getChildCount() - 1) instanceof TextView
                && !(panelList.getChildAt(panelList.getChildCount() - 1) instanceof android.widget.Button)) {
            panelList.removeViewAt(panelList.getChildCount() - 1);   // drop the "no tracks" note
        }
        if (live) return focus;

        TextView h = Ui.text(this, "FROM YOUR ADD-ONS", 12, Ui.DIM);
        h.setPadding(Ui.dp(this, 4), Ui.dp(this, 16), 0, Ui.dp(this, 4));
        panelList.addView(h);
        if (addonSubs == null) {
            item("Find subtitles", "Searches add-ons such as OpenSubtitles", false, v -> {
                addonSubs = new ArrayList<>();
                showPage("subs");
                loadAddonSubtitles();
            });
        } else if (addonSubs.isEmpty()) {
            panelList.addView(Ui.text(this, loadingSubs ? "Searching…" : "None found. Install a subtitles add-on "
                    + "(e.g. OpenSubtitles v3) in Settings → Add-ons.", 14, Ui.MUTED));
        } else {
            String pref = Store.get("player_sub_lang");
            for (JSONObject s : addonSubs) {
                String lang = s.optString("lang");
                String name = languageName(lang);
                View b = item(name, s.optString("addon"), false, v -> addAddonSubtitle(s));
                if (focus == null && !pref.isEmpty() && lang.startsWith(pref)) focus = b;
            }
        }
        return focus;
    }

    private boolean loadingSubs;

    private void loadAddonSubtitles() {
        String type = info.type.isEmpty() ? "movie" : info.type;
        String id = info.videoId;
        loadingSubs = true;
        App.async(() -> {
            List<JSONObject> out = new ArrayList<>();
            for (Addon a : Addons.all()) {
                if (!a.has("subtitles", type, id)) continue;
                try {
                    JSONArray subs = new JSONObject(Http.get(a.resourceUrl("subtitles", type, id, null), null)).optJSONArray("subtitles");
                    if (subs != null) for (int i = 0; i < subs.length() && out.size() < 80; i++) {
                        JSONObject s = subs.optJSONObject(i);
                        if (s == null || s.optString("url").isEmpty()) continue;
                        s.put("addon", a.name());
                        out.add(s);
                    }
                } catch (Exception ignored) { }
            }
            // Your language first, then the order the add-ons gave.
            String pref = Store.get("player_sub_lang").isEmpty() ? Locale.getDefault().getISO3Language() : Store.get("player_sub_lang");
            List<JSONObject> sorted = new ArrayList<>();
            for (JSONObject s : out) if (matchesLang(s.optString("lang"), pref)) sorted.add(s);
            for (JSONObject s : out) if (!sorted.contains(s)) sorted.add(s);
            return sorted;
        }, list -> {
            loadingSubs = false;
            addonSubs = list;
            if ("subs".equals(panelPage)) showPage("subs");
        }, e -> {
            loadingSubs = false;
            if ("subs".equals(panelPage)) showPage("subs");
        });
    }

    private void addAddonSubtitle(JSONObject s) {
        if (player == null || player.getCurrentMediaItem() == null) return;
        String u = s.optString("url");
        String lower = u.toLowerCase(Locale.US);
        String mime = lower.contains(".vtt") ? MimeTypes.TEXT_VTT
                : lower.contains(".ass") || lower.contains(".ssa") ? MimeTypes.TEXT_SSA : MimeTypes.APPLICATION_SUBRIP;
        String label = languageName(s.optString("lang")) + " · " + s.optString("addon") + " #" + (extraSubs.size() + 1);
        extraSubs.add(new MediaItem.SubtitleConfiguration.Builder(Uri.parse(u))
                .setMimeType(mime)
                .setLanguage(s.optString("lang"))
                .setLabel(label)
                .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                .build());
        pendingSubLabel = label;
        long pos = player.getCurrentPosition();
        MediaItem next = player.getCurrentMediaItem().buildUpon().setSubtitleConfigurations(new ArrayList<>(extraSubs)).build();
        player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).build());
        player.setMediaItem(next, pos);
        player.prepare();
        Toast.makeText(this, "Loading subtitles…", Toast.LENGTH_SHORT).show();
        closePanel();
    }

    private void selectPendingSubtitle(Tracks tracks) {
        if (pendingSubLabel == null) return;
        for (Tracks.Group g : tracks.getGroups()) {
            if (g.getType() != C.TRACK_TYPE_TEXT) continue;
            for (int i = 0; i < g.length; i++) {
                if (pendingSubLabel.equals(g.getTrackFormat(i).label)) {
                    pendingSubLabel = null;
                    selectTrack(g, i, C.TRACK_TYPE_TEXT);
                    return;
                }
            }
        }
    }

    private static boolean matchesLang(String lang, String pref) {
        if (lang == null || pref == null || pref.isEmpty()) return false;
        String a = iso3(lang), b = iso3(pref);
        return !a.isEmpty() && a.equals(b);
    }

    private static String iso3(String code) {
        try {
            return new Locale(code.length() > 3 ? code.substring(0, 2) : code).getISO3Language();
        } catch (Exception e) {
            return code;
        }
    }

    static String languageName(String code) {
        if (code == null || code.isEmpty() || code.equals("und")) return "Unknown language";
        String name = new Locale(code.split("[-_]")[0]).getDisplayLanguage();
        return name == null || name.isEmpty() || name.equalsIgnoreCase(code) ? code : name;
    }

    private String describe(Format f, int type) {
        if (type == C.TRACK_TYPE_VIDEO) {
            return f.height > 0 ? f.height + "p" + (f.frameRate > 30 ? Math.round(f.frameRate) : "") : "Video";
        }
        String name = f.label != null && !f.label.isEmpty() ? f.label : languageName(f.language);
        if (f.label != null && !f.label.isEmpty() && f.language != null && !f.label.toLowerCase(Locale.US)
                .contains(languageName(f.language).toLowerCase(Locale.US))) {
            name = languageName(f.language) + " · " + f.label;
        }
        if ((f.selectionFlags & C.SELECTION_FLAG_FORCED) != 0) name += " (forced)";
        return name;
    }

    private String detail(Format f, int type) {
        List<String> parts = new ArrayList<>();
        if (type == C.TRACK_TYPE_AUDIO) {
            String codec = codecName(f.sampleMimeType, f.codecs);
            if (!codec.isEmpty()) parts.add(codec);
            if (f.channelCount > 0) parts.add(f.channelCount == 1 ? "Mono" : f.channelCount == 2 ? "Stereo"
                    : f.channelCount == 6 ? "5.1" : f.channelCount == 8 ? "7.1" : f.channelCount + " ch");
        } else if (type == C.TRACK_TYPE_VIDEO) {
            if (f.width > 0) parts.add(f.width + "×" + f.height);
            if (f.bitrate > 0) parts.add(String.format(Locale.US, "%.1f Mbps", f.bitrate / 1_000_000f));
            String codec = codecName(f.sampleMimeType, f.codecs);
            if (!codec.isEmpty()) parts.add(codec);
        } else {
            String m = f.sampleMimeType == null ? "" : f.sampleMimeType;
            if (m.contains("pgs")) parts.add("Image (PGS)");
            else if (m.contains("ssa")) parts.add("Styled (ASS)");
            else if (m.contains("subrip")) parts.add("SRT");
            else if (m.contains("vtt")) parts.add("WebVTT");
            if ((f.roleFlags & C.ROLE_FLAG_DESCRIBES_MUSIC_AND_SOUND) != 0) parts.add("SDH");
        }
        return android.text.TextUtils.join(" · ", parts);
    }

    private static String codecName(String mime, String codecs) {
        if (mime == null) return "";
        switch (mime) {
            case MimeTypes.AUDIO_AC3: return "Dolby Digital";
            case MimeTypes.AUDIO_E_AC3: return "Dolby Digital+";
            case MimeTypes.AUDIO_E_AC3_JOC: return "Dolby Atmos";
            case MimeTypes.AUDIO_TRUEHD: return "Dolby TrueHD";
            case MimeTypes.AUDIO_DTS: return "DTS";
            case MimeTypes.AUDIO_DTS_HD: return "DTS-HD";
            case MimeTypes.AUDIO_AAC: return "AAC";
            case MimeTypes.AUDIO_OPUS: return "Opus";
            case MimeTypes.AUDIO_FLAC: return "FLAC";
            case MimeTypes.AUDIO_MPEG: return "MP3";
            case MimeTypes.VIDEO_H264: return "H.264";
            case MimeTypes.VIDEO_H265: return codecs != null && codecs.contains("dvh") ? "HEVC · Dolby Vision" : "HEVC";
            case MimeTypes.VIDEO_DOLBY_VISION: return "Dolby Vision";
            case MimeTypes.VIDEO_AV1: return "AV1";
            case MimeTypes.VIDEO_VP9: return "VP9";
            default: return "";
        }
    }

    private void openExternally() {
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(Uri.parse(url), "video/*");
        i.putExtra("title", info.title());
        if (player != null) i.putExtra("position", (int) player.getCurrentPosition());
        try {
            startActivity(Intent.createChooser(i, "Play with"));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "No other video player installed", Toast.LENGTH_SHORT).show();
        }
    }

    // ------------------------------------------------------------ live TV

    private boolean live, triedHls;
    private Iptv.Source liveSource;
    private String liveCategory;
    private int liveIndex;
    private java.util.List<Iptv.Channel> liveChannels;
    private LinearLayout banner;
    private TextView bannerTitle, bannerNow;
    private final Runnable hideBanner = () -> { if (banner != null) banner.animate().alpha(0f).setDuration(300).start(); };

    private void playUrl(String u) {
        player.setMediaItem(MediaItem.fromUri(Uri.parse(u)));
        player.prepare();
        player.setPlayWhenReady(true);
    }

    private void switchChannel(int delta) {
        if (liveChannels == null || liveChannels.isEmpty() || liveSource == null) return;
        liveIndex = (liveIndex + delta + liveChannels.size()) % liveChannels.size();
        triedHls = false;
        url = Iptv.liveUrl(liveSource, liveChannels.get(liveIndex), false);
        playUrl(url);
        showBanner();
    }

    private void showBanner() {
        if (banner == null || liveChannels == null || liveIndex >= liveChannels.size()) return;
        Iptv.Channel c = liveChannels.get(liveIndex);
        bannerTitle.setText((c.num.isEmpty() ? "" : c.num + "   ") + c.name);
        bannerNow.setText("");
        banner.animate().cancel();
        banner.setAlpha(1f);
        App.MAIN.removeCallbacks(hideBanner);
        App.MAIN.postDelayed(hideBanner, 4500);
        Iptv.Source src = liveSource;
        int idx = liveIndex;
        App.async(() -> Iptv.nowNext(src, c), progs -> {
            if (idx != liveIndex || progs.isEmpty()) return;
            String t = "Now  " + progs.get(0).title;
            if (progs.size() > 1) t += "\nNext  " + progs.get(1).title;
            bannerNow.setText(t);
        }, null);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        if (!live && controls != null && !panelOpen()) {
            Boolean r = vodKey(e);
            if (r != null) return r;
        }
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            if (panelOpen()) {
                if (k == KeyEvent.KEYCODE_BACK || k == KeyEvent.KEYCODE_ESCAPE) {
                    backInPanel();
                    return true;
                }
                if (k == KeyEvent.KEYCODE_MENU) {
                    closePanel();
                    return true;
                }
                return super.dispatchKeyEvent(e);
            }
            if (k == KeyEvent.KEYCODE_MENU || k == KeyEvent.KEYCODE_SETTINGS) {
                showPanel("main");
                return true;
            }
            if (k == KeyEvent.KEYCODE_CAPTIONS) {
                showPanel("subs");
                return true;
            }
            if (k == KeyEvent.KEYCODE_MEDIA_AUDIO_TRACK) {
                showPanel("audio");
                return true;
            }
            if (optionsBar.hasFocus()) {
                view.showController();
                if (k == KeyEvent.KEYCODE_DPAD_DOWN || k == KeyEvent.KEYCODE_BACK) {
                    view.requestFocus();
                    view.showController();
                    return true;
                }
                return super.dispatchKeyEvent(e);
            }
            boolean controls = view.isControllerFullyVisible();
            if (live) {
                if (k == KeyEvent.KEYCODE_CHANNEL_UP || (!controls && k == KeyEvent.KEYCODE_DPAD_UP)) {
                    switchChannel(-1);
                    return true;
                }
                if (k == KeyEvent.KEYCODE_CHANNEL_DOWN || (!controls && k == KeyEvent.KEYCODE_DPAD_DOWN)) {
                    switchChannel(1);
                    return true;
                }
                if (!controls && (k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER)) {
                    showBanner();
                }
            }
            // ←/→ with the controls hidden: skip back / forward 10 seconds.
            if (!live && !controls && (k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT)) {
                skip(k == KeyEvent.KEYCODE_DPAD_RIGHT ? 10_000 : -10_000);
                return true;
            }
            // ↑ jumps to Audio / Subtitles / Speed / Options at the top.
            if (k == KeyEvent.KEYCODE_DPAD_UP && (!live || controls)) {
                view.showController();
                optionsBar.setVisibility(View.VISIBLE);
                optionsBar.getChildAt(0).requestFocus();
                return true;
            }
        }
        return super.dispatchKeyEvent(e);
    }

    @Override
    protected void onResume() {
        super.onResume();
        App.MAIN.postDelayed(saver, 10_000);
    }

    @Override
    protected void onPause() {
        super.onPause();
        App.MAIN.removeCallbacks(saver);
        saveProgress();
        if (!reported) {
            reportToServer();
            if (scrobbling || percent() > 1) scrobble(percent() >= 80 ? "stop" : "pause");
            scrobbling = false;
        }
        if (player != null) player.setPlayWhenReady(false);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (player != null) {
            player.release();
            player = null;
        }
    }

    private double percent() {
        if (player == null) return 0;
        long dur = player.getDuration();
        long pos = player.getPlaybackState() == Player.STATE_ENDED ? dur : player.getCurrentPosition();
        return dur > 0 ? 100.0 * pos / dur : 0;
    }

    private void scrobble(String action) {
        if (live) return;
        Trakt.scrobble(info, action, "stop".equals(action) && player != null
                && player.getPlaybackState() == Player.STATE_ENDED ? 100 : percent());
    }

    private void saveProgress() {
        if (player == null || live || info.videoId.isEmpty()) return;
        long dur = player.getDuration();
        long pos = player.getPlaybackState() == Player.STATE_ENDED ? dur : player.getCurrentPosition();
        if (dur <= 0 || pos < 0) return;
        Store.saveProgress(info, pos, dur);
    }

    /** Sends the final position to Plex/Jellyfin/Emby when watching from a server. */
    private void reportToServer() {
        if (player == null) return;
        long dur = player.getDuration();
        long pos = player.getPlaybackState() == Player.STATE_ENDED ? dur : player.getCurrentPosition();
        if (dur > 0 && pos >= 0) Sources.report(info.videoId, pos, dur);
    }

    private void showError(PlaybackException error) {
        if (isFinishing()) return;
        new AlertDialog.Builder(this)
                .setTitle("Playback error")
                .setMessage(error.getErrorCodeName() + "\n" + (error.getMessage() == null ? "" : error.getMessage())
                        + "\n\nThe built-in player may not support this file's audio or video format.")
                .setPositiveButton("Open in another player", (d, w) -> {
                    openExternally();
                    finish();
                })
                .setNegativeButton("Close", (d, w) -> finish())
                .setCancelable(false)
                .show();
    }
}
