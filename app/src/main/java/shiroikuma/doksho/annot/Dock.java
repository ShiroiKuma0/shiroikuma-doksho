package shiroikuma.doksho.annot;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.RectF;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;

import androidx.annotation.NonNull;

import shiroikuma.doksho.DokshoViews;

/**
 * Shows an editor beside what it edits: the page scrolls so the target sits near the top, then the
 * editor docks right beneath it (above it when there is no room), leaving the target and the live
 * preview drawn over it in view, with the keyboard up and nothing dimmed.
 */
final class Dock {

    interface Target {
        /** Where the target is now, in the layer's coordinates. */
        @NonNull
        RectF onLayer();
    }

    private Dock() {
    }

    static void show(@NonNull Activity a, @NonNull AnnotSession s, @NonNull AnnotLayer layer, int barBottom,
                     @NonNull final AlertDialog dialog, @NonNull final View box, @NonNull final EditText edit,
                     @NonNull final Target target) {
        final DisplayMetrics dm = a.getResources().getDisplayMetrics();
        RectF r = target.onLayer();
        int top = barBottom + DokshoViews.dp(a, 12);
        int dy = Math.round(r.top - top);
        int dx = 0;
        int m = DokshoViews.dp(a, 8);
        if (r.left < m) dx = Math.round(r.left - m);
        else if (r.right > layer.getWidth() - m) dx = Math.round(Math.min(r.left - m, r.right - layer.getWidth() + m));
        if (dx != 0 || dy != 0) s.scrollBy(dx, dy);
        layer.invalidate();
        layer.post(() -> {
            RectF f = target.onLayer();
            int[] loc = new int[2];
            layer.getLocationOnScreen(loc);
            int width = Math.min(dm.widthPixels - DokshoViews.dp(a, 16), DokshoViews.dp(a, 560));
            box.measure(View.MeasureSpec.makeMeasureSpec(width - DokshoViews.dp(a, 20), View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            int h = box.getMeasuredHeight() + DokshoViews.dp(a, 20);
            int gap = DokshoViews.dp(a, 6);
            int below = loc[1] + Math.round(f.bottom) + gap;
            int above = loc[1] + Math.round(f.top) - gap - h;
            // the keyboard takes roughly the lower 40 % of the screen
            int free = Math.round(dm.heightPixels * 0.6f);
            int y;
            if (below + h <= free || above < loc[1]) y = Math.min(below, dm.heightPixels - h);
            else y = above;
            dialog.show();
            DokshoViews.transparentWindow(dialog);
            Window win = dialog.getWindow();
            if (win != null) {
                WindowManager.LayoutParams lp = win.getAttributes();
                lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
                lp.y = Math.max(0, y);
                lp.width = width;
                lp.dimAmount = 0f;
                win.setAttributes(lp);
                win.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND | WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM);
                win.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
                win.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
                        | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING);
            }
            edit.requestFocus();
        });
    }
}
