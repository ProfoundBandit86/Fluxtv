package com.fluxtv.app;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.os.Bundle;
import android.speech.RecognizerIntent;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Search: opens straight into voice search. Gemini turns what you say into matching titles;
 * your library, IPTV and add-ons are searched too.
 */
public class SearchActivity extends FluxActivity {

    @Override
    protected String sidebarKey() {
        return "search";
    }


    private static final int VOICE = 41;
    private EditText input;
    private LinearLayout results;
    private TextView status;
    private Button mic;
    private int searchId = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout content = Ui.page(this, "Search");
        content.setClipChildren(false);

        LinearLayout bar = Ui.horizontal(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        mic = Ui.button(this, R.drawable.ic_mic, "Speak", v -> voice());
        bar.addView(mic);
        LinearLayout box = Ui.horizontal(this);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setBackground(Ui.rounded(this, Ui.BUTTON, 0, 14));
        box.setPadding(Ui.dp(this, 14), Ui.dp(this, 2), Ui.dp(this, 6), Ui.dp(this, 2));
        box.addView(Ui.icon(this, R.drawable.ic_search, 18, Ui.MUTED));
        input = new EditText(this);
        input.setSingleLine(true);
        input.setHint(Gemini.enabled() ? "Try \"funny 90s heist movies\" or a title" : "Movie or show name");
        input.setTextColor(Ui.TEXT);
        input.setHintTextColor(Ui.DIM);
        input.setTextSize(17);
        input.setBackground(null);
        input.setPadding(Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 12), Ui.dp(this, 10));
        input.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        input.setOnEditorActionListener((v, actionId, event) -> {
            boolean enter = event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER && event.getAction() == KeyEvent.ACTION_DOWN;
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE || enter) {
                search();
                return true;
            }
            return false;
        });
        box.addView(input, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        bar.addView(box, new LinearLayout.LayoutParams(Ui.dp(this, 620), ViewGroup.LayoutParams.WRAP_CONTENT));
        content.addView(bar);

        status = Ui.text(this, Gemini.enabled() ? "Gemini is on: describe what you feel like watching." : "", 13, Ui.MUTED);
        status.setPadding(0, Ui.dp(this, 12), 0, 0);
        content.addView(status);
        results = Ui.vertical(this);
        results.setClipChildren(false);
        content.addView(results);

        Gemini.warmUp();   // connect to Google while you're still speaking
        if (getIntent().getBooleanExtra("voice", false) && savedInstanceState == null) {
            mic.post(this::voice);
        } else {
            input.requestFocus();
        }
    }

    private void voice() {
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PROMPT, "What do you want to watch?");
        try {
            startActivityForResult(i, VOICE);
        } catch (ActivityNotFoundException e) {
            status.setText("Voice search isn't available on this TV. Type instead.");
            keyboard();
        }
    }

    private void keyboard() {
        input.requestFocus();
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != VOICE) return;
        ArrayList<String> said = data == null ? null : data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
        if (resultCode == RESULT_OK && said != null && !said.isEmpty()) {
            input.setText(said.get(0));
            search();
        } else {
            mic.requestFocus();
        }
    }

    private void search() {
        String q = input.getText().toString().trim();
        if (q.isEmpty()) return;
        int myId = ++searchId;
        status.setText("Searching for \"" + q + "\"…");
        results.removeAllViews();
        LinearLayout geminiSlot = Ui.vertical(this), restSlot = Ui.vertical(this);
        geminiSlot.setClipChildren(false);
        restSlot.setClipChildren(false);
        results.addView(geminiSlot);
        results.addView(restSlot);
        final View[] firstCard = new View[1];
        final int[] pending = {Gemini.enabled() ? 2 : 1};
        final boolean[] any = {false};
        Runnable done = () -> {
            if (--pending[0] > 0 || myId != searchId) return;
            status.setText(any[0] ? "" : "Nothing found for \"" + q + "\"");
        };
        if (Gemini.enabled()) {
            App.async(() -> Gemini.search(q), picks -> {
                if (myId != searchId) return;
                if (!picks.isEmpty()) {
                    any[0] = true;
                    LinearLayout row = Ui.row(this, geminiSlot, "Gemini picks", "for \"" + q + "\"");
                    for (JSONObject m : picks) {
                        View card = Ui.poster(this, m, v -> DetailActivity.open(this, m), null);
                        row.addView(card);
                        if (firstCard[0] == null) {
                            firstCard[0] = card;
                            card.post(card::requestFocus);
                        }
                    }
                }
                done.run();
            }, e -> {
                if (myId != searchId) return;
                TextView t = Ui.text(this, "Gemini: " + App.message(e), 13, Ui.WARN);
                t.setPadding(0, Ui.dp(this, 10), 0, 0);
                geminiSlot.addView(t);
                done.run();
            });
        }
        App.async(() -> Blend.search(q), rows -> {
            if (myId != searchId) return;
            for (Addons.Row r : rows) {
                if (r.items.isEmpty()) continue;
                any[0] = true;
                LinearLayout row = Ui.row(this, restSlot, r.title);
                for (JSONObject m : r.items) {
                    View card = Ui.poster(this, m, v -> DetailActivity.open(this, m), null);
                    row.addView(card);
                    if (firstCard[0] == null && !Gemini.enabled()) {
                        firstCard[0] = card;
                        card.post(card::requestFocus);
                    }
                }
            }
            done.run();
        }, e -> done.run());
    }
}
