package shiroikuma.doksho.annot;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;

import androidx.annotation.NonNull;

import com.foobnix.model.AppState;

import org.ebookdroid.core.Page;

import java.util.ArrayList;
import java.util.List;

import shiroikuma.doksho.DokshoNight;

/**
 * A form field's text drawn over the page while it is being typed: the real size at the current
 * zoom, in the chosen font and colour, laid out exactly as {@code doksho_annot.c} (dk_layout) — and
 * so MuPDF — will lay it out in the PDF: single-line centred vertically, multi-line from the top with
 * 1.116 line spacing and breaks at spaces or between CJK characters, comb fields one character per
 * cell. The field's inside is painted the paper's colour first, so the old value does not show
 * through. Colours go through the reader's night mode, as the page does.
 *
 * <p>A text box ({@link #textBox}) is laid out by the same code with MuPDF's FreeText numbers
 * (first baseline 0.8 em below the top, lines 1.2 em apart) and drawn without paper, so the page
 * shows around its letters; {@link #cover} paints paper over where the box stood, so a box being
 * edited or moved is not seen twice. Its {@link #field} may change while it shows (arrows, drags).
 */
final class FieldPreview {

    final Page page;
    /** The field, normalised to the page: x0 y0 x1 y1. */
    final float[] field;
    String text = "";
    Typeface typeface = Typeface.SANS_SERIF;
    /** Points; 0 = fit the field (single-line), 12 (multi-line). */
    float size;
    int color = 0xFF000000;
    final boolean multiline;
    final int comb;
    final int quadding;
    final float border;
    /** Multi-line: the first baseline this many em below the top, the next ones {@link #lead} apart. */
    float base = 1.116f;
    float lead = 1.116f;
    /** Paint the field's inside the paper's colour first (a form field: hide the old value). */
    boolean paper = true;
    /** Normalised x0 y0 x1 y1 painted paper first, or null: where an edited / moved box stood. */
    float[] cover;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final Paint paperPaint = new Paint();

    FieldPreview(@NonNull Page page, @NonNull float[] field, boolean multiline, int comb, int quadding, float border) {
        this.page = page;
        this.field = field;
        this.multiline = multiline;
        this.comb = multiline ? 0 : comb;
        this.quadding = quadding;
        this.border = border;
        paperPaint.setStyle(Paint.Style.FILL);
    }

    /** A text box at rect (normalised; it may change while shown), laid out as doksho_annot.c draws it. */
    static FieldPreview textBox(@NonNull Page page, @NonNull float[] rect, @NonNull Typeface typeface, float size, int color) {
        FieldPreview p = new FieldPreview(page, rect, true, 0, 0, 0);
        p.base = 0.8f;
        p.lead = 1.2f;
        p.paper = false;
        p.typeface = typeface;
        p.size = size;
        p.color = color;
        return p;
    }

