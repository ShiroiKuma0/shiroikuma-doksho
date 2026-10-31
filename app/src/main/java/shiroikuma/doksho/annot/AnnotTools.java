package shiroikuma.doksho.annot;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.text.InputFilter;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.RelativeLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.foobnix.pdf.info.R;
import com.foobnix.pdf.info.wrapper.DocumentController;
import com.foobnix.sys.VerticalModeController;

import org.ebookdroid.core.Page;

import java.lang.ref.WeakReference;
import java.util.Locale;

import shiroikuma.doksho.ColorPickerDialog;
import shiroikuma.doksho.DokshoUi;
import shiroikuma.doksho.DokshoViews;

/**
 * 白い熊 読書's PDF tools: a small bar over the book (Scroll mode) with one icon per tool —
 * fill in forms, text box, pen, eraser — and undo / redo. A tap chooses a tool (a second tap puts
 * it down again, back to reading); a long-press opens the tool's options in a small popup. The
 * edits go into the PDF itself (MuPDF annotations and form values, {@link DokshoPdf}); Librera
 * saves them into the file when the book closes.
 *
 * <p>Opened from the reader's edit button ({@code DocumentWrapperUI}, {@code editTop2}).
 */
public final class AnnotTools implements AnnotLayer.Actions {

    private static WeakReference<AnnotTools> current = new WeakReference<>(null);
    private static boolean hinted;

    private static final int[] PALETTE = {
            0xFF000000, 0xFF1E4BD2, 0xFFD21E1E, 0xFF148C32, 0xFFE07800, 0xFF7B2FBE, 0xFFFFFF00, 0xFFFFFFFF};

    private final Activity a;
    private final ViewGroup root;
    private final AnnotSession s;
    private final AnnotLayer layer;
    private final LinearLayout bar;
    private final ImageView[] toolButtons = new ImageView[5];
    private ImageView undoButton;
    private ImageView redoButton;
    @Nullable
    private PopupWindow popup;

    /** The reader's edit button: opens the bar, or closes it when it is open. */
    public static void toggle(@NonNull Activity activity, @Nullable DocumentController dc) {
        AnnotTools open = current.get();
        if (open != null && open.a == activity && open.bar.getParent() != null) {
            open.close();
            return;
        }
        if (open != null) open.close();
        if (!(dc instanceof VerticalModeController)) {
            DokshoViews.toast(activity, activity.getString(R.string.doksho_annot_scroll_only));
            return;
        }
        ViewGroup root = activity.findViewById(R.id.parentParent);
        View document = activity.findViewById(R.id.documentView);
        AnnotSession session = AnnotSession.of((VerticalModeController) dc);
        if (root == null || document == null || session == null) return;
        AnnotTools tools = new AnnotTools(activity, root, document, session);
        current = new WeakReference<>(tools);
    }

