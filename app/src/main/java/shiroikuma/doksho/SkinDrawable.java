package shiroikuma.doksho;

import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.xmlpull.v1.XmlPullParser;

/**
 * A window background that draws itself from {@link DokshoUi} every time it is drawn — so a dialog
 * or popup follows the UI page live — and that hands its window to {@link DokshoSkin} on first draw.
 *
 * <p>The {@code doksho} flavour replaces upstream's {@code bg_dialog_round_dark},
 * {@code bg_dialog_round_light} and {@code bg_popup_round} with the three subclasses (custom
 * drawables inflate from XML since API 24, this app's minimum). With the skin switched off each
 * draws exactly what upstream's shape drew.
 */
public abstract class SkinDrawable extends Drawable {

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private float density = 1f;
    /** Upstream's look, for the skin switched off. */
    private int fallbackColor;
    private float fallbackRadiusDp;
    private int fallbackPadTop;
    private int fallbackPadBottom;

    protected SkinDrawable() {
        stroke.setStyle(Paint.Style.STROKE);
    }

    protected abstract int kind();

    /** Upstream's colour and corner for this surface, resolved against the inflating theme. */
    protected abstract void fallback(@NonNull Resources r, @Nullable Resources.Theme theme);

    protected final void setFallback(int color, float radiusDp, int padTopPx, int padBottomPx) {
        fallbackColor = color;
        fallbackRadiusDp = radiusDp;
        fallbackPadTop = padTopPx;
        fallbackPadBottom = padBottomPx;
    }

    @Override
    public void inflate(@NonNull Resources r, @NonNull XmlPullParser parser, @NonNull AttributeSet attrs,
                        @Nullable Resources.Theme theme) {
        density = r.getDisplayMetrics().density;
        fallback(r, theme);
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        Callback cb = getCallback();
        if (cb instanceof View) DokshoSkin.install((View) cb, kind());

        Rect b = getBounds();
        if (!DokshoUi.skin()) {
            fill.setColor(fallbackColor);
            rect.set(b);
            float r = fallbackRadiusDp * density;
            canvas.drawRoundRect(rect, r, r, fill);
            return;
        }
        float w = borderPx();
        float r = DokshoUi.i(DokshoUi.DLG_RADIUS) * density;
        fill.setColor(DokshoUi.i(DokshoUi.C_DLG_BG));
        rect.set(b.left + w / 2f, b.top + w / 2f, b.right - w / 2f, b.bottom - w / 2f);
        canvas.drawRoundRect(rect, r, r, fill);
        if (w > 0) {
            stroke.setStrokeWidth(w);
            stroke.setColor(DokshoUi.i(DokshoUi.C_DLG_BORDER));
            canvas.drawRoundRect(rect, r, r, stroke);
        }
    }

    private float borderPx() {
        int tenths = DokshoUi.i(DokshoUi.DLG_BORDER_W);
        return tenths <= 0 ? 0 : Math.max(1f, tenths / 10f * density);
    }

    @Override
    public boolean getPadding(@NonNull Rect padding) {
        int w = DokshoUi.skin() ? Math.round(borderPx()) : 0;
        padding.set(w, fallbackPadTop + w, w, fallbackPadBottom + w);
        return true;
    }

    @Override
    public void setAlpha(int alpha) {
        fill.setAlpha(alpha);
        stroke.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(@Nullable ColorFilter colorFilter) {
        fill.setColorFilter(colorFilter);
        stroke.setColorFilter(colorFilter);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }

    // ---- the three surfaces ----------------------------------------------------------------------

    /** Replaces {@code bg_dialog_round_dark}: upstream #F2303030, radius @dimen/radius. */
    public static class DialogDark extends SkinDrawable {
        @Override
        protected int kind() {
            return DokshoSkin.KIND_DIALOG;
        }

        @Override
        protected void fallback(@NonNull Resources r, @Nullable Resources.Theme theme) {
            setFallback(0xF2303030, r.getDimension(com.foobnix.pdf.info.R.dimen.radius) / r.getDisplayMetrics().density, 0, 0);
        }
    }

    /** Replaces {@code bg_dialog_round_light}: upstream #F2FAFAFA, radius @dimen/radius. */
    public static class DialogLight extends SkinDrawable {
        @Override
        protected int kind() {
            return DokshoSkin.KIND_DIALOG;
        }

        @Override
        protected void fallback(@NonNull Resources r, @Nullable Resources.Theme theme) {
            setFallback(0xF2FAFAFA, r.getDimension(com.foobnix.pdf.info.R.dimen.radius) / r.getDisplayMetrics().density, 0, 0);
        }
    }

    /** Replaces {@code bg_popup_round}: upstream ?colorBackgroundFloating, @dimen/popup_radius, vertical padding @dimen/dv. */
    public static class Popup extends SkinDrawable {
        @Override
        protected int kind() {
            return DokshoSkin.KIND_POPUP;
        }

        @Override
        protected void fallback(@NonNull Resources r, @Nullable Resources.Theme theme) {
            int color = 0xFF303030;
            if (theme != null) {
                TypedArray a = theme.obtainStyledAttributes(new int[]{android.R.attr.colorBackgroundFloating});
                color = a.getColor(0, color);
                a.recycle();
            }
            int dv = r.getDimensionPixelSize(com.foobnix.pdf.info.R.dimen.dv);
            setFallback(color, r.getDimension(com.foobnix.pdf.info.R.dimen.popup_radius) / r.getDisplayMetrics().density, dv, dv);
        }
    }
}
