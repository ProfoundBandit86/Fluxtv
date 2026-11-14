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


    private static final int VOICE = 41, MIC_PERMISSION = 42;
    private EditText input;
    private LinearLayout results, suggestions;
    private TextView status;
    private android.widget.ImageButton mic;
    private int searchId = 0;

    // Listening screen
    private android.widget.FrameLayout listen;
    private TextView heard, listenHint;
    private VoiceWave wave;
    private android.speech.SpeechRecognizer recognizer;
    private boolean listening;
    private long listenStarted;

    private static final String[] IDEAS = {"Funny 90s heist movies", "Something like Severance", "New action movies",
            "Feel-good shows to binge", "Best sci-fi of the last 5 years", "Movies set in Japan"};

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        android.widget.FrameLayout root = new android.widget.FrameLayout(this);
        root.setBackgroundColor(Ui.BG);
        android.widget.ScrollView scroll = new FocusScroll.Vertical(this);
        LinearLayout content = Ui.vertical(this);
        content.setClipChildren(false);
        content.setPadding(Ui.dp(this, 64), Ui.dp(this, 40), Ui.dp(this, 48), Ui.dp(this, 60));
        scroll.addView(content);
        root.addView(scroll);

        // The search pill: round mic on the left, then the text.
        LinearLayout pill = Ui.horizontal(this);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setBackground(Ui.rounded(this, Ui.PANEL, Ui.BORDER, 999));
        pill.setPadding(Ui.dp(this, 8), Ui.dp(this, 6), Ui.dp(this, 20), Ui.dp(this, 6));
        mic = new android.widget.ImageButton(this);
        mic.setImageResource(R.drawable.ic_mic);
        mic.setImageTintList(Ui.onFocusDark(Ui.BG));
        mic.setBackground(micBackground());
        mic.setContentDescription("Speak");
        mic.setOnClickListener(v -> voice());
        mic.setScaleType(android.widget.ImageView.ScaleType.CENTER);
        pill.addView(mic, new LinearLayout.LayoutParams(Ui.dp(this, 52), Ui.dp(this, 52)));
        input = new EditText(this);
        input.setSingleLine(true);
        input.setHint(Gemini.enabled() ? "Search or describe what you want to watch" : "Search movies and shows");
        input.setTextColor(Ui.TEXT);
        input.setHintTextColor(Ui.DIM);
        input.setTextSize(20);
        input.setBackground(null);
        input.setPadding(Ui.dp(this, 16), Ui.dp(this, 10), Ui.dp(this, 12), Ui.dp(this, 10));
        input.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        input.setOnEditorActionListener((v, actionId, event) -> {
            boolean enter = event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER && event.getAction() == KeyEvent.ACTION_DOWN;
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE || enter) {
                search();
                return true;
            }
            return false;
        });
        pill.addView(input, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        content.addView(pill, new LinearLayout.LayoutParams(Ui.dp(this, 760), ViewGroup.LayoutParams.WRAP_CONTENT));

        status = Ui.text(this, "", 14, Ui.MUTED);
        status.setPadding(Ui.dp(this, 8), Ui.dp(this, 14), 0, 0);
        content.addView(status);
        suggestions = Ui.vertical(this);
        content.addView(suggestions);
        renderSuggestions();
        results = Ui.vertical(this);
        results.setClipChildren(false);
        content.addView(results);

        buildListenScreen(root);
        setContentView(root);

        Gemini.warmUp();   // connect to Google while you're still speaking
        if (savedInstanceState == null) handle(getIntent());
        else mic.requestFocus();
    }

    /** Search opened again while already open (e.g. from the sidebar): act on the new request. */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        android.util.Log.i("FluxSearch", "onNewIntent");
        setIntent(intent);
        handle(intent);
    }

    private void handle(Intent i) {
        android.util.Log.i("FluxSearch", "handle voice=" + i.getBooleanExtra("voice", false) + " demo=" + i.getBooleanExtra("demo_listen", false));
        String q = i.getStringExtra("query");
        if (q != null && !q.isEmpty()) {
            input.setText(q);
            search();
        } else if (BuildConfig.TEST_BUILD && i.getBooleanExtra("demo_listen", false)) {
            showListening("Listening… say a title, or describe what you feel like watching");
            heard.setText("funny heist movies from the nineties");
            wave.setLevel(6f);
        } else if (i.getBooleanExtra("voice", false)) {
            mic.post(this::voice);
        } else {
            mic.requestFocus();
        }
    }

    private android.graphics.drawable.Drawable micBackground() {
        android.graphics.drawable.StateListDrawable s = new android.graphics.drawable.StateListDrawable();
        android.graphics.drawable.GradientDrawable f = new android.graphics.drawable.GradientDrawable();
        f.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        f.setColor(Ui.TEXT);
        f.setStroke(Ui.dp(this, 3), Ui.ACCENT);
        android.graphics.drawable.GradientDrawable n = new android.graphics.drawable.GradientDrawable();
        n.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        n.setColor(Ui.ACCENT);
        s.addState(new int[]{android.R.attr.state_focused}, f);
        s.addState(new int[]{}, n);
        return s;
    }

    /** Recent searches and a few ideas, shown before you search. */
    private void renderSuggestions() {
        suggestions.removeAllViews();
        List<String> recent = recentSearches();
        if (!recent.isEmpty()) chipRow("Recent", recent);
        if (Gemini.enabled()) {
            List<String> ideas = new ArrayList<>();
            for (String i : IDEAS) ideas.add(i);
            chipRow("Try asking", ideas);
        }
    }

    private void chipRow(String title, List<String> items) {
        TextView t = Ui.text(this, title.toUpperCase(java.util.Locale.ROOT), 12, Ui.DIM);
        t.setLetterSpacing(0.08f);
        t.setPadding(Ui.dp(this, 8), Ui.dp(this, 22), 0, Ui.dp(this, 6));
        suggestions.addView(t);
        LinearLayout row = Ui.horizontal(this);
        android.widget.HorizontalScrollView hs = new FocusScroll.Horizontal(this);
        hs.addView(row);
        for (String q : items) {
            row.addView(Ui.pill(this, q, false, v -> {
                input.setText(q);
                search();
            }));
        }
        suggestions.addView(hs);
    }

    private static List<String> recentSearches() {
        List<String> out = new ArrayList<>();
        try {
            org.json.JSONArray a = new org.json.JSONArray(Store.get("recent_searches").isEmpty() ? "[]" : Store.get("recent_searches"));
            for (int i = 0; i < a.length(); i++) out.add(a.optString(i));
        } catch (Exception ignored) { }
        return out;
    }

    private static void remember(String q) {
        List<String> r = recentSearches();
        r.remove(q);
        r.add(0, q);
        while (r.size() > 8) r.remove(r.size() - 1);
        Store.put("recent_searches", new org.json.JSONArray(r).toString());
    }

    // ------------------------------------------------------------ the listening screen

    private void buildListenScreen(android.widget.FrameLayout root) {
        listen = new android.widget.FrameLayout(this);
        listen.setBackground(new android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{Ui.alpha(Ui.BG, 0.98f), Ui.alpha(Ui.BG, 0.94f), Ui.alpha(Ui.BG, 0.85f)}));
        listen.setClickable(true);
        listen.setVisibility(View.GONE);
        LinearLayout col = Ui.vertical(this);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        heard = Ui.heading(this, "", 40);
        heard.setGravity(Gravity.CENTER);
        heard.setMaxLines(3);
        col.addView(heard, new LinearLayout.LayoutParams(Ui.dp(this, 820), ViewGroup.LayoutParams.WRAP_CONTENT));
        wave = new VoiceWave(this);
        LinearLayout.LayoutParams wl = new LinearLayout.LayoutParams(Ui.dp(this, 150), Ui.dp(this, 96));
        wl.setMargins(0, Ui.dp(this, 30), 0, Ui.dp(this, 16));
        col.addView(wave, wl);
        listenHint = Ui.text(this, "", 16, Ui.MUTED);
        listenHint.setGravity(Gravity.CENTER);
        col.addView(listenHint, new LinearLayout.LayoutParams(Ui.dp(this, 760), ViewGroup.LayoutParams.WRAP_CONTENT));
        android.widget.FrameLayout.LayoutParams cl = new android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM);
        cl.bottomMargin = Ui.dp(this, 70);
        listen.addView(col, cl);
        root.addView(listen, new android.widget.FrameLayout.LayoutParams(-1, -1));
    }

    private void showListening(String hint) {
        android.util.Log.i("FluxSearch", "showListening: " + hint);
        listen.animate().cancel();
        listen.setAlpha(1f);
        listen.setVisibility(View.VISIBLE);
        listen.bringToFront();
        heard.setText("");
        heard.setTextColor(Ui.TEXT);
        listenHint.setText(hint);
        wave.setActive(true);
    }

    private void hideListening() {
        android.util.Log.i("FluxSearch", "hideListening", new Throwable());
        listening = false;
        wave.setActive(false);
        listen.animate().cancel();
        listen.setVisibility(View.GONE);
    }

    private void voice() {
        // The TV's own voice screen, if chosen in Settings or Flux can't listen itself.
        if (Store.get("voice_ui").equals("system") || !android.speech.SpeechRecognizer.isRecognitionAvailable(this)) {
            systemVoice();
            return;
        }
        if (android.os.Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, MIC_PERMISSION);
            return;
        }
        startListening();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (code != MIC_PERMISSION) return;
        if (res.length > 0 && res[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) startListening();
        else systemVoice();
    }

    private void startListening() {
        if (recognizer == null) {
            recognizer = android.speech.SpeechRecognizer.createSpeechRecognizer(this);
            recognizer.setRecognitionListener(new android.speech.RecognitionListener() {
                @Override public void onReadyForSpeech(Bundle b) { listenHint.setText(Gemini.enabled()
                        ? "Listening… say a title, or describe what you feel like watching" : "Listening… say a movie or show"); }
                @Override public void onBeginningOfSpeech() { }
                @Override public void onRmsChanged(float rms) { wave.setLevel(rms); }
                @Override public void onBufferReceived(byte[] b) { }
                @Override public void onEndOfSpeech() { listenHint.setText("Got it…"); wave.setActive(false); }
                @Override public void onError(int error) { voiceError(error); }
                @Override public void onResults(Bundle b) {
                    ArrayList<String> said = b.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION);
                    if (said == null || said.isEmpty()) { voiceError(android.speech.SpeechRecognizer.ERROR_NO_MATCH); return; }
                    heard.setText(said.get(0));
                    App.MAIN.postDelayed(() -> {
                        hideListening();
                        input.setText(said.get(0));
                        search();
                    }, 450);
                }
                @Override public void onPartialResults(Bundle b) {
                    ArrayList<String> said = b.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION);
                    if (said != null && !said.isEmpty() && !said.get(0).isEmpty()) heard.setText(said.get(0));
                }
                @Override public void onEvent(int t, Bundle b) { }
            });
        }
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());
        listening = true;
        listenStarted = System.currentTimeMillis();
        showListening("Starting the mic…");
        try {
            recognizer.startListening(i);
        } catch (Exception e) {
            hideListening();
            systemVoice();
        }
    }

    private void voiceError(int error) {
        if (!listening) return;
        boolean early = System.currentTimeMillis() - listenStarted < 1500;
        // The TV's recogniser can't be used directly (busy, no remote mic access…): use its own voice screen.
        if (early && (error == android.speech.SpeechRecognizer.ERROR_AUDIO || error == android.speech.SpeechRecognizer.ERROR_CLIENT
                || error == android.speech.SpeechRecognizer.ERROR_RECOGNIZER_BUSY
                || error == android.speech.SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS)) {
            hideListening();
            systemVoice();
            return;
        }
        listening = false;
        wave.setActive(false);
        heard.setTextColor(Ui.MUTED);
        heard.setText(error == android.speech.SpeechRecognizer.ERROR_NETWORK || error == android.speech.SpeechRecognizer.ERROR_NETWORK_TIMEOUT
                ? "Couldn't reach the voice service" : "Didn't catch that");
        listenHint.setText("Press OK to try again · Back to type instead");
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (listen != null && listen.getVisibility() == View.VISIBLE && e.getAction() == KeyEvent.ACTION_DOWN) {
            int k = e.getKeyCode();
            if (k == KeyEvent.KEYCODE_BACK) {
                if (recognizer != null) recognizer.cancel();
                hideListening();
                mic.requestFocus();
                return true;
            }
            if (!listening && (k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER
                    || k == KeyEvent.KEYCODE_SEARCH || k == KeyEvent.KEYCODE_VOICE_ASSIST)) {
                startListening();
                return true;
            }
            return true;
        }
        if (e.getAction() == KeyEvent.ACTION_DOWN && (e.getKeyCode() == KeyEvent.KEYCODE_SEARCH)) {
            voice();
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (recognizer != null && listening) {
            recognizer.cancel();
            hideListening();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (recognizer != null) recognizer.destroy();
    }

    /** The TV's own voice screen (fallback). */
    private void systemVoice() {
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
        remember(q);
        suggestions.removeAllViews();
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
