package com.fluxtv.app;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * The left dock, the same on every main screen (Home, Movies, TV Shows, Live TV, Sports, Favorites, Apps, Search).
 * Closed it is a slim strip of icons. ← at the left edge glides it wider: the icons stay where they are and the
 * names slide out beside them, while the screen behind slides right by the same amount.
 */
final class Dock {

    /** Closed width, open width (dp). */
    static final int RAIL_DP = 38, OPEN_DP = 220;

    /** Keys in order (Settings → Home screen → Left dock lists these). Settings is always at the bottom. */
    static final String[] ITEMS = {"search", "home", "movie", "series", "live", "sports", "favorites", "apps"};
    static final String[] NAMES = {"Search", "Home", "Movies", "TV Shows", "Live TV", "Sports", "Favorites", "Apps"};
    private static final int[] ICONS = {R.drawable.ic_search, R.drawable.ic_home, R.drawable.ic_movie, R.drawable.ic_tv,
            R.drawable.ic_live, R.drawable.ic_sports, R.drawable.ic_heart, R.drawable.ic_apps};

    interface Host {
        /** The entry that is this screen (highlighted). */
        String dockKey();
        /** An entry was picked. "profile" = the profile at the top. */
        void dockGo(String key);
    }

    final FrameLayout view;
    private final Activity a;
    private final Host host;
    private final View content;
    private final LinearLayout list;
    private final List<View> rows = new ArrayList<>();
    private TextView avatar, profileName;
    private boolean open;
    private View lastFocus;
    private ValueAnimator anim;

    /** content: the screen beside the dock (slides right while the dock is open). */
    Dock(Activity a, Host host, View content) {
        this.a = a;
        this.host = host;
        this.content = content;
        view = new FrameLayout(a);
        view.setClipChildren(true);
        view.setFocusable(false);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Ui.alpha(Ui.BG, 0.94f));
        GradientDrawable edge = new GradientDrawable();   // a thin line down the right edge
        edge.setColor(Ui.alpha(Color.WHITE, 0.07f));
        LayerDrawable layers = new LayerDrawable(new android.graphics.drawable.Drawable[]{bg, edge});
        layers.setLayerGravity(1, Gravity.END);
        layers.setLayerWidth(1, Math.max(1, Ui.dp(a, 1)));
        view.setBackground(layers);
        view.setContentDescription("Menu. Press left to open it.");

