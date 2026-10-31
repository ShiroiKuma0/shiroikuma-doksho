package shiroikuma.doksho.annot;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.foobnix.pdf.info.R;

import org.ebookdroid.core.Page;

import shiroikuma.doksho.DokshoUi;
import shiroikuma.doksho.DokshoViews;

/**
 * Writing a text box (a new one, or one already in the PDF). The editor docks above or below the
 * box ({@link Dock}) and the words are drawn on the page live as they are typed ({@link
 * FieldPreview#textBox}) — the real size at the current zoom, in the very font file the box will be
 * embedded in, laid out as the native side lays it out — so what shows is what OK writes. The box
 * is sized to its words: as wide as the longest line, as tall as its lines; its top-left corner
 * stays put. Four arrows move it a point at a time (hold one to keep moving), the preview with it.
 * X and Y show the box's top-left corner in points from the page's top-left corner, and take a
 * typed value — boxes standing in for the lines of a form without fields line up on one column by
 * giving them the same X.
 *
 * <p>Layout: the words, X · Y · (Delete), ← ↑ ↓ → · Cancel · OK (still above the keyboard), then
 * font, size and colour.
 */
final class TextBoxEditor {

    interface Done {
        /** rect: normalised x0 y0 x1 y1; path: the font file (null: built-in font {@code builtin}). */
        void ok(@NonNull String text, @NonNull float[] rect, @Nullable String path, int builtin, int size, int color);

        void delete();
    }

    /** One arrow press, in points. */
    private static final float STEP_PT = 1f;
    /** Room for the layout's rounding, so a line that fits never wraps: em. */
    private static final float SLACK_EM = 0.3f;

    private final AnnotTools tools;
    private final Activity a;
    private final AnnotSession s;
    private final AnnotLayer layer;
    private final Page page;
    private final int barBottom;
    private final Handler handler = new Handler(Looper.getMainLooper());

    /** The box, normalised: its top-left corner is (x, y). */
    private final float[] rect = new float[4];
    private float x;
    private float y;
    private String text;
    @Nullable
    private String path;
    private int builtin;
    private int size;
    private int color;
    private final FieldPreview preview;
    /** X / Y in points; null until built. */
    @Nullable
    private EditText xField;
    @Nullable
    private EditText yField;
    /** The fields are being written from the box, not typed into. */
    private boolean showing;

    /**
     * @param x0    the box's left edge, normalised
     * @param y0    its top edge, normalised
     * @param cover where the box stood (an existing box), painted over while editing; or null
     */
    TextBoxEditor(@NonNull AnnotTools tools, @NonNull Activity a, @NonNull AnnotSession s, @NonNull AnnotLayer layer,
                  @NonNull Page page, int barBottom, float x0, float y0, @NonNull String text, @Nullable String path,
                  int builtin, int size, int color, @Nullable float[] cover, @NonNull Done done) {
        this.tools = tools;
        this.a = a;
        this.s = s;
        this.layer = layer;
        this.page = page;
        this.barBottom = barBottom;
        this.x = x0;
        this.y = y0;
        this.text = text;
        this.path = path;
        this.builtin = builtin;
        this.size = size;
        this.color = color;
        preview = FieldPreview.textBox(page, rect, typeface(), size, color);
        preview.cover = cover;
        relayout();
        build(cover != null, done);
    }

    /** The file the box is drawn and embedded in. */
    @Nullable
    static String fontFile(@Nullable String path, int builtin) {
        return path != null && !path.isEmpty() ? path : FormFonts.builtinFile(builtin);
    }

    private Typeface typeface() {
        return FormFonts.typeface(fontFile(path, builtin), builtin);
    }

    private int dp(float v) {
        return DokshoViews.dp(a, v);
    }

    /** The box around the words at (x, y), kept on the page; the preview follows. */
    private void relayout() {
        Typeface tf = typeface();
        float[] m = FieldPreview.measure(tf, text);
        float pw = Math.max(1f, s.pageWidthPt(page));
        float ph = Math.max(1f, s.pageHeightPt(page));
        float w = (m[0] + SLACK_EM) * size;
        float h = m[1] * 1.2f * size;
        float nw = Math.min(1f, w / pw);
        float nh = Math.min(1f, h / ph);
        x = Math.max(0f, Math.min(x, 1f - nw));
        y = Math.max(0f, Math.min(y, 1f - nh));
        rect[0] = x;
        rect[1] = y;
        rect[2] = x + nw;
        rect[3] = y + nh;
        preview.typeface = tf;
        preview.size = size;
        preview.color = color;
        preview.text = text;
        layer.setPreview(preview);
        showPosition();
    }

