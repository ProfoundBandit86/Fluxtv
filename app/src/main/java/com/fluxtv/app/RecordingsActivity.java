package com.fluxtv.app;

import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** DVR: what's recording now, what's scheduled, and finished recordings to watch or delete. */
public class RecordingsActivity extends FluxActivity {

    @Override
    protected String sidebarKey() {
        return "live";
    }

    private LinearLayout content;
    private int shownVersion = -1;
    private String focusId;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        content = Ui.page(this, "Recordings");
    }

    @Override
    protected void onResume() {
        super.onResume();
        render();
        App.MAIN.postDelayed(refresh, 5000);
    }

    @Override
    protected void onPause() {
        super.onPause();
        App.MAIN.removeCallbacks(refresh);
    }

    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            if (Dvr.version() != shownVersion) {
                View f = getCurrentFocus();
                focusId = f != null && f.getTag() instanceof String ? (String) f.getTag() : null;
                render();
            }
            App.MAIN.postDelayed(this, 5000);
        }
    };

    private void render() {
        shownVersion = Dvr.version();
        while (content.getChildCount() > 1) content.removeViewAt(1);
        TextView where = Ui.text(this, "Saving to: " + Dvr.targetLabel(this) + "   ·   change in Settings → Live TV & DVR", 14, Ui.MUTED);
        where.setPadding(0, 0, 0, Ui.dp(this, 8));
        content.addView(where);
        if (!Dvr.exactAlarmsAllowed(this)) {
            TextView w = Ui.text(this, "Scheduled recordings may start a minute or two late: allow \"Alarms & reminders\" for Flux "
                    + "(Settings → Live TV & DVR).", 13, Ui.WARN);
            content.addView(w);
        }
        List<JSONObject> now = new ArrayList<>(), later = new ArrayList<>(), done = new ArrayList<>();
        for (JSONObject r : Dvr.all()) {
            String st = r.optString("status");
            if (Dvr.RECORDING.equals(st)) now.add(r);
            else if (Dvr.SCHEDULED.equals(st)) later.add(r);
            else done.add(r);
        }
        Collections.reverse(done);
        View first = null;
        if (now.isEmpty() && later.isEmpty() && done.isEmpty()) {
            TextView t = Ui.text(this, "Nothing recorded yet. In Live TV, press OK on a later programme to record it, or hold OK / "
                    + "press MENU while watching and choose Record.", 15, Ui.MUTED);
            t.setPadding(0, Ui.dp(this, 24), 0, 0);
            content.addView(t);
            View open = Ui.button(this, R.drawable.ic_live, "Open Live TV", v -> startActivity(new Intent(this, LiveActivity.class)));
            content.addView(open);
            open.requestFocus();
            return;
        }
        first = section("Recording now", now, first);
        first = section("Scheduled", later, first);
        first = section("Recorded", done, first);
        View target = focusId == null ? null : content.findViewWithTag(focusId);
        View f = target != null ? target : first;
        if (f != null) f.post(f::requestFocus);
    }

    private View section(String title, List<JSONObject> list, View first) {
        if (list.isEmpty()) return first;
        TextView h = Ui.heading(this, title, 18);
        h.setPadding(0, Ui.dp(this, 18), 0, Ui.dp(this, 6));
        content.addView(h);
        for (JSONObject r : list) {
            View row = row(r);
            content.addView(row);
            if (first == null) first = row;
        }
        return first;
    }

    private View row(JSONObject r) {
        LinearLayout row = Ui.horizontal(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setFocusable(true);
        row.setClickable(true);
        row.setTag(r.optString("id"));
        row.setBackground(Ui.focusable(this, Ui.PANEL, Ui.BUTTON, Ui.ACCENT));
        row.setPadding(Ui.dp(this, 16), Ui.dp(this, 10), Ui.dp(this, 16), Ui.dp(this, 10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, Ui.dp(this, 4), 0, Ui.dp(this, 4));
        row.setLayoutParams(lp);
        ImageView logo = new ImageView(this);
        logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        ImageLoader.load(logo, r.optString("logo"), Ui.dp(this, 80));
        row.addView(logo, new LinearLayout.LayoutParams(Ui.dp(this, 64), Ui.dp(this, 40)));
        LinearLayout txt = Ui.vertical(this);
        txt.setPadding(Ui.dp(this, 16), 0, 0, 0);
        String st = r.optString("status");
        txt.addView(Ui.single(Ui.heading(this, (Dvr.RECORDING.equals(st) ? "● " : "") + r.optString("title"), 17)));
        String sub = r.optString("channelName") + "  ·  " + Dvr.timeRange(r);
        if (r.optLong("size") > 0) sub += "  ·  " + mb(r.optLong("size"));
        txt.addView(Ui.single(Ui.text(this, sub, 13, Ui.MUTED)));
        if (Dvr.FAILED.equals(st)) txt.addView(Ui.single(Ui.text(this, "Failed: " + r.optString("error"), 13, Ui.WARN)));
        else if (r.optString("file").startsWith("smb://")) txt.addView(Ui.single(Ui.text(this, "On your network share", 12, Ui.DIM)));
        row.addView(txt, new LinearLayout.LayoutParams(0, -2, 1f));
        row.setOnClickListener(v -> open(r));
        row.setOnLongClickListener(v -> { menu(r); return true; });
        return row;
    }

    private static String mb(long b) {
        return b > 1024L * 1024 * 1024 ? String.format(java.util.Locale.US, "%.1f GB", b / 1073741824.0) : (b / 1048576) + " MB";
    }

    private void open(JSONObject r) {
        String st = r.optString("status");
        if (Dvr.SCHEDULED.equals(st)) { menu(r); return; }
        String f = r.optString("file");
        if (f.isEmpty()) { menu(r); return; }
        // Recordings play in Flux's player (it can also play one that's still recording).
        String url = f.startsWith("smb://") ? f : Uri.fromFile(new java.io.File(f)).toString();
        PlayInfo info = PlayInfo.of("movie", "dvr:" + r.optString("id"), "dvr:" + r.optString("id"), r.optString("title"),
                r.optString("logo"), r.optString("channelName") + " · " + Dvr.timeRange(r));
        startActivity(info.into(new Intent(this, PlayerActivity.class)).putExtra("url", url));
    }

    private void menu(JSONObject r) {
        String st = r.optString("status");
        List<String> items = new ArrayList<>();
        if (!Dvr.SCHEDULED.equals(st) && !r.optString("file").isEmpty()) items.add("Play");
        if (Dvr.RECORDING.equals(st)) items.add("Stop recording");
        if (Dvr.SCHEDULED.equals(st)) items.add("Cancel recording");
        if (!Dvr.SCHEDULED.equals(st)) items.add("Delete");
        new AlertDialog.Builder(this)
                .setTitle(r.optString("title"))
                .setMessage(r.optString("desc").isEmpty() ? r.optString("channelName") : r.optString("desc"))
                .setItems(items.toArray(new String[0]), (d, w) -> {
                    String it = items.get(w);
                    if (it.equals("Play")) open(r);
                    else if (it.startsWith("Stop")) Dvr.cancel(this, r.optString("id"));
                    else if (it.startsWith("Cancel")) Dvr.cancel(this, r.optString("id"));
                    else Dvr.delete(this, r.optString("id"));
                    App.MAIN.postDelayed(this::render, 400);
                })
                .show();
    }
}
