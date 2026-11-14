package com.fluxtv.app;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.List;

/** Cast & Crew for a title: round headshots with roles. Pick a person to see their work. */
public class CreditsActivity extends Activity {

    public static void open(Context c, String id, String type, String name) {
        c.startActivity(new Intent(c, CreditsActivity.class).putExtra("id", id).putExtra("type", type).putExtra("name", name));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout content = Ui.page(this, "Cast & Crew");
        content.setClipChildren(false);
        TextView sub = Ui.text(this, getIntent().getStringExtra("name"), 15, Ui.MUTED);
        content.addView(sub);
        TextView status = Ui.text(this, "Loading…", 13, Ui.MUTED);
        status.setPadding(0, Ui.dp(this, 8), 0, 0);
        content.addView(status);
        String id = getIntent().getStringExtra("id"), type = getIntent().getStringExtra("type");
        App.async(() -> Tmdb.credits(id, type), lists -> {
            status.setText("");
            View first = addRow(content, "Cast", lists.get(0));
            View f2 = addRow(content, "Crew", lists.get(1));
            View f = first != null ? first : f2;
            if (f != null) f.post(f::requestFocus);
            if (first == null && f2 == null) status.setText("No cast or crew listed.");
        }, e -> status.setText(App.message(e)));
    }

    private View addRow(LinearLayout content, String title, List<JSONObject> people) {
        if (people.isEmpty()) return null;
        LinearLayout row = Ui.row(this, content, title);
        View first = null;
        for (JSONObject p : people) {
            View v = personCard(this, p);
            row.addView(v);
            if (first == null) first = (View) v.getTag();
        }
        return first;
    }

    /** Round headshot + name + role. */
    static View personCard(Activity a, JSONObject p) {
        LinearLayout col = Ui.vertical(a);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(Ui.dp(a, 118), ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.setMargins(0, Ui.dp(a, 6), Ui.dp(a, 10), Ui.dp(a, 6));
        col.setLayoutParams(clp);
        FrameLayout ring = new FrameLayout(a);
        ring.setFocusable(true);
        ring.setClickable(true);
        int pad = Ui.dp(a, 4);
        ring.setPadding(pad, pad, pad, pad);
        StateListDrawable s = new StateListDrawable();
        GradientDrawable on = new GradientDrawable();
        on.setShape(GradientDrawable.OVAL);
        on.setColor(Color.TRANSPARENT);
        on.setStroke(Ui.dp(a, 3), Ui.TEXT);
        s.addState(new int[]{android.R.attr.state_focused}, on);
        s.addState(new int[]{}, new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        ring.setBackground(s);
        ImageView img = new ImageView(a);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(Ui.BUTTON);
        img.setBackground(circle);
        img.setClipToOutline(true);
        ring.addView(img, new FrameLayout.LayoutParams(-1, -1));
        if (p.optString("photo").isEmpty()) {
            TextView initials = Ui.heading(a, initials(p.optString("name")), 26);
            initials.setGravity(Gravity.CENTER);
            initials.setTextColor(Ui.MUTED);
            ring.addView(initials, new FrameLayout.LayoutParams(-1, -1));
        } else {
            ImageLoader.load(img, p.optString("photo"), Ui.dp(a, 110));
        }
        col.addView(ring, new LinearLayout.LayoutParams(Ui.dp(a, 104), Ui.dp(a, 104)));
        TextView n = Ui.single(Ui.text(a, p.optString("name"), 13, Ui.TEXT));
        n.setGravity(Gravity.CENTER);
        n.setPadding(0, Ui.dp(a, 8), 0, 0);
        col.addView(n, new LinearLayout.LayoutParams(-1, -2));
        TextView r = Ui.text(a, p.optString("role"), 11, Ui.DIM);
        r.setGravity(Gravity.CENTER);
        r.setMaxLines(2);
        r.setEllipsize(android.text.TextUtils.TruncateAt.END);
        col.addView(r, new LinearLayout.LayoutParams(-1, -2));
        ring.setOnFocusChangeListener((v, has) -> v.animate().scaleX(has ? 1.07f : 1f).scaleY(has ? 1.07f : 1f).setDuration(140).start());
        ring.setOnClickListener(v -> a.startActivity(new Intent(a, PersonActivity.class)
                .putExtra("personId", p.optInt("personId")).putExtra("name", p.optString("name"))));
        col.setTag(ring);
        return col;
    }

    static String initials(String name) {
        StringBuilder sb = new StringBuilder();
        for (String w : name.trim().split("\\s+")) if (!w.isEmpty() && sb.length() < 2) sb.append(Character.toUpperCase(w.charAt(0)));
        return sb.toString();
    }
}
