package shiroikuma.doksho.annot;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewTreeObserver;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.ebookdroid.core.Page;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import shiroikuma.doksho.DokshoUi;
import shiroikuma.doksho.DokshoViews;

/**
 * The glass over the page while a PDF tool is chosen. It takes the touches the tool needs and hands
 * the rest to the document beneath:
 * <ul>
 *   <li>pen — one finger or the stylus draws; with "stylus only" a finger scrolls instead;</li>
 *   <li>eraser — wipes; the stylus's eraser end erases whatever tool is chosen;</li>
 *   <li>text / form — a tap acts; a drag scrolls (a drag that starts on a text box moves the box);</li>
 *   <li>two fingers always go to the document: scroll and pinch-zoom while a tool stays chosen.</li>
 * </ul>
 * Handing over starts the document's gesture with a made-up ACTION_DOWN where the finger first
 * landed, so the document sees an ordinary drag and never a tap (no page-turn, no menus).
 */
@SuppressLint("ViewConstructor")
final class AnnotLayer extends View {

    static final int NONE = 0;
    static final int FORM = 1;
    static final int TEXT = 2;
    static final int PEN = 3;
    static final int ERASER = 4;

    interface Actions {
        void tapForm(@NonNull AnnotSession.Hit hit);

        void tapText(@NonNull AnnotSession.Hit hit, int annotIndex);

        void moveText(@NonNull Page page, int annotIndex, float[] newRect);
    }

    private final AnnotSession session;
    private final View document;
    private final Actions actions;
    int tool = NONE;

    private final int slop;
    private final Paint inkPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint outline = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint eraserPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    // gesture state
    private boolean forwarding;
    private boolean moved;
    private float downX;
    private float downY;
    private long downTime;
    private int gestureTool;

    // pen
    private static final class Stroke {
        final Path path = new Path();
        final List<float[]> pts = new ArrayList<>();
        Page page;
        float widthPx;
        int color;
    }

    @Nullable
    private Stroke stroke;
    private final List<Stroke> pending = new ArrayList<>();

    // eraser
    private final Map<Page, List<Float>> eraseBatch = new HashMap<>();
    private long lastFlush;
    private float eraserX = -1;
    private float eraserY = -1;

    // text box move
    private Page movePage;
    private int moveIndex = -1;
    private RectF moveFrom;
    private float[] moveNorm;
    private float moveDx;
    private float moveDy;

    private long stamp;
    private final ViewTreeObserver.OnPreDrawListener preDraw;

    /** A form field being typed: drawn live over the page at its real size. */
    @Nullable
    private FieldPreview preview;

    AnnotLayer(Context context, AnnotSession session, View document, Actions actions) {
        super(context);
        this.session = session;
        this.document = document;
        this.actions = actions;
        // the document scrolled or zoomed: outlines and wet ink follow it
        preDraw = () -> {
            long st = session.viewStamp();
            if (st != stamp) {
                stamp = st;
                invalidate();
            }
            return true;
        };
        setTag(DokshoViews.NO_SKIN);
        slop = ViewConfiguration.get(context).getScaledTouchSlop();
        inkPaint.setStyle(Paint.Style.STROKE);
        inkPaint.setStrokeCap(Paint.Cap.ROUND);
        inkPaint.setStrokeJoin(Paint.Join.ROUND);
        outline.setStyle(Paint.Style.STROKE);
        outline.setStrokeWidth(Math.max(1f, DokshoViews.dp(context, 1.5f)));
        fill.setStyle(Paint.Style.FILL);
        eraserPaint.setStyle(Paint.Style.STROKE);
        eraserPaint.setStrokeWidth(Math.max(1f, DokshoViews.dp(context, 1.5f)));
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        getViewTreeObserver().addOnPreDrawListener(preDraw);
    }

    @Override
    protected void onDetachedFromWindow() {
        getViewTreeObserver().removeOnPreDrawListener(preDraw);
        super.onDetachedFromWindow();
    }

    void setTool(int tool) {
        this.tool = tool;
        stroke = null;
        eraserX = -1;
        invalidate();
    }

