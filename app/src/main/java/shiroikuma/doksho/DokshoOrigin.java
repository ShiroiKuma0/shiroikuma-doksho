package shiroikuma.doksho;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.provider.OpenableColumns;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.foobnix.android.utils.LOG;
import com.foobnix.model.AppSP;

import org.json.JSONObject;

import java.io.File;
import java.net.URI;

/**
 * Where a book opened from another app really is.
 *
 * <p>Librera opens a book it is handed as a real file when it can work out the path, and otherwise
 * copies it into the app's own {@code TempDownloads} — so an edited PDF would be saved into that
 * copy, invisible to everything else. Two fixes, both called from {@code OpenerActivity}:
 * <ul>
 *   <li>{@link #realFile} — more ways to find the real path behind a {@code content://} URI (storage
 *       and download document ids, the media store's path column, a path inside the URI), tried
 *       before Librera falls back to copying;</li>
 *   <li>{@link #opened} — when it does copy, the source URI, its name and the app that sent it are
 *       remembered against the copy, so saving can write back to the original
 *       ({@code annot.SaveOnClose}).</li>
 * </ul>
 * The record lives in its own prefs file ({@code doksho_origin}), never exported.
 */
public final class DokshoOrigin {

    private static final String PREFS = "doksho_origin";

    /** A copy's source: the URI it was read from, its display name, the sending app's label. */
    public static final class Origin {
        @NonNull
        public final Uri uri;
        @NonNull
        public final String name;
        @NonNull
        public final String app;

        Origin(@NonNull Uri uri, @NonNull String name, @NonNull String app) {
            this.uri = uri;
            this.name = name;
            this.app = app;
        }
    }

    private DokshoOrigin() {
    }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    @Nullable
    private static File usable(@Nullable File f, long size) {
        if (f == null || !f.isFile() || !f.canRead()) return null;
        if (size > 0 && f.length() != size) return null;
        return f;
    }

    @Nullable
    private static String column(Context c, Uri uri, String col) {
        try (Cursor cur = c.getContentResolver().query(uri, new String[]{col}, null, null, null)) {
            if (cur != null && cur.moveToFirst() && !cur.isNull(0)) return cur.getString(0);
        } catch (Exception ignored) {
            // a provider without that column
        }
        return null;
    }

    @Nullable
    private static String mediaPath(Context c, long id) {
        Uri files = MediaStore.Files.getContentUri("external");
        try (Cursor cur = c.getContentResolver().query(files, new String[]{MediaStore.MediaColumns.DATA},
                MediaStore.MediaColumns._ID + "=?", new String[]{String.valueOf(id)}, null)) {
            if (cur != null && cur.moveToFirst() && !cur.isNull(0)) return cur.getString(0);
        } catch (Exception ignored) {
            // no such row
        }
        return null;
    }

    /**
     * The real file behind a book another app sent, or null (then Librera copies it). First the
     * sender's own word: 書類管理 (shiroikuma-shoruikanri, a Material Files fork) and Material Files
     * put the path in {@code <their package>.extra.PATH_URI} as a {@code file://} URI.
     */
    @Nullable
    public static File realFile(@NonNull Context c, @Nullable Intent intent) {
        if (intent == null) return null;
        Bundle extras = intent.getExtras();
        if (extras != null) {
            for (String key : extras.keySet()) {
                if (!key.endsWith(".extra.PATH_URI")) continue;
                Object v = extras.get(key);
                File f = v instanceof String ? fromFileUri((String) v) : null;
                if (f != null) return f;
            }
        }
        return realFile(c, intent.getData());
    }

    /** A {@code file://} URI string as a readable file (java.net.URI decodes it fully). */
    @Nullable
    private static File fromFileUri(@Nullable String s) {
        if (s == null || !s.startsWith("file:")) return null;
        try {
            return usable(new File(new URI(s)), 0);
        } catch (Exception e) {
            return null;
        }
    }

