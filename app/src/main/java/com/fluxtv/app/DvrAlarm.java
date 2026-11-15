package com.fluxtv.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Wakes Flux for a scheduled recording; after a restart, re-arms all of them. */
public class DvrAlarm extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        String a = i.getAction();
        if ("com.fluxtv.app.DVR".equals(a)) {
            String id = i.getStringExtra("id");
            if (id != null) DvrService.start(c, id);
        } else {
            Dvr.rearm(c);   // BOOT_COMPLETED / app updated
        }
    }
}
