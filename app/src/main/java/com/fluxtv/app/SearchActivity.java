package com.fluxtv.app;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

/** One search across your libraries and every streaming add-on, grouped into Movies and Shows. */
public class SearchActivity extends Activity {

    private EditText input;
    private LinearLayout results;
    private TextView status;
    private int searchId = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout content = Ui.page(this, "Search");
        content.setClipChildren(false);

        LinearLayout bar = Ui.horizontal(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackground(Ui.rounded(this, Ui.BUTTON, 0, 14));
        bar.setPadding(Ui.dp(this, 16), Ui.dp(this, 4), Ui.dp(this, 6), Ui.dp(this, 4));
        bar.addView(Ui.icon(this, R.drawable.ic_search, 20, Ui.MUTED));
        input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("Movies and shows, from your library and add-ons");
        input.setTextColor(Ui.TEXT);
        input.setHintTextColor(Ui.DIM);
        input.setTextSize(18);
        input.setBackground(null);
        input.setPadding(Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 12), Ui.dp(this, 10));
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
        content.addView(bar, new LinearLayout.LayoutParams(Ui.dp(this, 720), ViewGroup.LayoutParams.WRAP_CONTENT));

        status = Ui.text(this, "", 13, Ui.MUTED);
        status.setPadding(0, Ui.dp(this, 12), 0, 0);
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
        status.setText("Searching your library and add-ons…");
        results.removeAllViews();
        App.async(() -> Blend.search(q), rows -> {
            if (myId != searchId) return;
            if (rows.isEmpty()) {
                status.setText("Nothing found for \"" + q + "\"");
                return;
            }
            status.setText("Titles in your library are marked with their server.");
            for (Addons.Row r : rows) {
                LinearLayout row = Ui.row(this, results, r.title);
                for (JSONObject m : r.items) {
                    row.addView(Ui.poster(this, m, v -> DetailActivity.open(this, m), null));
                }
            }
        }, e -> status.setText(App.message(e)));
    }
}
