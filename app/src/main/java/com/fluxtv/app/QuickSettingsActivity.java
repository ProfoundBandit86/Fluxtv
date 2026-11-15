package com.fluxtv.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Date;

/**
 * Quick Settings: a panel that slides over whatever is on screen, with the clock, network
 * status, shortcuts into the TV's settings, and Google's smart-home panel when available.
 * Also listed as its own app ("Flux Quick Settings") so it can be mapped to a remote button.
 */
public class QuickSettingsActivity extends Activity {

    /** Google TV's smart home panel (part of the Google TV home screen app). */
    static final String SMART_HOME = "com.google.android.libraries.tv.smarthome.intent.action.OPEN_SMART_HOME";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        FrameLayout root = new FrameLayout(this);
        View shade = new View(this);
        shade.setBackgroundColor(Ui.alpha(Color.BLACK, 0.45f));
        shade.setOnClickListener(v -> finish());
        root.addView(shade, new FrameLayout.LayoutParams(-1, -1));

        ScrollView panelScroll = new FocusScroll.Vertical(this);
        panelScroll.setBackground(new GradientDrawable(GradientDrawable.Orientation.RIGHT_LEFT, new int[]{Ui.PANEL, Ui.alpha(Ui.PANEL, 0.97f)}));
        panelScroll.setElevation(Ui.dp(this, 16));
        LinearLayout panel = Ui.vertical(this);
        panel.setPadding(Ui.dp(this, 28), Ui.dp(this, 30), Ui.dp(this, 28), Ui.dp(this, 30));
        panelScroll.addView(panel);
        int width = Ui.dp(this, 420);
        root.addView(panelScroll, new FrameLayout.LayoutParams(width, -1, Gravity.END));
        setContentView(root);
        panelScroll.setTranslationX(width);
        panelScroll.animate().translationX(0).setDuration(220).start();

        Date now = new Date();
        TextView time = Ui.heading(this, DateFormat.getTimeFormat(this).format(now), 40);
        panel.addView(time);
        TextView date = Ui.text(this, DateFormat.format("EEEE, MMMM d", now).toString(), 15, Ui.MUTED);
        date.setPadding(0, Ui.dp(this, 2), 0, Ui.dp(this, 12));
        panel.addView(date);

        LinearLayout status = Ui.horizontal(this);
        status.setGravity(Gravity.CENTER_VERTICAL);
        boolean online = online();
        View dot = new View(this);
        dot.setBackground(Ui.rounded(this, online ? Ui.GOOD : Ui.WARN, 0, 99));
        status.addView(dot, new LinearLayout.LayoutParams(Ui.dp(this, 9), Ui.dp(this, 9)));
        TextView net = Ui.text(this, (online ? "Online" : "Offline") + "   ·   Profile: " + Store.profile()
                + "   ·   Flux " + Updater.currentName(this), 13, Ui.MUTED);
        net.setPadding(Ui.dp(this, 10), 0, 0, 0);
        status.addView(net);
        panel.addView(status);

        Intent smartHome = new Intent(SMART_HOME);
        boolean hasHome = smartHome.resolveActivity(getPackageManager()) != null;

