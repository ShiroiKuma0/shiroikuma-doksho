package shiroikuma.doksho;

import android.graphics.Color;

import com.foobnix.model.AppState;
import com.foobnix.pdf.info.AppsConfig;

/**
 * The reader's night mode, 白い熊-style: a duotone. Every page pixel is placed by its darkness on the
 * line from the night background to the night text colour — black ink becomes full yellow, white
 * paper full black, and every grey the proportional step between (no threshold, so anti-aliased
 * glyphs stay smooth). A black-and-white book therefore turns entirely into yellow on black.
 *
 * <p>The strength blends it with Librera's own night rendering (a plain inversion): 100 % is the
 * pure duotone, lower keeps some of a colour picture's own hues, 0 leaves Librera's night mode as
 * upstream has it. Colours and strength are set on the 白い熊 読書 UI page ("Reading → Night mode").
 *
 * <p>Hooked into Librera's page pipeline at {@code MagicHelper} (the colours and the recolour pass),
 * {@code MuPdfPage} (render un-inverted, then recolour) and {@code RawBitmap.invert} (no second
 * inversion on top).
 */
public final class DokshoNight {

    private static int lutStamp = -1;
    private static final int[] LUT_R = new int[256];
    private static final int[] LUT_G = new int[256];
    private static final int[] LUT_B = new int[256];

    private DokshoNight() {
    }

    /** Set on the rendering thread while a library cover is drawn: covers keep their own colours. */
    private static final ThreadLocal<Boolean> COVER = new ThreadLocal<>();

    public static void coverBegin() {
        COVER.set(Boolean.TRUE);
    }

    public static void coverEnd() {
        COVER.remove();
    }

    /** True while a page is being drawn in our night mode. */
    public static boolean active() {
        return AppsConfig.IS_DOKSHO && !AppState.get().isDayNotInvert && DokshoUi.i(DokshoUi.NIGHT_STRENGTH) > 0
                && COVER.get() == null;
    }

    public static int text() {
        return DokshoUi.i(DokshoUi.C_NIGHT_TEXT);
    }

    public static int bg() {
        return DokshoUi.i(DokshoUi.C_NIGHT_BG);
    }

    private static synchronized void lut() {
        int stamp = DokshoUi.stamp();
        if (stamp == lutStamp) return;
        int t = text(), b = bg();
        for (int ink = 0; ink < 256; ink++) {
            LUT_R[ink] = Color.red(b) + (Color.red(t) - Color.red(b)) * ink / 255;
            LUT_G[ink] = Color.green(b) + (Color.green(t) - Color.green(b)) * ink / 255;
            LUT_B[ink] = Color.blue(b) + (Color.blue(t) - Color.blue(b)) * ink / 255;
        }
        lutStamp = stamp;
    }

    /** Recolour a rendered (day-coloured, un-inverted) page in place. */
    public static void apply(int[] pixels) {
        lut();
        int s = DokshoUi.i(DokshoUi.NIGHT_STRENGTH);
        int keep = 100 - s;
        for (int i = 0; i < pixels.length; i++) {
            int c = pixels[i];
            int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
            int ink = 255 - (r * 299 + g * 587 + b * 114) / 1000;
            int dr = LUT_R[ink], dg = LUT_G[ink], db = LUT_B[ink];
            if (keep > 0) {
                // blend with the plain inversion Librera's night mode would show
                dr = (dr * s + (255 - r) * keep) / 100;
                dg = (dg * s + (255 - g) * keep) / 100;
                db = (db * s + (255 - b) * keep) / 100;
            }
            pixels[i] = 0xFF000000 | (dr << 16) | (dg << 8) | db;
        }
    }
}
