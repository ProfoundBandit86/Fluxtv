package com.fluxtv.app;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/** Add / remove IPTV providers (Xtream Codes or M3U + EPG) and the movies & series switch. */
public class IptvActivity extends FluxActivity {

    private LinearLayout content;
    private int session = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
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

    private void render() {
        session++;
        content = Ui.page(this, "Live TV & IPTV");
        String addr = ConfigServer.address();
        if (addr != null && ConfigServer.running()) {
            TextView hint = Ui.text(this, "Tip: on your phone (same Wi-Fi) open " + addr
                    + " to type logins and links there. Keep this screen open.", 14, Ui.ACCENT);
            hint.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 10));
            content.addView(hint);
        }

        LinearLayout actions = Ui.horizontal(this);
        Button xc = Ui.button(this, R.drawable.ic_plus, "Xtream Codes login", v -> xtreamForm());
        actions.addView(xc);
        actions.addView(Ui.button(this, R.drawable.ic_plus, "M3U playlist", v -> m3uForm()));
        actions.addView(Ui.button(this, R.drawable.ic_tv, "Open Live TV", v -> startActivity(new Intent(this, LiveActivity.class))));
        content.addView(actions);

        TextView help = Ui.text(this, "Dispatcharr: use Xtream Codes with your Dispatcharr address (e.g. http://192.168.1.20:9191), "
                + "a Dispatcharr username, and that user's XC password (set under Users). Or add its M3U and EPG links as a playlist. "
                + "Movies and series need the Xtream Codes login.", 13, Ui.DIM);
        help.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 6));
        content.addView(help, new LinearLayout.LayoutParams(Ui.dp(this, 760), ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView sw = Ui.heading(this, "Provider movies & series", 17);
        sw.setPadding(0, Ui.dp(this, 20), 0, Ui.dp(this, 4));
        content.addView(sw);
        TextView swHelp = Ui.text(this, "Show your IPTV provider's movies and series in Movies, TV Shows, Home and Search.", 13, Ui.MUTED);
        content.addView(swHelp);
        LinearLayout toggle = Ui.horizontal(this);
        boolean on = Iptv.vodEnabled();
        toggle.addView(Ui.pill(this, "Show", on, v -> { Iptv.setVodEnabled(true); render(); }));
        toggle.addView(Ui.pill(this, "Hide", !on, v -> { Iptv.setVodEnabled(false); render(); }));
        content.addView(toggle);

        TextView listTitle = Ui.heading(this, "Providers", 17);
        listTitle.setPadding(0, Ui.dp(this, 20), 0, Ui.dp(this, 4));
        content.addView(listTitle);
        List<Iptv.Source> sources = Iptv.sources();
        if (sources.isEmpty()) content.addView(Ui.text(this, "None yet.", 14, Ui.MUTED));
        for (Iptv.Source s : sources) {
            LinearLayout item = Ui.vertical(this);
            item.setBackground(Ui.rounded(this, Ui.PANEL, 0, 14));
            item.setPadding(Ui.dp(this, 16), Ui.dp(this, 12), Ui.dp(this, 16), Ui.dp(this, 12));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(this, 760), ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, Ui.dp(this, 8), 0, 0);
            item.setLayoutParams(lp);
            item.addView(Ui.heading(this, s.name, 16));
            item.addView(Ui.text(this, s.xtream() ? "Xtream Codes · " + s.url : "M3U playlist" + (s.epg.isEmpty() ? "" : " + guide"), 12, Ui.MUTED));
            item.addView(Ui.button(this, "Remove", v -> { Iptv.remove(s.id); render(); }));
            content.addView(item);
        }
        xc.post(xc::requestFocus);
    }

    private EditText field(String hint, boolean password) {
        EditText e = new EditText(this);
        e.setSingleLine(true);
        e.setHint(hint);
        e.setTextColor(Ui.TEXT);
        e.setHintTextColor(Ui.DIM);
        e.setTextSize(17);
        if (password) e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        content.addView(e, new LinearLayout.LayoutParams(Ui.dp(this, 560), ViewGroup.LayoutParams.WRAP_CONTENT));
        return e;
    }

    private void xtreamForm() {
        session++;
        content = Ui.page(this, "Xtream Codes login");
        content.addView(Ui.text(this, "Server address from your provider, e.g. http://example.com:8080. For Dispatcharr: http://<its IP>:9191", 13, Ui.MUTED));
        EditText server = field("Server address", false);
        EditText user = field("Username", false);
        EditText pass = field("Password (Dispatcharr: the user's XC password)", true);
        LinearLayout actions = Ui.horizontal(this);
        actions.addView(Ui.button(this, "Sign in", v -> {
            String a = server.getText().toString(), u = user.getText().toString(), p = pass.getText().toString();
            Toast.makeText(this, "Signing in…", Toast.LENGTH_SHORT).show();
            App.async(() -> Iptv.loginXtream(a, u, p), s -> {
                Iptv.save(s);
                Toast.makeText(this, "Added " + s.name, Toast.LENGTH_SHORT).show();
                render();
            }, e -> Toast.makeText(this, App.message(e), Toast.LENGTH_LONG).show());
        }));
        actions.addView(Ui.button(this, "Cancel", v -> render()));
        content.addView(actions);
        server.requestFocus();
    }

    private void m3uForm() {
        session++;
        content = Ui.page(this, "M3U playlist");
        content.addView(Ui.text(this, "Paste the playlist link. An Xtream-style get.php link is upgraded to a full login automatically.", 13, Ui.MUTED));
        EditText m3u = field("Playlist (M3U) link", false);
        EditText epg = field("Guide (EPG / XMLTV) link, optional", false);
        LinearLayout actions = Ui.horizontal(this);
        actions.addView(Ui.button(this, "Add", v -> {
            String a = m3u.getText().toString(), b = epg.getText().toString();
            Toast.makeText(this, "Loading playlist…", Toast.LENGTH_SHORT).show();
            App.async(() -> Iptv.addM3u(a, b), s -> {
                Iptv.save(s);
                Toast.makeText(this, "Added " + s.name, Toast.LENGTH_SHORT).show();
                render();
            }, e -> Toast.makeText(this, App.message(e), Toast.LENGTH_LONG).show());
        }));
        actions.addView(Ui.button(this, "Cancel", v -> render()));
        content.addView(actions);
        m3u.requestFocus();
    }

    @Override
    public void onBackPressed() {
        View f = getCurrentFocus();
        if (f instanceof EditText || (content != null && content.getChildCount() > 0
                && content.getChildAt(0) instanceof TextView
                && !((TextView) content.getChildAt(0)).getText().toString().equals("Live TV & IPTV"))) {
            render();
            return;
        }
        super.onBackPressed();
    }
}
