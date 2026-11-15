package com.fluxtv.app;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Environment;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Live TV recordings: schedule a programme (or record what's on now), saved to this TV's storage,
 * a USB drive, or a network (SMB) share. Recordings run in DvrService, woken by an alarm at the start time.
 *
 * Each recording: id, source, channel, channelName, logo, title, desc, start, end, status, file, size, error.
 * status: scheduled · recording · done · failed · cancelled
 */
public final class Dvr {

    public static final String SCHEDULED = "scheduled", RECORDING = "recording", DONE = "done", FAILED = "failed";

    private Dvr() { }

    // ------------------------------------------------------------ settings

    /** "local" (this TV), "usb:N" (removable drive N), or "smb". */
    public static String target() {
        String t = Store.get("dvr_target");
        return t.isEmpty() ? "local" : t;
    }

    public static void setTarget(String t) {
        Store.put("dvr_target", t);
    }

    public static int padBeforeMin() { return intPref("dvr_pad_before", 1); }
    public static int padAfterMin() { return intPref("dvr_pad_after", 3); }

    private static int intPref(String k, int def) {
        try { return Integer.parseInt(Store.get(k)); } catch (Exception e) { return def; }
    }

    /** Storage choices on this TV: [key, label, folder]. */
    public static List<String[]> localTargets(Context c) {
        List<String[]> out = new ArrayList<>();
        File[] dirs = c.getExternalFilesDirs(Environment.DIRECTORY_MOVIES);
        for (int i = 0; i < dirs.length; i++) {
            if (dirs[i] == null) continue;
            File d = new File(dirs[i], "Recordings");
            long free = dirs[i].getUsableSpace();
            String label = (i == 0 ? "This TV" : "USB drive " + (dirs.length > 2 ? i : "")).trim()
                    + " (" + gb(free) + " free)";
            out.add(new String[]{i == 0 ? "local" : "usb:" + i, label, d.getAbsolutePath()});
        }
        return out;
    }

    static File localFolder(Context c) {
        String t = target();
        int idx = 0;
        if (t.startsWith("usb:")) try { idx = Integer.parseInt(t.substring(4)); } catch (Exception ignored) { }
        File[] dirs = c.getExternalFilesDirs(Environment.DIRECTORY_MOVIES);
        File base = idx < dirs.length && dirs[idx] != null ? dirs[idx] : (dirs.length > 0 && dirs[0] != null ? dirs[0] : c.getFilesDir());
        File d = new File(base, "Recordings");
        d.mkdirs();
        return d;
    }

    public static String targetLabel(Context c) {
        if ("smb".equals(target())) return Smb.configured() ? "Network share: \\\\" + Smb.host() + "\\" + Smb.path().replace('/', '\\') : "Network share (not set up)";
        for (String[] t : localTargets(c)) if (t[0].equals(target())) return t[1];
        return "This TV";
    }

    static String gb(long bytes) {
        return String.format(Locale.US, bytes > 10L * 1024 * 1024 * 1024 ? "%.0f GB" : "%.1f GB", bytes / 1073741824.0);
    }

    // ------------------------------------------------------------ the list

    private static List<JSONObject> memo;
    private static int memoVersion = -1;

    /** All recordings (cached copy; don't modify the objects). */
    public static synchronized List<JSONObject> all() {
        if (memo != null && memoVersion == version) return memo;
        memo = load();
        memoVersion = version;
        return memo;
    }

    private static List<JSONObject> load() {
        List<JSONObject> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(Store.get("dvr_list").isEmpty() ? "[]" : Store.get("dvr_list"));
            for (int i = 0; i < a.length(); i++) out.add(a.getJSONObject(i));
        } catch (JSONException ignored) { }
        Collections.sort(out, (x, y) -> Long.compare(x.optLong("start"), y.optLong("start")));
        return out;
    }

    public static synchronized JSONObject get(String id) {
        for (JSONObject r : load()) if (r.optString("id").equals(id)) return r;
        return null;
    }

    static synchronized void put(JSONObject rec) {
        List<JSONObject> list = load();
        boolean found = false;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).optString("id").equals(rec.optString("id"))) { list.set(i, rec); found = true; }
        }
        if (!found) list.add(rec);
        Store.put("dvr_list", new JSONArray(list).toString());
        version++;
    }

    static synchronized void update(String id, String key, Object value) {
        JSONObject r = get(id);
        if (r == null) return;
        try { r.put(key, value); } catch (JSONException ignored) { }
        put(r);
    }

    private static int version;

    /** Bumps whenever the recordings list changes (screens refresh). */
    public static int version() {
        return version;
    }

    /** Is this programme (channel + start) already set to record? */
    public static JSONObject find(Iptv.Source s, Iptv.Channel c, long start) {
        for (JSONObject r : all()) {
            if (r.optString("source").equals(s.id) && r.optString("channel").equals(c.id)
                    && Math.abs(r.optLong("start") - start) < 60000 && !"cancelled".equals(r.optString("status"))) return r;
        }
        return null;
    }

    public static boolean recordingNow(Iptv.Source s, Iptv.Channel c) {
        for (JSONObject r : all()) {
            if (RECORDING.equals(r.optString("status")) && r.optString("source").equals(s.id) && r.optString("channel").equals(c.id)) return true;
        }
        return false;
    }

    // ------------------------------------------------------------ scheduling

    /** Records a programme (now or later). Returns the recording. */
    public static JSONObject schedule(Context ctx, Iptv.Source s, Iptv.Channel c, String title, String desc, long start, long end) {
        JSONObject existing = find(s, c, start);
        if (existing != null) return existing;
        JSONObject r = new JSONObject();
        try {
            r.put("id", Long.toString(System.currentTimeMillis(), 36) + Integer.toString((int) (Math.random() * 1296), 36));
            r.put("source", s.id);
            r.put("channel", c.id);
            r.put("channelName", c.name);
            r.put("logo", c.logo);
            r.put("title", title == null || title.isEmpty() ? c.name : title);
            r.put("desc", desc == null ? "" : desc);
            r.put("start", start);
            r.put("end", end);
            r.put("status", SCHEDULED);
            r.put("target", target());
        } catch (JSONException ignored) { }
        put(r);
        arm(ctx, r);
        return r;
    }

    /** Records what's on now until the programme ends (or for an hour if there's no guide). */
    public static JSONObject recordNow(Context ctx, Iptv.Source s, Iptv.Channel c) {
        long now = System.currentTimeMillis();
        String title = c.name;
        String desc = "";
        long end = now + 60 * 60 * 1000L;
        for (Iptv.Program p : Iptv.programs(s, c)) {
            if (p.start <= now && p.end > now) { title = p.title; desc = p.desc; end = p.end; break; }
        }
        return schedule(ctx, s, c, title, desc, now, end);
    }

    public static void cancel(Context ctx, String id) {
        JSONObject r = get(id);
        if (r == null) return;
        if (RECORDING.equals(r.optString("status"))) {
            DvrService.stop(ctx, id);
        } else {
            alarms(ctx).cancel(alarmIntent(ctx, id));
            remove(id);
        }
    }

    /** Deletes the entry and its file. */
    public static void delete(Context ctx, String id) {
        JSONObject r = get(id);
        if (r == null) return;
        if (RECORDING.equals(r.optString("status"))) DvrService.stop(ctx, id);
        String f = r.optString("file");
        App.IO.execute(() -> {
            if (f.startsWith("smb://")) Smb.delete(f);
            else if (!f.isEmpty()) new File(f).delete();
        });
        alarms(ctx).cancel(alarmIntent(ctx, id));
        remove(id);
    }

    static synchronized void remove(String id) {
        List<JSONObject> list = load();
        for (int i = list.size() - 1; i >= 0; i--) if (list.get(i).optString("id").equals(id)) list.remove(i);
        Store.put("dvr_list", new JSONArray(list).toString());
        version++;
    }

    private static AlarmManager alarms(Context c) {
        return (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
    }

    private static PendingIntent alarmIntent(Context c, String id) {
        Intent i = new Intent(c, DvrAlarm.class).setAction("com.fluxtv.app.DVR").putExtra("id", id);
        return PendingIntent.getBroadcast(c, id.hashCode(), i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** Can this TV wake Flux exactly on time? (Android 12+ may need "Alarms & reminders" allowed.) */
    public static boolean exactAlarmsAllowed(Context c) {
        return Build.VERSION.SDK_INT < 31 || alarms(c).canScheduleExactAlarms();
    }

    static void arm(Context ctx, JSONObject r) {
        long when = r.optLong("start") - padBeforeMin() * 60000L;
        if (when <= System.currentTimeMillis() + 2000) {
            DvrService.start(ctx, r.optString("id"));
            return;
        }
        PendingIntent pi = alarmIntent(ctx, r.optString("id"));
        AlarmManager am = alarms(ctx);
        try {
            if (exactAlarmsAllowed(ctx)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, pi);
            else am.setWindow(AlarmManager.RTC_WAKEUP, when - 60000, 60000, pi);
        } catch (SecurityException e) {
            am.setWindow(AlarmManager.RTC_WAKEUP, when - 60000, 60000, pi);
        }
    }

    /** After a restart (or app update): re-arm scheduled ones, start any that should be running. */
    public static void rearm(Context ctx) {
        long now = System.currentTimeMillis();
        for (JSONObject r : all()) {
            String st = r.optString("status");
            if (SCHEDULED.equals(st) || RECORDING.equals(st)) {
                if (r.optLong("end") + padAfterMin() * 60000L < now) {
                    if (RECORDING.equals(st)) update(r.optString("id"), "status", r.optLong("size") > 0 ? DONE : FAILED);
                    else update(r.optString("id"), "status", FAILED);
                    if (SCHEDULED.equals(st)) update(r.optString("id"), "error", "The TV was off or asleep at the start time");
                } else {
                    arm(ctx, r);
                }
            }
        }
    }

    // ------------------------------------------------------------ file names

    static String fileName(JSONObject r) {
        String when = new SimpleDateFormat("yyyy-MM-dd HHmm", Locale.US).format(new Date(r.optLong("start")));
        String name = r.optString("title") + " - " + r.optString("channelName") + " - " + when;
        name = name.replaceAll("[\\\\/:*?\"<>|]", "_").replaceAll("\\s+", " ").trim();
        if (name.length() > 120) name = name.substring(0, 120);
        return name + ".ts";
    }

    public static String timeRange(JSONObject r) {
        SimpleDateFormat day = new SimpleDateFormat("EEE MMM d", Locale.getDefault());
        java.text.DateFormat t = android.text.format.DateFormat.getTimeFormat(App.ctx);
        return day.format(new Date(r.optLong("start"))) + " · " + t.format(new Date(r.optLong("start"))) + "–" + t.format(new Date(r.optLong("end")));
    }
}