    private AnnotTools(Activity a, ViewGroup root, View document, AnnotSession s) {
        this.a = a;
        this.root = root;
        this.s = s;
        DokshoUi.init(a);

        layer = new AnnotLayer(a, s, document, this);
        int at = root.indexOfChild(document) + 1;
        View draw = root.findViewById(R.id.drawView);
        if (draw != null && draw.getParent() == root) at = root.indexOfChild(draw) + 1;
        root.addView(layer, at, new RelativeLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        bar = buildBar();
        RelativeLayout.LayoutParams lp = new RelativeLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.addRule(RelativeLayout.ALIGN_PARENT_TOP);
        lp.addRule(RelativeLayout.CENTER_HORIZONTAL);
        lp.topMargin = dp(8);
        root.addView(bar, lp);

        s.onChanged = () -> {
            updateUndo();
            layer.invalidate();
        };
        int tool = DokshoUi.i(DokshoUi.ANNOT_TOOL);
        select(tool >= AnnotLayer.FORM && tool <= AnnotLayer.ERASER ? tool : AnnotLayer.NONE, false);
        updateUndo();
        s.readUndoState();
        if (!hinted) {
            hinted = true;
            DokshoViews.toast(a, a.getString(R.string.doksho_annot_hint));
        }
    }

    private void close() {
        if (popup != null) popup.dismiss();
        root.removeView(layer);
        root.removeView(bar);
        s.onChanged = null;
        if (current.get() == this) current = new WeakReference<>(null);
    }

    private int dp(float v) {
        return DokshoViews.dp(a, v);
    }

    // ---- the bar ---------------------------------------------------------------------------------

    private LinearLayout buildBar() {
        LinearLayout b = new LinearLayout(a);
        b.setOrientation(LinearLayout.HORIZONTAL);
        b.setGravity(Gravity.CENTER_VERTICAL);
        b.setPadding(dp(6), dp(4), dp(6), dp(4));
        b.setBackground(DokshoViews.panelBackground(a));
        b.setElevation(dp(8));
        b.setTag(DokshoViews.NO_SKIN);
        b.setClickable(true); // a tap on the bar's ground is not a tap on the page

        toolButtons[AnnotLayer.FORM] = toolButton(b, R.drawable.doksho_annot_form, R.string.doksho_annot_form, AnnotLayer.FORM);
        toolButtons[AnnotLayer.TEXT] = toolButton(b, R.drawable.doksho_annot_text, R.string.doksho_annot_text, AnnotLayer.TEXT);
        toolButtons[AnnotLayer.PEN] = toolButton(b, R.drawable.doksho_annot_pen, R.string.doksho_annot_pen, AnnotLayer.PEN);
        toolButtons[AnnotLayer.ERASER] = toolButton(b, R.drawable.doksho_annot_eraser, R.string.doksho_annot_eraser, AnnotLayer.ERASER);
        b.addView(separator());
        undoButton = button(b, R.drawable.doksho_annot_undo, R.string.doksho_annot_undo);
        undoButton.setOnClickListener(v -> s.undo(false));
        redoButton = button(b, R.drawable.doksho_annot_redo, R.string.doksho_annot_redo);
        redoButton.setOnClickListener(v -> s.undo(true));
        b.addView(separator());
        ImageView close = button(b, R.drawable.doksho_annot_close, R.string.doksho_annot_close);
        close.setOnClickListener(v -> close());
        return b;
    }

    private View separator() {
        View v = new View(a);
        v.setBackgroundColor(DokshoViews.border());
        v.setAlpha(0.5f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Math.max(1, dp(1)), dp(28));
        lp.leftMargin = dp(5);
        lp.rightMargin = dp(5);
        v.setLayoutParams(lp);
        v.setTag(DokshoViews.NO_SKIN);
        return v;
    }

    private ImageView button(LinearLayout parent, int icon, int label) {
        ImageView b = new ImageView(a);
        b.setImageResource(icon);
        b.setContentDescription(a.getString(label));
        b.setPadding(dp(10), dp(10), dp(10), dp(10));
        b.setScaleType(ImageView.ScaleType.FIT_CENTER);
        b.setImageTintList(ColorStateList.valueOf(DokshoViews.ink()));
        b.setTag(DokshoViews.NO_SKIN);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(46), dp(46));
        lp.leftMargin = dp(2);
        lp.rightMargin = dp(2);
        parent.addView(b, lp);
        return b;
    }

    private ImageView toolButton(LinearLayout parent, int icon, int label, final int tool) {
        ImageView b = button(parent, icon, label);
        b.setOnClickListener(v -> select(layer.tool == tool ? AnnotLayer.NONE : tool, true));
        b.setOnLongClickListener(v -> {
            select(tool, true);
            showOptions(tool, v);
            return true;
        });
        return b;
    }

    private void select(int tool, boolean remember) {
        layer.setTool(tool);
        if (remember) DokshoUi.set(DokshoUi.ANNOT_TOOL, tool);
        int ink = DokshoViews.ink();
        int ground = DokshoViews.ground();
        for (int t = AnnotLayer.FORM; t <= AnnotLayer.ERASER; t++) {
            ImageView b = toolButtons[t];
            if (b == null) continue;
            boolean on = t == tool;
            if (on) {
                GradientDrawable bg = new GradientDrawable();
                bg.setColor(ink);
                bg.setCornerRadius(dp(10));
                b.setBackground(bg);
            } else {
                b.setBackground(null);
            }
            b.setImageTintList(ColorStateList.valueOf(on ? ground : ink));
        }
    }

