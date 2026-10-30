/*
 * 白い熊 読書 — the PDF editing layer: form filling, text boxes, ink, the eraser, undo / redo and a
 * safe save, on the document and page handles Librera's own bridge (libmupdf_librera_<ver>.c) opens.
 *
 * Fork-only: shiroikuma/build-mupdf.sh copies this file into the jni tree and appends it to the
 * MuPDF module's LOCAL_SRC_FILES, so no upstream source is edited. The two structs below mirror the
 * ones at the top of upstream's bridge (they are not in a header); keep them identical on a sync.
 *
 * Coordinates cross the boundary normalised to the page's bounds (0..1, top-left origin), the same
 * space Librera's views measure pages in; widths and font sizes are in PDF points.
 * Every edit is one journal operation, so pdf_undo / pdf_redo step through them.
 */
#include <android/log.h>
#include <jni.h>
#include <math.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>

#include "mupdf/fitz.h"
#include "mupdf/pdf.h"

#include "hb.h"
#include "hb-subset.h"

#define DEBUG(args...) __android_log_print(ANDROID_LOG_DEBUG, "DokshoPdf", args)
#define ERROR(args...) __android_log_print(ANDROID_LOG_ERROR, "DokshoPdf", args)

#define FN(name) Java_shiroikuma_doksho_annot_DokshoPdf_##name

typedef struct renderdocument_s
{
    fz_context* ctx;
    fz_document* document;
    fz_outline* outline;
    char* accel;
    unsigned char format;
} renderdocument_t;

typedef struct renderpage_s
{
    fz_context* ctx;
    fz_page* page;
    int number;
    fz_display_list* pageList;
} renderpage_t;

/* The widget key recording the font file a field was filled in (ours; viewers ignore it). */
#define DOKSHO_FONT_KEY "DokshoFont"

/* erase() mask */
#define ERASE_INK 1
#define ERASE_TEXT 2
#define ERASE_OTHER 4

/* ---- plumbing --------------------------------------------------------------------------------- */

typedef struct
{
    fz_context* ctx;
    pdf_document* doc;
    pdf_page* page;
    fz_rect bounds;
} target;

static int
open_doc(jlong dochandle, target* t)
{
    renderdocument_t* d = (renderdocument_t*)(intptr_t)dochandle;
    memset(t, 0, sizeof(*t));
    if (!d || !d->ctx || !d->document)
        return 0;
    t->ctx = d->ctx;
    fz_try(t->ctx) t->doc = pdf_specifics(t->ctx, d->document);
    fz_catch(t->ctx) t->doc = NULL;
    return t->doc != NULL;
}

static int
open_page(jlong dochandle, jlong pagehandle, target* t)
{
    renderpage_t* p = (renderpage_t*)(intptr_t)pagehandle;
    if (!open_doc(dochandle, t) || !p || !p->page)
        return 0;
    fz_try(t->ctx)
    {
        t->page = pdf_page_from_fz_page(t->ctx, p->page);
        if (t->page)
            t->bounds = fz_bound_page(t->ctx, p->page);
    }
    fz_catch(t->ctx) t->page = NULL;
    return t->page != NULL && t->bounds.x1 > t->bounds.x0 && t->bounds.y1 > t->bounds.y0;
}

static float
nx(target* t, float x)
{
    return (x - t->bounds.x0) / (t->bounds.x1 - t->bounds.x0);
}

static float
ny(target* t, float y)
{
    return (y - t->bounds.y0) / (t->bounds.y1 - t->bounds.y0);
}

static fz_point
to_page(target* t, float x, float y)
{
    fz_point p;
    p.x = t->bounds.x0 + x * (t->bounds.x1 - t->bounds.x0);
    p.y = t->bounds.y0 + y * (t->bounds.y1 - t->bounds.y0);
    return p;
}

static void
rgb_of(jint argb, float c[3])
{
    c[0] = ((argb >> 16) & 0xFF) / 255.0f;
    c[1] = ((argb >> 8) & 0xFF) / 255.0f;
    c[2] = (argb & 0xFF) / 255.0f;
}

static const char*
font_name(int font)
{
    switch (font) {
        case 1: return "TiRo";
        case 2: return "Cour";
        default: return "Helv";
    }
}

static int
font_code(const char* name)
{
    if (name && !strcmp(name, "TiRo")) return 1;
    if (name && !strcmp(name, "Cour")) return 2;
    return 0;
}

static pdf_annot*
nth_annot(fz_context* ctx, pdf_page* page, int index)
{
    pdf_annot* a = pdf_first_annot(ctx, page);
    while (a && index-- > 0)
        a = pdf_next_annot(ctx, a);
    return a;
}

static pdf_annot*
nth_widget(fz_context* ctx, pdf_page* page, int index)
{
    pdf_annot* w = pdf_first_widget(ctx, page);
    while (w && index-- > 0)
        w = pdf_next_widget(ctx, w);
    return w;
}

static jstring
jstr(JNIEnv* env, const char* s)
{
    if (!s)
        return NULL;
    return (*env)->NewStringUTF(env, s);
}

static float
seg_dist(fz_point p, fz_point a, fz_point b)
{
    float dx = b.x - a.x, dy = b.y - a.y;
    float len = dx * dx + dy * dy;
    float u = len > 0 ? ((p.x - a.x) * dx + (p.y - a.y) * dy) / len : 0;
    if (u < 0) u = 0;
    if (u > 1) u = 1;
    float x = a.x + u * dx - p.x, y = a.y + u * dy - p.y;
    return sqrtf(x * x + y * y);
}

/* ---- form fields ------------------------------------------------------------------------------ */

/* Per widget, W_STRIDE floats: x0 y0 x1 y1 (normalised), type, flags (1 read-only, 2 multi-line,
 * 4 checked, 8 comb), max length (0 = none), font size of its DA (0 = auto), quadding (0 left,
 * 1 centre, 2 right), border width (points), DA colour 0xRRGGBB (exact in a float). */
#define W_STRIDE 11

JNIEXPORT jfloatArray JNICALL
FN(widgets)(JNIEnv* env, jclass clazz, jlong dochandle, jlong pagehandle)
{
    target t;
    if (!open_page(dochandle, pagehandle, &t))
        return NULL;
    fz_context* ctx = t.ctx;
    int n = 0;
    float* out = NULL;
    jfloatArray arr = NULL;
    fz_var(out);
    fz_try(ctx)
    {
        pdf_annot* w;
        for (w = pdf_first_widget(ctx, t.page); w; w = pdf_next_widget(ctx, w))
            n++;
        out = fz_malloc(ctx, sizeof(float) * W_STRIDE * (n > 0 ? n : 1));
        int i = 0;
        for (w = pdf_first_widget(ctx, t.page); w; w = pdf_next_widget(ctx, w), i++) {
            fz_rect r = pdf_bound_widget(ctx, w);
            int type = pdf_widget_type(ctx, w);
            int ff = pdf_field_flags(ctx, pdf_annot_obj(ctx, w));
            int flags = 0;
            if (ff & PDF_FIELD_IS_READ_ONLY) flags |= 1;
            if (type == PDF_WIDGET_TYPE_TEXT && (ff & PDF_TX_FIELD_IS_MULTILINE)) flags |= 2;
            if (type == PDF_WIDGET_TYPE_TEXT && (ff & PDF_TX_FIELD_IS_COMB)) flags |= 8;
            if (type == PDF_WIDGET_TYPE_CHECKBOX || type == PDF_WIDGET_TYPE_RADIOBUTTON) {
                const char* v = pdf_field_value(ctx, pdf_annot_obj(ctx, w));
                if (v && *v && strcmp(v, "Off")) flags |= 4;
            }
            float size = 0, border = 1, rgb = 0;
            int q = 0;
            if (type == PDF_WIDGET_TYPE_TEXT || type == PDF_WIDGET_TYPE_COMBOBOX || type == PDF_WIDGET_TYPE_LISTBOX) {
                fz_try(ctx)
                {
                    const char* font;
                    int cn;
                    float col[4] = {0, 0, 0, 0};
                    pdf_annot_default_appearance(ctx, w, &font, &size, &cn, col);
                    int c = 0;
                    if (cn == 3)
                        c = ((int)(col[0] * 255 + 0.5f) << 16) | ((int)(col[1] * 255 + 0.5f) << 8) | (int)(col[2] * 255 + 0.5f);
                    else if (cn == 1)
                        c = (int)(col[0] * 255 + 0.5f) * 0x010101;
                    else if (cn == 4) {
                        float k = 1 - col[3];
                        c = ((int)((1 - col[0]) * k * 255 + 0.5f) << 16) | ((int)((1 - col[1]) * k * 255 + 0.5f) << 8)
                            | (int)((1 - col[2]) * k * 255 + 0.5f);
                    }
                    rgb = (float)c;
                    q = pdf_annot_quadding(ctx, w);
                    border = pdf_annot_border_width(ctx, w);
                }
                fz_catch(ctx) size = 0;
            }
            float* o = out + i * W_STRIDE;
            o[0] = nx(&t, r.x0);
            o[1] = ny(&t, r.y0);
            o[2] = nx(&t, r.x1);
            o[3] = ny(&t, r.y1);
            o[4] = type;
            o[5] = flags;
            o[6] = type == PDF_WIDGET_TYPE_TEXT ? pdf_text_widget_max_len(ctx, w) : 0;
            o[7] = size;
            o[8] = q;
            o[9] = border;
            o[10] = rgb;
        }
    }
    fz_catch(ctx)
    {
        fz_free(ctx, out);
        return NULL;
    }
    arr = (*env)->NewFloatArray(env, n * W_STRIDE);
    if (arr && n > 0)
        (*env)->SetFloatArrayRegion(env, arr, 0, n * W_STRIDE, out);
    fz_free(ctx, out);
    return arr;
}

