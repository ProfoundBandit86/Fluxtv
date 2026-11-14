package com.fluxtv.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;

/**
 * Self-updater. Each time the home screen comes back ("reload"), it checks the latest
 * GitHub release of this repo. If it's newer, the APK is downloaded and installed straight
 * away through Android's PackageInstaller.
 *
 * Releases are created by .github/workflows/build.yml. The tag is v&lt;versionCode&gt;.
 */
public final class Updater {

    /** GitHub repo that publishes releases. */
    public static final String REPO = "ProfoundBandit86/fluxtv";
    private static final long MIN_INTERVAL_MS = 15 * 60 * 1000L;   // stays well inside GitHub's rate limit

    private static long lastCheck = 0;
    private static boolean busy = false;
    private static boolean askedPermission = false;

    static class Release {
        long code;
        String tag, apkUrl;
    }

    private Updater() { }

    /** Called from the home screen's onResume. force = user pressed "Check for updates". */
    public static void check(Activity a, boolean force) {
        long now = System.currentTimeMillis();
        if (busy || (!force && now - lastCheck < MIN_INTERVAL_MS)) return;
        lastCheck = now;
        busy = true;
        if (force) toast(a, "Checking for updates…");
        App.async(Updater::latest, rel -> {
            if (rel == null || rel.apkUrl == null || rel.code <= currentCode(a)) {
                busy = false;
                if (force) toast(a, "Flux TV is up to date (" + currentName(a) + ")");
                return;
            }
            install(a, rel, force);
        }, e -> {
            busy = false;
            if (force) toast(a, "Update check failed: " + App.message(e));
        });
    }

    static Release latest() throws Exception {
        JSONObject j = new JSONObject(Http.call("GET", "https://api.github.com/repos/" + REPO + "/releases/latest",
                Http.map("Accept", "application/vnd.github+json"), null, null));
        Release r = new Release();
        r.tag = j.optString("tag_name");
        String digits = r.tag.replaceAll("[^0-9]", "");
        r.code = digits.isEmpty() ? 0 : Long.parseLong(digits);
        JSONArray assets = j.optJSONArray("assets");
        if (assets != null) for (int i = 0; i < assets.length(); i++) {
            JSONObject as = assets.optJSONObject(i);
            if (as != null && as.optString("name").endsWith(".apk")) {
                r.apkUrl = as.optString("browser_download_url");
                break;
            }
        }
        return r;
    }

    public static long currentCode(Context c) {
        try {
            PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            return Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode;
        } catch (PackageManager.NameNotFoundException e) {
            return Long.MAX_VALUE;
        }
    }

    public static String currentName(Context c) {
        try {
            return "v" + c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "";
        }
    }

    private static void install(Activity a, Release rel, boolean force) {
        PackageManager pm = a.getPackageManager();
        if (Build.VERSION.SDK_INT >= 26 && !pm.canRequestPackageInstalls()) {
            busy = false;
            if (askedPermission && !force) return;   // ask once per app launch
            askedPermission = true;
            new AlertDialog.Builder(a)
                    .setTitle("Update available (" + rel.tag + ")")
                    .setMessage("To update itself automatically, Flux TV needs permission to install apps. "
                            + "Turn on \"Allow from this source\" on the next screen, then come back.")
                    .setPositiveButton("Open settings", (d, w) -> {
                        try {
                            a.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                    Uri.parse("package:" + a.getPackageName())));
                        } catch (Exception e) {
                            toast(a, "Open Settings → Apps → Special app access → Install unknown apps → Flux TV");
                        }
                    })
                    .setNegativeButton("Later", null)
                    .show();
            return;
        }
        toast(a, "Updating Flux TV to " + rel.tag + "…");
        Context app = a.getApplicationContext();
        App.async(() -> {
            downloadAndCommit(app, rel);
            return Boolean.TRUE;
        }, ok -> busy = false, e -> {
            busy = false;
            toast(app, "Update failed: " + App.message(e));
        });
    }

    private static void downloadAndCommit(Context c, Release rel) throws Exception {
        PackageInstaller installer = c.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(c.getPackageName());
        if (Build.VERSION.SDK_INT >= 31) {
            // Lets Android 12+ update silently once Flux is the installer of record.
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
        }
        int id = installer.createSession(params);
        PackageInstaller.Session session = installer.openSession(id);
        try {
            try (OutputStream out = session.openWrite("flux-tv.apk", 0, -1)) {
                Http.download(rel.apkUrl, out);
                session.fsync(out);
            }
            int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
            PendingIntent done = PendingIntent.getBroadcast(c, id, new Intent(c, UpdateReceiver.class), flags);
            session.commit(done.getIntentSender());
        } catch (Exception e) {
            session.abandon();
            throw e;
        } finally {
            session.close();
        }
    }

    static void toast(Context c, String msg) {
        App.MAIN.post(() -> Toast.makeText(c, msg, Toast.LENGTH_LONG).show());
    }
}
