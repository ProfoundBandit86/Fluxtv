package com.fluxtv.app;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** Real-Debrid sign-in (device code or API token) and account status. */
public class AccountActivity extends Activity {

    private LinearLayout content;
    private int session = 0;   // invalidates an in-flight device login when the screen changes

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ScrollView scroll = new ScrollView(this);
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
        render();
    }

    @Override
    protected void onPause() {
        super.onPause();
        session++;
    }

    private void render() {
        session++;
        content.removeAllViews();
        content.addView(Ui.heading(this, "Real-Debrid", 30));

        if (RealDebrid.isSignedIn()) {
            TextView status = Ui.text(this, "Checking account…", 19, Ui.MUTED);
            status.setPadding(0, Ui.dp(this, 12), 0, Ui.dp(this, 12));
            content.addView(status);
            App.async(RealDebrid::user, u -> {
                String exp = u.optString("expiration");
                status.setText("Signed in as " + u.optString("username")
                        + "\nAccount: " + u.optString("type")
                        + (exp.length() >= 10 ? "\nPremium until: " + exp.substring(0, 10) : ""));
                status.setTextColor(Ui.TEXT);
            }, e -> status.setText("Signed in, but the account check failed: " + App.message(e)));
            Button out = Ui.button(this, "Sign out", v -> {
                RealDebrid.signOut();
                render();
            });
            content.addView(out);
            out.requestFocus();
            return;
        }

        content.addView(paragraph("Real-Debrid turns torrent results from your stream add-ons into fast direct "
                + "streams. You need a Real-Debrid account."));
        Button code = Ui.button(this, "Sign in with a code", v -> startDeviceLogin());
        content.addView(code);
        code.requestFocus();

        content.addView(paragraph("Or paste your API token from real-debrid.com/apitoken. Typing it is easier from "
                + "your phone: open Add-ons on the home screen and follow the link shown there."));
        LinearLayout bar = Ui.horizontal(this);
        EditText key = new EditText(this);
        key.setSingleLine(true);
        key.setHint("API token");
        key.setTextColor(Ui.TEXT);
        key.setHintTextColor(Ui.MUTED);
        bar.addView(key, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        bar.addView(Ui.button(this, "Save", v -> {
            String k = key.getText().toString().trim();
            if (k.isEmpty()) return;
            RealDebrid.setApiKey(k);
            render();
        }));
        content.addView(bar);
    }

    private void startDeviceLogin() {
        int my = ++session;
        content.removeAllViews();
        content.addView(Ui.heading(this, "Real-Debrid", 30));
        TextView status = Ui.text(this, "Getting a code…", 20, Ui.MUTED);
        status.setPadding(0, Ui.dp(this, 16), 0, 0);
        content.addView(status);

        App.async(RealDebrid::startDeviceLogin, dc -> {
            if (my != session) return;
            status.setText("On your phone or computer, go to\n" + dc.verificationUrl + "\nand enter this code:");
            TextView code = Ui.heading(this, dc.userCode, 56);
            code.setTextColor(Ui.ACCENT);
            code.setGravity(Gravity.START);
            code.setPadding(0, Ui.dp(this, 16), 0, Ui.dp(this, 16));
            content.addView(code);
            content.addView(paragraph("Waiting for approval…"));
            Button cancel = Ui.button(this, "Cancel", v -> render());
            content.addView(cancel);
            cancel.requestFocus();
            poll(dc, my, System.currentTimeMillis() + dc.expiresIn * 1000L);
        }, e -> {
            if (my != session) return;
            status.setText("Couldn't start sign-in: " + App.message(e));
            content.addView(Ui.button(this, "Back", v -> render()));
        });
    }

    private void poll(RealDebrid.DeviceCode dc, int my, long deadline) {
        App.MAIN.postDelayed(() -> {
            if (my != session) return;
            if (System.currentTimeMillis() > deadline) {
                Toast.makeText(this, "Code expired, try again", Toast.LENGTH_LONG).show();
                render();
                return;
            }
            App.async(() -> RealDebrid.pollDeviceLogin(dc), done -> {
                if (my != session) return;
                if (done) {
                    Toast.makeText(this, "Signed in to Real-Debrid", Toast.LENGTH_SHORT).show();
                    render();
                } else {
                    poll(dc, my, deadline);
                }
            }, e -> { if (my == session) poll(dc, my, deadline); });
        }, dc.interval * 1000L);
    }

    private TextView paragraph(String s) {
        TextView t = Ui.text(this, s, 17, Ui.MUTED);
        t.setPadding(0, Ui.dp(this, 14), 0, Ui.dp(this, 6));
        return t;
    }
}
