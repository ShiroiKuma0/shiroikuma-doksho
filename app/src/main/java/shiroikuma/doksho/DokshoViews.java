package shiroikuma.doksho;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.foobnix.pdf.info.R;

/**
 * The house look, as view builders — ported from the sister apps' {@code ShiroikumaViews}
 * (termux-api, raikidoban): bordered rounded boxes, pill buttons, the black-yellow info / confirm
 * dialogs. Unlike those, every colour, border width and corner here is read from {@link DokshoUi}
 * (the "Dialogs & popups" group of the UI page), so the page's own dialogs follow what it sets.
 */
public final class DokshoViews {

    /** Tag on any view the app-wide skin must leave exactly as drawn (our own surfaces, previews). */
    public static final String NO_SKIN = "doksho-noskin";

    private DokshoViews() {
    }

    public static int dp(@NonNull Context context, float v) {
        return Math.round(v * context.getResources().getDisplayMetrics().density);
    }

    public static int ink() {
        return DokshoUi.i(DokshoUi.C_DLG_TEXT);
    }

    public static int ground() {
        return DokshoUi.i(DokshoUi.C_DLG_BG);
    }

    public static int border() {
        return DokshoUi.i(DokshoUi.C_DLG_BORDER);
    }

    /** The dialog panel: dialog ground, dialog border at its width and roundness. */
    @NonNull
    public static GradientDrawable panelBackground(@NonNull Context context) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(ground());
        int w = DokshoUi.borderPx(context, DokshoUi.DLG_BORDER_W);
        if (w > 0) bg.setStroke(w, border());
        bg.setCornerRadius(DokshoUi.dp(context, DokshoUi.i(DokshoUi.DLG_RADIUS)));
        return bg;
    }

    /** A smaller bordered box inside a panel (the directory box). */
    @NonNull
    public static GradientDrawable boxBackground(@NonNull Context context, int strokeColor) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(ground());
        bg.setStroke(Math.max(1, dp(context, 2)), strokeColor);
        bg.setCornerRadius(Math.max(0, DokshoUi.dp(context, DokshoUi.i(DokshoUi.DLG_RADIUS)) * 0.7f));
        return bg;
    }

    @NonNull
    public static TextView text(@NonNull Context context, @Nullable CharSequence s, int sizeSp, int color, boolean bold) {
        TextView tv = new TextView(context);
        tv.setText(s);
        tv.setTextColor(color);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
        tv.setTypeface(DokshoFonts.at(context, DokshoUi.s(DokshoUi.FONT_FAMILY),
                bold ? Math.max(700, DokshoUi.i(DokshoUi.FONT_WEIGHT)) : DokshoUi.i(DokshoUi.FONT_WEIGHT), false));
        tv.setTag(NO_SKIN);
        return tv;
    }

    @NonNull
    public static CheckBox checkbox(@NonNull Context context, @NonNull String label, boolean bold, int indentPx) {
        CheckBox cb = new CheckBox(context);
        cb.setText(label);
        cb.setTextColor(ink());
        cb.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        cb.setTypeface(DokshoFonts.at(context, DokshoUi.s(DokshoUi.FONT_FAMILY),
                bold ? 700 : DokshoUi.i(DokshoUi.FONT_WEIGHT), false));
        cb.setButtonTintList(ColorStateList.valueOf(ink()));
        cb.setPadding(dp(context, 8) + indentPx, dp(context, 7), 0, dp(context, 7));
        cb.setTag(NO_SKIN);
        return cb;
    }

    @NonNull
    public static View divider(@NonNull Context context, int topGapDp) {
        View v = new View(context);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(context, 1)));
        lp.topMargin = dp(context, topGapDp);
        v.setLayoutParams(lp);
        v.setBackgroundColor(border());
        v.setAlpha(0.4f);
        v.setTag(NO_SKIN);
        return v;
    }

    /** The house pill: ground fill, 1.5dp border stroke, fully round, ripple, no all-caps. */
    @NonNull
    public static Button pill(@NonNull Context context, @NonNull String label, @Nullable View.OnClickListener onClick) {
        Button b = new Button(context);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(ink());
        b.setTypeface(DokshoFonts.at(context, DokshoUi.s(DokshoUi.FONT_FAMILY), DokshoUi.i(DokshoUi.FONT_WEIGHT), false));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(ground());
        bg.setStroke(Math.max(1, Math.round(1.5f * context.getResources().getDisplayMetrics().density)), border());
        bg.setCornerRadius(dp(context, 50));
        b.setBackground(new RippleDrawable(ColorStateList.valueOf((ink() & 0x00FFFFFF) | 0x33000000), bg, null));
        b.setStateListAnimator(null);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setPadding(dp(context, 20), dp(context, 8), dp(context, 20), dp(context, 8));
        b.setOnClickListener(onClick);
        b.setTag(NO_SKIN);
        b.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return b;
    }

    /** A title + body inside the bordered panel; the caller appends its button row. */
    @NonNull
    public static LinearLayout infoBox(@NonNull Context context, @NonNull String title, @NonNull CharSequence body) {
        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(context, 22), dp(context, 20), dp(context, 22), dp(context, 16));
        box.setBackground(panelBackground(context));
        box.setTag(NO_SKIN);
        box.addView(text(context, title, 19, ink(), true));
        TextView bodyView = text(context, body, 14, ink(), false);
        bodyView.setPadding(0, dp(context, 10), 0, 0);
        box.addView(bodyView);
        return box;
    }

    /** A dialog whose only surface is {@code content} — the window itself is transparent. */
    @NonNull
    public static AlertDialog boxDialog(@NonNull Activity activity, @NonNull View content, boolean cancelable) {
        ScrollView scroll = new ScrollView(activity);
        int m = dp(activity, 10);
        scroll.setPadding(m, m, m, m);
        scroll.setClipToPadding(false);
        scroll.setTag(NO_SKIN);
        scroll.addView(content, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        AlertDialog dialog = new AlertDialog.Builder(activity).setView(scroll).create();
        dialog.setCancelable(cancelable);
        dialog.setCanceledOnTouchOutside(cancelable);
        return dialog;
    }

    public static void transparentWindow(@NonNull AlertDialog dialog) {
        Window window = dialog.getWindow();
        if (window != null) window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
    }

    @NonNull
    public static LinearLayout buttonRow(@NonNull Context context) {
        LinearLayout buttons = new LinearLayout(context);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.END);
        buttons.setPadding(0, dp(context, 16), 0, 0);
        buttons.setTag(NO_SKIN);
        return buttons;
    }

    /** A bordered info dialog with one OK pill, right-aligned; {@code onOk} runs after it closes. */
    public static void showInfo(@NonNull Activity activity, @NonNull String title, @NonNull CharSequence body,
                                boolean cancelable, @Nullable Runnable onOk) {
        LinearLayout box = infoBox(activity, title, body);
        final AlertDialog dialog = boxDialog(activity, box, cancelable);
        LinearLayout buttons = buttonRow(activity);
        buttons.addView(pill(activity, activity.getString(R.string.doksho_eim_ok), v -> {
            dialog.dismiss();
            if (onOk != null) onOk.run();
        }));
        box.addView(buttons);
        dialog.show();
        transparentWindow(dialog);
    }

    /** A bordered confirm dialog: Cancel on the left, the action pill on the right. */
    public static void showConfirm(@NonNull Activity activity, @NonNull String title, @NonNull CharSequence body,
                                   @NonNull String actionLabel, @NonNull Runnable onConfirm) {
        LinearLayout box = infoBox(activity, title, body);
        final AlertDialog dialog = boxDialog(activity, box, true);
        LinearLayout buttons = buttonRow(activity);
        buttons.setGravity(Gravity.CENTER_VERTICAL);
        buttons.addView(pill(activity, activity.getString(R.string.doksho_eim_cancel), v -> dialog.dismiss()));
        View spacer = new View(activity);
        buttons.addView(spacer, new LinearLayout.LayoutParams(0, 0, 1f));
        buttons.addView(pill(activity, actionLabel, v -> {
            dialog.dismiss();
            onConfirm.run();
        }));
        box.addView(buttons);
        dialog.show();
        transparentWindow(dialog);
    }

    /** The house toast: bordered pill of dialog ink on the dialog ground. */
    public static void toast(@NonNull Context context, @NonNull CharSequence msg) {
        TextView tv = text(context, msg, 15, ink(), false);
        int h = dp(context, 20);
        int v = dp(context, 12);
        tv.setPadding(h, v, h, v);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(ground());
        bg.setStroke(Math.max(1, dp(context, 2)), border());
        bg.setCornerRadius(dp(context, 18));
        tv.setBackground(bg);
        Toast t = new Toast(context.getApplicationContext());
        //noinspection deprecation
        t.setView(tv);
        t.setDuration(Toast.LENGTH_SHORT);
        t.show();
    }
}
