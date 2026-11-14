package com.fluxtv.app;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

/** A person: photo, short bio, and everything they worked on, newest to oldest. */
public class PersonActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout content = Ui.page(this, null);

        LinearLayout head = Ui.horizontal(this);
        ImageView photo = Ui.image(this, Ui.BUTTON, 14);
        head.addView(photo, new LinearLayout.LayoutParams(Ui.dp(this, 150), Ui.dp(this, 225)));
        LinearLayout info = Ui.vertical(this);
        info.setPadding(Ui.dp(this, 28), 0, 0, 0);
        TextView name = Ui.heading(this, getIntent().getStringExtra("name"), 34);
        info.addView(name);
        TextView facts = Ui.text(this, "", 13, Ui.MUTED);
        facts.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 10));
        info.addView(facts);
        TextView bio = Ui.text(this, "Loading…", 14, Ui.alpha(Ui.TEXT, 0.85f));
        bio.setMaxLines(6);
        bio.setEllipsize(TextUtils.TruncateAt.END);
        bio.setLineSpacing(0, 1.3f);
        info.addView(bio);
        head.addView(info, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        content.addView(head, new LinearLayout.LayoutParams(Ui.dp(this, 900), ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView h = Ui.heading(this, "Filmography · newest first", 18);
        h.setPadding(0, Ui.dp(this, 26), 0, Ui.dp(this, 8));
        content.addView(h);
        LinearLayout list = Ui.vertical(this);
        content.addView(list, new LinearLayout.LayoutParams(Ui.dp(this, 900), ViewGroup.LayoutParams.WRAP_CONTENT));

        int id = getIntent().getIntExtra("personId", 0);
        App.async(() -> Tmdb.personPage(id), p -> {
            name.setText(p.optString("name"));
            if (!p.optString("photo").isEmpty()) ImageLoader.load(photo, p.optString("photo"), Ui.dp(this, 150));
            StringBuilder f = new StringBuilder(p.optString("known"));
            if (p.optString("birthday").length() >= 4) f.append(f.length() > 0 ? "   ·   " : "").append("Born ").append(p.optString("birthday"));
            if (!p.optString("place").isEmpty()) f.append(", ").append(p.optString("place"));
            facts.setText(f);
            bio.setText(p.optString("bio").isEmpty() ? "No biography available." : p.optString("bio"));
            JSONArray credits = p.optJSONArray("credits");
            View first = null;
            if (credits != null) for (int i = 0; i < credits.length(); i++) {
                View row = creditRow(credits.optJSONObject(i));
                list.addView(row);
                if (first == null) first = row;
            }
            View ff = first;
            if (ff != null) ff.post(ff::requestFocus);
        }, e -> bio.setText(App.message(e)));
    }

    private View creditRow(JSONObject c) {
        LinearLayout r = Ui.horizontal(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setFocusable(true);
        r.setClickable(true);
        r.setPadding(Ui.dp(this, 10), Ui.dp(this, 8), Ui.dp(this, 14), Ui.dp(this, 8));
        StateListDrawable bg = new StateListDrawable();
        bg.addState(new int[]{android.R.attr.state_focused}, Ui.rounded(this, Ui.BUTTON, Ui.TEXT, 12));
        bg.addState(new int[]{}, Ui.rounded(this, Color.TRANSPARENT, 0, 12));
        r.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, Ui.dp(this, 2), 0, Ui.dp(this, 2));
        r.setLayoutParams(lp);

        String date = c.optString("date");
        TextView year = Ui.heading(this, date.length() >= 4 ? date.substring(0, 4) : "Soon", 15);
        year.setTextColor(Ui.MUTED);
        r.addView(year, new LinearLayout.LayoutParams(Ui.dp(this, 56), ViewGroup.LayoutParams.WRAP_CONTENT));
        ImageView poster = Ui.image(this, Ui.BUTTON, 6);
        ImageLoader.load(poster, c.optString("poster"), Ui.dp(this, 46));
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 66));
        pp.setMargins(0, 0, Ui.dp(this, 14), 0);
        r.addView(poster, pp);
        LinearLayout mid = Ui.vertical(this);
        TextView t = Ui.single(Ui.text(this, c.optString("name"), 15, Ui.TEXT));
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        mid.addView(t);
        String role = c.optString("role");
        if (c.optInt("episodes") > 0) role += (role.isEmpty() ? "" : "  ·  ") + c.optInt("episodes") + " episodes";
        TextView rl = Ui.single(Ui.text(this, role, 12, Ui.MUTED));
        rl.setPadding(0, Ui.dp(this, 3), 0, 0);
        mid.addView(rl);
        r.addView(mid, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView kind = Ui.chip(this, "series".equals(c.optString("type")) ? "TV" : "Movie", Ui.BORDER, Ui.MUTED);
        r.addView(kind);
        r.setOnClickListener(v -> DetailActivity.open(this, c));
        return r;
    }
}
