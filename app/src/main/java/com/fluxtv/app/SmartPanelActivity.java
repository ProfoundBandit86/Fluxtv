package com.fluxtv.app;

import android.app.Activity;
import android.os.Bundle;

/**
 * Opens the smart home panel: as an overlay over the current app when Flux may draw over other apps,
 * otherwise as a see-through screen on top. (Launcher entry "Flux Smart Home", fluxtv://smarthome, Quick Settings.)
 */
public class SmartPanelActivity extends Activity {
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (SmartPanel.showing()) {
            SmartPanel.hide();   // the same button toggles it
            finish();
            return;
        }
        if (SmartPanel.showOverlay(this)) {
            finish();
            overridePendingTransition(0, 0);
            return;
        }
        setContentView(SmartPanel.build(this, this::finish));
    }

    @Override
    public void finish() {
        super.finish();
        overridePendingTransition(0, 0);
    }
}