JNIEXPORT jstring JNICALL
FN(widgetValue)(JNIEnv* env, jclass clazz, jlong dochandle, jlong pagehandle, jint index)
{
    target t;
    if (!open_page(dochandle, pagehandle, &t))
        return NULL;
    const char* v = NULL;
    fz_try(t.ctx)
    {
        pdf_annot* w = nth_widget(t.ctx, t.page, index);
        if (w)
            v = pdf_field_value(t.ctx, pdf_annot_obj(t.ctx, w));
    }
    fz_catch(t.ctx) v = NULL;
    return jstr(env, v ? v : "");
}

JNIEXPORT jobjectArray JNICALL
FN(widgetOptions)(JNIEnv* env, jclass clazz, jlong dochandle, jlong pagehandle, jint index)
{
    target t;
    if (!open_page(dochandle, pagehandle, &t))
        return NULL;
    fz_context* ctx = t.ctx;
    jclass strClass = (*env)->FindClass(env, "java/lang/String");
    jobjectArray arr = NULL;
    const char** opts = NULL;
    fz_var(opts);
    fz_try(ctx)
    {
        pdf_annot* w = nth_widget(ctx, t.page, index);
        int n = w ? pdf_choice_widget_options(ctx, w, 0, NULL) : 0;
        opts = fz_malloc(ctx, sizeof(char*) * (n > 0 ? n : 1));
        if (n > 0)
            n = pdf_choice_widget_options(ctx, w, 0, opts);
        arr = (*env)->NewObjectArray(env, n, strClass, NULL);
        for (int i = 0; arr && i < n; i++) {
            jstring s = jstr(env, opts[i] ? opts[i] : "");
            (*env)->SetObjectArrayElement(env, arr, i, s);
            (*env)->DeleteLocalRef(env, s);
        }
    }
    fz_always(ctx) fz_free(ctx, opts);
    fz_catch(ctx) return NULL;
    return arr;
}

