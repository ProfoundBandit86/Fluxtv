package com.fluxtv.app;

import android.app.Activity;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/** Install, remove and reorder add-ons. Also hosts the phone setup page while open. */
public class AddonsActivity extends FluxActivity {

    private LinearLayout list;
    private TextView phoneHint;
    private EditText input;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ScrollView scroll = new FocusScroll.Vertical(this);
        scroll.setBackgroundColor(Ui.BG);
        LinearLayout content = Ui.vertical(this);
        int pad = Ui.dp(this, 48);
        content.setPadding(pad, Ui.dp(this, 32), pad, pad);
        scroll.addView(content);
        setContentView(scroll);

        content.addView(Ui.heading(this, "Add-ons", 30));
        phoneHint = Ui.text(this, "", 18, Ui.ACCENT);
        phoneHint.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 16));
        content.addView(phoneHint);

        LinearLayout bar = Ui.horizontal(this);
        input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("Add-on URL (…/manifest.json)");
        input.setTextColor(Ui.TEXT);
        input.setHintTextColor(Ui.MUTED);
        bar.addView(input, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        bar.addView(Ui.button(this, "Install", v -> install()));
        content.addView(bar);

        list = Ui.vertical(this);
        list.setPadding(0, Ui.dp(this, 16), 0, 0);
        content.addView(list);
    }

    @Override
    protected void onResume() {
        super.onResume();
        ConfigServer.start(this::render);
        String addr = ConfigServer.address();
        phoneHint.setText(addr != null && ConfigServer.running()
                ? "Easier: on your phone (same Wi-Fi) open " + addr + " and paste add-on links or your Real-Debrid token there. Keep this screen open."
                : "Connect the TV to your network to set up add-ons from your phone.");
        render();
    }

    @Override
    protected void onPause() {
        super.onPause();
        ConfigServer.stop();
    }

    private void install() {
        String url = input.getText().toString();
        App.async(() -> Addons.install(url), a -> {
            Toast.makeText(this, "Installed " + a.name(), Toast.LENGTH_SHORT).show();
            input.setText("");
            render();
        }, e -> Toast.makeText(this, App.message(e), Toast.LENGTH_LONG).show());
    }

    private void render() {
        list.removeAllViews();
        list.addView(Ui.text(this, "Loading…", 16, Ui.MUTED));
        App.async(() -> {
            List<String> urls = Store.addonUrls();
            for (String u : urls) Addons.load(u);   // warm manifests off the main thread
            return urls;
        }, urls -> {
            list.removeAllViews();
            for (int i = 0; i < urls.size(); i++) {
                String url = urls.get(i);
                Addon a = Addons.load(url);   // cached by now
                LinearLayout item = Ui.vertical(this);
                item.setBackground(Ui.rounded(this, Ui.PANEL, 0));
                item.setPadding(Ui.dp(this, 16), Ui.dp(this, 12), Ui.dp(this, 16), Ui.dp(this, 12));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.setMargins(0, Ui.dp(this, 6), 0, Ui.dp(this, 6));
                item.setLayoutParams(lp);

                item.addView(Ui.heading(this, a != null ? a.name() : "Unavailable add-on", 19));
                String desc = a != null ? a.description() : "";
                TextView d = Ui.text(this, (desc.isEmpty() ? "" : desc + "\n") + url, 14, Ui.MUTED);
                d.setMaxLines(3);
                item.addView(d);

                LinearLayout actions = Ui.horizontal(this);
                if (i > 0) actions.addView(Ui.button(this, "Move up", v -> { Store.moveAddon(url, -1); render(); }));
                if (i < urls.size() - 1) actions.addView(Ui.button(this, "Move down", v -> { Store.moveAddon(url, 1); render(); }));
                actions.addView(Ui.button(this, "Remove", v -> { Addons.uninstall(url); render(); }));
                item.addView(actions);
                list.addView(item);
            }
            if (urls.isEmpty()) list.addView(Ui.text(this, "No add-ons installed.", 17, Ui.MUTED));
        }, e -> Toast.makeText(this, App.message(e), Toast.LENGTH_SHORT).show());
    }
}
