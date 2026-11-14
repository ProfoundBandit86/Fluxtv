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
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Small web page served on the local network while the Add-ons screen is open,
 * so add-on URLs and the Real-Debrid token can be pasted from a phone or computer.
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
                    switch (path) {
                        case "/install":
                            message = "Installed " + Addons.install(form.get("url")).name();
                            break;
                        case "/remove":
                            Addons.uninstall(form.get("url"));
                            message = "Removed";
                            break;
                        case "/rdkey":
                            String key = form.get("key");
                            if (key == null || key.trim().isEmpty()) throw new Exception("Paste your API token");
                            RealDebrid.setApiKey(key);
                            message = "Real-Debrid token saved";
                            break;
                        case "/server-login":
                            MediaServer ms = Jellyfin.login(MediaServer.EMBY.equals(form.get("kind")) ? MediaServer.EMBY
                                    : MediaServer.JELLYFIN, form.get("url"), form.get("user"), form.get("pass"));
                            Store.saveServer(ms);
                            message = "Added " + ms.name;
                            break;
                        case "/plex-manual":
                            MediaServer px = Plex.manual(form.get("url"), form.get("token") == null ? "" : form.get("token"));
                            Store.saveServer(px);
                            message = "Added " + px.name;
                            break;
                        case "/iptv-xtream":
                            Iptv.Source xs = Iptv.loginXtream(form.get("url"), form.get("user"), form.get("pass"));
                            Iptv.save(xs);
                            message = "Added " + xs.name;
                            break;
                        case "/iptv-m3u":
                            Iptv.Source ms3 = Iptv.addM3u(form.get("m3u"), form.get("epg"));
                            Iptv.save(ms3);
                            message = "Added " + ms3.name;
                            break;
                        case "/iptv-remove":
                            Iptv.remove(form.get("id") == null ? "" : form.get("id"));
                            message = "IPTV provider removed";
                            break;
                        case "/server-remove":
                            String[] k = (form.get("key") == null ? "" : form.get("key")).split(":", 2);
                            if (k.length == 2) Store.removeServer(k[0], k[1]);
                            message = "Server removed";
                            break;
                        default:
                            break;
                    }
                } catch (Exception e) {
                    message = "Error: " + App.message(e);
                }
                Runnable r = onChange;
                if (r != null) App.MAIN.post(r);
            }
            respond(c.getOutputStream(), page(message));
        } catch (Exception ignored) { }
    }

    private static String page(String message) {
        StringBuilder sb = new StringBuilder();
        sb.append("<!doctype html><html><head><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'>")
          .append("<title>Flux TV setup</title><style>")
          .append("body{font-family:system-ui,sans-serif;background:#0f0c18;color:#eee;max-width:640px;margin:auto;padding:20px}")
          .append("h1{color:#7b5cff}input{width:100%;padding:12px;font-size:16px;border-radius:8px;border:1px solid #444;background:#1b1630;color:#fff;box-sizing:border-box}")
          .append("button{padding:10px 18px;font-size:16px;border:0;border-radius:8px;background:#7b5cff;color:#fff;margin-top:8px}")
          .append(".a{background:#1b1630;padding:12px;border-radius:10px;margin:10px 0}.u{color:#999;font-size:12px;word-break:break-all}")
          .append(".m{background:#2a2340;padding:10px;border-radius:8px}.rm{background:#a33}</style></head><body>")
          .append("<h1>Flux TV</h1>");
        if (message != null) sb.append("<p class='m'>").append(esc(message)).append("</p>");
        sb.append("<h2>Install an add-on</h2><form method='post' action='/install'>")
          .append("<input name='url' placeholder='https://…/manifest.json' autocomplete='off'>")
          .append("<button>Install</button></form>");
        sb.append("<h2>Installed add-ons</h2>");
        for (String url : Store.addonUrls()) {
            Addon a = Addons.load(url);
            sb.append("<div class='a'><b>").append(esc(a != null ? a.name() : url)).append("</b><div class='u'>")
              .append(esc(url)).append("</div><form method='post' action='/remove'><input type='hidden' name='url' value='")
              .append(esc(url)).append("'><button class='rm'>Remove</button></form></div>");
        }
        sb.append("<h2>Media servers</h2>");
        for (MediaServer ms : Store.servers()) {
            sb.append("<div class='a'><b>").append(esc(ms.name)).append("</b> (").append(ms.kindLabel())
              .append(")<div class='u'>").append(esc(ms.url)).append("</div><form method='post' action='/server-remove'>")
              .append("<input type='hidden' name='key' value='").append(esc(ms.kind + ":" + ms.id))
              .append("'><button class='rm'>Remove</button></form></div>");
        }
        sb.append("<h3>Add Jellyfin or Emby</h3><form method='post' action='/server-login'>")
          .append("<select name='kind' style='padding:10px;font-size:16px;margin-bottom:8px'><option value='jellyfin'>Jellyfin</option>")
          .append("<option value='emby'>Emby</option></select>")
          .append("<input name='url' placeholder='http://192.168.1.10:8096' autocomplete='off'>")
          .append("<input name='user' placeholder='Username' autocomplete='off' style='margin-top:8px'>")
          .append("<input name='pass' type='password' placeholder='Password' style='margin-top:8px'>")
          .append("<button>Sign in</button></form>")
          .append("<h3>Add Plex by address + token</h3><p class='u'>Easier: use \"+ Plex (sign in with code)\" on the TV.</p>")
          .append("<form method='post' action='/plex-manual'><input name='url' placeholder='http://192.168.1.10:32400' autocomplete='off'>")
          .append("<input name='token' placeholder='X-Plex-Token' autocomplete='off' style='margin-top:8px'>")
          .append("<button>Add Plex server</button></form>");
        sb.append("<h2>Live TV &amp; IPTV</h2>");
        for (Iptv.Source is : Iptv.sources()) {
            sb.append("<div class='a'><b>").append(esc(is.name)).append("</b><div class='u'>")
              .append(esc(is.xtream() ? "Xtream Codes · " + is.url : "M3U playlist")).append("</div><form method='post' action='/iptv-remove'>")
              .append("<input type='hidden' name='id' value='").append(esc(is.id)).append("'><button class='rm'>Remove</button></form></div>");
        }
        sb.append("<h3>Xtream Codes login</h3><p class='u'>Dispatcharr: http://&lt;its IP&gt;:9191, a Dispatcharr username and that user's XC password.</p>")
          .append("<form method='post' action='/iptv-xtream'><input name='url' placeholder='http://example.com:8080' autocomplete='off'>")
          .append("<input name='user' placeholder='Username' autocomplete='off' style='margin-top:8px'>")
          .append("<input name='pass' type='password' placeholder='Password' style='margin-top:8px'>")
          .append("<button>Sign in</button></form>")
          .append("<h3>M3U playlist</h3><form method='post' action='/iptv-m3u'>")
          .append("<input name='m3u' placeholder='Playlist link (.m3u / get.php)' autocomplete='off'>")
          .append("<input name='epg' placeholder='Guide link (XMLTV), optional' autocomplete='off' style='margin-top:8px'>")
          .append("<button>Add playlist</button></form>");
        sb.append("<h2>Real-Debrid</h2><p>")
          .append(RealDebrid.isSignedIn() ? "Signed in." : "Not signed in.")
          .append(" Get your token at <a style='color:#7b5cff' href='https://real-debrid.com/apitoken'>real-debrid.com/apitoken</a>.</p>")
          .append("<form method='post' action='/rdkey'><input name='key' placeholder='API token' autocomplete='off'>")
          .append("<button>Save token</button></form></body></html>");
        return sb.toString();
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
