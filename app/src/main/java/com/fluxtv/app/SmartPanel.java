package com.fluxtv.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The smart home panel: slides in from the right over whatever is on screen (video keeps playing).
 * OK switches a device on/off · ←/→ dims lights (or sets fan speed, blind position, thermostat) · Back closes.
 */
public final class SmartPanel {

    private SmartPanel() { }

    // ------------------------------------------------------------ showing it over other apps

    private static View overlay;
    private static WindowManager overlayWm;

    /** Can Flux draw over other apps? (via its accessibility service, or "Display over other apps"). */
    public static boolean canOverlay(Context c) {
        return HomeButtonService.instance != null || (Build.VERSION.SDK_INT >= 23 && android.provider.Settings.canDrawOverlays(c));
    }

    /** Shows the panel over everything. Returns false if Flux isn't allowed to (use SmartPanelActivity instead). */
    public static boolean showOverlay(Context any) {
        if (overlay != null) return true;
        Context c;
        int type;
        if (HomeButtonService.instance != null) {
            c = HomeButtonService.instance;
            type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY;
        } else if (Build.VERSION.SDK_INT >= 26 && android.provider.Settings.canDrawOverlays(any)) {
            c = any.getApplicationContext();
            type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        } else {
            return false;
        }
        WindowManager wm = (WindowManager) c.getSystemService(Context.WINDOW_SERVICE);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, type,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_DIM_BEHIND,
                PixelFormat.TRANSLUCENT);
        lp.dimAmount = 0.35f;
        lp.gravity = Gravity.TOP | Gravity.START;
        View v = build(c, SmartPanel::hide);
        try {
            wm.addView(v, lp);
        } catch (Exception e) {
            return false;
        }
        overlay = v;
        overlayWm = wm;
        return true;
    }

    public static boolean showing() {
        return overlay != null;
    }

    public static void hide() {
        if (overlay == null) return;
        View v = overlay;
        overlay = null;
        Object p = v.getTag();
        if (p instanceof Panel) ((Panel) p).stop();
        try { overlayWm.removeView(v); } catch (Exception ignored) { }
    }

    // ------------------------------------------------------------ the panel view

    /** Builds the panel (full-screen, card on the right). close runs on Back. */
    public static View build(Context c, Runnable close) {
        Panel p = new Panel(c, close);
        p.root.setTag(p);
        return p.root;
    }

    private static final class Panel {
        final Context c;
        final Runnable close;
        final FrameLayout root;
        final LinearLayout list;
        final TextView status;
        final Map<String, Row> rows = new HashMap<>();
        boolean running = true;

        Panel(Context ctx, Runnable closeAction) {
            c = ctx;
            close = closeAction;
            root = new FrameLayout(c) {
                @Override
                public boolean dispatchKeyEvent(KeyEvent e) {
                    if (e.getKeyCode() == KeyEvent.KEYCODE_BACK || e.getKeyCode() == KeyEvent.KEYCODE_ESCAPE) {
                        if (e.getAction() == KeyEvent.ACTION_UP) close.run();
                        return true;
                    }
                    return super.dispatchKeyEvent(e);
                }
            };
            root.setFocusable(false);
            View tapOut = new View(c);
            tapOut.setOnClickListener(v -> close.run());
            root.addView(tapOut, new FrameLayout.LayoutParams(-1, -1));

            LinearLayout card = Ui.vertical(c);
            card.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                    new int[]{Ui.alpha(Ui.PANEL, 0.97f), Ui.PANEL}));
            card.setElevation(Ui.dp(c, 16));
            card.setPadding(Ui.dp(c, 26), Ui.dp(c, 26), Ui.dp(c, 26), Ui.dp(c, 18));
            LinearLayout head = Ui.horizontal(c);
            head.setGravity(Gravity.CENTER_VERTICAL);
            head.addView(Ui.icon(c, R.drawable.ic_ghome, 24, Ui.ACCENT), new LinearLayout.LayoutParams(Ui.dp(c, 24), Ui.dp(c, 24)));
            TextView title = Ui.heading(c, "Home", 24);
            title.setPadding(Ui.dp(c, 12), 0, 0, 0);
            head.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
            TextView clock = Ui.text(c, android.text.format.DateFormat.getTimeFormat(c).format(new java.util.Date()), 15, Ui.MUTED);
            head.addView(clock);
            card.addView(head);
            status = Ui.text(c, "Loading devices…", 13, Ui.MUTED);
            status.setPadding(0, Ui.dp(c, 6), 0, Ui.dp(c, 8));
            card.addView(status);
            ScrollView sc = new FocusScroll.Vertical(c);
            sc.setVerticalScrollBarEnabled(false);
            sc.setClipToPadding(false);
            list = Ui.vertical(c);
            list.setPadding(0, Ui.dp(c, 4), 0, Ui.dp(c, 30));
            sc.addView(list);
            card.addView(sc, new LinearLayout.LayoutParams(-1, 0, 1f));
            TextView hint = Ui.text(c, "OK on/off  ·  ← → dim  ·  Back to close", 12, Ui.DIM);
            hint.setPadding(0, Ui.dp(c, 8), 0, 0);
            card.addView(hint);
            root.addView(card, new FrameLayout.LayoutParams(Ui.dp(c, 470), -1, Gravity.END));
            card.setTranslationX(Ui.dp(c, 470));
            card.animate().translationX(0).setDuration(220).start();
            refresh(true);
        }

        void stop() {
            running = false;
        }

        private final Runnable poll = () -> refresh(false);

        void refresh(boolean first) {
            if (!running) return;
            if (!SmartHome.configured()) {
                status.setText("Connect Home Assistant in Flux → Settings → Smart home (or on the phone settings page).");
                return;
            }
            App.async(SmartHome::all, all -> {
                if (!running) return;
                List<SmartHome.Device> shown = new ArrayList<>();
                for (SmartHome.Device d : all) if (SmartHome.shown(d)) shown.add(d);
                if (first || rows.size() != shown.size() || !sameIds(shown)) render(shown);
                else for (SmartHome.Device d : shown) { Row r = rows.get(d.id); if (r != null && !r.busy()) r.update(d); }
                status.setText(shown.isEmpty() ? "No devices chosen. Pick them in Flux → Settings → Smart home." : "");
                status.setVisibility(shown.isEmpty() ? View.VISIBLE : View.GONE);
                App.MAIN.removeCallbacks(poll);
                App.MAIN.postDelayed(poll, 3000);
            }, e -> {
                if (!running) return;
                status.setVisibility(View.VISIBLE);
                status.setText("Can't reach Home Assistant: " + App.message(e));
                App.MAIN.removeCallbacks(poll);
                App.MAIN.postDelayed(poll, 6000);
            });
        }

        private boolean sameIds(List<SmartHome.Device> shown) {
            for (SmartHome.Device d : shown) if (!rows.containsKey(d.id)) return false;
            return true;
        }

        void render(List<SmartHome.Device> shown) {
            View f = list.findFocus();
            String focusId = f != null && f.getTag() instanceof String ? (String) f.getTag() : null;
            list.removeAllViews();
            rows.clear();
            String room = null;
            View first = null;
            for (SmartHome.Device d : shown) {
                String r = d.room.isEmpty() ? "Other" : d.room;
                if (!r.equals(room)) {
                    room = r;
                    TextView h = Ui.text(c, r.toUpperCase(), 12, Ui.DIM);
                    h.setPadding(Ui.dp(c, 4), Ui.dp(c, 14), 0, Ui.dp(c, 6));
                    list.addView(h);
                }
                Row row = new Row(c, d);
                rows.put(d.id, row);
                list.addView(row.view);
                if (first == null) first = row.view;
            }
            View target = focusId != null && rows.containsKey(focusId) ? rows.get(focusId).view : first;
            if (target != null) target.post(target::requestFocus);
        }
    }

    // ------------------------------------------------------------ one device

    private static final class Row {
        final Context c;
        SmartHome.Device d;
        final LinearLayout view;
        final ImageView icon;
        final FrameLayout iconBox;
        final TextView name, state;
        final Slider slider;
        long busyUntil;

        Row(Context ctx, SmartHome.Device device) {
            c = ctx;
            d = device;
            view = Ui.vertical(c);
            view.setTag(d.id);
            view.setFocusable(true);
            view.setClickable(true);
            view.setBackground(Ui.focusable(c, Ui.alpha(Ui.TEXT, 0.05f), Ui.alpha(Ui.TEXT, 0.12f), Ui.ACCENT));
            view.setPadding(Ui.dp(c, 14), Ui.dp(c, 10), Ui.dp(c, 14), Ui.dp(c, 10));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.setMargins(0, Ui.dp(c, 3), 0, Ui.dp(c, 3));
            view.setLayoutParams(lp);
            LinearLayout top = Ui.horizontal(c);
            top.setGravity(Gravity.CENTER_VERTICAL);
            iconBox = new FrameLayout(c);
            icon = Ui.icon(c, iconFor(d.kind), 22, Ui.TEXT);
            iconBox.addView(icon, new FrameLayout.LayoutParams(Ui.dp(c, 22), Ui.dp(c, 22), Gravity.CENTER));
            top.addView(iconBox, new LinearLayout.LayoutParams(Ui.dp(c, 42), Ui.dp(c, 42)));
            LinearLayout txt = Ui.vertical(c);
            txt.setPadding(Ui.dp(c, 14), 0, 0, 0);
            name = Ui.single(Ui.text(c, d.name, 16, Ui.TEXT));
            state = Ui.single(Ui.text(c, "", 13, Ui.MUTED));
            txt.addView(name);
            txt.addView(state);
            top.addView(txt, new LinearLayout.LayoutParams(0, -2, 1f));
            view.addView(top);
            slider = new Slider(c);
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, Ui.dp(c, 22));
            slp.setMargins(Ui.dp(c, 56), Ui.dp(c, 6), 0, 0);
            view.addView(slider, slp);
            view.setOnClickListener(v -> press());
            view.setOnKeyListener((v, code, e) -> {
                if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    int dir = code == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1;
                    return adjust(dir);
                }
                return false;
            });
            update(d);
        }

        boolean busy() {
            return System.currentTimeMillis() < busyUntil;
        }

        void update(SmartHome.Device nd) {
            d = nd;
            boolean active = d.available && (d.on || SmartHome.CLIMATE.equals(d.kind) && d.on);
            iconBox.setBackground(Ui.rounded(c, active ? Ui.ACCENT : Ui.alpha(Ui.TEXT, 0.12f), 0, 999));
            icon.setColorFilter(active ? Ui.BG : Ui.TEXT);
            name.setTextColor(d.available ? Ui.TEXT : Ui.DIM);
            state.setText(SmartHome.describe(d));
            boolean showSlider = d.dimmable() && d.available;
            slider.setVisibility(showSlider ? View.VISIBLE : View.GONE);
            if (showSlider) slider.set(d.level);
        }

        private long lockArmed;

        void press() {
            if (!d.available) return;
            if (SmartHome.CLIMATE.equals(d.kind)) return;
            if (SmartHome.LOCK.equals(d.kind) && d.on && System.currentTimeMillis() - lockArmed > 3000) {
                lockArmed = System.currentTimeMillis();
                state.setText("Press OK again to unlock");
                return;
            }
            boolean on = SmartHome.SCENE.equals(d.kind) || !d.on;
            SmartHome.Device dev = d;
            if (!SmartHome.SCENE.equals(d.kind)) {
                d.on = on;
                if (d.dimmable() && !SmartHome.COVER.equals(d.kind)) d.level = on ? (d.level > 0 ? d.level : 100) : 0;
                update(d);
            }
            busyUntil = System.currentTimeMillis() + 2500;
            App.async(() -> { SmartHome.setOn(dev, on); return true; }, ok -> { if (SmartHome.SCENE.equals(dev.kind)) state.setText("Done"); },
                    e -> state.setText("Didn't work: " + App.message(e)));
        }

        private final Runnable sendLevel = this::sendLevel;

        boolean adjust(int dir) {
            if (!d.available) return true;
            if (SmartHome.CLIMATE.equals(d.kind)) {
                if (Double.isNaN(d.target)) return true;
                d.target = d.target + dir * d.step;
                update(d);
                busyUntil = System.currentTimeMillis() + 2500;
                App.MAIN.removeCallbacks(sendLevel);
                App.MAIN.postDelayed(sendLevel, 500);
                return true;
            }
            if (!d.dimmable()) return dir < 0 ? false : true;   // ← on a plain switch does nothing
            int lvl = Math.max(0, Math.min(100, (d.level < 0 ? 0 : d.level) + dir * 10));
            if (dir > 0 && d.level <= 0) lvl = 10;
            d.level = lvl;
            if (!SmartHome.COVER.equals(d.kind)) d.on = lvl > 0;
            update(d);
            busyUntil = System.currentTimeMillis() + 2500;
            App.MAIN.removeCallbacks(sendLevel);
            App.MAIN.postDelayed(sendLevel, 350);   // send once you stop pressing
            return true;
        }

        void sendLevel() {
            SmartHome.Device dev = d;
            int lvl = d.level;
            double t = d.target;
            App.async(() -> {
                if (SmartHome.CLIMATE.equals(dev.kind)) SmartHome.setTarget(dev, t); else SmartHome.setLevel(dev, lvl);
                return true;
            }, ok -> { }, e -> state.setText("Didn't work: " + App.message(e)));
        }
    }

    private static int iconFor(String kind) {
        switch (kind) {
            case SmartHome.LIGHT: return R.drawable.ic_bulb;
            case SmartHome.FAN: return R.drawable.ic_fan;
            case SmartHome.CLIMATE: return R.drawable.ic_thermo;
            case SmartHome.LOCK: return R.drawable.ic_lock;
            case SmartHome.COVER: return R.drawable.ic_blinds;
            case SmartHome.SCENE: return R.drawable.ic_star;
            default: return R.drawable.ic_plug;
        }
    }

    /** A brightness bar with the percentage at the end. */
    static final class Slider extends View {
        private int value;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private final float d;

        Slider(Context c) {
            super(c);
            d = c.getResources().getDisplayMetrics().density;
            p.setTextSize(12 * d);
        }

        void set(int v) {
            value = v;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth() - 46 * d, h = getHeight(), y = h / 2;
            r.set(0, y - 3 * d, w, y + 3 * d);
            p.setColor(Ui.alpha(Ui.TEXT, 0.15f));
            c.drawRoundRect(r, 3 * d, 3 * d, p);
            float x = w * value / 100f;
            r.set(0, y - 3 * d, x, y + 3 * d);
            p.setColor(Ui.ACCENT);
            c.drawRoundRect(r, 3 * d, 3 * d, p);
            p.setColor(Color.WHITE);
            c.drawCircle(Math.max(7 * d, Math.min(w - 7 * d, x)), y, 7 * d, p);
            p.setColor(Ui.MUTED);
            c.drawText(value + "%", w + 10 * d, y + 4 * d, p);
        }
    }
}
