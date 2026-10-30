package shiroikuma.doksho.annot;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.foobnix.android.utils.LOG;
import com.foobnix.model.AppState;
import com.foobnix.pdf.info.R;
import com.foobnix.sys.TempHolder;

import org.ebookdroid.core.DecodeService;
import org.ebookdroid.core.DecodeServiceBase;
import org.ebookdroid.core.codec.CodecDocument;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import shiroikuma.doksho.DokshoFileDialog;
import shiroikuma.doksho.DokshoOrigin;
import shiroikuma.doksho.DokshoViews;

/**
 * Closing a PDF with unsaved edits (Scroll mode, {@code VerticalModeController}). Save writes them
 * into the original:
 * <ul>
 *   <li>the book is a real file — into that file (an incremental append, see {@code DokshoPdf.save});</li>
 *   <li>the book is Librera's copy of something another app sent ({@link DokshoOrigin}) — into the
 *       copy, which is then written back through the sending app's URI. If that app lent it
 *       read-only, the save goes on to Save as.</li>
 * </ul>
 * Save as asks for a directory and a name, starting in the original's directory (or the last one
 * used, for a book that had none); the original stays as it was. Don't save closes; Back stays in
 * the book. With Librera's "save automatically" on, Save happens without asking.
 */
public final class SaveOnClose {

    private static final String PREFS = "doksho_save";
    private static final String LAST_DIR = "last_dir";

    private final Activity a;
    private final File book;
    private final DecodeServiceBase ds;
    private final Runnable close;
    @Nullable
    private final DokshoOrigin.Origin origin;
    private final Handler ui = new Handler(Looper.getMainLooper());
    /** The edits are already in the book file (written there before a write-back failed). */
    private boolean inBook;

    private SaveOnClose(Activity a, File book, DecodeServiceBase ds, Runnable close) {
        this.a = a;
        this.book = book;
        this.ds = ds;
        this.close = close;
        this.origin = DokshoOrigin.of(a, book);
    }

    /** @return false when it cannot handle this document (the caller keeps Librera's flow) */
    public static boolean ask(@NonNull Activity a, @NonNull File book, @Nullable DecodeService ds, @NonNull Runnable close) {
        if (!(ds instanceof DecodeServiceBase)) return false;
        SaveOnClose s = new SaveOnClose(a, book, (DecodeServiceBase) ds, close);
        if (AppState.get().isSaveAnnotatationsAutomatically) s.save();
        else s.dialog();
        return true;
    }

    private String where() {
        if (origin == null) return book.getAbsolutePath();
        String app = origin.app.isEmpty() ? a.getString(R.string.doksho_save_other_app) : origin.app;
        return a.getString(R.string.doksho_save_from_app, origin.name, app);
    }

    private void dialog() {
        LinearLayout box = DokshoViews.infoBox(a, a.getString(R.string.doksho_save_title),
                a.getString(R.string.doksho_save_body, where()));
        final AlertDialog d = DokshoViews.boxDialog(a, box, true);
        LinearLayout buttons = DokshoViews.buttonRow(a);
        buttons.setGravity(Gravity.CENTER_VERTICAL);
        buttons.addView(DokshoViews.pill(a, a.getString(R.string.doksho_save_discard), v -> {
            d.dismiss();
            close.run();
        }));
        buttons.addView(new View(a), new LinearLayout.LayoutParams(0, 0, 1f));
        buttons.addView(DokshoViews.pill(a, a.getString(R.string.doksho_save_as), v -> {
            d.dismiss();
            saveAs();
        }));
        View gap = new View(a);
        buttons.addView(gap, new LinearLayout.LayoutParams(DokshoViews.dp(a, 10), 0));
        buttons.addView(DokshoViews.pill(a, a.getString(R.string.doksho_save), v -> {
            d.dismiss();
            save();
        }));
        box.addView(buttons);
        d.show();
        DokshoViews.transparentWindow(d);
    }

    // ---- Save ------------------------------------------------------------------------------------

    private void save() {
        final AlertDialog progress = progress();
        saveInto(book, ok -> {
            if (!ok) {
                progress.dismiss();
                failed(a.getString(R.string.doksho_save_failed, book.getAbsolutePath()));
                return;
            }
            inBook = true;
            if (origin == null) {
                progress.dismiss();
                DokshoViews.toast(a, a.getString(R.string.doksho_save_done, book.getAbsolutePath()));
                close.run();
                return;
            }
            // the copy holds the edits: write it back to where it came from
            new Thread(() -> {
                String err = null;
                try (InputStream in = new FileInputStream(book);
                     OutputStream out = openForWrite(a, origin)) {
                    copy(in, out);
                } catch (Exception e) {
                    LOG.e(e);
                    err = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                }
                final String error = err;
                ui.post(() -> {
                    progress.dismiss();
                    if (error == null) {
                        DokshoViews.toast(a, a.getString(R.string.doksho_save_done, origin.name));
                        close.run();
                    } else {
                        String app = origin.app.isEmpty() ? a.getString(R.string.doksho_save_other_app) : origin.app;
                        DokshoViews.showInfo(a, a.getString(R.string.doksho_save_title),
                                a.getString(R.string.doksho_save_read_only, app), true, this::saveAs);
                    }
                });
            }, "doksho-save-back").start();
        });
    }

