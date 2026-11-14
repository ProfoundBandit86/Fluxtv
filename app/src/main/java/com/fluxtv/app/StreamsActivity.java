package com.fluxtv.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Stream picker: a panel over the title's backdrop. Your library's copies come first, then
 * add-on streams with clean quality tags. Torrent results go through Real-Debrid.
 * Long-press a stream to open it in another installed player.
 */
public class StreamsActivity extends FluxActivity {

    private static final Pattern SIZE = Pattern.compile("(\\d+(?:[.,]\\d+)?)\\s?(GB|MB)", Pattern.CASE_INSENSITIVE);

    /** A stream plus what we parsed from its text. */
    private static class Item {
        Addons.Stream s;
        boolean library;
        String title, quality = "", mark;
        List<String> tags = new ArrayList<>();
        double sizeGb = -1;
        View row;
        TextView status;
    }

    private PlayInfo info;
    private LinearLayout list, filters;
    private TextView status, banner;
    private LinearLayout requestRow;
    private android.widget.Button requestBtn;
    private boolean requested;
    private final java.util.Set<Item> failed = new java.util.HashSet<>();
    private LinearLayout bannerBox;
    private final List<Item> items = new ArrayList<>();
    private String filter = "All";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        info = PlayInfo.from(getIntent());

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Ui.BG);
        ImageView bg = new ImageView(this);
        bg.setScaleType(ImageView.ScaleType.CENTER_CROP);
        bg.setAlpha(0.35f);
        root.addView(bg, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        ImageLoader.load(bg, info.background.isEmpty() ? info.poster : info.background, Ui.dp(this, 900));
        View dim = new View(this);
        dim.setBackgroundColor(Ui.alpha(Color.BLACK, 0.45f));
        root.addView(dim, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout left = Ui.vertical(this);
        left.setPadding(Ui.dp(this, 56), Ui.dp(this, 56), Ui.dp(this, 24), 0);
        TextView bigTitle = Ui.heading(this, info.name, 34);
        bigTitle.setMaxLines(3);
        left.addView(bigTitle);
        if (!info.label.isEmpty()) {
            TextView l = Ui.text(this, info.label, 15, Ui.MUTED);
            l.setPadding(0, Ui.dp(this, 8), 0, 0);
            left.addView(l);
        }
        root.addView(left, new FrameLayout.LayoutParams(Ui.dp(this, 340), ViewGroup.LayoutParams.WRAP_CONTENT));

        // Panel on the right.
        ScrollView scroll = new FocusScroll.Vertical(this);
        scroll.setBackgroundColor(Ui.PANEL);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout panel = Ui.vertical(this);
        panel.setPadding(Ui.dp(this, 36), Ui.dp(this, 32), Ui.dp(this, 36), Ui.dp(this, 40));
        scroll.addView(panel);
        FrameLayout.LayoutParams pp = new FrameLayout.LayoutParams(Ui.dp(this, 560), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END);
        root.addView(scroll, pp);
        setContentView(root);

        panel.addView(Ui.eyebrow(this, info.label.isEmpty() ? info.name : info.label));
        TextView h = Ui.heading(this, "Choose a stream", 26);
        h.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 4));
        panel.addView(h);
        status = Ui.text(this, "Finding streams…", 13, Ui.MUTED);
        status.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 10));
        panel.addView(status);

        bannerBox = Ui.horizontal(this);
        bannerBox.setBackground(Ui.rounded(this, Ui.alpha(Ui.WARN, 0.12f), Ui.alpha(Ui.WARN, 0.35f), 12));
        bannerBox.setPadding(Ui.dp(this, 14), Ui.dp(this, 12), Ui.dp(this, 14), Ui.dp(this, 12));
        bannerBox.setGravity(Gravity.CENTER_VERTICAL);
        bannerBox.addView(Ui.icon(this, R.drawable.ic_alert, 20, Ui.WARN));
        banner = Ui.text(this, "", 13, Color.parseColor("#F8D9A6"));
        banner.setPadding(Ui.dp(this, 12), 0, 0, 0);
        bannerBox.addView(banner, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        bannerBox.setVisibility(View.GONE);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.setMargins(0, 0, 0, Ui.dp(this, 10));
        panel.addView(bannerBox, blp);

        // Radarr / Sonarr fallback.
        requestRow = Ui.horizontal(this);
        requestRow.setGravity(Gravity.CENTER_VERTICAL);
        Arr arr = Arr.forType(info.type);
        if (arr.configured() && !info.metaId.startsWith("iptv:")) {
            requestBtn = Ui.button(this, R.drawable.ic_download, "Get with " + arr.name, v -> requestFromArr(false));
            requestRow.addView(requestBtn);
            requestRow.setPadding(0, 0, 0, Ui.dp(this, 8));
        }
        panel.addView(requestRow);

        filters = Ui.horizontal(this);
        panel.addView(filters);
        list = Ui.vertical(this);
        panel.addView(list);

        load();
    }

    private void load() {
        status.setText("Finding streams…");
        list.removeAllViews();
        App.async(() -> Addons.streams(info.type, info.videoId), this::onStreams, e -> status.setText(App.message(e)));
    }

    // ------------------------------------------------------------ parsing

    private static Item parse(Addons.Stream s) {
        Item it = new Item();
        it.s = s;
        JSONObject j = s.json;
        it.library = s.library;
        String name = j.optString("name");
        String desc = j.optString("title", j.optString("description"));
        String all = (name + " " + desc).replace('\n', ' ');
        String low = all.toLowerCase(Locale.ROOT);
        if (low.matches(".*\\b(2160p|4k|uhd)\\b.*")) it.quality = "4K";
        else if (low.contains("1080p")) it.quality = "1080p";
        else if (low.contains("720p")) it.quality = "720p";
        else if (low.matches(".*\\b(480p|sd)\\b.*")) it.quality = "SD";
        if (low.contains("dolby vision") || low.matches(".*\\b(dv|dovi)\\b.*")) it.tags.add("Dolby Vision");
        if (low.contains("hdr10+")) it.tags.add("HDR10+");
        else if (low.contains("hdr")) it.tags.add("HDR");
        if (low.contains("atmos")) it.tags.add("Atmos");
        else if (low.matches(".*(ddp?5\\.1|5\\.1|dts).*")) it.tags.add("5.1");
        if (low.matches(".*\\b(remux)\\b.*")) it.tags.add("Remux");
        Matcher m = SIZE.matcher(all);
        if (m.find()) {
            double v = Double.parseDouble(m.group(1).replace(',', '.'));
            it.sizeGb = m.group(2).equalsIgnoreCase("MB") ? v / 1024 : v;
        }
        // Title: first meaningful line of the description, else the name.
        String first = "";
        for (String line : desc.split("\n")) {
            String t = line.trim();
            if (t.length() > 3 && Character.isLetterOrDigit(t.charAt(0))) { first = t; break; }
        }
        it.title = !first.isEmpty() ? first : name.replace('\n', ' ').trim();
        if (it.library) it.title = desc.isEmpty() ? name : desc;
        String src = s.source;
        it.mark = src.contains("Plex") ? "PLEX" : src.contains("Jellyfin") ? "JF" : src.contains("Emby") ? "EMBY"
                : src.length() >= 2 ? src.substring(0, 2).toUpperCase(Locale.ROOT) : "··";
        return it;
    }

    // ------------------------------------------------------------ rendering

    private void onStreams(List<Addons.Stream> streams) {
        items.clear();
        for (Addons.Stream s : streams) items.add(parse(s));
        if (items.isEmpty()) {
            status.setText("No streams found. Add a streaming add-on, or a Plex / Jellyfin / Emby server that has this title.");
            View retry = Ui.button(this, "Retry", v -> load());
            list.addView(retry);
            retry.post(retry::requestFocus);
            if (autoRequest()) requestFromArr(true);
            return;
        }
        // Nothing here can actually play (no library copy, no direct link, no Real-Debrid): fall back.
        boolean playable = false;
        for (Item i : items) {
            JSONObject j = i.s.json;
            if (i.library || !j.optString("url").isEmpty() || (!j.optString("infoHash").isEmpty() && RealDebrid.isSignedIn())) playable = true;
        }
        if (!playable && autoRequest()) requestFromArr(true);
        if (getIntent().getBooleanExtra("autoplay", false) && autoQueue == null) {
            autoQueue = ranked();
            if (!autoQueue.isEmpty()) {
                Item best = autoQueue.remove(0);
                status.setText("Starting the best source: " + describe(best) + "…  (Back to choose another)");
                renderFilters();
                renderList(false);
                play(best, false);
                return;
            }
        }
        int lib = 0;
        for (Item i : items) if (i.library) lib++;
        status.setText(items.size() + " streams" + (lib > 0 ? " · " + lib + " from your library" : "")
                + " · long-press for another player");
        renderFilters();
        renderList(true);
    }

    private void renderFilters() {
        filters.removeAllViews();
        for (String f : new String[]{"All", "4K", "1080p", "720p & less", "Smallest"}) {
            filters.addView(Ui.pill(this, f, f.equals(filter), v -> {
                filter = f;
                renderFilters();
                renderList(false);
                for (int i = 0; i < filters.getChildCount(); i++) {
                    if (((TextView) filters.getChildAt(i)).getText().toString().equals(f)) filters.getChildAt(i).requestFocus();
                }
            }));
        }
    }

    private boolean showOversize;

    private static boolean oversize(Item it) {
        return !it.library && SizeLimits.tooBig(it.quality, it.sizeGb);
    }

    private boolean matches(Item it) {
        if (!showOversize && oversize(it)) return false;
        switch (filter) {
            case "4K": return it.quality.equals("4K");
            case "1080p": return it.quality.equals("1080p");
            case "720p & less": return it.quality.equals("720p") || it.quality.equals("SD");
            default: return true;
        }
    }

    private void renderList(boolean focusFirst) {
        list.removeAllViews();
        List<Item> shown = new ArrayList<>();
        for (Item it : items) if (matches(it)) shown.add(it);
        if (filter.equals("Smallest")) {
            Collections.sort(shown, (a, b) -> Double.compare(a.sizeGb < 0 ? 1e9 : a.sizeGb, b.sizeGb < 0 ? 1e9 : b.sizeGb));
        }
        boolean libHeader = false, otherHeader = false;
        View first = null;
        for (Item it : shown) {
            if (it.library && !libHeader && !filter.equals("Smallest")) {
                list.addView(section("Your library"));
                libHeader = true;
            }
            if (!it.library && !otherHeader && !filter.equals("Smallest")) {
                list.addView(section("Streaming add-ons" + (RealDebrid.isSignedIn() ? " · Real-Debrid" : "")));
                otherHeader = true;
            }
            View row = row(it);
            list.addView(row);
            if (first == null) first = row;
        }
        if (shown.isEmpty()) list.addView(Ui.text(this, "No streams match this filter.", 13, Ui.MUTED));
        int hidden = 0;
        for (Item it : items) if (oversize(it)) hidden++;
        if (hidden > 0) {
            LinearLayout note = Ui.horizontal(this);
            note.setGravity(Gravity.CENTER_VERTICAL);
            note.setPadding(0, Ui.dp(this, 12), 0, 0);
            note.addView(Ui.text(this, showOversize ? "Showing streams over your size limits  " : hidden + " larger than your size limits are hidden  ", 13, Ui.DIM));
            note.addView(Ui.button(this, showOversize ? "Hide them" : "Show them", v -> {
                showOversize = !showOversize;
                renderList(false);
            }));
            list.addView(note);
        }
        if (focusFirst && first != null) {
            View f = first;
            f.post(f::requestFocus);
        }
    }

    private TextView section(String s) {
        TextView t = Ui.text(this, s.toUpperCase(Locale.ROOT), 11, Ui.DIM);
        t.setLetterSpacing(0.08f);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setPadding(0, Ui.dp(this, 18), 0, Ui.dp(this, 6));
        return t;
    }

    /**
     * How a stream will play: [label, non-null when it's a ready Real-Debrid stream].
     * Torrentio set up with a Real-Debrid key (its Configure page) marks streams [RD+] (ready) or [RD download].
     */
    private static String[] via(Item it) {
        JSONObject j = it.s.json;
        String name = (j.optString("name") + " " + j.optString("title")).toUpperCase(Locale.ROOT);
        String url = j.optString("url").toLowerCase(Locale.ROOT);
        if (it.library) return new String[]{"Your copy", "ok"};
        if (name.contains("[RD+]")) return new String[]{"Real-Debrid ✓ ready", "ok"};
        if (name.contains("[RD DOWNLOAD]") || name.contains("[RD ")) return new String[]{"Real-Debrid · not ready", null};
        if (url.contains("/realdebrid/") || url.contains("real-debrid.com") || url.contains("rdeb.io")) return new String[]{"Real-Debrid", "ok"};
        if (!j.optString("infoHash").isEmpty() && url.isEmpty()) {
            return new String[]{RealDebrid.isSignedIn() ? "Real-Debrid (via Flux)" : "Needs Real-Debrid", null};
        }
        return new String[]{"Direct", null};
    }

    private View row(Item it) {
        LinearLayout r = Ui.horizontal(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setFocusable(true);
        r.setClickable(true);
        r.setPadding(Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 14), Ui.dp(this, 12));
        StateListDrawable bg = new StateListDrawable();
        bg.addState(new int[]{android.R.attr.state_focused}, Ui.rounded(this, Ui.BUTTON, Ui.TEXT, 14));
        bg.addState(new int[]{}, Ui.rounded(this, Ui.alpha(Ui.BUTTON, 0.55f), 0, 14));
        r.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, Ui.dp(this, 4), 0, Ui.dp(this, 4));
        r.setLayoutParams(lp);

        TextView mark = Ui.heading(this, it.mark, 10);
        mark.setGravity(Gravity.CENTER);
        mark.setBackground(Ui.rounded(this, Ui.BORDER, 0, 9));
        r.addView(mark, new LinearLayout.LayoutParams(Ui.dp(this, 38), Ui.dp(this, 38)));

        LinearLayout mid = Ui.vertical(this);
        mid.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 10), 0);
        TextView t = Ui.text(this, it.title, 14, Ui.TEXT);
        t.setMaxLines(2);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        mid.addView(t);
        LinearLayout chips = Ui.horizontal(this);
        chips.setGravity(Gravity.CENTER_VERTICAL);
        chips.setPadding(0, Ui.dp(this, 7), 0, 0);
        if (!it.quality.isEmpty()) chips.addView(Ui.chip(this, it.quality, Ui.TEXT, Ui.BG));
        for (String tag : it.tags) chips.addView(Ui.chip(this, tag, Ui.BORDER, Ui.TEXT));
        String extra = (it.sizeGb > 0 ? String.format(Locale.ROOT, it.sizeGb >= 1 ? "%.1f GB" : "%.0f MB",
                it.sizeGb >= 1 ? it.sizeGb : it.sizeGb * 1024) : "");
        String src = it.library ? it.s.source : it.s.source;
        TextView meta = Ui.text(this, (extra.isEmpty() ? "" : extra + "  ·  ") + src, 12, Ui.MUTED);
        chips.addView(meta);
        mid.addView(chips);
        r.addView(mid, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        String[] via = via(it);
        it.status = Ui.text(this, via[0], 12, it.library || via[1] != null ? Ui.GOOD : Ui.MUTED);
        r.addView(it.status);

        it.row = r;
        r.setOnClickListener(v -> play(it, false));
        r.setOnLongClickListener(v -> {
            play(it, true);
            return true;
        });
        return r;
    }

    // ------------------------------------------------------------ playing

    /** Playable streams, best first: your library, then highest resolution within your size limits, in add-on order. */
    private List<Item> autoQueue;

    private List<Item> ranked() {
        return ranked(items);
    }

    private static List<Item> ranked(List<Item> all) {
        List<Item> out = new ArrayList<>();
        for (Item i : all) {
            JSONObject j = i.s.json;
            boolean playable = i.library || !j.optString("url").isEmpty()
                    || (!j.optString("infoHash").isEmpty() && RealDebrid.isSignedIn());
            if (playable && !oversize(i)) out.add(i);
        }
        Collections.sort(out, (a, b) -> {
            if (a.library != b.library) return a.library ? -1 : 1;
            return Integer.compare(rank(b.quality), rank(a.quality));
        });
        return out;
    }

    private static int rank(String q) {
        switch (q) {
            case "4K": return 4;
            case "1080p": return 3;
            case "720p": return 2;
            case "SD": return 1;
            default: return 0;
        }
    }

    /** For the title page: [label of the stream Play would start, number of streams], or null if none can play. */
    static String[] bestOf(List<Addons.Stream> streams) {
        List<Item> all = new ArrayList<>();
        for (Addons.Stream st : streams) all.add(parse(st));
        List<Item> r = ranked(all);
        if (r.isEmpty()) return new String[]{null, String.valueOf(all.size())};
        return new String[]{describeStatic(r.get(0)), String.valueOf(all.size())};
    }

    private static String describeStatic(Item it) {
        String q = it.quality.isEmpty() ? "" : it.quality + " · ";
        return q + (it.library ? it.s.source : RealDebrid.isSignedIn() && !it.s.json.optString("infoHash").isEmpty() ? "Real-Debrid" : it.s.source);
    }

    private String describe(Item it) {
        String q = it.quality.isEmpty() ? "" : it.quality + " · ";
        return q + (it.library ? it.s.source : RealDebrid.isSignedIn() && !it.s.json.optString("infoHash").isEmpty() ? "Real-Debrid" : it.s.source);
    }

    private boolean autoRequest() {
        return !requested && Arr.fallbackOn() && Arr.forType(info.type).configured() && !info.metaId.startsWith("iptv:");
    }

    /** Sends the title (or this episode) to Radarr/Sonarr. */
    private void requestFromArr(boolean automatic) {
        if (requested && automatic) return;
        requested = true;
        Arr arr = Arr.forType(info.type);
        if (requestBtn != null) requestBtn.setText("Sending to " + arr.name + "…");
        App.async(() -> Arr.request(info), msg -> {
            if (isFinishing()) return;
            showBanner(msg);
            if (requestBtn != null) {
                requestBtn.setText("Open Downloads");
                requestBtn.setOnClickListener(v -> startActivity(new Intent(this, DownloadsActivity.class)));
            }
        }, e -> {
            if (isFinishing()) return;
            requested = false;
            showBanner(arr.name + ": " + App.message(e));
            if (requestBtn != null) requestBtn.setText("Get with " + arr.name);
        });
    }

    private void showBanner(String msg) {
        banner.setText(msg);
        bannerBox.setVisibility(View.VISIBLE);
    }

    private void markUnavailable(Item it, String label) {
        it.row.setAlpha(0.45f);
        it.status.setText(label);
        it.status.setTextColor(Ui.WARN);
    }

    private String lastSource = "";

    private void play(Item it, boolean external) {
        lastSource = describe(it);
        JSONObject s = it.s.json;
        bannerBox.setVisibility(View.GONE);
        if (!s.optString("url").isEmpty()) {
            start(s.optString("url"), external);
        } else if (!s.optString("infoHash").isEmpty()) {
            if (!RealDebrid.isSignedIn()) {
                showBanner("Sign in to Real-Debrid to play this kind of stream.");
                startActivity(new Intent(this, AccountActivity.class));
                return;
            }
            resolve(it, external);
        } else if (!s.optString("ytId").isEmpty()) {
            view("https://www.youtube.com/watch?v=" + s.optString("ytId"));
        } else if (!s.optString("externalUrl").isEmpty()) {
            view(s.optString("externalUrl"));
        } else {
            showBanner("This stream type isn't supported.");
        }
    }

    private void resolve(Item it, boolean external) {
        JSONObject s = it.s.json;
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Real-Debrid")
                .setMessage("Starting…")
                .setCancelable(true)
                .create();
        dialog.show();
        JSONObject hints = s.optJSONObject("behaviorHints");
        String filename = hints != null ? hints.optString("filename") : "";
        int fileIdx = s.has("fileIdx") ? s.optInt("fileIdx", -1) : -1;
        String hash = s.optString("infoHash");
        App.async(() -> RealDebrid.resolve(hash, fileIdx, filename,
                        msg -> App.MAIN.post(() -> { if (dialog.isShowing()) dialog.setMessage(msg); })),
                url -> {
                    if (!dialog.isShowing() || isFinishing()) return;   // user cancelled
                    dialog.dismiss();
                    start(url, external);
                },
                e -> {
                    if (isFinishing()) return;
                    dialog.dismiss();
                    showBanner(App.message(e));
                    if (autoQueue != null && !autoQueue.isEmpty() && e instanceof RealDebrid.RdException
                            && ((RealDebrid.RdException) e).unavailable) {
                        markUnavailable(it, e instanceof RealDebrid.NotCachedException ? "Not ready" : "Blocked");
                        failed.add(it);
                        Item next = autoQueue.remove(0);
                        status.setText("That one wasn't available. Trying " + describe(next) + "…");
                        play(next, false);
                        return;
                    }
                    if (autoQueue != null) {
                        autoQueue = new ArrayList<>();   // stop auto-trying; let the viewer pick
                        status.setText("Couldn't start the best source automatically. Pick one below.");
                    }
                    if (e instanceof RealDebrid.RdException && ((RealDebrid.RdException) e).unavailable) {
                        markUnavailable(it, e instanceof RealDebrid.NotCachedException ? "Not ready" : "Blocked");
                        failed.add(it);
                        // Every Real-Debrid stream failed and there's no library copy: fall back.
                        boolean anyLeft = false;
                        for (Item i : items) {
                            if (i.library || !i.s.json.optString("url").isEmpty()) anyLeft = true;
                            else if (!i.s.json.optString("infoHash").isEmpty() && !failed.contains(i)) anyLeft = true;
                        }
                        if (!anyLeft && autoRequest()) requestFromArr(true);
                        else if (requestBtn != null) showBanner(App.message(e) + "  Or press \"Get with " + Arr.forType(info.type).name + "\".");
                    }
                    it.row.requestFocus();
                });
    }

    private void start(String url, boolean external) {
        if (external) {
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(Uri.parse(url), "video/*");
            i.putExtra("title", info.title());
            try {
                startActivity(Intent.createChooser(i, "Play with"));
            } catch (ActivityNotFoundException e) {
                Toast.makeText(this, "No other video player installed", Toast.LENGTH_SHORT).show();
            }
        } else {
            startActivity(info.into(new Intent(this, PlayerActivity.class)).putExtra("url", url)
                    .putExtra("source", lastSource));
            // Started straight from Play: Back from the player returns to the title page.
            if (autoQueue != null && getIntent().getBooleanExtra("autoplay", false)) finish();
        }
    }

    private void view(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (ActivityNotFoundException e) {
            showBanner("No app can open this link.");
        }
    }
}
