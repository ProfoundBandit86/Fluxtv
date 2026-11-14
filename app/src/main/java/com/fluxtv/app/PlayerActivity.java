package com.fluxtv.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.WindowManager;
import android.widget.Toast;

import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;

import org.json.JSONObject;

/** Built-in player (Media3/ExoPlayer). Resumes where you left off and saves progress. */
public class PlayerActivity extends Activity {

    private ExoPlayer player;
    private PlayerView view;
    private PlayInfo info;
    private String url;
    private boolean reported;

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
        android.widget.FrameLayout root = new android.widget.FrameLayout(this);
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
            android.widget.FrameLayout.LayoutParams bl = new android.widget.FrameLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                    android.view.Gravity.TOP | android.view.Gravity.START);
            bl.setMargins(Ui.dp(this, 40), Ui.dp(this, 32), 0, 0);
            root.addView(banner, bl);
            liveSource = Iptv.source(getIntent().getStringExtra("live_source"));
            liveCategory = getIntent().getStringExtra("live_category");
            liveIndex = getIntent().getIntExtra("live_index", 0);
            view.setControllerShowTimeoutMs(2500);
        }
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
        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_ENDED) {
                    saveProgress();
                    reportToServer();
                    reported = true;
                    finish();
                }
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
        view.requestFocus();
    }

    // ------------------------------------------------------------ live TV

    private boolean live, triedHls;
    private Iptv.Source liveSource;
    private String liveCategory;
    private int liveIndex;
    private java.util.List<Iptv.Channel> liveChannels;
    private android.widget.LinearLayout banner;
    private android.widget.TextView bannerTitle, bannerNow;
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
    public boolean dispatchKeyEvent(android.view.KeyEvent e) {
        if (live && e.getAction() == android.view.KeyEvent.ACTION_DOWN) {
            int k = e.getKeyCode();
            boolean controls = view.isControllerFullyVisible();
            if (k == android.view.KeyEvent.KEYCODE_CHANNEL_UP || (!controls && k == android.view.KeyEvent.KEYCODE_DPAD_UP)) {
                switchChannel(-1);
                return true;
            }
            if (k == android.view.KeyEvent.KEYCODE_CHANNEL_DOWN || (!controls && k == android.view.KeyEvent.KEYCODE_DPAD_DOWN)) {
                switchChannel(1);
                return true;
            }
            if (!controls && (k == android.view.KeyEvent.KEYCODE_DPAD_CENTER || k == android.view.KeyEvent.KEYCODE_ENTER)) {
                showBanner();
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
        if (!reported) reportToServer();
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
                    Intent i = new Intent(Intent.ACTION_VIEW);
                    i.setDataAndType(Uri.parse(url), "video/*");
                    try {
                        startActivity(Intent.createChooser(i, "Play with"));
                    } catch (ActivityNotFoundException e) {
                        Toast.makeText(this, "No other video player installed", Toast.LENGTH_SHORT).show();
                    }
                    finish();
                })
                .setNegativeButton("Close", (d, w) -> finish())
                .setCancelable(false)
                .show();
    }
}
