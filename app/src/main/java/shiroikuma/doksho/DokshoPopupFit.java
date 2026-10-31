package shiroikuma.doksho;

import android.view.View;
import android.view.ViewGroup;
import android.widget.ScrollView;

import androidx.annotation.NonNull;

/**
 * Librera's draggable popups ({@code DragingPopup}) open at a fixed height (400 dp for the text
 * popup), so content taller than that is cut at the bottom. A popup whose content is a ScrollView
 * is fitted to the content's full height instead — at most the screen's — and moved up when it
 * would run off the bottom. Popups holding lists keep Librera's size: a list cannot be measured
 * whole, and scrolls inside it anyway.
 */
public final class DokshoPopupFit {

    private DokshoPopupFit() {
    }

    public static void fit(@NonNull final View anchor, @NonNull final View popup, @NonNull final ViewGroup content,
                           @NonNull final View header) {
        popup.post(() -> {
            if (content.getChildCount() == 0 || !(content.getChildAt(0) instanceof ScrollView)) return;
            ScrollView sv = (ScrollView) content.getChildAt(0);
            if (sv.getChildCount() == 0) return;
            View inner = sv.getChildAt(0);
            int w = content.getWidth() - sv.getPaddingLeft() - sv.getPaddingRight();
            if (w <= 0) return;
            inner.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.AT_MOST),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            int want = popup.getPaddingTop() + popup.getPaddingBottom() + header.getHeight()
                    + sv.getPaddingTop() + sv.getPaddingBottom() + inner.getMeasuredHeight();
            View parent = (View) anchor.getParent();
            int margin = DokshoViews.dp(popup.getContext(), 8);
            int room = (parent != null ? parent.getHeight() : popup.getRootView().getHeight()) - margin * 2;
            int h = Math.min(want, room);
            if (h <= 0) return;
            ViewGroup.LayoutParams lp = popup.getLayoutParams();
            if (lp.height != h) {
                lp.height = h;
                popup.setLayoutParams(lp);
            }
            float bottom = anchor.getY() + h + margin;
            int limit = parent != null ? parent.getHeight() : popup.getRootView().getHeight();
            if (bottom > limit) anchor.setY(Math.max(margin, limit - h - margin));
            if (anchor.getY() < margin) anchor.setY(margin);
        });
    }
}
