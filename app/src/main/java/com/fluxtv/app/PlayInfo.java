package com.fluxtv.app;

import android.content.Intent;

/** What is being watched; passed between the details, streams and player screens. */
public class PlayInfo {
    public String type = "", metaId = "", videoId = "", name = "", poster = "", label = "";

    public static PlayInfo of(String type, String metaId, String videoId, String name, String poster, String label) {
        PlayInfo p = new PlayInfo();
        p.type = type;
        p.metaId = metaId;
        p.videoId = videoId;
        p.name = name;
        p.poster = poster;
        p.label = label == null ? "" : label;
        return p;
    }

    public Intent into(Intent i) {
        return i.putExtra("pi_type", type).putExtra("pi_meta", metaId).putExtra("pi_video", videoId)
                .putExtra("pi_name", name).putExtra("pi_poster", poster).putExtra("pi_label", label);
    }

    public static PlayInfo from(Intent i) {
        return of(s(i, "pi_type"), s(i, "pi_meta"), s(i, "pi_video"), s(i, "pi_name"), s(i, "pi_poster"), s(i, "pi_label"));
    }

    private static String s(Intent i, String k) {
        String v = i.getStringExtra(k);
        return v == null ? "" : v;
    }

    public String title() {
        return label.isEmpty() ? name : name + " · " + label;
    }
}
