package com.fluxtv.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/** Live TV: provider / channel groups on the left, channels with now & next on the right. */
public class LiveActivity extends FluxActivity {

    @Override
    protected String sidebarKey() {
        return "live";
    }


    private static final int MAX_ROWS = 400;

    private LinearLayout groups, channels;
    private TextView header, status;
    private Iptv.Source source;
    private String category;
    private final List<Iptv.Channel> shown = new ArrayList<>();
    private int loadId = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout root = Ui.horizontal(this);
        root.setBackgroundColor(Ui.BG);

        ScrollView left = new FocusScroll.Vertical(this);
        left.setBackgroundColor(Ui.PANEL);
        left.setVerticalScrollBarEnabled(false);
        groups = Ui.vertical(this);
        groups.setPadding(Ui.dp(this, 20), Ui.dp(this, 28), Ui.dp(this, 16), Ui.dp(this, 28));
        left.addView(groups);
        root.addView(left, new LinearLayout.LayoutParams(Ui.dp(this, 250), ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout right = Ui.vertical(this);
        right.setPadding(Ui.dp(this, 32), Ui.dp(this, 28), Ui.dp(this, 32), 0);
        header = Ui.heading(this, "Live TV", 28);
        right.addView(header);
        status = Ui.text(this, "", 13, Ui.MUTED);
        status.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 10));
        right.addView(status);
        ScrollView sc = new FocusScroll.Vertical(this);
        sc.setVerticalScrollBarEnabled(false);
        channels = Ui.vertical(this);
        channels.setPadding(0, 0, 0, Ui.dp(this, 40));
        sc.addView(channels);
        right.addView(sc, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(right, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        setContentView(root);

        List<Iptv.Source> all = Iptv.sources();
        if (all.isEmpty()) {
            status.setText("No IPTV provider yet.");
            View add = Ui.button(this, R.drawable.ic_plus, "Add an IPTV provider", v -> startActivity(new Intent(this, IptvActivity.class)));
            channels.addView(add);
            add.post(add::requestFocus);
            return;
        }
        String last = Store.get("live_source");
        source = all.get(0);
        for (Iptv.Source s : all) if (s.id.equals(last)) source = s;
        loadGroups();
    }

    private void loadGroups() {
        groups.removeAllViews();
        List<Iptv.Source> all = Iptv.sources();
        if (all.size() > 1) {
            TextView t = Ui.eyebrow(this, "Provider");
            t.setPadding(0, 0, 0, Ui.dp(this, 6));
            groups.addView(t);
            for (Iptv.Source s : all) {
                groups.addView(Ui.pill(this, s.name, s.id.equals(source.id), v -> {
                    source = s;
                    Store.put("live_source", s.id);
                    loadGroups();
                }));
            }
        }
        TextView t = Ui.eyebrow(this, "Groups");
        t.setPadding(0, Ui.dp(this, 14), 0, Ui.dp(this, 6));
        groups.addView(t);
        status.setText("Loading channels…");
        Iptv.Source src = source;
        App.async(() -> {
            Iptv.channels(src);   // warm the channel list
            return Iptv.liveCategories(src);
        }, cats -> {
            if (src != source) return;
            String lastCat = Store.get("live_cat:" + src.id);
            String first = null;
            View firstView = null;
            for (Iptv.Category c : cats) {
                TextView b = groupButton(c.name, c.id);
                groups.addView(b);
                if (first == null || c.id.equals(lastCat)) {
                    first = c.id;
                    firstView = b;
                }
            }
            if (cats.isEmpty()) {
                status.setText("This provider has no live channels.");
                return;
            }
            showCategory(first, nameOf(cats, first));
            View f = firstView;
            if (f != null) f.post(f::requestFocus);
        }, e -> status.setText("Couldn't load channels: " + App.message(e)));
    }

    private static String nameOf(List<Iptv.Category> cats, String id) {
        for (Iptv.Category c : cats) if (c.id.equals(id)) return c.name;
        return "";
    }

    private TextView groupButton(String name, String id) {
        TextView b = Ui.single(Ui.text(this, name, 14, Ui.MUTED));
        b.setFocusable(true);
        b.setClickable(true);
        b.setGravity(Gravity.CENTER_VERTICAL);
        b.setPadding(Ui.dp(this, 14), 0, Ui.dp(this, 10), 0);
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_focused}, Ui.rounded(this, Ui.TEXT, 0, 10));
        s.addState(new int[]{android.R.attr.state_selected}, Ui.rounded(this, Ui.BUTTON, 0, 10));
        s.addState(new int[]{}, Ui.rounded(this, Color.TRANSPARENT, 0, 10));
        b.setBackground(s);
        b.setTextColor(new android.content.res.ColorStateList(
                new int[][]{{android.R.attr.state_focused}, {android.R.attr.state_selected}, {}},
                new int[]{Ui.BG, Ui.TEXT, Ui.MUTED}));
        b.setTag(id);
        b.setOnClickListener(v -> {
            showCategory(id, name);
            View first = channels.getChildAt(0);
            if (first != null) first.requestFocus();
        });
        // Moving through groups previews their channels.
        b.setOnFocusChangeListener((v, has) -> { if (has && !id.equals(category)) showCategory(id, name); });
        b.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 40)));
        return b;
    }

    private void showCategory(String id, String name) {
        category = id;
        Store.put("live_cat:" + source.id, id);
        for (int i = 0; i < groups.getChildCount(); i++) {
            View v = groups.getChildAt(i);
            v.setSelected(id.equals(v.getTag()));
        }
        header.setText(name);
        int my = ++loadId;
        Iptv.Source src = source;
        App.async(() -> Iptv.channelsIn(src, id), list -> {
            if (my != loadId) return;
            shown.clear();
            shown.addAll(list.size() > MAX_ROWS ? list.subList(0, MAX_ROWS) : list);
            status.setText(list.size() + " channels" + (list.size() > MAX_ROWS ? " · showing the first " + MAX_ROWS : ""));
            channels.removeAllViews();
            for (int i = 0; i < shown.size(); i++) channels.addView(channelRow(shown.get(i), i));
            for (int i = 0; i < Math.min(12, shown.size()); i++) loadGuide(i);
        }, e -> status.setText(App.message(e)));
    }

    private View channelRow(Iptv.Channel c, int index) {
        LinearLayout r = Ui.horizontal(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setFocusable(true);
        r.setClickable(true);
        r.setPadding(Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 14), Ui.dp(this, 10));
        StateListDrawable bg = new StateListDrawable();
        bg.addState(new int[]{android.R.attr.state_focused}, Ui.rounded(this, Ui.BUTTON, Ui.TEXT, 12));
        bg.addState(new int[]{}, Ui.rounded(this, Color.TRANSPARENT, 0, 12));
        r.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, Ui.dp(this, 2), 0, Ui.dp(this, 2));
        r.setLayoutParams(lp);

        TextView num = Ui.text(this, c.num, 13, Ui.DIM);
        num.setGravity(Gravity.END);
        r.addView(num, new LinearLayout.LayoutParams(Ui.dp(this, 40), ViewGroup.LayoutParams.WRAP_CONTENT));

        FrameLayout logoBox = new FrameLayout(this);
        logoBox.setBackground(Ui.rounded(this, Ui.alpha(Color.WHITE, 0.06f), 0, 8));
        ImageView logo = new ImageView(this);
        logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int p = Ui.dp(this, 5);
        logo.setPadding(p, p, p, p);
        logoBox.addView(logo, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        ImageLoader.load(logo, c.logo, Ui.dp(this, 72));
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(Ui.dp(this, 72), Ui.dp(this, 44));
        llp.setMargins(Ui.dp(this, 12), 0, Ui.dp(this, 14), 0);
        r.addView(logoBox, llp);

        LinearLayout mid = Ui.vertical(this);
        TextView name = Ui.single(Ui.heading(this, c.name, 15));
        mid.addView(name);
        TextView now = Ui.single(Ui.text(this, "", 13, Ui.MUTED));
        now.setPadding(0, Ui.dp(this, 4), 0, 0);
        mid.addView(now);
        FrameLayout bar = new FrameLayout(this);
        bar.setVisibility(View.GONE);
        mid.addView(bar, new LinearLayout.LayoutParams(Ui.dp(this, 220), Ui.dp(this, 3)));
        r.addView(mid, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView next = Ui.single(Ui.text(this, "", 12, Ui.DIM));
        next.setGravity(Gravity.END);
        r.addView(next, new LinearLayout.LayoutParams(Ui.dp(this, 260), ViewGroup.LayoutParams.WRAP_CONTENT));

        r.setTag(new View[]{now, next, bar});
        r.setOnClickListener(v -> play(index));
        r.setOnFocusChangeListener((v, has) -> {
            if (!has) return;
            for (int i = index; i < Math.min(index + 8, shown.size()); i++) loadGuide(i);
        });
        return r;
    }

    private final java.util.Set<String> guideRequested = new java.util.HashSet<>();

    private void loadGuide(int index) {
        if (index >= shown.size() || index >= channels.getChildCount()) return;
        Iptv.Channel c = shown.get(index);
        String key = source.id + "|" + c.id;
        if (!guideRequested.add(key)) return;
        View row = channels.getChildAt(index);
        Iptv.Source src = source;
        App.async(() -> Iptv.nowNext(src, c), progs -> {
            if (!(row.getTag() instanceof View[]) || progs.isEmpty()) return;
            View[] v = (View[]) row.getTag();
            Iptv.Program now = progs.get(0);
            ((TextView) v[0]).setText(time(now.start) + "–" + time(now.end) + "   " + now.title);
            if (progs.size() > 1) ((TextView) v[1]).setText("Next  " + time(progs.get(1).start) + "  " + progs.get(1).title);
            long span = Math.max(1, now.end - now.start);
            double f = Math.max(0, Math.min(1, (System.currentTimeMillis() - now.start) / (double) span));
            FrameLayout bar = (FrameLayout) v[2];
            bar.removeAllViews();
            bar.setBackgroundColor(Ui.alpha(Color.WHITE, 0.15f));
            View fill = new View(this);
            fill.setBackgroundColor(Ui.ACCENT);
            bar.addView(fill, new FrameLayout.LayoutParams((int) (Ui.dp(this, 220) * f), ViewGroup.LayoutParams.MATCH_PARENT));
            bar.setVisibility(View.VISIBLE);
        }, null);
    }

    private String time(long ms) {
        return ms <= 0 ? "" : DateFormat.getTimeFormat(this).format(new Date(ms));
    }

    private void play(int index) {
        Iptv.Channel c = shown.get(index);
        Intent i = new Intent(this, PlayerActivity.class)
                .putExtra("live", true)
                .putExtra("live_source", source.id)
                .putExtra("live_category", category)
                .putExtra("live_index", index)
                .putExtra("url", Iptv.liveUrl(source, c, false))
                .putExtra("pi_name", c.name);
        startActivity(i);
    }

    static String clean(String s) {
        return TextUtils.isEmpty(s) ? "" : s;
    }

    static Typeface bold() {
        return Typeface.create("sans-serif", Typeface.BOLD);
    }
}
