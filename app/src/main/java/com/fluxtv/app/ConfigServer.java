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
            respond(c.getOutputStream(), page(message));
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
            case "/iptv-m3u": {
                Iptv.Source ms3 = Iptv.addM3u(f(form, "m3u"), f(form, "epg"));
                Iptv.save(ms3);
                return "Added " + ms3.name;
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
                Store.bumpSources();
                return "Keys saved";
            case "/gemini-speed":
                Gemini.setFast(!"smart".equals(f(form, "speed")));
                return "Gemini search speed saved";
            case "/key-remove":
                if (f(form, "key").equals("gemini_key") || f(form, "key").equals("tmdb_key")) Store.put(f(form, "key"), "");
                Store.bumpSources();
                return "Key removed";
            // ---- profiles & Trakt
            case "/profile-add":
                if (f(form, "name").isEmpty()) throw new Exception("Type a name");
                Store.addProfile(f(form, "name"));
                return "Added profile " + f(form, "name");
            case "/profile-use":
                Store.setProfile(f(form, "name"));
                return "Now watching as " + f(form, "name");
            case "/profile-remove":
                Store.removeProfile(f(form, "name"));
                return "Removed profile " + f(form, "name");
            case "/trakt-app":
                Trakt.setApp(f(form, "id"), f(form, "secret"));
                return Trakt.configured() ? "Trakt app saved. Now link a profile." : "Add both the Client ID and Secret";
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
                if (!f(form, "accent").isEmpty()) Theme.setAccent(Integer.parseInt(f(form, "accent")));
                if (!f(form, "bg").isEmpty()) Theme.setBackground(Integer.parseInt(f(form, "bg")));
                return "Colours saved";
            case "/region":
                Store.put("region", f(form, "region"));
                Store.bumpSources();
                return "Region saved";
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
            case "/player":
                for (String k : new String[]{"player_audio_lang", "player_sub_lang", "player_subs", "player_sub_size",
                        "player_sub_style", "player_zoom"}) {
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
          .append("summary{font-size:19px;font-weight:700;padding:12px 0;cursor:pointer}")
          .append(".a{background:#1b1b26;padding:11px 12px;border-radius:10px;margin:8px 0}.u{color:#999;font-size:13px;word-break:break-all}")
          .append(".m{background:#232336;border-left:4px solid var(--a);padding:12px;border-radius:8px;font-size:16px}")
          .append(".row{display:flex;align-items:center;gap:6px}.row b{flex:1}.off{opacity:.45}")
          .append(".code{font-size:40px;font-weight:800;color:var(--a);letter-spacing:4px}form{margin:0}")
          .append(".in{display:inline}</style></head><body>")
          .append("<h1>Flux TV</h1><div class='u'>").append(esc(Updater.currentName(App.ctx)))
          .append(" · settings apply to the TV right away</div>");
        if (message != null) sb.append("<p class='m'>").append(esc(message)).append("</p>");

        // ---------------- profiles & Trakt
        Trakt.Login login = Trakt.current();
        sb.append("<details open><summary>Profiles &amp; Trakt</summary>");
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
            sb.append("</div>");
        }
        sb.append("<form method='post' action='/profile-add' class='row'><input name='name' placeholder='New profile name' autocomplete='off'>")
          .append("<button>Add</button></form>");
        sb.append("<h3>Trakt API app</h3><p class='u'>")
          .append(Trakt.configured() ? "Saved. Paste new values to replace it." : "Create one (free) at <a style='color:var(--a)' href='https://trakt.tv/oauth/applications/new' target='_blank'>trakt.tv/oauth/applications/new</a>. Any name; Redirect URI: <b>urn:ietf:wg:oauth:2.0:oob</b>. Then paste its Client ID and Client Secret:")
          .append("</p><form method='post' action='/trakt-app'><input name='id' placeholder='Client ID' autocomplete='off'>")
          .append("<input name='secret' placeholder='Client Secret' autocomplete='off'><button>Save Trakt app</button></form>")
          .append("<p class='u'>Synced per profile: Favorites ⇄ Trakt watchlist, resume points, and watch history (scrobbling).</p>");
        sb.append("</details>");

        // ---------------- appearance
        sb.append("<details><summary>Appearance</summary><form method='post' action='/appearance'>Accent colour")
          .append(select("accent", Theme.ACCENT_NAMES, Theme.accentIndex())).append("Background")
          .append(select("bg", Theme.BG_NAMES, Theme.bgIndex())).append("<button>Save colours</button></form></details>");

        // ---------------- home
        sb.append("<details><summary>Home screen</summary>");
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
        sb.append("</select><button>Save</button></form></details>");

        // ---------------- player
        sb.append("<details><summary>Player</summary><form method='post' action='/player'>")
          .append("Preferred audio language").append(langSelect("player_audio_lang"))
          .append("Preferred subtitle language").append(langSelect("player_sub_lang"))
          .append("Subtitles").append(select("player_subs", new String[]{"", "off", "on"},
                  new String[]{"Only when the video says so (forced/default)", "Always off", "Always on"}, Store.get("player_subs")))
          .append("Subtitle size").append(select("player_sub_size", PlayerActivity.SUB_SIZES, PlayerActivity.intPref("player_sub_size", 1)))
          .append("Subtitle style").append(select("player_sub_style", PlayerActivity.SUB_STYLES, PlayerActivity.intPref("player_sub_style", 0)))
          .append("Picture size").append(select("player_zoom", PlayerActivity.ZOOMS, PlayerActivity.intPref("player_zoom", 0)))
          .append("<button>Save player settings</button></form><p class='u'>While watching: press ↑ (or Menu) for Audio, Subtitles, "
                  + "Speed and more. Subtitles from add-ons need a subtitles add-on such as OpenSubtitles v3.</p></details>");

        // ---------------- downloads
        sb.append("<details><summary>Radarr, Sonarr &amp; SABnzbd</summary><p class='u'>API keys: in each app under Settings → General. "
                + "Once connected, title pages get an Add to Radarr/Sonarr button and the sidebar gets Downloads.</p>");
        arrForm(sb, "radarr", "Radarr", "http://192.168.1.10:7878", Arr.RADARR.configured(), Arr.RADARR);
        arrForm(sb, "sonarr", "Sonarr", "http://192.168.1.10:8989", Arr.SONARR.configured(), Arr.SONARR);
        arrForm(sb, "sab", "SABnzbd", "http://192.168.1.10:8080", Sab.configured(), null);
        sb.append("<h3>Fallback</h3><form method='post' action='/arr-fallback'>When nothing can play from your servers or Real-Debrid")
          .append(select("fallback", new String[]{"on", "off"}, new String[]{"Send it to Radarr / Sonarr automatically",
                  "Only when I press \"Get with Radarr/Sonarr\""}, Arr.fallbackOn() ? "on" : "off"))
          .append("<button>Save</button></form>");
        sb.append("</details>");

        // ---------------- add-ons
        sb.append("<details><summary>Add-ons</summary><form method='post' action='/install'>")
          .append("<input name='url' placeholder='https://…/manifest.json' autocomplete='off'><button>Install</button></form>");
        List<String> urls = Store.addonUrls();
        for (String url : urls) {
            Addon a = Addons.load(url);
            String h = hidden("url", url);
            sb.append("<div class='a'><b>").append(esc(a != null ? a.name() : url)).append("</b><div class='u'>").append(esc(url)).append("</div>")
              .append(form("/addon-move", h + hidden("dir", "up"), "▲", "g s in"))
              .append(form("/addon-move", h + hidden("dir", "down"), "▼", "g s in"))
              .append(form("/remove", h, "Remove", "rm s in")).append("</div>");
        }
        sb.append("</details>");

        // ---------------- servers
        sb.append("<details><summary>Media servers</summary>");
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

        // ---------------- IPTV
        sb.append("<details><summary>Live TV &amp; IPTV</summary>");
        for (Iptv.Source is : Iptv.sources()) {
            sb.append("<div class='a'><b>").append(esc(is.name)).append("</b><div class='u'>")
              .append(esc(is.xtream() ? "Xtream Codes · " + is.url : "M3U playlist")).append("</div>")
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
          .append("<button>Add playlist</button></form></details>");

        // ---------------- RD
        sb.append("<details><summary>Real-Debrid</summary><p>").append(RealDebrid.isSignedIn() ? "Signed in." : "Not signed in.")
          .append(" Get your token at <a style='color:var(--a)' href='https://real-debrid.com/apitoken'>real-debrid.com/apitoken</a>.</p>")
          .append("<form method='post' action='/rdkey'><input name='key' placeholder='API token' autocomplete='off'><button>Save token</button></form>");
        if (RealDebrid.isSignedIn()) sb.append(form("/rd-signout", "", "Sign out", "rm"));
        sb.append("</details>");

        // ---------------- keys
        sb.append("<details><summary>Search &amp; discovery keys</summary>")
          .append("<p class='u'>Gemini (smart voice search): ").append(Store.get("gemini_key").isEmpty() ? "not set" : "saved")
          .append(" · TMDB (streaming services, cast &amp; crew): ").append(Store.get("tmdb_key").isEmpty() ? "not set" : "saved").append("</p>")
          .append("<form method='post' action='/keys'><input name='gemini' placeholder='Gemini API key (aistudio.google.com)' autocomplete='off'>")
          .append("<input name='tmdb' placeholder='TMDB API key or read token' autocomplete='off'><button>Save keys</button></form>");
        sb.append("<form method='post' action='/gemini-speed' class='row'><b>Gemini search</b>")
          .append(select("speed", new String[]{"fast", "smart"}, new String[]{"Fast (Flash-Lite)", "Smarter (Flash)"}, Gemini.fast() ? "fast" : "smart"))
          .append("<button>Save</button></form>");
        if (!Store.get("gemini_key").isEmpty()) sb.append(form("/key-remove", hidden("key", "gemini_key"), "Remove Gemini key", "g s"));
        if (!Store.get("tmdb_key").isEmpty()) sb.append(form("/key-remove", hidden("key", "tmdb_key"), "Remove TMDB key", "g s"));
        sb.append("</details></body></html>");
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
}