    @Nullable
    public static File realFile(@NonNull Context c, @Nullable Uri uri) {
        if (uri == null) return null;
        try {
            if ("file".equals(uri.getScheme())) return usable(new File(uri.getPath()), 0);
            if (!"content".equals(uri.getScheme())) return null;
            long size = 0;
            String sz = column(c, uri, OpenableColumns.SIZE);
            if (sz != null) {
                try {
                    size = Long.parseLong(sz);
                } catch (NumberFormatException ignored) {
                    size = 0;
                }
            }
            File f = null;
            if (DocumentsContract.isDocumentUri(c, uri)) {
                String id = DocumentsContract.getDocumentId(uri);
                String auth = uri.getAuthority();
                if ("com.android.externalstorage.documents".equals(auth) && id.contains(":")) {
                    String vol = id.substring(0, id.indexOf(':'));
                    String rel = id.substring(id.indexOf(':') + 1);
                    File base = "primary".equalsIgnoreCase(vol) ? Environment.getExternalStorageDirectory()
                            : new File("/storage/" + vol);
                    f = usable(new File(base, rel), size);
                } else if ("com.android.providers.downloads.documents".equals(auth)) {
                    if (id.startsWith("raw:")) f = usable(new File(id.substring(4)), size);
                    else if (id.startsWith("msf:")) {
                        String p = mediaPath(c, Long.parseLong(id.substring(4)));
                        if (p != null) f = usable(new File(p), size);
                    }
                } else if ("com.android.providers.media.documents".equals(auth) && id.contains(":")) {
                    String p = mediaPath(c, Long.parseLong(id.substring(id.indexOf(':') + 1)));
                    if (p != null) f = usable(new File(p), size);
                }
            }
            if (f == null) {
                String p = column(c, uri, MediaStore.MediaColumns.DATA);
                if (p != null && p.startsWith("/")) f = usable(new File(p), size);
            }
            if (f == null) {
                // a file manager's provider carrying a whole file:// URI in its path (Material Files
                // and 書類管理: encoded twice — one decode leaves the name still percent-encoded)
                String p = Uri.decode(uri.getEncodedPath());
                int at = p == null ? -1 : p.indexOf("file:");
                if (at >= 0) f = fromFileUri(p.substring(at));
            }
            if (f == null) {
                // a file manager's own provider often carries the path: …/root/storage/emulated/0/…
                String p = Uri.decode(uri.getEncodedPath());
                for (String root : new String[]{"/storage/", "/sdcard/"}) {
                    int at = p == null ? -1 : p.indexOf(root);
                    if (at >= 0 && f == null) f = usable(new File(p.substring(at)), size);
                }
            }
            return f;
        } catch (Exception e) {
            LOG.e(e);
            return null;
        }
    }

    /** OpenerActivity, as the book opens: remember a copy's source, forget a stale record. */
    public static void opened(@NonNull Activity a, @Nullable Uri uri, @NonNull File file) {
        try {
            SharedPreferences.Editor ed = prefs(a).edit();
            File temp = AppSP.get().getTempDownloadBooks(a.getApplicationContext());
            boolean copy = file.getAbsolutePath().startsWith(temp.getAbsolutePath() + "/");
            if (!copy || uri == null || !"content".equals(uri.getScheme())) {
                ed.remove(file.getAbsolutePath()).apply();
                return;
            }
            String name = column(a, uri, OpenableColumns.DISPLAY_NAME);
            if (name == null) name = file.getName();
            String app = "";
            Uri ref = a.getReferrer();
            String pkg = ref != null && "android-app".equals(ref.getScheme()) ? ref.getHost() : null;
            if (pkg == null) pkg = uri.getAuthority();
            if (pkg != null) {
                try {
                    PackageManager pm = a.getPackageManager();
                    ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                    app = String.valueOf(pm.getApplicationLabel(ai));
                } catch (Exception e) {
                    app = pkg;
                }
            }
            JSONObject o = new JSONObject();
            o.put("uri", uri.toString());
            o.put("name", name);
            o.put("app", app);
            ed.putString(file.getAbsolutePath(), o.toString()).apply();
        } catch (Exception e) {
            LOG.e(e);
        }
    }

    /** The source of a book that is a copy, or null when the book is the original file. */
    @Nullable
    public static Origin of(@NonNull Context c, @NonNull File book) {
        String s = prefs(c).getString(book.getAbsolutePath(), null);
        if (s == null) return null;
        try {
            JSONObject o = new JSONObject(s);
            return new Origin(Uri.parse(o.getString("uri")), o.optString("name", book.getName()), o.optString("app", ""));
        } catch (Exception e) {
            return null;
        }
    }
}
