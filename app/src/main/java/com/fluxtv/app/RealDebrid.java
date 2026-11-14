package com.fluxtv.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Real-Debrid client: device-code sign-in (or a pasted API token), token refresh,
 * and turning a torrent info-hash from a stream add-on into a direct HTTPS link.
 */
public final class RealDebrid {

    private static final String OAUTH = "https://api.real-debrid.com/oauth/v2/";
    private static final String API = "https://api.real-debrid.com/rest/1.0/";
    /** Real-Debrid's public client ID for open-source apps (device-code flow). */
    private static final String OPEN_SOURCE_CLIENT_ID = "X245A4XAIBGVM";
    private static final String DEVICE_GRANT = "http://oauth.net/grant_type/device/1.0";

    private static final List<String> VIDEO_EXT = Arrays.asList(
            ".mkv", ".mp4", ".avi", ".m4v", ".mov", ".ts", ".m2ts", ".webm", ".wmv", ".mpg", ".mpeg");
    private static final List<String> FAILED = Arrays.asList("magnet_error", "error", "virus", "dead");

    public static class NotCachedException extends Exception {
        NotCachedException(String m) { super(m); }
    }

    public static class DeviceCode {
        public String deviceCode, userCode, verificationUrl;
        public int interval, expiresIn;
    }

    public interface Status { void update(String message); }

    private RealDebrid() { }

    // ------------------------------------------------------------ account

    public static boolean isSignedIn() {
        return !Store.get("rd_api_key").isEmpty() || !Store.get("rd_refresh").isEmpty();
    }

    public static void signOut() {
        for (String k : new String[]{"rd_api_key", "rd_access", "rd_refresh", "rd_expiry", "rd_client_id", "rd_client_secret"}) {
            Store.put(k, "");
        }
    }

    public static void setApiKey(String key) {
        signOut();
        Store.put("rd_api_key", key == null ? "" : key.trim());
    }

    public static JSONObject user() throws Exception {
        return new JSONObject(Http.get(API + "user", token()));
    }

    public static DeviceCode startDeviceLogin() throws Exception {
        JSONObject j = new JSONObject(Http.get(OAUTH + "device/code?client_id=" + OPEN_SOURCE_CLIENT_ID
                + "&new_credentials=yes", null));
        DeviceCode d = new DeviceCode();
        d.deviceCode = j.getString("device_code");
        d.userCode = j.getString("user_code");
        d.verificationUrl = j.optString("verification_url", "https://real-debrid.com/device");
        d.interval = Math.max(2, j.optInt("interval", 5));
        d.expiresIn = j.optInt("expires_in", 600);
        return d;
    }

    /** Returns true once the user has approved the code on real-debrid.com. */
    public static boolean pollDeviceLogin(DeviceCode d) throws Exception {
        JSONObject cred;
        try {
            cred = new JSONObject(Http.get(OAUTH + "device/credentials?client_id=" + OPEN_SOURCE_CLIENT_ID
                    + "&code=" + Http.enc(d.deviceCode), null));
        } catch (Http.HttpException notYet) {
            return false;
        }
        if (!cred.has("client_secret")) return false;
        String cid = cred.getString("client_id");
        String secret = cred.getString("client_secret");
        JSONObject tok = new JSONObject(Http.post(OAUTH + "token", Http.map(
                "client_id", cid, "client_secret", secret, "code", d.deviceCode, "grant_type", DEVICE_GRANT), null));
        signOut();
        Store.put("rd_client_id", cid);
        Store.put("rd_client_secret", secret);
        saveToken(tok);
        return true;
    }

    private static void saveToken(JSONObject t) {
        Store.put("rd_access", t.optString("access_token"));
        Store.put("rd_refresh", t.optString("refresh_token"));
        Store.put("rd_expiry", String.valueOf(System.currentTimeMillis() + t.optLong("expires_in", 3600) * 1000L));
    }

