package com.fluxtv.app;

import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.FocusFinder;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.animation.DecelerateInterpolator;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Base for Flux's screens: adds the same sidebar as Home. Press ← at the left edge of a screen to open it.
 * Subclasses just call setContentView as usual.
 */
public abstract class FluxActivity extends Activity {

    private FrameLayout frame;
    private ViewGroup content;
    private ScrollView panel;
    private LinearLayout menu;
    private View dim;
    private boolean open;
    private View lastFocus;
    private final List<View> items = new ArrayList<>();

    /** Which sidebar entry this screen belongs to (highlighted), or null. */
    protected String sidebarKey() {
        return null;
    }

    @Override
    public void setContentView(View view) {
        open = false;
        frame = new FrameLayout(this);
        content = view instanceof ViewGroup ? (ViewGroup) view : null;
        frame.addView(view, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        dim = new View(this);
        dim.setBackgroundColor(Ui.alpha(Color.BLACK, 0.55f));
        dim.setAlpha(0f);
        dim.setVisibility(View.GONE);
        frame.addView(dim, new FrameLayout.LayoutParams(-1, -1));
        buildMenu();
        frame.addView(panel, new FrameLayout.LayoutParams(Ui.dp(this, 250), ViewGroup.LayoutParams.MATCH_PARENT));
        super.setContentView(frame);
    }

    // ------------------------------------------------------------ building

    private void buildMenu() {
        items.clear();
        panel = new FocusScroll.Vertical(this);
        panel.setFillViewport(true);
        panel.setFocusable(false);
        panel.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{Ui.PANEL, Ui.alpha(Ui.PANEL, 0.97f)}));
        panel.setElevation(Ui.dp(this, 12));
        panel.setTranslationX(-Ui.dp(this, 250));
        panel.setVisibility(View.INVISIBLE);   // closed: hidden entirely, so its shadow doesn't show on the left edge
        menu = Ui.vertical(this);
        menu.setPadding(Ui.dp(this, 16), Ui.dp(this, 26), Ui.dp(this, 16), Ui.dp(this, 20));
        menu.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        panel.addView(menu, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout profile = row();
        String name = Store.profile();
        TextView avatar = Ui.heading(this, name.isEmpty() ? "?" : name.substring(0, 1).toUpperCase(), 13);
        avatar.setGravity(Gravity.CENTER);
        avatar.setBackground(Ui.rounded(this, Ui.ACCENT, 0, 999));
        avatar.setTextColor(Ui.BG);
        profile.addView(avatar, new LinearLayout.LayoutParams(Ui.dp(this, 26), Ui.dp(this, 26)));
        profile.addView(label(name));
        profile.setOnClickListener(v -> { close(false); go(new Intent(this, ProfilesActivity.class)); });
        menu.addView(profile);
        items.add(profile);
        menu.addView(new View(this), new LinearLayout.LayoutParams(1, Ui.dp(this, 14)));

        item(R.drawable.ic_search, "Search", "search", () -> go(new Intent(this, SearchActivity.class).putExtra("voice", true)));
        item(R.drawable.ic_home, "Home", "home", () -> home("home"));
        item(R.drawable.ic_movie, "Movies", "movie", () -> home("movie"));
        item(R.drawable.ic_tv, "TV Shows", "series", () -> home("series"));
        if (!Ratings.restricted()) item(R.drawable.ic_live, "Live TV", "live", () -> go(new Intent(this, LiveActivity.class)));
        if (!Ratings.restricted() && !Iptv.sources().isEmpty()) item(R.drawable.ic_sports, "Sports", "sports", () -> go(new Intent(this, SportsActivity.class)));
        item(R.drawable.ic_heart, "Favorites", "favorites", () -> home("favorites"));
        item(R.drawable.ic_apps, "Apps", "apps", () -> go(new Intent(this, AppsActivity.class)));
        if (SmartHome.configured()) item(R.drawable.ic_bulb, "Smart home", "smarthome", () -> { close(false); startActivity(new Intent(this, SmartPanelActivity.class)); });
        if (Arr.anyConfigured()) {
            item(R.drawable.ic_download, "Downloads", "downloads", () -> go(new Intent(this, DownloadsActivity.class)));
        }
        menu.addView(new View(this), new LinearLayout.LayoutParams(1, 0, 1f));
        item(R.drawable.ic_settings, "Settings", "settings", () -> go(new Intent(this, SettingsActivity.class)));

    }

    private void item(int icon, String text, String key, Runnable action) {
        LinearLayout row = row();
        ImageView ic = new ImageView(this);
        ic.setImageResource(icon);
        ic.setImageTintList(new ColorStateList(new int[][]{{android.R.attr.state_focused}, {android.R.attr.state_selected}, {}},
                new int[]{Ui.BG, Ui.ACCENT, Ui.MUTED}));
        ic.setImageTintMode(PorterDuff.Mode.SRC_IN);
        ic.setDuplicateParentStateEnabled(true);
        row.addView(ic, new LinearLayout.LayoutParams(Ui.dp(this, 20), Ui.dp(this, 20)));
        row.addView(label(text));
        row.setTag(key);
        row.setSelected(key.equals(sidebarKey()));
        row.setOnClickListener(v -> {
            if (key.equals(sidebarKey())) { close(true); return; }
            close(false);
            action.run();
        });
        menu.addView(row);
        items.add(row);
    }

