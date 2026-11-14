package com.fluxtv.app;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Shared look & feel for the D-pad driven TV interface. */
public final class Ui {

    public static final int BG = Color.parseColor("#0F0C18");
    public static final int PANEL = Color.parseColor("#17132A");
    public static final int BUTTON = Color.parseColor("#2A2340");
    public static final int ACCENT = Color.parseColor("#7B5CFF");
    public static final int TEXT = Color.WHITE;
    public static final int MUTED = Color.parseColor("#A59FC0");

    private Ui() { }

    public static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics()));
    }

    public static TextView text(Context c, CharSequence s, float sp, int color) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        return t;
    }

    public static TextView heading(Context c, CharSequence s, float sp) {
        TextView t = text(c, s, sp, TEXT);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    public static GradientDrawable rounded(Context c, int color, int strokeColor) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(c, 10));
        if (strokeColor != 0) d.setStroke(dp(c, 3), strokeColor);
        return d;
    }

    public static Drawable focusable(Context c, int normal, int focused, int focusStroke) {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_focused}, rounded(c, focused, focusStroke));
        s.addState(new int[]{android.R.attr.state_pressed}, rounded(c, focused, focusStroke));
        s.addState(new int[]{}, normal == Color.TRANSPARENT ? new ColorDrawable(Color.TRANSPARENT) : rounded(c, normal, 0));
        return s;
    }

    public static Button button(Context c, CharSequence label, View.OnClickListener click) {
        Button b = new Button(c);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        b.setTextColor(TEXT);
        b.setFocusable(true);
        b.setBackground(focusable(c, BUTTON, ACCENT, 0));
        b.setPadding(dp(c, 20), dp(c, 8), dp(c, 20), dp(c, 8));
        b.setOnClickListener(click);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(c, 6), dp(c, 6), dp(c, 6), dp(c, 6));
        b.setLayoutParams(lp);
        b.setMinHeight(dp(c, 52));
        return b;
    }

    /** A full-width, left-aligned, multi-line button (used for lists). */
    public static Button listButton(Context c, CharSequence label, View.OnClickListener click) {
        Button b = button(c, label, click);
        b.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        b.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        ((LinearLayout.LayoutParams) b.getLayoutParams()).setMargins(0, dp(c, 5), 0, dp(c, 5));
        return b;
    }

    /** Poster card with title underneath; grows when focused. */
    public static View poster(Context c, String title, String imageUrl, View.OnClickListener click) {
        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setFocusable(true);
        card.setClickable(true);
        card.setOnClickListener(click);
        int pad = dp(c, 4);
        card.setPadding(pad, pad, pad, pad);
        card.setBackground(focusable(c, Color.TRANSPARENT, Color.TRANSPARENT, Color.WHITE));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(c, 148), ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(c, 6), dp(c, 10), dp(c, 6), dp(c, 10));
        card.setLayoutParams(lp);

        ImageView img = new ImageView(c);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        img.setBackgroundColor(BUTTON);
        card.addView(img, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(c, 210)));
        ImageLoader.load(img, imageUrl, dp(c, 148));

        TextView t = text(c, title, 14, MUTED);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setPadding(0, dp(c, 6), 0, 0);
        card.addView(t);

        card.setOnFocusChangeListener((v, has) -> {
            v.animate().scaleX(has ? 1.07f : 1f).scaleY(has ? 1.07f : 1f).setDuration(120).start();
            t.setTextColor(has ? TEXT : MUTED);
        });
        return card;
    }

    /** A titled horizontally-scrolling row. Add items to the returned container. */
    public static LinearLayout row(Context c, ViewGroup parent, String title) {
        TextView h = heading(c, title, 20);
        h.setPadding(dp(c, 6), dp(c, 18), 0, 0);
        parent.addView(h);
        HorizontalScrollView hs = new HorizontalScrollView(c);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setClipToPadding(false);
        hs.setClipChildren(false);
        LinearLayout items = new LinearLayout(c);
        items.setOrientation(LinearLayout.HORIZONTAL);
        items.setClipChildren(false);
        items.setPadding(dp(c, 4), 0, dp(c, 24), 0);
        hs.addView(items);
        parent.addView(hs);
        return items;
    }

    public static LinearLayout vertical(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    public static LinearLayout horizontal(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        return l;
    }

    public static String time(long ms) {
        long s = ms / 1000;
        long h = s / 3600, m = (s % 3600) / 60, sec = s % 60;
        return h > 0 ? String.format(java.util.Locale.ROOT, "%d:%02d:%02d", h, m, sec)
                : String.format(java.util.Locale.ROOT, "%d:%02d", m, sec);
    }
}
