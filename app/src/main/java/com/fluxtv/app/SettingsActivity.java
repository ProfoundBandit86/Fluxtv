package com.fluxtv.app;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/** Appearance (accent + background colours), sources, and system shortcuts. */
public class SettingsActivity extends Activity {

    private LinearLayout content;
    private int focusAccent = -1, focusBg = -1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        render();
    }

    private void render() {
        content = Ui.page(this, "Settings");

        content.addView(section("Appearance"));
        content.addView(label("Accent colour"));
        LinearLayout accents = Ui.horizontal(this);
        accents.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 4));
        View firstFocus = null;
        for (int i = 0; i < Theme.ACCENT_HEX.length; i++) {
            int idx = i;
            View sw = swatch(Color.parseColor(Theme.ACCENT_HEX[i]), Theme.ACCENT_NAMES[i], i == Theme.accentIndex(), v -> {
                Theme.setAccent(idx);
                focusAccent = idx;
                render();
            });
            accents.addView(sw);
            if (i == (focusAccent >= 0 ? focusAccent : Theme.accentIndex()) && focusBg < 0) firstFocus = sw;
        }
        content.addView(accents);
        TextView an = Ui.text(this, Theme.ACCENT_NAMES[Theme.accentIndex()], 13, Ui.MUTED);
        an.setPadding(Ui.dp(this, 4), 0, 0, Ui.dp(this, 14));
        content.addView(an);

        content.addView(label("Background"));
        LinearLayout bgs = Ui.horizontal(this);
        for (int i = 0; i < Theme.BG_NAMES.length; i++) {
            int idx = i;
            View b = Ui.pill(this, Theme.BG_NAMES[i], i == Theme.bgIndex(), v -> {
                Theme.setBackground(idx);
                focusBg = idx;
                focusAccent = -1;
                render();
            });
            bgs.addView(b);
            if (focusBg == i) firstFocus = b;
        }
        content.addView(bgs);

        content.addView(section("Sources"));
        LinearLayout src = Ui.horizontal(this);
        src.addView(Ui.button(this, R.drawable.ic_addons, "Add-ons", v -> startActivity(new Intent(this, AddonsActivity.class))));
        src.addView(Ui.button(this, R.drawable.ic_servers, "Media servers", v -> startActivity(new Intent(this, ServersActivity.class))));
        src.addView(Ui.button(this, R.drawable.ic_cloud, "Real-Debrid", v -> startActivity(new Intent(this, AccountActivity.class))));
        src.addView(Ui.button(this, R.drawable.ic_live, "Live TV & IPTV", v -> startActivity(new Intent(this, IptvActivity.class))));
        content.addView(src);

        content.addView(section("System"));
        LinearLayout sys = Ui.horizontal(this);
        sys.addView(Ui.button(this, R.drawable.ic_update, "Check for updates · " + Updater.currentName(this),
                v -> Updater.check(this, true)));
        sys.addView(Ui.button(this, R.drawable.ic_apps, "All apps", v -> startActivity(new Intent(this, AppsActivity.class))));
        sys.addView(Ui.button(this, R.drawable.ic_settings, "TV settings", v -> {
            try {
                startActivity(new Intent(Settings.ACTION_SETTINGS));
            } catch (ActivityNotFoundException e) {
                Toast.makeText(this, "Settings not available", Toast.LENGTH_SHORT).show();
            }
        }));
        content.addView(sys);

        View f = firstFocus;
        if (f != null) f.post(f::requestFocus);
    }

    private TextView section(String s) {
        TextView t = Ui.heading(this, s, 18);
        t.setPadding(0, Ui.dp(this, 26), 0, Ui.dp(this, 8));
        return t;
    }

    private TextView label(String s) {
        TextView t = Ui.text(this, s, 13, Ui.DIM);
        t.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 6));
        return t;
    }

    /** Round colour swatch: white ring when focused, check-style inner ring when selected. */
    private View swatch(int color, String name, boolean selected, View.OnClickListener click) {
        View v = new View(this);
        v.setFocusable(true);
        v.setClickable(true);
        v.setContentDescription(name);
        v.setOnClickListener(click);
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_focused}, circle(color, Ui.TEXT, 4));
        s.addState(new int[]{}, circle(color, selected ? Ui.alpha(Ui.TEXT, 0.6f) : 0, 2));
        v.setBackground(s);
        v.setOnFocusChangeListener((x, has) -> x.animate().scaleX(has ? 1.12f : 1f).scaleY(has ? 1.12f : 1f).setDuration(120).start());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 44));
        lp.setMargins(0, Ui.dp(this, 4), Ui.dp(this, 14), Ui.dp(this, 4));
        v.setLayoutParams(lp);
        return v;
    }

    private GradientDrawable circle(int color, int stroke, float strokeDp) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(color);
        if (stroke != 0) d.setStroke(Ui.dp(this, strokeDp), stroke);
        return d;
    }
}
