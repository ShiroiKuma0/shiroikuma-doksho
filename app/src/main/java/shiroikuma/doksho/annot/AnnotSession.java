package shiroikuma.doksho.annot;

import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.foobnix.android.utils.LOG;
import com.foobnix.sys.TempHolder;
import com.foobnix.sys.VerticalModeController;

import org.ebookdroid.core.DecodeService;
import org.ebookdroid.core.DecodeServiceBase;
import org.ebookdroid.core.Page;
import org.ebookdroid.core.codec.CodecDocument;
import org.ebookdroid.core.codec.CodecPage;
import org.ebookdroid.ui.viewer.IViewController;
import org.ebookdroid.ui.viewer.ViewerActivityController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The PDF tools' link to the open book in Scroll mode: where pages are on the screen, and edits
 * run on Librera's decoder thread (the one that owns the MuPDF context) under its lock. After a
 * change every page re-opens, so its display list — and the next render — carries the edit.
 *
 * <p>Each page's form fields and annotations are fetched once and cached; any edit or undo drops
 * the cache, and the overlay asks again.
 */
final class AnnotSession {

    interface Op {
        /** Runs on the decoder thread under the lock; returns whether the document changed. */
        boolean run(long doc, long page);
    }

    interface Query<T> {
        T run(long doc, long page);
    }

    interface Result<T> {
        void on(@Nullable T value);
    }

    /** A screen point on a page: which page, where on it (0..1). */
    static final class Hit {
        final Page page;
        final float nx;
        final float ny;

        Hit(Page page, float nx, float ny) {
            this.page = page;
            this.nx = nx;
            this.ny = ny;
        }
    }

    final VerticalModeController dc;
    private final ViewerActivityController ctr;
    private final DecodeServiceBase decoder;
    private final Handler ui = new Handler(Looper.getMainLooper());

    final Map<Integer, float[]> widgets = new HashMap<>();
    final Map<Integer, float[]> annots = new HashMap<>();
    private final Set<String> asked = new HashSet<>();
    private int generation;

    /** bit 0 undo, bit 1 redo. */
    int undoState;
    @Nullable
    Runnable onChanged;

    private AnnotSession(VerticalModeController dc, ViewerActivityController ctr, DecodeServiceBase decoder) {
        this.dc = dc;
        this.ctr = ctr;
        this.decoder = decoder;
    }

    @Nullable
    static AnnotSession of(@NonNull VerticalModeController dc) {
        ViewerActivityController ctr = dc.getActivityController();
        if (ctr == null) return null;
        DecodeService ds = ctr.getDecodeService();
        if (!(ds instanceof DecodeServiceBase)) return null;
        return new AnnotSession(dc, ctr, (DecodeServiceBase) ds);
    }

    // ---- geometry --------------------------------------------------------------------------------

    private float zoom() {
        return ctr.getZoomModel().getZoom();
    }

    private IViewController view() {
        return ctr.getDocumentController();
    }

    /** The pages on the screen now. */
    @NonNull
    List<Page> visiblePages() {
        List<Page> out = new ArrayList<>();
        try {
            int first = view().getFirstVisiblePage();
            int last = view().getLastVisiblePage();
            for (Page p : ctr.getDocumentModel().getPages(Math.max(0, first), last + 1)) out.add(p);
        } catch (Exception e) {
            LOG.e(e);
        }
        return out;
    }

    /** The page's visible box on the screen (the cropped part when Crop is on). */
    @NonNull
    RectF screenBox(@NonNull Page page) {
        RectF b = new RectF(page.getBounds(zoom()));
        b.offset(-view().getView().getScrollX(), -view().getView().getScrollY());
        return b;
    }

    @Nullable
    private static RectF crop(@NonNull Page page) {
        RectF c = page.getCroppedBounds();
        return c != null && c.width() > 0 && c.height() > 0 ? c : null;
    }

    @Nullable
    Hit hit(float x, float y) {
        for (Page p : visiblePages()) {
            RectF b = screenBox(p);
            if (b.contains(x, y)) return toPage(p, x, y);
        }
        return null;
    }

    /** A screen point on a given page, even outside it (a stroke that wanders off its page). */
    @NonNull
    Hit toPage(@NonNull Page p, float x, float y) {
        RectF b = screenBox(p);
        float nx = (x - b.left) / b.width();
        float ny = (y - b.top) / b.height();
        RectF c = crop(p);
        if (c != null) {
            nx = c.left + nx * c.width();
            ny = c.top + ny * c.height();
        }
        return new Hit(p, nx, ny);
    }

    float screenX(@NonNull Page p, RectF b, float nx) {
        RectF c = crop(p);
        if (c != null) nx = (nx - c.left) / c.width();
        return b.left + nx * b.width();
    }

    float screenY(@NonNull Page p, RectF b, float ny) {
        RectF c = crop(p);
        if (c != null) ny = (ny - c.top) / c.height();
        return b.top + ny * b.height();
    }

    @NonNull
    RectF toScreen(@NonNull Page p, float nx0, float ny0, float nx1, float ny1) {
        RectF b = screenBox(p);
        return new RectF(screenX(p, b, nx0), screenY(p, b, ny0), screenX(p, b, nx1), screenY(p, b, ny1));
    }

