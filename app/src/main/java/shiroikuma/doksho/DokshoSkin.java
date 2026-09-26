package shiroikuma.doksho;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.webkit.WebView;
import android.widget.AbsSeekBar;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.foobnix.model.AppState;
import com.foobnix.pdf.info.R;
import com.foobnix.pdf.info.TintUtil;

/**
 * The app-wide black-yellow skin. Librera paints most of its surfaces in code (and keeps dozens of
 * literal colours in layouts), so a theme alone cannot reach them — the lesson ArcaneChat learned
 * the hard way. Instead every window is walked after each layout pass and its views repainted from
 * {@link DokshoUi}:
 *
 * <ul>
 *   <li><b>Activities</b> — through {@link Application.ActivityLifecycleCallbacks} ({@link #register}).</li>
 *   <li><b>Dialogs and popup menus</b> — through their window background: the flavour replaces
 *   upstream's {@code bg_dialog_round_*} / {@code bg_popup_round} with {@link SkinDrawable}, whose
 *   first draw hands its host view to {@link #install}. So all ~80 upstream {@code AlertDialog}s
 *   and every popup are reached without a single call site being edited.</li>
 * </ul>
 *
 * <p>What is repainted, and what is left alone:
 * <ul>
 *   <li>text: neutral ink → text / secondary text (by how strong the original was), coloured ink →
 *   the accent (links, marks), reds kept (warnings); inside a bar → the bar ink;</li>
 *   <li>grounds: neutral fills (white, grey, black) and Librera's tint fills → our grounds; boxes
 *   and pills get the border colour, width and corner; saturated fills (colour swatches, tags) are
 *   never touched;</li>
 *   <li>icons: vector / tinted icons → the accent (bar ink inside a bar); bitmaps (book covers,
 *   page images) never;</li>
 *   <li>the reader's page area ({@code documentView}, {@code pager2}), web views and surfaces are
 *   skipped whole, and so is anything tagged {@link DokshoViews#NO_SKIN} (the UI page itself).</li>
 * </ul>
 *
 * <p>Every original value is remembered on first sight, and re-remembered whenever Librera changes
 * it behind our back (the current value no longer equals what we last applied) — so repeated passes
 * are idempotent and a colour Librera sets later is still mapped, never compounded.
 */
public final class DokshoSkin {

    public static final int KIND_ACTIVITY = 0;
    public static final int KIND_DIALOG = 1;
    public static final int KIND_POPUP = 2;

    private DokshoSkin() {
    }

    // ---------------------------------------------------------------------------------------------
    // wiring
    // ---------------------------------------------------------------------------------------------

    public static void register(@NonNull Application app) {
        DokshoUi.init(app);
        app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override
            public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle savedInstanceState) {
            }

            @Override
            public void onActivityStarted(@NonNull Activity activity) {
            }

            @Override
            public void onActivityResumed(@NonNull Activity activity) {
                if (activity.getWindow() != null) install(activity.getWindow().getDecorView(), KIND_ACTIVITY);
            }

            @Override
            public void onActivityPaused(@NonNull Activity activity) {
            }

            @Override
            public void onActivityStopped(@NonNull Activity activity) {
            }

