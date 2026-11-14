package com.fluxtv.app;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/** Settings → Downloads: connect Radarr, Sonarr and SABnzbd, and pick Radarr/Sonarr defaults. */
public class ArrSetupActivity extends Activity {

    private LinearLayout content;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        content = Ui.page(this, "Downloads");
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
        ConfigServer.stop();
    }

    private void render() {
        while (content.getChildCount() > 1) content.removeViewAt(1);
        String addr = ConfigServer.address();
        content.addView(para("Connect Radarr, Sonarr and SABnzbd to add movies and shows from any title page and follow "
                + "downloads under Downloads in the sidebar. API keys are in each app under Settings → General."
                + (addr != null ? "  Typing is easier from your phone: " + addr : "")));
        service("Radarr", "http://192.168.1.10:7878", "radarr", Arr.RADARR);
        service("Sonarr", "http://192.168.1.10:8989", "sonarr", Arr.SONARR);
        service("SABnzbd", "http://192.168.1.10:8080", "sab", null);
        View first = content.getFocusables(View.FOCUS_DOWN).isEmpty() ? null : content.getFocusables(View.FOCUS_DOWN).get(0);
        if (first != null && getCurrentFocus() == null) first.requestFocus();
    }

    private void service(String name, String example, String key, Arr arr) {
        boolean on = arr != null ? arr.configured() : Sab.configured();
        content.addView(section(name));
        TextView status = Ui.text(this, on ? "Connected to " + Store.get(key + "_url") : "Not set up", 13, on ? Ui.GOOD : Ui.DIM);
        content.addView(status);
        LinearLayout row = Ui.horizontal(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        EditText url = field(on ? Store.get(key + "_url") : example);
        EditText api = field(on ? "API key saved" : "API key");
        row.addView(url, new LinearLayout.LayoutParams(Ui.dp(this, 300), ViewGroup.LayoutParams.WRAP_CONTENT));
        row.addView(api, new LinearLayout.LayoutParams(Ui.dp(this, 260), ViewGroup.LayoutParams.WRAP_CONTENT));
        row.addView(Ui.button(this, "Save & test", v -> {
            String u = url.getText().toString(), k = api.getText().toString();
            status.setText("Testing…");
            App.async(() -> arr != null ? arr.save(u, k) : Sab.save(u, k), msg -> {
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
                render();
            }, e -> {
                status.setText("Couldn't connect: " + App.message(e));
                status.setTextColor(Ui.WARN);
            });
        }));
        if (on) row.addView(Ui.button(this, "Remove", v -> {
            if (arr != null) arr.remove(); else Sab.remove();
            render();
        }));
        content.addView(row);
        if (on && arr != null) defaults(arr);
    }

    /** Quality profile and folder pills for Radarr/Sonarr. */
    private void defaults(Arr arr) {
        LinearLayout holder = Ui.vertical(this);
        content.addView(holder);
        App.async(() -> new Object[]{arr.profiles(), arr.rootFolders()}, r -> {
            @SuppressWarnings("unchecked") List<String[]> profiles = (List<String[]>) r[0];
            @SuppressWarnings("unchecked") List<String> roots = (List<String>) r[1];
            holder.addView(label("Quality for new " + (arr == Arr.RADARR ? "movies" : "shows")));
            LinearLayout p = Ui.horizontal(this);
            for (String[] pr : profiles) {
                p.addView(Ui.pill(this, pr[1], pr[0].equals(arr.profile()), v -> { arr.setDefaults(pr[0], null); render(); }));
            }
            holder.addView(p);
            holder.addView(label("Save to"));
            LinearLayout f = Ui.horizontal(this);
            for (String root : roots) {
                f.addView(Ui.pill(this, root, root.equals(arr.root()), v -> { arr.setDefaults(null, root); render(); }));
            }
            holder.addView(f);
        }, e -> holder.addView(Ui.text(this, App.message(e), 13, Ui.WARN)));
    }

    private EditText field(String hint) {
        EditText e = new EditText(this);
        e.setSingleLine(true);
        e.setHint(hint);
        e.setTextColor(Ui.TEXT);
        e.setHintTextColor(Ui.DIM);
        e.setTextSize(15);
        return e;
    }

    private TextView section(String s) {
        TextView t = Ui.heading(this, s, 18);
        t.setPadding(0, Ui.dp(this, 22), 0, Ui.dp(this, 4));
        return t;
    }

    private TextView label(String s) {
        TextView t = Ui.text(this, s, 13, Ui.DIM);
        t.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 4));
        return t;
    }

    private TextView para(String s) {
        TextView t = Ui.text(this, s, 14, Ui.MUTED);
        t.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 6));
        t.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return t;
    }
}
