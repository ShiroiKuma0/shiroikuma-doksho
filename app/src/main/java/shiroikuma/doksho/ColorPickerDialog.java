package shiroikuma.doksho;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.foobnix.pdf.info.R;

import java.util.Locale;

import static shiroikuma.doksho.DokshoViews.dp;

/**
 * The house colour picker (kxkb's {@code ColorPicker}, ported): above everything a row of one-click
 * swatches — the colours chosen before, newest first, topped up with the house colours; then a
 * preview filled with the colour and labelled with its {@code #AARRGGBB}; then the four A / R / G /
 * B sliders with their values.
 *
 * <p>Every move applies live through {@code onColor}, so the page and its previews show the colour
 * at once; Cancel (or dismissing) puts the starting colour back, OK keeps it and remembers it.
 */
public final class ColorPickerDialog {

    public interface OnColor {
        void onColor(int color);
    }

    private ColorPickerDialog() {
    }

    public static void show(@NonNull Activity activity, @NonNull String title, final int initial, @NonNull OnColor onColor) {
        final int ink = DokshoViews.ink();
        final int[] argb = {Color.alpha(initial), Color.red(initial), Color.green(initial), Color.blue(initial)};
        final SeekBar[] bars = new SeekBar[4];
        final TextView[] values = new TextView[4];

        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(activity, 20), dp(activity, 16), dp(activity, 20), dp(activity, 16));
        box.setBackground(DokshoViews.panelBackground(activity));
        box.setTag(DokshoViews.NO_SKIN);

        TextView head = DokshoViews.text(activity, title, 18, ink, true);
        head.setPadding(0, 0, 0, dp(activity, 10));
        box.addView(head);

        final TextView preview = DokshoViews.text(activity, "", 16, ink, false);
        preview.setGravity(Gravity.CENTER);
        preview.setMinHeight(dp(activity, 52));

        final boolean[] applying = {false};
        final Runnable refresh = () -> {
            int c = Color.argb(argb[0], argb[1], argb[2], argb[3]);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(c);
            bg.setStroke(Math.max(1, dp(activity, 1.5f)), ink);
            bg.setCornerRadius(dp(activity, 6));
            preview.setBackground(bg);
            preview.setText(String.format(Locale.ROOT, "#%08X", c));
            preview.setTextColor(readableOn(c));
            for (int i = 0; i < 4; i++) values[i].setText(String.valueOf(argb[i]));
            onColor.onColor(c);
        };
        final Runnable setBars = () -> {
            applying[0] = true;
            for (int i = 0; i < 4; i++) bars[i].setProgress(argb[i]);
            applying[0] = false;
        };

        // one-click swatches: the colours picked before
        LinearLayout swatches = new LinearLayout(activity);
        swatches.setOrientation(LinearLayout.HORIZONTAL);
        swatches.setTag(DokshoViews.NO_SKIN);
        for (final int sw : DokshoUi.recentColors()) {
            View v = new View(activity);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(sw);
            bg.setStroke(Math.max(1, dp(activity, 1.5f)), ink);
            bg.setCornerRadius(dp(activity, 4));
            v.setBackground(bg);
            v.setTag(DokshoViews.NO_SKIN);
            v.setContentDescription(String.format(Locale.ROOT, "#%08X", sw));
            v.setOnClickListener(x -> {
                argb[0] = Color.alpha(sw);
                argb[1] = Color.red(sw);
                argb[2] = Color.green(sw);
                argb[3] = Color.blue(sw);
                setBars.run();
                refresh.run();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(activity, 32), dp(activity, 32));
            lp.rightMargin = dp(activity, 6);
            swatches.addView(v, lp);
        }
        HorizontalScrollView swatchScroll = new HorizontalScrollView(activity);
        swatchScroll.setTag(DokshoViews.NO_SKIN);
        swatchScroll.addView(swatches);
        LinearLayout.LayoutParams swLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        swLp.bottomMargin = dp(activity, 14);
        box.addView(swatchScroll, swLp);

        LinearLayout.LayoutParams pvLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pvLp.bottomMargin = dp(activity, 14);
        box.addView(preview, pvLp);

        String[] labels = {"A", "R", "G", "B"};
        for (int i = 0; i < 4; i++) {
            final int idx = i;
            LinearLayout row = new LinearLayout(activity);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setTag(DokshoViews.NO_SKIN);
            TextView label = DokshoViews.text(activity, labels[i], 15, ink, true);
            row.addView(label, new LinearLayout.LayoutParams(dp(activity, 22), ViewGroup.LayoutParams.WRAP_CONTENT));
            SeekBar bar = new SeekBar(activity);
            bar.setMax(255);
            bar.setProgress(argb[i]);
            tint(bar, ink);
            bar.setTag(DokshoViews.NO_SKIN);
            bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    if (applying[0]) return;
                    argb[idx] = progress;
                    refresh.run();
                }

                @Override
                public void onStartTrackingTouch(SeekBar seekBar) {
                }

                @Override
                public void onStopTrackingTouch(SeekBar seekBar) {
                }
            });
            bars[i] = bar;
            row.addView(bar, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            TextView value = DokshoViews.text(activity, "", 15, ink, false);
            value.setGravity(Gravity.END);
            values[i] = value;
            row.addView(value, new LinearLayout.LayoutParams(dp(activity, 40), ViewGroup.LayoutParams.WRAP_CONTENT));
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rowLp.bottomMargin = dp(activity, 6);
            box.addView(row, rowLp);
        }

        final AlertDialog dialog = DokshoViews.boxDialog(activity, box, true);
        final boolean[] kept = {false};
        LinearLayout buttons = DokshoViews.buttonRow(activity);
        buttons.setGravity(Gravity.CENTER_VERTICAL);
        buttons.addView(DokshoViews.pill(activity, activity.getString(R.string.doksho_eim_cancel), v -> dialog.dismiss()));
        buttons.addView(new View(activity), new LinearLayout.LayoutParams(0, 0, 1f));
        buttons.addView(DokshoViews.pill(activity, activity.getString(R.string.doksho_eim_ok), v -> {
            kept[0] = true;
            int c = Color.argb(argb[0], argb[1], argb[2], argb[3]);
            DokshoUi.rememberColor(c);
            onColor.onColor(c);
            dialog.dismiss();
        }));
        box.addView(buttons);
        dialog.setOnDismissListener(d -> {
            if (!kept[0]) onColor.onColor(initial);
        });
        refresh.run();
        dialog.show();
        DokshoViews.transparentWindow(dialog);
    }

    static void tint(@NonNull SeekBar bar, int color) {
        ColorStateList c = ColorStateList.valueOf(color);
        bar.setThumbTintList(c);
        bar.setProgressTintList(c);
        bar.setProgressBackgroundTintList(c);
    }

    /** Black or white, whichever reads on {@code c} (the see-through case reads as the dark ground). */
    static int readableOn(int c) {
        if (Color.alpha(c) < 128) return Color.WHITE;
        double lum = 0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c);
        return lum < 128 ? Color.WHITE : Color.BLACK;
    }
}
