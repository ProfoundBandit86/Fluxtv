package com.fluxtv.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import org.json.JSONObject;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Records live channels in the background (several at once), keeping the TV awake while it does.
 * MPEG-TS streams are saved as they arrive; HLS streams are saved segment by segment. Drop-outs reconnect.
 */
public class DvrService extends Service {

    private static final String CHANNEL = "dvr";
    private static final Map<String, Job> JOBS = new ConcurrentHashMap<>();

    private PowerManager.WakeLock wake;
    private WifiManager.WifiLock wifi;

    public static void start(Context c, String id) {
        Intent i = new Intent(c, DvrService.class).putExtra("id", id).setAction("start");
        try {
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
            else c.startService(i);
        } catch (Exception e) {
            Dvr.update(id, "status", Dvr.FAILED);
            Dvr.update(id, "error", "Android didn't let Flux start the recording in the background (" + e.getClass().getSimpleName() + ")");
        }
    }

    public static void stop(Context c, String id) {
        Job j = JOBS.get(id);
        if (j != null) j.stop = true;
    }

    public static boolean active(String id) {
        return JOBS.containsKey(id);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Recordings", NotificationManager.IMPORTANCE_LOW));
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "flux:dvr");
        wake.setReferenceCounted(false);
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        if (wm != null) {
            wifi = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "flux:dvr");
            wifi.setReferenceCounted(false);
        }
    }

    private Notification note() {
        int n = JOBS.size();
        String text = n == 0 ? "Starting…" : n == 1 ? "Recording " + JOBS.values().iterator().next().title : "Recording " + n + " programmes";
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        return b.setContentTitle("Flux DVR").setContentText(text).setSmallIcon(R.drawable.ic_live).setOngoing(true).build();
    }

    private void foreground() {
        if (Build.VERSION.SDK_INT >= 29) startForeground(42, note(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        else startForeground(42, note());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        foreground();
        String id = intent == null ? null : intent.getStringExtra("id");
        if (id != null && !JOBS.containsKey(id)) {
            JSONObject r = Dvr.get(id);
            if (r != null && !Dvr.DONE.equals(r.optString("status"))) {
                Job j = new Job(r);
                JOBS.put(id, j);
                new Thread(() -> run(j), "dvr-" + id).start();
            }
        }
        if (JOBS.isEmpty()) {
            stopForeground(true);
            stopSelf();
        } else {
            wake.acquire(12 * 3600 * 1000L);
            if (wifi != null) wifi.acquire();
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(42, note());
        }
        return START_NOT_STICKY;
    }

    private void finished(Job j) {
        JOBS.remove(j.id);
        App.MAIN.post(() -> {
            if (JOBS.isEmpty()) {
                if (wake.isHeld()) wake.release();
                if (wifi != null && wifi.isHeld()) wifi.release();
                stopForeground(true);
                stopSelf();
            } else {
                ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(42, note());
            }
        });
    }

    // ------------------------------------------------------------ one recording

    private static final class Job {
        final String id, title;
        final JSONObject rec;
        volatile boolean stop;
        long bytes;

        Job(JSONObject r) {
            rec = r;
            id = r.optString("id");
            title = r.optString("title");
        }
    }

    private void run(Job j) {
        JSONObject r = j.rec;
        long stopAt = r.optLong("end") + Dvr.padAfterMin() * 60000L;
        Iptv.Source s = Iptv.source(r.optString("source"));
        Iptv.Channel c = null;
        String error = "";
        OutputStream out = null;
        String path = "";
        try {
            if (s == null) throw new Exception("The IPTV provider was removed");
            for (Iptv.Channel ch : Iptv.channels(s)) if (ch.id.equals(r.optString("channel"))) { c = ch; break; }
            if (c == null) throw new Exception("The channel is no longer in the provider's list");
            // Wait for the start (we were woken a minute early).
            while (!j.stop && System.currentTimeMillis() < r.optLong("start") - Dvr.padBeforeMin() * 60000L) Thread.sleep(1000);
            if (j.stop) throw new InterruptedException();
            String name = Dvr.fileName(r);
            String target = r.optString("target", Dvr.target());
            if ("smb".equals(target)) {
                out = Smb.create(name);
                path = Smb.url(Smb.path() + "/" + name);
            } else {
                Dvr.setTarget(target);   // keep the drive chosen at scheduling time
                File f = new File(Dvr.localFolder(this), name);
                out = new FileOutputStream(f);
                path = f.getAbsolutePath();
            }
            out = new BufferedOutputStream(out, 512 * 1024);
            Dvr.update(j.id, "status", Dvr.RECORDING);
            Dvr.update(j.id, "file", path);
            Dvr.update(j.id, "error", "");

            String ts = Iptv.liveUrl(s, c, false), hls = s.xtream() ? Iptv.liveUrl(s, c, true) : null;
            int failures = 0;
            String linkOf = ts;
            boolean useHls = ts.contains(".m3u8");
            while (!j.stop && System.currentTimeMillis() < stopAt) {
                try {
                    if (linkOf.startsWith("fluxiptv://")) {   // MAC portal: fresh link each (re)connect
                        ts = Iptv.resolve(linkOf);
                        useHls = useHls || ts.contains(".m3u8");
                    }
                    if (useHls) recordHls(j, ts, out, stopAt);
                    else recordTs(j, ts, out, stopAt);
                    failures = 0;
                } catch (HlsInstead e) {
                    useHls = true;
                } catch (Exception e) {
                    failures++;
                    error = App.message(e);
                    if (!useHls && hls != null && j.bytes == 0 && failures >= 2) { useHls = true; ts = hls; }
                    if (failures > 30) throw e;
                    Thread.sleep(Math.min(10000, 1000L * failures));
                }
                Dvr.update(j.id, "size", j.bytes);
            }
            out.flush();
            out.close();
            out = null;
            Dvr.update(j.id, "size", j.bytes);
            if (j.bytes > 0) {
                Dvr.update(j.id, "status", Dvr.DONE);
                Dvr.update(j.id, "error", "");
                Dvr.update(j.id, "end", Math.min(System.currentTimeMillis(), r.optLong("end")));
            } else {
                Dvr.update(j.id, "status", Dvr.FAILED);
                Dvr.update(j.id, "error", error.isEmpty() ? "Nothing was received from the channel" : error);
            }
        } catch (InterruptedException e) {
            Dvr.update(j.id, "status", j.bytes > 0 ? Dvr.DONE : Dvr.FAILED);
            if (j.bytes == 0) Dvr.update(j.id, "error", "Stopped");
        } catch (Exception e) {
            Dvr.update(j.id, "status", j.bytes > 0 ? Dvr.DONE : Dvr.FAILED);
            Dvr.update(j.id, "error", App.message(e));
        } finally {
            try { if (out != null) out.close(); } catch (Exception ignored) { }
            if (j.stop && j.bytes > 0) Dvr.update(j.id, "end", System.currentTimeMillis());
            finished(j);
        }
    }

    private static final class HlsInstead extends Exception { }

    private static HttpURLConnection connect(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(20000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "FluxTV/1.0 (Android TV)");
        int code = c.getResponseCode();
        // Follow redirects across hosts / http→https (Java won't by itself).
        for (int i = 0; i < 5 && code >= 300 && code < 400; i++) {
            String loc = c.getHeaderField("Location");
            c.disconnect();
            if (loc == null) break;
            c = (HttpURLConnection) new URL(new URL(url), loc).openConnection();
            c.setConnectTimeout(15000);
            c.setReadTimeout(20000);
            c.setRequestProperty("User-Agent", "FluxTV/1.0 (Android TV)");
            url = c.getURL().toString();
            code = c.getResponseCode();
        }
        if (code >= 400) {
            c.disconnect();
            throw new Exception("The channel answered " + code);
        }
        return c;
    }

    private void recordTs(Job j, String url, OutputStream out, long stopAt) throws Exception {
        HttpURLConnection c = connect(url);
        String type = String.valueOf(c.getContentType()).toLowerCase();
        try (InputStream in = c.getInputStream()) {
            byte[] buf = new byte[188 * 700];
            int n = in.read(buf);
            if (n <= 0) throw new Exception("No data");
            String head = new String(buf, 0, Math.min(n, 16), "ISO-8859-1");
            if (type.contains("mpegurl") || head.startsWith("#EXTM3U")) throw new HlsInstead();
            do {
                out.write(buf, 0, n);
                j.bytes += n;
                if (j.stop || System.currentTimeMillis() >= stopAt) return;
            } while ((n = in.read(buf)) > 0);
        } finally {
            c.disconnect();
        }
        throw new Exception("The stream ended");
    }

    /** HLS: fetch the playlist repeatedly and append each new segment. */
    private void recordHls(Job j, String url, OutputStream out, long stopAt) throws Exception {
        Set<String> done = new LinkedHashSet<>();
        String playlist = url;
        int idle = 0;
        while (!j.stop && System.currentTimeMillis() < stopAt) {
            HttpURLConnection c = connect(playlist);
            String base = c.getURL().toString();
            List<String> lines = new ArrayList<>();
            try (java.io.BufferedReader rd = new java.io.BufferedReader(new java.io.InputStreamReader(c.getInputStream()))) {
                String l;
                while ((l = rd.readLine()) != null) lines.add(l.trim());
            } finally {
                c.disconnect();
            }
            // Master playlist: pick the highest-quality variant.
            if (lines.toString().contains("#EXT-X-STREAM-INF")) {
                long best = -1;
                String pick = null;
                for (int i = 0; i < lines.size() - 1; i++) {
                    String l = lines.get(i);
                    if (!l.startsWith("#EXT-X-STREAM-INF")) continue;
                    java.util.regex.Matcher m = java.util.regex.Pattern.compile("BANDWIDTH=(\\d+)").matcher(l);
                    long bw = m.find() ? Long.parseLong(m.group(1)) : 0;
                    String next = lines.get(i + 1);
                    if (bw > best && !next.startsWith("#")) { best = bw; pick = next; }
                }
                if (pick == null) throw new Exception("Empty HLS playlist");
                playlist = new URL(new URL(base), pick).toString();
                continue;
            }
            int target = 6;
            boolean got = false;
            for (String l : lines) {
                if (l.startsWith("#EXT-X-TARGETDURATION:")) try { target = Integer.parseInt(l.substring(22).trim()); } catch (Exception ignored) { }
                if (l.isEmpty() || l.startsWith("#")) continue;
                String seg = new URL(new URL(base), l).toString();
                if (!done.add(seg)) continue;
                got = true;
                HttpURLConnection sc = connect(seg);
                try (InputStream in = sc.getInputStream()) {
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                        j.bytes += n;
                    }
                } finally {
                    sc.disconnect();
                }
                if (j.stop || System.currentTimeMillis() >= stopAt) return;
            }
            while (done.size() > 500) done.remove(done.iterator().next());
            if (lines.contains("#EXT-X-ENDLIST") && !got) {
                if (++idle > 3) throw new Exception("The stream ended");
            }
            Thread.sleep(Math.max(1000, target * 500L));
        }
    }

    @Override
    public void onDestroy() {
        for (Job j : JOBS.values()) j.stop = true;
        if (wake != null && wake.isHeld()) wake.release();
        if (wifi != null && wifi.isHeld()) wifi.release();
        super.onDestroy();
    }
}
