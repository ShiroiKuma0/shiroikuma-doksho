package shiroikuma.doksho.annot;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.RectF;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
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
 * Filling one text field. The page scrolls so the field sits near the top; the editor docks right
 * beneath it (above it when there is no room), leaving the field in view; and while it is open the
 * text is drawn over the field live ({@link FieldPreview}) — the real size at the current zoom, in
 * the chosen font and colour — so it can be measured against the print around it before it goes
 * into the PDF.
 *
 * <p>Layout: the words, then Own style · Cancel · OK (still above the keyboard), then font, size
 * and colour.
 */
final class FormEditor {

    interface Done {
        /** path null: a built-in font (code builtin); own: keep the field's own style. */
        void ok(@NonNull String text, @Nullable String path, int builtin, int size, int color, boolean own);
    }

    private final AnnotTools tools;
    private final Activity a;
    private final AnnotSession s;
    private final AnnotLayer layer;
    private final Page page;
    private final FieldPreview preview;
    private final int barBottom;

    private String path;
    private int builtin;
    private int size;
    private int color;
    private boolean own;
    /** The field's own style, for the preview when "own style" is on. */
    private final int daSize;
    private final int daColor;

    FormEditor(@NonNull AnnotTools tools, @NonNull Activity a, @NonNull AnnotSession s, @NonNull AnnotLayer layer,
               @NonNull Page page, @NonNull float[] w, int o, int barBottom, @NonNull String value,
               @Nullable String fieldFont, @NonNull Done done) {
        this.tools = tools;
        this.a = a;
        this.s = s;
        this.layer = layer;
        this.page = page;
        this.barBottom = barBottom;
        int flags = (int) w[o + 5];
        boolean multiline = (flags & DokshoPdf.F_MULTILINE) != 0;
        int maxLen = (int) w[o + 6];
        int comb = (flags & DokshoPdf.F_COMB) != 0 ? maxLen : 0;
        daSize = Math.round(w[o + 7]);
        daColor = 0xFF000000 | (int) w[o + 10];
        preview = new FieldPreview(page, new float[]{w[o], w[o + 1], w[o + 2], w[o + 3]}, multiline, comb,
                (int) w[o + 8], w[o + 9]);

        if (fieldFont != null && !fieldFont.isEmpty() && new java.io.File(fieldFont).isFile()) {
            // filled in a font file before: it opens as it is in the field
            path = fieldFont;
            builtin = 0;
            size = daSize;
            color = daColor;
            own = false;
        } else {
            String p = DokshoUi.s(DokshoUi.ANNOT_FORM_FONT_FILE);
            path = p != null && !p.isEmpty() && new java.io.File(p).isFile() ? p : null;
            builtin = DokshoUi.i(DokshoUi.ANNOT_FORM_FONT);
            size = DokshoUi.i(DokshoUi.ANNOT_FORM_SIZE);
            color = DokshoUi.i(DokshoUi.ANNOT_FORM_COLOR);
            own = DokshoUi.b(DokshoUi.ANNOT_FORM_OWN);
        }
        preview.text = value;
        build(value, multiline, maxLen, done);
    }

    private int dp(float v) {
        return DokshoViews.dp(a, v);
    }

    private void refresh() {
        if (own) {
            preview.typeface = FormFonts.builtinTypeface(0);
            preview.size = daSize;
            preview.color = daColor;
        } else {
            preview.typeface = FormFonts.typeface(path, builtin);
            preview.size = size;
            preview.color = color;
        }
        layer.setPreview(preview);
    }

    private void build(String value, boolean multiline, int maxLen, final Done done) {
        final int ink = DokshoViews.ink();
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(12), dp(16), dp(12));
        box.setBackground(DokshoViews.panelBackground(a));
        box.setTag(DokshoViews.NO_SKIN);