        list = Ui.vertical(a);
        list.setPadding(Ui.dp(a, 3), Ui.dp(a, 16), Ui.dp(a, 3), Ui.dp(a, 10));
        list.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        view.addView(list, new FrameLayout.LayoutParams(Ui.dp(a, OPEN_DP), ViewGroup.LayoutParams.MATCH_PARENT));
        build();
    }

    /** Adds the dock to a screen's root (a FrameLayout), full height on the left. */
    FrameLayout.LayoutParams params() {
        return new FrameLayout.LayoutParams(Ui.dp(a, RAIL_DP), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START);
    }

    // ------------------------------------------------------------ building

    private void build() {
        list.removeAllViews();
        rows.clear();
        LinearLayout profile = row("profile");
        avatar = Ui.heading(a, "", 13);
        avatar.setGravity(Gravity.CENTER);
        avatar.setBackground(Ui.rounded(a, Ui.ACCENT, 0, 999));
        avatar.setTextColor(Ui.BG);
        FrameLayout ab = new FrameLayout(a);
        ab.addView(avatar, new FrameLayout.LayoutParams(Ui.dp(a, 24), Ui.dp(a, 24), Gravity.CENTER));
        profile.addView(ab, new LinearLayout.LayoutParams(Ui.dp(a, 32), ViewGroup.LayoutParams.MATCH_PARENT));
        profileName = label("");
        profileName.setAllCaps(false);
        profileName.setLetterSpacing(0f);
        profileName.setTextSize(13);
        profile.addView(profileName);
        list.addView(profile);
        list.addView(new View(a), new LinearLayout.LayoutParams(1, Ui.dp(a, 10)));
        java.util.Set<String> hidden = hidden();
        String here = host.dockKey();
        for (int i = 0; i < ITEMS.length; i++) {
            String k = ITEMS[i];
            if (("live".equals(k) || "sports".equals(k)) && Ratings.restricted()) continue;
            if ("sports".equals(k) && Iptv.sources().isEmpty()) continue;
            if (hidden.contains(k) && !k.equals(here)) continue;
            list.addView(entry(k, NAMES[i], ICONS[i]));
        }
        list.addView(new View(a), new LinearLayout.LayoutParams(1, 0, 1f));
        list.addView(entry("settings", "Settings", R.drawable.ic_settings));
        refresh();
    }

    /** Picks up a new profile name / hidden items / highlight after returning to the screen. */
    void refresh() {
        String name = Store.profile();
        avatar.setText(name.isEmpty() ? "?" : name.substring(0, 1).toUpperCase());
        profileName.setText(name.isEmpty() ? "Profile" : name);
        String here = host.dockKey();
        for (View r : rows) r.setSelected(String.valueOf(r.getTag()).equals(here));
    }

    /** Rebuild when the entries change (hidden items, Live TV allowed, IPTV added). */
    void rebuildIfChanged() {
        String sig = signature();
        if (!sig.equals(builtSig)) { builtSig = sig; build(); } else refresh();
    }

    private String builtSig = signature();

    private static String signature() {
        return Store.get("dock_hidden") + "|" + Ratings.restricted() + "|" + Iptv.sources().isEmpty() + "|" + Theme.version();
    }

    static java.util.Set<String> hidden() {
        java.util.Set<String> out = new java.util.HashSet<>();
        for (String k : Store.get("dock_hidden").split("\\|")) if (!k.isEmpty()) out.add(keyOf(k));
        return out;
    }

    /** Older settings saved names ("Movies"); new ones save keys ("movie"). */
    static String keyOf(String nameOrKey) {
        for (int i = 0; i < NAMES.length; i++) if (NAMES[i].equals(nameOrKey)) return ITEMS[i];
        return nameOrKey;
    }

    private LinearLayout row(String key) {
        LinearLayout r = Ui.horizontal(a);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setFocusable(true);
        r.setClickable(true);
        r.setTag(key);
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_focused}, Ui.rounded(a, Ui.FOCUS, 0, 10));
        s.addState(new int[]{}, Ui.rounded(a, Color.TRANSPARENT, 0, 10));
        r.setBackground(s);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(a, 34));
        lp.setMargins(0, Ui.dp(a, 2), 0, Ui.dp(a, 2));
        r.setLayoutParams(lp);
        r.setOnClickListener(v -> {
            boolean same = key.equals(host.dockKey());
            close(same);
            if (!same) host.dockGo(key);
        });
        rows.add(r);
        return r;
    }

    private View entry(String key, String name, int icon) {
        LinearLayout r = row(key);
        // The icon sits in a fixed 32 dp box at the left, so it doesn't move when the dock opens.
        FrameLayout box = new FrameLayout(a);
        StateListDrawable sel = new StateListDrawable();
        sel.addState(new int[]{android.R.attr.state_focused}, Ui.rounded(a, Color.TRANSPARENT, 0, 10));
        sel.addState(new int[]{android.R.attr.state_selected}, Ui.rounded(a, Ui.alpha(Color.WHITE, 0.11f), 0, 10));
        sel.addState(new int[]{}, Ui.rounded(a, Color.TRANSPARENT, 0, 10));
        box.setBackground(sel);
        box.setDuplicateParentStateEnabled(true);
        ImageView ic = new ImageView(a);
        ic.setImageResource(icon);
        ic.setImageTintList(new ColorStateList(new int[][]{{android.R.attr.state_focused}, {android.R.attr.state_selected}, {}},
                new int[]{Ui.ON_FOCUS, Ui.ACCENT, Ui.MUTED}));
        ic.setImageTintMode(PorterDuff.Mode.SRC_IN);
        ic.setDuplicateParentStateEnabled(true);
        box.addView(ic, new FrameLayout.LayoutParams(Ui.dp(a, 18), Ui.dp(a, 18), Gravity.CENTER));
        r.addView(box, new LinearLayout.LayoutParams(Ui.dp(a, 32), ViewGroup.LayoutParams.MATCH_PARENT));
        r.addView(label(name));
        r.setContentDescription(name);
        return r;
    }

    private TextView label(String s) {
        TextView t = Ui.single(Ui.text(a, s, 11, Ui.MUTED));
        t.setAllCaps(true);
        t.setLetterSpacing(0.12f);
        t.setTextColor(new ColorStateList(new int[][]{{android.R.attr.state_focused}, {android.R.attr.state_selected}, {}},
                new int[]{Ui.ON_FOCUS, Ui.TEXT, Ui.MUTED}));
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setPadding(Ui.dp(a, 12), 0, 0, 0);
        t.setDuplicateParentStateEnabled(true);
        return t;
    }

    // ------------------------------------------------------------ open / close

    boolean isOpen() {
        return open;
    }

    void open() {
        if (open) return;
        open = true;
        lastFocus = a.getCurrentFocus();
        list.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        if (content instanceof ViewGroup) ((ViewGroup) content).setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        glide(OPEN_DP, 240);
        View target = list.findViewWithTag(host.dockKey());
        if (target == null) target = list.findViewWithTag("search");
        if (target == null && !rows.isEmpty()) target = rows.get(0);
        if (target != null) target.requestFocus();
    }

    /** restoreFocus: put focus back where it was on the screen. */
    void close(boolean restoreFocus) {
        if (!open) return;
        open = false;
        glide(RAIL_DP, 220);
        list.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        if (content instanceof ViewGroup) ((ViewGroup) content).setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        if (restoreFocus) {
            if (lastFocus != null && lastFocus.isAttachedToWindow() && lastFocus.isShown() && lastFocus.requestFocus()) return;
            if (content != null) content.requestFocus(View.FOCUS_DOWN);
        }
    }

    View lastFocus() {
        return lastFocus;
    }

    /** Widens / narrows the dock (icons stay put) and slides the screen by the same amount. */
    private void glide(int toDp, int ms) {
        if (anim != null) anim.cancel();
        int from = view.getLayoutParams() != null ? view.getLayoutParams().width : Ui.dp(a, RAIL_DP);
        int to = Ui.dp(a, toDp), rail = Ui.dp(a, RAIL_DP);
        anim = ValueAnimator.ofInt(from, to);
        anim.setDuration(ms);
        anim.setInterpolator(new DecelerateInterpolator());
        anim.addUpdateListener(v -> {
            int w = (Integer) v.getAnimatedValue();
            ViewGroup.LayoutParams lp = view.getLayoutParams();
            if (lp != null) { lp.width = w; view.setLayoutParams(lp); }
            if (content != null) content.setTranslationX(w - rail);
        });
        anim.start();
    }

    /**
     * Keys while the dock is open: ↑↓ move through it, → / BACK close it. Returns true when handled.
     * (← at the screen's left edge is the host's to detect; it then calls open().)
     */
    private boolean swallowBackUp;

    boolean onKey(KeyEvent e) {
        int k = e.getKeyCode();
        if (swallowBackUp && k == KeyEvent.KEYCODE_BACK && e.getAction() == KeyEvent.ACTION_UP) {
            swallowBackUp = false;   // BACK closed the dock: its release must not also leave the screen
            return true;
        }
        if (!open) return false;
        if (e.getAction() == KeyEvent.ACTION_UP) return k == KeyEvent.KEYCODE_BACK;
        if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
        switch (k) {
            case KeyEvent.KEYCODE_BACK:
                swallowBackUp = true;
                close(true);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                close(true);
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN: {
                List<View> shown = new ArrayList<>();
                for (View r : rows) if (r.getVisibility() == View.VISIBLE && r.getParent() != null) shown.add(r);
                int at = shown.indexOf(a.getCurrentFocus());
                int next = at < 0 ? 0 : Math.max(0, Math.min(shown.size() - 1, at + (k == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1)));
                if (!shown.isEmpty()) shown.get(next).requestFocus();
                return true;
            }
            default:
                return false;
        }
    }

    // ------------------------------------------------------------ going to another screen

    /** Opens the screen for a dock entry. Between main screens there is no slide, so the dock seems to stay put. */
    static void openScreen(Activity from, String key) {
        android.content.Intent i;
        switch (key) {
            case "home": case "movie": case "series": case "favorites":
                i = new android.content.Intent(from, MainActivity.class).putExtra("section", key)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP | android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP);
                break;
            case "search": i = new android.content.Intent(from, SearchActivity.class).putExtra("voice", true); break;
            case "live": i = new android.content.Intent(from, LiveActivity.class); break;
            case "sports": i = new android.content.Intent(from, SportsActivity.class); break;
            case "apps": i = new android.content.Intent(from, AppsActivity.class); break;
            case "settings": i = new android.content.Intent(from, SettingsActivity.class); break;
            case "profile": i = new android.content.Intent(from, ProfilesActivity.class); break;
            default: return;
        }
        from.startActivity(i);
        from.overridePendingTransition(0, 0);
        // One main screen at a time on top of Home (BACK from Apps goes Home, not through every screen visited).
        if (!(from instanceof MainActivity) && isMain(from)) {
            from.finish();
            from.overridePendingTransition(0, 0);
        }
    }

    /** The screens the dock's entries open (not Settings or a title page). */
    static boolean isMain(Activity a) {
        return a instanceof SearchActivity || a instanceof LiveActivity || a instanceof SportsActivity || a instanceof AppsActivity;
    }
}
