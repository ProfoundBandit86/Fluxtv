package com.fluxtv.app;

import android.content.Context;
import android.graphics.Color;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Date;

/**
 * The weather + clock pill in the top right corner of Flux's screens: "☁ 48° | 8:01 PM".
 * Without a weather place (Settings → Home screen) it shows just the time.
 */
public class ClockBadge extends LinearLayout {

    public static final String TAG = "fluxClockBadge";

    private final ImageView icon;
    private final TextView temp, time;
    private final View divider;
    private static long lastTry;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            update();
            postDelayed(this, 15_000);
        }
    };

    public ClockBadge(Context c) {
        super(c);
        setTag(TAG);
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        setFocusable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        setBackground(Ui.rounded(c, Ui.alpha(Color.parseColor("#06080C"), 0.62f), Ui.alpha(Color.WHITE, 0.14f), 999));
        setPadding(Ui.dp(c, 14), 0, Ui.dp(c, 16), 0);
        setMinimumHeight(Ui.dp(c, 36));
        icon = new ImageView(c);
        icon.setImageTintList(android.content.res.ColorStateList.valueOf(Color.WHITE));
        addView(icon, new LayoutParams(Ui.dp(c, 18), Ui.dp(c, 18)));
        temp = Ui.heading(c, "", 14);
        temp.setTextColor(Color.WHITE);
        temp.setPadding(Ui.dp(c, 7), 0, 0, 0);
        addView(temp);
        divider = new View(c);
        divider.setBackgroundColor(Ui.alpha(Color.WHITE, 0.3f));
        LayoutParams dl = new LayoutParams(Ui.dp(c, 1), Ui.dp(c, 16));
        dl.setMargins(Ui.dp(c, 10), 0, Ui.dp(c, 10), 0);
        addView(divider, dl);
        time = Ui.heading(c, "", 14);
        time.setTextColor(Color.WHITE);
        addView(time);
        showWeather(null);
    }

    /** FrameLayout params for the usual spot in the top right corner (the same on every screen). */
    public static android.widget.FrameLayout.LayoutParams topRight(Context c) {
        android.widget.FrameLayout.LayoutParams p = new android.widget.FrameLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, Ui.dp(c, 36), Gravity.TOP | Gravity.END);
        p.setMargins(0, Ui.dp(c, 20), Ui.dp(c, 32), 0);
        return p;
    }

    /** Adds a badge to the activity's window (on top of its content) unless one is there already. */
    public static void attach(android.app.Activity a) {
        android.view.ViewGroup decor = (android.view.ViewGroup) a.getWindow().getDecorView();
        if (decor.findViewWithTag(TAG) != null || !(decor instanceof android.widget.FrameLayout)) return;
        decor.addView(new ClockBadge(a), topRight(a));
    }

    private final android.view.ViewTreeObserver.OnScrollChangedListener onScroll = this::checkCovered;
    private float fadeTarget = 1f;

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        getViewTreeObserver().addOnScrollChangedListener(onScroll);
        removeCallbacks(tick);
        tick.run();
        refreshWeather(false);
    }

    @Override
    protected void onDetachedFromWindow() {
        getViewTreeObserver().removeOnScrollChangedListener(onScroll);
        removeCallbacks(tick);
        super.onDetachedFromWindow();
    }

    /** Fades out while a page that runs under the badge is scrolled down (title pages, Settings), so it never sits on buttons. */
    private void checkCovered() {
        View content = getRootView().findViewById(android.R.id.content);
        float target = scrolledUnder(content) ? 0f : 1f;
        if (target == fadeTarget) return;
        fadeTarget = target;
        animate().alpha(target).setDuration(160).start();
    }

    private boolean scrolledUnder(View v) {
        if (v == null || v == this || v.getVisibility() != VISIBLE) return false;
        if (v instanceof android.widget.ScrollView && v.getScrollY() > Ui.dp(getContext(), 24)) {
            int[] a = new int[2], b = new int[2];
            v.getLocationOnScreen(a);
            getLocationOnScreen(b);
            if (a[1] < b[1] + getHeight() && a[0] + v.getWidth() > b[0] && a[0] < b[0] + getWidth()) return true;
        }
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) if (scrolledUnder(g.getChildAt(i))) return true;
        }
        return false;
    }

    private void update() {
        time.setText(DateFormat.getTimeFormat(getContext()).format(new Date()));
        if (System.currentTimeMillis() - lastTry > 20 * 60 * 1000) refreshWeather(true);
    }

    /** Re-reads the weather (cached for 20 minutes unless the place changed). */
    public void refreshWeather(boolean force) {
        if (Weather.place().isEmpty()) { showWeather(null); return; }
        if (force) lastTry = System.currentTimeMillis();
        App.async(Weather::now, this::showWeather, e -> { });
    }

    private void showWeather(Weather.Now n) {
        boolean has = n != null;
        icon.setVisibility(has ? VISIBLE : GONE);
        temp.setVisibility(has ? VISIBLE : GONE);
        divider.setVisibility(has ? VISIBLE : GONE);
        if (!has) {
            setContentDescription(null);
            return;
        }
        icon.setImageResource(Weather.icon(n));
        temp.setText(n.temp + "°");
        setContentDescription(Weather.describe(n) + (n.place.isEmpty() ? "" : " in " + n.place));
    }
}
