package com.fluxtv.app;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Every title in one genre, as a poster grid that keeps loading as you go. */
public class GenreActivity extends Activity {

    private static final int PER_LINE = 6;

    private LinearLayout grid;
    private LinearLayout line;
    private String type, genre;
    private int loaded;
    private boolean loading, done;
    private View more;
    private LinearLayout moreHolder;
    private final Set<String> seen = new HashSet<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        type = getIntent().getStringExtra("type");
        genre = getIntent().getStringExtra("genre");
        LinearLayout content = Ui.page(this, genre + ("series".equals(type) ? " shows" : " movies"));
        content.setClipChildren(false);
        grid = Ui.vertical(this);
        grid.setClipChildren(false);
        content.addView(grid);
        loadMore();
    }

    private void loadMore() {
        if (loading || done) return;
        loading = true;
        if (more != null) ((TextView) more).setText("Loading…");
        App.async(() -> Addons.genrePage(type, genre, loaded), items -> {
            loading = false;
            if (moreHolder != null) {
                grid.removeView(moreHolder);
                moreHolder = null;
                more = null;
            }
            boolean first = loaded == 0;
            loaded += items.size();
            int added = 0;
            View firstCard = null;
            for (JSONObject p : items) {
                if (!seen.add(p.optString("id"))) continue;
                if (line == null || line.getChildCount() >= PER_LINE) {
                    line = Ui.horizontal(this);
                    line.setClipChildren(false);
                    line.setTag("row");
                    grid.addView(line);
                }
                View card = Ui.poster(this, p, v -> DetailActivity.open(this, p), null);
                line.addView(card);
                if (firstCard == null) firstCard = card;
                added++;
            }
            if (added == 0) done = true;
            if (first && grid.getChildCount() == 0) {
                grid.addView(Ui.text(this, "Nothing found in " + genre + ".", 15, Ui.MUTED));
            }
            if (!done) {
                more = Ui.button(this, "Load more", v -> loadMore());
                moreHolder = Ui.horizontal(this);
                moreHolder.setPadding(0, Ui.dp(this, 16), 0, 0);
                moreHolder.addView(more);
                grid.addView(moreHolder);
            }
            if (first && firstCard != null) firstCard.requestFocus();
        }, e -> {
            loading = false;
            grid.addView(Ui.text(this, "Couldn't load: " + App.message(e), 14, Ui.WARN));
        });
    }
}