    /** Screen pixels per PDF point on this page at the current zoom. */
    float pxPerPt(@NonNull Page p) {
        RectF b = screenBox(p);
        RectF c = crop(p);
        float pts = p.cpi.width * (c != null ? c.width() : 1f);
        return pts > 0 ? b.width() / pts : 1f;
    }

    /** The page's size in points. */
    float pageWidthPt(@NonNull Page p) {
        return p.cpi.width;
    }

    float pageHeightPt(@NonNull Page p) {
        return p.cpi.height;
    }

    /** Scrolls the document (within its limits). */
    void scrollBy(int dx, int dy) {
        try {
            view().getView().scrollBy(dx, dy);
        } catch (Exception e) {
            LOG.e(e);
        }
    }

    /** What the view looks like now — the overlay repaints when this changes. */
    long viewStamp() {
        try {
            return ((long) view().getView().getScrollX() * 31 + view().getView().getScrollY()) * 31
                    + Float.floatToIntBits(zoom());
        } catch (Exception e) {
            return 0;
        }
    }

    // ---- the decoder thread ----------------------------------------------------------------------

    private long docHandle() {
        CodecDocument d = decoder.getCodecDocument();
        return d == null || d.isRecycled() ? 0 : d.getDocumentHandle();
    }

    /** An edit on one page; {@code done} runs on the UI thread with whether it changed anything. */
    void edit(final int pageIndex, @NonNull final Op op, @Nullable final Result<Boolean> done) {
        decoder.runEdit(() -> {
            boolean changed = false;
            int state = undoState;
            TempHolder.lock.lock();
            try {
                long doc = docHandle();
                CodecPage page = doc == 0 ? null : decoder.editPage(pageIndex);
                if (page != null && !page.isRecycled()) {
                    changed = op.run(doc, page.getPageHandle());
                }
                if (changed) state = DokshoPdf.undoState(doc);
            } catch (Throwable e) {
                LOG.e(e);
            } finally {
                TempHolder.lock.unlock();
            }
            if (changed) decoder.reopenPages();
            final boolean result = changed;
            final int newState = state;
            ui.post(() -> {
                if (result) refreshAfterChange(newState);
                if (done != null) done.on(result);
            });
        });
    }

    /** Undo (or redo) the last edit. */
    void undo(final boolean redo) {
        decoder.runEdit(() -> {
            boolean changed = false;
            int state = undoState;
            TempHolder.lock.lock();
            try {
                long doc = docHandle();
                if (doc != 0) {
                    changed = DokshoPdf.undo(doc, redo);
                    state = DokshoPdf.undoState(doc);
                }
            } catch (Throwable e) {
                LOG.e(e);
            } finally {
                TempHolder.lock.unlock();
            }
            if (changed) decoder.reopenPages();
            final boolean result = changed;
            final int newState = state;
            ui.post(() -> {
                if (result) refreshAfterChange(newState);
                else {
                    undoState = newState;
                    if (onChanged != null) onChanged.run();
                }
            });
        });
    }

    void readUndoState() {
        decoder.runEdit(() -> {
            int state = 0;
            TempHolder.lock.lock();
            try {
                long doc = docHandle();
                if (doc != 0) state = DokshoPdf.undoState(doc);
            } catch (Throwable e) {
                LOG.e(e);
            } finally {
                TempHolder.lock.unlock();
            }
            final int s = state;
            ui.post(() -> {
                undoState = s;
                if (onChanged != null) onChanged.run();
            });
        });
    }

    /** A read on one page; {@code done} runs on the UI thread. */
    <T> void query(final int pageIndex, @NonNull final Query<T> q, @NonNull final Result<T> done) {
        decoder.runEdit(() -> {
            T value = null;
            TempHolder.lock.lock();
            try {
                long doc = docHandle();
                CodecPage page = doc == 0 ? null : decoder.editPage(pageIndex);
                if (page != null && !page.isRecycled()) value = q.run(doc, page.getPageHandle());
            } catch (Throwable e) {
                LOG.e(e);
            } finally {
                TempHolder.lock.unlock();
            }
            final T v = value;
            ui.post(() -> done.on(v));
        });
    }

    private void refreshAfterChange(int state) {
        undoState = state;
        generation++;
        widgets.clear();
        annots.clear();
        asked.clear();
        try {
            ctr.getDocumentController().toggleRenderingEffects();
        } catch (Exception e) {
            LOG.e(e);
        }
        if (onChanged != null) onChanged.run();
    }

    /** Fetches a page's form fields / annotations once; the overlay repaints when they arrive. */
    void want(final int pageIndex, final boolean fields, @NonNull final Runnable arrived) {
        final Map<Integer, float[]> cache = fields ? widgets : annots;
        if (cache.containsKey(pageIndex)) return;
        String key = (fields ? "w" : "a") + pageIndex;
        if (!asked.add(key)) return;
        final int gen = generation;
        query(pageIndex, (doc, page) -> fields ? DokshoPdf.widgets(doc, page) : DokshoPdf.annots(doc, page), v -> {
            if (gen != generation) return;
            cache.put(pageIndex, v == null ? new float[0] : v);
            arrived.run();
        });
    }
}
