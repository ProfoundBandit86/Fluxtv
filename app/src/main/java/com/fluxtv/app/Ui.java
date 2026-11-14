package com.fluxtv.app;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.PorterDuff;
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
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

/** Shared look & feel for the D-pad driven TV interface. Colours come from {@link Theme}. */
public final class Ui {

    // Set by Theme.apply()
    public static int BG = Color.parseColor("#0B0B10");
    public static int PANEL = Color.parseColor("#111118");
    public static int BUTTON = Color.parseColor("#1E1E2A");
    public static int BORDER = Color.parseColor("#22222E");
    public static int ACCENT = Color.parseColor("#8B7CFF");

    public static final int TEXT = Color.parseColor("#F4F4F7");
    public static final int MUTED = Color.parseColor("#A9A9B8");
    public static final int DIM = Color.parseColor("#8E8EA0");
    public static final int GOOD = Color.parseColor("#4ADE80");
    public static final int WARN = Color.parseColor("#F5B54A");
    public static final int GLASS = Color.parseColor("#1AFFFFFF");   // 10% white

    private Ui() { }

    public static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics()));
    }

    public static int alpha(int color, float a) {
        return (Math.round(a * 255) << 24) | (color & 0x00FFFFFF);
    }

    // ------------------------------------------------------------ text

    public static TextView text(Context c, CharSequence s, float sp, int color) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        t.setLineSpacing(0, 1.15f);
        return t;
    }

    public static TextView heading(Context c, CharSequence s, float sp) {
        TextView t = text(c, s, sp, TEXT);
        t.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        return t;
    }

    public static TextView eyebrow(Context c, CharSequence s) {
        TextView t = text(c, s.toString().toUpperCase(), 11, ACCENT);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setLetterSpacing(0.1f);
        return t;
    }

    public static TextView single(TextView t) {
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }

    // ------------------------------------------------------------ drawables

    public static GradientDrawable rounded(Context c, int color, int strokeColor) {
        return rounded(c, color, strokeColor, 12);
    }

    public static GradientDrawable rounded(Context c, int color, int strokeColor, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(c, radiusDp));
        if (strokeColor != 0) d.setStroke(dp(c, 2.5f), strokeColor);
        return d;
    }

    public static Drawable focusable(Context c, int normal, int focused, int focusStroke) {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_focused}, rounded(c, focused, focusStroke));
        s.addState(new int[]{android.R.attr.state_pressed}, rounded(c, focused, focusStroke));
        s.addState(new int[]{}, normal == Color.TRANSPARENT && focusStroke != 0
                ? new ColorDrawable(Color.TRANSPARENT) : rounded(c, normal, 0));
        return s;
    }

    /** Text/icon colour that turns dark when the (white) control is focused. */
    public static ColorStateList onFocusDark(int normal) {
        return new ColorStateList(new int[][]{{android.R.attr.state_focused}, {android.R.attr.state_pressed}, {}},
                new int[]{BG, BG, normal});
    }

    /** Vertical scrim from a colour to transparent (direction is where the colour sits). */
    public static GradientDrawable scrim(GradientDrawable.Orientation o, int color, float strength) {
        return new GradientDrawable(o, new int[]{alpha(color, strength), alpha(color, strength * 0.6f), alpha(color, 0f)});
    }

    // ------------------------------------------------------------ controls

    public static ImageView icon(Context c, int res, float sizeDp, int color) {
        ImageView v = new ImageView(c);
        v.setImageResource(res);
        v.setColorFilter(color, PorterDuff.Mode.SRC_IN);
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(c, sizeDp), dp(c, sizeDp)));
        return v;
    }

    /** Standard TV button: translucent, turns white with dark text when focused. */
    public static Button button(Context c, CharSequence label, View.OnClickListener click) {
        return button(c, 0, label, click);
    }

    public static Button button(Context c, int iconRes, CharSequence label, View.OnClickListener click) {
        Button b = new Button(c);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        b.setTextColor(onFocusDark(TEXT));
        b.setFocusable(true);
        b.setBackground(focusable(c, GLASS, TEXT, 0));
        b.setPadding(dp(c, 20), 0, dp(c, 20), 0);
        b.setMinHeight(dp(c, 46));
        b.setMinimumHeight(dp(c, 46));
        b.setStateListAnimator(null);
        if (iconRes != 0) {
            Drawable d = c.getResources().getDrawable(iconRes, c.getTheme()).mutate();
            int s = dp(c, 18);
            d.setBounds(0, 0, s, s);
            b.setCompoundDrawables(d, null, null, null);
            b.setCompoundDrawablePadding(dp(c, 10));
            b.setCompoundDrawableTintList(onFocusDark(TEXT));
        }
        b.setOnClickListener(click);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(c, 46));
        lp.setMargins(0, dp(c, 4), dp(c, 10), dp(c, 4));
        b.setLayoutParams(lp);
        return b;
    }

    /** A full-width, left-aligned, multi-line button (used for lists). */
    public static Button listButton(Context c, CharSequence label, View.OnClickListener click) {
        Button b = button(c, label, click);
        b.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        b.setBackground(focusable(c, BUTTON, TEXT, 0));
        b.setPadding(dp(c, 18), dp(c, 12), dp(c, 18), dp(c, 12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(c, 4), 0, dp(c, 4));
        b.setLayoutParams(lp);
        return b;
    }

    /** Small rounded label, e.g. "4K", "Plex". */
    public static TextView chip(Context c, String s, int bg, int fg) {
        TextView t = text(c, s, 11, fg);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setBackground(rounded(c, bg, 0, 6));
        t.setPadding(dp(c, 7), dp(c, 3), dp(c, 7), dp(c, 3));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, dp(c, 6), 0);
        t.setLayoutParams(lp);
        return t;
    }

    /** A focusable pill that can be selected (season and filter pickers). */
    public static TextView pill(Context c, String s, boolean selected, View.OnClickListener click) {
        TextView t = text(c, s, 14, selected ? BG : MUTED);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setFocusable(true);
        t.setClickable(true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(c, 16), 0, dp(c, 16), 0);
        StateListDrawable s1 = new StateListDrawable();
        s1.addState(new int[]{android.R.attr.state_focused}, rounded(c, TEXT, ACCENT, 999));
        s1.addState(new int[]{}, rounded(c, selected ? TEXT : GLASS, 0, 999));
        t.setBackground(s1);
        if (!selected) t.setTextColor(onFocusDark(MUTED));
        t.setOnClickListener(click);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(c, 36));
        lp.setMargins(0, dp(c, 4), dp(c, 8), dp(c, 4));
        t.setLayoutParams(lp);
        return t;
    }

    // ------------------------------------------------------------ cards

    private static View.OnFocusChangeListener grow(float scale) {
        return (v, has) -> v.animate().scaleX(has ? scale : 1f).scaleY(has ? scale : 1f).setDuration(140).start();
    }

    /** Rounded image that clips its picture to the corners. */
    public static ImageView image(Context c, int bg, float radiusDp) {
        ImageView img = new ImageView(c);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        img.setBackground(rounded(c, bg, 0, radiusDp));
        img.setClipToOutline(true);
        return img;
    }

    private static FrameLayout frame(Context c, View.OnClickListener click, float widthDp) {
        FrameLayout f = new FrameLayout(c);
        f.setFocusable(true);
        f.setClickable(true);
        f.setOnClickListener(click);
        int pad = dp(c, 3);
        f.setPadding(pad, pad, pad, pad);
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_focused}, rounded(c, Color.TRANSPARENT, TEXT, 13));
        s.addState(new int[]{}, new ColorDrawable(Color.TRANSPARENT));
        f.setBackground(s);
        return f;
    }

    private static TextView badge(Context c, String s) {
        TextView b = chip(c, s, alpha(Color.BLACK, 0.72f), TEXT);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START);
        lp.setMargins(dp(c, 8), dp(c, 8), 0, 0);
        b.setLayoutParams(lp);
        return b;
    }

    private static View progressBar(Context c, double fraction) {
        float f = (float) Math.max(0.02, Math.min(1, fraction));
        LinearLayout bar = horizontal(c);
        bar.setBackgroundColor(alpha(Color.WHITE, 0.2f));
        View fill = new View(c);
        fill.setBackgroundColor(ACCENT);
        bar.addView(fill, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, f));
        if (f < 1) bar.addView(new View(c), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1 - f));
        bar.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(c, 4), Gravity.BOTTOM));
        return bar;
    }

    /**
     * Poster card (2:3). Item fields used: name, poster, badge.
     * onFocus runs when the card gains focus (used for the home screen's hero).
     */
    public static View poster(Context c, JSONObject item, View.OnClickListener click, Runnable onFocus) {
        LinearLayout col = vertical(c);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(dp(c, 116), ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.setMargins(0, dp(c, 6), dp(c, 12), dp(c, 6));
        col.setLayoutParams(clp);

        FrameLayout f = frame(c, click, 116);
        ImageView img = image(c, BUTTON, 10);
        f.addView(img, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(c, 168)));
        ImageLoader.load(img, item.optString("poster"), dp(c, 116));
        if (!item.optString("badge").isEmpty()) f.addView(badge(c, item.optString("badge")));
        col.addView(f);

        TextView t = single(text(c, item.optString("name"), 12, DIM));
        t.setPadding(dp(c, 3), dp(c, 6), 0, 0);
        col.addView(t);

        f.setOnFocusChangeListener((v, has) -> {
            grow(1.06f).onFocusChange(v, has);
            t.setTextColor(has ? TEXT : DIM);
            if (has && onFocus != null) onFocus.run();
        });
        return col;
    }

    /** Wide 16:9 card with progress (Continue watching, episodes). */
    public static View landscape(Context c, String imageUrl, String title, String sub, String badge,
                                 double progress, float widthDp, View.OnClickListener click, Runnable onFocus) {
        LinearLayout col = vertical(c);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(dp(c, widthDp), ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.setMargins(0, dp(c, 6), dp(c, 14), dp(c, 6));
        col.setLayoutParams(clp);

        FrameLayout f = frame(c, click, widthDp);
        ImageView img = image(c, BUTTON, 10);
        f.addView(img, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(c, widthDp * 9 / 16)));
        ImageLoader.load(img, imageUrl, dp(c, widthDp));
        if (badge != null && !badge.isEmpty()) f.addView(badge(c, badge));
        if (progress > 0) {
            View bar = progressBar(c, progress);
            FrameLayout inner = new FrameLayout(c);
            inner.setClipToOutline(true);
            inner.setBackground(rounded(c, Color.TRANSPARENT, 0, 10));
            inner.addView(bar);
            f.addView(inner, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(c, widthDp * 9 / 16)));
        }
        col.addView(f);

        TextView t = single(text(c, title, 13, TEXT));
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setPadding(dp(c, 3), dp(c, 7), 0, 0);
        col.addView(t);
        if (sub != null && !sub.isEmpty()) {
            TextView s = single(text(c, sub, 12, MUTED));
            s.setPadding(dp(c, 3), dp(c, 3), 0, 0);
            col.addView(s);
        }
        f.setOnFocusChangeListener((v, has) -> {
            grow(1.04f).onFocusChange(v, has);
            if (has && onFocus != null) onFocus.run();
        });
        return col;
    }

    /** A titled horizontally-scrolling row. Add items to the returned container. */
    public static LinearLayout row(Context c, ViewGroup parent, String title) {
        return row(c, parent, title, null);
    }

    public static LinearLayout row(Context c, ViewGroup parent, String title, String note) {
        if (title != null && !title.isEmpty()) {
            LinearLayout h = horizontal(c);
            h.setGravity(Gravity.BOTTOM);
            h.setPadding(0, dp(c, 18), 0, dp(c, 4));
            h.addView(heading(c, title, 17));
            if (note != null && !note.isEmpty()) {
                TextView n = text(c, note, 12, DIM);
                n.setPadding(dp(c, 10), 0, 0, dp(c, 1));
                h.addView(n);
            }
            parent.addView(h);
        }
        HorizontalScrollView hs = new HorizontalScrollView(c);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setClipToPadding(false);
        hs.setClipChildren(false);
        hs.setPadding(dp(c, 4), 0, dp(c, 48), 0);
        LinearLayout items = new LinearLayout(c);
        items.setOrientation(LinearLayout.HORIZONTAL);
        items.setClipChildren(false);
        items.setPadding(0, dp(c, 4), 0, dp(c, 4));
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

    /** Standard scrolling page for the simpler screens (settings, add-ons, servers…). */
    public static LinearLayout page(android.app.Activity a, String title) {
        android.widget.ScrollView scroll = new android.widget.ScrollView(a);
        scroll.setBackgroundColor(BG);
        LinearLayout content = vertical(a);
        content.setPadding(dp(a, 56), dp(a, 36), dp(a, 56), dp(a, 48));
        scroll.addView(content);
        a.setContentView(scroll);
        if (title != null) {
            TextView h = heading(a, title, 30);
            h.setPadding(0, 0, 0, dp(a, 8));
            content.addView(h);
        }
        return content;
    }

    public static String time(long ms) {
        long s = ms / 1000;
        long h = s / 3600, m = (s % 3600) / 60, sec = s % 60;
        return h > 0 ? String.format(java.util.Locale.ROOT, "%d:%02d:%02d", h, m, sec)
                : String.format(java.util.Locale.ROOT, "%d:%02d", m, sec);
    }

    /** "1 h 12 min left" / "24 min left" */
    public static String left(long posMs, long durMs) {
        long min = Math.max(1, (durMs - posMs) / 60000);
        return min >= 60 ? (min / 60) + " h " + (min % 60) + " min left" : min + " min left";
    }
}
