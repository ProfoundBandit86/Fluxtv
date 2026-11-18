package com.fluxtv.app;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Settings → Live TV → Channels → "Hide groups and channels": every group from every provider with a Shown/Hidden
 * switch; open a group to hide single channels. Hidden ones disappear from the guide, zapping, Home and Sports.
 */
public class IptvFilterActivity extends Activity {

    private LinearLayout list;
    private String sourceId, groupKey;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        sourceId = getIntent().getStringExtra("source");
        groupKey = getIntent().getStringExtra("group");
        Iptv.Source s = sourceId == null ? null : Iptv.source(sourceId);
        list = Ui.page(this, s != null && groupKey != null ? Iptv.groupName(s, groupKey) : "Hide groups and channels");
        TextView hint = Ui.text(this, s != null && groupKey != null
                ? "Hidden channels don't show in the guide, when changing channels, on Home or in Sports."
                : "Hidden groups don't show in Live TV. Open a group to hide single channels. Favorites always show.", 14, Ui.MUTED);
        hint.setPadding(0, 0, 0, Ui.dp(this, 10));
        list.addView(hint);
        TextView loading = Ui.text(this, "Loading…", 14, Ui.DIM);
        list.addView(loading);
        App.async(this::rows, rows -> {
            list.removeView(loading);
            if (rows.isEmpty()) list.addView(Ui.text(this, "No channels.", 14, Ui.DIM));
            View first = null;
            for (Object[] r : rows) {
                View v = row(r);
                list.addView(v);
                if (first == null) first = v.findViewWithTag("toggle");
            }
            if (first != null) first.requestFocus();
        }, e -> loading.setText("Couldn't load channels: " + App.message(e)));
    }

    /** Rows: [source, group key, label, count, channel or null]. */
    private List<Object[]> rows() throws Exception {
        List<Object[]> out = new ArrayList<>();
        java.util.Set<String> regions = Iptv.regions();
        boolean many = Iptv.sources().size() > 1;
        for (Iptv.Source s : Iptv.sources()) {
            if (sourceId != null && !sourceId.equals(s.id)) continue;
            List<Iptv.Channel> chans = Iptv.channels(s);
            if (groupKey != null) {
                for (Iptv.Channel c : chans) if (groupKey.equals(Iptv.groupKey(s, c))) out.add(new Object[]{s, groupKey, c.name, 0, c});
                continue;
            }
            Map<String, Integer> counts = new LinkedHashMap<>();
            for (Iptv.Channel c : chans) {
                String g = Iptv.groupKey(s, c);
                counts.put(g, counts.containsKey(g) ? counts.get(g) + 1 : 1);
            }
            for (Map.Entry<String, Integer> e : counts.entrySet()) {
                String name = Iptv.groupName(s, e.getKey());
                String region = Iptv.regionOf(name);
                String note = !regions.isEmpty() && !region.isEmpty() && !regions.contains(region) ? "  ·  other country" : "";
                out.add(new Object[]{s, e.getKey(), (many ? s.name + " · " : "") + name + note, e.getValue(), null});
            }
        }
        return out;
    }

    private View row(Object[] r) {
        Iptv.Source s = (Iptv.Source) r[0];
        String g = (String) r[1];
        Iptv.Channel c = (Iptv.Channel) r[4];
        LinearLayout line = Ui.horizontal(this);
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setPadding(0, Ui.dp(this, 2), 0, Ui.dp(this, 2));
        addToggle(line, s, g, c);
        if (c == null) {
            int count = (Integer) r[3];
            android.widget.Button open = Ui.listButton(this, r[2] + "\n" + String.format(Locale.getDefault(), "%,d channel%s  ›", count, count == 1 ? "" : "s"),
                    v -> startActivity(new android.content.Intent(this, IptvFilterActivity.class).putExtra("source", s.id).putExtra("group", g)));
            open.setTextSize(15);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.setMargins(Ui.dp(this, 6), Ui.dp(this, 3), 0, Ui.dp(this, 3));
            line.addView(open, lp);
        } else {
            TextView name = Ui.single(Ui.text(this, (String) r[2], 16, Ui.TEXT));
            name.setPadding(Ui.dp(this, 10), 0, 0, 0);
            line.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        return line;
    }

    /** The Shown / Hidden switch at the start of a row; pressing it flips it in place. */
    private void addToggle(LinearLayout line, Iptv.Source s, String g, Iptv.Channel c) {
        boolean hidden = c != null ? Iptv.channelHidden(s, c) : Iptv.groupHidden(s, g);
        TextView t = Ui.pill(this, hidden ? "Hidden" : "Shown", !hidden, v -> {
            if (c != null) Iptv.setChannelHidden(s, c, !hidden); else Iptv.setGroupHidden(s, g, !hidden);
            line.removeViewAt(0);
            addToggle(line, s, g, c);
            line.getChildAt(0).requestFocus();
        });
        t.setTag("toggle");
        t.setMinWidth(Ui.dp(this, 96));
        t.setContentDescription((hidden ? "Hidden. " : "Shown. ") + (c != null ? c.name : Iptv.groupName(s, g)));
        line.addView(t, 0);
    }
}