    // ---- touches ---------------------------------------------------------------------------------

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        int action = ev.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            forwarding = false;
            moved = false;
            downX = ev.getX();
            downY = ev.getY();
            downTime = ev.getDownTime();
            int toolType = ev.getToolType(0);
            gestureTool = toolType == MotionEvent.TOOL_TYPE_ERASER ? ERASER : tool;
            if (gestureTool == NONE) return false;
            if (gestureTool == PEN && toolType == MotionEvent.TOOL_TYPE_FINGER && !DokshoUi.b(DokshoUi.ANNOT_PEN_FINGER)) {
                forwarding = true;
                forward(ev);
                return true;
            }
            onDown(ev);
            return true;
        }
        if (forwarding) {
            forward(ev);
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) forwarding = false;
            return true;
        }
        switch (action) {
            case MotionEvent.ACTION_POINTER_DOWN:
                // a second finger: the document's scroll / pinch takes over
                cancelGesture();
                startForwarding(ev, ev.getX(0), ev.getY(0));
                forward(ev);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!moved && Math.hypot(ev.getX() - downX, ev.getY() - downY) > slop) {
                    moved = true;
                    if ((gestureTool == FORM || gestureTool == TEXT) && moveIndex < 0) {
                        startForwarding(ev, downX, downY);
                        forward(ev);
                        return true;
                    }
                }
                onMove(ev);
                return true;
            case MotionEvent.ACTION_UP:
                onUp(ev);
                return true;
            case MotionEvent.ACTION_CANCEL:
                cancelGesture();
                return true;
            default:
                return true;
        }
    }

    private void startForwarding(MotionEvent ev, float x, float y) {
        forwarding = true;
        MotionEvent down = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_DOWN, x, y, 0);
        document.dispatchTouchEvent(down);
        down.recycle();
    }

    private void forward(MotionEvent ev) {
        MotionEvent copy = MotionEvent.obtain(ev);
        document.dispatchTouchEvent(copy);
        copy.recycle();
    }

    private void cancelGesture() {
        stroke = null;
        moveIndex = -1;
        moveFrom = null;
        flushErase(true);
        eraserX = -1;
        invalidate();
    }

    private void onDown(MotionEvent ev) {
        float x = ev.getX(), y = ev.getY();
        switch (gestureTool) {
            case PEN: {
                AnnotSession.Hit h = session.hit(x, y);
                if (h == null) {
                    stroke = null;
                    return;
                }
                Stroke s = new Stroke();
                s.page = h.page;
                s.color = penColor();
                s.widthPx = Math.max(1f, DokshoUi.i(DokshoUi.ANNOT_PEN_WIDTH) / 10f * session.pxPerPt(h.page));
                s.path.moveTo(x, y);
                s.pts.add(new float[]{x, y});
                stroke = s;
                invalidate();
                return;
            }
            case ERASER:
                eraseAt(x, y);
                lastFlush = SystemClock.uptimeMillis();
                return;
            case TEXT: {
                moveIndex = -1;
                AnnotSession.Hit h = session.hit(x, y);
                if (h == null) return;
                int idx = textBoxAt(h);
                if (idx >= 0) {
                    float[] a = session.annots.get(h.page.index.docIndex);
                    int o = idx * DokshoPdf.A_STRIDE;
                    movePage = h.page;
                    moveIndex = idx;
                    moveNorm = new float[]{a[o + 1], a[o + 2], a[o + 3], a[o + 4]};
                    moveFrom = session.toScreen(h.page, a[o + 1], a[o + 2], a[o + 3], a[o + 4]);
                    moveDx = moveDy = 0;
                }
                return;
            }
            default:
        }
    }

    private void onMove(MotionEvent ev) {
        switch (gestureTool) {
            case PEN:
                if (stroke == null) return;
                for (int i = 0; i < ev.getHistorySize(); i++) addPoint(ev.getHistoricalX(i), ev.getHistoricalY(i));
                addPoint(ev.getX(), ev.getY());
                invalidate();
                return;
            case ERASER:
                for (int i = 0; i < ev.getHistorySize(); i++) eraseAt(ev.getHistoricalX(i), ev.getHistoricalY(i));
                eraseAt(ev.getX(), ev.getY());
                if (SystemClock.uptimeMillis() - lastFlush > 80) flushErase(false);
                return;
            case TEXT:
                if (moveIndex >= 0 && moved) {
                    moveDx = ev.getX() - downX;
                    moveDy = ev.getY() - downY;
                    invalidate();
                }
                return;
            default:
        }
    }

    private void onUp(MotionEvent ev) {
        float x = ev.getX(), y = ev.getY();
        switch (gestureTool) {
            case PEN:
                if (stroke != null) {
                    addPoint(x, y);
                    commitStroke(stroke);
                    stroke = null;
                }
                return;
            case ERASER:
                eraseAt(x, y);
                flushErase(true);
                eraserX = -1;
                invalidate();
                return;
            case TEXT: {
                if (moveIndex >= 0 && moved) {
                    RectF r = session.screenBox(movePage);
                    float dnx = moveDx / r.width() * cropW(movePage);
                    float dny = moveDy / r.height() * cropH(movePage);
                    float[] n = {moveNorm[0] + dnx, moveNorm[1] + dny, moveNorm[2] + dnx, moveNorm[3] + dny};
                    actions.moveText(movePage, moveIndex, n);
                    // the ghost stays until the page comes back drawn with the box in its new place
                    return;
                }
                AnnotSession.Hit h = session.hit(x, y);
                moveIndex = -1;
                if (h != null && !moved) actions.tapText(h, textBoxAt(h));
                return;
            }
            case FORM: {
                AnnotSession.Hit h = session.hit(x, y);
                if (h != null && !moved) actions.tapForm(h);
                return;
            }
            default:
        }
    }

    private static float cropW(Page p) {
        RectF c = p.getCroppedBounds();
        return c != null && c.width() > 0 ? c.width() : 1f;
    }

    private static float cropH(Page p) {
        RectF c = p.getCroppedBounds();
        return c != null && c.height() > 0 ? c.height() : 1f;
    }

    /** Called when a move has been written (or failed): drop the ghost. */
    void setPreview(@Nullable FieldPreview p) {
        preview = p;
        invalidate();
    }

    void moveDone() {
        moveIndex = -1;
        moveFrom = null;
        invalidate();
    }

    // ---- pen -------------------------------------------------------------------------------------

    private int penColor() {
        int c = DokshoUi.i(DokshoUi.ANNOT_PEN_COLOR);
        int alpha = Math.round(255 * Math.max(5, Math.min(100, DokshoUi.i(DokshoUi.ANNOT_PEN_OPACITY))) / 100f);
        return (c & 0x00FFFFFF) | (alpha << 24);
    }

    private void addPoint(float x, float y) {
        Stroke s = stroke;
        if (s == null) return;
        float[] last = s.pts.get(s.pts.size() - 1);
        if (Math.abs(last[0] - x) < 0.5f && Math.abs(last[1] - y) < 0.5f) return;
        // a smooth line through the midpoints, as the ink will be drawn
        s.path.quadTo(last[0], last[1], (last[0] + x) / 2, (last[1] + y) / 2);
        s.pts.add(new float[]{x, y});
    }

    private void commitStroke(final Stroke s) {
        final int n = s.pts.size();
        final float[] xy = new float[n * 2];
        for (int i = 0; i < n; i++) {
            AnnotSession.Hit h = session.toPage(s.page, s.pts.get(i)[0], s.pts.get(i)[1]);
            xy[i * 2] = clamp(h.nx);
            xy[i * 2 + 1] = clamp(h.ny);
        }
        final float width = DokshoUi.i(DokshoUi.ANNOT_PEN_WIDTH) / 10f;
        final int color = s.color;
        pending.add(s);
        invalidate();
        session.edit(s.page.index.docIndex, (doc, page) -> DokshoPdf.addInk(doc, page, xy, width, color), changed -> {
            // keep the wet ink a moment: the page re-renders with the stroke just after this
            postDelayed(() -> {
                pending.remove(s);
                invalidate();
            }, changed ? 450 : 0);
        });
    }

    private static float clamp(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    // ---- eraser ----------------------------------------------------------------------------------

    private int eraseMask() {
        int m = 0;
        if (DokshoUi.b(DokshoUi.ANNOT_ERASE_INK)) m |= DokshoPdf.ERASE_INK;
        if (DokshoUi.b(DokshoUi.ANNOT_ERASE_TEXT)) m |= DokshoPdf.ERASE_TEXT;
        if (DokshoUi.b(DokshoUi.ANNOT_ERASE_OTHER)) m |= DokshoPdf.ERASE_OTHER;
        return m;
    }

    private void eraseAt(float x, float y) {
        eraserX = x;
        eraserY = y;
        invalidate();
        AnnotSession.Hit h = session.hit(x, y);
        if (h == null) return;
        List<Float> list = eraseBatch.get(h.page);
        if (list == null) {
            list = new ArrayList<>();
            eraseBatch.put(h.page, list);
        }
        list.add(h.nx);
        list.add(h.ny);
    }

    private void flushErase(boolean force) {
        lastFlush = SystemClock.uptimeMillis();
        if (eraseBatch.isEmpty()) return;
        final int mask = eraseMask();
        for (Map.Entry<Page, List<Float>> e : eraseBatch.entrySet()) {
            Page p = e.getKey();
            List<Float> l = e.getValue();
            final float[] xy = new float[l.size()];
            for (int i = 0; i < xy.length; i++) xy[i] = l.get(i);
            final float radius = DokshoViews.dp(getContext(), DokshoUi.i(DokshoUi.ANNOT_ERASER_SIZE)) / session.pxPerPt(p);
            if (mask != 0) session.edit(p.index.docIndex, (doc, page) -> DokshoPdf.erase(doc, page, xy, radius, mask) > 0, null);
        }
        eraseBatch.clear();
    }

    // ---- text boxes ------------------------------------------------------------------------------

    /** The topmost text box under the hit, or -1 (the page's annotations must be cached). */
    private int textBoxAt(@NonNull AnnotSession.Hit h) {
        float[] a = session.annots.get(h.page.index.docIndex);
        if (a == null) return -1;
        // a little reach around the box, so a small box is easy to catch
        float rx = 6f / Math.max(1f, session.pageWidthPt(h.page));
        float ry = 6f / Math.max(1f, session.pageHeightPt(h.page));
        for (int i = a.length / DokshoPdf.A_STRIDE - 1; i >= 0; i--) {
            int o = i * DokshoPdf.A_STRIDE;
            if ((int) a[o] != DokshoPdf.A_FREE_TEXT) continue;
            if (h.nx >= a[o + 1] - rx && h.nx <= a[o + 3] + rx && h.ny >= a[o + 2] - ry && h.ny <= a[o + 4] + ry) return i;
        }
        return -1;
    }

    // ---- drawing ---------------------------------------------------------------------------------

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        int accent = DokshoUi.i(DokshoUi.C_ACCENT);
        if (tool == FORM || tool == TEXT) {
            boolean fields = tool == FORM;
            outline.setColor(accent);
            outline.setPathEffect(fields ? null : new DashPathEffect(new float[]{12f, 8f}, 0));
            fill.setColor((accent & 0x00FFFFFF) | 0x22000000);
            for (Page p : session.visiblePages()) {
                int idx = p.index.docIndex;
                float[] list = fields ? session.widgets.get(idx) : session.annots.get(idx);
                if (list == null) {
                    session.want(idx, fields, this::invalidate);
                    if (!fields) session.want(idx, true, () -> { });
                    continue;
                }
                int stride = fields ? DokshoPdf.W_STRIDE : DokshoPdf.A_STRIDE;
                for (int i = 0; i + stride <= list.length; i += stride) {
                    RectF r;
                    if (fields) {
                        if (((int) list[i + 5] & DokshoPdf.F_READ_ONLY) != 0) continue;
                        r = session.toScreen(p, list[i], list[i + 1], list[i + 2], list[i + 3]);
                        canvas.drawRect(r, fill);
                    } else {
                        if ((int) list[i] != DokshoPdf.A_FREE_TEXT) continue;
                        r = session.toScreen(p, list[i + 1], list[i + 2], list[i + 3], list[i + 4]);
                    }
                    canvas.drawRect(r, outline);
                }
            }
        }
        if (preview != null) preview.draw(canvas, session);
        if (moveIndex >= 0 && moveFrom != null && (moved || moveDx != 0 || moveDy != 0)) {
            RectF g = new RectF(moveFrom);
            g.offset(moveDx, moveDy);
            outline.setPathEffect(null);
            outline.setColor(accent);
            fill.setColor((accent & 0x00FFFFFF) | 0x33000000);
            canvas.drawRect(g, fill);
            canvas.drawRect(g, outline);
        }
        for (Stroke s : pending) drawStroke(canvas, s);
        if (stroke != null) drawStroke(canvas, stroke);
        if (eraserX >= 0 && (gestureTool == ERASER || tool == ERASER)) {
            float r = DokshoViews.dp(getContext(), DokshoUi.i(DokshoUi.ANNOT_ERASER_SIZE));
            eraserPaint.setColor(accent);
            canvas.drawCircle(eraserX, eraserY, r, eraserPaint);
            eraserPaint.setColor(Color.BLACK);
            canvas.drawCircle(eraserX, eraserY, r + eraserPaint.getStrokeWidth(), eraserPaint);
        }
    }

    private void drawStroke(Canvas canvas, Stroke s) {
        inkPaint.setColor(s.color);
        inkPaint.setStrokeWidth(s.widthPx);
        if (s.pts.size() == 1) {
            inkPaint.setStyle(Paint.Style.FILL);
            canvas.drawCircle(s.pts.get(0)[0], s.pts.get(0)[1], s.widthPx / 2, inkPaint);
            inkPaint.setStyle(Paint.Style.STROKE);
        } else {
            canvas.drawPath(s.path, inkPaint);
        }
    }
}