/* Text field / combo / list box: sets the value; font < 0 leaves the field's own style alone. */
JNIEXPORT jboolean JNICALL
FN(setWidgetValue)(JNIEnv* env, jclass clazz, jlong dochandle, jlong pagehandle, jint index, jstring jvalue,
                   jint font, jfloat size, jint argb)
{
    target t;
    if (!open_page(dochandle, pagehandle, &t) || !jvalue)
        return JNI_FALSE;
    fz_context* ctx = t.ctx;
    const char* value = (*env)->GetStringUTFChars(env, jvalue, NULL);
    int ok = 0;
    pdf_enable_journal(ctx, t.doc);
    pdf_begin_operation(ctx, t.doc, "Fill form field");
    fz_try(ctx)
    {
        pdf_annot* w = nth_widget(ctx, t.page, index);
        if (w) {
            int type = pdf_widget_type(ctx, w);
            pdf_dict_dels(ctx, pdf_annot_obj(ctx, w), DOKSHO_FONT_KEY);
            if (type == PDF_WIDGET_TYPE_TEXT) {
                if (font >= 0) {
                    float c[3];
                    rgb_of(argb, c);
                    pdf_set_annot_default_appearance(ctx, w, font_name(font), size, 3, c);
                }
                ok = pdf_set_text_field_value(ctx, w, value);
            } else if (type == PDF_WIDGET_TYPE_COMBOBOX || type == PDF_WIDGET_TYPE_LISTBOX) {
                ok = pdf_set_choice_field_value(ctx, w, value);
            }
            pdf_update_page(ctx, t.page);
        }
        pdf_end_operation(ctx, t.doc);
    }
    fz_always(ctx) (*env)->ReleaseStringUTFChars(env, jvalue, value);
    fz_catch(ctx)
    {
        pdf_abandon_operation(ctx, t.doc);
        ERROR("setWidgetValue: %s", fz_caught_message(ctx));
        ok = 0;
    }
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
FN(toggleWidget)(JNIEnv* env, jclass clazz, jlong dochandle, jlong pagehandle, jint index)
{
    target t;
    if (!open_page(dochandle, pagehandle, &t))
        return JNI_FALSE;
    fz_context* ctx = t.ctx;
    int ok = 0;
    pdf_enable_journal(ctx, t.doc);
    pdf_begin_operation(ctx, t.doc, "Toggle form field");
    fz_try(ctx)
    {
        pdf_annot* w = nth_widget(ctx, t.page, index);
        if (w)
            ok = pdf_toggle_widget(ctx, w);
        pdf_update_page(ctx, t.page);
        pdf_end_operation(ctx, t.doc);
    }
    fz_catch(ctx)
    {
        pdf_abandon_operation(ctx, t.doc);
        ERROR("toggleWidget: %s", fz_caught_message(ctx));
        ok = 0;
    }
    return ok ? JNI_TRUE : JNI_FALSE;
}

/* ---- form fields in any TrueType / OpenType font --------------------------------------------- *
 *
 * MuPDF draws a field's text only in the base-14 fonts its DA names. For any other font the field is
 * filled as usual (value, and a DA in Helvetica at the chosen size and colour — what other viewers
 * fall back to if they redraw it), MuPDF draws its appearance, and then the text part of that
 * appearance — everything from the first BT after the clip — is replaced by ours: laid out with the
 * same metrics MuPDF uses, in a Type0 / Identity-H font embedded as a subset holding only the
 * glyphs this field shows. The widget remembers the font file under /DokshoFont. */

typedef struct
{
    int* gid;
    int* uni;
    float* x; /* glyph origin, in points inside the appearance's box */
    float* y;
    int n, cap;
} dk_glyphs;

static void
dk_push(fz_context* ctx, dk_glyphs* g, int gid, int uni, float x, float y)
{
    if (g->n == g->cap) {
        int cap = g->cap ? g->cap * 2 : 64;
        g->gid = fz_realloc(ctx, g->gid, sizeof(int) * cap);
        g->uni = fz_realloc(ctx, g->uni, sizeof(int) * cap);
        g->x = fz_realloc(ctx, g->x, sizeof(float) * cap);
        g->y = fz_realloc(ctx, g->y, sizeof(float) * cap);
        g->cap = cap;
    }
    g->gid[g->n] = gid;
    g->uni[g->n] = uni;
    g->x[g->n] = x;
    g->y[g->n] = y;
    g->n++;
}

static void
dk_free(fz_context* ctx, dk_glyphs* g)
{
    fz_free(ctx, g->gid);
    fz_free(ctx, g->uni);
    fz_free(ctx, g->x);
    fz_free(ctx, g->y);
    memset(g, 0, sizeof(*g));
}

/* Width of chars [a, b) in em, trailing spaces left out. */
static float
dk_width(const float* adv, const int* ucs, int a, int b)
{
    while (b > a && ucs[b - 1] == ' ')
        b--;
    float w = 0;
    for (int i = a; i < b; i++)
        w += adv[i];
    return w;
}

/* Han, kana, hangul, CJK punctuation and full-width forms — text that breaks between characters. */
static int
dk_cjk(int c)
{
    return (c >= 0x2E80 && c <= 0x9FFF) || (c >= 0xAC00 && c <= 0xD7AF) || (c >= 0xF900 && c <= 0xFAFF)
           || (c >= 0xFF00 && c <= 0xFFEF) || (c >= 0x20000 && c <= 0x3FFFF);
}

static void
dk_place_line(fz_context* ctx, dk_glyphs* out, const int* ucs, const int* gids, const float* adv, int a, int b,
              float x0, float maxw, int q, float size, float y)
{
    float lw = dk_width(adv, ucs, a, b) * size;
    float x = x0;
    if (q == 1) x += (maxw - lw) / 2;
    else if (q == 2) x += maxw - lw;
    for (int i = a; i < b; i++) {
        if (ucs[i] != '\n' && ucs[i] != '\r' && ucs[i] != '\t')
            dk_push(ctx, out, gids[i], ucs[i], x, y);
        x += adv[i] * size;
    }
}

/* Lays the text out exactly as MuPDF's write_variable_text would (single-line, multi-line, comb);
 * returns the size used (an automatic size resolved). */
static float
dk_layout(fz_context* ctx, fz_font* font, const char* text, float size, float w, float h, float b, int multiline,
          int comb, int q, dk_glyphs* out, int* missing)
{
    int n = 0, cap = (int)strlen(text) + 1;
    int* ucs = fz_malloc(ctx, sizeof(int) * cap);
    int* gids = fz_malloc(ctx, sizeof(int) * cap);
    float* adv = fz_malloc(ctx, sizeof(float) * cap);
    fz_try(ctx)
    {
        const char* s = text;
        while (*s) {
            int c;
            s += fz_chartorune(&c, s);
            if (c == '\r' && *s == '\n') continue;
            if (c == '\r') c = '\n';
            if (!multiline && c == '\n') c = ' ';
            ucs[n] = c;
            gids[n] = c == '\n' || c == '\t' ? 0 : fz_encode_character(ctx, font, c);
            if (c == '\t') gids[n] = fz_encode_character(ctx, font, ' ');
            if (gids[n] == 0 && c != '\n' && c != ' ' && c != '\t')
                (*missing)++;
            adv[n] = c == '\n' ? 0 : fz_advance_glyph(ctx, font, gids[n], 0);
            n++;
        }
        float pad = comb > 0 ? 0 : b * 2;
        float W = w - pad * 2, H = h - pad * 2;
        if (size <= 0) {
            if (multiline)
                size = 12;
            else {
                float ms = dk_width(adv, ucs, 0, n);
                size = ms > 0 ? W / ms : 12;
                if (size > H) size = H;
            }
        }
        if (multiline) {
            float lh = size * 1.116f;
            float y = pad + H - size * 1.116f;
            float maxw = W / size;
            int a = 0;
            while (a < n) {
                /* one line: up to a hard break, or the last space that still fits */
                int i = a, brk_end = -1, brk_next = -1, end = -1, next = -1;
                float lw = 0;
                while (i < n) {
                    if (ucs[i] == '\n') {
                        end = i;
                        next = i + 1;
                        break;
                    }
                    /* a line may end before a CJK character (and after one): no spaces there */
                    if (i > a && (dk_cjk(ucs[i]) || dk_cjk(ucs[i - 1])) && ucs[i] != ' ') {
                        brk_end = i;
                        brk_next = i;
                    }
                    if (lw + adv[i] > maxw && i > a && ucs[i] != ' ') {
                        if (brk_end > a) {
                            end = brk_end;
                            next = brk_next;
                        } else {
                            end = i;
                            next = i;
                        }
                        break;
                    }
                    if (ucs[i] == ' ') {
                        brk_end = i;
                        brk_next = i + 1;
                    }
                    lw += adv[i];
                    i++;
                }
                if (end < 0) {
                    end = n;
                    next = n;
                }
                dk_place_line(ctx, out, ucs, gids, adv, a, end, pad, W, q, size, y);
                y -= lh;
                a = next;
            }
        } else if (comb > 0) {
            float cell = W / comb;
            float y = H - size * 0.8f - (H - size) / 2;
            int k = 0;
            for (int i = 0; i < n && k < comb; i++, k++) {
                float x = cell * k + (cell - adv[i] * size) / 2;
                if (ucs[i] != ' ')
                    dk_push(ctx, out, gids[i], ucs[i], x, y);
            }
        } else {
            float y = pad + H - size * 0.8f - (H - size) / 2;
            dk_place_line(ctx, out, ucs, gids, adv, 0, n, pad, W, q, size, y);
        }
    }
    fz_always(ctx)
    {
        fz_free(ctx, ucs);
        fz_free(ctx, gids);
        fz_free(ctx, adv);
    }
    fz_catch(ctx) fz_rethrow(ctx);
    return size;
}

static int
dk_cmp_int(const void* a, const void* b)
{
    return *(const int*)a - *(const int*)b;
}

static void
dk_hex_utf16(fz_buffer* buf, fz_context* ctx, int u)
{
    if (u >= 0x10000) {
        u -= 0x10000;
        fz_append_printf(ctx, buf, "%04x%04x", 0xD800 + (u >> 10), 0xDC00 + (u & 0x3FF));
    } else {
        fz_append_printf(ctx, buf, "%04x", u);
    }
}

/* The font cut down to the glyphs used, by HarfBuzz (MuPDF's own subsetter cannot do CID-keyed CFF).
 * Glyph ids are kept, so the codes in the content stay valid. A collection (.ttc) gives its first
 * face. */
static fz_buffer*
dk_subset(fz_context* ctx, fz_buffer* file, const int* gids, int n)
{
    unsigned char* copy = NULL;
    unsigned int len = 0;
    fz_hb_lock(ctx);
    {
        hb_blob_t* blob = hb_blob_create((const char*)file->data, (unsigned int)file->len, HB_MEMORY_MODE_READONLY, NULL, NULL);
        hb_face_t* face = hb_face_create(blob, 0);
        hb_subset_input_t* in = hb_subset_input_create_or_fail();
        hb_face_t* sub = NULL;
        if (in) {
            hb_set_t* gs = hb_subset_input_glyph_set(in);
            for (int i = 0; i < n; i++)
                hb_set_add(gs, (hb_codepoint_t)gids[i]);
            hb_subset_input_set_flags(in, HB_SUBSET_FLAGS_RETAIN_GIDS);
            sub = hb_subset_or_fail(face, in);
        }
        if (sub) {
            hb_blob_t* ob = hb_face_reference_blob(sub);
            const char* data = hb_blob_get_data(ob, &len);
            if (data && len > 0) {
                copy = fz_malloc_no_throw(ctx, len);
                if (copy) memcpy(copy, data, len);
            }
            hb_blob_destroy(ob);
            hb_face_destroy(sub);
        }
        if (in) hb_subset_input_destroy(in);
        hb_face_destroy(face);
        hb_blob_destroy(blob);
    }
    fz_hb_unlock(ctx);
    if (!copy)
        fz_throw(ctx, FZ_ERROR_FORMAT, "cannot subset this font");
    return fz_new_buffer_from_data(ctx, copy, len);
}

static unsigned int
dk_rd(const unsigned char* p, int n)
{
    unsigned int v = 0;
    while (n-- > 0)
        v = (v << 8) | *p++;
    return v;
}

/* A CFF INDEX at off: its count and where its data begins (offsets are relative to data0 - 1);
 * returns the offset after it, or 0 when it does not fit. */
static size_t
dk_cff_index(const unsigned char* d, size_t len, size_t off, int* count, size_t* offs, int* osz, size_t* data0)
{
    if (off + 2 > len) return 0;
    int n = (int)dk_rd(d + off, 2);
    *count = n;
    if (n == 0) return off + 2;
    if (off + 3 > len) return 0;
    int os = d[off + 2];
    if (os < 1 || os > 4) return 0;
    size_t oa = off + 3, dz = oa + (size_t)(n + 1) * os;
    if (dz > len) return 0;
    *offs = oa;
    *osz = os;
    *data0 = dz - 1;
    size_t end = *data0 + dk_rd(d + oa + (size_t)n * os, os);
    return end <= len ? end : 0;
}

/* For an OpenType font whose CFF is CID-keyed: the CID of every glyph id (count in *n), from the
 * charset. NULL for TrueType and name-keyed CFF, where the glyph id is the code. A PDF reader
 * selects a CID-keyed CFF glyph by CID, so those are the codes to write. */
static int*
dk_cff_cids(fz_context* ctx, fz_buffer* sfnt, int* n_out)
{
    const unsigned char* s = sfnt->data;
    size_t sl = sfnt->len;
    *n_out = 0;
    if (sl < 12 || memcmp(s, "OTTO", 4)) return NULL;
    int tables = (int)dk_rd(s + 4, 2);
    const unsigned char* d = NULL;
    size_t len = 0;
    for (int i = 0; i < tables && 12 + (size_t)i * 16 + 16 <= sl; i++) {
        const unsigned char* r = s + 12 + i * 16;
        if (!memcmp(r, "CFF ", 4)) {
            size_t o = dk_rd(r + 8, 4), l = dk_rd(r + 12, 4);
            if (o + l <= sl) {
                d = s + o;
                len = l;
            }
        }
    }
    if (!d || len < 4) return NULL;
    int cnt, osz;
    size_t offs, data0;
    size_t off = dk_cff_index(d, len, d[2], &cnt, &offs, &osz, &data0); /* Name INDEX */
    if (!off) return NULL;
    size_t top = off;
    if (!dk_cff_index(d, len, top, &cnt, &offs, &osz, &data0) || cnt < 1) return NULL; /* Top DICT INDEX */
    size_t a = data0 + dk_rd(d + offs, osz), b = data0 + dk_rd(d + offs + osz, osz);
    if (b > len || a >= b) return NULL;

    long charset = -1, charstrings = -1, stack[48];
    int sp = 0, ros = 0;
    const unsigned char* p = d + a;
    const unsigned char* e = d + b;
    while (p < e) {
        int b0 = *p;
        if (b0 <= 21) {
            int op = b0;
            p++;
            if (b0 == 12 && p < e) op = 1200 + *p++;
            if (op == 15 && sp > 0) charset = stack[sp - 1];
            if (op == 17 && sp > 0) charstrings = stack[sp - 1];
            if (op == 1230) ros = 1;
            sp = 0;
            continue;
        }
        long v = 0;
        if (b0 == 28 && p + 3 <= e) {
            v = (short)dk_rd(p + 1, 2);
            p += 3;
        } else if (b0 == 29 && p + 5 <= e) {
            v = (int)dk_rd(p + 1, 4);
            p += 5;
        } else if (b0 == 30) {
            p++;
            while (p < e) {
                int nb = *p++;
                if ((nb & 0x0F) == 0x0F || (nb >> 4) == 0x0F) break;
            }
        } else if (b0 >= 32 && b0 <= 246) {
            v = b0 - 139;
            p++;
        } else if (b0 >= 247 && b0 <= 250 && p + 2 <= e) {
            v = (b0 - 247) * 256 + p[1] + 108;
            p += 2;
        } else if (b0 >= 251 && b0 <= 254 && p + 2 <= e) {
            v = -(b0 - 251) * 256 - p[1] - 108;
            p += 2;
        } else {
            p++;
            continue;
        }
        if (sp < 48) stack[sp++] = v;
    }
    if (!ros || charset <= 2 || charstrings <= 0) return NULL;
    int glyphs;
    if (!dk_cff_index(d, len, (size_t)charstrings, &glyphs, &offs, &osz, &data0) || glyphs < 1) return NULL;

    int* cid = fz_calloc(ctx, glyphs, sizeof(int));
    size_t q = (size_t)charset;
    if (q >= len) {
        fz_free(ctx, cid);
        return NULL;
    }
    int fmt = d[q++], gid = 1;
    while (gid < glyphs) {
        if (fmt == 0) {
            if (q + 2 > len) break;
            cid[gid++] = (int)dk_rd(d + q, 2);
            q += 2;
        } else if (fmt == 1 || fmt == 2) {
            int w = fmt == 1 ? 1 : 2;
            if (q + 2 + w > len) break;
            int first = (int)dk_rd(d + q, 2), left = (int)dk_rd(d + q + 2, w);
            q += 2 + w;
            for (int k = 0; k <= left && gid < glyphs; k++)
                cid[gid++] = first + k;
        } else {
            break;
        }
    }
    if (gid < glyphs) {
        fz_free(ctx, cid);
        return NULL;
    }
    *n_out = glyphs;
    return cid;
}

typedef struct
{
    int code, width, uni;
} dk_code;

static int
dk_cmp_code(const void* a, const void* b)
{
    return ((const dk_code*)a)->code - ((const dk_code*)b)->code;
}

/* The embedded subset: a Type0 / Identity-H font. code[i] receives the code to write for glyph i
 * of g: its glyph id (TrueType, name-keyed CFF — CIDToGIDMap Identity) or its CID (CID-keyed CFF). */
static pdf_obj*
dk_add_font(fz_context* ctx, pdf_document* doc, fz_buffer* file, fz_font* font, const dk_glyphs* g, int* code)
{
    int* used = NULL;
    int* cids = NULL;
    dk_code* codes = NULL;
    fz_buffer* sub = NULL;
    fz_buffer* cmap = NULL;
    pdf_obj *ff = NULL, *fd = NULL, *cid = NULL, *t0 = NULL, *tu = NULL, *dict = NULL;
    fz_var(used);
    fz_var(cids);
    fz_var(codes);
    fz_var(sub);
    fz_var(cmap);
    fz_var(ff);
    fz_var(fd);
    fz_var(cid);
    fz_var(t0);
    fz_var(tu);
    fz_var(dict);
    fz_try(ctx)
    {
        /* the glyphs used, sorted and unique, .notdef first */
        int nu = 0;
        used = fz_malloc(ctx, sizeof(int) * (g->n + 1));
        used[nu++] = 0;
        for (int i = 0; i < g->n; i++)
            used[nu++] = g->gid[i];
        qsort(used, nu, sizeof(int), dk_cmp_int);
        int k = 0;
        for (int i = 0; i < nu; i++)
            if (i == 0 || used[i] != used[k - 1])
                used[k++] = used[i];
        nu = k;

        sub = dk_subset(ctx, file, used, nu);
        int is_cff = sub->len >= 4 && !memcmp(sub->data, "OTTO", 4);
        int ncids = 0;
        cids = is_cff ? dk_cff_cids(ctx, sub, &ncids) : NULL;
        for (int i = 0; i < g->n; i++)
            code[i] = cids && g->gid[i] < ncids ? cids[g->gid[i]] : g->gid[i];

        /* code, width, unicode — once per code */
        codes = fz_malloc(ctx, sizeof(dk_code) * (g->n + 1));
        int nc = 0;
        codes[nc].code = 0;
        codes[nc].width = (int)(fz_advance_glyph(ctx, font, 0, 0) * 1000 + 0.5f);
        codes[nc++].uni = 0;
        for (int i = 0; i < g->n; i++) {
            codes[nc].code = code[i];
            codes[nc].width = (int)(fz_advance_glyph(ctx, font, g->gid[i], 0) * 1000 + 0.5f);
            codes[nc++].uni = g->uni[i];
        }
        qsort(codes, nc, sizeof(dk_code), dk_cmp_code);
        k = 0;
        for (int i = 0; i < nc; i++)
            if (i == 0 || codes[i].code != codes[k - 1].code)
                codes[k++] = codes[i];
        nc = k;

        /* subset tag + PostScript-ish name */
        char name[96], base[64];
        const char* fname = fz_font_name(ctx, font);
        int j = 0;
        for (const char* s = fname ? fname : "Font"; *s && j < (int)sizeof(base) - 1; s++)
            if ((*s >= 'A' && *s <= 'Z') || (*s >= 'a' && *s <= 'z') || (*s >= '0' && *s <= '9') || *s == '-')
                base[j++] = *s;
        base[j] = 0;
        if (!j) strcpy(base, "Font");
        unsigned int hsh = 2166136261u;
        for (int i = 0; i < nu; i++)
            hsh = (hsh ^ (unsigned int)used[i]) * 16777619u;
        for (int i = 0; i < 6; i++) {
            name[i] = 'A' + hsh % 26;
            hsh /= 26;
        }
        name[6] = '+';
        fz_strlcpy(name + 7, base, sizeof(name) - 7);

        dict = pdf_new_dict(ctx, doc, 2);
        if (is_cff)
            pdf_dict_put(ctx, dict, PDF_NAME(Subtype), PDF_NAME(OpenType));
        else
            pdf_dict_put_int(ctx, dict, PDF_NAME(Length1), (int64_t)sub->len);
        ff = pdf_add_stream(ctx, doc, sub, dict, 0);
        pdf_drop_obj(ctx, dict);
        dict = NULL;

        fd = pdf_add_new_dict(ctx, doc, 10);
        pdf_dict_put(ctx, fd, PDF_NAME(Type), PDF_NAME(FontDescriptor));
        pdf_dict_put_name(ctx, fd, PDF_NAME(FontName), name);
        fz_rect bb = fz_font_bbox(ctx, font);
        pdf_dict_put_rect(ctx, fd, PDF_NAME(FontBBox), fz_make_rect(bb.x0 * 1000, bb.y0 * 1000, bb.x1 * 1000, bb.y1 * 1000));
        pdf_dict_put_int(ctx, fd, PDF_NAME(Flags), 4);
        pdf_dict_put_int(ctx, fd, PDF_NAME(ItalicAngle), 0);
        pdf_dict_put_int(ctx, fd, PDF_NAME(Ascent), (int64_t)(fz_font_ascender(ctx, font) * 1000));
        pdf_dict_put_int(ctx, fd, PDF_NAME(Descent), (int64_t)(fz_font_descender(ctx, font) * 1000));
        pdf_dict_put_int(ctx, fd, PDF_NAME(CapHeight), (int64_t)(fz_font_ascender(ctx, font) * 1000));
        pdf_dict_put_int(ctx, fd, PDF_NAME(StemV), 80);
        pdf_dict_put(ctx, fd, is_cff ? PDF_NAME(FontFile3) : PDF_NAME(FontFile2), ff);

        cid = pdf_add_new_dict(ctx, doc, 8);
        pdf_dict_put(ctx, cid, PDF_NAME(Type), PDF_NAME(Font));
        pdf_dict_put(ctx, cid, PDF_NAME(Subtype), is_cff ? PDF_NAME(CIDFontType0) : PDF_NAME(CIDFontType2));
        pdf_dict_put_name(ctx, cid, PDF_NAME(BaseFont), name);
        pdf_obj* si = pdf_dict_put_dict(ctx, cid, PDF_NAME(CIDSystemInfo), 3);
        pdf_dict_put_string(ctx, si, PDF_NAME(Registry), "Adobe", 5);
        pdf_dict_put_string(ctx, si, PDF_NAME(Ordering), "Identity", 8);
        pdf_dict_put_int(ctx, si, PDF_NAME(Supplement), 0);
        pdf_dict_put(ctx, cid, PDF_NAME(FontDescriptor), fd);
        if (!is_cff)
            pdf_dict_put(ctx, cid, PDF_NAME(CIDToGIDMap), PDF_NAME(Identity));
        pdf_dict_put_int(ctx, cid, PDF_NAME(DW), 0);
        pdf_obj* wa = pdf_dict_put_array(ctx, cid, PDF_NAME(W), nc * 2);
        for (int i = 0; i < nc; i++) {
            pdf_array_push_int(ctx, wa, codes[i].code);
            pdf_obj* one = pdf_array_push_array(ctx, wa, 1);
            pdf_array_push_int(ctx, one, codes[i].width);
        }

        /* ToUnicode for the glyphs shown, so the text can be searched and copied */
        cmap = fz_new_buffer(ctx, 512);
        fz_append_string(ctx, cmap,
                         "/CIDInit /ProcSet findresource begin\n12 dict begin\nbegincmap\n"
                         "/CIDSystemInfo << /Registry (Adobe) /Ordering (UCS) /Supplement 0 >> def\n"
                         "/CMapName /Adobe-Identity-UCS def\n/CMapType 2 def\n"
                         "1 begincodespacerange\n<0000> <FFFF>\nendcodespacerange\n");
        int left = 0;
        for (int i = 0; i < nc; i++)
            if (codes[i].uni > 0) left++;
        int idx = 0;
        while (left > 0) {
            int chunk = left > 100 ? 100 : left;
            fz_append_printf(ctx, cmap, "%d beginbfchar\n", chunk);
            for (int c = 0; c < chunk && idx < nc; idx++) {
                if (codes[idx].uni <= 0) continue;
                fz_append_printf(ctx, cmap, "<%04x> <", codes[idx].code);
                dk_hex_utf16(cmap, ctx, codes[idx].uni);
                fz_append_string(ctx, cmap, ">\n");
                c++;
            }
            fz_append_string(ctx, cmap, "endbfchar\n");
            left -= chunk;
        }
        fz_append_string(ctx, cmap, "endcmap\nCMapName currentdict /CMap defineresource pop\nend\nend\n");
        tu = pdf_add_stream(ctx, doc, cmap, NULL, 0);

        t0 = pdf_add_new_dict(ctx, doc, 6);
        pdf_dict_put(ctx, t0, PDF_NAME(Type), PDF_NAME(Font));
        pdf_dict_put(ctx, t0, PDF_NAME(Subtype), PDF_NAME(Type0));
        pdf_dict_put_name(ctx, t0, PDF_NAME(BaseFont), name);
        pdf_dict_put(ctx, t0, PDF_NAME(Encoding), PDF_NAME(Identity_H));
        pdf_obj* df = pdf_dict_put_array(ctx, t0, PDF_NAME(DescendantFonts), 1);
        pdf_array_push(ctx, df, cid);
        pdf_dict_put(ctx, t0, PDF_NAME(ToUnicode), tu);
    }
    fz_always(ctx)
    {
        fz_free(ctx, used);
        fz_free(ctx, cids);
        fz_free(ctx, codes);
        fz_drop_buffer(ctx, sub);
        fz_drop_buffer(ctx, cmap);
        pdf_drop_obj(ctx, dict);
        pdf_drop_obj(ctx, ff);
        pdf_drop_obj(ctx, fd);
        pdf_drop_obj(ctx, cid);
        pdf_drop_obj(ctx, tu);
    }
    fz_catch(ctx)
    {
        pdf_drop_obj(ctx, t0);
        fz_rethrow(ctx);
    }
    return t0;
}

/* Where MuPDF's own text begins in a text widget's appearance: the first BT after the clip
 * ("re W n"), else the end of the clip; -1 when the stream is not one of MuPDF's. */
static ptrdiff_t
dk_text_start(const unsigned char* d, size_t len)
{
    const char* clip = "W\nn\n";
    size_t cl = strlen(clip);
    for (size_t i = 0; i + cl <= len; i++) {
        if (!memcmp(d + i, clip, cl)) {
            size_t after = i + cl;
            for (size_t j = after; j + 3 <= len; j++)
                if (!memcmp(d + j, "BT\n", 3))
                    return (ptrdiff_t)j;
            return (ptrdiff_t)after;
        }
    }
    return -1;
}

/* Fills a text field in the font file at fontpath. Returns the number of characters the font has
 * no glyph for (drawn as its .notdef), or -1 on failure. */
JNIEXPORT jint JNICALL
FN(setWidgetValueFont)(JNIEnv* env, jclass clazz, jlong dochandle, jlong pagehandle, jint index, jstring jvalue,
                       jstring jfontpath, jfloat size, jint argb)
{
    target t;
    if (!open_page(dochandle, pagehandle, &t) || !jvalue || !jfontpath)
        return -1;
    fz_context* ctx = t.ctx;
    const char* value = (*env)->GetStringUTFChars(env, jvalue, NULL);
    const char* path = (*env)->GetStringUTFChars(env, jfontpath, NULL);
    int result = -1, missing = 0;
    fz_buffer* file = NULL;
    fz_buffer* ap = NULL;
    fz_buffer* contents = NULL;
    fz_font* font = NULL;
    pdf_obj* res = NULL;
    pdf_obj* f0 = NULL;
    dk_glyphs g = {0};
    int* code = NULL;
    fz_var(code);
    fz_var(file);
    fz_var(ap);
    fz_var(contents);
    fz_var(font);
    fz_var(res);
    fz_var(f0);
    pdf_enable_journal(ctx, t.doc);
    pdf_begin_operation(ctx, t.doc, "Fill form field");
    fz_try(ctx)
    {
        pdf_annot* w = nth_widget(ctx, t.page, index);
        if (!w || pdf_widget_type(ctx, w) != PDF_WIDGET_TYPE_TEXT)
            fz_throw(ctx, FZ_ERROR_ARGUMENT, "not a text field");
        pdf_obj* obj = pdf_annot_obj(ctx, w);
        int ff = pdf_field_flags(ctx, obj);
        int multiline = (ff & PDF_TX_FIELD_IS_MULTILINE) != 0;
        int comb = (ff & PDF_TX_FIELD_IS_COMB) ? pdf_text_widget_max_len(ctx, w) : 0;
        if (multiline) comb = 0;

        /* the field filled the usual way: MuPDF draws border, background and a Helvetica text */
        float c[3];
        rgb_of(argb, c);
        pdf_set_annot_default_appearance(ctx, w, "Helv", size, 3, c);
        if (!pdf_set_text_field_value(ctx, w, value))
            fz_throw(ctx, FZ_ERROR_ARGUMENT, "value refused");
        pdf_update_page(ctx, t.page);

        pdf_obj* ap_n = pdf_dict_getl(ctx, obj, PDF_NAME(AP), PDF_NAME(N), NULL);
        if (!pdf_is_stream(ctx, ap_n))
            fz_throw(ctx, FZ_ERROR_FORMAT, "no appearance to draw into");
        fz_rect bbox = pdf_dict_get_rect(ctx, ap_n, PDF_NAME(BBox));
        fz_matrix mtx = pdf_dict_get_matrix(ctx, ap_n, PDF_NAME(Matrix));
        ap = pdf_load_stream(ctx, ap_n);
        unsigned char* d;
        size_t dl = fz_buffer_storage(ctx, ap, &d);
        ptrdiff_t cut = dk_text_start(d, dl);

        file = fz_read_file(ctx, path);
        font = fz_new_font_from_buffer(ctx, NULL, file, 0, 0);

        float border = pdf_annot_border_width(ctx, w);
        int q = pdf_annot_quadding(ctx, w);
        float used_size = dk_layout(ctx, font, value, size, bbox.x1 - bbox.x0, bbox.y1 - bbox.y0, border, multiline,
                                    comb, q, &g, &missing);

        contents = fz_new_buffer(ctx, dl + 64 + g.n * 16);
        if (cut >= 0)
            fz_append_data(ctx, contents, d, (size_t)cut);
        else
            fz_append_string(ctx, contents, "/Tx BMC\nq\n");
        if (g.n > 0) {
            code = fz_malloc(ctx, sizeof(int) * g.n);
            f0 = dk_add_font(ctx, t.doc, file, font, &g, code);
            fz_append_printf(ctx, contents, "BT\n/DkF %g Tf\n%g %g %g rg\n", used_size, c[0], c[1], c[2]);
            for (int i = 0; i < g.n; i++) {
                if (i == 0 || g.y[i] != g.y[i - 1] || comb > 0) {
                    if (i > 0) fz_append_string(ctx, contents, "> Tj\n");
                    fz_append_printf(ctx, contents, "1 0 0 1 %g %g Tm\n<", g.x[i], g.y[i]);
                }
                fz_append_printf(ctx, contents, "%04x", code[i]);
            }
            fz_append_string(ctx, contents, "> Tj\nET\n");
        }
        fz_append_string(ctx, contents, "Q\nEMC\n");

        pdf_obj* oldres = pdf_dict_get(ctx, ap_n, PDF_NAME(Resources));
        res = pdf_is_dict(ctx, oldres) ? pdf_copy_dict(ctx, oldres) : pdf_new_dict(ctx, t.doc, 1);
        pdf_obj* fonts = pdf_dict_put_dict(ctx, res, PDF_NAME(Font), 1);
        if (f0)
            pdf_dict_puts(ctx, fonts, "DkF", f0);
        pdf_set_annot_appearance(ctx, w, "N", NULL, mtx, bbox, res, contents);
        pdf_dict_puts_drop(ctx, obj, DOKSHO_FONT_KEY, pdf_new_text_string(ctx, path));

        /* With NeedAppearances set MuPDF redraws every field locally at each page load, which
         * would paint over this one; the appearances are all in the file by now. */
        pdf_obj* form = pdf_dict_getl(ctx, pdf_trailer(ctx, t.doc), PDF_NAME(Root), PDF_NAME(AcroForm), NULL);
        if (pdf_dict_get_bool(ctx, form, PDF_NAME(NeedAppearances)))
            pdf_dict_put_bool(ctx, form, PDF_NAME(NeedAppearances), 0);
        pdf_clean_obj(ctx, obj);
        pdf_end_operation(ctx, t.doc);
        result = missing;
    }
    fz_always(ctx)
    {
        (*env)->ReleaseStringUTFChars(env, jvalue, value);
        (*env)->ReleaseStringUTFChars(env, jfontpath, path);
        dk_free(ctx, &g);
        fz_free(ctx, code);
        fz_drop_buffer(ctx, file);
        fz_drop_buffer(ctx, ap);
        fz_drop_buffer(ctx, contents);
        fz_drop_font(ctx, font);
        pdf_drop_obj(ctx, res);
        pdf_drop_obj(ctx, f0);
    }
    fz_catch(ctx)
    {
        pdf_abandon_operation(ctx, t.doc);
        ERROR("setWidgetValueFont: %s", fz_caught_message(ctx));
        result = -1;
    }
    return result;
}

/* The font file a field was last filled in by us, or null. */
JNIEXPORT jstring JNICALL
FN(widgetFont)(JNIEnv* env, jclass clazz, jlong dochandle, jlong pagehandle, jint index)
{
    target t;
    if (!open_page(dochandle, pagehandle, &t))
        return NULL;
    const char* v = NULL;
    fz_try(t.ctx)
    {
        pdf_annot* w = nth_widget(t.ctx, t.page, index);
        if (w) {
            pdf_obj* o = pdf_dict_gets(t.ctx, pdf_annot_obj(t.ctx, w), DOKSHO_FONT_KEY);
            if (pdf_is_string(t.ctx, o))
                v = pdf_to_text_string(t.ctx, o);
        }
    }
    fz_catch(t.ctx) v = NULL;
    return v ? jstr(env, v) : NULL;
}

/* ---- annotations ------------------------------------------------------------------------------ */

/* Per annotation (widgets and links are not in this list), 5 floats: type, x0 y0 x1 y1. */
JNIEXPORT jfloatArray JNICALL
FN(annots)(JNIEnv* env, jclass clazz, jlong dochandle, jlong pagehandle)
{
    target t;
    if (!open_page(dochandle, pagehandle, &t))
        return NULL;
    fz_context* ctx = t.ctx;
    int n = 0;
    float* out = NULL;
    fz_var(out);
    fz_try(ctx)
    {
        pdf_annot* a;
        for (a = pdf_first_annot(ctx, t.page); a; a = pdf_next_annot(ctx, a))
            n++;
        out = fz_malloc(ctx, sizeof(float) * 5 * (n > 0 ? n : 1));
        int i = 0;
        for (a = pdf_first_annot(ctx, t.page); a; a = pdf_next_annot(ctx, a), i++) {
            fz_rect r = pdf_bound_annot(ctx, a);
            float* o = out + i * 5;
            o[0] = pdf_annot_type(ctx, a);
            o[1] = nx(&t, r.x0);
            o[2] = ny(&t, r.y0);
            o[3] = nx(&t, r.x1);
            o[4] = ny(&t, r.y1);
        }
    }
    fz_catch(ctx)
    {
        fz_free(ctx, out);
        return NULL;
    }
    jfloatArray arr = (*env)->NewFloatArray(env, n * 5);
    if (arr && n > 0)
        (*env)->SetFloatArrayRegion(env, arr, 0, n * 5, out);
    fz_free(ctx, out);
    return arr;
}

JNIEXPORT jstring JNICALL
FN(annotText)(JNIEnv* env, jclass clazz, jlong dochandle, jlong pagehandle, jint index)
{
    target t;
    if (!open_page(dochandle, pagehandle, &t))
        return NULL;
    const char* v = NULL;
    fz_try(t.ctx)
    {
        pdf_annot* a = nth_annot(t.ctx, t.page, index);
        if (a)
            v = pdf_annot_contents(t.ctx, a);
    }
    fz_catch(t.ctx) v = NULL;
    return jstr(env, v ? v : "");
}

/* A text box's style: font code, size, colour as 0xRRGGBB; null when it has none. */
JNIEXPORT jfloatArray JNICALL
FN(annotStyle)(JNIEnv* env, jclass clazz, jlong dochandle, jlong pagehandle, jint index)
{
    target t;
    if (!open_page(dochandle, pagehandle, &t))
        return NULL;
    float out[3];
    int ok = 0;
    fz_try(t.ctx)
    {
        pdf_annot* a = nth_annot(t.ctx, t.page, index);
        if (a && pdf_annot_has_default_appearance(t.ctx, a)) {
            const char* font;
            float size, c[4] = { 0, 0, 0, 0 };
            int n = 0, r, g, b;
            pdf_annot_default_appearance(t.ctx, a, &font, &size, &n, c);
            if (n == 1) {
                r = g = b = (int)(c[0] * 255);
            } else if (n == 4) {
                r = (int)((1 - fminf(1, c[0] + c[3])) * 255);
                g = (int)((1 - fminf(1, c[1] + c[3])) * 255);
                b = (int)((1 - fminf(1, c[2] + c[3])) * 255);
            } else {
                r = (int)(c[0] * 255);
                g = (int)(c[1] * 255);
                b = (int)(c[2] * 255);
            }
            out[0] = font_code(font);
            out[1] = size;
            out[2] = (float)((r << 16) | (g << 8) | b); /* 24 bits: exact in a float */
            ok = 1;
        }
    }
    fz_catch(t.ctx) ok = 0;
    if (!ok)
        return NULL;
    jfloatArray arr = (*env)->NewFloatArray(env, 3);
    if (arr)
        (*env)->SetFloatArrayRegion(env, arr, 0, 3, out);
    return arr;
}

static void
style_free_text(fz_context* ctx, pdf_annot* a, int font, float size, jint argb)
{
    float c[3];
    rgb_of(argb, c);
    pdf_set_annot_default_appearance(ctx, a, font_name(font), size, 3, c);
    pdf_set_annot_border_width(ctx, a, 0);
}

/* A text box at (x, y, w, h), normalised; returns its index or -1. */
JNIEXPORT jint JNICALL
FN(addText)(JNIEnv* env, jclass clazz, jlong dochandle, jlong pagehandle, jfloat x, jfloat y, jfloat w, jfloat h,
            jstring jtext, jint font, jfloat size, jint argb)
{
    target t;
    if (!open_page(dochandle, pagehandle, &t) || !jtext)
        return -1;
    fz_context* ctx = t.ctx;
    const char* text = (*env)->GetStringUTFChars(env, jtext, NULL);
    int index = -1;
    pdf_enable_journal(ctx, t.doc);
    pdf_begin_operation(ctx, t.doc, "Add text box");
    fz_try(ctx)
    {
        pdf_annot* a = pdf_create_annot(ctx, t.page, PDF_ANNOT_FREE_TEXT);
        fz_point p0 = to_page(&t, x, y), p1 = to_page(&t, x + w, y + h);
        pdf_set_annot_rect(ctx, a, fz_make_rect(p0.x, p0.y, p1.x, p1.y));
        style_free_text(ctx, a, font, size, argb);
        pdf_set_annot_contents(ctx, a, text);
        pdf_update_page(ctx, t.page);
        int i = 0;
        for (pdf_annot* it = pdf_first_annot(ctx, t.page); it; it = pdf_next_annot(ctx, it), i++)
            if (it == a)
                index = i;
        pdf_drop_annot(ctx, a);
        pdf_end_operation(ctx, t.doc);
    }
    fz_always(ctx) (*env)->ReleaseStringUTFChars(env, jtext, text);
    fz_catch(ctx)
    {
        pdf_abandon_operation(ctx, t.doc);
        ERROR("addText: %s", fz_caught_message(ctx));
        index = -1;
    }
    return index;
}

/* Changes a text box: rect null keeps the place, text null keeps the words, font < 0 the style. */
JNIEXPORT jboolean JNICALL
FN(editText)(JNIEnv* env, jclass clazz, jlong dochandle, jlong pagehandle, jint index, jfloatArray jrect,
             jstring jtext, jint font, jfloat size, jint argb)
{
    target t;
    if (!open_page(dochandle, pagehandle, &t))
        return JNI_FALSE;
    fz_context* ctx = t.ctx;
    float rect[4];
    int has_rect = 0;
    if (jrect && (*env)->GetArrayLength(env, jrect) >= 4) {
        (*env)->GetFloatArrayRegion(env, jrect, 0, 4, rect);
        has_rect = 1;
    }
    const char* text = jtext ? (*env)->GetStringUTFChars(env, jtext, NULL) : NULL;
    int ok = 0;
    pdf_enable_journal(ctx, t.doc);
    pdf_begin_operation(ctx, t.doc, has_rect && !text ? "Move text box" : "Edit text box");
    fz_try(ctx)
    {
        pdf_annot* a = nth_annot(ctx, t.page, index);
        if (a && pdf_annot_type(ctx, a) == PDF_ANNOT_FREE_TEXT) {
            if (has_rect) {
                fz_point p0 = to_page(&t, rect[0], rect[1]), p1 = to_page(&t, rect[2], rect[3]);
                pdf_set_annot_rect(ctx, a, fz_make_rect(p0.x, p0.y, p1.x, p1.y));
            }
            if (font >= 0)
                style_free_text(ctx, a, font, size, argb);
            if (text)
                pdf_set_annot_contents(ctx, a, text);
            pdf_update_page(ctx, t.page);
            ok = 1;
        }
        pdf_end_operation(ctx, t.doc);
    }
    fz_always(ctx)
    {
        if (text)
            (*env)->ReleaseStringUTFChars(env, jtext, text);
    }
    fz_catch(ctx)
    {
        pdf_abandon_operation(ctx, t.doc);
        ERROR("editText: %s", fz_caught_message(ctx));
        ok = 0;
    }
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
FN(deleteAnnot)(JNIEnv* env, jclass clazz, jlong dochandle, jlong pagehandle, jint index)
{
    target t;
    if (!open_page(dochandle, pagehandle, &t))
        return JNI_FALSE;
    fz_context* ctx = t.ctx;
    int ok = 0;
    pdf_enable_journal(ctx, t.doc);
    pdf_begin_operation(ctx, t.doc, "Delete annotation");
    fz_try(ctx)
    {
        pdf_annot* a = nth_annot(ctx, t.page, index);
        if (a) {
            pdf_delete_annot(ctx, t.page, a);
            pdf_update_page(ctx, t.page);
            ok = 1;
        }
        pdf_end_operation(ctx, t.doc);
    }
    fz_catch(ctx)
    {
        pdf_abandon_operation(ctx, t.doc);
        ok = 0;
    }
    return ok ? JNI_TRUE : JNI_FALSE;
}

/* One pen stroke: xy = x0 y0 x1 y1 … normalised, width in points, ARGB (alpha = opacity). */
JNIEXPORT jboolean JNICALL
FN(addInk)(JNIEnv* env, jclass clazz, jlong dochandle, jlong pagehandle, jfloatArray jxy, jfloat width, jint argb)
{
    target t;
    if (!open_page(dochandle, pagehandle, &t) || !jxy)
        return JNI_FALSE;
    fz_context* ctx = t.ctx;
    int len = (*env)->GetArrayLength(env, jxy) / 2;
    if (len < 1)
        return JNI_FALSE;
    float* xy = NULL;
    fz_point* pts = NULL;
    int ok = 0;
    fz_var(xy);
    fz_var(pts);
    pdf_enable_journal(ctx, t.doc);
    pdf_begin_operation(ctx, t.doc, "Draw");
    fz_try(ctx)
    {
        /* a dot is a stroke too: give it a second point so it has a length to cap */
        int count = len == 1 ? 2 : len;
        xy = fz_malloc(ctx, sizeof(float) * len * 2);
        pts = fz_malloc(ctx, sizeof(fz_point) * count);
        (*env)->GetFloatArrayRegion(env, jxy, 0, len * 2, xy);
        for (int i = 0; i < len; i++)
            pts[i] = to_page(&t, xy[i * 2], xy[i * 2 + 1]);
        if (len == 1) {
            pts[1] = pts[0];
            pts[1].x += 0.01f;
        }
        float c[3];
        rgb_of(argb, c);
        pdf_annot* a = pdf_create_annot(ctx, t.page, PDF_ANNOT_INK);
        pdf_set_annot_border_width(ctx, a, width);
        pdf_set_annot_color(ctx, a, 3, c);
        int alpha = (argb >> 24) & 0xFF;
        if (alpha < 255)
            pdf_set_annot_opacity(ctx, a, alpha / 255.0f);
        pdf_set_annot_ink_list(ctx, a, 1, &count, pts);
        pdf_update_page(ctx, t.page);
        pdf_drop_annot(ctx, a);
        pdf_end_operation(ctx, t.doc);
        ok = 1;
    }
    fz_always(ctx)
    {
        fz_free(ctx, xy);
        fz_free(ctx, pts);
    }
    fz_catch(ctx)
    {
        pdf_abandon_operation(ctx, t.doc);
        ERROR("addInk: %s", fz_caught_message(ctx));
        ok = 0;
    }
    return ok ? JNI_TRUE : JNI_FALSE;
}

static int
annot_hit(fz_context* ctx, target* t, pdf_annot* a, int type, const fz_point* pts, int n, float radius)
{
    if (type == PDF_ANNOT_INK && pdf_annot_has_ink_list(ctx, a)) {
        float reach = radius + pdf_annot_border_width(ctx, a) / 2;
        fz_rect r = fz_expand_rect(pdf_bound_annot(ctx, a), radius);
        int strokes = pdf_annot_ink_list_count(ctx, a);
        for (int k = 0; k < n; k++) {
            if (!fz_is_point_inside_rect(pts[k], r))
                continue;
            for (int s = 0; s < strokes; s++) {
                int vn = pdf_annot_ink_list_stroke_count(ctx, a, s);
                fz_point prev = pdf_annot_ink_list_stroke_vertex(ctx, a, s, 0);
                if (vn == 1 && seg_dist(pts[k], prev, prev) <= reach)
                    return 1;
                for (int v = 1; v < vn; v++) {
                    fz_point cur = pdf_annot_ink_list_stroke_vertex(ctx, a, s, v);
                    if (seg_dist(pts[k], prev, cur) <= reach)
                        return 1;
                    prev = cur;
                }
            }
        }
        return 0;
    }
    fz_rect r = fz_expand_rect(pdf_bound_annot(ctx, a), radius);
    for (int k = 0; k < n; k++)
        if (fz_is_point_inside_rect(pts[k], r))
            return 1;
    return 0;
}

/* The eraser: every annotation of the kinds in mask touched by a circle of radius (points) around
 * any of the points goes. Returns how many were removed. */
JNIEXPORT jint JNICALL
FN(erase)(JNIEnv* env, jclass clazz, jlong dochandle, jlong pagehandle, jfloatArray jxy, jfloat radius, jint mask)
{
    target t;
    if (!open_page(dochandle, pagehandle, &t) || !jxy)
        return 0;
    fz_context* ctx = t.ctx;
    int len = (*env)->GetArrayLength(env, jxy) / 2;
    if (len < 1)
        return 0;
    float* xy = NULL;
    fz_point* pts = NULL;
    pdf_annot** hits = NULL;
    int nhits = 0, removed = 0, began = 0;
    fz_var(xy);
    fz_var(pts);
    fz_var(hits);
    fz_var(began);
    fz_try(ctx)
    {
        xy = fz_malloc(ctx, sizeof(float) * len * 2);
        pts = fz_malloc(ctx, sizeof(fz_point) * len);
        (*env)->GetFloatArrayRegion(env, jxy, 0, len * 2, xy);
        for (int i = 0; i < len; i++)
            pts[i] = to_page(&t, xy[i * 2], xy[i * 2 + 1]);
        int total = 0;
        for (pdf_annot* a = pdf_first_annot(ctx, t.page); a; a = pdf_next_annot(ctx, a))
            total++;
        hits = fz_malloc(ctx, sizeof(pdf_annot*) * (total > 0 ? total : 1));
        for (pdf_annot* a = pdf_first_annot(ctx, t.page); a; a = pdf_next_annot(ctx, a)) {
            int type = pdf_annot_type(ctx, a);
            int kind = type == PDF_ANNOT_INK ? ERASE_INK : type == PDF_ANNOT_FREE_TEXT ? ERASE_TEXT : ERASE_OTHER;
            if (type == PDF_ANNOT_POPUP || !(mask & kind))
                continue;
            if (annot_hit(ctx, &t, a, type, pts, len, radius))
                hits[nhits++] = a;
        }
        if (nhits > 0) {
            pdf_enable_journal(ctx, t.doc);
            pdf_begin_operation(ctx, t.doc, "Erase");
            began = 1;
            for (int i = 0; i < nhits; i++) {
                pdf_delete_annot(ctx, t.page, hits[i]);
                removed++;
            }
            pdf_update_page(ctx, t.page);
            pdf_end_operation(ctx, t.doc);
        }
    }
    fz_always(ctx)
    {
        fz_free(ctx, xy);
        fz_free(ctx, pts);
        fz_free(ctx, hits);
    }
    fz_catch(ctx)
    {
        if (began)
            pdf_abandon_operation(ctx, t.doc);
        ERROR("erase: %s", fz_caught_message(ctx));
    }
    return removed;
}

/* ---- undo / redo / save ----------------------------------------------------------------------- */

/* bit 0: something to undo, bit 1: something to redo. */
JNIEXPORT jint JNICALL
FN(undoState)(JNIEnv* env, jclass clazz, jlong dochandle)
{
    target t;
    if (!open_doc(dochandle, &t))
        return 0;
    int state = 0;
    fz_try(t.ctx)
    {
        if (t.doc->journal) {
            if (pdf_can_undo(t.ctx, t.doc)) state |= 1;
            if (pdf_can_redo(t.ctx, t.doc)) state |= 2;
        }
    }
    fz_catch(t.ctx) state = 0;
    return state;
}

JNIEXPORT jboolean JNICALL
FN(undo)(JNIEnv* env, jclass clazz, jlong dochandle, jboolean redo)
{
    target t;
    if (!open_doc(dochandle, &t))
        return JNI_FALSE;
    int ok = 0;
    fz_try(t.ctx)
    {
        if (t.doc->journal) {
            if (redo && pdf_can_redo(t.ctx, t.doc)) {
                pdf_redo(t.ctx, t.doc);
                ok = 1;
            } else if (!redo && pdf_can_undo(t.ctx, t.doc)) {
                pdf_undo(t.ctx, t.doc);
                ok = 1;
            }
            if (ok)
                pdf_update_open_pages(t.ctx, t.doc);
        }
    }
    fz_catch(t.ctx)
    {
        ERROR("undo: %s", fz_caught_message(t.ctx));
        ok = 0;
    }
    return ok ? JNI_TRUE : JNI_FALSE;
}

/* Saves into path. Incremental when the file allows it (an append — the open document keeps
 * reading the bytes it already knows); otherwise the whole file is written beside it and renamed
 * over, so the document being read is never truncated under itself. */
JNIEXPORT jboolean JNICALL
FN(save)(JNIEnv* env, jclass clazz, jlong dochandle, jstring jpath)
{
    target t;
    if (!open_doc(dochandle, &t) || !jpath)
        return JNI_FALSE;
    fz_context* ctx = t.ctx;
    const char* path = (*env)->GetStringUTFChars(env, jpath, NULL);
    char tmp[4096];
    int ok = 0, incremental = 0;
    snprintf(tmp, sizeof tmp, "%s.doksho-saving", path);
    fz_try(ctx)
    {
        pdf_write_options opts = pdf_default_write_options;
        opts.do_compress = 1; /* new streams (embedded font subsets, appearances) deflated */
        opts.do_compress_fonts = 1;
        incremental = pdf_can_be_saved_incrementally(ctx, t.doc);
        opts.do_incremental = incremental;
        if (incremental) {
            pdf_save_document(ctx, t.doc, path, &opts);
        } else {
            pdf_save_document(ctx, t.doc, tmp, &opts);
            if (rename(tmp, path) != 0)
                fz_throw(ctx, FZ_ERROR_SYSTEM, "rename %s failed", tmp);
        }
        ok = 1;
    }
    fz_catch(ctx)
    {
        ERROR("save %s: %s", path, fz_caught_message(ctx));
        if (!incremental)
            unlink(tmp);
        ok = 0;
    }
    (*env)->ReleaseStringUTFChars(env, jpath, path);
    return ok ? JNI_TRUE : JNI_FALSE;
}
