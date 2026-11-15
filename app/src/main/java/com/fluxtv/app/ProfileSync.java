package com.fluxtv.app;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * TV-to-TV sync over the home network: no account, no server, no internet.
 *
 * Every Flux TV listens on port 8091 and announces itself on the network (mDNS "_fluxtv._tcp").
 * Linking two TVs once (a 6-digit code shown on one, typed on the other) gives them a shared key.
 * After that, linked TVs swap profiles, age limits/PINs, Favorites and watch history whenever Flux opens,
 * every few minutes, and after changes. Newest change wins; removals carry over.
 */
public final class ProfileSync {

    public static final int PORT = 8091;
    private static final String SERVICE = "_fluxtv._tcp.";

    private static ServerSocket server;
    private static NsdManager nsd;
    private static NsdManager.RegistrationListener registration;
    private static NsdManager.DiscoveryListener discovery;
    /** TVs seen on the network: id → {id, name, host, port}. */
    private static final Map<String, JSONObject> FOUND = new LinkedHashMap<>();
    private static final List<NsdServiceInfo> TO_RESOLVE = new ArrayList<>();
    private static boolean resolving;

    private static String pairCode;
    private static long pairUntil;
    private static long lastSync;
    private static String lastResult = "";
    private static Runnable onMerged;

    private ProfileSync() { }

    // ------------------------------------------------------------ setup

    public static synchronized void start(Context c) {
        Context app = c.getApplicationContext();
        if (server == null) {
            try {
                server = new ServerSocket(PORT);
                final ServerSocket s = server;
                Thread t = new Thread(() -> {
                    while (!s.isClosed()) {
                        try {
                            Socket client = s.accept();
                            App.IO.execute(() -> handle(client));
                        } catch (IOException e) {
                            break;
                        }
                    }
                }, "flux-sync");
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                server = null;
            }
        }
        if (nsd == null) {
            try {
                nsd = (NsdManager) app.getSystemService(Context.NSD_SERVICE);
                announce();
                discover();
            } catch (Exception ignored) { }
        }
        App.MAIN.removeCallbacks(TICK);
        App.MAIN.postDelayed(TICK, 4000);
    }

    private static final Runnable TICK = new Runnable() {
        @Override public void run() {
            syncNow(null);
            App.MAIN.postDelayed(this, 3 * 60 * 1000);
        }
    };

    /** Called when another TV's changes arrive (e.g. Home refreshes its rows). */
    public static void setOnMerged(Runnable r) {
        onMerged = r;
    }

    public static String deviceName() {
        String n = Store.get("sync_name");
        if (!n.isEmpty()) return n;
        try {
            n = android.provider.Settings.Global.getString(App.ctx.getContentResolver(), "device_name");
        } catch (Exception ignored) { }
        if (n == null || n.isEmpty()) n = android.os.Build.MODEL;
        return n == null || n.isEmpty() ? "Flux TV" : n;
    }

