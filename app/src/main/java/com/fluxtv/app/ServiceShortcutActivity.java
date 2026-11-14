package com.fluxtv.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

import java.util.List;

/**
 * Lets apps like Button Mapper add a shortcut to one of Flux's streaming-service pages
 * (e.g. the remote's Netflix button → Flux's Netflix page). Shows up in their "Shortcut" list as "Flux service page".
 */
public class ServiceShortcutActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        List<String[]> services = Tmdb.serviceNames();
        String[] names = new String[services.size()];
        for (int i = 0; i < names.length; i++) names[i] = services.get(i)[1];
        new AlertDialog.Builder(this)
                .setTitle("Open which Flux page?")
                .setItems(names, (d, w) -> {
                    String[] s = services.get(w);
                    Intent open = new Intent(Intent.ACTION_VIEW, Uri.parse("fluxtv://service/" + s[0]))
                            .setClassName(getPackageName(), ServiceActivity.class.getName())
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                    Intent result = new Intent();
                    result.putExtra(Intent.EXTRA_SHORTCUT_INTENT, open);
                    result.putExtra(Intent.EXTRA_SHORTCUT_NAME, "Flux · " + s[1]);
                    result.putExtra(Intent.EXTRA_SHORTCUT_ICON_RESOURCE, Intent.ShortcutIconResource.fromContext(this, R.drawable.icon));
                    setResult(RESULT_OK, result);
                    finish();
                })
                .setOnCancelListener(d -> { setResult(RESULT_CANCELED); finish(); })
                .show();
    }
}