    private void updateUndo() {
        undoButton.setEnabled((s.undoState & 1) != 0);
        undoButton.setAlpha((s.undoState & 1) != 0 ? 1f : 0.35f);
        redoButton.setEnabled((s.undoState & 2) != 0);
        redoButton.setAlpha((s.undoState & 2) != 0 ? 1f : 0.35f);
    }

    // ---- options popups --------------------------------------------------------------------------

    private void showOptions(int tool, View anchor) {
        if (popup != null) popup.dismiss();
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(12), dp(16), dp(12));
        box.setBackground(DokshoViews.panelBackground(a));
        box.setTag(DokshoViews.NO_SKIN);
        int titleRes = tool == AnnotLayer.FORM ? R.string.doksho_annot_form
                : tool == AnnotLayer.TEXT ? R.string.doksho_annot_text
                : tool == AnnotLayer.PEN ? R.string.doksho_annot_pen : R.string.doksho_annot_eraser;
        box.addView(DokshoViews.text(a, a.getString(titleRes), 17, DokshoViews.ink(), true));

        switch (tool) {
            case AnnotLayer.PEN:
                penOptions(box);
                break;
            case AnnotLayer.ERASER:
                eraserOptions(box);
                break;
            case AnnotLayer.TEXT:
                styleOptions(box, DokshoUi.ANNOT_TEXT_FONT, DokshoUi.ANNOT_TEXT_SIZE, DokshoUi.ANNOT_TEXT_COLOR, false);
                break;
            case AnnotLayer.FORM: {
                final LinearLayout style = new LinearLayout(a);
                style.setOrientation(LinearLayout.VERTICAL);
                style.setTag(DokshoViews.NO_SKIN);
                final CheckBox own = DokshoViews.checkbox(a, a.getString(R.string.doksho_annot_own_style), false, 0);
                own.setChecked(DokshoUi.b(DokshoUi.ANNOT_FORM_OWN));
                own.setOnCheckedChangeListener((v, on) -> {
                    DokshoUi.set(DokshoUi.ANNOT_FORM_OWN, on);
                    enableTree(style, !on);
                });
                box.addView(own);
                styleOptions(style, DokshoUi.ANNOT_FORM_FONT, DokshoUi.ANNOT_FORM_SIZE, DokshoUi.ANNOT_FORM_COLOR, true);
                enableTree(style, !own.isChecked());
                box.addView(style);
                break;
            }
            default:
        }

        ScrollView scroll = new ScrollView(a);
        scroll.setTag(DokshoViews.NO_SKIN);
        scroll.addView(box);
        int width = Math.min(dp(360), root.getWidth() - dp(16));
        popup = new PopupWindow(scroll, width, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        popup.setOutsideTouchable(true);
        popup.setElevation(dp(10));
        popup.setOnDismissListener(() -> {
            popup = null;
            layer.invalidate();
        });
        popup.showAsDropDown(anchor, 0, dp(8));
    }

