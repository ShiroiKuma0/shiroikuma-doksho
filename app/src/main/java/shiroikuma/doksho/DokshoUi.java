package shiroikuma.doksho;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 白い熊 読書 UI — every setting of the fork's UI page, with its default. One SharedPreferences file
 * ({@link #PREFS}) holds them all; it is exactly what the Export / Import "UI" category carries.
 *
 * <p>The defaults are the house black-yellow look: pure {@code #000000} grounds, pure
 * {@code #FFFF00} ink and borders — never a theme-derived near-black or amber.
 *
 * <p>Readers call the static getters; the page writes through {@link #set}, which bumps
 * {@link #stamp()} so every skinned window knows to repaint.
 */
public final class DokshoUi {

    public static final String PREFS = "doksho_ui";

    public static final int BLACK = 0xFF000000;
    public static final int YELLOW = 0xFFFFFF00;
    public static final int YELLOW_DIM = 0xFFC8C800;
    public static final int WARN = 0xFFFF5252;

    // ---- keys ------------------------------------------------------------------------------------

    /** Fork behaviour: paint the whole app in these colours (off = upstream's own look). */
    public static final String SKIN = "skin_enabled";

    public static final String C_BG = "c_bg";
    public static final String C_TEXT = "c_text";
    public static final String C_TEXT2 = "c_text2";
    public static final String C_ACCENT = "c_accent";
    public static final String C_BOX_BORDER = "c_box_border";

    public static final String C_BAR_BG = "c_bar_bg";
    public static final String C_BAR_FG = "c_bar_fg";

    public static final String C_DLG_BG = "c_dlg_bg";
    public static final String C_DLG_TEXT = "c_dlg_text";
    public static final String C_DLG_BORDER = "c_dlg_border";

    public static final String BOX_BORDER_W = "box_border_w";   // tenths of a dp
    public static final String BOX_RADIUS = "box_radius";       // dp
    public static final String DLG_BORDER_W = "dlg_border_w";   // tenths of a dp
    public static final String DLG_RADIUS = "dlg_radius";       // dp

    public static final String FONT_FAMILY = "font_family";     // "" = system, else a DokshoFonts id
    public static final String FONT_WEIGHT = "font_weight";     // 100..900
    public static final String FONT_SCALE = "font_scale";       // percent

    public static final String HEAD_FONT_FAMILY = "head_font_family";
    public static final String HEAD_FONT_WEIGHT = "head_font_weight";
    public static final String HEAD_FONT_SIZE = "head_font_size"; // sp, the UI page's own headings

    /** Reader night mode (DokshoNight): the duotone's ends and how strongly it replaces the inversion. */
    public static final String C_NIGHT_TEXT = "c_night_text";
    public static final String C_NIGHT_BG = "c_night_bg";
    public static final String NIGHT_STRENGTH = "night_strength"; // percent, 0 = Librera's own

    /** Reading (DokshoPaging): the colour around the page, and book mode's edge scrolling. */
    public static final String C_PAGE_SURROUND = "c_page_surround";
    public static final String EDGE_PAGING = "edge_paging";
    /** Scroll mode (DokshoPaging.scrollLimitsX): sideways panning stops at the page's edges. */
    public static final String SCROLL_EDGES = "scroll_edges";
    /** Set once books have been switched to Scroll mode (the film) as the fork's default. */
    public static final String FILM_DEFAULT_DONE = "film_default_done";

    /** PDF tools (shiroikuma.doksho.annot): the last tool, and each tool's options. */
    public static final String ANNOT_TOOL = "annot_tool";
    public static final String ANNOT_PEN_COLOR = "annot_pen_color";
    public static final String ANNOT_PEN_WIDTH = "annot_pen_width";       // tenths of a point
    public static final String ANNOT_PEN_OPACITY = "annot_pen_opacity";   // percent
    public static final String ANNOT_PEN_FINGER = "annot_pen_finger";     // a finger draws (off: stylus only)
    public static final String ANNOT_ERASER_SIZE = "annot_eraser_size";   // dp, the circle's radius
    public static final String ANNOT_ERASE_INK = "annot_erase_ink";
    public static final String ANNOT_ERASE_TEXT = "annot_erase_text";
    public static final String ANNOT_ERASE_OTHER = "annot_erase_other";
    public static final String ANNOT_TEXT_FONT = "annot_text_font";       // 0 sans, 1 serif, 2 mono
    public static final String ANNOT_TEXT_SIZE = "annot_text_size";       // points
    public static final String ANNOT_TEXT_COLOR = "annot_text_color";
    public static final String ANNOT_FORM_FONT = "annot_form_font";
    public static final String ANNOT_FORM_SIZE = "annot_form_size";       // points, 0 = fit the field
    public static final String ANNOT_FORM_COLOR = "annot_form_color";
    public static final String ANNOT_FORM_OWN = "annot_form_own";         // keep the field's own style
    public static final String ANNOT_FORM_FONT_FILE = "annot_form_font_file"; // a TrueType / OpenType file; "" = ANNOT_FORM_FONT

    /** Colour-picker memory: the last colours chosen, newest first, comma-separated ARGB ints. */
    public static final String RECENT_COLORS = "recent_colors";
    public static final int MAX_RECENT = 8;

    private static final List<Setting> ALL = new ArrayList<>();

    /** A setting's key, kind and default — the page, reset and export all read this one table. */
    public static final class Setting {
        public final String key;
        public final boolean isBool;
        public final boolean isString;
        public final int defInt;
        public final boolean defBool;
        public final String defString;

        Setting(String key, int def) {
            this.key = key;
            this.isBool = false;
            this.isString = false;
            this.defInt = def;
            this.defBool = false;
            this.defString = null;
        }

        Setting(String key, boolean def) {
            this.key = key;
            this.isBool = true;
            this.isString = false;
            this.defInt = 0;
            this.defBool = def;
            this.defString = null;
        }

        Setting(String key, String def) {
            this.key = key;
            this.isBool = false;
            this.isString = true;
            this.defInt = 0;
            this.defBool = false;
            this.defString = def;
        }
    }

    static {
        ALL.add(new Setting(SKIN, true));

        ALL.add(new Setting(C_BG, BLACK));
        ALL.add(new Setting(C_TEXT, YELLOW));
        ALL.add(new Setting(C_TEXT2, YELLOW_DIM));
        ALL.add(new Setting(C_ACCENT, YELLOW));
        ALL.add(new Setting(C_BOX_BORDER, YELLOW));

        ALL.add(new Setting(C_BAR_BG, BLACK));
        ALL.add(new Setting(C_BAR_FG, YELLOW));

        ALL.add(new Setting(C_DLG_BG, BLACK));
        ALL.add(new Setting(C_DLG_TEXT, YELLOW));
        ALL.add(new Setting(C_DLG_BORDER, YELLOW));

        ALL.add(new Setting(BOX_BORDER_W, 15));
        ALL.add(new Setting(BOX_RADIUS, 12));
        ALL.add(new Setting(DLG_BORDER_W, 20));
        ALL.add(new Setting(DLG_RADIUS, 8));

        ALL.add(new Setting(FONT_FAMILY, ""));
        ALL.add(new Setting(FONT_WEIGHT, 400));
        ALL.add(new Setting(FONT_SCALE, 100));

        ALL.add(new Setting(HEAD_FONT_FAMILY, ""));
        ALL.add(new Setting(HEAD_FONT_WEIGHT, 700));
        ALL.add(new Setting(HEAD_FONT_SIZE, 20));

        ALL.add(new Setting(C_NIGHT_TEXT, YELLOW));
        ALL.add(new Setting(C_NIGHT_BG, BLACK));
        ALL.add(new Setting(NIGHT_STRENGTH, 100));

        ALL.add(new Setting(C_PAGE_SURROUND, BLACK));
        ALL.add(new Setting(EDGE_PAGING, true));
        ALL.add(new Setting(SCROLL_EDGES, true));
        ALL.add(new Setting(FILM_DEFAULT_DONE, false));

        ALL.add(new Setting(ANNOT_TOOL, 0));
        ALL.add(new Setting(ANNOT_PEN_COLOR, 0xFF1E4BD2));
        ALL.add(new Setting(ANNOT_PEN_WIDTH, 15));
        ALL.add(new Setting(ANNOT_PEN_OPACITY, 100));
        ALL.add(new Setting(ANNOT_PEN_FINGER, true));
        ALL.add(new Setting(ANNOT_ERASER_SIZE, 12));
        ALL.add(new Setting(ANNOT_ERASE_INK, true));
        ALL.add(new Setting(ANNOT_ERASE_TEXT, true));
        ALL.add(new Setting(ANNOT_ERASE_OTHER, true));
        ALL.add(new Setting(ANNOT_TEXT_FONT, 0));
        ALL.add(new Setting(ANNOT_TEXT_SIZE, 12));
        ALL.add(new Setting(ANNOT_TEXT_COLOR, BLACK));
        ALL.add(new Setting(ANNOT_FORM_FONT, 0));
        ALL.add(new Setting(ANNOT_FORM_SIZE, 0));
        ALL.add(new Setting(ANNOT_FORM_COLOR, BLACK));
        ALL.add(new Setting(ANNOT_FORM_OWN, false));
        ALL.add(new Setting(ANNOT_FORM_FONT_FILE, ""));
    }

    private static SharedPreferences sp;
    private static volatile int stamp = 1;

    private DokshoUi() {
    }

    public static void init(@NonNull Context context) {
        if (sp == null) {
            sp = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        }
    }

    private static SharedPreferences sp() {
        return sp;
    }

    @NonNull
    public static List<Setting> settings() {
        return ALL;
    }

    private static Setting find(String key) {
        for (Setting s : ALL) {
            if (s.key.equals(key)) return s;
        }
        throw new IllegalArgumentException("unknown setting " + key);
    }

    // ---- read ------------------------------------------------------------------------------------

    public static int i(String key) {
        Setting s = find(key);
        return sp() == null ? s.defInt : sp().getInt(key, s.defInt);
    }

    public static boolean b(String key) {
        Setting s = find(key);
        return sp() == null ? s.defBool : sp().getBoolean(key, s.defBool);
    }

    @NonNull
    public static String s(String key) {
        Setting s = find(key);
        String v = sp() == null ? s.defString : sp().getString(key, s.defString);
        return v == null ? "" : v;
    }

    public static boolean skin() {
        return b(SKIN);
    }

    /** Monotonic change counter: a skinned window repaints when the stamp it painted with is stale. */
    public static int stamp() {
        return stamp;
    }

    // ---- write -----------------------------------------------------------------------------------

    public static void set(String key, int value) {
        sp().edit().putInt(key, value).apply();
        changed();
    }

    public static void set(String key, boolean value) {
        sp().edit().putBoolean(key, value).apply();
        changed();
    }

    public static void set(String key, String value) {
        sp().edit().putString(key, value).apply();
        changed();
    }

    /** Back to the house defaults — every setting, the colour memory kept. */
    public static void reset() {
        SharedPreferences.Editor e = sp().edit();
        for (Setting s : ALL) e.remove(s.key);
        e.commit();
        changed();
    }

    public static void changed() {
        stamp++;
    }

    // ---- colour memory ---------------------------------------------------------------------------

    /** The one-click swatches: remembered colours newest first, topped up with the house colours. */
    @NonNull
    public static List<Integer> recentColors() {
        LinkedHashSet<Integer> out = new LinkedHashSet<>();
        String raw = sp() == null ? "" : sp().getString(RECENT_COLORS, "");
        if (raw != null) {
            for (String part : raw.split(",")) {
                try {
                    if (!part.trim().isEmpty()) out.add((int) Long.parseLong(part.trim()));
                } catch (NumberFormatException ignored) {
                    // a damaged entry just drops out
                }
            }
        }
        out.add(BLACK);
        out.add(YELLOW);
        out.add(Color.WHITE);
        out.add(YELLOW_DIM);
        List<Integer> list = new ArrayList<>(out);
        return list.size() > MAX_RECENT ? list.subList(0, MAX_RECENT) : list;
    }

    public static void rememberColor(int color) {
        LinkedHashSet<Integer> out = new LinkedHashSet<>();
        out.add(color);
        String raw = sp().getString(RECENT_COLORS, "");
        if (raw != null) {
            for (String part : raw.split(",")) {
                try {
                    if (!part.trim().isEmpty()) out.add((int) Long.parseLong(part.trim()));
                } catch (NumberFormatException ignored) {
                    // skip
                }
            }
        }
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (Integer c : out) {
            if (n++ >= MAX_RECENT) break;
            if (sb.length() > 0) sb.append(',');
            sb.append(c);
        }
        sp().edit().putString(RECENT_COLORS, sb.toString()).apply();
    }

    // ---- derived ---------------------------------------------------------------------------------

    public static float dp(Context c, float v) {
        return v * c.getResources().getDisplayMetrics().density;
    }

    /** A border width setting (tenths of a dp) in pixels; 0 stays 0. */
    public static int borderPx(Context c, String key) {
        int tenths = i(key);
        if (tenths <= 0) return 0;
        return Math.max(1, Math.round(dp(c, tenths / 10f)));
    }
}
