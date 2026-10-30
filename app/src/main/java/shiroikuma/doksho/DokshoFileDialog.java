package shiroikuma.doksho;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Environment;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.foobnix.pdf.info.R;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Our directory browser, in the house dialog style, on real paths (the app has All files access):
 * the directory in yellow at the top, its subdirectories and matching files beneath, “..” to go up.
 *
 * <ul>
 *   <li>{@link #open} — tap a file to choose it (importing a font);</li>
 *   <li>{@link #save} — walk to a directory, type the name, Save (saving a PDF under a new name;
 *       it starts in the original's directory).</li>
 * </ul>
 */
public final class DokshoFileDialog {

    public interface OnFile {
        void on(@NonNull File file);
    }

    private DokshoFileDialog() {
    }

    /** Where browsing starts when there is nothing better: the shared storage's Download. */
    @NonNull
    public static File downloads() {
        File d = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        return d != null && d.isDirectory() ? d : Environment.getExternalStorageDirectory();
    }

    public static void open(@NonNull Activity a, @NonNull String title, @Nullable File start, @NonNull String[] exts,
                            @NonNull OnFile onFile) {
        show(a, title, start, exts, null, onFile);
    }

    public static void save(@NonNull Activity a, @NonNull String title, @Nullable File start, @NonNull String name,
                            @NonNull String ext, @NonNull OnFile onFile) {
        show(a, title, start, new String[]{ext}, name, onFile);
    }

    private static int dp(Activity a, float v) {
        return DokshoViews.dp(a, v);
    }

    private static void show(final Activity a, String title, @Nullable File start, final String[] exts,
                             @Nullable String name, final OnFile onFile) {
        final boolean saving = name != null;
        final int ink = DokshoViews.ink();
        final File[] dir = {start != null && start.isDirectory() ? start : downloads()};

        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(a, 20), dp(a, 16), dp(a, 20), dp(a, 14));
        box.setBackground(DokshoViews.panelBackground(a));
        box.setTag(DokshoViews.NO_SKIN);
        box.addView(DokshoViews.text(a, title, 18, ink, true));

        final TextView where = DokshoViews.text(a, "", 14, DokshoUi.i(DokshoUi.C_ACCENT), false);
        where.setPadding(0, dp(a, 8), 0, dp(a, 6));
        box.addView(where);

        final LinearLayout list = new LinearLayout(a);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setTag(DokshoViews.NO_SKIN);
        final ScrollView scroll = new ScrollView(a);
        scroll.setTag(DokshoViews.NO_SKIN);
        scroll.addView(list);
        int h = Math.round(a.getResources().getDisplayMetrics().heightPixels * (saving ? 0.42f : 0.55f));
        box.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h));

        final EditText nameEdit;
        if (saving) {
            nameEdit = new EditText(a);
            nameEdit.setText(name);
            nameEdit.setSingleLine(true);
            nameEdit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
            nameEdit.setTextColor(ink);
            nameEdit.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            nameEdit.setBackground(DokshoViews.boxBackground(a, DokshoViews.border()));
            nameEdit.setPadding(dp(a, 12), dp(a, 9), dp(a, 12), dp(a, 9));
            nameEdit.setTag(DokshoViews.NO_SKIN);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(a, 10);
            box.addView(nameEdit, lp);
            // the name without its extension selected, ready to be typed over
            int dot = name.toLowerCase(Locale.ROOT).lastIndexOf('.');
            nameEdit.setSelection(0, dot > 0 ? dot : name.length());
        } else {
            nameEdit = null;
        }

        final AlertDialog dialog = DokshoViews.boxDialog(a, box, true);
        final Runnable[] fill = new Runnable[1];
        fill[0] = () -> {
            where.setText(dir[0].getAbsolutePath());
            list.removeAllViews();
            File parent = dir[0].getParentFile();
            if (parent != null && parent.canRead() && parent.listFiles() != null) {
                list.addView(row(a, "↑  ..", true, v -> {
                    dir[0] = parent;
                    fill[0].run();
                }));
            }
            File[] all = dir[0].listFiles();
            List<File> dirs = new ArrayList<>();
            List<File> files = new ArrayList<>();
            if (all != null) {
                for (File f : all) {
                    if (f.getName().startsWith(".")) continue;
                    if (f.isDirectory()) dirs.add(f);
                    else if (matches(f, exts)) files.add(f);
                }
            }
            dirs.sort((x, y) -> x.getName().compareToIgnoreCase(y.getName()));
            files.sort((x, y) -> x.getName().compareToIgnoreCase(y.getName()));
            for (final File d : dirs) {
                list.addView(row(a, d.getName() + "/", true, v -> {
                    dir[0] = d;
                    fill[0].run();
                }));
            }
            for (final File f : files) {
                list.addView(row(a, f.getName(), false, v -> {
                    if (saving) {
                        nameEdit.setText(f.getName());
                    } else {
                        dialog.dismiss();
                        onFile.on(f);
                    }
                }));
            }
            if (dirs.isEmpty() && files.isEmpty()) {
                TextView none = DokshoViews.text(a, a.getString(R.string.doksho_file_empty), 14,
                        DokshoUi.i(DokshoUi.C_TEXT2), false);
                none.setPadding(dp(a, 6), dp(a, 10), dp(a, 6), dp(a, 10));
                list.addView(none);
            }
            scroll.scrollTo(0, 0);
        };
        fill[0].run();

        LinearLayout buttons = DokshoViews.buttonRow(a);
        buttons.setGravity(Gravity.CENTER_VERTICAL);
        buttons.addView(new View(a), new LinearLayout.LayoutParams(0, 0, 1f));
        buttons.addView(DokshoViews.pill(a, a.getString(R.string.doksho_eim_cancel), v -> dialog.dismiss()));
        if (saving) {
            View gap = new View(a);
            buttons.addView(gap, new LinearLayout.LayoutParams(dp(a, 10), 0));
            buttons.addView(DokshoViews.pill(a, a.getString(R.string.doksho_file_save), v -> {
                String n = nameEdit.getText().toString().trim();
                if (n.isEmpty() || n.contains("/")) {
                    DokshoViews.toast(a, a.getString(R.string.doksho_file_bad_name));
                    return;
                }
                if (!matches(new File(n), exts)) n = n + exts[0];
                dialog.dismiss();
                onFile.on(new File(dir[0], n));
            }));
        }
        box.addView(buttons);
        dialog.show();
        DokshoViews.transparentWindow(dialog);
        if (saving && dialog.getWindow() != null) {
            dialog.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM);
        }
    }

    private static boolean matches(File f, String[] exts) {
        String n = f.getName().toLowerCase(Locale.ROOT);
        return Arrays.stream(exts).anyMatch(e -> n.endsWith(e.toLowerCase(Locale.ROOT)));
    }

    private static TextView row(Activity a, String label, boolean isDir, View.OnClickListener onClick) {
        TextView t = DokshoViews.text(a, label, 16, isDir ? DokshoUi.i(DokshoUi.C_ACCENT) : DokshoViews.ink(), isDir);
        t.setPadding(dp(a, 6), dp(a, 10), dp(a, 6), dp(a, 10));
        t.setOnClickListener(onClick);
        return t;
    }
}
