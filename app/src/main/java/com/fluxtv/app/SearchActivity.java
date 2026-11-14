package com.fluxtv.app;

import android.app.Activity;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

/** Searches every add-on catalog that supports search. */
public class SearchActivity extends Activity {

    private EditText input;
    private LinearLayout results;
    private TextView status;
    private int searchId = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Ui.BG);
        scroll.setClipChildren(false);
        LinearLayout content = Ui.vertical(this);
        int pad = Ui.dp(this, 48);
        content.setPadding(pad, Ui.dp(this, 32), pad, pad);
        content.setClipChildren(false);
        scroll.addView(content);
        setContentView(scroll);

        content.addView(Ui.heading(this, "Search", 30));
        LinearLayout bar = Ui.horizontal(this);
        input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("Movie or show name");
        input.setTextColor(Ui.TEXT);
        input.setHintTextColor(Ui.MUTED);
        input.setTextSize(20);
        input.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        input.setOnEditorActionListener((v, actionId, event) -> {
            boolean enter = event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    && event.getAction() == KeyEvent.ACTION_DOWN;
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE || enter) {
                search();
                return true;
            }
            return false;
        });
        bar.addView(input, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        bar.addView(Ui.button(this, "Search", v -> search()));
        content.addView(bar);

        status = Ui.text(this, "", 17, Ui.MUTED);
        status.setPadding(0, Ui.dp(this, 10), 0, 0);
        content.addView(status);
        results = Ui.vertical(this);
        results.setClipChildren(false);
        content.addView(results);
        input.requestFocus();
    }

    private void search() {
        String q = input.getText().toString().trim();
        if (q.isEmpty()) return;
        int myId = ++searchId;
        status.setText("Searching…");
        results.removeAllViews();
        App.async(() -> Addons.search(q), rows -> {
            if (myId != searchId) return;
            if (rows.isEmpty()) {
                status.setText("Nothing found for \"" + q + "\"");
                return;
            }
            status.setText("");
            for (Addons.Row r : rows) {
                LinearLayout row = Ui.row(this, results, r.title);
                for (JSONObject m : r.items) {
                    row.addView(Ui.poster(this, m.optString("name"), m.optString("poster"), v -> DetailActivity.open(this, m)));
                }
            }
        }, e -> status.setText(App.message(e)));
    }
}
