package com.fluxtv.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * TiviMate-style TV guide: channels down the left, programmes across a timeline, drawn in one view so
 * thousands of channels scroll smoothly. ↑↓ change channel (keeping the time), ←→ move between programmes.
 */
public class GuideView extends View {

    public static final class Entry {
        public final Iptv.Source source;
        public final Iptv.Channel channel;

        public Entry(Iptv.Source s, Iptv.Channel c) {
            source = s;
            channel = c;
        }
    }

    public interface Listener {
        void onSelect(Entry e, Iptv.Program p);
        void onClick(Entry e, Iptv.Program p);
        void onLongClick(Entry e, Iptv.Program p);
        /** ← pressed on the first programme (open the groups menu). */
        void onLeftEdge();
    }

    private static final long HALF_HOUR = 30 * 60 * 1000L;

    private final List<Entry> entries = new ArrayList<>();
    private Listener listener;
    private int sel, top;
    private long windowStart, focusTime;

    private final float d;
    private final int rowH, headH, chanW, logoW;
    private final float pxPerMs;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint title = new TextPaint(Paint.ANTI_ALIAS_FLAG), small = new TextPaint(Paint.ANTI_ALIAS_FLAG),
            num = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final Rect src = new Rect();
    private final Set<String> asked = new HashSet<>();
    private final DateFormat timeFmt;

    public GuideView(Context c) {
        super(c);
        d = c.getResources().getDisplayMetrics().density;
        rowH = (int) (58 * d);
        headH = (int) (34 * d);
        chanW = (int) (236 * d);
        logoW = (int) (54 * d);
        pxPerMs = 6 * d / 60000f;   // 6dp per minute: about two hours across
        title.setTextSize(15 * d * c.getResources().getConfiguration().fontScale);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        small.setTextSize(12 * d);
        num.setTextSize(13 * d);
        num.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        timeFmt = android.text.format.DateFormat.getTimeFormat(c);
        setFocusable(true);
        setFocusableInTouchMode(true);
        resetTime();
    }

    public void setListener(Listener l) {
        listener = l;
    }

    public void setEntries(List<Entry> list, int select) {
        entries.clear();
        entries.addAll(list);
        sel = Math.max(0, Math.min(select, entries.size() - 1));
        top = Math.max(0, sel - 2);
        resetTime();
        clampTop();
        invalidate();
        fire();
    }

    public int selectedIndex() {
        return sel;
    }

    public Entry selected() {
        return sel < entries.size() ? entries.get(sel) : null;
    }

    private void resetTime() {
        long now = System.currentTimeMillis();
        windowStart = now - now % HALF_HOUR;
        focusTime = now;
    }

    /** Called every minute so the "now" line and the current programme move on. */
    public void tick() {
        long now = System.currentTimeMillis();
        if (focusTime >= now - 120_000) {   // browsing catch-up in the past stays put
            if (focusTime < now) focusTime = now;
            if (windowStart + HALF_HOUR <= now - now % HALF_HOUR - HALF_HOUR) windowStart = now - now % HALF_HOUR;
        }
        invalidate();
    }

    private int visibleRows() {
        return Math.max(1, (getHeight() - headH) / rowH);
    }

    private long span() {
        return (long) ((getWidth() - chanW) / pxPerMs);
    }

    private void clampTop() {
        int vis = getHeight() > 0 ? visibleRows() : 6;
        if (sel < top) top = sel;
        if (sel >= top + vis) top = sel - vis + 1;
        top = Math.max(0, Math.min(top, Math.max(0, entries.size() - vis)));
    }

    // ------------------------------------------------------------ programmes

    /** Programmes for an entry; a gap-free list where unknown times are "No information" blocks. */
    private List<Iptv.Program> programs(Entry e) {
        return Iptv.programs(e.source, e.channel);
    }

    /** The programme at a time (null = no information). */
    public static Iptv.Program at(List<Iptv.Program> list, long t) {
        for (Iptv.Program p : list) if (p.start <= t && p.end > t) return p;
        return null;
    }

