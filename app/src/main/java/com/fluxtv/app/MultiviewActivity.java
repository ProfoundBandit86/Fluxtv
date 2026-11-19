package com.fluxtv.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;

import java.util.ArrayList;
import java.util.List;

/**
 * Up to four channels at once. Arrows move the highlight between views; the highlighted one plays its sound.
 * OK changes that view's channel · MENU adds / removes views or opens one full screen · Back leaves.
 */
public class MultiviewActivity extends Activity {

    private static final int MAX = 4;

    private static final class Tile {
        FrameLayout frame;
        PlayerView view;
        ExoPlayer player;
        TextView label;
        GuideView.Entry entry;
        boolean triedHls;
    }

    private FrameLayout root;
    private final List<Tile> tiles = new ArrayList<>();
    private List<GuideView.Entry> channels = new ArrayList<>();
    private int focus;
    private String group;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        setContentView(root);
        root.addOnLayoutChangeListener((v, l, t, r, bt, ol, ot, or, ob) -> { if (r - l != or - ol || bt - t != ob - ot) root.post(this::layout); });
        group = getIntent().getStringExtra("group");
        if (group == null) group = "all";
        String src = getIntent().getStringExtra("source"), ch = getIntent().getStringExtra("channel");
        App.async(() -> LiveActivity.entries(group), l -> {
            channels = l;
            if (channels.isEmpty()) { finish(); return; }
            GuideView.Entry first = null;
            for (GuideView.Entry e : channels) if (e.source.id.equals(src) && e.channel.id.equals(ch)) { first = e; break; }
            if (first == null) first = channels.get(0);
            addTile(first);
            // Second view: the channel watched before, or the next one in the list.
            int at = channels.indexOf(first);
            addTile(channels.size() > 1 ? channels.get((at + 1) % channels.size()) : null);
            setFocus(0);
            Toast.makeText(this, "Multiview · arrows pick a view (its sound plays) · OK change channel · MENU more", Toast.LENGTH_LONG).show();
        }, e -> finish());
    }

    private void addTile(GuideView.Entry e) {
        if (tiles.size() >= MAX) return;
        Tile t = new Tile();
        t.frame = new FrameLayout(this);
        t.view = new PlayerView(this);
        t.view.setUseController(false);
        t.view.setShutterBackgroundColor(Color.BLACK);
        t.view.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
        t.frame.addView(t.view, new FrameLayout.LayoutParams(-1, -1));
        t.label = Ui.single(Ui.text(this, "", 13, Color.WHITE));
        t.label.setPadding(Ui.dp(this, 10), Ui.dp(this, 4), Ui.dp(this, 10), Ui.dp(this, 4));
        t.label.setBackground(Ui.rounded(this, Ui.alpha(Color.BLACK, 0.6f), 0, 6));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        lp.setMargins(Ui.dp(this, 10), Ui.dp(this, 10), 0, 0);
        t.frame.addView(t.label, lp);
        t.player = Players.create(this);
        LipSync.use(this, null);
        // Smaller pictures: ask for at most 720p so the box's decoders can handle several channels.
        t.player.setTrackSelectionParameters(t.player.getTrackSelectionParameters().buildUpon().setMaxVideoSize(1280, 720).build());
        t.view.setPlayer(t.player);
        t.player.addListener(new Player.Listener() {
            @Override public void onPlayerError(PlaybackException err) {
                if (t.entry != null && t.entry.source.xtream() && !t.triedHls) {   // MPEG-TS refused: try HLS
                    t.triedHls = true;
                    t.player.setMediaItem(MediaItem.fromUri(Uri.parse(Iptv.liveUrl(t.entry.source, t.entry.channel, true))));
                    t.player.prepare();
                    t.player.play();
                    return;
                }
                t.label.setText((t.entry == null ? "" : t.entry.channel.name + " · ") + "not playing");
            }
        });
        tiles.add(t);
        root.addView(t.frame);
        tune(t, e);
        layout();
    }

    private void removeTile(int i) {
        if (tiles.size() <= 1) { finish(); return; }
        Tile t = tiles.remove(i);
        t.player.release();
        root.removeView(t.frame);
        layout();
        setFocus(Math.min(i, tiles.size() - 1));
    }

    private void tune(Tile t, GuideView.Entry e) {
        t.entry = e;
        t.triedHls = false;
        if (e == null) {
            t.player.stop();
            t.label.setText("OK to pick a channel");
            return;
        }
        t.label.setText(e.channel.num + "  " + e.channel.name);
        t.player.setMediaItem(MediaItem.fromUri(Uri.parse(Iptv.liveUrl(e.source, e.channel, false))));
        t.player.prepare();
        t.player.play();
    }

    /** 1 = full screen, 2 = side by side, 3–4 = a 2×2 grid. */
    private void layout() {
        int w = root.getWidth() > 0 ? root.getWidth() : getResources().getDisplayMetrics().widthPixels;
        int h = root.getHeight() > 0 ? root.getHeight() : getResources().getDisplayMetrics().heightPixels;
        int n = tiles.size(), gap = Ui.dp(this, 3);
        for (int i = 0; i < n; i++) {
            int cw, chh, x, y;
            if (n == 1) { cw = w; chh = h; x = 0; y = 0; }
            else if (n == 2) { cw = w / 2; chh = cw * 9 / 16; x = i * cw; y = (h - chh) / 2; }
            else { cw = w / 2; chh = h / 2; x = (i % 2) * cw; y = (i / 2) * chh; }
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(cw - gap, chh - gap);
            lp.leftMargin = x + gap / 2;
            lp.topMargin = y + gap / 2;
            tiles.get(i).frame.setLayoutParams(lp);
        }
        root.requestLayout();
    }

    private void setFocus(int i) {
        if (tiles.isEmpty()) return;
        focus = Math.max(0, Math.min(tiles.size() - 1, i));
        for (int k = 0; k < tiles.size(); k++) {
            Tile t = tiles.get(k);
            boolean f = k == focus;
            t.player.setVolume(f ? 1f : 0f);   // sound follows the highlight
            t.frame.setForeground(f ? Ui.rounded(this, Color.TRANSPARENT, Ui.ACCENT, 4) : null);
        }
    }

    private void pickChannel() {
        Tile t = tiles.get(focus);
        String[] names = new String[channels.size()];
        int sel = 0;
        for (int i = 0; i < channels.size(); i++) {
            GuideView.Entry e = channels.get(i);
            List<Iptv.Program> now = Iptv.nowNext(e.source, e.channel);
            names[i] = e.channel.num + "  " + e.channel.name + (now.isEmpty() ? "" : "  ·  " + now.get(0).title);
            if (t.entry == e) sel = i;
        }
        new AlertDialog.Builder(this).setTitle("Channel for this view")
                .setSingleChoiceItems(names, sel, (d, w) -> { d.dismiss(); tune(t, channels.get(w)); })
                .show();
    }

    private void menu() {
        Tile t = tiles.get(focus);
        List<String> items = new ArrayList<>();
        items.add("Change channel");
        if (tiles.size() < MAX) items.add("Add a view");
        items.add("Remove this view");
        if (t.entry != null) items.add("Watch " + t.entry.channel.name + " full screen");
        new AlertDialog.Builder(this).setTitle("Multiview")
                .setItems(items.toArray(new String[0]), (d, w) -> {
                    String it = items.get(w);
                    if (it.startsWith("Change")) pickChannel();
                    else if (it.startsWith("Add")) {
                        GuideView.Entry last = tiles.get(tiles.size() - 1).entry;
                        int at = last == null ? 0 : channels.indexOf(last) + 1;
                        addTile(channels.get(Math.max(0, at) % channels.size()));
                        setFocus(tiles.size() - 1);
                    } else if (it.startsWith("Remove")) removeTile(focus);
                    else {
                        startActivity(new Intent(this, LivePlayerActivity.class).putExtra("group", group)
                                .putExtra("source", t.entry.source.id).putExtra("channel", t.entry.channel.id));
                        finish();
                    }
                }).show();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getAction() != KeyEvent.ACTION_DOWN || tiles.isEmpty()) return super.dispatchKeyEvent(e);
        int n = tiles.size(), cols = n <= 2 ? n : 2;
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_LEFT: if (focus % cols > 0) setFocus(focus - 1); return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT: if (focus % cols < cols - 1 && focus + 1 < n) setFocus(focus + 1); return true;
            case KeyEvent.KEYCODE_DPAD_UP: if (focus - cols >= 0) setFocus(focus - cols); return true;
            case KeyEvent.KEYCODE_DPAD_DOWN: if (focus + cols < n) setFocus(focus + cols); return true;
            case KeyEvent.KEYCODE_CHANNEL_UP:
            case KeyEvent.KEYCODE_CHANNEL_DOWN: {
                Tile t = tiles.get(focus);
                int at = t.entry == null ? 0 : channels.indexOf(t.entry) + (e.getKeyCode() == KeyEvent.KEYCODE_CHANNEL_UP ? -1 : 1);
                tune(t, channels.get((at % channels.size() + channels.size()) % channels.size()));
                return true;
            }
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                pickChannel();
                return true;
            case KeyEvent.KEYCODE_MENU:
                menu();
                return true;
            default:
                return super.dispatchKeyEvent(e);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        for (Tile t : tiles) t.player.pause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        for (Tile t : tiles) if (t.entry != null) t.player.play();
    }

    @Override
    protected void onDestroy() {
        for (Tile t : tiles) t.player.release();
        tiles.clear();
        super.onDestroy();
    }
}
