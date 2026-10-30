package shiroikuma.doksho.annot;

/**
 * The PDF editing layer's native half ({@code shiroikuma/jni/doksho_annot.c}, linked into
 * libmupdf.so by {@code shiroikuma/build-mupdf.sh}). Every call takes the handles Librera's own
 * bridge opened — {@code CodecDocument.getDocumentHandle()}, {@code CodecPage.getPageHandle()} — and
 * must run under {@code TempHolder.lock}, as Librera's calls do.
 *
 * <p>Places are normalised to the page (0..1, top-left origin); widths and font sizes are points.
 * Each edit is one undo step.
 */
public final class DokshoPdf {

    // pdf_widget_type
    public static final int W_BUTTON = 1;
    public static final int W_CHECKBOX = 2;
    public static final int W_COMBOBOX = 3;
    public static final int W_LISTBOX = 4;
    public static final int W_RADIO = 5;
    public static final int W_SIGNATURE = 6;
    public static final int W_TEXT = 7;

    /** widgets() flags. */
    public static final int F_READ_ONLY = 1;
    public static final int F_MULTILINE = 2;
    public static final int F_CHECKED = 4;
    public static final int F_COMB = 8;

    /**
     * widgets() stride: x0 y0 x1 y1 type flags maxLen fontSize quadding (0 left, 1 centre, 2 right)
     * borderWidth colour (0xRRGGBB).
     */
    public static final int W_STRIDE = 11;
    /** annots() stride: type x0 y0 x1 y1. */
    public static final int A_STRIDE = 5;

    // pdf_annot_type
    public static final int A_FREE_TEXT = 2;
    public static final int A_INK = 15;

    /** erase() mask. */
    public static final int ERASE_INK = 1;
    public static final int ERASE_TEXT = 2;
    public static final int ERASE_OTHER = 4;

    private DokshoPdf() {
    }

    public static native float[] widgets(long doc, long page);

    public static native String widgetValue(long doc, long page, int index);

    public static native String[] widgetOptions(long doc, long page, int index);

    /** font &lt; 0 keeps the field's own style. */
    public static native boolean setWidgetValue(long doc, long page, int index, String value, int font, float size, int argb);

    /**
     * Fills a text field in a TrueType / OpenType font file, embedded as a subset of the glyphs used.
     * Returns how many characters the font has no glyph for, or -1 on failure.
     */
    public static native int setWidgetValueFont(long doc, long page, int index, String value, String fontPath, float size, int argb);

    /** The font file a field was last filled in by {@link #setWidgetValueFont}, or null. */
    public static native String widgetFont(long doc, long page, int index);

    public static native boolean toggleWidget(long doc, long page, int index);

    public static native float[] annots(long doc, long page);

    public static native String annotText(long doc, long page, int index);

    /** font code, size, 0xRRGGBB — or null. */
    public static native float[] annotStyle(long doc, long page, int index);

    /** Returns the new box's annotation index, or -1. */
    public static native int addText(long doc, long page, float x, float y, float w, float h, String text, int font, float size, int argb);

    /** rect (x0 y0 x1 y1) null keeps the place, text null the words, font &lt; 0 the style. */
    public static native boolean editText(long doc, long page, int index, float[] rect, String text, int font, float size, int argb);

    public static native boolean deleteAnnot(long doc, long page, int index);

    public static native boolean addInk(long doc, long page, float[] xy, float width, int argb);

    public static native int erase(long doc, long page, float[] xy, float radius, int mask);

    /** bit 0: can undo, bit 1: can redo. */
    public static native int undoState(long doc);

    public static native boolean undo(long doc, boolean redo);

    public static native boolean save(long doc, String path);
}