            @Override
            public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle outState) {
            }

            @Override
            public void onActivityDestroyed(@NonNull Activity activity) {
            }
        });
    }

    /**
     * Librera's own colour knobs, set from ours whenever its state is (re)loaded: the OLED-black
     * theme, the bar tint, and the "UI text colour" its reader bars and links honour. Upstream code
     * then draws in our colours by itself; the walker covers the rest.
     */
    public static void applyToLibrera() {
        if (!DokshoUi.skin()) return;
        AppState s = AppState.get();
        s.appTheme = AppState.THEME_DARK_OLED;
        s.isSystemThemeColor = false;
        s.tintThemeColor = DokshoUi.i(DokshoUi.C_BAR_BG);
        s.isUiTextColor = true;
        s.uiTextColor = DokshoUi.i(DokshoUi.C_BAR_FG);
        if (s.uiTextColor == s.tintThemeColor) {
            // Librera draws white when the two are equal; nudge the ground by one step instead.
            s.tintThemeColor = s.tintThemeColor ^ 0x00000001;
        }
        TintUtil.color = s.tintThemeColor;
    }

    /** Walk {@code root} now and after every layout pass from here on; idempotent per root. */
    public static void install(@NonNull final View root, final int kind) {
        if (root.getTag(R.id.doksho_skin_installed) != null) {
            apply(root, kind);
            return;
        }
        root.setTag(R.id.doksho_skin_installed, Boolean.TRUE);
        root.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                apply(root, kind);
            }
        });
        root.post(() -> apply(root, kind));
    }

    public static void apply(@NonNull View root, int kind) {
        if (!DokshoUi.skin()) return;
        try {
            walk(root, kind, false);
        } catch (Throwable ignored) {
            // A skin must never take the app down: an odd view is simply left as upstream drew it.
        }
    }

    // ---------------------------------------------------------------------------------------------
    // the walk
    // ---------------------------------------------------------------------------------------------

    /** What a view looked like before we touched it, and what we last gave it. */
    private static final class Memo {
        int textOrig;
        int textApplied;
        boolean textSeen;
        float sizeOrig;
        float sizeApplied;
        Typeface faceOrig;
        Typeface faceApplied;
        boolean faceSeen;
        int bgOrig;
        int bgApplied;
        boolean bgSeen;
        float radiusOrig = -1;
    }

    private static Memo memo(View v) {
        Object o = v.getTag(R.id.doksho_skin_memo);
        if (o instanceof Memo) return (Memo) o;
        Memo m = new Memo();
        v.setTag(R.id.doksho_skin_memo, m);
        return m;
    }

    private static boolean skipWhole(View v) {
        if (DokshoViews.NO_SKIN.equals(v.getTag())) return true;
        if (v instanceof WebView || v instanceof SurfaceView || v instanceof TextureView) return true;
        int id = v.getId();
        if (id == R.id.documentView || id == R.id.pager2) return true;
        String cls = v.getClass().getName();
        return cls.startsWith("org.ebookdroid.") || cls.endsWith(".DrawView") || cls.endsWith(".BgClickbaleView");
    }

    private static void walk(View v, int kind, boolean inBar) {
        if (skipWhole(v)) return;
        Memo m = memo(v);
        boolean bar = inBar || background(v, m, kind, inBar);

        if (v instanceof TextView) text((TextView) v, m, kind, bar);
        if (v instanceof ImageView) icon((ImageView) v, kind, bar);
        if (v instanceof CompoundButton) {
            ((CompoundButton) v).setButtonTintList(ColorStateList.valueOf(accent(kind, bar)));
        }
        if (v instanceof AbsSeekBar) {
            ColorStateList c = ColorStateList.valueOf(accent(kind, bar));
            AbsSeekBar s = (AbsSeekBar) v;
            s.setThumbTintList(c);
            s.setProgressTintList(c);
        } else if (v instanceof ProgressBar) {
            ProgressBar p = (ProgressBar) v;
            p.setIndeterminateTintList(ColorStateList.valueOf(accent(kind, bar)));
            p.setProgressTintList(ColorStateList.valueOf(accent(kind, bar)));
        }

        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) walk(g.getChildAt(i), kind, bar);
        }
    }

    // ---- colours by role -------------------------------------------------------------------------

    private static int ground(int kind, boolean bar) {
        if (bar) return DokshoUi.i(DokshoUi.C_BAR_BG);
        return kind == KIND_ACTIVITY ? DokshoUi.i(DokshoUi.C_BG) : DokshoUi.i(DokshoUi.C_DLG_BG);
    }

    private static int ink(int kind, boolean bar) {
        if (bar) return DokshoUi.i(DokshoUi.C_BAR_FG);
        return kind == KIND_ACTIVITY ? DokshoUi.i(DokshoUi.C_TEXT) : DokshoUi.i(DokshoUi.C_DLG_TEXT);
    }

    private static int ink2(int kind, boolean bar) {
        if (bar || kind != KIND_ACTIVITY) return (ink(kind, bar) & 0x00FFFFFF) | 0xC8000000;
        return DokshoUi.i(DokshoUi.C_TEXT2);
    }

    private static int accent(int kind, boolean bar) {
        if (bar) return DokshoUi.i(DokshoUi.C_BAR_FG);
        return kind == KIND_ACTIVITY ? DokshoUi.i(DokshoUi.C_ACCENT) : DokshoUi.i(DokshoUi.C_DLG_BORDER);
    }

    private static int border(int kind) {
        return kind == KIND_ACTIVITY ? DokshoUi.i(DokshoUi.C_BOX_BORDER) : DokshoUi.i(DokshoUi.C_DLG_BORDER);
    }

    // ---- classification --------------------------------------------------------------------------

    /** Grey, white or black — a surface or an ink with no colour of its own. */
    static boolean neutral(int c) {
        int r = Color.red(c), g = Color.green(c), b = Color.blue(c);
        int max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b));
        return max - min < 40;
    }

    private static boolean reddish(int c) {
        float[] hsv = new float[3];
        Color.colorToHSV(c, hsv);
        return hsv[1] > 0.45f && (hsv[0] < 20 || hsv[0] > 340);
    }

    private static boolean weak(int c) {
        if (Color.alpha(c) < 0xC0) return true;
        double lum = 0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c);
        return lum > 0x55 && lum < 0xB0;
    }

    // ---- per view --------------------------------------------------------------------------------

    private static void text(TextView t, Memo m, int kind, boolean bar) {
        int cur = t.getCurrentTextColor();
        if (!m.textSeen || cur != m.textApplied) {
            m.textOrig = cur;
            m.textSeen = true;
        }
        int want;
        int o = m.textOrig;
        if (Color.alpha(o) == 0) {
            want = o; // invisible on purpose
        } else if (!neutral(o)) {
            want = reddish(o) ? o : accent(kind, bar);
        } else {
            want = weak(o) ? ink2(kind, bar) : ink(kind, bar);
        }
        if (want != cur) t.setTextColor(want);
        m.textApplied = want;
        t.setLinkTextColor(accent(kind, bar));
        if (t instanceof EditText) {
            t.setHintTextColor(ink2(kind, bar));
            t.setBackgroundTintList(ColorStateList.valueOf(accent(kind, bar)));
        }
        if (kind == KIND_DIALOG && isDialogButton(t)) pill(t, kind);

        // compound icons (the tab bar's are compound drawables)
        for (Drawable d : t.getCompoundDrawablesRelative()) tint(d, accentOrBarInk(kind, bar));
        for (Drawable d : t.getCompoundDrawables()) tint(d, accentOrBarInk(kind, bar));

        font(t, m);
    }

    private static int accentOrBarInk(int kind, boolean bar) {
        return bar ? DokshoUi.i(DokshoUi.C_BAR_FG) : accent(kind, false);
    }

    private static boolean isDialogButton(TextView t) {
        int id = t.getId();
        return id == android.R.id.button1 || id == android.R.id.button2 || id == android.R.id.button3;
    }

    private static void pill(TextView t, int kind) {
        Object done = t.getTag(R.id.doksho_skin_pill);
        if (done instanceof Integer && (Integer) done == DokshoUi.stamp()) return;
        Context c = t.getContext();
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(ground(kind, false));
        bg.setStroke(Math.max(1, Math.round(DokshoUi.dp(c, 1.5f))), border(kind));
        bg.setCornerRadius(DokshoUi.dp(c, 50));
        t.setBackground(new RippleDrawable(ColorStateList.valueOf((ink(kind, false) & 0x00FFFFFF) | 0x33000000), bg, null));
        t.setAllCaps(false);
        int h = Math.round(DokshoUi.dp(c, 16));
        int v = Math.round(DokshoUi.dp(c, 6));
        t.setPadding(h, v, h, v);
        t.setTag(R.id.doksho_skin_pill, DokshoUi.stamp());
    }

    private static void font(TextView t, Memo m) {
        String family = DokshoUi.s(DokshoUi.FONT_FAMILY);
        int weight = DokshoUi.i(DokshoUi.FONT_WEIGHT);
        int scale = DokshoUi.i(DokshoUi.FONT_SCALE);

        Typeface curFace = t.getTypeface();
        if (!m.faceSeen || curFace != m.faceApplied) {
            m.faceOrig = curFace;
            m.faceSeen = true;
        }
        float curSize = t.getTextSize();
        if (m.sizeOrig == 0 || Math.abs(curSize - m.sizeApplied) > 0.5f) {
            m.sizeOrig = curSize;
        }

        if (!family.isEmpty() || weight != 400) {
            boolean bold = m.faceOrig != null && m.faceOrig.isBold();
            boolean italic = m.faceOrig != null && m.faceOrig.isItalic();
            Typeface want = DokshoFonts.at(t.getContext(), family, bold ? Math.min(900, weight + 300) : weight, italic);
            if (want != curFace) t.setTypeface(want);
            m.faceApplied = want;
        } else if (m.faceApplied != null && curFace == m.faceApplied) {
            t.setTypeface(m.faceOrig);
            m.faceApplied = null;
        }

        float wantSize = m.sizeOrig * scale / 100f;
        if (Math.abs(wantSize - curSize) > 0.5f) t.setTextSize(TypedValue.COMPLEX_UNIT_PX, wantSize);
        m.sizeApplied = wantSize;
    }

    private static void icon(ImageView iv, int kind, boolean bar) {
        Drawable d = iv.getDrawable();
        if (d == null) return;
        boolean tinted = iv.getImageTintList() != null || iv.getColorFilter() != null;
        if (d instanceof BitmapDrawable && !tinted) return; // a picture — a cover, a page
        if (DokshoViews.NO_SKIN.equals(iv.getTag())) return;
        int want = accentOrBarInk(kind, bar);
        iv.setImageTintList(null);
        iv.setColorFilter(want, PorterDuff.Mode.SRC_IN);
    }

    private static void tint(@Nullable Drawable d, int color) {
        if (d == null || d instanceof BitmapDrawable && d.getColorFilter() == null) return;
        d.setColorFilter(new PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN));
    }

    /**
     * Repaint the view's own ground. Returns true when the view is one of Librera's bars (filled with
     * its tint colour), so the subtree is drawn in the bar colours.
     */
    private static boolean background(View v, Memo m, int kind, boolean inBar) {
        Drawable bg = v.getBackground();
        if (bg == null) return false;
        GradientDrawable shape = shapeOf(bg);
        int cur;
        if (bg instanceof ColorDrawable) {
            cur = ((ColorDrawable) bg).getColor();
        } else if (shape != null && shape.getColor() != null) {
            cur = shape.getColor().getDefaultColor();
        } else {
            return false;
        }
        if (!m.bgSeen || cur != m.bgApplied) {
            m.bgOrig = cur;
            m.bgSeen = true;
        }
        int o = m.bgOrig;
        if (Color.alpha(o) < 0x40) return false; // a veil, a scrim, a selection wash
        boolean isBar = !inBar && (o == TintUtil.color || o == AppState.get().tintThemeColor);
        if (!isBar && !neutral(o)) return false; // a colour that means something: swatch, tag, mark

        int want = ground(kind, inBar || isBar);
        if (Color.alpha(o) < 0xFF) want = (want & 0x00FFFFFF) | (o & 0xFF000000);
        if (bg instanceof ColorDrawable) {
            if (want != cur) ((ColorDrawable) bg.mutate()).setColor(want);
        } else {
            GradientDrawable s = (GradientDrawable) shape.mutate();
            if (want != cur) s.setColor(want);
            box(v, s, m, kind);
        }
        m.bgApplied = want;
        return isBar;
    }

    /** Rounded boxes and pills get the border: its colour, width and — for boxes — the corner. */
    private static void box(View v, GradientDrawable s, Memo m, int kind) {
        if (s.getShape() != GradientDrawable.RECTANGLE) return;
        float r = s.getCornerRadius();
        if (m.radiusOrig < 0) m.radiusOrig = r;
        if (m.radiusOrig <= 0) return; // square: a full-width band, not a box
        Integer stamped = (Integer) v.getTag(R.id.doksho_skin_box);
        if (stamped != null && stamped == DokshoUi.stamp()) return;
        Context c = v.getContext();
        String widthKey = kind == KIND_ACTIVITY ? DokshoUi.BOX_BORDER_W : DokshoUi.DLG_BORDER_W;
        int w = DokshoUi.borderPx(c, widthKey);
        s.setStroke(w, border(kind));
        boolean pill = m.radiusOrig >= Math.min(v.getWidth(), v.getHeight()) / 2f - 1 && v.getHeight() > 0;
        if (!pill) {
            String radiusKey = kind == KIND_ACTIVITY ? DokshoUi.BOX_RADIUS : DokshoUi.DLG_RADIUS;
            s.setCornerRadius(DokshoUi.dp(c, DokshoUi.i(radiusKey)));
        }
        v.setTag(R.id.doksho_skin_box, DokshoUi.stamp());
    }

    @Nullable
    private static GradientDrawable shapeOf(Drawable d) {
        for (int depth = 0; depth < 4 && d != null; depth++) {
            if (d instanceof GradientDrawable) return (GradientDrawable) d;
            if (d instanceof RippleDrawable || d instanceof LayerDrawable) {
                LayerDrawable l = (LayerDrawable) d;
                if (l.getNumberOfLayers() == 0) return null;
                d = l.getDrawable(0);
            } else if (d instanceof InsetDrawable) {
                d = ((InsetDrawable) d).getDrawable();
            } else {
                d = d.getCurrent() != d ? d.getCurrent() : null;
            }
        }
        return null;
    }
}