    private static String pt(float v) {
        return String.format(java.util.Locale.US, "%.1f", v);
    }

    /** The box's corner into X / Y, unless the field is being typed into and already says it. */
    private void showPosition() {
        if (xField == null || yField == null) return;
        showing = true;
        setIfDifferent(xField, x * s.pageWidthPt(page));
        setIfDifferent(yField, y * s.pageHeightPt(page));
        showing = false;
    }

    private static void setIfDifferent(EditText f, float v) {
        try {
            if (f.hasFocus() && Math.abs(Float.parseFloat(f.getText().toString().replace(',', '.')) - v) < 0.05f) return;
        } catch (NumberFormatException ignored) {
        }
        f.setText(pt(v));
        if (f.hasFocus()) f.setSelection(f.getText().length());
    }

    /** X or Y, in points: an editable number; typing moves the box. */
    private EditText coordinate(LinearLayout row, String label, final boolean horizontal) {
        int ink = DokshoViews.ink();
        TextView l = DokshoViews.text(a, label, 15, ink, true);
        l.setPadding(horizontal ? 0 : dp(14), 0, dp(6), 0);
        row.addView(l);
        final EditText f = new EditText(a);
        f.setTextColor(ink);
        f.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        f.setBackground(DokshoViews.boxBackground(a, DokshoViews.border()));
        f.setPadding(dp(8), dp(4), dp(8), dp(4));
        f.setTag(DokshoViews.NO_SKIN);
        f.setSingleLine(true);
        f.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        f.setSelectAllOnFocus(true);
        f.setMinWidth(dp(78));
        row.addView(f, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        f.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence c, int st, int n, int after) {
            }

            @Override
            public void onTextChanged(CharSequence c, int st, int before, int n) {
            }

            @Override
            public void afterTextChanged(Editable e) {
                if (showing) return;
                float v;
                try {
                    v = Float.parseFloat(e.toString().replace(',', '.'));
                } catch (NumberFormatException ex) {
                    return;
                }
                if (horizontal) x = v / Math.max(1f, s.pageWidthPt(page));
                else y = v / Math.max(1f, s.pageHeightPt(page));
                relayout();
            }
        });
        return f;
    }

    private void nudge(float dxPt, float dyPt) {
        x += dxPt / Math.max(1f, s.pageWidthPt(page));
        y += dyPt / Math.max(1f, s.pageHeightPt(page));
        relayout();
    }

    /** An arrow: a press moves a step, holding it keeps moving. */
    @SuppressLint("ClickableViewAccessibility")
    private Button arrow(String label, String description, final float dx, final float dy) {
        final Button b = DokshoViews.pill(a, label, null);
        b.setContentDescription(description);
        b.setMinWidth(0);
        b.setMinimumWidth(dp(44));
        final Runnable[] repeat = new Runnable[1];
        repeat[0] = () -> {
            nudge(dx, dy);
            handler.postDelayed(repeat[0], 60);
        };
        b.setOnTouchListener((v, ev) -> {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    v.setPressed(true);
                    nudge(dx, dy);
                    handler.postDelayed(repeat[0], 400);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    v.setPressed(false);
                    handler.removeCallbacks(repeat[0]);
                    return true;
                default:
                    return true;
            }
        });
        return b;
    }

    private void build(boolean existing, final Done done) {
        final int ink = DokshoViews.ink();
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(12), dp(16), dp(12));
        box.setBackground(DokshoViews.panelBackground(a));
        box.setTag(DokshoViews.NO_SKIN);

        final EditText edit = new EditText(a);
        edit.setText(text);
        edit.setSelection(edit.getText().length());
        edit.setTextColor(ink);
        edit.setHintTextColor(DokshoUi.i(DokshoUi.C_TEXT2));
        edit.setHint(R.string.doksho_annot_new_text);
        edit.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        edit.setBackground(DokshoViews.boxBackground(a, DokshoViews.border()));
        edit.setPadding(dp(12), dp(8), dp(12), dp(8));
        edit.setTag(DokshoViews.NO_SKIN);
        edit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        edit.setMinLines(2);
        edit.setMaxLines(4);
        edit.setGravity(Gravity.TOP | Gravity.START);
        box.addView(edit, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        edit.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence c, int st, int n, int after) {
            }

            @Override
            public void onTextChanged(CharSequence c, int st, int before, int n) {
            }

            @Override
            public void afterTextChanged(Editable e) {
                text = e.toString();
                relayout();
            }
        });

        // X · Y · (Delete), then ← ↑ ↓ → · Cancel · OK — right under the words, so the keyboard never hides them
        final AlertDialog[] dialog = new AlertDialog[1];
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setTag(DokshoViews.NO_SKIN);
        row.setPadding(0, dp(8), 0, dp(4));
        row.addView(arrow("←", a.getString(R.string.doksho_annot_move_left), -STEP_PT, 0));
        row.addView(arrow("↑", a.getString(R.string.doksho_annot_move_up), 0, -STEP_PT));
        row.addView(arrow("↓", a.getString(R.string.doksho_annot_move_down), 0, STEP_PT));
        row.addView(arrow("→", a.getString(R.string.doksho_annot_move_right), STEP_PT, 0));
        row.addView(new View(a), new LinearLayout.LayoutParams(0, 0, 1f));
        row.addView(DokshoViews.pill(a, a.getString(R.string.doksho_eim_cancel), v -> dialog[0].dismiss()));
        row.addView(new View(a), new LinearLayout.LayoutParams(dp(8), 0));
        row.addView(DokshoViews.pill(a, a.getString(R.string.doksho_eim_ok), v -> {
            dialog[0].dismiss();
            DokshoUi.set(DokshoUi.ANNOT_TEXT_FONT_FILE, path == null ? "" : path);
            DokshoUi.set(DokshoUi.ANNOT_TEXT_FONT, builtin);
            DokshoUi.set(DokshoUi.ANNOT_TEXT_SIZE, size);
            DokshoUi.set(DokshoUi.ANNOT_TEXT_COLOR, color);
            done.ok(edit.getText().toString(), rect.clone(), path, builtin, size, color);
        }));
        // X / Y of the top-left corner, in points from the page's top-left corner
        LinearLayout pos = new LinearLayout(a);
        pos.setOrientation(LinearLayout.HORIZONTAL);
        pos.setGravity(Gravity.CENTER_VERTICAL);
        pos.setTag(DokshoViews.NO_SKIN);
        pos.setPadding(0, dp(2), 0, dp(4));
        xField = coordinate(pos, "X", true);
        yField = coordinate(pos, "Y", false);
        TextView unit = DokshoViews.text(a, a.getString(R.string.doksho_annot_position_unit), 13, DokshoUi.i(DokshoUi.C_TEXT2), false);
        unit.setPadding(dp(10), 0, 0, 0);
        pos.addView(unit, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (existing) {
            pos.addView(DokshoViews.pill(a, a.getString(R.string.doksho_annot_delete), v -> {
                dialog[0].dismiss();
                done.delete();
            }));
        }
        box.addView(pos);
        box.addView(row);
        showPosition();

        // font
        LinearLayout fontRow = new LinearLayout(a);
        fontRow.setOrientation(LinearLayout.HORIZONTAL);
        fontRow.setGravity(Gravity.CENTER_VERTICAL);
        fontRow.setTag(DokshoViews.NO_SKIN);
        fontRow.setPadding(0, dp(4), 0, 0);
        TextView fl = DokshoViews.text(a, a.getString(R.string.doksho_annot_font), 15, ink, false);
        fl.setPadding(0, 0, dp(10), 0);
        fontRow.addView(fl);
        final Button fontButton = DokshoViews.pill(a, "", null);
        final Runnable showFont = () -> {
            fontButton.setText(FormFonts.label(a, path, builtin) + "  ▾");
            fontButton.setTypeface(typeface());
        };
        showFont.run();
        fontButton.setOnClickListener(v -> FormFonts.pick(a, path, builtin, (p, b) -> {
            path = p;
            builtin = b;
            showFont.run();
            relayout();
        }));
        fontRow.addView(fontButton, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        box.addView(fontRow);

        // size
        final TextView sizeLabel = tools.label(box, tools.sizeText(size));
        tools.slider(box, 4, 72, size, v -> {
            size = v;
            sizeLabel.setText(tools.sizeText(v));
            relayout();
        });

        // colour
        box.addView(tools.swatches(color, c -> {
            color = c;
            relayout();
        }));

        dialog[0] = DokshoViews.boxDialog(a, box, true);
        dialog[0].setOnDismissListener(d -> {
            handler.removeCallbacksAndMessages(null);
            layer.setPreview(null);
        });
        relayout();
        Dock.show(a, s, layer, barBottom, dialog[0], box, edit,
                () -> s.toScreen(page, rect[0], rect[1], rect[2], rect[3]));
    }
}
