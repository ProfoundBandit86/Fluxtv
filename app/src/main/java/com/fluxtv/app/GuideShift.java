package com.fluxtv.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.DateFormat;
import java.util.Date;
import java.util.List;

/**
 * "Guide times…": moves a channel's TV guide earlier or later (or every channel from the provider) when the
 * provider's guide is off, usually by a time-zone difference. Shows what the guide says is on now, so you can
 * line it up with what's actually playing.
 */
final class GuideShift {

    private GuideShift() { }

    static void show(Activity a, Iptv.Source s, Iptv.Channel c, Runnable changed) {
        DateFormat tf = android.text.format.DateFormat.getTimeFormat(a);
        LinearLayout box = Ui.vertical(a);
        box.setPadding(Ui.dp(a, 24), Ui.dp(a, 8), Ui.dp(a, 24), Ui.dp(a, 4));
        TextView now = Ui.text(a, "", 15, Ui.TEXT);
        now.setLineSpacing(0, 1.2f);
        box.addView(now);
        TextView amounts = Ui.text(a, "", 13, Ui.MUTED);
        amounts.setPadding(0, Ui.dp(a, 8), 0, Ui.dp(a, 6));
        box.addView(amounts);
        boolean[] whole = {false};
        LinearLayout scope = Ui.horizontal(a);
        box.addView(scope);
        LinearLayout moves = Ui.horizontal(a);
        moves.setGravity(Gravity.CENTER_VERTICAL);
        box.addView(moves);

        Runnable[] refresh = new Runnable[1];
        java.util.function.IntConsumer move = d -> {
            if (whole[0]) Iptv.setSourceShift(s, Iptv.sourceShift(s) + d);
            else Iptv.setChannelShift(s, c, Iptv.channelShift(s, c) + d);
            refresh[0].run();
            if (changed != null) changed.run();
        };
        refresh[0] = () -> {
            long t = System.currentTimeMillis();
            List<Iptv.Program> progs = Iptv.programs(s, c);
            if (progs.isEmpty()) progs = Iptv.nowNext(s, c);
            Iptv.Program p = GuideView.at(progs, t);
            now.setText(p == null ? "The guide has nothing for right now on " + c.name + "."
                    : "The guide says on now:\n" + p.title + "   " + tf.format(new Date(p.start)) + " – " + tf.format(new Date(p.end)));
            amounts.setText("This channel: " + label(Iptv.channelShift(s, c)) + "   ·   Every channel from " + s.name + ": "
                    + label(Iptv.sourceShift(s)));
            // Rebuilt to show which is chosen; focus stays on the one just picked.
            boolean onScope = scope.findFocus() != null;
            int at = onScope ? scope.indexOfChild(scope.findFocus()) : -1;
            scope.removeAllViews();
            scope.addView(Ui.pill(a, "Just this channel", !whole[0], v -> { whole[0] = false; refresh[0].run(); }));
            scope.addView(Ui.pill(a, "Every channel from " + s.name, whole[0], v -> { whole[0] = true; refresh[0].run(); }));
            if (at >= 0 && at < scope.getChildCount()) scope.getChildAt(at).requestFocus();
        };
        moves.addView(Ui.button(a, "− 1 h", v -> move.accept(-60)));
        moves.addView(Ui.button(a, "− 15 min", v -> move.accept(-15)));
        moves.addView(Ui.button(a, "+ 15 min", v -> move.accept(15)));
        moves.addView(Ui.button(a, "+ 1 h", v -> move.accept(60)));
        moves.addView(Ui.button(a, "Reset", v -> {
            if (whole[0]) Iptv.setSourceShift(s, 0); else Iptv.setChannelShift(s, c, 0);
            refresh[0].run();
            if (changed != null) changed.run();
        }));
        refresh[0].run();
        AlertDialog d = new AlertDialog.Builder(a).setTitle("Guide times · " + c.name).setView(box)
                .setPositiveButton("Done", null).create();
        d.show();
        if (moves.getChildCount() > 2) moves.getChildAt(2).post(() -> moves.getChildAt(2).requestFocus());   // "+ 15 min"
    }

    static String label(int minutes) {
        if (minutes == 0) return "no change";
        int m = Math.abs(minutes);
        String amount = (m >= 60 ? (m / 60) + " h" + (m % 60 > 0 ? " " : "") : "") + (m % 60 > 0 ? (m % 60) + " min" : "");
        return (minutes > 0 ? "later by " : "earlier by ") + amount;
    }
}
