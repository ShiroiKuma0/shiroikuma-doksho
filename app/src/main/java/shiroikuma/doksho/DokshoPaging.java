package shiroikuma.doksho;

import android.graphics.Matrix;

import com.foobnix.model.AppSP;
import com.foobnix.model.AppState;

/**
 * Book mode (Librera's page-by-page reader, {@code PageImaveView} inside the horizontal pager) as
 * 白い熊 reads a PDF:
 *
 * <ul>
 * <li>the page never leaves the screen — panning stops at the page's edges, in both directions;</li>
 * <li>a page wider than the screen (zoomed in) pans left / right under a sideways swipe instead of
 * the pager flipping to the next page;</li>
 * <li>a vertical swipe scrolls the page, and once the page's top or bottom edge is reached, pulling
 * further turns to the previous / next page — which opens at its top (or bottom, going back).</li>
 * </ul>
 *
 * <p>Applies only to an unlocked page in the horizontal pager ({@code rotateViewPager == 0}); a
 * locked page (text formats, the lock button) keeps upstream's own swipes. The switch is
 * {@link DokshoUi#EDGE_PAGING}; the colour around the page is {@link DokshoUi#C_PAGE_SURROUND}.
 */
public final class DokshoPaging {

    /** How far past the page's edge a vertical pull must go to turn the page. */
    public static final float PULL_DP = 56f;

    /** The page on screen is wider than the view — the pager must leave sideways swipes to it. */
    private static volatile boolean wide;

    private DokshoPaging() {
    }

    /** The colour around the page — book mode and scroll mode alike. */
    public static int surround() {
        return DokshoUi.i(DokshoUi.C_PAGE_SURROUND);
    }

    public static boolean on() {
        return DokshoUi.b(DokshoUi.EDGE_PAGING) && !AppSP.get().isLocked && AppState.get().rotateViewPager == 0;
    }

    /** Called by the page on screen whenever it draws. */
    public static void noteWide(Matrix m, int viewW, int drawableW) {
        wide = drawableW > 0 && drawableW * scale(m) > viewW + 1;
    }

    public static boolean isWide(Matrix m, int viewW, int drawableW) {
        return drawableW > 0 && drawableW * scale(m) > viewW + 1;
    }

    /** True while the pager must not take a sideways swipe away from the page. */
    public static boolean blocksPager() {
        return wide && on();
    }

    private static float scale(Matrix m) {
        float[] v = new float[9];
        m.getValues(v);
        return v[Matrix.MSCALE_X];
    }

    /** The translation range of one axis: the page stays fully on screen, or fully covers it. */
    private static float clampAxis(float t, float view, float page) {
        float lo = Math.min(0, view - page);
        float hi = Math.max(0, view - page);
        return Math.max(lo, Math.min(hi, t));
    }

    /** Pull the page back inside its edges; returns true when it had to move. */
    public static boolean clamp(Matrix m, int viewW, int viewH, int drawableW, int drawableH) {
        if (viewW <= 0 || viewH <= 0 || drawableW <= 0 || drawableH <= 0) return false;
        float[] v = new float[9];
        m.getValues(v);
        float s = v[Matrix.MSCALE_X];
        float tx = clampAxis(v[Matrix.MTRANS_X], viewW, drawableW * s);
        float ty = clampAxis(v[Matrix.MTRANS_Y], viewH, drawableH * s);
        float dx = tx - v[Matrix.MTRANS_X];
        float dy = ty - v[Matrix.MTRANS_Y];
        if (dx == 0 && dy == 0) return false;
        m.postTranslate(dx, dy);
        return true;
    }

    /**
     * Move the page by (dx, dy) as far as its edges allow. Returns {movedX, movedY, leftoverY}: what
     * was applied and the part of the vertical move the page's edge stopped — the pull.
     */
    public static float[] pan(Matrix m, float dx, float dy, int viewW, int viewH, int drawableW, int drawableH) {
        float[] v = new float[9];
        m.getValues(v);
        float s = v[Matrix.MSCALE_X];
        float tx0 = v[Matrix.MTRANS_X];
        float ty0 = v[Matrix.MTRANS_Y];
        // A page already outside its range (pinched off, old state) is first brought back.
        float txBase = clampAxis(tx0, viewW, drawableW * s);
        float tyBase = clampAxis(ty0, viewH, drawableH * s);
        float tx = clampAxis(txBase + dx, viewW, drawableW * s);
        float ty = clampAxis(tyBase + dy, viewH, drawableH * s);
        m.postTranslate(tx - tx0, ty - ty0);
        return new float[]{tx - tx0, ty - ty0, dy - (ty - tyBase)};
    }

    /**
     * Before turning by {@code dir} (+1 next, -1 previous): place the shared page matrix so the
     * new page opens at its top (next) or bottom (previous), keeping the sideways position.
     */
    public static void alignForTurn(Matrix m, int dir, int viewH, int drawableH) {
        float[] v = new float[9];
        m.getValues(v);
        float ph = drawableH * v[Matrix.MSCALE_X];
        if (ph <= viewH) return;
        float ty = dir > 0 ? 0 : viewH - ph;
        m.postTranslate(0, ty - v[Matrix.MTRANS_Y]);
    }

    /**
     * Scroll mode (VScrollController): the horizontal scroll range of the page column. Pages span
     * x = 0 … width × zoom; wider than the view, the view moves between the page's own edges;
     * narrower, the page stays centred.
     */
    public static int[] scrollLimitsX(int width, float zoom) {
        int over = (int) (width * zoom) - width;
        if (over >= 0) {
            return new int[]{0, over};
        }
        return new int[]{over / 2, over / 2};
    }

    /**
     * Books open in Scroll mode — pages rolling by as one film — rather than Librera's default
     * Book mode. Done once, the first time a book opens; switching back stays switched back.
     */
    public static void filmOnce() {
        if (DokshoUi.b(DokshoUi.FILM_DEFAULT_DONE)) return;
        DokshoUi.set(DokshoUi.FILM_DEFAULT_DONE, true);
        if (AppSP.get().readingMode == AppState.READING_MODE_BOOK) {
            AppSP.get().readingMode = AppState.READING_MODE_SCROLL;
        }
    }
}
