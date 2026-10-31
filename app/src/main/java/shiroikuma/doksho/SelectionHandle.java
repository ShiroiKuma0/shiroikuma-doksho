package shiroikuma.doksho;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * A text-selection handle that reads on any page, white paper or night black: a bar standing at
 * the edge that touches the selection (the exact end — the start handle's right edge, the end
 * handle's left one) and a grip circle beside it, both deep blue inside a white then a black ring.
 * Replaces Librera's small circles, which the skin painted yellow.
 */
public final class SelectionHandle extends Drawable {

    private static final int FILL = 0xFF1E4BD2;

    private final boolean start;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private SelectionHandle(boolean start) {
        this.start = start;
        paint.setStyle(Paint.Style.FILL);
    }

    /** Puts the handle on Librera's anchor view, out of the skin's and the tint's reach. */
    public static void apply(@NonNull ImageView anchor, boolean start) {
        anchor.setColorFilter(null);
        anchor.setImageTintList(null);
        anchor.setImageAlpha(255);
        anchor.setAlpha(1f);
        anchor.setImageDrawable(new SelectionHandle(start));
        anchor.setTag(DokshoViews.NO_SKIN);
        // Librera's layout gives the anchors top margins (100 / 150 dp): a handle shown before it
        // was ever laid out was placed counting from 0, then pushed down by its margin
        if (anchor.getLayoutParams() instanceof android.view.ViewGroup.MarginLayoutParams) {
            android.view.ViewGroup.MarginLayoutParams lp = (android.view.ViewGroup.MarginLayoutParams) anchor.getLayoutParams();
            lp.topMargin = 0;
            lp.leftMargin = 0;
            anchor.setLayoutParams(lp);
        }
    }

    @Override
    public void draw(@NonNull Canvas c) {
        Rect b = getBounds();
        float w = b.width(), h = b.height();
        float unit = Math.max(1f, w / 36f);
        float bar = 3f * unit;
        float edge = start ? b.right - bar / 2 : b.left + bar / 2;
        float r = Math.min(w, h) * 0.3f;
        float cx = start ? b.right - bar - r * 0.9f : b.left + bar + r * 0.9f;
        float cy = b.top + h / 2f;
        int[] rings = {0xFF000000, 0xFFFFFFFF, FILL};
        float[] grow = {2.5f * unit, 1.25f * unit, 0};
        for (int i = 0; i < 3; i++) {
            paint.setColor(rings[i]);
            float g = grow[i];
            c.drawRect(edge - bar / 2 - g, b.top, edge + bar / 2 + g, b.bottom, paint);
            c.drawCircle(cx, cy, r + g, paint);
        }
    }

    @Override
    public void setAlpha(int alpha) {
    }

    @Override
    public void setColorFilter(@Nullable ColorFilter cf) {
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
