package shiroikuma.doksho.annot;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Typeface;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.foobnix.android.utils.LOG;
import com.foobnix.pdf.info.R;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import shiroikuma.doksho.DokshoFileDialog;
import shiroikuma.doksho.DokshoFonts;
import shiroikuma.doksho.DokshoUi;
import shiroikuma.doksho.DokshoViews;

/**
 * The fonts a form field can be filled in: the three every PDF viewer has built in (Sans, Serif,
 * Mono — MuPDF's own drawing), and any TrueType / OpenType file — imported ones (the app's fonts
 * directory, shared with the UI page's font picker, travels in Export) and Android's own
 * ({@code /system/fonts}). A file font is embedded in the PDF as a subset of the glyphs used.
 *
 * <p>A font is a path (a file) or, with a null path, a built-in code 0–2.
 */
final class FormFonts {

    static final String[] EXTS = {".ttf", ".otf", ".ttc"};
    private static final Map<String, Typeface> CACHE = new HashMap<>();

    interface OnPick {
        void on(@Nullable String path, int builtin);
    }

    private FormFonts() {
    }

    @NonNull
    /** Android's own files for the three built-ins, best first. */
    private static final String[][] BUILTIN_FILES = {
            {"RobotoStatic-Regular.ttf", "Roboto-Regular.ttf", "NotoSans-Regular.ttf", "DroidSans.ttf"},
            {"NotoSerif-Regular.ttf", "DroidSerif-Regular.ttf", "SourceSerifPro-Regular.ttf"},
            {"DroidSansMono.ttf", "RobotoMono-Regular.ttf", "CutiveMono.ttf", "NotoSansMono-Regular.ttf"},
    };

    /**
     * The file a text box in built-in font 0–2 is drawn and embedded in (text boxes always embed:
     * MuPDF's base-14 drawing loses any letter outside Latin-1, ř and č among them), or null.
     */
    @Nullable
    static String builtinFile(int font) {
        String[] names = BUILTIN_FILES[font >= 0 && font < BUILTIN_FILES.length ? font : 0];
        for (String n : names) {
            File f = new File("/system/fonts", n);
            if (f.isFile()) return f.getAbsolutePath();
        }
        return font != 0 ? builtinFile(0) : null;
    }

    static Typeface builtinTypeface(int font) {
        return font == 1 ? Typeface.SERIF : font == 2 ? Typeface.MONOSPACE : Typeface.SANS_SERIF;
    }

    /** The face to draw a font in; the built-in look when the file cannot be read. */
    @NonNull
    static Typeface typeface(@Nullable String path, int builtin) {
        if (path == null || path.isEmpty()) return builtinTypeface(builtin);
        synchronized (CACHE) {
            Typeface t = CACHE.get(path);
            if (t != null) return t;
            try {
                t = Typeface.createFromFile(path);
            } catch (Exception e) {
                t = Typeface.DEFAULT;
            }
            CACHE.put(path, t);
            return t;
        }
    }

    @NonNull
    static String label(@NonNull Context c, @Nullable String path, int builtin) {
        if (path == null || path.isEmpty()) {
            int[] names = {R.string.doksho_annot_font_sans, R.string.doksho_annot_font_serif, R.string.doksho_annot_font_mono};
            return c.getString(names[Math.max(0, Math.min(2, builtin))]);
        }
        String n = new File(path).getName();
        int dot = n.lastIndexOf('.');
        return dot > 0 ? n.substring(0, dot) : n;
    }

    private static boolean isFont(File f) {
        return f.isFile() && f.length() > 1024 && DokshoFonts.isFontName(f.getName());
    }

    @NonNull
    private static List<File> files(@Nullable File dir) {
        List<File> out = new ArrayList<>();
        File[] all = dir == null ? null : dir.listFiles();
        if (all != null) for (File f : all) if (isFont(f)) out.add(f);
        out.sort((x, y) -> x.getName().compareToIgnoreCase(y.getName()));
        return out;
    }

    /** One row of the list: a heading, the import action, or a font. */
    private static final class Row {
        final int kind; // 0 heading, 1 import, 2 font
        final String text;
        final String path;
        final int builtin;

        Row(int kind, String text, String path, int builtin) {
            this.kind = kind;
            this.text = text;
            this.path = path;
            this.builtin = builtin;
        }
    }

