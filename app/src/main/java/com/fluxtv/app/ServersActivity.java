package com.fluxtv.app;

import android.app.Activity;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/** Add and remove Plex, Jellyfin and Emby servers. */
public class ServersActivity extends Activity {

    private LinearLayout content;
    private int session = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ScrollView scroll = new FocusScroll.Vertical(this);
        scroll.setBackgroundColor(Ui.BG);
        content = Ui.vertical(this);
        int pad = Ui.dp(this, 48);
        content.setPadding(pad, Ui.dp(this, 32), pad, pad);
        scroll.addView(content);
        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        ConfigServer.start(this::render);
        render();
    }

    @Override
    protected void onPause() {
        super.onPause();
        session++;
        ConfigServer.stop();
    }

    private void header() {
        content.removeAllViews();
        content.addView(Ui.heading(this, "Media servers", 30));
    }

    private void render() {
        session++;
        header();
        String addr = ConfigServer.address();
        if (addr != null && ConfigServer.running()) {
            TextView hint = Ui.text(this, "Tip: on your phone (same Wi-Fi) open " + addr
                    + " to type server addresses and passwords there. Keep this screen open.", 17, Ui.ACCENT);
            hint.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 8));
            content.addView(hint);
        }

        LinearLayout actions = Ui.horizontal(this);
        Button plex = Ui.button(this, "+ Plex (sign in with code)", v -> plexLogin());
        actions.addView(plex);
        actions.addView(Ui.button(this, "+ Jellyfin", v -> passwordForm(MediaServer.JELLYFIN)));
        actions.addView(Ui.button(this, "+ Emby", v -> passwordForm(MediaServer.EMBY)));
        content.addView(actions);
        plex.requestFocus();

        List<MediaServer> servers = Store.servers();
        if (servers.isEmpty()) {
            content.addView(paragraph("No servers yet. Your server's movies and shows will appear on the home "
                    + "screen and in search, and they're offered as a stream whenever an add-on title matches "
                    + "something in your library."));
        }
        for (MediaServer s : servers) {
            LinearLayout item = Ui.vertical(this);
            item.setBackground(Ui.rounded(this, Ui.PANEL, 0));
            item.setPadding(Ui.dp(this, 16), Ui.dp(this, 12), Ui.dp(this, 16), Ui.dp(this, 12));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, Ui.dp(this, 12), 0, 0);
            item.setLayoutParams(lp);
            item.addView(Ui.heading(this, s.name + "  (" + s.kindLabel() + ")", 19));
            item.addView(Ui.text(this, s.url, 14, Ui.MUTED));
            item.addView(Ui.button(this, "Remove", v -> {
                Store.removeServer(s.kind, s.id);
                Sources.forget(s);
                render();
            }));
            content.addView(item);
        }
    }

    // ------------------------------------------------------------ Plex

    private void plexLogin() {
        int my = ++session;
        header();
        TextView status = paragraph("Getting a code…");
        content.addView(status);
        App.async(Plex::startPin, pin -> {
            if (my != session) return;
            status.setText("On your phone or computer, go to\nplex.tv/link\nand enter this code:");
            TextView code = Ui.heading(this, pin.code, 56);
            code.setTextColor(Ui.ACCENT);
            code.setGravity(Gravity.START);
            code.setPadding(0, Ui.dp(this, 16), 0, Ui.dp(this, 16));
            content.addView(code);
            content.addView(paragraph("Waiting for you to sign in…"));
            Button cancel = Ui.button(this, "Cancel", v -> render());
            content.addView(cancel);
            cancel.requestFocus();
            pollPlex(pin, my, System.currentTimeMillis() + 10 * 60 * 1000L);
        }, e -> {
            if (my != session) return;
            status.setText("Couldn't reach plex.tv: " + App.message(e));
            content.addView(Ui.button(this, "Back", v -> render()));
        });
    }

    private void pollPlex(Plex.Pin pin, int my, long deadline) {
        App.MAIN.postDelayed(() -> {
            if (my != session) return;
            if (System.currentTimeMillis() > deadline) {
                Toast.makeText(this, "Code expired, try again", Toast.LENGTH_LONG).show();
                render();
                return;
            }
            App.async(() -> Plex.pollPin(pin), token -> {
                if (my != session) return;
                if (token == null) {
                    pollPlex(pin, my, deadline);
                    return;
                }
                header();
                content.addView(paragraph("Signed in. Finding your servers…"));
                App.async(() -> Plex.discover(token), found -> {
                    for (MediaServer s : found) Store.saveServer(s);
                    Toast.makeText(this, found.isEmpty() ? "No reachable Plex servers on this account"
                            : "Added " + found.size() + " Plex server" + (found.size() == 1 ? "" : "s"), Toast.LENGTH_LONG).show();
                    if (my == session) render();
                }, e -> {
                    Toast.makeText(this, "Couldn't list servers: " + App.message(e), Toast.LENGTH_LONG).show();
                    if (my == session) render();
                });
            }, e -> { if (my == session) pollPlex(pin, my, deadline); });
        }, 2000);
    }

    // ------------------------------------------------------------ Jellyfin / Emby

    private void passwordForm(String kind) {
        session++;
        header();
        String label = MediaServer.EMBY.equals(kind) ? "Emby" : "Jellyfin";
        content.addView(paragraph("Sign in to " + label + ". Use the address you'd type in a browser, "
                + "e.g. http://192.168.1.10:8096"));
        EditText url = field("Server address");
        EditText user = field("Username");
        EditText pass = field("Password");
        pass.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        LinearLayout actions = Ui.horizontal(this);
        actions.addView(Ui.button(this, "Sign in", v -> {
            String u = url.getText().toString(), n = user.getText().toString(), pw = pass.getText().toString();
            Toast.makeText(this, "Signing in…", Toast.LENGTH_SHORT).show();
            App.async(() -> Jellyfin.login(kind, u, n, pw), s -> {
                Store.saveServer(s);
                Toast.makeText(this, "Added " + s.name, Toast.LENGTH_SHORT).show();
                render();
            }, e -> Toast.makeText(this, "Sign-in failed: " + App.message(e), Toast.LENGTH_LONG).show());
        }));
        actions.addView(Ui.button(this, "Cancel", v -> render()));
        content.addView(actions);
        url.requestFocus();
    }

    private EditText field(String hint) {
        EditText e = new EditText(this);
        e.setSingleLine(true);
        e.setHint(hint);
        e.setTextColor(Ui.TEXT);
        e.setHintTextColor(Ui.MUTED);
        e.setTextSize(18);
        content.addView(e, new LinearLayout.LayoutParams(Ui.dp(this, 520), ViewGroup.LayoutParams.WRAP_CONTENT));
        return e;
    }

    private TextView paragraph(String s) {
        TextView t = Ui.text(this, s, 17, Ui.MUTED);
        t.setPadding(0, Ui.dp(this, 14), 0, Ui.dp(this, 6));
        return t;
    }
}