        final EditText edit = new EditText(a);
        edit.setText(value);
        edit.setSelection(edit.getText().length());
        edit.setTextColor(ink);
        edit.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        edit.setBackground(DokshoViews.boxBackground(a, DokshoViews.border()));
        edit.setPadding(dp(12), dp(8), dp(12), dp(8));
        edit.setTag(DokshoViews.NO_SKIN);
        int type = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES;
        if (multiline) {
            type |= InputType.TYPE_TEXT_FLAG_MULTI_LINE;
            edit.setMinLines(2);
            edit.setMaxLines(4);
            edit.setGravity(Gravity.TOP | Gravity.START);
        } else {
            edit.setSingleLine(true);
        }
        edit.setInputType(type);
        if (maxLen > 0) edit.setFilters(new InputFilter[]{new InputFilter.LengthFilter(maxLen)});
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
                preview.text = e.toString();
                layer.invalidate();
            }
        });

        // Own style · Cancel · OK — right under the words, so the keyboard never hides them
        final LinearLayout style = new LinearLayout(a);
        style.setOrientation(LinearLayout.VERTICAL);
        style.setTag(DokshoViews.NO_SKIN);
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setTag(DokshoViews.NO_SKIN);
        row.setPadding(0, dp(8), 0, dp(4));
        CheckBox keep = DokshoViews.checkbox(a, a.getString(R.string.doksho_annot_own_style_short), false, 0);
        keep.setChecked(own);
        keep.setOnCheckedChangeListener((v, on) -> {
            own = on;
            AnnotTools.enableTree(style, !on);
            refresh();
        });
        row.addView(keep, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        final AlertDialog[] dialog = new AlertDialog[1];
        row.addView(DokshoViews.pill(a, a.getString(R.string.doksho_eim_cancel), v -> dialog[0].dismiss()));
        View gap = new View(a);
        row.addView(gap, new LinearLayout.LayoutParams(dp(8), 0));
        row.addView(DokshoViews.pill(a, a.getString(R.string.doksho_eim_ok), v -> {
            dialog[0].dismiss();
            if (!own) {
                DokshoUi.set(DokshoUi.ANNOT_FORM_FONT_FILE, path == null ? "" : path);
                DokshoUi.set(DokshoUi.ANNOT_FORM_FONT, builtin);
                DokshoUi.set(DokshoUi.ANNOT_FORM_SIZE, size);
                DokshoUi.set(DokshoUi.ANNOT_FORM_COLOR, color);
            }
            DokshoUi.set(DokshoUi.ANNOT_FORM_OWN, own);
            done.ok(edit.getText().toString(), path, builtin, size, color, own);
        }));
        box.addView(row);

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
            fontButton.setTypeface(FormFonts.typeface(path, builtin));
        };
        showFont.run();
        fontButton.setOnClickListener(v -> FormFonts.pick(a, path, builtin, (p, b) -> {
            path = p;
            builtin = b;
            showFont.run();
            refresh();
        }));
        fontRow.addView(fontButton, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        style.addView(fontRow);

        // size
        final TextView sizeLabel = tools.label(style, tools.sizeText(size));
        tools.slider(style, 0, 72, size, v -> {
            size = v;
            sizeLabel.setText(tools.sizeText(v));
            refresh();
        });

        // colour
        style.addView(tools.swatches(color, c -> {
            color = c;
            refresh();
        }));
        box.addView(style);
        AnnotTools.enableTree(style, !own);

        dialog[0] = DokshoViews.boxDialog(a, box, true);
        dialog[0].setOnDismissListener(d -> layer.setPreview(null));
        place(dialog[0], box, edit);
    }

    /** Scrolls the field near the top, then shows the editor beside it. */
    private void place(final AlertDialog dialog, final View box, final EditText edit) {
        DisplayMetrics dm = a.getResources().getDisplayMetrics();
        RectF r = fieldOnLayer();
        int top = barBottom + dp(12);
        int dy = Math.round(r.top - top);
        int dx = 0;
        if (r.left < dp(8)) dx = Math.round(r.left - dp(8));
        else if (r.right > layer.getWidth() - dp(8)) dx = Math.round(Math.min(r.left - dp(8), r.right - layer.getWidth() + dp(8)));
        if (dx != 0 || dy != 0) s.scrollBy(dx, dy);
        refresh();
        layer.post(() -> {
            RectF f = fieldOnLayer();
            int[] loc = new int[2];
            layer.getLocationOnScreen(loc);
            int width = Math.min(dm.widthPixels - dp(16), dp(560));
            box.measure(View.MeasureSpec.makeMeasureSpec(width - dp(20), View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            int h = box.getMeasuredHeight() + dp(20);
            int below = loc[1] + Math.round(f.bottom) + dp(6);
            int above = loc[1] + Math.round(f.top) - dp(6) - h;
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

    private RectF fieldOnLayer() {
        return s.toScreen(page, preview.field[0], preview.field[1], preview.field[2], preview.field[3]);
    }
}
