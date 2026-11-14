package com.fluxtv.app;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

/** One streaming service: Latest, Most popular and Top rated movies and shows. */
public class ServiceActivity extends FluxActivity {

    private LinearLayout content;
    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String extraKey = getIntent().getStringExtra("service");
        // Also opened by link (remote-button shortcuts): fluxtv://service/netflix
        if (extraKey == null && getIntent().getData() != null) extraKey = getIntent().getData().getLastPathSegment();
        final String key = extraKey == null ? "netflix" : extraKey;
        content = Ui.page(this, null);
        content.setClipChildren(false);

        LinearLayout head = Ui.horizontal(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        ImageView logo = new ImageView(this);
        logo.setScaleType(ImageView.ScaleType.FIT_START);
        logo.setAdjustViewBounds(true);
        logo.setMaxHeight(Ui.dp(this, 44));
        logo.setMaxWidth(Ui.dp(this, 220));
        logo.setVisibility(View.GONE);
        head.addView(logo, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView title = Ui.heading(this, "", 30);
        head.addView(title);
        content.addView(head);
        status = Ui.text(this, "Loading…", 13, Ui.MUTED);
        status.setPadding(0, Ui.dp(this, 8), 0, 0);
        content.addView(status);
        LinearLayout actions = Ui.horizontal(this);
        content.addView(actions);

        App.async(() -> Tmdb.service(key), s -> {
            if (s == null) {
                status.setText("Unknown service");
                return;
            }
            title.setText(s.name);
            if (!s.wordmark.isEmpty()) {
                logo.setColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN);
                ImageLoader.load(logo, s.wordmark, Ui.dp(this, 220), () -> {
                    logo.setVisibility(View.VISIBLE);
                    title.setVisibility(View.GONE);
                });
            }
            for (String pkg : s.packages) {
                if (Apps.isInstalled(this, pkg)) {
                    View open = Ui.button(this, R.drawable.ic_apps, "Open " + s.name, v -> Apps.launch(this, pkg));
                    actions.addView(open);
                    break;
                }
            }
            App.async(() -> Tmdb.serviceRows(s), rowsList -> {
                status.setText("What's on " + s.name + " in your region (" + Tmdb.region() + ")");
                View first = null;
                for (Addons.Row r : rowsList) {
                    LinearLayout row = Ui.row(this, content, r.title);
                    for (JSONObject m : r.items) {
                        View card = Ui.poster(this, m, v -> DetailActivity.open(this, m), null);
                        row.addView(card);
                        if (first == null) first = card;
                    }
                }
                if (rowsList.isEmpty()) status.setText("Nothing found for " + s.name + ".");
                View f = first;
                if (f != null) f.post(f::requestFocus);
            }, e -> status.setText(App.message(e)));
        }, e -> status.setText(App.message(e)));
    }
}
