package shiroikuma.doksho;

import android.app.Activity;
import android.app.AlertDialog;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.foobnix.pdf.info.R;

import java.util.List;

import static shiroikuma.doksho.DokshoViews.dp;

/**
 * The font picker: every choice drawn in its own glyphs — the name plus a sample line, set in that
 * very font — the current one ticked; below the list, an Import pill that brings in a .ttf / .otf
 * from anywhere (copied into the app, see {@link DokshoFonts}).
 */
public final class FontPickerDialog {

    public interface OnFont {
        void onFont(@NonNull String fontId);
    }

    private FontPickerDialog() {
    }

    public static void show(@NonNull Activity activity, @NonNull String title, @Nullable String current,
                            int weight, @NonNull OnFont onFont, @NonNull Runnable onImport) {
        final int ink = DokshoViews.ink();
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(activity, 20), dp(activity, 16), dp(activity, 20), dp(activity, 16));
        box.setBackground(DokshoViews.panelBackground(activity));
        box.setTag(DokshoViews.NO_SKIN);

        TextView head = DokshoViews.text(activity, title, 18, ink, true);
        head.setPadding(0, 0, 0, dp(activity, 8));
        box.addView(head);

        LinearLayout list = new LinearLayout(activity);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setTag(DokshoViews.NO_SKIN);
        ScrollView scroll = new ScrollView(activity);
        scroll.setTag(DokshoViews.NO_SKIN);
        scroll.addView(list);
        box.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                Math.round(activity.getResources().getDisplayMetrics().heightPixels * 0.5f)));

        final AlertDialog dialog = DokshoViews.boxDialog(activity, box, true);
        String cur = current == null ? "" : current;
        String sample = activity.getString(R.string.doksho_font_sample);
        List<String> fonts = DokshoFonts.all(activity);
        for (final String id : fonts) {
            LinearLayout item = new LinearLayout(activity);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setPadding(dp(activity, 6), dp(activity, 6), dp(activity, 6), dp(activity, 6));
            item.setTag(DokshoViews.NO_SKIN);
            item.setClickable(true);
            item.setBackgroundResource(android.R.drawable.list_selector_background);
            TextView name = DokshoViews.text(activity, (id.equals(cur) ? "✓  " : "") + DokshoFonts.displayName(activity, id), 18, ink, false);
            name.setTypeface(DokshoFonts.at(activity, id, weight, false));
            TextView line = DokshoViews.text(activity, sample, 14, ink, false);
            line.setTypeface(DokshoFonts.at(activity, id, weight, false));
            line.setAlpha(0.85f);
            line.setSingleLine(true);
            item.addView(name);
            item.addView(line);
            item.setOnClickListener(v -> {
                dialog.dismiss();
                onFont.onFont(id);
            });
            list.addView(item);
        }

        LinearLayout buttons = DokshoViews.buttonRow(activity);
        buttons.setGravity(Gravity.CENTER_VERTICAL);
        buttons.addView(DokshoViews.pill(activity, activity.getString(R.string.doksho_eim_cancel), v -> dialog.dismiss()));
        buttons.addView(new View(activity), new LinearLayout.LayoutParams(0, 0, 1f));
        buttons.addView(DokshoViews.pill(activity, activity.getString(R.string.doksho_font_import), v -> {
            dialog.dismiss();
            onImport.run();
        }));
        box.addView(buttons);
        dialog.show();
        DokshoViews.transparentWindow(dialog);
    }
}