    static void enableTree(View v, boolean on) {
        v.setEnabled(on);
        v.setAlpha(on ? 1f : 0.4f);
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) g.getChildAt(i).setEnabled(on);
            for (int i = 0; i < g.getChildCount(); i++) enableTree(g.getChildAt(i), on);
            v.setAlpha(on ? 1f : 0.4f);
        }
    }

    TextView label(LinearLayout box, String text) {
        TextView t = DokshoViews.text(a, text, 14, DokshoViews.ink(), false);
        t.setPadding(0, dp(10), 0, dp(2));
        box.addView(t);
        return t;
    }

    SeekBar slider(LinearLayout box, int min, int max, int value, final SliderMoved moved) {
        SeekBar bar = new SeekBar(a);
        bar.setMax(max - min);
        bar.setProgress(Math.max(0, Math.min(max - min, value - min)));
        bar.setProgressTintList(ColorStateList.valueOf(DokshoViews.ink()));
        bar.setThumbTintList(ColorStateList.valueOf(DokshoViews.ink()));
        bar.setProgressBackgroundTintList(ColorStateList.valueOf(DokshoViews.ink()));
        bar.setTag(DokshoViews.NO_SKIN);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) moved.to(progress + min);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        box.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(36)));
        return bar;
    }

    interface SliderMoved {
        void to(int value);
    }

    interface ColorChosen {
        void on(int color);
    }

    /** One-click swatches and "Any colour…" (the fork's picker); the chosen one is ringed. */
    View swatches(final int initial, final ColorChosen chosen) {
        final LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setTag(DokshoViews.NO_SKIN);
        final int[] selected = {initial | 0xFF000000};
        final Runnable[] paint = new Runnable[1];
        for (final int c : PALETTE) {
            View v = new View(a);
            v.setTag(DokshoViews.NO_SKIN);
            v.setOnClickListener(x -> {
                selected[0] = c;
                chosen.on(c);
                paint[0].run();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(30), dp(30));
            lp.rightMargin = dp(7);
            row.addView(v, lp);
        }
        final Button more = DokshoViews.pill(a, a.getString(R.string.doksho_annot_colour_more), null);
        more.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        more.setPadding(dp(12), dp(5), dp(12), dp(5));
        more.setOnClickListener(x -> ColorPickerDialog.show(a, a.getString(R.string.doksho_annot_colour), selected[0], c -> {
            selected[0] = c | 0xFF000000;
            chosen.on(selected[0]);
            paint[0].run();
        }));
        row.addView(more);
        paint[0] = () -> {
            for (int i = 0; i < PALETTE.length; i++) {
                GradientDrawable d = new GradientDrawable();
                d.setShape(GradientDrawable.OVAL);
                d.setColor(PALETTE[i]);
                boolean on = (PALETTE[i] | 0xFF000000) == selected[0];
                d.setStroke(on ? dp(3) : Math.max(1, dp(1)), on ? DokshoViews.ink() : (DokshoViews.ink() & 0x00FFFFFF) | 0x88000000);
                row.getChildAt(i).setBackground(d);
            }
            boolean custom = true;
            for (int c : PALETTE) if ((c | 0xFF000000) == selected[0]) custom = false;
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(custom ? selected[0] : DokshoViews.ground());
            bg.setStroke(custom ? dp(3) : Math.max(1, dp(1)), DokshoViews.border());
            bg.setCornerRadius(dp(50));
            more.setBackground(bg);
            more.setTextColor(custom ? contrast(selected[0]) : DokshoViews.ink());
        };
        paint[0].run();
        HorizontalScrollView scroll = new HorizontalScrollView(a);
        scroll.setTag(DokshoViews.NO_SKIN);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.addView(row);
        return scroll;
    }

    private static int contrast(int c) {
        double l = 0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c);
        return l > 140 ? Color.BLACK : Color.WHITE;
    }

    private void penOptions(LinearLayout box) {
        label(box, a.getString(R.string.doksho_annot_colour));
        box.addView(swatches(DokshoUi.i(DokshoUi.ANNOT_PEN_COLOR), c -> DokshoUi.set(DokshoUi.ANNOT_PEN_COLOR, c)));

        final PenPreview preview = new PenPreview(a);
        final TextView width = label(box, "");
        final Runnable showWidth = () -> width.setText(a.getString(R.string.doksho_annot_thickness,
                fmt(DokshoUi.i(DokshoUi.ANNOT_PEN_WIDTH) / 10f)));
        showWidth.run();
        slider(box, 2, 120, DokshoUi.i(DokshoUi.ANNOT_PEN_WIDTH), v -> {
            DokshoUi.set(DokshoUi.ANNOT_PEN_WIDTH, v);
            showWidth.run();
            preview.invalidate();
        });

        final TextView opacity = label(box, "");
        final Runnable showOpacity = () -> opacity.setText(a.getString(R.string.doksho_annot_opacity,
                DokshoUi.i(DokshoUi.ANNOT_PEN_OPACITY)));
        showOpacity.run();
        slider(box, 5, 100, DokshoUi.i(DokshoUi.ANNOT_PEN_OPACITY), v -> {
            DokshoUi.set(DokshoUi.ANNOT_PEN_OPACITY, v);
            showOpacity.run();
            preview.invalidate();
        });
        box.addView(preview, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));

        CheckBox finger = DokshoViews.checkbox(a, a.getString(R.string.doksho_annot_finger), false, 0);
        finger.setChecked(DokshoUi.b(DokshoUi.ANNOT_PEN_FINGER));
        finger.setOnCheckedChangeListener((v, on) -> DokshoUi.set(DokshoUi.ANNOT_PEN_FINGER, on));
        box.addView(finger);
    }

    /** The pen's line at its thickness (as on a page at 100 %), colour and opacity, on white. */
    private static final class PenPreview extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        PenPreview(Context c) {
            super(c);
            setTag(DokshoViews.NO_SKIN);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
        }

        @Override
        protected void onDraw(@NonNull android.graphics.Canvas canvas) {
            float d = getResources().getDisplayMetrics().density;
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(Color.WHITE);
            bg.setCornerRadius(6 * d);
            bg.setBounds(0, (int) (4 * d), getWidth(), getHeight() - (int) (4 * d));
            bg.draw(canvas);
            int c = DokshoUi.i(DokshoUi.ANNOT_PEN_COLOR);
            int alpha = Math.round(255 * DokshoUi.i(DokshoUi.ANNOT_PEN_OPACITY) / 100f);
            paint.setColor((c & 0x00FFFFFF) | (alpha << 24));
            // a point is 1/72 inch; on the phone's screen, as on paper
            float px = DokshoUi.i(DokshoUi.ANNOT_PEN_WIDTH) / 10f * getResources().getDisplayMetrics().xdpi / 72f;
            paint.setStrokeWidth(Math.max(1f, px));
            android.graphics.Path p = new android.graphics.Path();
            float w = getWidth(), h = getHeight();
            p.moveTo(16 * d, h * 0.62f);
            p.cubicTo(w * 0.3f, h * 0.15f, w * 0.55f, h * 0.95f, w - 16 * d, h * 0.38f);
            canvas.drawPath(p, paint);
        }
    }

    private void eraserOptions(LinearLayout box) {
        final TextView size = label(box, "");
        final Runnable show = () -> size.setText(a.getString(R.string.doksho_annot_eraser_size,
                DokshoUi.i(DokshoUi.ANNOT_ERASER_SIZE)));
        show.run();
        slider(box, 4, 60, DokshoUi.i(DokshoUi.ANNOT_ERASER_SIZE), v -> {
            DokshoUi.set(DokshoUi.ANNOT_ERASER_SIZE, v);
            show.run();
        });
        label(box, a.getString(R.string.doksho_annot_erase_what));
        eraseBox(box, R.string.doksho_annot_erase_ink, DokshoUi.ANNOT_ERASE_INK);
        eraseBox(box, R.string.doksho_annot_erase_text, DokshoUi.ANNOT_ERASE_TEXT);
        eraseBox(box, R.string.doksho_annot_erase_other, DokshoUi.ANNOT_ERASE_OTHER);
        TextView note = DokshoViews.text(a, a.getString(R.string.doksho_annot_eraser_note), 13, DokshoUi.i(DokshoUi.C_TEXT2), false);
        note.setPadding(0, dp(6), 0, 0);
        box.addView(note);
    }

    private void eraseBox(LinearLayout box, int label, final String key) {
        CheckBox cb = DokshoViews.checkbox(a, a.getString(label), false, 0);
        cb.setChecked(DokshoUi.b(key));
        cb.setOnCheckedChangeListener((v, on) -> DokshoUi.set(key, on));
        box.addView(cb);
    }

    /** Font, size, colour — written to the given settings as they change. */
    private void styleOptions(LinearLayout box, final String fontKey, final String sizeKey, final String colorKey,
                              final boolean auto) {
        label(box, a.getString(R.string.doksho_annot_font));
        final String fileKey = DokshoUi.ANNOT_FORM_FONT.equals(fontKey) ? DokshoUi.ANNOT_FORM_FONT_FILE : DokshoUi.ANNOT_TEXT_FONT_FILE;
        final Button fb = DokshoViews.pill(a, "", null);
        final Runnable showFont = () -> {
            String p = DokshoUi.s(fileKey);
            fb.setText(FormFonts.label(a, p, DokshoUi.i(fontKey)) + "  ▾");
            fb.setTypeface(FormFonts.typeface(p, DokshoUi.i(fontKey)));
        };
        showFont.run();
        fb.setOnClickListener(v -> FormFonts.pick(a, DokshoUi.s(fileKey), DokshoUi.i(fontKey), (p, b) -> {
            DokshoUi.set(fileKey, p == null ? "" : p);
            DokshoUi.set(fontKey, b);
            showFont.run();
        }));
        box.addView(fb, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        final TextView size = label(box, "");
        final Runnable show = () -> size.setText(sizeText(DokshoUi.i(sizeKey)));
        show.run();
        slider(box, auto ? 0 : 4, 72, DokshoUi.i(sizeKey), v -> {
            DokshoUi.set(sizeKey, v);
            show.run();
        });
        label(box, a.getString(R.string.doksho_annot_colour));
        box.addView(swatches(DokshoUi.i(colorKey), c -> DokshoUi.set(colorKey, c)));
        TextView note = DokshoViews.text(a, a.getString(auto ? R.string.doksho_annot_cjk_note : R.string.doksho_annot_text_font_note),
                13, DokshoUi.i(DokshoUi.C_TEXT2), false);
        note.setPadding(0, dp(8), 0, 0);
        box.addView(note);
    }

    String sizeText(int size) {
        return size <= 0 ? a.getString(R.string.doksho_annot_size_auto) : a.getString(R.string.doksho_annot_size, String.valueOf(size));
    }

    private static String fmt(float v) {
        return v == Math.round(v) ? String.valueOf(Math.round(v)) : String.format(Locale.US, "%.1f", v);
    }

    // ---- the tools' actions ----------------------------------------------------------------------

    @Override
    public void tapForm(@NonNull final AnnotSession.Hit hit) {
        final int pageIndex = hit.page.index.docIndex;
        float[] list = s.widgets.get(pageIndex);
        if (list == null) {
            s.want(pageIndex, true, () -> {
                layer.invalidate();
                if (s.widgets.containsKey(pageIndex)) tapForm(hit);
            });
            return;
        }
        int found = -1;
        for (int i = list.length / DokshoPdf.W_STRIDE - 1; i >= 0; i--) {
            int o = i * DokshoPdf.W_STRIDE;
            if (hit.nx >= list[o] && hit.nx <= list[o + 2] && hit.ny >= list[o + 1] && hit.ny <= list[o + 3]) {
                found = i;
                break;
            }
        }
        if (found < 0) {
            DokshoViews.toast(a, a.getString(R.string.doksho_annot_no_field));
            return;
        }
        final int index = found;
        int o = index * DokshoPdf.W_STRIDE;
        int type = (int) list[o + 4];
        int flags = (int) list[o + 5];
        final int maxLen = (int) list[o + 6];
        if ((flags & DokshoPdf.F_READ_ONLY) != 0) {
            DokshoViews.toast(a, a.getString(R.string.doksho_annot_read_only));
            return;
        }
        switch (type) {
            case DokshoPdf.W_CHECKBOX:
            case DokshoPdf.W_RADIO:
                s.edit(pageIndex, (doc, page) -> DokshoPdf.toggleWidget(doc, page, index), this::failedIfNot);
                return;
            case DokshoPdf.W_TEXT: {
                final float[] w = list;
                s.query(pageIndex, (doc, page) -> new String[]{DokshoPdf.widgetValue(doc, page, index),
                        DokshoPdf.widgetFont(doc, page, index)}, r -> {
                    if (r == null) return;
                    new FormEditor(this, a, s, layer, hit.page, w, o, bar.getBottom(), r[0] == null ? "" : r[0], r[1],
                            (text, path, builtin, size, color, own) -> fill(pageIndex, index, text, path, builtin, size, color, own));
                });
                return;
            }
            case DokshoPdf.W_COMBOBOX:
            case DokshoPdf.W_LISTBOX:
                s.query(pageIndex, (doc, page) -> new Object[]{DokshoPdf.widgetOptions(doc, page, index),
                        DokshoPdf.widgetValue(doc, page, index)}, r -> {
                    if (r == null || r[0] == null) return;
                    choiceDialog((String[]) r[0], (String) r[1], v -> s.edit(pageIndex,
                            (doc, page) -> DokshoPdf.setWidgetValue(doc, page, index, v, -1, 0, 0), this::failedIfNot));
                });
                return;
            default:
                DokshoViews.toast(a, a.getString(R.string.doksho_annot_unsupported));
        }
    }

    /** Writes a text field: its own style, a built-in font, or a font file embedded as a subset. */
    private void fill(int pageIndex, int index, String text, @Nullable String path, int builtin, int size, int color,
                      boolean own) {
        if (own || path == null) {
            s.edit(pageIndex, (doc, page) -> DokshoPdf.setWidgetValue(doc, page, index, text, own ? -1 : builtin, size, color),
                    this::failedIfNot);
            return;
        }
        final int[] missing = {0};
        s.edit(pageIndex, (doc, page) -> {
            missing[0] = DokshoPdf.setWidgetValueFont(doc, page, index, text, path, size, color);
            return missing[0] >= 0;
        }, ok -> {
            failedIfNot(ok);
            if (ok != null && ok && missing[0] > 0) {
                DokshoViews.toast(a, a.getString(R.string.doksho_annot_font_missing, missing[0]));
            }
        });
    }

    private void failedIfNot(@Nullable Boolean ok) {
        if (ok == null || !ok) DokshoViews.toast(a, a.getString(R.string.doksho_annot_failed));
    }

    @Override
    public void tapText(@NonNull final AnnotSession.Hit hit, final int annotIndex) {
        final Page page = hit.page;
        final int pageIndex = page.index.docIndex;
        if (annotIndex < 0) {
            String p = DokshoUi.s(DokshoUi.ANNOT_TEXT_FONT_FILE);
            String path = p != null && !p.isEmpty() && new java.io.File(p).isFile() ? p : null;
            int size = DokshoUi.i(DokshoUi.ANNOT_TEXT_SIZE);
            // the first line's baseline where the finger was
            float y0 = hit.ny - size * 0.8f / Math.max(1f, s.pageHeightPt(page));
            new TextBoxEditor(this, a, s, layer, page, bar.getBottom(), hit.nx, y0, "", path,
                    DokshoUi.i(DokshoUi.ANNOT_TEXT_FONT), size, DokshoUi.i(DokshoUi.ANNOT_TEXT_COLOR), null,
                    new TextBoxEditor.Done() {
                        @Override
                        public void ok(@NonNull String text, @NonNull float[] r, @Nullable String path, int builtin, int size, int color) {
                            if (text.trim().isEmpty()) return;
                            final String file = TextBoxEditor.fontFile(path, builtin);
                            s.edit(pageIndex, (doc, pg) -> DokshoPdf.addText(doc, pg, r[0], r[1], r[2] - r[0], r[3] - r[1],
                                    text, builtin, size, color, file) >= 0, AnnotTools.this::failedIfNot);
                        }

                        @Override
                        public void delete() {
                        }
                    });
            return;
        }
        boxStyle(pageIndex, annotIndex, st -> {
            if (st == null) return;
            new TextBoxEditor(this, a, s, layer, page, bar.getBottom(), st.rect[0], st.rect[1], st.text, st.path,
                    st.builtin, st.size, st.color, st.rect.clone(), new TextBoxEditor.Done() {
                @Override
                public void ok(@NonNull String t, @NonNull float[] rect, @Nullable String path, int builtin, int size, int color) {
                    if (t.trim().isEmpty()) {
                        delete();
                        return;
                    }
                    final String file = TextBoxEditor.fontFile(path, builtin);
                    s.edit(pageIndex, (doc, p) -> DokshoPdf.editText(doc, p, annotIndex, rect, t, builtin, size, color, file),
                            AnnotTools.this::failedIfNot);
                }

                @Override
                public void delete() {
                    s.edit(pageIndex, (doc, p) -> DokshoPdf.deleteAnnot(doc, p, annotIndex), AnnotTools.this::failedIfNot);
                }
            });
        });
    }

    /** A text box as it stands: its words, place and style. */
    static final class BoxStyle {
        String text;
        float[] rect;
        @Nullable
        String path;
        int builtin;
        int size;
        int color;
    }

    interface BoxStyleRead {
        void on(@Nullable BoxStyle st);
    }

    /** Reads a text box's words, place, font and style (null when it is gone). */
    private void boxStyle(final int pageIndex, final int annotIndex, @NonNull final BoxStyleRead done) {
        s.query(pageIndex, (doc, p) -> new Object[]{DokshoPdf.annotText(doc, p, annotIndex), DokshoPdf.annotStyle(doc, p, annotIndex),
                DokshoPdf.annotFont(doc, p, annotIndex)}, r -> {
            float[] all = s.annots.get(pageIndex);
            if (r == null || all == null || (annotIndex + 1) * DokshoPdf.A_STRIDE > all.length) {
                done.on(null);
                return;
            }
            final int o = annotIndex * DokshoPdf.A_STRIDE;
            BoxStyle st = new BoxStyle();
            st.text = r[0] == null ? "" : (String) r[0];
            st.rect = new float[]{all[o + 1], all[o + 2], all[o + 3], all[o + 4]};
            float[] style = (float[]) r[1];
            st.builtin = style != null ? (int) style[0] : DokshoUi.i(DokshoUi.ANNOT_TEXT_FONT);
            st.size = style != null && style[1] > 0 ? Math.round(style[1]) : DokshoUi.i(DokshoUi.ANNOT_TEXT_SIZE);
            st.color = style != null ? 0xFF000000 | (int) style[2] : DokshoUi.i(DokshoUi.ANNOT_TEXT_COLOR);
            String file = (String) r[2];
            // a built-in's own file stays "the built-in"
            st.path = file == null || file.isEmpty() || file.equals(FormFonts.builtinFile(st.builtin)) ? null : file;
            done.on(st);
        });
    }

    @Override
    public void dragPreview(@NonNull final Page page, final int annotIndex, @NonNull final AnnotLayer.PreviewReady ready) {
        boxStyle(page.index.docIndex, annotIndex, st -> {
            if (st == null) return;
            String file = st.path != null ? st.path : FormFonts.builtinFile(st.builtin);
            FieldPreview p = FieldPreview.textBox(page, st.rect.clone(), FormFonts.typeface(file, st.builtin), st.size, st.color);
            p.text = st.text;
            p.cover = st.rect;
            ready.on(p);
        });
    }

    @Override
    public void moveText(@NonNull Page page, final int annotIndex, final float[] rect) {
        s.edit(page.index.docIndex, (doc, p) -> DokshoPdf.editText(doc, p, annotIndex, rect, null, -1, 0, 0, null), ok -> {
            layer.moveDone();
            failedIfNot(ok);
        });
    }

    private interface Chosen {
        void on(String value);
    }

    private void choiceDialog(String[] options, @Nullable String value, final Chosen chosen) {
        int ink = DokshoViews.ink();
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(16), dp(20), dp(14));
        box.setBackground(DokshoViews.panelBackground(a));
        box.setTag(DokshoViews.NO_SKIN);
        box.addView(DokshoViews.text(a, a.getString(R.string.doksho_annot_field), 18, ink, true));
        final AlertDialog dialog = DokshoViews.boxDialog(a, box, true);
        for (final String o : options) {
            boolean on = o.equals(value);
            TextView t = DokshoViews.text(a, (on ? "● " : "○ ") + o, 16, ink, on);
            t.setPadding(dp(6), dp(11), dp(6), dp(11));
            t.setOnClickListener(v -> {
                dialog.dismiss();
                chosen.on(o);
            });
            box.addView(t);
        }
        LinearLayout buttons = DokshoViews.buttonRow(a);
        buttons.addView(DokshoViews.pill(a, a.getString(R.string.doksho_eim_cancel), v -> dialog.dismiss()));
        box.addView(buttons);
        dialog.show();
        DokshoViews.transparentWindow(dialog);
    }
}
