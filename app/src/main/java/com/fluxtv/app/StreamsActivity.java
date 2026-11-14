package com.fluxtv.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.util.List;

/**
 * Lists streams from every installed stream add-on. Direct links play straight away;
 * torrent results (infoHash) are resolved through Real-Debrid first.
 * Long-press a stream to open it in another installed player.
 */
public class StreamsActivity extends Activity {

    private PlayInfo info;
    private LinearLayout list;
    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        info = PlayInfo.from(getIntent());

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Ui.BG);
        LinearLayout content = Ui.vertical(this);
        int pad = Ui.dp(this, 48);
        content.setPadding(pad, Ui.dp(this, 32), pad, pad);
        scroll.addView(content);
        setContentView(scroll);

        content.addView(Ui.heading(this, info.title(), 28));
        status = Ui.text(this, "Finding streams…", 17, Ui.MUTED);
        status.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 16));
        content.addView(status);
        list = Ui.vertical(this);
        content.addView(list);

        load();
    }

    private void load() {
        status.setText("Finding streams…");
        list.removeAllViews();
        App.async(() -> Addons.streams(info.type, info.videoId), this::render, e -> status.setText(App.message(e)));
    }

    private void render(List<Addons.Stream> streams) {
        if (streams.isEmpty()) {
            status.setText("No streams found. Install a stream add-on, or add a Plex/Jellyfin/Emby server, from the home screen.");
            Button retry = Ui.button(this, "Retry", v -> load());
            list.addView(retry);
            retry.requestFocus();
            return;
        }
        status.setText(streams.size() + " streams · long-press to use another player"
                + (RealDebrid.isSignedIn() ? "" : " · sign in to Real-Debrid for torrent results"));
        for (Addons.Stream s : streams) {
            JSONObject j = s.json;
            StringBuilder label = new StringBuilder();
            String name = j.optString("name").replace('\n', ' ').trim();
            label.append(s.source);
            if (!name.isEmpty()) label.append("  ·  ").append(name);
            if (j.has("infoHash") && !j.has("url")) label.append("  ·  [RD]");
            String desc = j.optString("title", j.optString("description")).trim();
            if (!desc.isEmpty()) label.append('\n').append(desc);
            Button b = Ui.listButton(this, label, v -> play(j, false));
            b.setOnLongClickListener(v -> {
                play(j, true);
                return true;
            });
            list.addView(b);
        }
        View first = list.getChildAt(0);
        first.post(first::requestFocus);
    }

    private void play(JSONObject s, boolean external) {
        if (!s.optString("url").isEmpty()) {
            start(s.optString("url"), external);
        } else if (!s.optString("infoHash").isEmpty()) {
            if (!RealDebrid.isSignedIn()) {
                Toast.makeText(this, "Sign in to Real-Debrid to play this", Toast.LENGTH_LONG).show();
                startActivity(new Intent(this, AccountActivity.class));
                return;
            }
            resolve(s, external);
        } else if (!s.optString("ytId").isEmpty()) {
            view("https://www.youtube.com/watch?v=" + s.optString("ytId"));
        } else if (!s.optString("externalUrl").isEmpty()) {
            view(s.optString("externalUrl"));
        } else {
            Toast.makeText(this, "This stream type isn't supported", Toast.LENGTH_SHORT).show();
        }
    }

    private void resolve(JSONObject s, boolean external) {
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Real-Debrid")
                .setMessage("Starting…")
                .setCancelable(true)
                .create();
        dialog.show();
        JSONObject hints = s.optJSONObject("behaviorHints");
        String filename = hints != null ? hints.optString("filename") : "";
        int fileIdx = s.has("fileIdx") ? s.optInt("fileIdx", -1) : -1;
        String hash = s.optString("infoHash");
        App.async(() -> RealDebrid.resolve(hash, fileIdx, filename,
                        msg -> App.MAIN.post(() -> { if (dialog.isShowing()) dialog.setMessage(msg); })),
                url -> {
                    if (!dialog.isShowing() || isFinishing()) return;   // user cancelled
                    dialog.dismiss();
                    start(url, external);
                },
                e -> {
                    if (isFinishing()) return;
                    dialog.dismiss();
                    new AlertDialog.Builder(this)
                            .setTitle("Couldn't play")
                            .setMessage(App.message(e))
                            .setPositiveButton("OK", null)
                            .show();
                });
    }

    private void start(String url, boolean external) {
        if (external) {
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(Uri.parse(url), "video/*");
            i.putExtra("title", info.title());
            try {
                startActivity(Intent.createChooser(i, "Play with"));
            } catch (ActivityNotFoundException e) {
                Toast.makeText(this, "No other video player installed", Toast.LENGTH_SHORT).show();
            }
        } else {
            startActivity(info.into(new Intent(this, PlayerActivity.class)).putExtra("url", url));
        }
    }

    private void view(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "No app can open this link", Toast.LENGTH_SHORT).show();
        }
    }
}