    private static OutputStream openForWrite(Context c, DokshoOrigin.Origin o) throws IOException {
        OutputStream out;
        try {
            out = c.getContentResolver().openOutputStream(o.uri, "wt");
        } catch (IllegalArgumentException | UnsupportedOperationException e) {
            out = c.getContentResolver().openOutputStream(o.uri, "w");
        }
        if (out == null) throw new IOException("no output stream");
        return out;
    }

    // ---- Save as ---------------------------------------------------------------------------------

    private File startDir() {
        if (origin == null && book.getParentFile() != null) return book.getParentFile();
        String last = a.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(LAST_DIR, null);
        if (last != null && new File(last).isDirectory()) return new File(last);
        return DokshoFileDialog.downloads();
    }

    private void saveAs() {
        String name = origin != null ? origin.name : book.getName();
        if (!name.toLowerCase(java.util.Locale.ROOT).endsWith(".pdf")) name = name + ".pdf";
        DokshoFileDialog.save(a, a.getString(R.string.doksho_save_as_title), startDir(), name, ".pdf", target -> {
            if (target.getAbsolutePath().equals(book.getAbsolutePath())) {
                save();
                return;
            }
            if (target.exists()) {
                DokshoViews.showConfirm(a, a.getString(R.string.doksho_save_as_title),
                        a.getString(R.string.doksho_save_overwrite, target.getAbsolutePath()),
                        a.getString(R.string.doksho_save_overwrite_yes), () -> saveAs(target));
            } else {
                saveAs(target);
            }
        });
    }

    private void saveAs(final File target) {
        final AlertDialog progress = progress();
        new Thread(() -> {
            boolean copied;
            try (InputStream in = new FileInputStream(book); OutputStream out = new FileOutputStream(target)) {
                copy(in, out);
                copied = true;
            } catch (IOException e) {
                LOG.e(e);
                copied = false;
            }
            final boolean ok = copied;
            ui.post(() -> {
                if (!ok) {
                    progress.dismiss();
                    failed(a.getString(R.string.doksho_save_failed, target.getAbsolutePath()));
                    return;
                }
                if (target.getParentFile() != null) {
                    a.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                            .putString(LAST_DIR, target.getParentFile().getAbsolutePath()).apply();
                }
                if (inBook) {
                    // the copy already carried the edits
                    progress.dismiss();
                    DokshoViews.toast(a, a.getString(R.string.doksho_save_done, target.getAbsolutePath()));
                    close.run();
                    return;
                }
                // the original's bytes are in place: the edits append to them
                saveInto(target, saved -> {
                    progress.dismiss();
                    if (saved) {
                        DokshoViews.toast(a, a.getString(R.string.doksho_save_done, target.getAbsolutePath()));
                        close.run();
                    } else {
                        //noinspection ResultOfMethodCallIgnored
                        target.delete();
                        failed(a.getString(R.string.doksho_save_failed, target.getAbsolutePath()));
                    }
                });
            });
        }, "doksho-save-as").start();
    }

    // ---- plumbing --------------------------------------------------------------------------------

    private interface Saved {
        void on(boolean ok);
    }

    /** The open document's edits into a file holding the bytes it was opened from. */
    private void saveInto(final File file, final Saved done) {
        ds.runEdit(() -> {
            boolean ok = false;
            TempHolder.lock.lock();
            try {
                CodecDocument d = ds.getCodecDocument();
                if (d != null && !d.isRecycled()) ok = DokshoPdf.save(d.getDocumentHandle(), file.getAbsolutePath());
            } catch (Throwable e) {
                LOG.e(e);
            } finally {
                TempHolder.lock.unlock();
            }
            final boolean r = ok;
            ui.post(() -> done.on(r));
        });
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[256 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        out.flush();
    }

    private AlertDialog progress() {
        LinearLayout box = DokshoViews.infoBox(a, a.getString(R.string.doksho_save_saving), where());
        AlertDialog d = DokshoViews.boxDialog(a, box, false);
        d.show();
        DokshoViews.transparentWindow(d);
        return d;
    }

    /** A failure: say so, then ask again (the edits are still in the open book). */
    private void failed(String msg) {
        DokshoViews.showInfo(a, a.getString(R.string.doksho_save_title), msg, true, this::dialog);
    }
}
