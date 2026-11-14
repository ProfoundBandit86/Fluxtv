package com.fluxtv.app;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Customize Home: show/hide rows and change their order. */
public class HomeRowsActivity extends FluxActivity {

    private LinearLayout list;
    private final List<String> order = new ArrayList<>();
    private Set<String> hidden;
    private final java.util.Map<String, Addons.Row> byKey = new java.util.HashMap<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout content = Ui.page(this, "Customize Home");
        TextView help = Ui.text(this, "Continue watching, your apps and streaming services always stay at the top. "
                + "Choose which rows follow them, and their order.", 13, Ui.MUTED);
        help.setPadding(0, 0, 0, Ui.dp(this, 12));
        content.addView(help, new LinearLayout.LayoutParams(Ui.dp(this, 760), ViewGroup.LayoutParams.WRAP_CONTENT));
        content.addView(Ui.heading(this, "Continue watching shows items from", 16));
        TextView cwHelp = Ui.text(this, "Apps appear here once they've added something to Continue watching. "
                + "Tip: long-press a Continue watching card to hide its app.", 12, Ui.DIM);
        cwHelp.setPadding(0, Ui.dp(this, 2), 0, Ui.dp(this, 6));
        content.addView(cwHelp);
        LinearLayout cw = new LinearLayout(this);
        cw.setOrientation(LinearLayout.VERTICAL);
        content.addView(cw);
        renderSources(cw);
        TextView rowsHead = Ui.heading(this, "Rows", 16);
        rowsHead.setPadding(0, Ui.dp(this, 18), 0, Ui.dp(this, 6));
        content.addView(rowsHead);
        list = Ui.vertical(this);
        content.addView(list, new LinearLayout.LayoutParams(Ui.dp(this, 900), ViewGroup.LayoutParams.WRAP_CONTENT));
        hidden = HomeLayout.hidden();
        for (Addons.Row r : HomeLayout.ordered(HomeLayout.available)) {
            order.add(HomeLayout.key(r));
            byKey.put(HomeLayout.key(r), r);
        }
        if (order.isEmpty()) content.addView(Ui.text(this, "Open Home first so Flux knows which rows are available.", 14, Ui.MUTED));
        render(-1, 0);
    }

    private void render(int focusIndex, int focusChild) {
        list.removeAllViews();
        for (int i = 0; i < order.size(); i++) {
            String key = order.get(i);
            Addons.Row r = byKey.get(key);
            int idx = i;
            LinearLayout line = Ui.horizontal(this);
            line.setGravity(Gravity.CENTER_VERTICAL);
            line.setBackground(Ui.rounded(this, Ui.PANEL, 0, 12));
            line.setPadding(Ui.dp(this, 16), Ui.dp(this, 6), Ui.dp(this, 8), Ui.dp(this, 6));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, Ui.dp(this, 4), 0, Ui.dp(this, 4));
            line.setLayoutParams(lp);
            boolean shown = !hidden.contains(key);
            LinearLayout names = Ui.vertical(this);
            TextView t = Ui.single(Ui.heading(this, r.title, 15));
            t.setAlpha(shown ? 1f : 0.45f);
            names.addView(t);
            if (!r.note.isEmpty()) names.addView(Ui.text(this, r.note, 12, Ui.DIM));
            line.addView(names, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            line.addView(Ui.pill(this, shown ? "Shown" : "Hidden", shown, v -> {
                if (hidden.contains(key)) hidden.remove(key); else hidden.add(key);
                save();
                render(idx, 0);
            }));
            line.addView(Ui.button(this, "▲", v -> { move(idx, -1); render(Math.max(0, idx - 1), 1); }));
            line.addView(Ui.button(this, "▼", v -> { move(idx, 1); render(Math.min(order.size() - 1, idx + 1), 2); }));
            list.addView(line);
        }
        if (focusIndex >= 0 && focusIndex < list.getChildCount()) {
            View target = ((LinearLayout) list.getChildAt(focusIndex)).getChildAt(1 + focusChild);
            target.post(target::requestFocus);
        } else if (list.getChildCount() > 0) {
            View target = ((LinearLayout) list.getChildAt(0)).getChildAt(1);
            target.post(target::requestFocus);
        }
    }

    private void renderSources(LinearLayout cw) {
        cw.removeAllViews();
        java.util.Set<String> hiddenApps = ContinueWatching.hidden();
        LinearLayout line = null;
        int n = 0;
        for (String[] src : ContinueWatching.sources()) {
            if (n++ % 4 == 0) {
                line = Ui.horizontal(this);
                cw.addView(line);
            }
            boolean on = !hiddenApps.contains(src[0]);
            line.addView(Ui.pill(this, (on ? "✓ " : "") + src[1], on, v -> {
                ContinueWatching.setHidden(src[0], on);
                int idx = cw.getFocusables(View.FOCUS_DOWN).indexOf(v);
                renderSources(cw);
                java.util.ArrayList<View> f = cw.getFocusables(View.FOCUS_DOWN);
                if (idx >= 0 && idx < f.size()) f.get(idx).requestFocus();
            }));
        }
    }

    private void move(int i, int d) {
        int j = i + d;
        if (j < 0 || j >= order.size()) return;
        java.util.Collections.swap(order, i, j);
        save();
    }

    private void save() {
        HomeLayout.save(order, hidden);
    }
}