    private static void announce() {
        NsdServiceInfo info = new NsdServiceInfo();
        info.setServiceName("Flux " + deviceName());
        info.setServiceType(SERVICE);
        info.setPort(PORT);
        info.setAttribute("id", Store.deviceId());
        info.setAttribute("name", deviceName());
        registration = new NsdManager.RegistrationListener() {
            @Override public void onRegistrationFailed(NsdServiceInfo i, int e) { }
            @Override public void onUnregistrationFailed(NsdServiceInfo i, int e) { }
            @Override public void onServiceRegistered(NsdServiceInfo i) { }
            @Override public void onServiceUnregistered(NsdServiceInfo i) { }
        };
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, registration);
    }

    private static void discover() {
        discovery = new NsdManager.DiscoveryListener() {
            @Override public void onStartDiscoveryFailed(String t, int e) { }
            @Override public void onStopDiscoveryFailed(String t, int e) { }
            @Override public void onDiscoveryStarted(String t) { }
            @Override public void onDiscoveryStopped(String t) { }
            @Override public void onServiceLost(NsdServiceInfo i) { }
            @Override public void onServiceFound(NsdServiceInfo i) {
                synchronized (TO_RESOLVE) { TO_RESOLVE.add(i); }
                resolveNext();
            }
        };
        nsd.discoverServices(SERVICE, NsdManager.PROTOCOL_DNS_SD, discovery);
    }

    /** Older Android resolves one service at a time. */
    private static void resolveNext() {
        NsdServiceInfo next;
        synchronized (TO_RESOLVE) {
            if (resolving || TO_RESOLVE.isEmpty()) return;
            resolving = true;
            next = TO_RESOLVE.remove(0);
        }
        nsd.resolveService(next, new NsdManager.ResolveListener() {
            @Override public void onResolveFailed(NsdServiceInfo i, int e) { done(); }
            @Override public void onServiceResolved(NsdServiceInfo i) {
                try {
                    Map<String, byte[]> a = i.getAttributes();
                    String id = a.containsKey("id") ? new String(a.get("id"), StandardCharsets.UTF_8) : "";
                    String name = a.containsKey("name") ? new String(a.get("name"), StandardCharsets.UTF_8) : i.getServiceName();
                    InetAddress h = i.getHost();
                    if (!id.isEmpty() && !id.equals(Store.deviceId()) && h != null) {
                        JSONObject tv = new JSONObject().put("id", id).put("name", name)
                                .put("host", h.getHostAddress()).put("port", i.getPort());
                        synchronized (FOUND) { FOUND.put(id, tv); }
                        updatePeerAddress(id, h.getHostAddress(), i.getPort());
                    }
                } catch (Exception ignored) { }
                done();
            }
            private void done() {
                synchronized (TO_RESOLVE) { resolving = false; }
                App.MAIN.post(ProfileSync::resolveNext);
            }
        });
    }

    // ------------------------------------------------------------ linking

    public static String key() {
        return Store.get("sync_key");
    }

    /** Linked TVs: [{id, name, host, port, last}]. */
    public static synchronized List<JSONObject> peers() {
        List<JSONObject> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(Store.get("sync_peers").isEmpty() ? "[]" : Store.get("sync_peers"));
            for (int i = 0; i < a.length(); i++) out.add(a.getJSONObject(i));
        } catch (JSONException ignored) { }
        return out;
    }

    private static synchronized void savePeers(List<JSONObject> list) {
        Store.put("sync_peers", new JSONArray(list).toString());
    }

    private static synchronized void addPeer(String id, String name, String host, int port) {
        List<JSONObject> list = peers();
        for (Iterator<JSONObject> it = list.iterator(); it.hasNext(); ) if (it.next().optString("id").equals(id)) it.remove();
        try { list.add(new JSONObject().put("id", id).put("name", name).put("host", host).put("port", port)); } catch (JSONException ignored) { }
        savePeers(list);
    }

    private static synchronized void updatePeerAddress(String id, String host, int port) {
        List<JSONObject> list = peers();
        boolean changed = false;
        for (JSONObject p : list) {
            if (p.optString("id").equals(id) && (!host.equals(p.optString("host")) || p.optInt("port") != port)) {
                try { p.put("host", host).put("port", port); } catch (JSONException ignored) { }
                changed = true;
            }
        }
        if (changed) savePeers(list);
    }

    public static synchronized void unlink(String id) {
        List<JSONObject> list = peers();
        for (Iterator<JSONObject> it = list.iterator(); it.hasNext(); ) if (it.next().optString("id").equals(id)) it.remove();
        savePeers(list);
    }

    public static boolean isPeer(String id) {
        for (JSONObject p : peers()) if (p.optString("id").equals(id)) return true;
        return false;
    }

    /** Flux TVs on the network that aren't linked yet. */
    public static List<JSONObject> nearby() {
        List<JSONObject> out = new ArrayList<>();
        synchronized (FOUND) {
            for (JSONObject tv : FOUND.values()) if (!isPeer(tv.optString("id"))) out.add(tv);
        }
        return out;
    }

    /** Shows a 6-digit code on this TV for 10 minutes; the other TV types it in. */
    public static synchronized String newPairCode() {
        pairCode = String.valueOf(100000 + new java.security.SecureRandom().nextInt(900000));
        pairUntil = System.currentTimeMillis() + 10 * 60 * 1000;
        return pairCode;
    }

    public static synchronized String currentPairCode() {
        return pairCode != null && System.currentTimeMillis() < pairUntil ? pairCode : null;
    }

    /** This TV links to another one (host, or host:port) using the code shown on it. Blocking. */
    public static String link(String host, String code) throws Exception {
        host = host.trim().replaceFirst("^https?://", "").replaceAll("/.*$", "");
        int port = PORT;
        int colon = host.lastIndexOf(':');
        if (colon > 0) {
            try { port = Integer.parseInt(host.substring(colon + 1)); } catch (NumberFormatException ignored) { }
            host = host.substring(0, colon);
        }
        JSONObject req = new JSONObject().put("code", code.trim()).put("id", Store.deviceId()).put("name", deviceName())
                .put("key", key());
        String body = Http.call("POST", "http://" + host + ":" + port + "/sync/pair", null, req.toString(), "application/json");
        JSONObject r = new JSONObject(body);
        if (!r.optBoolean("ok")) throw new Exception(r.optString("error", "That code didn't work"));
        Store.put("sync_key", r.getString("key"));
        addPeer(r.optString("id"), r.optString("name"), host, port);
        lastSync = 0;
        sync(true);
        return r.optString("name");
    }

    /** One-time copy of every setting (add-ons, accounts, API keys, look) from a linked TV. Blocking. */
    public static int copySettingsFrom(String peerId) throws Exception {
        for (JSONObject p : peers()) {
            if (!p.optString("id").equals(peerId)) continue;
            String base = "http://" + p.optString("host") + ":" + p.optInt("port", PORT);
            JSONObject all = new JSONObject(Http.call("GET", base + "/sync/settings", Http.map("X-Flux-Key", key()), null, null));
            int n = Store.importSettings(all);
            Theme.apply();
            return n;
        }
        throw new Exception("That TV isn't linked");
    }

    // ------------------------------------------------------------ syncing

    public static String status() {
        if (peers().isEmpty()) return "Not linked to another TV.";
        return lastResult.isEmpty() ? "Linked. Waiting to sync…" : lastResult;
    }

    /** Syncs in the background (at most every 20 s unless forced). */
    public static void syncNow(Runnable done) {
        App.IO.execute(() -> {
            sync(false);
            if (done != null) App.MAIN.post(done);
        });
    }

    public static void syncForced(Runnable done) {
        App.IO.execute(() -> {
            sync(true);
            if (done != null) App.MAIN.post(done);
        });
    }

    private static final Object SYNC_LOCK = new Object();

    private static void sync(boolean force) {
        synchronized (SYNC_LOCK) {
            if (!force && System.currentTimeMillis() - lastSync < 20000) return;
            lastSync = System.currentTimeMillis();
            List<JSONObject> list = peers();
            if (list.isEmpty() || key().isEmpty()) return;
            int ok = 0;
            List<String> failed = new ArrayList<>();
            for (JSONObject p : list) {
                String base = "http://" + p.optString("host") + ":" + p.optInt("port", PORT);
                try {
                    Map<String, String> h = Http.map("X-Flux-Key", key());
                    JSONObject theirs = new JSONObject(Http.call("GET", base + "/sync/state", h, null, null));
                    boolean changed = merge(theirs);
                    Http.call("POST", base + "/sync/state", h, export().toString(), "application/json");
                    ok++;
                    if (changed) notifyMerged();
                } catch (Exception e) {
                    failed.add(p.optString("name"));
                }
            }
            String time = new java.text.SimpleDateFormat("h:mm a", java.util.Locale.US).format(new java.util.Date());
            lastResult = failed.isEmpty() ? "Synced with " + ok + (ok == 1 ? " TV" : " TVs") + " at " + time + "."
                    : "Couldn't reach " + android.text.TextUtils.join(", ", failed) + " at " + time
                    + " (it may be off or asleep)." + (ok > 0 ? " Synced with the rest." : "");
        }
    }

    private static void notifyMerged() {
        Runnable r = onMerged;
        if (r != null) App.MAIN.post(r);
    }

    // ------------------------------------------------------------ what's shared, and merging

    private static final String[] OBJECTS = {"library", "library_removed", "progress"};
    private static final String[] VALUES = {"max_rating", "pin", "settings_t"};

    static JSONObject export() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id", Store.deviceId());
        o.put("name", deviceName());
        o.put("profiles", new JSONArray(Store.profiles()));
        o.put("deleted", Store.deletedProfiles());
        JSONObject data = new JSONObject();
        for (String name : Store.profiles()) {
            JSONObject d = new JSONObject();
            for (String k : OBJECTS) d.put(k, Store.profileObject(k, name));
            for (String k : VALUES) d.put(k, Store.profileValue(k, name));
            data.put(name, d);
        }
        o.put("data", data);
        return o;
    }

    /** Merges another TV's state into this one. Returns true if anything here changed. */
    static synchronized boolean merge(JSONObject theirs) throws JSONException {
        boolean changed = false;
        JSONObject data = theirs.optJSONObject("data");
        if (data == null) data = new JSONObject();

        // Profiles: everyone's, minus ones deleted after they were last set up.
        JSONObject deleted = Store.deletedProfiles();
        String deletedBefore = deleted.toString();
        JSONObject td = theirs.optJSONObject("deleted");
        if (td != null) for (Iterator<String> it = td.keys(); it.hasNext(); ) {
            String n = it.next();
            if (td.optLong(n) > deleted.optLong(n)) deleted.put(n, td.optLong(n));
        }
        List<String> names = new ArrayList<>(Store.profiles());
        JSONArray tp = theirs.optJSONArray("profiles");
        if (tp != null) for (int i = 0; i < tp.length(); i++) if (!names.contains(tp.optString(i))) { names.add(tp.optString(i)); changed = true; }
        List<String> keep = new ArrayList<>();
        for (String n : names) {
            long created = Math.max(parseLong(Store.profileValue("settings_t", n)),
                    data.optJSONObject(n) == null ? 0 : parseLong(data.optJSONObject(n).optString("settings_t")));
            if (deleted.has(n) && deleted.optLong(n) >= created) { changed |= Store.profiles().contains(n); continue; }
            deleted.remove(n);
            keep.add(n);
        }
        if (!keep.equals(Store.profiles()) || !deleted.toString().equals(deletedBefore)) {
            Store.setProfiles(keep, deleted);
            changed = true;
        }

        for (String n : keep) {
            JSONObject d = data.optJSONObject(n);
            if (d == null) continue;
            // Age limit + PIN: newest wins.
            if (parseLong(d.optString("settings_t")) > parseLong(Store.profileValue("settings_t", n))) {
                for (String k : VALUES) {
                    String v = d.optString(k);
                    Store.putProfileValue(k, n, v.isEmpty() ? null : v);
                }
                changed = true;
            }
            // Removals: newest per title.
            JSONObject removed = Store.profileObject("library_removed", n);
            JSONObject tr = d.optJSONObject("library_removed");
            boolean rChanged = false;
            if (tr != null) for (Iterator<String> it = tr.keys(); it.hasNext(); ) {
                String id = it.next();
                if (tr.optLong(id) > removed.optLong(id)) { removed.put(id, tr.optLong(id)); rChanged = true; }
            }
            if (rChanged) Store.putProfileObject("library_removed", n, removed);
            // Favorites: union, unless removed after being added.
            JSONObject lib = Store.profileObject("library", n);
            JSONObject tl = d.optJSONObject("library");
            boolean lChanged = false;
            if (tl != null) for (Iterator<String> it = tl.keys(); it.hasNext(); ) {
                String id = it.next();
                JSONObject e = tl.optJSONObject(id);
                if (e != null && !lib.has(id) && e.optLong("t") > removed.optLong(id)) { lib.put(id, e); lChanged = true; }
            }
            for (Iterator<String> it = lib.keys(); it.hasNext(); ) {
                String id = it.next();
                JSONObject e = lib.optJSONObject(id);
                if (e != null && removed.optLong(id) > e.optLong("t")) { it.remove(); lChanged = true; }
            }
            if (lChanged) Store.putProfileObject("library", n, lib);
            // Watch history: newest per episode/movie.
            JSONObject prog = Store.profileObject("progress", n);
            JSONObject tpg = d.optJSONObject("progress");
            boolean pChanged = false;
            if (tpg != null) for (Iterator<String> it = tpg.keys(); it.hasNext(); ) {
                String vid = it.next();
                JSONObject e = tpg.optJSONObject(vid);
                JSONObject mine = prog.optJSONObject(vid);
                if (e != null && (mine == null || e.optLong("t") > mine.optLong("t"))) { prog.put(vid, e); pChanged = true; }
            }
            if (pChanged) Store.putProfileObject("progress", n, prog);
            changed |= rChanged || lChanged || pChanged;
        }
        return changed;
    }

    private static long parseLong(String s) {
        try { return Long.parseLong(s); } catch (Exception e) { return 0; }
    }

    // ------------------------------------------------------------ the little server other TVs talk to

    private static void handle(Socket socket) {
        try (Socket c = socket) {
            c.setSoTimeout(20000);
            InputStream in = new BufferedInputStream(c.getInputStream());
            String first = line(in);
            if (first == null) return;
            String[] parts = first.split(" ");
            if (parts.length < 2) return;
            String method = parts[0], path = parts[1];
            int length = 0;
            String key = "";
            String l;
            while ((l = line(in)) != null && !l.isEmpty()) {
                String low = l.toLowerCase();
                if (low.startsWith("content-length:")) {
                    try { length = Integer.parseInt(l.substring(15).trim()); } catch (NumberFormatException ignored) { }
                } else if (low.startsWith("x-flux-key:")) {
                    key = l.substring(11).trim();
                }
            }
            length = Math.min(length, 16 * 1024 * 1024);
            byte[] body = new byte[length];
            int read = 0;
            while (read < length) {
                int n = in.read(body, read, length - read);
                if (n < 0) break;
                read += n;
            }
            String text = new String(body, 0, read, StandardCharsets.UTF_8);
            OutputStream out = c.getOutputStream();

            if (path.equals("/sync/hello")) {
                send(out, 200, new JSONObject().put("id", Store.deviceId()).put("name", deviceName()).toString());
                return;
            }
            if (path.equals("/sync/pair") && "POST".equals(method)) {
                JSONObject r = new JSONObject();
                String code = currentPairCode();
                JSONObject req = new JSONObject(text);
                if (code == null || !code.equals(req.optString("code"))) {
                    r.put("ok", false).put("error", code == null ? "Open \"Link another TV\" on the other TV first." : "Wrong code.");
                } else {
                    synchronized (ProfileSync.class) { pairCode = null; }
                    // Join a group: if this TV already has linked TVs keep its key, otherwise use the other TV's.
                    String k = key();
                    if (k.isEmpty() || peers().isEmpty()) {
                        k = req.optString("key");
                        if (k.isEmpty()) k = java.util.UUID.randomUUID().toString();
                        Store.put("sync_key", k);
                    }
                    addPeer(req.optString("id"), req.optString("name"), c.getInetAddress().getHostAddress(), PORT);
                    r.put("ok", true).put("key", k).put("id", Store.deviceId()).put("name", deviceName());
                    App.MAIN.post(() -> { Runnable x = onPaired; if (x != null) x.run(); });
                }
                send(out, 200, r.toString());
                return;
            }
            if (key().isEmpty() || !key().equals(key)) {
                send(out, 403, "{\"error\":\"not linked\"}");
                return;
            }
            if (path.equals("/sync/settings")) {
                send(out, 200, Store.exportSettings().toString());
                return;
            }
            if (path.equals("/sync/state")) {
                if ("POST".equals(method)) {
                    JSONObject theirs = new JSONObject(text);
                    boolean changed = merge(theirs);
                    updatePeerAddress(theirs.optString("id"), c.getInetAddress().getHostAddress(), PORT);
                    if (changed) notifyMerged();
                    String time = new java.text.SimpleDateFormat("h:mm a", java.util.Locale.US).format(new java.util.Date());
                    lastResult = "Synced with " + theirs.optString("name", "another TV") + " at " + time + ".";
                    send(out, 200, "{\"ok\":true}");
                } else {
                    send(out, 200, export().toString());
                }
                return;
            }
            send(out, 404, "{}");
        } catch (Exception ignored) { }
    }

    /** Runs on this TV when another TV has just linked to it (to close the code screen). */
    public static Runnable onPaired;

    private static void send(OutputStream out, int code, String json) throws IOException {
        byte[] b = json.getBytes(StandardCharsets.UTF_8);
        String head = "HTTP/1.1 " + code + (code == 200 ? " OK" : " Error") + "\r\nContent-Type: application/json\r\nContent-Length: "
                + b.length + "\r\nConnection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.UTF_8));
        out.write(b);
        out.flush();
    }

    private static String line(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int ch;
        while ((ch = in.read()) != -1) {
            if (ch == '\n') break;
            if (ch != '\r') sb.append((char) ch);
            if (sb.length() > 8192) break;
        }
        return ch == -1 && sb.length() == 0 ? null : sb.toString();
    }
}
