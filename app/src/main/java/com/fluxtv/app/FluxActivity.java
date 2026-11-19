package com.fluxtv.app;

import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.FocusFinder;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.animation.DecelerateInterpolator;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Base for Flux's screens: adds the same sidebar as Home. Press ← at the left edge of a screen to open it.
 * Subclasses just call setContentView as usual.
 */
public abstract class FluxActivity extends Activity implements Dock.Host {

    private FrameLayout frame;
    private ViewGroup content;
    private Dock dock;

    /** Which dock entry this screen belongs to (highlighted), or null. */
    protected String sidebarKey() {
        return null;
    }

    @Override public String dockKey() { return sidebarKey(); }

    @Override public void dockGo(String key) { Dock.openScreen(this, key); }

    @Override
    public void setContentView(View view) {
        frame = new FrameLayout(this);
        frame.setBackgroundColor(Ui.BG);
        content = view instanceof ViewGroup ? (ViewGroup) view : null;
        // The dock stays on screen on the left; the screen sits beside it.
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        lp.leftMargin = Ui.dp(this, Dock.RAIL_DP);
        frame.addView(view, lp);
        dock = new Dock(this, this, view);
        frame.addView(dock.view, dock.params());
        super.setContentView(frame);
    }

    // ------------------------------------------------------------ open / close

    protected boolean sidebarOpen() {
        return dock != null && dock.isOpen();
    }

    protected void openSidebar() {
        if (dock != null) dock.open();
    }

    protected void close(boolean restoreFocus) {
        if (dock != null) dock.close(restoreFocus);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (dock != null) dock.rebuildIfChanged();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (dock != null) {
            if (dock.onKey(e)) return true;
            if (!dock.isOpen() && e.getAction() == KeyEvent.ACTION_DOWN && e.getKeyCode() == KeyEvent.KEYCODE_DPAD_LEFT && atLeftEdge()) {
                dock.open();
                return true;
            }
        }
        return super.dispatchKeyEvent(e);
    }

    /** True when ← has nowhere to go on this screen (so it should open the sidebar). */
    private boolean atLeftEdge() {
        View f = getCurrentFocus();
        if (content == null) return false;
        if (f == null) return true;
        if (f instanceof EditText) {
            EditText t = (EditText) f;
            if (t.getSelectionStart() > 0) return false;   // ← moves the text cursor
        }
        boolean inside = false;
        for (ViewParent p = f.getParent(); p != null; p = p.getParent()) {
            if (p == content) { inside = true; break; }
        }
        if (!inside) return false;
        try {
            return Ui.leftLeavesRow(f, FocusFinder.getInstance().findNextFocus(content, f, View.FOCUS_LEFT));
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }
}
