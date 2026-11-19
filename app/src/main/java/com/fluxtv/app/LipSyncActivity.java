package com.fluxtv.app;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTimestamp;
import android.media.AudioTrack;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Lip-sync check: a white circle flashes once a second together with a beep. Use ← → until the flash and the beep
 * happen at the same moment, then OK saves it for the sound output in use (TV speakers, soundbar, Bluetooth…).
 */
public class LipSyncActivity extends Activity {

    private static final int RATE = 48000, TONE_MS = 70;
    private View dot;
    private TextView value;
    private int lateMs;
    private volatile boolean running;
    private AudioTrack track;
    private Thread writer;
    /** Frames written before the current second started (tone frames start at each multiple of RATE). */
    private long nextToneFrame;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        lateMs = LipSync.deviceMs(this);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        dot = new View(this);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(Color.WHITE);
        dot.setBackground(g);
        dot.setVisibility(View.INVISIBLE);
        root.addView(dot, new FrameLayout.LayoutParams(Ui.dp(this, 160), Ui.dp(this, 160), Gravity.CENTER));

        LinearLayout top = Ui.vertical(this);
        top.setGravity(Gravity.CENTER_HORIZONTAL);
        top.setPadding(0, Ui.dp(this, 36), 0, 0);
        TextView kicker = Ui.eyebrow(this, "Audio sync · " + LipSync.deviceName(this));
        kicker.setGravity(Gravity.CENTER);
        top.addView(kicker);
        TextView how = Ui.text(this, "Watch the circle and listen for the beep.", 18, Ui.TEXT);
        how.setGravity(Gravity.CENTER);
        how.setPadding(0, Ui.dp(this, 10), 0, 0);
        top.addView(how);
        root.addView(top, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

        LinearLayout bottom = Ui.vertical(this);
        bottom.setGravity(Gravity.CENTER_HORIZONTAL);
        bottom.setPadding(0, 0, 0, Ui.dp(this, 36));
        value = Ui.heading(this, "", 22);
        value.setGravity(Gravity.CENTER);
        bottom.addView(value);
        TextView keys = Ui.text(this, "Beep after the flash?  →        Beep before the flash?  ←        OK  save        BACK  cancel", 14, Ui.MUTED);
        keys.setGravity(Gravity.CENTER);
        keys.setPadding(0, Ui.dp(this, 10), 0, 0);
        bottom.addView(keys);
        root.addView(bottom, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        setContentView(root);
        showValue();
    }

    private void showValue() {
        value.setText(lateMs == 0 ? "No adjustment" : "Picture held back " + lateMs + " ms" + (lateMs < 0 ? " (sound held back " + -lateMs + " ms)" : ""));
    }

    // ------------------------------------------------------------ sound and flash

    @Override
    protected void onResume() {
        super.onResume();
        start();
    }

    @Override
    protected void onPause() {
        super.onPause();
        stop();
    }

    private void start() {
        int min = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        track = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
                .setAudioFormat(new AudioFormat.Builder().setSampleRate(RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(Math.max(min, RATE / 5 * 2))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build();
        running = true;
        track.play();
        // One second at a time: a short 1 kHz beep, then silence.
        short[] second = new short[RATE];
        int toneFrames = RATE * TONE_MS / 1000;
        for (int i = 0; i < toneFrames; i++) {
            double fade = Math.min(1.0, Math.min(i, toneFrames - i) / 240.0);
            second[i] = (short) (Math.sin(2 * Math.PI * 1000 * i / RATE) * 12000 * fade);
        }
        writer = new Thread(() -> {
            while (running) {
                int off = 0;
                while (running && off < second.length) {
                    int n = track.write(second, off, second.length - off);
                    if (n <= 0) break;
                    off += n;
                }
            }
        }, "lipsync-beep");
        writer.start();
        nextToneFrame = 0;
        App.MAIN.post(tick);
    }

    private void stop() {
        running = false;
        App.MAIN.removeCallbacks(tick);
        if (track != null) {
            try { track.pause(); track.flush(); track.release(); } catch (Exception ignored) { }
            track = null;
        }
    }

    private final AudioTimestamp ts = new AudioTimestamp();
    private long flashUntil;

    /** Every few ms: when the next beep is heard (by the TV's own clock) plus the adjustment, flash. */
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running || track == null) return;
            long nowNs = System.nanoTime();
            long toneAtNs;
            if (track.getTimestamp(ts)) {
                toneAtNs = ts.nanoTime + (nextToneFrame - ts.framePosition) * 1_000_000_000L / RATE;
            } else {
                long played = track.getPlaybackHeadPosition() & 0xFFFFFFFFL;
                toneAtNs = nowNs + (nextToneFrame - played) * 1_000_000_000L / RATE;
            }
            long flashAt = toneAtNs + lateMs * 1_000_000L;
            if (nowNs >= flashAt) {
                dot.setVisibility(View.VISIBLE);
                flashUntil = nowNs + 100_000_000L;
                nextToneFrame += RATE;
            } else if (nowNs > flashUntil && dot.getVisibility() == View.VISIBLE) {
                dot.setVisibility(View.INVISIBLE);
            }
            App.MAIN.postDelayed(this, 4);
        }
    };

    // ------------------------------------------------------------ keys

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            switch (e.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_RIGHT: lateMs += 10; showValue(); return true;
                case KeyEvent.KEYCODE_DPAD_LEFT: lateMs -= 10; showValue(); return true;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                    LipSync.setDeviceMs(this, lateMs);
                    android.widget.Toast.makeText(this, "Saved for " + LipSync.deviceName(this), android.widget.Toast.LENGTH_SHORT).show();
                    finish();
                    return true;
                default:
                    break;
            }
        }
        return super.dispatchKeyEvent(e);
    }
}
