package org.ebookdroid.droids.mupdf.codec;

import android.graphics.RectF;

import com.foobnix.android.utils.LOG;
import com.foobnix.android.utils.TxtUtils;


public class TextWord extends RectF {
    public String w;
    private RectF original;
    public int number;

    /**
     * 白い熊 読書: the word's letters — their text and their left / right edges (in the word's own
     * coordinates, normalised with it) — so a selection handle can end inside a word. Null for
     * words built without letters (DjVu, PDFium, EPUB).
     */
    public java.util.ArrayList<String> chars;
    public float[] charLeft;
    public float[] charRight;
    private int charCount;

    public TextWord() {
        super();
        w = "";
    }

    public TextWord(TextChar tc) {
        w = "";
        Add(tc);
    }


    @Override
    public String toString() {
        return "" + w + left + " " + top + " " + right + " " + bottom;
    }

    public void Add(TextChar tc) {
        super.union(tc);
        if (chars == null) {
            chars = new java.util.ArrayList<>();
            charLeft = new float[8];
            charRight = new float[8];
        }
        if (charCount == charLeft.length) {
            charLeft = java.util.Arrays.copyOf(charLeft, charCount * 2);
            charRight = java.util.Arrays.copyOf(charRight, charCount * 2);
        }
        try {
            chars.add(new String(Character.toChars(tc.c)));
        } catch (Exception e) {
            chars.add("");
        }
        charLeft[charCount] = tc.left;
        charRight[charCount] = tc.right;
        charCount++;
        try {
            w = w.concat(new String(Character.toChars(tc.c)));
        }catch (Exception e){
            w = w.concat("");
            LOG.e(e);
        }
    }

    public TextWord(String w, RectF rect) {
        super(rect);
        this.w = w;
    }

    public int charCount() {
        return charCount;
    }

    /** 白い熊 読書: the letters' edges normalised as the word's own were (x → (x - left) / width). */
    public void normalizeChars(float left, float width) {
        for (int i = 0; i < charCount; i++) {
            charLeft[i] = (charLeft[i] - left) / width;
            charRight[i] = (charRight[i] - left) / width;
        }
    }

    /**
     * 白い熊 読書: the letters [from, to) as a word of their own (same line, narrower box), or this
     * word when that is all of it.
     */
    public TextWord part(int from, int to) {
        if (from <= 0 && to >= charCount) {
            return this;
        }
        StringBuilder b = new StringBuilder();
        for (int i = from; i < to; i++) {
            if (!"\u00AD".equals(chars.get(i))) b.append(chars.get(i)); // MuPDF's soft hyphen
        }
        TextWord t = new TextWord(b.toString(), new RectF(charLeft[from], top, charRight[to - 1], bottom));
        if (original != null) {
            // the same cut in PDF points (highlight, underline and strike-out are made from it)
            float k = width() > 0 ? original.width() / width() : 0;
            t.original = new RectF(original.left + (charLeft[from] - left) * k, original.top,
                    original.left + (charRight[to - 1] - left) * k, original.bottom);
        }
        return t;
    }

    public RectF getOriginal() {
        return original;
    }

    public void setOriginal(RectF original) {
        this.original = new RectF(original);
    }

    @Deprecated
    private String asString(TextWord[][] input, boolean isFiltering) {
        if (input == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (TextWord[] line : input) {

            for (int i = 0; i < line.length; i++) {
                TextWord word = line[i];
                // delete '-' from last word
                if (i == line.length - 1 && word.w.endsWith("-")) {
                    builder.append(word.w.replaceAll("-$", ""));
                } else {
                    builder.append(word.getWord() + " ");
                }
            }

        }
        return isFiltering ? TxtUtils.filterString(builder.toString()) : builder.toString();
    }

    public String getWord() {
        return w;
    }
}
