package com.fluxtv.app;

import android.content.Context;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.HorizontalScrollView;
import android.widget.ScrollView;

/**
 * Scroll views for the remote: the focused item (plus a margin for its focus "grow")
 * always ends fully on screen, and scrolling is animated instead of jumping.
 */
public final class FocusScroll {

    private FocusScroll() { }

    /** Vertical. Snaps the focused item's row (tag "row", or "group" if inside one) fully into view. */
    public static class Vertical extends ScrollView {
        private final int margin;

        public Vertical(Context c) {
            super(c);
            margin = Ui.dp(c, 14);
            setSmoothScrollingEnabled(true);
            setVerticalScrollBarEnabled(false);
            setClipChildren(false);
        }

        @Override
        protected int computeScrollDeltaToGetChildRectOnScreen(Rect rect) {
            return 0;   // we scroll ourselves (smoothly) in requestChildFocus
        }

        @Override
        public void requestChildFocus(View child, View focused) {
            super.requestChildFocus(child, focused);
            post(() -> reveal(focused));
        }

        void reveal(View focused) {
            if (focused == null || !isAncestorOf(this, focused)) return;
            View target = focused;
            View group = null, row = null;
            for (ViewParent p = focused.getParent(); p instanceof View && p != this; p = p.getParent()) {
                View v = (View) p;
                if (row == null && "row".equals(v.getTag())) row = v;
                if (group == null && "group".equals(v.getTag())) group = v;
            }
            View block = group != null ? group : row;
            int top, bottom;
            Rect r = new Rect();
            if (block != null && block.getHeight() <= getHeight() - 2 * margin) {
                block.getDrawingRect(r);
                offsetDescendantRectToMyCoords(block, r);
            } else {
                target.getDrawingRect(r);
                offsetDescendantRectToMyCoords(target, r);
            }
            top = r.top - margin;
            bottom = r.bottom + margin;
            int y = getScrollY(), h = getHeight();
            int dest = y;
            if (top < y) dest = top;
            else if (bottom > y + h) dest = Math.min(top, bottom - h);
            dest = Math.max(0, dest);
            if (dest != y) smoothScrollTo(0, dest);
        }
    }

    /** Horizontal rows: keeps the focused card fully visible with room for its focus grow. */
    public static class Horizontal extends HorizontalScrollView {
        private final int margin;

        public Horizontal(Context c) {
            super(c);
            margin = Ui.dp(c, 24);
            setSmoothScrollingEnabled(true);
            setHorizontalScrollBarEnabled(false);
            setClipChildren(false);
            setClipToPadding(false);
        }

        @Override
        protected int computeScrollDeltaToGetChildRectOnScreen(Rect rect) {
            return 0;
        }

        @Override
        public void requestChildFocus(View child, View focused) {
            super.requestChildFocus(child, focused);
            post(() -> {
                if (focused == null || !isAncestorOf(this, focused)) return;
                Rect r = new Rect();
                focused.getDrawingRect(r);
                offsetDescendantRectToMyCoords(focused, r);
                int left = r.left - margin, right = r.right + margin;
                int x = getScrollX(), w = getWidth();
                int dest = x;
                if (left < x) dest = left;
                else if (right > x + w) dest = right - w;
                dest = Math.max(0, dest);
                if (r.left - margin <= 0) dest = 0;   // first card: keep the row's left margin visible
                if (dest != x) smoothScrollTo(dest, 0);
            });
        }
    }

    static boolean isAncestorOf(ViewGroup parent, View v) {
        for (ViewParent p = v.getParent(); p != null; p = p.getParent()) if (p == parent) return true;
        return false;
    }
}
