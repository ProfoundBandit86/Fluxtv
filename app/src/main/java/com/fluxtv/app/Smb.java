package com.fluxtv.app;

import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

import jcifs.CIFSContext;
import jcifs.config.PropertyConfiguration;
import jcifs.context.BaseContext;
import jcifs.smb.NtlmPasswordAuthenticator;
import jcifs.smb.SmbFile;

/**
 * Windows / NAS network shares (SMB 2 and 3) for DVR recordings: browse folders, write recordings, play them back.
 * Settings: dvr_smb_host, dvr_smb_user, dvr_smb_pass, dvr_smb_domain, dvr_smb_path ("Share/Folder/Sub").
 */
public final class Smb {

    private Smb() { }

    public static String host() { return Store.get("dvr_smb_host").trim(); }
    public static String path() { return Store.get("dvr_smb_path").replaceAll("^/+|/+$", ""); }

    public static boolean configured() {
        return !host().isEmpty() && !path().isEmpty();
    }

    public static void save(String host, String user, String pass, String domain) {
        Store.put("dvr_smb_host", host.trim().replaceFirst("^smb://", "").replaceFirst("^\\\\\\\\", "").replaceAll("[/\\\\].*$", ""));
        Store.put("dvr_smb_user", user.trim());
        if (pass != null) Store.put("dvr_smb_pass", pass);
        Store.put("dvr_smb_domain", domain == null ? "" : domain.trim());
        ctx = null;
    }

    public static void setPath(String p) {
        Store.put("dvr_smb_path", p == null ? "" : p.replace('\\', '/').replaceAll("^/+|/+$", ""));
    }

    private static CIFSContext ctx;
    private static String ctxKey = "";

    static synchronized CIFSContext context() throws Exception {
        String key = host() + "|" + Store.get("dvr_smb_user") + "|" + Store.get("dvr_smb_pass") + "|" + Store.get("dvr_smb_domain");
        if (ctx != null && key.equals(ctxKey)) return ctx;
        Properties p = new Properties();
        p.setProperty("jcifs.smb.client.minVersion", "SMB202");
        p.setProperty("jcifs.smb.client.maxVersion", "SMB311");
        p.setProperty("jcifs.resolveOrder", "DNS,BCAST");
        p.setProperty("jcifs.smb.client.responseTimeout", "20000");
        p.setProperty("jcifs.smb.client.connTimeout", "10000");
        p.setProperty("jcifs.smb.client.soTimeout", "60000");
        CIFSContext base = new BaseContext(new PropertyConfiguration(p));
        String user = Store.get("dvr_smb_user");
        ctx = user.isEmpty() ? base.withGuestCrendentials()
                : base.withCredentials(new NtlmPasswordAuthenticator(Store.get("dvr_smb_domain"), user, Store.get("dvr_smb_pass")));
        ctxKey = key;
        return ctx;
    }

    /** smb://host/ + relative path (folders end with /). */
    public static String url(String rel) {
        rel = rel == null ? "" : rel.replaceAll("^/+", "");
        return "smb://" + host() + "/" + rel;
    }

    /** Shares (path "") or sub-folders of "Share/Folder". Blocking. */
    public static List<String> folders(String rel) throws Exception {
        String u = url(rel);
        if (!u.endsWith("/")) u += "/";
        List<String> out = new ArrayList<>();
        try (SmbFile dir = new SmbFile(u, context())) {
            for (SmbFile f : dir.listFiles()) {
                try {
                    String n = f.getName().replaceAll("/$", "");
                    if (n.endsWith("$") || n.startsWith(".")) continue;   // hidden admin shares / dot folders
                    if (rel.isEmpty() || f.isDirectory()) out.add(n);
                } catch (Exception ignored) { }
                f.close();
            }
        }
        Collections.sort(out, String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    public static void mkdir(String rel) throws Exception {
        try (SmbFile d = new SmbFile(url(rel) + (rel.endsWith("/") ? "" : "/"), context())) {
            if (!d.exists()) d.mkdirs();
        }
    }

    /** Checks the chosen folder can be written to (creates and deletes a small file). */
    public static void test() throws Exception {
        if (!configured()) throw new Exception("Choose a share and folder first");
        String probe = path() + "/.flux-test";
        try (SmbFile f = new SmbFile(url(probe), context())) {
            try (OutputStream o = f.getOutputStream()) { o.write('x'); }
            f.delete();
        }
    }

    public static OutputStream create(String fileName) throws Exception {
        mkdir(path());
        SmbFile f = new SmbFile(url(path() + "/" + fileName), context());
        return f.getOutputStream();
    }

    public static void delete(String smbUrl) {
        try (SmbFile f = new SmbFile(smbUrl, context())) { f.delete(); } catch (Exception ignored) { }
    }

    public static long size(String smbUrl) {
        try (SmbFile f = new SmbFile(smbUrl, context())) { return f.length(); } catch (Exception e) { return -1; }
    }
}