    private LinearLayout row() {
        LinearLayout item = Ui.horizontal(this);
        item.setGravity(Gravity.CENTER_VERTICAL);
        item.setFocusable(true);
        item.setClickable(true);
        item.setPadding(Ui.dp(this, 14), 0, Ui.dp(this, 14), 0);
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_focused}, Ui.rounded(this, Ui.TEXT, 0, 12));
        s.addState(new int[]{android.R.attr.state_selected}, Ui.rounded(this, Ui.BUTTON, 0, 12));
        s.addState(new int[]{}, Ui.rounded(this, Color.TRANSPARENT, 0, 12));
        item.setBackground(s);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 44));
        lp.setMargins(0, Ui.dp(this, 2), 0, Ui.dp(this, 2));
        item.setLayoutParams(lp);
        return item;
    }

    private TextView label(String s) {
        TextView t = Ui.single(Ui.text(this, s, 15, Ui.MUTED));
        t.setTextColor(new ColorStateList(new int[][]{{android.R.attr.state_focused}, {android.R.attr.state_selected}, {}},
                new int[]{Ui.BG, Ui.TEXT, Ui.MUTED}));
        t.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        t.setPadding(Ui.dp(this, 14), 0, 0, 0);
        t.setDuplicateParentStateEnabled(true);
        return t;
    }

    private void go(Intent i) {
        startActivity(i);
    }

    /** Home, Movies, TV Shows and Favorites live on the Home screen. */
    private void home(String section) {
        startActivity(new Intent(this, MainActivity.class).putExtra("section", section)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
    }

    // ------------------------------------------------------------ open / close

    protected boolean sidebarOpen() {
        return open;
    }

    protected void openSidebar() {
        if (open || panel == null) return;
        open = true;
        lastFocus = getCurrentFocus();
        menu.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        if (content != null) content.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        panel.setVisibility(View.VISIBLE);
        panel.animate().translationX(0).setDuration(220).setInterpolator(new DecelerateInterpolator()).start();
        dim.setVisibility(View.VISIBLE);
        dim.animate().alpha(1f).setDuration(220).start();
        View target = sidebarKey() == null ? null : menu.findViewWithTag(sidebarKey());
        (target != null ? target : items.get(Math.min(1, items.size() - 1))).requestFocus();   // Search, under the profile
    }

    protected void close(boolean restoreFocus) {
        if (!open) return;
        open = false;
        panel.animate().translationX(-Ui.dp(this, 250)).setDuration(200).setInterpolator(new DecelerateInterpolator())
                .withEndAction(() -> { if (!open) panel.setVisibility(View.INVISIBLE); }).start();
        dim.animate().alpha(0f).setDuration(200).withEndAction(() -> dim.setVisibility(View.GONE)).start();
        menu.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        if (content != null) content.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        if (restoreFocus) {
            if (lastFocus != null && lastFocus.isAttachedToWindow() && lastFocus.requestFocus()) return;
            if (content != null) content.requestFocus(View.FOCUS_DOWN);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Rebuild if the menu's contents changed (profile, colours, Downloads set up) while away.
        if (panel != null && !open) {
            boolean hasDownloads = menu.findViewWithTag("downloads") != null;
            boolean hasLive = menu.findViewWithTag("live") != null;
            boolean hasSmart = menu.findViewWithTag("smarthome") != null;
            if (hasDownloads != Arr.anyConfigured() || themeVersion != Theme.version() || hasLive == Ratings.restricted()
                    || hasSmart != SmartHome.configured()) {
                frame.removeView(panel);
                buildMenu();
                frame.addView(panel, new FrameLayout.LayoutParams(Ui.dp(this, 250), ViewGroup.LayoutParams.MATCH_PARENT));
            }
        }
        themeVersion = Theme.version();
    }

    private int themeVersion = Theme.version();

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (panel != null && e.getAction() == KeyEvent.ACTION_DOWN) {
            int k = e.getKeyCode();
            if (open) {
                if (k == KeyEvent.KEYCODE_DPAD_RIGHT || k == KeyEvent.KEYCODE_BACK) {
                    close(true);
                    return true;
                }
                if (k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_DPAD_DOWN) {
                    int at = items.indexOf(getCurrentFocus());
                    int next = Math.max(0, Math.min(items.size() - 1, at + (k == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1)));
                    items.get(at < 0 ? 0 : next).requestFocus();
                    return true;
                }
                if (k == KeyEvent.KEYCODE_DPAD_LEFT) return true;
            } else if (k == KeyEvent.KEYCODE_DPAD_LEFT && atLeftEdge()) {
                openSidebar();
                return true;
            }
        } else if (open && e.getAction() == KeyEvent.ACTION_UP && e.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    /** True when ← has nowhere to go on this screen (so it should open the sidebar). */
    private boolean atLeftEdge() {
        View f = getCurrentFocus();
        if (content == null) return false;
        if (f == null) return true;
        if (f instanceof EditText) {
            EditText t = (EditText) f;
            if (t.getSelectionStart() > 0) return false;   // ← moves the text cursor
        }
        boolean inside = false;
        for (ViewParent p = f.getParent(); p != null; p = p.getParent()) {
            if (p == content) { inside = true; break; }
        }
        if (!inside) return false;
        try {
            return Ui.leftLeavesRow(f, FocusFinder.getInstance().findNextFocus(content, f, View.FOCUS_LEFT));
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }
}
