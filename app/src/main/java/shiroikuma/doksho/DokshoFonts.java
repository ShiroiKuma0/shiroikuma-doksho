package shiroikuma.doksho;

import android.content.Context;
import android.database.Cursor;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.provider.OpenableColumns;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The UI fonts: the system families plus every font 白い熊 imported. An import COPIES the file into
 * the app's own storage ({@code files/fonts}), like kxkb and denwa do — the choice then survives
 * the original being moved, needs no storage permission to load, and travels in the Export / Import
 * "UI" category.
 *
 * <p>A font is identified by a string: {@code ""} is the system default, {@code "@serif"},
 * {@code "@monospace"}, … are system families, anything else is a file name in the fonts dir.
 */
public final class DokshoFonts {

    public static final String SYSTEM = "";
    private static final String[] SYSTEM_FAMILIES = {
            "@sans-serif", "@sans-serif-condensed", "@sans-serif-light", "@sans-serif-medium",
            "@serif", "@monospace", "@cursive"};

    private static final Map<String, Typeface> CACHE = new HashMap<>();

    private DokshoFonts() {
    }

    @NonNull
    public static File dir(@NonNull Context c) {
        File d = new File(c.getApplicationContext().getFilesDir(), "fonts");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    /** Every choice, in picker order: system default, the system families, then the imports A–Z. */
    @NonNull
    public static List<String> all(@NonNull Context c) {
        List<String> out = new ArrayList<>();
        out.add(SYSTEM);
        out.addAll(Arrays.asList(SYSTEM_FAMILIES));
        File[] files = dir(c).listFiles();
        if (files != null) {
            List<String> names = new ArrayList<>();
            for (File f : files) {
                if (f.isFile() && isFontName(f.getName())) names.add(f.getName());
            }
            names.sort(String.CASE_INSENSITIVE_ORDER);
            out.addAll(names);
        }
        return out;
    }

    public static boolean isFontName(@Nullable String name) {
        if (name == null) return false;
        String n = name.toLowerCase(Locale.ROOT);
        return n.endsWith(".ttf") || n.endsWith(".otf") || n.endsWith(".ttc");
    }

    @NonNull
    public static String displayName(@NonNull Context c, @Nullable String id) {
        if (id == null || id.isEmpty()) return c.getString(com.foobnix.pdf.info.R.string.doksho_font_system);
        if (id.startsWith("@")) return id.substring(1);
        int dot = id.lastIndexOf('.');
        return dot > 0 ? id.substring(0, dot) : id;
    }

    /** The regular face of a font id; never null — anything unreadable falls back to the default. */
    @NonNull
    public static Typeface base(@NonNull Context c, @Nullable String id) {
        if (id == null || id.isEmpty()) return Typeface.DEFAULT;
        synchronized (CACHE) {
            Typeface cached = CACHE.get(id);
            if (cached != null) return cached;
            Typeface tf;
            try {
                if (id.startsWith("@")) {
                    tf = Typeface.create(id.substring(1), Typeface.NORMAL);
                } else {
                    tf = Typeface.createFromFile(new File(dir(c), id));
                }
            } catch (Exception e) {
                tf = Typeface.DEFAULT;
            }
            CACHE.put(id, tf);
            return tf;
        }
    }

    /** The font at a CSS weight (100–900); below API 28 the nearest of regular / bold. */
    @NonNull
    public static Typeface at(@NonNull Context c, @Nullable String id, int weight, boolean italic) {
        Typeface base = base(c, id);
        weight = Math.max(1, Math.min(1000, weight));
        if (Build.VERSION.SDK_INT >= 28) {
            return Typeface.create(base, weight, italic);
        }
        int style = (weight >= 600 ? Typeface.BOLD : Typeface.NORMAL) | (italic ? Typeface.ITALIC : 0);
        return Typeface.create(base, style);
    }

    /**
     * Copy a picked font into the fonts dir under its own display name.
     *
     * @return the new font id
     * @throws IOException when it is not a .ttf / .otf / .ttc, or cannot be read or loaded
     */
    @NonNull
    public static String importFont(@NonNull Context c, @NonNull Uri uri) throws IOException {
        String name = null;
        try (Cursor cursor = c.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME},
                null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) name = cursor.getString(0);
        } catch (Exception ignored) {
            // fall through to the Uri's last segment
        }
        if (name == null) name = uri.getLastPathSegment();
        if (name != null) name = name.substring(name.lastIndexOf('/') + 1);
        if (!isFontName(name)) throw new IOException("not a font file (.ttf / .otf / .ttc): " + name);
        File target = new File(dir(c), name);
        try (InputStream in = c.getContentResolver().openInputStream(uri);
             OutputStream out = new FileOutputStream(target)) {
            if (in == null) throw new IOException("cannot read " + name);
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
        try {
            Typeface.createFromFile(target);
        } catch (Exception e) {
            //noinspection ResultOfMethodCallIgnored
            target.delete();
            throw new IOException("not a loadable font: " + name);
        }
        synchronized (CACHE) {
            CACHE.remove(name);
        }
        return name;
    }

    public static void clearCache() {
        synchronized (CACHE) {
            CACHE.clear();
        }
    }
}