    /** The text's longest line in em (as the layout measures it: glyph by glyph) and its line count. */
    static float[] measure(@NonNull Typeface typeface, @NonNull String text) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setTypeface(typeface);
        p.setTextSize(1000f);
        float longest = 0;
        String[] lines = text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        for (String l : lines) {
            float w = 0;
            int[] cps = l.replace('\t', ' ').codePoints().toArray();
            int n = cps.length;
            while (n > 0 && cps[n - 1] == ' ') n--;
            for (int i = 0; i < n; i++) w += p.measureText(new String(Character.toChars(cps[i]))) / 1000f;
            longest = Math.max(longest, w);
        }
        return new float[]{longest, lines.length};
    }

    /** A colour as the reader shows it: the night duotone, Librera's plain inversion, or as is. */
    static int shown(int color) {
        if (DokshoNight.active()) {
            int[] px = {color | 0xFF000000};
            DokshoNight.apply(px);
            return px[0] | 0xFF000000;
        }
        if (!AppState.get().isDayNotInvert) return (~color & 0x00FFFFFF) | 0xFF000000;
        return color | 0xFF000000;
    }

    private static boolean cjk(int c) {
        return (c >= 0x2E80 && c <= 0x9FFF) || (c >= 0xAC00 && c <= 0xD7AF) || (c >= 0xF900 && c <= 0xFAFF)
                || (c >= 0xFF00 && c <= 0xFFEF) || (c >= 0x20000 && c <= 0x3FFFF);
    }

    private static float width(float[] adv, int[] ucs, int a, int b) {
        while (b > a && ucs[b - 1] == ' ') b--;
        float w = 0;
        for (int i = a; i < b; i++) w += adv[i];
        return w;
    }

    /** One glyph to draw: its text and baseline origin in points, bottom-left origin. */
    private static final class G {
        final String s;
        final float x;
        final float y;

        G(String s, float x, float y) {
            this.s = s;
            this.x = x;
            this.y = y;
        }
    }

    private void line(List<G> out, int[] ucs, float[] adv, int a, int b, float x0, float maxw, float size, float y) {
        float lw = width(adv, ucs, a, b) * size;
        float x = x0;
        if (quadding == 1) x += (maxw - lw) / 2;
        else if (quadding == 2) x += maxw - lw;
        for (int i = a; i < b; i++) {
            if (ucs[i] != '\n' && ucs[i] != '\t' && ucs[i] != ' ') out.add(new G(new String(Character.toChars(ucs[i])), x, y));
            x += adv[i] * size;
        }
    }

    void draw(@NonNull Canvas canvas, @NonNull AnnotSession s) {
        RectF r = s.toScreen(page, field[0], field[1], field[2], field[3]);
        float k = s.pxPerPt(page);
        if (k <= 0 || r.width() <= 1 || r.height() <= 1) return;
        float w = r.width() / k, h = r.height() / k;

        // the code points, as the native side reads them
        String t = text.replace("\r\n", "\n").replace('\r', '\n');
        int[] cps = t.codePoints().toArray();
        int n = cps.length;
        int[] ucs = new int[n];
        float[] adv = new float[n];
        paint.setTypeface(typeface);
        paint.setTextSize(1000f);
        for (int i = 0; i < n; i++) {
            int c = cps[i];
            if (!multiline && c == '\n') c = ' ';
            if (c == '\t') c = ' ';
            ucs[i] = c;
            adv[i] = c == '\n' ? 0 : paint.measureText(new String(Character.toChars(c))) / 1000f;
        }

        float pad = comb > 0 ? 0 : border * 2;
        float W = w - pad * 2, H = h - pad * 2;
        float size = this.size;
        if (size <= 0) {
            if (multiline) {
                size = 12;
            } else {
                float ms = width(adv, ucs, 0, n);
                size = ms > 0 ? W / ms : 12;
                if (size > H) size = H;
            }
        }
        List<G> glyphs = new ArrayList<>();
        if (multiline) {
            float lh = size * lead;
            float y = pad + H - size * base;
            float maxw = W / size;
            int a = 0;
            while (a < n) {
                int i = a, brkEnd = -1, brkNext = -1, end = -1, next = -1;
                float lw = 0;
                while (i < n) {
                    if (ucs[i] == '\n') {
                        end = i;
                        next = i + 1;
                        break;
                    }
                    if (i > a && (cjk(ucs[i]) || cjk(ucs[i - 1])) && ucs[i] != ' ') {
                        brkEnd = i;
                        brkNext = i;
                    }
                    if (lw + adv[i] > maxw && i > a && ucs[i] != ' ') {
                        if (brkEnd > a) {
                            end = brkEnd;
                            next = brkNext;
                        } else {
                            end = i;
                            next = i;
                        }
                        break;
                    }
                    if (ucs[i] == ' ') {
                        brkEnd = i;
                        brkNext = i + 1;
                    }
                    lw += adv[i];
                    i++;
                }
                if (end < 0) {
                    end = n;
                    next = n;
                }
                line(glyphs, ucs, adv, a, end, pad, W, size, y);
                y -= lh;
                a = next;
            }
        } else if (comb > 0) {
            float cell = W / comb;
            float y = H - size * 0.8f - (H - size) / 2;
            for (int i = 0; i < n && i < comb; i++) {
                if (ucs[i] == ' ') continue;
                glyphs.add(new G(new String(Character.toChars(ucs[i])), cell * i + (cell - adv[i] * size) / 2, y));
            }
        } else {
            float y = pad + H - size * 0.8f - (H - size) / 2;
            line(glyphs, ucs, adv, 0, n, pad, W, size, y);
        }

        float b = border * k;
        RectF inner = new RectF(r.left + b, r.top + b, r.right - b, r.bottom - b);
        paperPaint.setColor(shown(0xFFFFFFFF));
        if (cover != null) canvas.drawRect(s.toScreen(page, cover[0], cover[1], cover[2], cover[3]), paperPaint);
        if (paper) canvas.drawRect(inner, paperPaint);
        canvas.save();
        canvas.clipRect(inner);
        paint.setTextSize(size * k);
        paint.setColor(shown(color));
        for (G g : glyphs) canvas.drawText(g.s, r.left + g.x * k, r.bottom - g.y * k, paint);
        canvas.restore();
    }
}