    private Iptv.Program focused() {
        Entry e = selected();
        return e == null ? null : at(programs(e), focusTime);
    }

    // ------------------------------------------------------------ keys

    @Override
    public boolean onKeyDown(int code, KeyEvent ev) {
        if (entries.isEmpty()) return super.onKeyDown(code, ev);
        long now = System.currentTimeMillis();
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (sel >= entries.size() - 1) return false;
                sel++;
                break;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (sel == 0) return false;
                sel--;
                break;
            case KeyEvent.KEYCODE_PAGE_DOWN:
            case KeyEvent.KEYCODE_CHANNEL_DOWN:
                sel = Math.min(entries.size() - 1, sel + visibleRows());
                break;
            case KeyEvent.KEYCODE_PAGE_UP:
            case KeyEvent.KEYCODE_CHANNEL_UP:
                sel = Math.max(0, sel - visibleRows());
                break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: {
                Iptv.Program p = focused();
                long next = p != null ? p.end : nextStart(focusTime);
                if (next <= 0 || next > now + 47L * 3600 * 1000) next = focusTime + HALF_HOUR;
                focusTime = next;
                while (focusTime >= windowStart + span() - HALF_HOUR / 2) windowStart += HALF_HOUR;
                break;
            }
            case KeyEvent.KEYCODE_DPAD_LEFT: {
                Iptv.Program p = focused();
                long start = p != null ? p.start : focusTime - HALF_HOUR;
                Entry se = selected();
                // Channels with catch-up can go back into the past (replays); others stop at now.
                long earliest = se != null && se.channel.archiveDays > 0 ? now - se.channel.archiveDays * 86_400_000L : now;
                if (start <= earliest || (earliest >= now && (start <= now || focusTime <= now))) {
                    if (focusTime > now) { focusTime = now; windowStart = now - now % HALF_HOUR; break; }
                    if (listener != null) listener.onLeftEdge();
                    return true;
                }
                Iptv.Program prev = at(programs(se), start - 1);
                focusTime = Math.max(earliest, prev != null ? Math.max(prev.start, earliest) : start - HALF_HOUR);
                if (earliest >= now) focusTime = Math.max(now, focusTime);
                while (focusTime < windowStart) windowStart -= HALF_HOUR;
                if (earliest >= now && windowStart < now - now % HALF_HOUR) windowStart = now - now % HALF_HOUR;
                break;
            }
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                ev.startTracking();
                return true;
            default:
                return super.onKeyDown(code, ev);
        }
        clampPast(now);
        clampTop();
        invalidate();
        fire();
        return true;
    }

    /** Moving onto a channel without catch-up while looking at the past: back to now. */
    private void clampPast(long now) {
        Entry e = selected();
        if (focusTime >= now - 120_000 || e == null) return;
        long earliest = e.channel.archiveDays > 0 ? now - e.channel.archiveDays * 86_400_000L : now;
        if (focusTime < earliest) {
            focusTime = earliest >= now ? now : earliest;
            windowStart = focusTime - focusTime % HALF_HOUR;
        }
    }

    private boolean longPressed;

    @Override
    public boolean onKeyLongPress(int code, KeyEvent ev) {
        if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) {
            longPressed = true;
            if (listener != null && selected() != null) listener.onLongClick(selected(), focused());
            return true;
        }
        return super.onKeyLongPress(code, ev);
    }

    @Override
    public boolean onKeyUp(int code, KeyEvent ev) {
        if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER) {
            if (longPressed) { longPressed = false; return true; }
            if (listener != null && selected() != null) listener.onClick(selected(), focused());
            return true;
        }
        return super.onKeyUp(code, ev);
    }

    private long nextStart(long t) {
        Entry e = selected();
        if (e == null) return 0;
        for (Iptv.Program p : programs(e)) if (p.start > t) return p.start;
        return 0;
    }

    private void fire() {
        if (listener != null && selected() != null) listener.onSelect(selected(), focused());
    }

    /** Jump to a channel (e.g. typed number). */
    public void select(int index) {
        if (index < 0 || index >= entries.size()) return;
        sel = index;
        clampTop();
        invalidate();
        fire();
    }

    // ------------------------------------------------------------ drawing

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        clampTop();
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        long now = System.currentTimeMillis(), end = windowStart + span();
        boolean focusedView = hasFocus();

        // Timeline header
        small.setColor(Ui.MUTED);
        small.setTypeface(Typeface.DEFAULT_BOLD);
        String day = android.text.format.DateUtils.formatDateTime(getContext(), windowStart,
                android.text.format.DateUtils.FORMAT_SHOW_WEEKDAY | android.text.format.DateUtils.FORMAT_ABBREV_WEEKDAY);
        c.drawText(windowStart - (now - now % HALF_HOUR) < 12 * 3600 * 1000L && sameDay(windowStart, now) ? "Today" : day,
                12 * d, headH - 11 * d, small);
        small.setTypeface(Typeface.DEFAULT);
        for (long t = windowStart; t < end; t += HALF_HOUR) {
            float x = chanW + (t - windowStart) * pxPerMs;
            fill.setColor(Ui.alpha(Ui.TEXT, 0.12f));
            c.drawRect(x, headH - 10 * d, x + d, headH, fill);
            small.setColor(Ui.MUTED);
            c.drawText(timeFmt.format(new Date(t)), x + 6 * d, headH - 13 * d, small);
        }

        int vis = visibleRows();
        for (int i = 0; i <= vis && top + i < entries.size(); i++) {
            int row = top + i;
            Entry e = entries.get(row);
            float y = headH + i * rowH;
            boolean selRow = row == sel;

            // Channel cell
            r.set(4 * d, y + 2 * d, chanW - 4 * d, y + rowH - 2 * d);
            fill.setColor(selRow ? Ui.alpha(Ui.TEXT, focusedView ? 0.16f : 0.10f) : Ui.alpha(Ui.TEXT, 0.04f));
            c.drawRoundRect(r, 8 * d, 8 * d, fill);
            num.setColor(selRow ? Ui.TEXT : Ui.MUTED);
            String n = e.channel.num;
            c.drawText(n, 12 * d, y + rowH / 2f + 5 * d, num);
            float lx = 52 * d;
            drawLogo(c, e.channel.logo, lx, y + 7 * d, logoW, rowH - 14 * d);
            title.setColor(Ui.TEXT);
            float nx = lx + logoW + 10 * d;
            boolean rec = Dvr.recordingNow(e.source, e.channel);
            String name = TextUtils.ellipsize(e.channel.name, title, chanW - nx - (rec ? 22 * d : 10 * d), TextUtils.TruncateAt.END).toString();
            c.drawText(name, nx, y + rowH / 2f + 5 * d, title);
            if (rec) {
                fill.setColor(0xFFE53935);
                c.drawCircle(chanW - 16 * d, y + rowH / 2f, 5 * d, fill);
            }

            // Programmes
            c.save();
            c.clipRect(chanW, y, w, y + rowH);
            List<Iptv.Program> list = programs(e);
            boolean any = false;
            for (Iptv.Program p : list) {
                if (p.end <= windowStart || p.start >= end) continue;
                any = true;
                drawCell(c, p.start, p.end, (p.end <= now && Iptv.canReplay(e.channel, p) ? "↺ " : "") + p.title, y, selRow && p.start <= focusTime && p.end > focusTime, focusedView,
                        p.start <= now && p.end > now, Dvr.find(e.source, e.channel, p.start) != null, now);
            }
            if (!any) drawCell(c, windowStart, end, "No information", y, selRow, focusedView, false, false, now);
            c.restore();
        }

        // "Now" line
        if (now >= windowStart && now < end) {
            float x = chanW + (now - windowStart) * pxPerMs;
            fill.setColor(Ui.ACCENT);
            c.drawRect(x - d, headH - 6 * d, x + d, h, fill);
            c.drawCircle(x, headH - 6 * d, 4 * d, fill);
        }
    }

    private void drawCell(Canvas c, long start, long stop, String text, float y, boolean focusedCell, boolean viewFocused,
                          boolean live, boolean scheduled, long now) {
        float x1 = chanW + Math.max(0, start - windowStart) * pxPerMs + 2 * d;
        float x2 = chanW + (stop - windowStart) * pxPerMs - 2 * d;
        if (x2 - x1 < 2 * d) return;
        r.set(x1, y + 2 * d, x2, y + rowH - 2 * d);
        int bg = focusedCell ? (viewFocused ? Ui.TEXT : Ui.alpha(Ui.TEXT, 0.3f))
                : live ? Ui.alpha(Ui.TEXT, 0.11f) : Ui.alpha(Ui.TEXT, 0.06f);
        fill.setColor(bg);
        c.drawRoundRect(r, 8 * d, 8 * d, fill);
        if (live && !focusedCell && stop > start) {
            // progress of what's on now
            float px = x1 + (x2 - x1) * Math.min(1f, (now - start) / (float) (stop - start));
            fill.setColor(Ui.alpha(Ui.ACCENT, 0.5f));
            c.drawRect(x1 + 6 * d, y + rowH - 6 * d, Math.max(x1 + 6 * d, px - 6 * d), y + rowH - 4 * d, fill);
        }
        int fg = focusedCell && viewFocused ? Ui.BG : Ui.TEXT;
        float pad = 10 * d, tx = Math.max(x1, chanW) + pad;
        float room = x2 - tx - pad - (scheduled ? 16 * d : 0);
        if (room > 12 * d) {
            title.setColor(fg);
            String t = TextUtils.ellipsize(text, title, room, TextUtils.TruncateAt.END).toString();
            c.drawText(t, tx, y + rowH / 2f - 1 * d, title);
            small.setColor(focusedCell && viewFocused ? Ui.alpha(Ui.BG, 0.7f) : Ui.MUTED);
            String time = timeFmt.format(new Date(start)) + " – " + timeFmt.format(new Date(stop));
            if (text.equals("No information")) time = "";
            c.drawText(TextUtils.ellipsize(time, small, room, TextUtils.TruncateAt.END).toString(), tx, y + rowH / 2f + 15 * d, small);
        }
        if (scheduled) {
            fill.setColor(0xFFE53935);
            c.drawCircle(x2 - 12 * d, y + 14 * d, 4.5f * d, fill);
        }
    }

    private void drawLogo(Canvas c, String url, float x, float y, float w, float h) {
        if (url == null || url.isEmpty()) return;
        Bitmap b = ImageLoader.cached(url);
        if (b == null) {
            if (asked.add(url)) ImageLoader.bitmap(url, (int) (w * 1.5f), bm -> invalidate());
            return;
        }
        float s = Math.min(w / b.getWidth(), h / b.getHeight());
        float bw = b.getWidth() * s, bh = b.getHeight() * s;
        src.set(0, 0, b.getWidth(), b.getHeight());
        r.set(x + (w - bw) / 2, y + (h - bh) / 2, x + (w + bw) / 2, y + (h + bh) / 2);
        fill.setColor(0xFFFFFFFF);
        c.drawBitmap(b, src, r, fill);
    }

    private static boolean sameDay(long a, long b) {
        java.util.Calendar x = java.util.Calendar.getInstance(), y = java.util.Calendar.getInstance();
        x.setTimeInMillis(a);
        y.setTimeInMillis(b);
        return x.get(java.util.Calendar.DAY_OF_YEAR) == y.get(java.util.Calendar.DAY_OF_YEAR) && x.get(java.util.Calendar.YEAR) == y.get(java.util.Calendar.YEAR);
    }
}
