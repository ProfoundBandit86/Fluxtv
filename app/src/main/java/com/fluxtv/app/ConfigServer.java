package com.fluxtv.app;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.HashMap;
import java.util.Map;

/**
 * The settings website: served on the local network while Settings (or one of its screens) is open,
 * so every setting can be changed from a phone or computer.
 */
public final class ConfigServer {

    public static final int PORT = 8090;
    private static ServerSocket server;
    private static Runnable onChange;

    private ConfigServer() { }

    public static synchronized void start(Runnable changed) {
        onChange = changed;
        if (server != null) return;
        try {
            server = new ServerSocket(PORT);
        } catch (IOException e) {
            server = null;
            return;
        }
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
        }, "config-server");
        t.setDaemon(true);
        t.start();
    }

    public static synchronized void stop() {
        onChange = null;
        try {
            if (server != null) server.close();
        } catch (IOException ignored) { }
        server = null;
    }

    public static synchronized boolean running() {
        return server != null;
    }

    /** e.g. http://192.168.1.20:8090, or null when not on a network. */
    public static String address() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) {
                        return "http://" + a.getHostAddress() + ":" + PORT;
                    }
                }
            }
        } catch (Exception ignored) { }
        return null;
    }

    // ------------------------------------------------------------ request handling

    private static void handle(Socket socket) {
        try (Socket c = socket) {
            c.setSoTimeout(15000);
            InputStream in = new BufferedInputStream(c.getInputStream());
            String requestLine = readLine(in);
            if (requestLine == null) return;
            String[] parts = requestLine.split(" ");
            if (parts.length < 2) return;
            String method = parts[0], path = parts[1];
            int length = 0;
            String line;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                if (line.toLowerCase().startsWith("content-length:")) {
                    try { length = Integer.parseInt(line.substring(15).trim()); } catch (NumberFormatException ignored) { }
                }
            }
            length = Math.min(length, 64 * 1024);
            byte[] body = new byte[length];
            int read = 0;
            while (read < length) {
                int n = in.read(body, read, length - read);
                if (n < 0) break;
                read += n;
            }
            Map<String, String> form = parseForm(new String(body, 0, read, StandardCharsets.UTF_8));

            String message = null;
            if ("POST".equals(method)) {
                try {
                    message = post(path, form);
                } catch (Exception e) {
                    message = "Error: " + App.message(e);
                }
                Runnable r = onChange;
                if (r != null) App.MAIN.post(r);
            }
            respond(c.getOutputStream(), page(message, "POST".equals(method) ? sectionFor(path) : null));
        } catch (Exception ignored) { }
    }

    private static String f(Map<String, String> form, String k) {
        String v = form.get(k);
        return v == null ? "" : v.trim();
    }

    /** Applies one form; returns the message to show. */
    private static String post(String path, Map<String, String> form) throws Exception {
        switch (path) {
            // ---- add-ons, servers, IPTV, Real-Debrid
            case "/install":
                return "Installed " + Addons.install(f(form, "url")).name();
            case "/remove":
                Addons.uninstall(f(form, "url"));
                return "Removed";
            case "/addon-move":
                Store.moveAddon(f(form, "url"), "up".equals(f(form, "dir")) ? -1 : 1);
                return "Add-on order saved";
            case "/rdkey":
                if (f(form, "key").isEmpty()) throw new Exception("Paste your API token");
                RealDebrid.setApiKey(f(form, "key"));
                return "Real-Debrid token saved";
            case "/rd-signout":
                RealDebrid.signOut();
                return "Signed out of Real-Debrid";
            case "/server-login": {
                MediaServer ms = Jellyfin.login(MediaServer.EMBY.equals(form.get("kind")) ? MediaServer.EMBY
                        : MediaServer.JELLYFIN, f(form, "url"), f(form, "user"), form.get("pass") == null ? "" : form.get("pass"));
                Store.saveServer(ms);
                return "Added " + ms.name;
            }
            case "/plex-manual": {
                MediaServer px = Plex.manual(f(form, "url"), f(form, "token"));
                Store.saveServer(px);
                return "Added " + px.name;
            }
            case "/server-remove": {
                String[] k = f(form, "key").split(":", 2);
                if (k.length == 2) Store.removeServer(k[0], k[1]);
                return "Server removed";
            }
            case "/iptv-xtream": {
                Iptv.Source xs = Iptv.loginXtream(f(form, "url"), f(form, "user"), form.get("pass") == null ? "" : form.get("pass"));
                Iptv.save(xs);
                return "Added " + xs.name;
            }
            case "/iptv-stalker": {
                Iptv.Source ps = Iptv.loginStalker(f(form, "url"), f(form, "mac"));
                Iptv.save(ps);
                App.IO.execute(Iptv::prefetchAll);
                return "Added " + ps.name;
            }
            case "/iptv-m3u": {
                Iptv.Source ms3 = Iptv.addM3u(f(form, "m3u"), f(form, "epg"));
                Iptv.save(ms3);
                return "Added " + ms3.name;
            }
            case "/iptv-epg": {
                Iptv.Source es = Iptv.source(f(form, "id"));
                if (es == null) throw new Exception("Provider not found");
                es.epg = f(form, "epg");
                Iptv.save(es);
                App.IO.execute(() -> { try { Iptv.loadGuide(es, true); } catch (Exception ignored) { } });
                return "Guide link saved; loading the guide in the background";
            }
            case "/dvr-guide":
                return Iptv.downloadAllGuides();
            case "/iptv-show-all":
                Iptv.showEverything();
                return "Showing every channel again";
            case "/iptv-regions": {
                List<String> codes = new ArrayList<>();
                for (String c : f(form, "regions").toUpperCase(java.util.Locale.ROOT).split("[,\\s]+")) if (!c.isEmpty()) codes.add(c);
                Iptv.setRegions(codes);
                Iptv.setHideUnknownRegion("hide".equals(f(form, "unknown")));
                return codes.isEmpty() ? "Showing channels from every country" : "Showing channels from " + android.text.TextUtils.join(", ", codes);
            }
            case "/epg-extra": {
                List<String> l = new ArrayList<>();
                for (String line : f(form, "urls").split("[\\r\\n]+")) {
                    String u = line.trim();
                    if (u.isEmpty()) continue;
                    if (!u.startsWith("http")) throw new Exception("Links must start with http: " + u);
                    if (!l.contains(u)) l.add(u);
                }
                Iptv.setExtraEpgUrls(l);
                if (form.containsKey("refresh")) Store.put("epg_refresh", f(form, "refresh"));
                App.IO.execute(Iptv::downloadAllGuides);
                return l.size() + " extra guide source" + (l.size() == 1 ? "" : "s") + " saved; downloading guides in the background";
            }
            case "/dvr-preview":
                Store.put("live_preview", f(form, "preview"));
                return "Saved";
            case "/dvr-target":
                if ("smb".equals(f(form, "target")) && !Smb.configured()) throw new Exception("Set up the network share below first");
                Dvr.setTarget(f(form, "target"));
                return "Recordings will be saved to: " + Dvr.targetLabel(App.ctx);
            case "/dvr-pad":
                Store.put("dvr_pad_before", f(form, "before"));
                Store.put("dvr_pad_after", f(form, "after"));
                return "Saved";
            case "/dvr-smb-login":
                Smb.save(f(form, "host"), f(form, "user"), form.get("pass") == null || form.get("pass").isEmpty() ? null : form.get("pass"), f(form, "domain"));
                smbBrowse = "";
                Smb.folders("");
                return "Connected to " + Smb.host() + ". Now choose a folder.";
            case "/dvr-smb-open":
                smbBrowse = f(form, "path");
                return null;
            case "/dvr-smb-mkdir": {
                String np = f(form, "path") + "/" + f(form, "name").replaceAll("[\\\\/:*?\"<>|]", "_");
                Smb.mkdir(np);
                smbBrowse = np;
                return "Folder created";
            }
            case "/dvr-smb-use": {
                String old = Smb.path();
                Smb.setPath(f(form, "path"));
                try { Smb.test(); } catch (Exception e) { Smb.setPath(old); throw new Exception("Flux can't save there: " + App.message(e)); }
                Dvr.setTarget("smb");
                smbBrowse = null;
                return "Recordings will be saved to " + Dvr.targetLabel(App.ctx);
            }
            case "/sh-connect":
                return "Connected: " + HomeAssistant.connect(f(form, "url"), f(form, "token"));
            case "/gc-add":
                GoogleCommands.add(f(form, "name"), f(form, "kind"));
                return "Added " + f(form, "name");
            case "/gc-remove":
                GoogleCommands.remove(f(form, "name"));
                return "Removed";
            case "/gc-test":
                GoogleCommands.send(f(form, "cmd").isEmpty() ? "what time is it" : f(form, "cmd"));
                return "Sent to the TV's Assistant: " + f(form, "cmd");
            case "/sh-disconnect":
                HomeAssistant.disconnect();
                return "Home Assistant disconnected";
            case "/sh-devices": {
                List<SmartHome.Device> all = SmartHome.all();
                List<String> ids = new ArrayList<>();
                for (SmartHome.Device d : all) if ("on".equals(form.get("d_" + d.id))) ids.add(d.id);
                Store.put("sh_shown", new org.json.JSONArray(ids).toString());
                return ids.size() + " devices on the panel";
            }
            case "/iptv-remove":
                Iptv.remove(f(form, "id"));
                return "IPTV provider removed";
            case "/iptv-vod":
                Iptv.setVodEnabled("on".equals(f(form, "vod")));
                Store.bumpSources();
                return "IPTV movies & series " + ("on".equals(f(form, "vod")) ? "shown" : "hidden");
            // ---- Radarr / Sonarr / SABnzbd
            case "/arr": {
                String which = f(form, "which");
                if (which.equals("sab")) return Sab.save(f(form, "url"), f(form, "key"));
                Arr arr = which.equals("sonarr") ? Arr.SONARR : Arr.RADARR;
                return arr.save(f(form, "url"), f(form, "key"));
            }
            case "/arr-fallback":
                Arr.setFallback("on".equals(f(form, "fallback")));
                return "Saved";
            case "/arr-remove": {
                String which = f(form, "which");
                if (which.equals("sab")) Sab.remove(); else (which.equals("sonarr") ? Arr.SONARR : Arr.RADARR).remove();
                return "Removed";
            }
            case "/arr-defaults": {
                Arr arr = f(form, "which").equals("sonarr") ? Arr.SONARR : Arr.RADARR;
                arr.setDefaults(f(form, "profile"), f(form, "root"));
                return arr.name + " defaults saved";
            }
            // ---- keys
            case "/keys":
                if (!f(form, "gemini").isEmpty()) Store.put("gemini_key", f(form, "gemini"));
                if (!f(form, "tmdb").isEmpty()) Store.put("tmdb_key", f(form, "tmdb"));
                if (!f(form, "mdblist").isEmpty()) Store.put("mdblist_key", f(form, "mdblist"));
                Store.bumpSources();
                return "Keys saved";
            case "/mdblist-add":
                return "Added " + MdbList.add(f(form, "url")) + " to Home";
            case "/gemini-speed":
                Gemini.setFast(!"smart".equals(f(form, "speed")));
                return "Gemini search speed saved";
            case "/key-remove":
                if (f(form, "key").equals("gemini_key") || f(form, "key").equals("tmdb_key") || f(form, "key").equals("mdblist_key")) Store.put(f(form, "key"), "");
                Store.bumpSources();
                return "Key removed";
            // ---- profiles & Trakt
            case "/profile-add":
                if (f(form, "name").isEmpty()) throw new Exception("Type a name");
                Store.addProfile(f(form, "name"));
                return "Added profile " + f(form, "name");
            case "/profile-limit": {
                String n = f(form, "name"), lim = f(form, "limit"), pin = f(form, "pin");
                if (!pin.isEmpty() && !pin.matches("\\d{4,8}")) throw new Exception("The PIN should be 4–8 digits");
                Ratings.setLimit(n, lim);
                if (lim.isEmpty()) Ratings.setPin(n, "");
                else if (!pin.isEmpty() || "1".equals(f(form, "clearpin"))) Ratings.setPin(n, pin);
                ProfileSync.syncNow(null);
                return lim.isEmpty() ? n + ": no age limit" : n + ": " + lim + " and under" + (Ratings.pin(n).isEmpty() ? "" : ", PIN set");
            }
            case "/sync-code":
                ProfileSync.newPairCode();
                return "Type this TV's code on the other TV (or in its settings page): " + ProfileSync.currentPairCode();
            case "/sync-link": {
                String tv = ProfileSync.link(f(form, "host"), f(form, "code"));
                return "Linked with " + tv + ". " + ProfileSync.status();
            }
            case "/sync-now":
                ProfileSync.syncForced(null);
                Thread.sleep(2500);
                return ProfileSync.status();
            case "/sync-unlink":
                ProfileSync.unlink(f(form, "id"));
                return "Unlinked";
            case "/sync-copy": {
                int n = ProfileSync.copySettingsFrom(f(form, "id"));
                return "Copied " + n + " settings to this TV";
            }
            case "/profile-use":
                Store.setProfile(f(form, "name"));
                return "Now watching as " + f(form, "name");
            case "/profile-remove":
                Store.removeProfile(f(form, "name"));
                return "Removed profile " + f(form, "name");
            case "/trakt-app":
                Trakt.setApp(f(form, "id"), f(form, "secret"));
                return Trakt.configured() ? "Trakt app saved. Now link a profile." : "Add the Client ID";
            case "/trakt-link": {
                Trakt.Login l = Trakt.startLogin(f(form, "name"));
                return "Go to " + l.url + " and enter " + l.userCode;
            }
            case "/trakt-cancel":
                Trakt.cancelLogin();
                return "Cancelled";
            case "/trakt-unlink":
                Trakt.signOut(f(form, "name"));
                return "Trakt unlinked for " + f(form, "name");
            case "/trakt-sync":
                return Trakt.sync();
            // ---- appearance & home
            case "/appearance":
                if (!f(form, "theme").isEmpty()) {
                    Theme.setTheme(Integer.parseInt(f(form, "theme")));
                    return "Theme saved";
                }
                if (!f(form, "accent").isEmpty()) Theme.setAccent(Integer.parseInt(f(form, "accent")));
                if (!f(form, "bg").isEmpty()) Theme.setBackground(Integer.parseInt(f(form, "bg")));
                if (!f(form, "highlight").isEmpty()) Theme.setHighlight(Integer.parseInt(f(form, "highlight")));
                return "Colours saved";
            case "/region":
                Store.put("region", f(form, "region"));
                Store.bumpSources();
                return "Region saved";
            case "/weather": {
                Weather.setPlace(f(form, "place"));
                if (!f(form, "unit").isEmpty()) Store.put("weather_unit", f(form, "unit"));
                if (Weather.place().isEmpty()) return "Weather turned off";
                try {
                    Weather.Now n = Weather.now();
                    return "Weather: " + Weather.describe(n) + " in " + n.place;
                } catch (Exception e) {
                    return "Couldn't find that place: " + App.message(e);
                }
            }
            case "/home-row":
                homeRow(f(form, "key"), f(form, "action"));
                return "Home rows saved";
            case "/cw":
                ContinueWatching.setHidden(f(form, "key"), "hide".equals(f(form, "action")));
                return "Continue watching saved";
            case "/dock":
                dock(f(form, "pkg"), f(form, "action"));
                return "Your apps saved";
            // ---- player
            case "/size-limits":
                for (String q : SizeLimits.QUALITIES) {
                    String v = f(form, "q_" + q);
                    try { SizeLimits.set(q, v.isEmpty() ? 0 : Double.parseDouble(v)); } catch (NumberFormatException ignored) { }
                }
                return "Size limits saved";
            case "/player":
                for (String k : new String[]{"player_audio_lang", "player_sub_lang", "player_subs", "player_sub_size",
                        "player_sub_style", "player_zoom", "player_skip", "player_next"}) {
                    if (form.containsKey(k)) Store.put(k, f(form, k));
                }
                return "Player settings saved";
            case "/test-channel":
                if (!BuildConfig.TEST_BUILD) return null;
                return WatchNext.insertTestChannel(App.ctx);
            case "/test-config":
                if (!BuildConfig.TEST_BUILD) return null;
                Store.put("test_tmdb", f(form, "tmdb"));
                Store.put("test_gemini", f(form, "gemini"));
                Store.put("test_trakt", f(form, "trakt"));
                if (!f(form, "introdb").isEmpty()) Store.put("test_introdb", f(form, "introdb"));
                if (!f(form, "sports").isEmpty()) Store.put("test_sports", f(form, "sports"));
                if (!f(form, "weather").isEmpty()) Store.put("test_weather", f(form, "weather"));
                if (!f(form, "mdblist").isEmpty()) Store.put("test_mdblist", f(form, "mdblist"));
                Store.put("gemini_model_t", "0");
                return "Test endpoints set";
            default:
                return null;
        }
    }

    private static void homeRow(String key, String action) {
        List<String> order = new ArrayList<>();
        for (JSONObject o : HomeLayout.savedAvailable()) order.add(o.optString("key"));
        java.util.Set<String> hidden = HomeLayout.hidden();
        int i = order.indexOf(key);
        if (i < 0) return;
        if (action.equals("toggle")) {
            if (!hidden.remove(key)) hidden.add(key);
        } else {
            int j = action.equals("up") ? i - 1 : i + 1;
            if (j >= 0 && j < order.size()) Collections.swap(order, i, j);
        }
        HomeLayout.save(order, hidden);
    }

    private static void dock(String pkg, String action) {
        List<String> d = Apps.dock(App.ctx);
        int i = d.indexOf(pkg);
        switch (action) {
            case "add": if (i < 0 && !pkg.isEmpty()) d.add(pkg); break;
            case "remove": if (i >= 0) d.remove(i); break;
            case "up": if (i > 0) Collections.swap(d, i, i - 1); break;
            case "down": if (i >= 0 && i < d.size() - 1) Collections.swap(d, i, i + 1); break;
            default: break;
        }
        Apps.saveDock(d);
        Store.bumpSources();
    }

    // ------------------------------------------------------------ the page

    private static final String[][] LANGS = {{"", "No preference"}, {"en", "English"}, {"es", "Spanish"}, {"fr", "French"},
            {"de", "German"}, {"it", "Italian"}, {"pt", "Portuguese"}, {"nl", "Dutch"}, {"sv", "Swedish"}, {"no", "Norwegian"},
            {"da", "Danish"}, {"fi", "Finnish"}, {"pl", "Polish"}, {"ru", "Russian"}, {"uk", "Ukrainian"}, {"tr", "Turkish"},
            {"ar", "Arabic"}, {"he", "Hebrew"}, {"hi", "Hindi"}, {"ja", "Japanese"}, {"ko", "Korean"}, {"zh", "Chinese"},
            {"th", "Thai"}, {"vi", "Vietnamese"}, {"id", "Indonesian"}, {"el", "Greek"}, {"cs", "Czech"}, {"hu", "Hungarian"},
            {"ro", "Romanian"}};

    private static String page(String message) {
        return page(message, null);
    }

    /** open = the section to show expanded (the one the last form belonged to). */
    private static String page(String message, String open) {
        openSection = open;
        StringBuilder sb = new StringBuilder();
        String accent = Theme.ACCENT_HEX[Theme.accentIndex()];
        sb.append("<!doctype html><html><head><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'>")
          .append("<title>Flux TV settings</title><style>")
          .append(":root{--a:").append(accent).append("}")
          .append("body{font-family:system-ui,sans-serif;background:#0b0b10;color:#eee;max-width:680px;margin:auto;padding:16px 16px 60px}")
          .append("h1{color:var(--a);margin:6px 0 2px}h3{margin:18px 0 6px}")
          .append("input,select{width:100%;padding:11px;font-size:16px;border-radius:9px;border:1px solid #333;background:#16161f;color:#fff;box-sizing:border-box;margin:4px 0}")
          .append("button{padding:9px 16px;font-size:15px;border:0;border-radius:9px;background:var(--a);color:#000;font-weight:600;margin:6px 6px 0 0}")
          .append("button.g{background:#2a2a36;color:#eee}button.rm{background:#a33;color:#fff}button.s{padding:6px 11px;font-size:14px}")
          .append("details{background:#111118;border-radius:14px;margin:12px 0;padding:4px 16px}")
          .append("summary{font-size:19px;font-weight:700;padding:14px 0;cursor:pointer;list-style:none;display:flex;flex-direction:column}")
          .append(".btn{display:inline-block;padding:9px 14px;border-radius:9px;background:#2a2a36;color:#eee;text-decoration:none;margin:6px 6px 0 0;font-size:14px}")
          .append("summary::-webkit-details-marker{display:none}.sub{font-size:13px;font-weight:400;color:#999;margin-top:2px}")
          .append("details[open] summary{border-bottom:1px solid #222;margin-bottom:8px}")
          .append("nav{position:sticky;top:0;background:#0b0b10;padding:10px 0;display:flex;gap:6px;overflow-x:auto;z-index:2}")
          .append("nav a{flex:none;padding:7px 12px;border-radius:99px;background:#1b1b26;color:#ddd;text-decoration:none;font-size:14px}")
          .append(".grp{font-size:13px;letter-spacing:.08em;text-transform:uppercase;color:#888;margin:22px 0 0}")
          .append(".a{background:#1b1b26;padding:11px 12px;border-radius:10px;margin:8px 0}.u{color:#999;font-size:13px;word-break:break-all}")
          .append(".m{background:#232336;border-left:4px solid var(--a);padding:12px;border-radius:8px;font-size:16px}")
          .append(".row{display:flex;align-items:center;gap:6px}.row b{flex:1}.off{opacity:.45}")
          .append(".code{font-size:40px;font-weight:800;color:var(--a);letter-spacing:4px}form{margin:0}")
          .append(".in{display:inline}</style></head><body>")
          .append("<h1>Flux TV</h1><div class='u'>").append(esc(Updater.currentName(App.ctx)))
          .append(" · settings apply to the TV right away</div>")
          .append("<nav><a href='#profiles'>Profiles</a><a href='#sync'>TV sync</a><a href='#home'>Home</a><a href='#appearance'>Look</a><a href='#player'>Player</a>")
          .append("<a href='#sources'>Sources</a><a href='#dvr'>DVR</a><a href='#smarthome'>Smart home</a><a href='#downloads'>Downloads</a><a href='#search'>Search</a><a href='#system'>System</a></nav>");
        if (message != null) sb.append("<p class='m'>").append(esc(message)).append("</p>");

        // ---------------- profiles & Trakt
        Trakt.Login login = Trakt.current();
        sb.append("" + section("profiles", "Profiles &amp; Trakt", "Who&#39;s watching, watchlist sync") + "");
        if (login != null && "waiting".equals(login.state)) {
            sb.append("<div class='a'>Linking Trakt for <b>").append(esc(login.profile)).append("</b>. Open <a style='color:var(--a)' href='")
              .append(esc(login.url)).append("' target='_blank'>").append(esc(login.url)).append("</a> and enter:<div class='code'>")
              .append(esc(login.userCode)).append("</div><div class='u'>Then reload this page.</div>")
              .append(form("/trakt-cancel", "", "Cancel", "g")).append("</div>");
        }
        String cur = Store.profile();
        for (String name : Store.profiles()) {
            boolean linked = Trakt.signedIn(name);
            sb.append("<div class='a'><div class='row'><b>").append(esc(name)).append(name.equals(cur) ? " · watching now" : "")
              .append("</b></div><div class='u'>Trakt: ").append(linked ? esc(Trakt.user(name).isEmpty() ? "linked" : Trakt.user(name)) : "not linked").append("</div>");
            String h = hidden("name", name);
            if (!name.equals(cur)) sb.append(form("/profile-use", h, "Switch to", "s"));
            if (linked) {
                if (name.equals(cur)) sb.append(form("/trakt-sync", h, "Sync now", "s"));
                sb.append(form("/trakt-unlink", h, "Unlink Trakt", "g s"));
            } else if (Trakt.configured()) {
                sb.append(form("/trakt-link", h, "Link Trakt", "s"));
            }
            if (Store.profiles().size() > 1) sb.append(form("/profile-remove", h, "Remove", "rm s"));
            String[] lv = new String[Ratings.LIMITS.length + 1], ll = new String[Ratings.LIMITS.length + 1];
            lv[0] = ""; ll[0] = "No age limit";
            for (int i = 0; i < Ratings.LIMITS.length; i++) { lv[i + 1] = Ratings.LIMITS[i]; ll[i + 1] = Ratings.LIMIT_NAMES[i] + " and under"; }
            sb.append("<form method='post' action='/profile-limit' class='row'>").append(h)
              .append(select("limit", lv, ll, Ratings.limit(name)))
              .append("<input name='pin' inputmode='numeric' placeholder='").append(Ratings.pin(name).isEmpty() ? "PIN (optional)" : "PIN set · type to change")
              .append("' autocomplete='off'><button>Save</button></form>");
            sb.append("</div>");
        }
        sb.append("<form method='post' action='/profile-add' class='row'><input name='name' placeholder='New profile name' autocomplete='off'>")
          .append("<button>Add</button></form>");
        sb.append("<h3>Trakt API app</h3><p class='u'>")
          .append(Trakt.configured() ? "Saved. Paste a new Client ID to switch apps." : "Create one (free) at <a style='color:var(--a)' href='https://developer.trakt.tv' target='_blank'>developer.trakt.tv</a> → My Apps. Any name; if it asks for a Redirect URI use <b>http://localhost</b>. Then paste its Client ID:")
          .append("</p><form method='post' action='/trakt-app'><input name='id' placeholder='Client ID' autocomplete='off'>")
          .append("<input name='secret' placeholder='Client Secret (only older apps have one; leave empty)' autocomplete='off'><button>Save Trakt app</button></form>")
          .append("<p class='u'>Synced per profile: Favorites ⇄ Trakt watchlist, resume points, and watch history (scrobbling).</p>");
        sb.append("<p class='u'>Age limit: titles above it, and titles without a rating, are hidden for that profile, and Live TV is off. "
                + "With a PIN, leaving the profile and opening Settings need the PIN.</p>");
        sb.append("</details>");

        // ---------------- TV to TV sync
        sb.append(section("sync", "Sync between TVs", "Share profiles and copy settings to your other Flux TVs"))
          .append("<p class='u'>Linked Flux TVs on your home network share profiles, age limits, Favorites and watch history. "
                + "No account or internet needed. This TV: <b>").append(esc(ProfileSync.deviceName())).append("</b>. ")
          .append(esc(ProfileSync.status())).append("</p>");
        for (org.json.JSONObject peer : ProfileSync.peers()) {
            String hid = hidden("id", peer.optString("id"));
            sb.append("<div class='a'><div class='row'><b>").append(esc(peer.optString("name"))).append("</b> <span class='u'>")
              .append(esc(peer.optString("host"))).append("</span></div>")
              .append(form("/sync-copy", hid, "Copy its settings to this TV", "s"))
              .append(form("/sync-unlink", hid, "Unlink", "rm s")).append("</div>");
        }
        if (!ProfileSync.peers().isEmpty()) sb.append(form("/sync-now", "", "Sync now", "s"));
        sb.append("<h3>Link another TV</h3>");
        String code = ProfileSync.currentPairCode();
        sb.append("<p class='u'>Option 1: show a code here and type it on the other TV (Settings → Profiles → Link another TV).</p>")
          .append(code == null ? form("/sync-code", "", "Show this TV's code", "s")
                : "<p style='font-size:34px;letter-spacing:4px;color:var(--a)'><b>" + code + "</b></p>");
        sb.append("<p class='u'>Option 2: link to the other TV using the code it shows.</p><form method='post' action='/sync-link'>");
        List<org.json.JSONObject> near = ProfileSync.nearby();
        if (near.isEmpty()) {
            sb.append("<input name='host' placeholder='Other TV address, e.g. 192.168.1.20' autocomplete='off'>");
        } else {
            String[] hv = new String[near.size()], hl = new String[near.size()];
            for (int i = 0; i < near.size(); i++) {
                hv[i] = near.get(i).optString("host") + ":" + near.get(i).optInt("port");
                hl[i] = near.get(i).optString("name") + " (" + near.get(i).optString("host") + ")";
            }
            sb.append(select("host", hv, hl, hv[0]));
        }
        sb.append("<input name='code' inputmode='numeric' placeholder='6-digit code from the other TV' autocomplete='off'><button>Link</button></form>")
          .append("<p class='u'>After linking, use <b>Copy its settings to this TV</b> so add-ons, Real-Debrid, servers, IPTV and API keys "
                + "don't have to be entered again. Profiles, Favorites and history sync by themselves.</p>");
        sb.append("</details>");

        // ---------------- home
        sb.append("" + section("home", "Home screen", "Rows, Continue watching, apps, region") + "");
        sb.append("<h3>Rows</h3><p class='u'>Continue watching, your apps and streaming services stay at the top. Rows below them, in order:</p>");
        List<JSONObject> rows = HomeLayout.savedAvailable();
        java.util.Set<String> hiddenRows = HomeLayout.hidden();
        if (rows.isEmpty()) sb.append("<p class='u'>Open Home on the TV once so Flux knows which rows are available.</p>");
        for (JSONObject r : rows) {
            String key = r.optString("key");
            boolean shown = !hiddenRows.contains(key);
            String h = hidden("key", key);
            sb.append("<div class='a row").append(shown ? "" : " off").append("'><b>").append(esc(r.optString("title")))
              .append(r.optString("note").isEmpty() ? "" : " <span class='u'>" + esc(r.optString("note")) + "</span>").append("</b>")
              .append(form("/home-row", h + hidden("action", "up"), "▲", "g s in"))
              .append(form("/home-row", h + hidden("action", "down"), "▼", "g s in"))
              .append(form("/home-row", h + hidden("action", "toggle"), shown ? "Hide" : "Show", (shown ? "g " : "") + "s in"))
              .append("</div>");
        }
        sb.append("<h3>Continue watching shows items from</h3>");
        java.util.Set<String> cwHidden = ContinueWatching.hidden();
        for (String[] src : ContinueWatching.sources()) {
            boolean on = !cwHidden.contains(src[0]);
            sb.append("<div class='a row").append(on ? "" : " off").append("'><b>").append(esc(src[1])).append("</b>")
              .append(form("/cw", hidden("key", src[0]) + hidden("action", on ? "hide" : "show"), on ? "Hide" : "Show", (on ? "g " : "") + "s in"))
              .append("</div>");
        }
        sb.append("<h3>Your apps</h3>");
        List<String> dock = Apps.dock(App.ctx);
        for (String pkg : dock) {
            String h = hidden("pkg", pkg);
            sb.append("<div class='a row'><b>").append(esc(Apps.label(App.ctx, pkg))).append("</b>")
              .append(form("/dock", h + hidden("action", "up"), "▲", "g s in"))
              .append(form("/dock", h + hidden("action", "down"), "▼", "g s in"))
              .append(form("/dock", h + hidden("action", "remove"), "Remove", "rm s in")).append("</div>");
        }
        sb.append("<form method='post' action='/dock' class='row'><input type='hidden' name='action' value='add'><select name='pkg'>");
        for (android.content.pm.ResolveInfo ri : Apps.installed(App.ctx)) {
            String pkg = ri.activityInfo.packageName;
            if (dock.contains(pkg) || pkg.equals(App.ctx.getPackageName())) continue;
            sb.append("<option value='").append(esc(pkg)).append("'>").append(esc(Apps.label(App.ctx, pkg))).append("</option>");
        }
        sb.append("</select><button>Add</button></form>");
        sb.append("<h3>Region for streaming services</h3><form method='post' action='/region' class='row'><select name='region'>");
        for (String rg : new String[]{"US", "CA", "GB", "AU", "IE", "DE", "FR", "NL", "ES", "IT", "SE", "NO", "DK", "BR", "MX", "IN", "JP"}) {
            sb.append("<option").append(rg.equals(Tmdb.region()) ? " selected" : "").append(">").append(rg).append("</option>");
        }
        sb.append("</select><button>Save</button></form>");
        sb.append("<h3>Weather next to the clock</h3><form method='post' action='/weather' class='row'>")
          .append("<input name='place' placeholder='ZIP code or city' value='").append(esc(Weather.place())).append("'>")
          .append("<select name='unit'><option value='f'").append(Weather.fahrenheit() ? " selected" : "").append(">°F</option>")
          .append("<option value='c'").append(Weather.fahrenheit() ? "" : " selected").append(">°C</option></select>")
          .append("<button>Save</button></form></details>");

        // ---------------- appearance
        String[] themeNames = new String[Theme.THEMES.length];
        for (int i = 0; i < themeNames.length; i++) themeNames[i] = (String) Theme.THEMES[i][0];
        sb.append("" + section("appearance", "Appearance", "Themes and colours") + "<form method='post' action='/appearance'>Theme")
          .append(select("theme", themeNames, Theme.themeIndex())).append("<button>Use theme</button></form>")
          .append("<h3>Or mix your own</h3><form method='post' action='/appearance'>Accent colour")
          .append(select("accent", Theme.ACCENT_NAMES, Theme.accentIndex())).append("Background")
          .append(select("bg", Theme.BG_NAMES, Theme.bgIndex())).append("Highlight colour (what the remote has selected)")
          .append(select("highlight", highlightNames(), Theme.highlightIndex())).append("<button>Save colours</button></form></details>");

        // ---------------- player
        sb.append("" + section("player", "Player", "Subtitles, picture, size limits") + "<form method='post' action='/player'>")
          .append("Preferred audio language").append(langSelect("player_audio_lang"))
          .append("Preferred subtitle language").append(langSelect("player_sub_lang"))
          .append("Subtitles").append(select("player_subs", new String[]{"", "off", "on"},
                  new String[]{"Only when the video says so (forced/default)", "Always off", "Always on"}, Store.get("player_subs")))
          .append("Subtitle size").append(select("player_sub_size", PlayerActivity.SUB_SIZES, PlayerActivity.intPref("player_sub_size", 1)))
          .append("Subtitle style").append(select("player_sub_style", PlayerActivity.SUB_STYLES, PlayerActivity.intPref("player_sub_style", 0)))
          .append("Picture size").append(select("player_zoom", PlayerActivity.ZOOMS, PlayerActivity.intPref("player_zoom", 0)))
          .append("Skip intro &amp; credits").append(select("player_skip", PlayerActivity.SKIP_MODES, PlayerActivity.intPref("player_skip", 0)))
          .append("Play next episode").append(select("player_next", PlayerActivity.NEXT_MODES, PlayerActivity.intPref("player_next", 1)))
          .append("<button>Save player settings</button></form>")
          .append(sizeLimitsForm())
          .append("<p class='u'>While watching: press OK or ↑ to show the controls; Audio, Subtitles, Quality, Speed and Picture are in the bar. "
                  + "Subtitles from add-ons need a subtitles add-on such as OpenSubtitles v3.</p></details>");

        sb.append("<h2 class='grp' id='sources'>Sources</h2>");
        // ---------------- add-ons
        sb.append("" + section("addons", "Add-ons", "Catalogs and streams") + "<p class='u'>To stream through Real-Debrid inside "
                + "Torrentio (like Stremio and WuPlay): press Configure on Torrentio below, add your Real-Debrid key, press Install, "
                + "then copy the install link and paste it here. Flux swaps it in for the old Torrentio. Streams then show "
                + "<b>Real-Debrid ✓ ready</b> or <b>not ready</b> in the stream list.</p><form method='post' action='/install'>")
          .append("<input name='url' placeholder='https://…/manifest.json' autocomplete='off'><button>Install</button></form>");
        List<String> urls = Store.addonUrls();
        for (String url : urls) {
            Addon a = Addons.load(url);
            String h = hidden("url", url);
            String conf = a == null ? null : Addons.configureUrl(a);
            sb.append("<div class='a'><b>").append(esc(a != null ? a.name() : url)).append("</b><div class='u'>").append(esc(url)).append("</div>")
              .append(conf == null ? "" : "<a class='btn' href='" + esc(conf) + "' target='_blank'>Configure</a> ")
              .append(form("/addon-move", h + hidden("dir", "up"), "▲", "g s in"))
              .append(form("/addon-move", h + hidden("dir", "down"), "▼", "g s in"))
              .append(form("/remove", h, "Remove", "rm s in")).append("</div>");
        }
        sb.append("</details>");

        // ---------------- servers
        sb.append("" + section("servers", "Media servers", "Plex, Jellyfin, Emby") + "");
        for (MediaServer ms : Store.servers()) {
            sb.append("<div class='a'><b>").append(esc(ms.name)).append("</b> (").append(ms.kindLabel())
              .append(")<div class='u'>").append(esc(ms.url)).append("</div>")
              .append(form("/server-remove", hidden("key", ms.kind + ":" + ms.id), "Remove", "rm s")).append("</div>");
        }
        sb.append("<h3>Add Jellyfin or Emby</h3><form method='post' action='/server-login'>")
          .append("<select name='kind'><option value='jellyfin'>Jellyfin</option><option value='emby'>Emby</option></select>")
          .append("<input name='url' placeholder='http://192.168.1.10:8096' autocomplete='off'>")
          .append("<input name='user' placeholder='Username' autocomplete='off'>")
          .append("<input name='pass' type='password' placeholder='Password'><button>Sign in</button></form>")
          .append("<h3>Add Plex by address + token</h3><p class='u'>Easier: Settings → Media servers → Plex (sign in with code) on the TV.</p>")
          .append("<form method='post' action='/plex-manual'><input name='url' placeholder='http://192.168.1.10:32400' autocomplete='off'>")
          .append("<input name='token' placeholder='X-Plex-Token' autocomplete='off'><button>Add Plex server</button></form></details>");

        // ---------------- RD
        sb.append("" + section("rd", "Real-Debrid", "Account") + "<p>").append(RealDebrid.isSignedIn() ? "Signed in." : "Not signed in.")
          .append(" Get your token at <a style='color:var(--a)' href='https://real-debrid.com/apitoken'>real-debrid.com/apitoken</a>.</p>")
          .append("<form method='post' action='/rdkey'><input name='key' placeholder='API token' autocomplete='off'><button>Save token</button></form>");
        if (RealDebrid.isSignedIn()) sb.append(form("/rd-signout", "", "Sign out", "rm"));
        sb.append("</details>");

        // ---------------- IPTV
        sb.append("" + section("iptv", "Live TV &amp; IPTV", "Xtream Codes, M3U, MAC portals, Dispatcharr") + "");
        for (Iptv.Source is : Iptv.sources()) {
            sb.append("<div class='a'><b>").append(esc(is.name)).append("</b><div class='u'>")
              .append(esc(is.kindLabel())).append("</div>")
              .append("<form method='post' action='/iptv-epg' class='row'>").append(hidden("id", is.id))
              .append("<input name='epg' value='").append(esc(is.epg)).append("' placeholder='")
              .append(is.xtream() ? "Guide (XMLTV) link · empty = the provider's own guide" : "Guide (XMLTV) link").append("' autocomplete='off'><button>Save guide link</button></form>")
              .append(form("/iptv-remove", hidden("id", is.id), "Remove", "rm s")).append("</div>");
        }
        sb.append("<form method='post' action='/iptv-vod' class='row'><b>Provider movies &amp; series in Movies / TV Shows</b>")
          .append("<select name='vod' style='width:auto'><option value='on'").append(Iptv.vodEnabled() ? " selected" : "").append(">Show</option>")
          .append("<option value='off'").append(Iptv.vodEnabled() ? "" : " selected").append(">Hide</option></select><button>Save</button></form>");
        sb.append("<h3>Xtream Codes login</h3><p class='u'>Dispatcharr: http://&lt;its IP&gt;:9191, a Dispatcharr username and that user's XC password.</p>")
          .append("<form method='post' action='/iptv-xtream'><input name='url' placeholder='http://example.com:8080' autocomplete='off'>")
          .append("<input name='user' placeholder='Username' autocomplete='off'>")
          .append("<input name='pass' type='password' placeholder='Password'><button>Sign in</button></form>")
          .append("<h3>M3U playlist</h3><form method='post' action='/iptv-m3u'>")
          .append("<input name='m3u' placeholder='Playlist link (.m3u / get.php)' autocomplete='off'>")
          .append("<input name='epg' placeholder='Guide link (XMLTV), optional' autocomplete='off'>")
          .append("<button>Add playlist</button></form>")
          .append("<h3>MAC portal (Stalker / Ministra)</h3><form method='post' action='/iptv-stalker'>")
          .append("<input name='url' placeholder='Portal address, e.g. http://example.com:8080/c/' autocomplete='off'>")
          .append("<input name='mac' placeholder='MAC address, e.g. 00:1A:79:12:34:56' autocomplete='off'>")
          .append("<button>Sign in</button></form></details>");

        // ---------------- Smart home
        sb.append(section("smarthome", "Smart home", "Home Assistant · lights panel over any app"));
        if (HomeAssistant.configured()) {
            sb.append("<p class='u'>Connected to ").append(esc(HomeAssistant.url())).append("</p>")
              .append(form("/sh-disconnect", "", "Disconnect", "rm s"));
            try {
                List<SmartHome.Device> all = SmartHome.all();
                sb.append("<h3>Devices on the panel</h3><form method='post' action='/sh-devices'>");
                String room = null;
                for (SmartHome.Device d : all) {
                    String r = d.room.isEmpty() ? "Other" : d.room;
                    if (!r.equals(room)) { room = r; sb.append("<p class='u' style='margin:12px 0 4px'><b>").append(esc(r)).append("</b></p>"); }
                    sb.append("<label style='display:block;padding:6px 0'><input type='checkbox' style='width:auto;margin-right:10px' name='d_")
                      .append(esc(d.id)).append("' value='on'").append(SmartHome.shown(d) ? " checked" : "").append(">")
                      .append(esc(d.name)).append(" <span class='u'>· ").append(esc(SmartHome.describe(d))).append("</span></label>");
                }
                sb.append("<button>Save</button></form>");
            } catch (Exception e) {
                sb.append("<p class='u' style='color:#f88'>Couldn't load devices: ").append(esc(App.message(e))).append("</p>");
            }
        } else {
            sb.append("<p class='u'>In Home Assistant: your profile → Security → Long-lived access tokens → Create token, then paste it here.</p>")
              .append("<form method='post' action='/sh-connect'><input name='url' value='").append(esc(Store.get("ha_url")))
              .append("' placeholder='http://homeassistant.local:8123' autocomplete='off'>")
              .append("<input name='token' placeholder='Long-lived access token' autocomplete='off'><button>Connect</button></form>");
        }
        sb.append("<h3>Google Home (through the TV's Google Assistant)</h3><p class='u'>Names exactly as in the Google Home app.</p>");
        for (org.json.JSONObject o : GoogleCommands.saved()) {
            sb.append("<div class='row'><b>").append(esc(o.optString("name"))).append("</b> <span class='u'>").append(esc(o.optString("kind")))
              .append("</span>").append(form("/gc-remove", hidden("name", o.optString("name")), "Remove", "rm s")).append("</div>");
        }
        String[] gk = GoogleCommands.KINDS, gn = GoogleCommands.KIND_NAMES;
        sb.append("<form method='post' action='/gc-add' class='row'><input name='name' placeholder='Device name in Google Home' autocomplete='off'>")
          .append(select("kind", gk, gn, "dimmable")).append("<button>Add</button></form>")
          .append("<form method='post' action='/gc-test' class='row'><input name='cmd' placeholder='Test a command, e.g. turn off kitchen light' autocomplete='off'><button>Send to the TV</button></form>")
          .append("<p class='u'>Open the panel from Quick Settings (hold Home), the sidebar, "
                + "or the \"Flux Smart Home\" app (map it to a remote button with Button Mapper).</p></details>");

        // ---------------- Live TV & DVR
        sb.append(section("dvr", "Live TV guide &amp; DVR", "Guide, preview, where recordings are saved"));
        StringBuilder ga = new StringBuilder();
        for (Iptv.Source is : Iptv.sources()) {
            long age = Iptv.guideAge(is);
            ga.append(esc(is.name)).append(": ").append(age == Long.MAX_VALUE ? "guide not loaded yet" : "guide updated " + (age / 60000 < 60 ? age / 60000 + " min ago" : age / 3600000 + " h ago")).append("<br>");
        }
        sb.append("<p class='u'>").append(ga.length() == 0 ? "Add an IPTV provider first." : ga.toString()).append("</p>")
          .append(form("/dvr-guide", "", "Download guides now", "s"));
        if (!Store.get("epg_last").isEmpty()) sb.append("<p class='u'>Last download: ").append(esc(Store.get("epg_last"))).append("</p>");
        StringBuilder eu = new StringBuilder(), est = new StringBuilder();
        for (String u : Iptv.extraEpgUrls()) {
            eu.append(esc(u)).append("\n");
            String st = Store.get("epg_status:" + u);
            est.append(esc(u)).append(": ").append(esc(st.isEmpty() ? "not downloaded yet" : st)).append("<br>");
        }
        sb.append("<form method='post' action='/epg-extra'><b>Extra guide sources</b><p class='u'>XMLTV links (.xml or .xml.gz), one per line. "
                + "They fill in channels your provider's guide misses, matched by guide id, then by channel name.</p>")
          .append("<textarea name='urls' rows='3' style='width:100%' placeholder='https://…/guide.xml.gz'>").append(eu).append("</textarea>")
          .append("Download every").append(select("refresh", new String[]{"3 hours", "6 hours", "12 hours", "Day"}, PlayerActivity.intPref("epg_refresh", 0)))
          .append("<button>Save guide sources</button></form>")
          .append(est.length() == 0 ? "" : "<p class='u'>" + est + "</p>");
        sb.append("<form method='post' action='/iptv-regions'><b>Only show channels from these countries</b><p class='u'>Country codes "
                + "as your provider writes them at the start of group or channel names, e.g. US, UK, CA (empty = every country). "
                + "Hide single groups and channels on the TV: Settings → Live TV → Channels.</p>")
          .append("<input name='regions' placeholder='US, UK' value='").append(esc(android.text.TextUtils.join(", ", Iptv.regions()))).append("'>")
          .append("Channels with no country in their name").append("<select name='unknown'><option value='show'>Show them</option><option value='hide'")
          .append(Iptv.hideUnknownRegion() ? " selected" : "").append(">Hide them</option></select>")
          .append("<button>Save countries</button></form>");
        sb.append("<form method='post' action='/dvr-preview' class='row'><b>Preview while browsing the guide</b>")
          .append(select("preview", new String[]{"on", "off"}, new String[]{"On", "Off (one-connection providers)"}, "off".equals(Store.get("live_preview")) ? "off" : "on"))
          .append("<button>Save</button></form>");
        List<String[]> lt = Dvr.localTargets(App.ctx);
        String[] tv = new String[lt.size() + 1], tl = new String[lt.size() + 1];
        for (int i = 0; i < lt.size(); i++) { tv[i] = lt.get(i)[0]; tl[i] = lt.get(i)[1]; }
        tv[lt.size()] = "smb";
        tl[lt.size()] = Smb.configured() ? "Network share \\\\" + Smb.host() + "\\" + Smb.path().replace('/', '\\') : "Network share (set up below)";
        sb.append("<h3>Save recordings to</h3><form method='post' action='/dvr-target' class='row'>")
          .append(select("target", tv, tl, Dvr.target())).append("<button>Save</button></form>");
        sb.append("<form method='post' action='/dvr-pad' class='row'>Start")
          .append(select("before", new String[]{"0", "1", "2", "5"}, new String[]{"on time", "1 min early", "2 min early", "5 min early"}, String.valueOf(Dvr.padBeforeMin())))
          .append("end").append(select("after", new String[]{"0", "3", "5", "10", "15"}, new String[]{"on time", "3 min late", "5 min late", "10 min late", "15 min late"}, String.valueOf(Dvr.padAfterMin())))
          .append("<button>Save</button></form>");
        sb.append("<h3>Network share (SMB)</h3><p class='u'>A Windows PC or NAS folder. Sign in, then pick the folder below.</p>")
          .append("<form method='post' action='/dvr-smb-login'>")
          .append("<input name='host' value='").append(esc(Smb.host())).append("' placeholder='Computer or NAS, e.g. 192.168.1.10' autocomplete='off'>")
          .append("<input name='user' value='").append(esc(Store.get("dvr_smb_user"))).append("' placeholder='User name (empty for guest)' autocomplete='off'>")
          .append("<input name='pass' type='password' placeholder='").append(Store.get("dvr_smb_pass").isEmpty() ? "Password" : "Password (saved · type to change)").append("'>")
          .append("<input name='domain' value='").append(esc(Store.get("dvr_smb_domain"))).append("' placeholder='Domain / workgroup (usually empty)' autocomplete='off'>")
          .append("<button>Connect</button></form>");
        if (!Smb.host().isEmpty()) {
            String at = smbBrowse == null ? Smb.path() : smbBrowse;
            sb.append("<div class='a'><b>\\\\").append(esc(Smb.host())).append(at.isEmpty() ? "" : "\\" + esc(at.replace('/', '\\'))).append("</b>");
            try {
                List<String> fs = Smb.folders(at);
                if (!at.isEmpty()) {
                    sb.append("<div class='row'>").append(form("/dvr-smb-use", hidden("path", at), "Use this folder", "s"));
                    int cut = at.lastIndexOf('/');
                    sb.append(form("/dvr-smb-open", hidden("path", cut < 0 ? "" : at.substring(0, cut)), "↑ Up", "g s")).append("</div>");
                    sb.append("<form method='post' action='/dvr-smb-mkdir' class='row'>").append(hidden("path", at))
                      .append("<input name='name' placeholder='New folder name' autocomplete='off'><button>Create</button></form>");
                }
                if (fs.isEmpty()) sb.append("<p class='u'>").append(at.isEmpty() ? "No shared folders found." : "No folders inside.").append("</p>");
                for (String f : fs) sb.append(form("/dvr-smb-open", hidden("path", at.isEmpty() ? f : at + "/" + f), "📁 " + f, "g s"));
            } catch (Exception e) {
                sb.append("<p class='u' style='color:#f88'>Couldn't open it: ").append(esc(App.message(e))).append("</p>");
            }
            sb.append("</div>");
        }
        sb.append("</details>");

        // ---------------- downloads
        sb.append("" + section("downloads", "Downloads", "Radarr, Sonarr, SABnzbd") + "<p class='u'>API keys: in each app under Settings → General. "
                + "Once connected, title pages get an Add to Radarr/Sonarr button and the sidebar gets Downloads.</p>");
        arrForm(sb, "radarr", "Radarr", "http://192.168.1.10:7878", Arr.RADARR.configured(), Arr.RADARR);
        arrForm(sb, "sonarr", "Sonarr", "http://192.168.1.10:8989", Arr.SONARR.configured(), Arr.SONARR);
        arrForm(sb, "sab", "SABnzbd", "http://192.168.1.10:8080", Sab.configured(), null);
        sb.append("<h3>Fallback</h3><form method='post' action='/arr-fallback'>When nothing can play from your servers or Real-Debrid")
          .append(select("fallback", new String[]{"on", "off"}, new String[]{"Send it to Radarr / Sonarr automatically",
                  "Only when I press \"Get with Radarr/Sonarr\""}, Arr.fallbackOn() ? "on" : "off"))
          .append("<button>Save</button></form>");
        sb.append("</details>");

        // ---------------- keys
        sb.append("" + section("search", "Search", "Gemini and TMDB keys") + "")
          .append("<p class='u'>Gemini (smart voice search): ").append(Store.get("gemini_key").isEmpty() ? "not set" : "saved")
          .append(" · TMDB (streaming services, cast &amp; crew): ").append(Store.get("tmdb_key").isEmpty() ? "not set" : "saved").append("</p>")
          .append("<form method='post' action='/keys'><input name='gemini' placeholder='Gemini API key (aistudio.google.com)' autocomplete='off'>")
          .append("<input name='tmdb' placeholder='TMDB API key or read token' autocomplete='off'><button>Save keys</button></form>");
        sb.append("<form method='post' action='/gemini-speed' class='row'><b>Gemini search</b>")
          .append(select("speed", new String[]{"fast", "smart"}, new String[]{"Fast (Flash-Lite)", "Smarter (Flash)"}, Gemini.fast() ? "fast" : "smart"))
          .append("<button>Save</button></form>");
        if (!Store.get("gemini_key").isEmpty()) sb.append(form("/key-remove", hidden("key", "gemini_key"), "Remove Gemini key", "g s"));
        if (!Store.get("tmdb_key").isEmpty()) sb.append(form("/key-remove", hidden("key", "tmdb_key"), "Remove TMDB key", "g s"));
        sb.append("</details>");
        // ---------------- system
        sb.append(section("system", "System", "Version, Home button")).append("<p>Flux TV ").append(esc(Updater.currentName(App.ctx)))
          .append("</p><p class='u'>Home button: ").append(esc(HomeButtonService.status(App.ctx)))
          .append(" To change it, open Settings → Home screen on the TV.</p>");
        if (!HomeButtonService.running(App.ctx) && !HomeButtonService.isDefaultHome(App.ctx)) {
            sb.append("<p class='u'>If the TV's Accessibility switch for Flux turns itself off: first press <b>Unlock the Home button</b> in "
                    + "Flux's Settings → Home screen. If it still turns off, from a computer with ADB connected to the TV run:<br><code>"
                    + "adb shell appops set com.fluxtv.app ACCESS_RESTRICTED_SETTINGS allow</code><br>then turn the switch on again. Or switch "
                    + "it on directly:<br><code>adb shell settings put secure enabled_accessibility_services "
                    + "com.fluxtv.app/com.fluxtv.app.HomeButtonService</code><br><code>adb shell settings put secure accessibility_enabled 1</code></p>");
        }
        sb.append("</details>");
        sb.append("</body></html>");
        return sb.toString();
    }

    private static void arrForm(StringBuilder sb, String which, String name, String example, boolean on, Arr arr) {
        sb.append("<h3>").append(name).append("</h3><p class='u'>").append(on ? "Connected to " + esc(Store.get(which + "_url")) : "Not set up")
          .append("</p><form method='post' action='/arr'>").append(hidden("which", which))
          .append("<input name='url' placeholder='").append(on ? esc(Store.get(which + "_url")) : example).append("' autocomplete='off'>")
          .append("<input name='key' placeholder='").append(on ? "API key saved (paste to replace)" : "API key").append("' autocomplete='off'>")
          .append("<button>Save &amp; test</button></form>");
        if (!on) return;
        sb.append(form("/arr-remove", hidden("which", which), "Remove", "rm s"));
        if (arr == null) return;
        try {
            List<String[]> profiles = arr.profiles();
            List<String> roots = arr.rootFolders();
            String[] pv = new String[profiles.size()], pl = new String[profiles.size()];
            for (int i = 0; i < pv.length; i++) { pv[i] = profiles.get(i)[0]; pl[i] = profiles.get(i)[1]; }
            String[] rv = roots.toArray(new String[0]);
            sb.append("<form method='post' action='/arr-defaults'>").append(hidden("which", which))
              .append("Quality for new ").append(arr == Arr.RADARR ? "movies" : "shows").append(select("profile", pv, pl, arr.profile()))
              .append("Save to").append(select("root", rv, rv, arr.root())).append("<button>Save defaults</button></form>");
        } catch (Exception e) {
            sb.append("<p class='u'>Couldn't read profiles: ").append(esc(App.message(e))).append("</p>");
        }
    }

    private static String sizeLimitsForm() {
        StringBuilder sb = new StringBuilder("<h3>Stream size limits</h3><p class='u'>Streams bigger than this are hidden "
                + "and never auto-played (your own library is never limited).</p><form method='post' action='/size-limits'>");
        for (int q = 0; q < SizeLimits.QUALITIES.length; q++) {
            String quality = SizeLimits.QUALITIES[q];
            double[] ch = SizeLimits.CHOICES[q];
            String[] v = new String[ch.length], l = new String[ch.length];
            String cur = "";
            for (int i = 0; i < ch.length; i++) {
                v[i] = ch[i] <= 0 ? "" : String.valueOf(ch[i]);
                l[i] = SizeLimits.label(ch[i]);
                if (Math.abs(SizeLimits.max(quality) - ch[i]) < 0.01) cur = v[i];
            }
            sb.append(quality).append(select("q_" + quality, v, l, cur));
        }
        return sb.append("<button>Save size limits</button></form>").toString();
    }

    private static String openSection;
    /** Folder being browsed on the network share in the web page (null = the chosen one). */
    private static String smbBrowse;

    /** A collapsible card with an anchor, a title and a grey one-line summary. */
    private static String section(String id, String title, String sub) {
        return "<details id='" + id + "'" + (id.equals(openSection) ? " open" : "") + "><summary>" + title
                + "<span class='sub'>" + sub + "</span></summary>";
    }

    /** Which section a form belongs to, so the page reopens there after saving. */
    private static String sectionFor(String path) {
        if (path.startsWith("/profile") || path.startsWith("/trakt")) return "profiles";
        if (path.startsWith("/sync")) return "sync";
        if (path.equals("/appearance")) return "appearance";
        if (path.equals("/home-row") || path.equals("/cw") || path.equals("/dock") || path.equals("/region") || path.equals("/weather")) return "home";
        if (path.equals("/player") || path.equals("/size-limits")) return "player";
        if (path.startsWith("/arr")) return "downloads";
        if (path.equals("/install") || path.equals("/remove") || path.equals("/addon-move")) return "addons";
        if (path.startsWith("/server") || path.startsWith("/plex")) return "servers";
        if (path.startsWith("/iptv")) return "iptv";
        if (path.startsWith("/dvr")) return "dvr";
        if (path.startsWith("/sh-") || path.startsWith("/gc-")) return "smarthome";
        if (path.startsWith("/rd")) return "rd";
        if (path.equals("/keys") || path.equals("/key-remove") || path.equals("/gemini-speed")) return "search";
        return null;
    }

    private static String hidden(String name, String value) {
        return "<input type='hidden' name='" + name + "' value='" + esc(value) + "'>";
    }

    private static String form(String action, String hiddenFields, String label, String cls) {
        return "<form method='post' action='" + action + "' class='in'>" + hiddenFields
                + "<button class='" + cls + "'>" + esc(label) + "</button></form>";
    }

    private static String select(String name, String[] labels, int selected) {
        String[] values = new String[labels.length];
        for (int i = 0; i < labels.length; i++) values[i] = String.valueOf(i);
        return select(name, values, labels, String.valueOf(selected));
    }

    private static String select(String name, String[] values, String[] labels, String selected) {
        StringBuilder sb = new StringBuilder("<select name='" + name + "'>");
        for (int i = 0; i < values.length; i++) {
            sb.append("<option value='").append(esc(values[i])).append("'").append(values[i].equals(selected) ? " selected" : "")
              .append(">").append(esc(labels[i])).append("</option>");
        }
        return sb.append("</select>").toString();
    }

    private static String langSelect(String key) {
        String[] v = new String[LANGS.length], l = new String[LANGS.length];
        for (int i = 0; i < LANGS.length; i++) { v[i] = LANGS[i][0]; l[i] = LANGS[i][1]; }
        return select(key, v, l, Store.get(key));
    }

    private static void respond(OutputStream out, String html) throws IOException {
        byte[] body = html.getBytes(StandardCharsets.UTF_8);
        String head = "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: " + body.length
                + "\r\nConnection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.UTF_8));
        out.write(body);
        out.flush();
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int ch;
        while ((ch = in.read()) != -1) {
            if (ch == '\n') break;
            if (ch != '\r') b.write(ch);
            if (b.size() > 8192) break;
        }
        if (ch == -1 && b.size() == 0) return null;
        return new String(b.toByteArray(), StandardCharsets.UTF_8);
    }

    private static Map<String, String> parseForm(String s) {
        Map<String, String> m = new HashMap<>();
        for (String pair : s.split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) continue;
            try {
                m.put(URLDecoder.decode(pair.substring(0, eq), "UTF-8"), URLDecoder.decode(pair.substring(eq + 1), "UTF-8"));
            } catch (Exception ignored) { }
        }
        return m;
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("'", "&#39;").replace("\"", "&quot;");
    }

    private static String[] highlightNames() {
        String[] out = new String[Theme.ACCENT_NAMES.length + 2];
        out[0] = "White";
        out[1] = "Match theme";
        System.arraycopy(Theme.ACCENT_NAMES, 0, out, 2, Theme.ACCENT_NAMES.length);
        return out;
    }
}
