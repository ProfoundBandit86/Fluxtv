package com.fluxtv.app;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Every installed app, so the TV stays fully usable with Flux as the home screen. */
public class AppsActivity extends Activity {

    private static final int PER_ROW = 5;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ScrollView scroll = new FocusScroll.Vertical(this);
        scroll.setBackgroundColor(Ui.BG);
        scroll.setClipChildren(false);
        LinearLayout content = Ui.vertical(this);
        int pad = Ui.dp(this, 48);
        content.setPadding(pad, Ui.dp(this, 32), pad, pad);
        content.setClipChildren(false);
        scroll.addView(content);
        setContentView(scroll);
        content.addView(Ui.heading(this, "Apps", 30));

        PackageManager pm = getPackageManager();
        List<ResolveInfo> apps = launchableApps(pm);
        LinearLayout row = null;
        for (int i = 0; i < apps.size(); i++) {
            if (i % PER_ROW == 0) {
                row = Ui.horizontal(this);
                row.setClipChildren(false);
                content.addView(row);
            }
            row.addView(tile(pm, apps.get(i)));
        }
        if (content.getChildCount() > 1) {
            LinearLayout first = (LinearLayout) content.getChildAt(1);
            if (first.getChildCount() > 0) first.getChildAt(0).requestFocus();
        }
    }

    private LinearLayout tile(PackageManager pm, ResolveInfo ri) {
        String pkg = ri.activityInfo.packageName;
        LinearLayout t = Ui.vertical(this);
        t.setGravity(Gravity.CENTER_HORIZONTAL);
        t.setFocusable(true);
        t.setClickable(true);
        int p = Ui.dp(this, 8);
        t.setPadding(p, p, p, p);
        t.setBackground(Ui.focusable(this, Ui.PANEL, Ui.BUTTON, Ui.ACCENT));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(this, 190), ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8));
        t.setLayoutParams(lp);

        ImageView img = new ImageView(this);
        img.setScaleType(ImageView.ScaleType.FIT_CENTER);
        Drawable banner = ri.activityInfo.loadBanner(pm);
        if (banner == null) banner = ri.activityInfo.applicationInfo.loadBanner(pm);
        img.setImageDrawable(banner != null ? banner : ri.loadIcon(pm));
        t.addView(img, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 96)));

        TextView label = Ui.text(this, ri.loadLabel(pm), 14, Ui.TEXT);
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setGravity(Gravity.CENTER);
        t.addView(label);

        t.setOnClickListener(v -> {
            Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
            if (i == null) i = pm.getLaunchIntentForPackage(pkg);
            if (i == null) {
                Toast.makeText(this, "Can't open this app", Toast.LENGTH_SHORT).show();
                return;
            }
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        });
        return t;
    }

    private List<ResolveInfo> launchableApps(PackageManager pm) {
        List<ResolveInfo> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        seen.add(getPackageName());
        for (String cat : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            for (ResolveInfo ri : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(cat), 0)) {
                if (seen.add(ri.activityInfo.packageName)) out.add(ri);
            }
        }
        Collections.sort(out, (a, b) -> a.loadLabel(pm).toString().compareToIgnoreCase(b.loadLabel(pm).toString()));
        return out;
    }
}
