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

        view = new PlayerView(this);
        view.setBackgroundColor(Color.BLACK);
        view.setKeepScreenOn(true);
        setContentView(view);

        player = new ExoPlayer.Builder(this).build();
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
                showError(error);
            }
        });

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
        if (player == null || info.videoId.isEmpty()) return;
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