    private static synchronized String token() throws Exception {
        String key = Store.get("rd_api_key");
        if (!key.isEmpty()) return key;
        String refresh = Store.get("rd_refresh");
        if (refresh.isEmpty()) throw new Exception("Sign in to Real-Debrid first (Real-Debrid in the menu).");
        long expiry = 0;
        try { expiry = Long.parseLong(Store.get("rd_expiry")); } catch (NumberFormatException ignored) { }
        if (System.currentTimeMillis() > expiry - 60_000) {
            saveToken(new JSONObject(Http.post(OAUTH + "token", Http.map(
                    "client_id", Store.get("rd_client_id"),
                    "client_secret", Store.get("rd_client_secret"),
                    "code", refresh,
                    "grant_type", DEVICE_GRANT), null)));
        }
        return Store.get("rd_access");
    }

    // ------------------------------------------------------------ resolving

    /**
     * Adds the torrent to Real-Debrid, selects the right file and returns a direct
     * streaming URL. Throws NotCachedException when Real-Debrid doesn't already have it.
     */
    public static String resolve(String infoHash, int fileIdx, String filename, Status status) throws Exception {
        String tok = token();
        status.update("Adding to Real-Debrid…");
        String id = new JSONObject(Http.post(API + "torrents/addMagnet",
                Http.map("magnet", "magnet:?xt=urn:btih:" + infoHash), tok)).getString("id");
        boolean keep = false;
        try {
            JSONObject info = waitFor(id, tok, 20, "waiting_files_selection", "downloaded");
            if ("waiting_files_selection".equals(info.optString("status"))) {
                int fileId = pickFile(info.optJSONArray("files"), fileIdx, filename);
                Http.post(API + "torrents/selectFiles/" + id,
                        Http.map("files", fileId > 0 ? String.valueOf(fileId) : "all"), tok);
                status.update("Checking Real-Debrid cache…");
                info = waitFor(id, tok, 8, "downloaded");
            }
            if (!"downloaded".equals(info.optString("status"))) {
                throw new NotCachedException("Not cached on Real-Debrid (" + info.optString("status")
                        + ", " + info.optInt("progress") + "%). Try another stream.");
            }
            JSONArray links = info.optJSONArray("links");
            if (links == null || links.length() == 0) throw new Exception("Real-Debrid returned no links");
            status.update("Getting stream link…");
            JSONObject un = new JSONObject(Http.post(API + "unrestrict/link",
                    Http.map("link", links.getString(0)), tok));
            keep = true;
            return un.getString("download");
        } finally {
            if (!keep) {
                try { Http.delete(API + "torrents/delete/" + id, tok); } catch (Exception ignored) { }
            }
        }
    }

    private static JSONObject waitFor(String id, String tok, int seconds, String... targets) throws Exception {
        JSONObject info = new JSONObject();
        for (int i = 0; i < seconds; i++) {
            info = new JSONObject(Http.get(API + "torrents/info/" + id, tok));
            String st = info.optString("status");
            for (String t : targets) if (t.equals(st)) return info;
            if (FAILED.contains(st)) throw new Exception("Real-Debrid: " + st);
            Thread.sleep(1000);
        }
        return info;
    }

    /** Real-Debrid file ids are 1-based in torrent order; add-ons give a 0-based fileIdx. */
    private static int pickFile(JSONArray files, int fileIdx, String filename) {
        if (files == null || files.length() == 0) return -1;
        if (fileIdx >= 0) {
            for (int i = 0; i < files.length(); i++) {
                JSONObject f = files.optJSONObject(i);
                if (f != null && f.optInt("id") == fileIdx + 1 && isVideo(f.optString("path"))) return f.optInt("id");
            }
        }
        if (filename != null && !filename.isEmpty()) {
            for (int i = 0; i < files.length(); i++) {
                JSONObject f = files.optJSONObject(i);
                if (f == null) continue;
                String path = f.optString("path");
                if (path.equals(filename) || path.endsWith("/" + filename)) return f.optInt("id");
            }
        }
        int best = -1, any = -1;
        long bestSize = -1, anySize = -1;
        for (int i = 0; i < files.length(); i++) {
            JSONObject f = files.optJSONObject(i);
            if (f == null) continue;
            long size = f.optLong("bytes");
            if (size > anySize) { anySize = size; any = f.optInt("id"); }
            if (isVideo(f.optString("path")) && size > bestSize) { bestSize = size; best = f.optInt("id"); }
        }
        return best > 0 ? best : any;
    }

    private static boolean isVideo(String path) {
        String p = path.toLowerCase(Locale.ROOT);
        for (String ext : VIDEO_EXT) if (p.endsWith(ext)) return true;
        return false;
    }
}
