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
    /** What the remote has selected (white by default; Settings → Theme → Highlight colour). */
    public static int FOCUS = Color.WHITE;
    /** Text and icons drawn on top of FOCUS: dark on a light highlight, white on a dark one. */
    public static int ON_FOCUS = Color.parseColor("#0B0B10");
    /** Text on an ACCENT fill (the main button, selected switches). */
    public static int ON_ACCENT = Color.parseColor("#0B0B10");
    /** Corner radius of buttons and pills (Eclipse's look: squared-off, not round). */
    public static final int BTN_RADIUS = 8;

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

    /** Buttons in Eclipse's look: squared-off corners and a faint edge; the highlight fills it when selected. */
    public static Drawable focusablePill(Context c, int normal, int focused, int focusStroke) {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_focused}, rounded(c, focused, focusStroke, BTN_RADIUS));
        s.addState(new int[]{android.R.attr.state_pressed}, rounded(c, focused, focusStroke, BTN_RADIUS));
        GradientDrawable n = rounded(c, normal, 0, BTN_RADIUS);
        n.setStroke(Math.max(1, dp(c, 1)), alpha(Color.WHITE, 0.10f));   // a faint edge so buttons read on any picture
        s.addState(new int[]{}, n);
        return s;
    }

    /** Small spaced capitals (Eclipse's labels): buttons, pills, section titles. */
    public static void caps(TextView t, float spacing) {
        t.setAllCaps(true);
        t.setLetterSpacing(spacing);
    }

    /** A section title in Eclipse's look: accent capitals followed by a thin line. */
    public static LinearLayout sectionLabel(Context c, String s) {
        LinearLayout row = horizontal(c);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = text(c, s.toUpperCase(java.util.Locale.ROOT), 12, ACCENT);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setLetterSpacing(0.2f);
        row.addView(t);
        View line = new View(c);
        line.setBackgroundColor(alpha(Color.WHITE, 0.12f));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, Math.max(1, dp(c, 1)), 1f);
        lp.setMargins(dp(c, 12), 0, 0, 0);
        row.addView(line, lp);
        return row;
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
                new int[]{ON_FOCUS, ON_FOCUS, normal});
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
        if (!(label instanceof android.text.Spanned)) caps(b, 0.1f);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        b.setTextColor(onFocusDark(TEXT));
        b.setFocusable(true);
        b.setBackground(focusablePill(c, alpha(Color.WHITE, 0.07f), FOCUS, 0));
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

    /**
     * True when ← from `from` would leave its row (no item to its left on the same line), so the
     * sidebar should open instead of focus jumping to something further down the screen.
     */
    public static boolean leftLeavesRow(View from, View next) {
        if (next == null) return true;
        int[] a = new int[2], b = new int[2];
        from.getLocationOnScreen(a);
        next.getLocationOnScreen(b);
        int fromMid = a[1] + from.getHeight() / 2, nextTop = b[1], nextBottom = b[1] + next.getHeight();
        return fromMid < nextTop || fromMid > nextBottom;
    }

    /** The big white Play button: a bold first line and a smaller second line (e.g. which source will play). */
    public static Button primary(Context c, int iconRes, String title, String sub, View.OnClickListener click) {
        Button b = button(c, iconRes, "", click);
        b.setAllCaps(false);
        b.setLetterSpacing(0.08f);
        setPrimaryText(c, b, title, sub);
        b.setTextColor(onFocusDark(ON_ACCENT));
        b.setCompoundDrawableTintList(onFocusDark(ON_ACCENT));
        b.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        // Eclipse's main button: filled with the accent colour.
        b.setBackground(focusablePill(c, ACCENT, FOCUS, alpha(Color.WHITE, 0.25f)));
        b.setPadding(dp(c, 22), 0, dp(c, 28), 0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(c, 56));
        lp.setMargins(0, dp(c, 4), dp(c, 12), dp(c, 4));
        b.setLayoutParams(lp);
        return b;
    }

    public static void setPrimaryText(Context c, Button b, String title, String sub) {
        android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder(title.toUpperCase(java.util.Locale.getDefault()));
        sb.setSpan(new android.text.style.StyleSpan(Typeface.BOLD), 0, sb.length(), 0);
        sb.setSpan(new android.text.style.RelativeSizeSpan(1.1f), 0, sb.length(), 0);
        if (sub != null && !sub.isEmpty()) {
            int start = sb.length();
            sb.append("\n").append(sub.toUpperCase(java.util.Locale.getDefault()));
            sb.setSpan(new android.text.style.RelativeSizeSpan(0.75f), start, sb.length(), 0);
            sb.setSpan(new android.text.style.ForegroundColorSpan(alpha(ON_ACCENT, 0.65f)), start, sb.length(), 0);
        }
        b.setText(sb);
    }

    /** A compact button with an icon beside a short label (secondary actions on the title page). */
    public static Button tile(Context c, int iconRes, String label, View.OnClickListener click) {
        Button b = new Button(c);
        b.setText(label);
        caps(b, 0.1f);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        b.setTextColor(onFocusDark(TEXT));
        b.setSingleLine(true);
        b.setEllipsize(android.text.TextUtils.TruncateAt.END);
        b.setFocusable(true);
        b.setBackground(focusablePill(c, alpha(Color.WHITE, 0.07f), FOCUS, 0));
        b.setStateListAnimator(null);
        b.setGravity(Gravity.CENTER_VERTICAL);
        b.setPadding(dp(c, 16), 0, dp(c, 18), 0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        Drawable d = c.getResources().getDrawable(iconRes, c.getTheme()).mutate();
        int sz = dp(c, 18);
        d.setBounds(0, 0, sz, sz);
        b.setCompoundDrawables(d, null, null, null);
        b.setCompoundDrawablePadding(dp(c, 9));
        b.setCompoundDrawableTintList(onFocusDark(TEXT));
        b.setOnClickListener(click);
        b.setContentDescription(label);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(c, 46));
        lp.setMargins(0, dp(c, 4), dp(c, 10), dp(c, 4));
        lp.gravity = Gravity.CENTER_VERTICAL;
        b.setLayoutParams(lp);
        return b;
    }

    /** A full-width, left-aligned, multi-line button (used for lists). */
    public static Button listButton(Context c, CharSequence label, View.OnClickListener click) {
        Button b = button(c, label, click);
        b.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        b.setBackground(focusable(c, BUTTON, FOCUS, 0));
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
        TextView t = text(c, s, 12, selected ? ACCENT : MUTED);
        caps(t, 0.12f);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setFocusable(true);
        t.setClickable(true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(c, 16), 0, dp(c, 16), 0);
        // Eclipse's choice look: the chosen one tinted and edged with the accent colour.
        StateListDrawable s1 = new StateListDrawable();
        s1.addState(new int[]{android.R.attr.state_focused}, rounded(c, FOCUS, 0, BTN_RADIUS));
        GradientDrawable n = rounded(c, selected ? alpha(ACCENT, 0.12f) : alpha(Color.WHITE, 0.06f), 0, BTN_RADIUS);
        n.setStroke(Math.max(1, dp(c, 1)), selected ? alpha(ACCENT, 0.55f) : alpha(Color.WHITE, 0.10f));
        s1.addState(new int[]{}, n);
        t.setBackground(s1);
        t.setTextColor(onFocusDark(selected ? ACCENT : MUTED));
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

    /**
     * Scrolls the nearest vertical ScrollView so the focused card's whole row is on screen
     * (a block tagged "group" wins over one tagged "row", e.g. season pills + episodes).
     */
    public static void reveal(View card) {
        View group = null, row = null;
        android.view.ViewParent p = card.getParent();
        View child = card;
        while (p instanceof View && !(p instanceof android.widget.ScrollView)) {
            View v = (View) p;
            if (row == null && "row".equals(v.getTag())) row = v;
            if (group == null && "group".equals(v.getTag())) group = v;
            child = v;
            p = v.getParent();
        }
        if (!(p instanceof android.widget.ScrollView)) return;
        android.widget.ScrollView sv = (android.widget.ScrollView) p;
        View target = group != null ? group : row;
        if (target == null) return;
        int top = 0;
        for (View v = target; v != null && v.getParent() != sv; v = (View) v.getParent()) top += v.getTop();
        int bottom = top + target.getHeight();
        int margin = dp(card.getContext(), 6);
        int y = sv.getScrollY(), h = sv.getHeight();
        if (top - margin < y || bottom + margin > y + h) sv.smoothScrollTo(0, Math.max(0, top - margin));
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
        s.addState(new int[]{android.R.attr.state_focused}, rounded(c, Color.TRANSPARENT, FOCUS, 13));
        s.addState(new int[]{}, new ColorDrawable(Color.TRANSPARENT));
        // The highlight is drawn on top of the picture, so the picture can never hide part of it.
        f.setForeground(s);
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
    /** Poster card width in dp (2:3 cards). */
    public static final int POSTER_W = 150;

    public static View poster(Context c, JSONObject item, View.OnClickListener click, Runnable onFocus) {
        LinearLayout col = vertical(c);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(dp(c, POSTER_W), ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.setMargins(0, dp(c, 6), dp(c, 14), dp(c, 6));
        col.setLayoutParams(clp);

        FrameLayout f = frame(c, click, POSTER_W);
        ImageView img = image(c, BUTTON, 12);
        f.addView(img, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(c, POSTER_W * 3 / 2)));
        ImageLoader.load(img, item.optString("poster"), dp(c, POSTER_W));
        if (!item.optString("badge").isEmpty()) f.addView(badge(c, item.optString("badge")));
        col.addView(f);

        TextView t = single(text(c, item.optString("name"), 13, DIM));
        t.setPadding(dp(c, 3), dp(c, 6), 0, 0);
        col.addView(t);

        f.setOnFocusChangeListener((v, has) -> {
            grow(1.06f).onFocusChange(v, has);
            t.setTextColor(has ? TEXT : DIM);
            if (has && onFocus != null) onFocus.run();
        });
        Ratings.gate(col, item);   // age-restricted profiles
        return col;
    }

    /** Wide 16:9 card with progress (Continue watching, episodes). */
    public static View landscape(Context c, String imageUrl, String title, String sub, String badge,
                                 double progress, float widthDp, View.OnClickListener click, Runnable onFocus) {
        return landscape(c, new String[]{imageUrl}, title, sub, badge, progress, widthDp, click, onFocus);
    }

    /** images: tried in turn until one loads (e.g. episode still, backdrop, poster). */
    public static View landscape(Context c, String[] images, String title, String sub, String badge,
                                 double progress, float widthDp, View.OnClickListener click, Runnable onFocus) {
        LinearLayout col = vertical(c);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(dp(c, widthDp), ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.setMargins(0, dp(c, 6), dp(c, 14), dp(c, 6));
        col.setLayoutParams(clp);

        FrameLayout f = frame(c, click, widthDp);
        ImageView img = image(c, BUTTON, 10);
        f.addView(img, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(c, widthDp * 9 / 16)));
        ImageLoader.loadFirst(img, dp(c, widthDp), images);
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
        LinearLayout block = vertical(c);
        block.setTag("row");
        block.setClipChildren(false);
        block.setClipToPadding(false);
        parent.addView(block);
        parent = block;
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
        HorizontalScrollView hs = new FocusScroll.Horizontal(c);
        // Room around the cards so a highlighted (slightly enlarged) card isn't cut off at the row's edges;
        // the negative margin keeps the first card lined up with the row title.
        // (The room is padding inside the row's contents: Android clips at the scroll view's own padding.)
        hs.setPadding(0, 0, 0, 0);
        LinearLayout items = new LinearLayout(c);
        items.setOrientation(LinearLayout.HORIZONTAL);
        items.setClipChildren(false);
        items.setClipToPadding(false);   // this was what cut off the left of an enlarged card
        items.setPadding(dp(c, 14), dp(c, 10), dp(c, 48), dp(c, 10));
        hs.addView(items);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hlp.setMargins(-dp(c, 14), -dp(c, 6), 0, -dp(c, 6));
        parent.addView(hs, hlp);
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
        android.widget.ScrollView scroll = new FocusScroll.Vertical(a);
        scroll.setBackgroundColor(BG);
        LinearLayout content = vertical(a);
        content.setPadding(dp(a, 56), dp(a, 36), dp(a, 56), dp(a, 48));
        // Rows reach a little into the page's margin so a highlighted first card isn't cut off on the left.
        content.setClipChildren(false);
        content.setClipToPadding(false);
        scroll.addView(content);
        a.setContentView(scroll);
        if (title != null) {
            TextView h = heading(a, title, 30);
            h.setPadding(0, 0, 0, dp(a, 8));
            content.addView(h);
        }
        return content;
    }

    /** True when a good part of the picture is see-through (a logo on a clear background, safe to tint white). */
    public static boolean hasTransparency(android.graphics.Bitmap b) {
        if (b == null || !b.hasAlpha() || b.getWidth() < 2 || b.getHeight() < 2) return false;
        int clear = 0, n = 0;
        for (int y = 0; y < 12; y++) {
            for (int x = 0; x < 12; x++) {
                int p = b.getPixel(x * (b.getWidth() - 1) / 11, y * (b.getHeight() - 1) / 11);
                n++;
                if (Color.alpha(p) < 40) clear++;
            }
        }
        return clear * 100 / n >= 12;
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