    static void pick(@NonNull final Activity a, @Nullable final String current, final int currentBuiltin,
                     @NonNull final OnPick onPick) {
        final List<Row> rows = new ArrayList<>();
        rows.add(new Row(1, a.getString(R.string.doksho_annot_font_import), null, 0));
        rows.add(new Row(0, a.getString(R.string.doksho_annot_fonts_builtin), null, 0));
        for (int i = 0; i < 3; i++) rows.add(new Row(2, label(a, null, i), null, i));
        List<File> mine = files(DokshoFonts.dir(a));
        if (!mine.isEmpty()) {
            rows.add(new Row(0, a.getString(R.string.doksho_annot_fonts_mine), null, 0));
            for (File f : mine) rows.add(new Row(2, label(a, f.getPath(), 0), f.getPath(), 0));
        }
        List<File> system = files(new File("/system/fonts"));
        if (!system.isEmpty()) {
            rows.add(new Row(0, a.getString(R.string.doksho_annot_fonts_system), null, 0));
            for (File f : system) rows.add(new Row(2, label(a, f.getPath(), 0), f.getPath(), 0));
        }

        final int ink = DokshoViews.ink();
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(DokshoViews.dp(a, 20), DokshoViews.dp(a, 16), DokshoViews.dp(a, 20), DokshoViews.dp(a, 14));
        box.setBackground(DokshoViews.panelBackground(a));
        box.setTag(DokshoViews.NO_SKIN);
        box.addView(DokshoViews.text(a, a.getString(R.string.doksho_annot_font), 18, ink, true));
        TextView note = DokshoViews.text(a, a.getString(R.string.doksho_annot_font_embed_note), 13,
                DokshoUi.i(DokshoUi.C_TEXT2), false);
        note.setPadding(0, DokshoViews.dp(a, 6), 0, DokshoViews.dp(a, 6));
        box.addView(note);

        final ListView list = new ListView(a);
        list.setTag(DokshoViews.NO_SKIN);
        list.setDivider(null);
        box.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                Math.round(a.getResources().getDisplayMetrics().heightPixels * 0.55f)));
        final AlertDialog dialog = DokshoViews.boxDialog(a, box, true);
        final String sample = a.getString(R.string.doksho_annot_font_sample);
        list.setAdapter(new BaseAdapter() {
            @Override
            public int getCount() {
                return rows.size();
            }

            @Override
            public Object getItem(int i) {
                return rows.get(i);
            }

            @Override
            public long getItemId(int i) {
                return i;
            }

            @Override
            public int getViewTypeCount() {
                return 3;
            }

            @Override
            public int getItemViewType(int i) {
                return rows.get(i).kind;
            }

            @Override
            public View getView(int i, View v, ViewGroup parent) {
                Row r = rows.get(i);
                TextView t = v instanceof TextView ? (TextView) v : DokshoViews.text(a, "", 16, ink, false);
                t.setTag(DokshoViews.NO_SKIN);
                int pv = DokshoViews.dp(a, r.kind == 0 ? 12 : 9);
                t.setPadding(DokshoViews.dp(a, 6), pv, DokshoViews.dp(a, 6), pv);
                if (r.kind == 0) {
                    t.setText(r.text);
                    t.setTextColor(DokshoUi.i(DokshoUi.C_TEXT2));
                    t.setTypeface(Typeface.DEFAULT_BOLD);
                    t.setTextSize(13);
                } else if (r.kind == 1) {
                    t.setText(r.text);
                    t.setTextColor(DokshoUi.i(DokshoUi.C_ACCENT));
                    t.setTypeface(Typeface.DEFAULT_BOLD);
                    t.setTextSize(16);
                } else {
                    boolean on = r.path == null ? (current == null || current.isEmpty()) && r.builtin == currentBuiltin
                            : r.path.equals(current);
                    t.setText((on ? "● " : "") + r.text + " — " + sample);
                    t.setTextColor(on ? DokshoUi.i(DokshoUi.C_ACCENT) : ink);
                    t.setTypeface(typeface(r.path, r.builtin));
                    t.setTextSize(17);
                }
                return t;
            }
        });
        list.setOnItemClickListener((parent, view, i, id) -> {
            Row r = rows.get(i);
            if (r.kind == 0) return;
            dialog.dismiss();
            if (r.kind == 1) {
                importFont(a, onPick);
            } else {
                onPick.on(r.path, r.builtin);
            }
        });
        LinearLayout buttons = DokshoViews.buttonRow(a);
        buttons.addView(DokshoViews.pill(a, a.getString(R.string.doksho_eim_cancel), v -> dialog.dismiss()));
        box.addView(buttons);
        dialog.show();
        DokshoViews.transparentWindow(dialog);
    }

    /** Copies a font file from storage into the app's fonts directory, then picks it. */
    private static void importFont(@NonNull final Activity a, @NonNull final OnPick onPick) {
        DokshoFileDialog.open(a, a.getString(R.string.doksho_annot_font_import), DokshoFileDialog.downloads(), EXTS, f -> {
            File target = new File(DokshoFonts.dir(a), f.getName());
            try (InputStream in = new FileInputStream(f); OutputStream out = new FileOutputStream(target)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            } catch (IOException e) {
                LOG.e(e);
                DokshoViews.toast(a, a.getString(R.string.doksho_annot_font_import_failed, f.getName()));
                return;
            }
            try {
                Typeface.createFromFile(target);
            } catch (Exception e) {
                //noinspection ResultOfMethodCallIgnored
                target.delete();
                DokshoViews.toast(a, a.getString(R.string.doksho_annot_font_import_failed, f.getName()));
                return;
            }
            synchronized (CACHE) {
                CACHE.remove(target.getPath());
            }
            DokshoFonts.clearCache();
            onPick.on(target.getPath(), 0);
        });
    }
}