        View first = null;
        LinearLayout grid = null;
        Object[][] tiles = {
                {R.drawable.ic_bulb, "Smart home", new Intent(this, SmartPanelActivity.class)},
                {R.drawable.ic_ghome, "Google Home", hasHome ? smartHome : null},
                {R.drawable.ic_wifi, "Network & Internet", new Intent(Settings.ACTION_WIFI_SETTINGS)},
                {R.drawable.ic_bt, "Remotes & Bluetooth", new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)},
                {R.drawable.ic_display, "Display", new Intent(Settings.ACTION_DISPLAY_SETTINGS)},
                {R.drawable.ic_sound, "Sound", new Intent(Settings.ACTION_SOUND_SETTINGS)},
                {R.drawable.ic_moon, "Screensaver", new Intent(Settings.ACTION_DREAM_SETTINGS)},
                {R.drawable.ic_access, "Accessibility", new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)},
                {R.drawable.ic_clock, "Date & time", new Intent(Settings.ACTION_DATE_SETTINGS)},
                {R.drawable.ic_apps, "Apps", new Intent(Settings.ACTION_APPLICATION_SETTINGS)},
                {R.drawable.ic_account, "Accounts", new Intent(Settings.ACTION_SYNC_SETTINGS)},
                {R.drawable.ic_settings, "All settings", new Intent(Settings.ACTION_SETTINGS)},
                {R.drawable.ic_sliders, "Flux settings", new Intent(this, SettingsActivity.class)},
        };
        int col = 0;
        for (Object[] t : tiles) {
            Intent target = (Intent) t[2];
            if (target == null) continue;   // Google Home only when the Google TV home screen is enabled
            if (col % 2 == 0) {
                grid = Ui.horizontal(this);
                grid.setPadding(0, Ui.dp(this, 10), 0, 0);
                panel.addView(grid);
            }
            View tile = tile((Integer) t[0], (String) t[1], target);
            grid.addView(tile, new LinearLayout.LayoutParams(0, Ui.dp(this, 74), 1f));
            if (col % 2 == 0) ((LinearLayout.LayoutParams) tile.getLayoutParams()).setMargins(0, 0, Ui.dp(this, 10), 0);
            if (first == null) first = tile;
            col++;
        }
        if (!hasHome) {
            TextView note = Ui.text(this, "Google Home appears here when Google TV's own home screen is enabled.", 12, Ui.DIM);
            note.setPadding(0, Ui.dp(this, 16), 0, 0);
            panel.addView(note);
        }
        View f = first;
        if (f != null) f.post(f::requestFocus);
    }

    private View tile(int icon, String label, Intent target) {
        LinearLayout t = Ui.vertical(this);
        t.setFocusable(true);
        t.setClickable(true);
        t.setPadding(Ui.dp(this, 14), Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 10));
        StateListDrawable bg = new StateListDrawable();
        bg.addState(new int[]{android.R.attr.state_focused}, Ui.rounded(this, Ui.TEXT, 0, 14));
        bg.addState(new int[]{}, Ui.rounded(this, Ui.alpha(Color.WHITE, 0.07f), 0, 14));
        t.setBackground(bg);
        ImageView ic = new ImageView(this);
        ic.setImageResource(icon);
        ic.setImageTintList(Ui.onFocusDark(Ui.TEXT));
        ic.setImageTintMode(android.graphics.PorterDuff.Mode.SRC_IN);
        ic.setDuplicateParentStateEnabled(true);
        t.addView(ic, new LinearLayout.LayoutParams(Ui.dp(this, 22), Ui.dp(this, 22)));
        TextView l = Ui.single(Ui.text(this, label, 13, Ui.TEXT));
        l.setTextColor(Ui.onFocusDark(Ui.TEXT));
        l.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        l.setDuplicateParentStateEnabled(true);
        l.setPadding(0, Ui.dp(this, 10), 0, 0);
        t.addView(l);
        t.setOnFocusChangeListener((v, has) -> v.animate().scaleX(has ? 1.04f : 1f).scaleY(has ? 1.04f : 1f).setDuration(120).start());
        t.setOnClickListener(v -> open(target));
        return t;
    }

    private void open(Intent target) {
        Intent i = new Intent(target).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            if (i.getComponent() == null && i.resolveActivity(getPackageManager()) == null) {
                i = new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);   // TV lacks that page
            }
            startActivity(i);
            finish();
        } catch (Exception e) {
            Toast.makeText(this, "Not available on this TV", Toast.LENGTH_SHORT).show();
        }
    }

    private boolean online() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            if (cm == null) return true;
            if (Build.VERSION.SDK_INT >= 23) {
                NetworkCapabilities c = cm.getNetworkCapabilities(cm.getActiveNetwork());
                return c != null && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
            }
            return true;
        } catch (Exception e) {
            return true;
        }
    }
}
