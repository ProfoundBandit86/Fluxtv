package com.fluxtv.app;

import android.app.AlertDialog;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/** Network share for recordings: sign in to the computer / NAS, then pick (or create) the folder. */
public class SmbBrowseActivity extends FluxActivity {

    @Override
    protected String sidebarKey() {
        return "settings";
    }

    private LinearLayout content;
    private String path = "";
    private int gen;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        content = Ui.page(this, "Recordings folder on your network");
        path = Smb.path();
        if (Smb.host().isEmpty()) login(); else browse();
    }

    private EditText field(String hint, String value, boolean password) {
        EditText e = new EditText(this);
        e.setSingleLine(true);
        e.setHint(hint);
        e.setText(value);
        e.setTextColor(Ui.TEXT);
        e.setHintTextColor(Ui.DIM);
        if (password) e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        content.addView(e, new LinearLayout.LayoutParams(Ui.dp(this, 520), ViewGroup.LayoutParams.WRAP_CONTENT));
        return e;
    }

    private void clear() {
        gen++;
        while (content.getChildCount() > 1) content.removeViewAt(1);
    }

    private TextView para(String s, int color) {
        TextView t = Ui.text(this, s, 14, color);
        t.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 8));
        content.addView(t);
        return t;
    }

    private void login() {
        clear();
        para("The computer or NAS that shares the folder (Windows file sharing / SMB). Its name or address, e.g. "
                + "192.168.1.10 or MYNAS. Leave the user name empty for a guest share.", Ui.MUTED);
        EditText host = field("Computer or NAS (e.g. 192.168.1.10)", Smb.host(), false);
        EditText user = field("User name (optional)", Store.get("dvr_smb_user"), false);
        EditText pass = field(Store.get("dvr_smb_pass").isEmpty() ? "Password" : "Password (saved, type to change)", "", true);
        EditText domain = field("Domain / workgroup (usually empty)", Store.get("dvr_smb_domain"), false);
        LinearLayout b = Ui.horizontal(this);
        b.setPadding(0, Ui.dp(this, 10), 0, 0);
        View go = Ui.button(this, "Connect", v -> {
            String p = pass.getText().toString();
            Smb.save(host.getText().toString(), user.getText().toString(), p.isEmpty() ? null : p, domain.getText().toString());
            if (Smb.host().isEmpty()) { Toast.makeText(this, "Type the computer's name or address", Toast.LENGTH_SHORT).show(); return; }
            path = "";
            browse();
        });
        b.addView(go);
        content.addView(b);
        (Smb.host().isEmpty() ? host : go).requestFocus();
    }

    private void browse() {
        clear();
        int my = gen;
        String shown = "\\\\" + Smb.host() + (path.isEmpty() ? "" : "\\" + path.replace('/', '\\'));
        TextView where = Ui.heading(this, shown, 20);
        content.addView(where);
        TextView status = para("Opening…", Ui.MUTED);
        LinearLayout actions = Ui.horizontal(this);
        actions.setPadding(0, 0, 0, Ui.dp(this, 8));
        content.addView(actions);
        LinearLayout list = Ui.vertical(this);
        content.addView(list);
        App.async(() -> Smb.folders(path), folders -> {
            if (my != gen) return;
            status.setText(path.isEmpty() ? (folders.isEmpty() ? "No shared folders found." : "Choose a shared folder:")
                    : folders.isEmpty() ? "No folders inside. Use this one, or make a new folder." : "Folders:");
            View first = null;
            if (!path.isEmpty()) {
                View use = Ui.button(this, R.drawable.ic_download, "Use this folder", v -> choose());
                actions.addView(use);
                actions.addView(Ui.button(this, R.drawable.ic_plus, "New folder", v -> newFolder()));
                first = use;
            }
            View up = Ui.button(this, path.isEmpty() ? "Change computer / sign-in" : "Up a level", v -> {
                if (path.isEmpty()) { login(); return; }
                int i = path.lastIndexOf('/');
                path = i < 0 ? "" : path.substring(0, i);
                browse();
            });
            actions.addView(up);
            if (first == null) first = up;
            for (String f : folders) {
                View row = Ui.button(this, R.drawable.ic_folder, f, v -> { path = path.isEmpty() ? f : path + "/" + f; browse(); });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.setMargins(0, Ui.dp(this, 3), 0, Ui.dp(this, 3));
                list.addView(row, lp);
                if (path.isEmpty() && first == up) first = row;
            }
            View f = first;
            f.post(f::requestFocus);
        }, e -> {
            if (my != gen) return;
            status.setText("Couldn't open " + shown + ": " + App.message(e)
                    + "\nCheck the address, user name and password, and that file sharing is on.");
            status.setTextColor(Ui.WARN);
            View back = Ui.button(this, "Change computer / sign-in", v -> login());
            actions.addView(back);
            if (!path.isEmpty()) actions.addView(Ui.button(this, "Up a level", v -> {
                int i = path.lastIndexOf('/');
                path = i < 0 ? "" : path.substring(0, i);
                browse();
            }));
            back.requestFocus();
        });
    }

    private void newFolder() {
        EditText in = new EditText(this);
        in.setSingleLine(true);
        in.setText("Flux Recordings");
        new AlertDialog.Builder(this).setTitle("New folder").setView(in)
                .setPositiveButton("Create", (d, w) -> {
                    String n = in.getText().toString().trim().replaceAll("[\\\\/:*?\"<>|]", "_");
                    if (n.isEmpty()) return;
                    String p = path + "/" + n;
                    App.async(() -> { Smb.mkdir(p); return true; }, ok -> { path = p; browse(); },
                            e -> Toast.makeText(this, "Couldn't create it: " + App.message(e), Toast.LENGTH_LONG).show());
                })
                .setNegativeButton("Cancel", null).show();
    }

    private void choose() {
        String chosen = path;
        Toast.makeText(this, "Checking Flux can save there…", Toast.LENGTH_SHORT).show();
        String old = Smb.path();
        Smb.setPath(chosen);
        App.async(() -> { Smb.test(); return true; }, ok -> {
            Dvr.setTarget("smb");
            Toast.makeText(this, "Recordings will be saved to \\\\" + Smb.host() + "\\" + chosen.replace('/', '\\'), Toast.LENGTH_LONG).show();
            finish();
        }, e -> {
            Smb.setPath(old);
            new AlertDialog.Builder(this).setTitle("Flux can't save there")
                    .setMessage(App.message(e) + "\n\nMake sure this user can write to the folder.")
                    .setPositiveButton("OK", null).show();
        });
    }
}
