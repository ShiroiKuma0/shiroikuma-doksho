package shiroikuma.doksho;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.format.DateFormat;
import android.text.style.ForegroundColorSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.documentfile.provider.DocumentFile;

import com.foobnix.model.AppProfile;
import com.foobnix.pdf.info.R;
import com.foobnix.ui2.MainTabs2;

import java.util.Locale;

import shiroikuma.doksho.automation.AutomationAuth;
import shiroikuma.doksho.backup.ShiroikumaExport;

/**
 * 白い熊 読書 UI — the fork's settings page: every configurable item of the fork, grouped, black-
 * yellow, in the kxkb UI page's visual format (36 / 54 / 72 / 90 dp indents, 20 sp bold headings
 * underlined as wide as their text, a 1 px rule between top-level groups, tight rows). Opened by a
 * long-press on the Settings tab of the main screen.
 *
 * <p>Built in code, from {@link DokshoUi}; every change applies at once and the page repaints in
 * the new colours, so the page itself is the first preview. Each group also ends in its own live
 * preview.
 *
 * <p>The first section is Export / Import (the Kōjiki flow, via {@link ExportImportPanel}) with the
 * three 保存復元 automation rows of the sister-app contract.
 */
public class DokshoUiActivity extends Activity implements ExportImportPanel.Host {

    private static final int REQ_DIR = 41;
    private static final int REQ_IMPORT = 42;
    private static final int REQ_FONT = 43;

    private static final int IND_HEAD = 36;
    private static final int IND_SUB = 54;
    private static final int IND_L1 = 72;
    private static final int IND_L2 = 90;

    private ScrollView scroll;
    private LinearLayout page;
    private ExportImportPanel panel;
    /** Something Librera paints at creation changed: the main screen is recreated on leaving. */
    private boolean libreraDirty;
    /** The font slot an import is for (family key), so the imported font is chosen right away. */
    private String pendingFontKey;

    public static void open(@NonNull Context context) {
        Intent i = new Intent(context, DokshoUiActivity.class);
        if (!(context instanceof Activity)) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(i);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        DokshoUi.init(this);
        scroll = new ScrollView(this);
        scroll.setTag(DokshoViews.NO_SKIN);
        scroll.setFillViewport(true);
        page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setTag(DokshoViews.NO_SKIN);
        page.setPadding(0, 0, 0, dp(32));
        scroll.addView(page, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(scroll);
        panel = new ExportImportPanel(this, this);
        rebuild();
    }

    @Override
    protected void onResume() {
        super.onResume();
        rebuild(); // the export status is re-queried on every open
    }

    @Override
    public void finish() {
        if (libreraDirty) {
            AppProfile.save(this);
            Intent main = new Intent(this, MainTabs2.class);
            main.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(main);
        }
        super.finish();
    }

    // ---------------------------------------------------------------------------------------------
    // colours of the page itself
    // ---------------------------------------------------------------------------------------------

    private int bg() {
        return DokshoUi.i(DokshoUi.C_BG);
    }

    private int ink() {
        return DokshoUi.i(DokshoUi.C_TEXT);
    }

    private int ink2() {
        return DokshoUi.i(DokshoUi.C_TEXT2);
    }

    private int accent() {
        return DokshoUi.i(DokshoUi.C_ACCENT);
    }

    private int dp(float v) {
        return DokshoViews.dp(this, v);
    }

    // ---------------------------------------------------------------------------------------------
    // the page
    // ---------------------------------------------------------------------------------------------

    private void rebuild() {
        final int y = scroll.getScrollY();
        Window w = getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(bg()));
            w.setStatusBarColor(bg());
            w.setNavigationBarColor(bg());
        }
        scroll.setBackgroundColor(bg());
        page.removeAllViews();

        TextView title = body(Doksho.NAME + " UI", DokshoUi.i(DokshoUi.HEAD_FONT_SIZE) + 4, ink(), true, true);
        title.setPadding(dp(IND_HEAD), dp(14), dp(16), dp(4));
        page.addView(title);

        sectionExportImport();
        sectionBehaviour();
        sectionColours();
        sectionShapes();
        sectionFonts();
        sectionReading();
        sectionAbout();

        scroll.post(() -> scroll.scrollTo(0, y));
    }

    // ---- 1. Export / Import ----------------------------------------------------------------------

    private void sectionExportImport() {
        heading(getString(R.string.doksho_ui_sec_eximport), true);
        View row = itemRow(IND_L1, getString(R.string.doksho_eim_entry), eximportSummary(null), null, v -> panel.show());
        if (ShiroikumaExport.exportDir(this) != null) {
            // The last-export lookup lists the directory: off the main thread, filled in when known.
            final TextView summary = (TextView) ((LinearLayout) ((LinearLayout) row).getChildAt(0)).getChildAt(1);
            final Context app = getApplicationContext();
            new Thread(() -> {
                final DocumentFile newest = ShiroikumaExport.newestExport(app);
                runOnUiThread(() -> {
                    if (!isFinishing() && summary.isAttachedToWindow()) summary.setText(eximportSummary(newest == null ? NONE : newest));
                });
            }, "doksho-ui-status").start();
        }

        final Context ctx = this;
        Switch enabled = toggle(AutomationAuth.isEnabled(ctx), on -> AutomationAuth.setEnabled(ctx, on));
        itemRow(IND_L1, getString(R.string.doksho_auto_switch), getString(R.string.doksho_auto_switch_desc), enabled,
                v -> enabled.toggle());

        Switch require = toggle(AutomationAuth.isTokenRequired(ctx), on -> {
            AutomationAuth.setTokenRequired(ctx, on);
            rebuild();
        });
        itemRow(IND_L1, getString(R.string.doksho_auto_require_token), getString(R.string.doksho_auto_require_token_desc),
                require, v -> require.toggle());

        if (AutomationAuth.isTokenRequired(ctx)) {
            View regen = DokshoViews.pill(this, getString(R.string.doksho_auto_regenerate), v ->
                    DokshoViews.showConfirm(this, getString(R.string.doksho_auto_token_regen_title),
                            getString(R.string.doksho_auto_token_regen_msg), getString(R.string.doksho_auto_regenerate),
                            () -> {
                                AutomationAuth.regenerateToken(ctx);
                                DokshoViews.toast(ctx, getString(R.string.doksho_auto_token_regenerated));
                                rebuild();
                            }));
            itemRow(IND_L2, getString(R.string.doksho_auto_token),
                    AutomationAuth.abbreviate(AutomationAuth.token(ctx)) + "\n" + getString(R.string.doksho_auto_token_desc),
                    regen, v -> {
                        ClipboardManager cb = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                        if (cb != null) {
                            cb.setPrimaryClip(ClipData.newPlainText("automation_token", AutomationAuth.token(ctx)));
                            DokshoViews.toast(ctx, getString(R.string.doksho_auto_token_copied));
                        }
                    });
        }
    }

    /** Marker for "looked, found nothing" (null = not looked yet). */
    private static final Object NONE = new Object();

    /**
     * Directory in yellow once set, "not set" in red; then the last export — red when there is
     * none. {@code newest} is null while the lookup is still running.
     */
    private CharSequence eximportSummary(@Nullable Object newest) {
        SpannableStringBuilder sb = new SpannableStringBuilder(getString(R.string.doksho_eim_entry_desc));
        sb.append('\n');
        String dir = ShiroikumaExport.dirLabel(this);
        int start = sb.length();
        sb.append(getString(R.string.doksho_eim_dir)).append(": ")
                .append(dir != null ? dir : getString(R.string.doksho_eim_dir_unset));
        sb.setSpan(new ForegroundColorSpan(dir != null ? ink() : DokshoUi.WARN), start, sb.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.append('\n');
        start = sb.length();
        boolean warn;
        if (dir == null) {
            sb.append(getString(R.string.doksho_eim_warn_nodir));
            warn = true;
        } else if (newest == null) {
            sb.append(getString(R.string.doksho_eim_last_checking));
            warn = false;
        } else if (!(newest instanceof DocumentFile)) {
            sb.append(getString(R.string.doksho_eim_warn_none));
            warn = true;
        } else {
            DocumentFile f = (DocumentFile) newest;
            long ts = f.lastModified();
            sb.append(getString(R.string.doksho_eim_last_line, DateFormat.getDateFormat(this).format(ts) + " "
                    + DateFormat.getTimeFormat(this).format(ts) + " · " + ShiroikumaExport.humanSize(f.length())));
            warn = false;
        }
        sb.setSpan(new ForegroundColorSpan(warn ? DokshoUi.WARN : ink()), start, sb.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return sb;
    }

    // ---- 2. Behaviour -----------------------------------------------------------------------------

    private void sectionBehaviour() {
        heading(getString(R.string.doksho_ui_sec_behaviour), false);
        Switch skin = toggle(DokshoUi.skin(), on -> {
            DokshoUi.set(DokshoUi.SKIN, on);
            DokshoSkin.applyToLibrera();
            libreraDirty = true;
            rebuild();
        });
        itemRow(IND_L1, getString(R.string.doksho_ui_skin), getString(R.string.doksho_ui_skin_desc), skin,
                v -> skin.toggle());
        itemRow(IND_L1, getString(R.string.doksho_ui_reset), getString(R.string.doksho_ui_reset_desc), null,
                v -> DokshoViews.showConfirm(this, getString(R.string.doksho_ui_reset),
                        getString(R.string.doksho_ui_reset_confirm), getString(R.string.doksho_ui_reset_action), () -> {
                            DokshoUi.reset();
                            DokshoSkin.applyToLibrera();
                            libreraDirty = true;
                            rebuild();
                        }));
    }

    // ---- 3. Colours --------------------------------------------------------------------------------

    private void sectionColours() {
        heading(getString(R.string.doksho_ui_sec_colours), false);

        sub(getString(R.string.doksho_ui_sub_general));
        colorRow(R.string.doksho_ui_c_bg, DokshoUi.C_BG, false);
        colorRow(R.string.doksho_ui_c_text, DokshoUi.C_TEXT, false);
        colorRow(R.string.doksho_ui_c_text2, DokshoUi.C_TEXT2, false);
        colorRow(R.string.doksho_ui_c_accent, DokshoUi.C_ACCENT, false);
        colorRow(R.string.doksho_ui_c_box_border, DokshoUi.C_BOX_BORDER, false);
        preview(generalPreview());

        sub(getString(R.string.doksho_ui_sub_bars));
        colorRow(R.string.doksho_ui_c_bar_bg, DokshoUi.C_BAR_BG, true);
        colorRow(R.string.doksho_ui_c_bar_fg, DokshoUi.C_BAR_FG, true);
        preview(barPreview());

        sub(getString(R.string.doksho_ui_sub_dialogs));
        colorRow(R.string.doksho_ui_c_dlg_bg, DokshoUi.C_DLG_BG, false);
        colorRow(R.string.doksho_ui_c_dlg_text, DokshoUi.C_DLG_TEXT, false);
        colorRow(R.string.doksho_ui_c_dlg_border, DokshoUi.C_DLG_BORDER, false);
        preview(dialogPreview());
    }

    // ---- 4. Borders & shapes -----------------------------------------------------------------------

    private void sectionShapes() {
        heading(getString(R.string.doksho_ui_sec_shapes), false);

        sub(getString(R.string.doksho_ui_sub_boxes));
        final View[] boxPv = new View[1];
        slider(R.string.doksho_ui_border_w, DokshoUi.BOX_BORDER_W, 0, 80, 5, this::tenthsDp, () -> refreshBox(boxPv[0]));
        slider(R.string.doksho_ui_radius, DokshoUi.BOX_RADIUS, 0, 40, 1, v -> v + " dp", () -> refreshBox(boxPv[0]));
        boxPv[0] = boxPreview();
        preview(boxPv[0]);

        sub(getString(R.string.doksho_ui_sub_dialogs));
        final View[] dlgPv = new View[1];
        slider(R.string.doksho_ui_border_w, DokshoUi.DLG_BORDER_W, 0, 80, 5, this::tenthsDp, () -> refreshDialog(dlgPv[0]));
        slider(R.string.doksho_ui_radius, DokshoUi.DLG_RADIUS, 0, 40, 1, v -> v + " dp", () -> refreshDialog(dlgPv[0]));
        dlgPv[0] = dialogPreview();
        preview(dlgPv[0]);
    }

    private String tenthsDp(int tenths) {
        return String.format(Locale.ROOT, "%.1f dp", tenths / 10f);
    }

    // ---- 5. Fonts --------------------------------------------------------------------------------

    private void sectionFonts() {
        heading(getString(R.string.doksho_ui_sec_fonts), false);

        sub(getString(R.string.doksho_ui_sub_ui_text));
        final TextView[] uiPv = new TextView[1];
        fontRow(DokshoUi.FONT_FAMILY, DokshoUi.FONT_WEIGHT);
        slider(R.string.doksho_ui_font_weight, DokshoUi.FONT_WEIGHT, 100, 900, 100, String::valueOf,
                () -> styleUiSample(uiPv[0]));
        slider(R.string.doksho_ui_font_size, DokshoUi.FONT_SCALE, 70, 160, 5, v -> v + " %",
                () -> styleUiSample(uiPv[0]));
        uiPv[0] = sample();
        styleUiSample(uiPv[0]);
        preview(uiPv[0]);

        sub(getString(R.string.doksho_ui_sub_headings));
        final TextView[] headPv = new TextView[1];
        fontRow(DokshoUi.HEAD_FONT_FAMILY, DokshoUi.HEAD_FONT_WEIGHT);
        slider(R.string.doksho_ui_font_weight, DokshoUi.HEAD_FONT_WEIGHT, 100, 900, 100, String::valueOf,
                () -> styleHeadSample(headPv[0]));
        slider(R.string.doksho_ui_font_head_size, DokshoUi.HEAD_FONT_SIZE, 12, 36, 1, v -> v + " sp",
                () -> styleHeadSample(headPv[0]));
        headPv[0] = sample();
        headPv[0].setText(R.string.doksho_ui_head_sample);
        styleHeadSample(headPv[0]);
        preview(headPv[0]);

        itemRow(IND_L1, getString(R.string.doksho_font_import_row), getString(R.string.doksho_font_import_desc), null,
                v -> importFont(null));
    }

    private TextView sample() {
        TextView tv = new TextView(this);
        tv.setText(R.string.doksho_font_sample);
        tv.setTag(DokshoViews.NO_SKIN);
        return tv;
    }

    private void styleUiSample(TextView tv) {
        if (tv == null) return;
        tv.setTextColor(ink());
        tv.setTypeface(DokshoFonts.at(this, DokshoUi.s(DokshoUi.FONT_FAMILY), DokshoUi.i(DokshoUi.FONT_WEIGHT), false));
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f * DokshoUi.i(DokshoUi.FONT_SCALE) / 100f);
    }

    private void styleHeadSample(TextView tv) {
        if (tv == null) return;
        tv.setTextColor(ink());
        tv.setTypeface(DokshoFonts.at(this, DokshoUi.s(DokshoUi.HEAD_FONT_FAMILY), DokshoUi.i(DokshoUi.HEAD_FONT_WEIGHT), false));
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, DokshoUi.i(DokshoUi.HEAD_FONT_SIZE));
    }

    // ---- 6. Reading -------------------------------------------------------------------------------

    private void sectionReading() {
        heading(getString(R.string.doksho_ui_sec_reading), false);
        sub(getString(R.string.doksho_ui_sub_night));
        colorRow(R.string.doksho_ui_c_night_text, DokshoUi.C_NIGHT_TEXT, false);
        colorRow(R.string.doksho_ui_c_night_bg, DokshoUi.C_NIGHT_BG, false);
        final ImageView[] pv = new ImageView[1];
        slider(R.string.doksho_ui_night_strength, DokshoUi.NIGHT_STRENGTH, 0, 100, 5,
                v -> v == 0 ? getString(R.string.doksho_ui_night_off) : v + " %", () -> nightSample(pv[0]));
        pv[0] = new ImageView(this);
        pv[0].setTag(DokshoViews.NO_SKIN);
        pv[0].setAdjustViewBounds(true);
        nightSample(pv[0]);
        preview(pv[0]);
    }

    /** A day page — a white-to-black ramp and black print on white — through the night duotone. */
    private void nightSample(ImageView iv) {
        if (iv == null) return;
        int w = dp(280), h = dp(64);
        android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas c = new android.graphics.Canvas(bmp);
        c.drawColor(Color.WHITE);
        android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        p.setShader(new android.graphics.LinearGradient(0, 0, w, 0, Color.WHITE, Color.BLACK,
                android.graphics.Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h / 3f, p);
        p.setShader(null);
        p.setColor(Color.BLACK);
        p.setTextSize(h / 2.6f);
        c.drawText(getString(R.string.doksho_ui_night_sample), dp(8), h - dp(10), p);
        int[] px = new int[w * h];
        bmp.getPixels(px, 0, w, 0, 0, w, h);
        if (DokshoUi.i(DokshoUi.NIGHT_STRENGTH) > 0) DokshoNight.apply(px);
        else for (int i = 0; i < px.length; i++) px[i] = 0xFF000000 | ~px[i]; // Librera's plain inversion
        bmp.setPixels(px, 0, w, 0, 0, w, h);
        iv.setImageBitmap(bmp);
        GradientDrawable frame = new GradientDrawable();
        frame.setStroke(Math.max(1, dp(1)), ink2());
        iv.setBackground(frame);
        iv.setPadding(dp(1), dp(1), dp(1), dp(1));
    }

    // ---- 7. About -----------------------------------------------------------------------------------

    private void sectionAbout() {
        heading(getString(R.string.doksho_ui_sec_about), false);
        String version = "";
        try {
            version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {
            // shown without a version
        }
        itemRow(IND_L1, Doksho.NAME + " " + version, Doksho.GITHUB, null,
                v -> startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(Doksho.GITHUB))));
    }

    // ---------------------------------------------------------------------------------------------
    // row builders (kxkb layout numbers)
    // ---------------------------------------------------------------------------------------------

    private TextView body(CharSequence s, float sizeSp, int color, boolean heading, boolean bold) {
        TextView tv = new TextView(this);
        tv.setText(s);
        tv.setTextColor(color);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
        if (heading) {
            tv.setTypeface(DokshoFonts.at(this, DokshoUi.s(DokshoUi.HEAD_FONT_FAMILY),
                    bold ? DokshoUi.i(DokshoUi.HEAD_FONT_WEIGHT) : 400, false));
        } else {
            tv.setTypeface(DokshoFonts.at(this, DokshoUi.s(DokshoUi.FONT_FAMILY),
                    bold ? Math.max(700, DokshoUi.i(DokshoUi.FONT_WEIGHT)) : DokshoUi.i(DokshoUi.FONT_WEIGHT), false));
        }
        tv.setTag(DokshoViews.NO_SKIN);
        return tv;
    }

    /** Top-level heading: a 1px rule (not above the first), then the bold title underlined as wide as its text. */
    private void heading(String title, boolean first) {
        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setPadding(0, first ? dp(6) : dp(10), 0, dp(2));
        if (!first) {
            View rule = new View(this);
            rule.setBackgroundColor(ink());
            outer.addView(rule, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));
        }
        outer.addView(underlined(title, DokshoUi.i(DokshoUi.HEAD_FONT_SIZE), 2.5f, IND_HEAD, 8));
        page.addView(outer);
    }

    private void sub(String title) {
        View v = underlined(title, Math.max(10, DokshoUi.i(DokshoUi.HEAD_FONT_SIZE) - 3), 1.5f, IND_SUB, 10);
        page.addView(v);
    }

    private View underlined(String title, float sizeSp, float lineDp, int indentDp, int topDp) {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setPadding(dp(indentDp), dp(topDp), dp(16), dp(2));
        // wrap_content inner box: the underline (match_parent inside it) is exactly as wide as the text
        LinearLayout inner = new LinearLayout(this);
        inner.setOrientation(LinearLayout.VERTICAL);
        inner.addView(body(title, sizeSp, ink(), true, true));
        View line = new View(this);
        line.setBackgroundColor(ink());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                Math.max(1, dp(lineDp)));
        lp.topMargin = dp(2);
        inner.addView(line, lp);
        wrap.addView(inner, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return wrap;
    }

    private View itemRow(int indentDp, CharSequence title, @Nullable CharSequence summary, @Nullable View widget,
                         @Nullable View.OnClickListener onClick) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(indentDp), dp(5), dp(16), dp(5));
        row.setTag(DokshoViews.NO_SKIN);
        if (onClick != null) {
            row.setClickable(true);
            row.setOnClickListener(onClick);
            row.setBackground(ripple());
        }
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(body(title, 16, ink(), false, false));
        if (summary != null && summary.length() > 0) texts.addView(body(summary, 13, ink2(), false, false));
        row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (widget != null) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.leftMargin = dp(10);
            row.addView(widget, lp);
        }
        page.addView(row);
        return row;
    }

    private android.graphics.drawable.RippleDrawable ripple() {
        return new android.graphics.drawable.RippleDrawable(
                ColorStateList.valueOf((ink() & 0x00FFFFFF) | 0x33000000), null, new ColorDrawable(Color.WHITE));
    }

    interface OnToggle {
        void on(boolean value);
    }

    private Switch toggle(boolean value, OnToggle onToggle) {
        Switch s = new Switch(this);
        s.setChecked(value);
        s.setTag(DokshoViews.NO_SKIN);
        int[][] states = {{android.R.attr.state_checked}, {}};
        s.setThumbTintList(new ColorStateList(states, new int[]{accent(), ink2()}));
        s.setTrackTintList(new ColorStateList(states, new int[]{(accent() & 0x00FFFFFF) | 0x88000000,
                (ink2() & 0x00FFFFFF) | 0x44000000}));
        s.setOnCheckedChangeListener((b, checked) -> onToggle.on(checked));
        return s;
    }

    /** A colour row: the value as #AARRGGBB, a bordered swatch of it; tap opens the picker. */
    private void colorRow(int titleRes, final String key, final boolean touchesLibrera) {
        int c = DokshoUi.i(key);
        View swatch = new View(this);
        GradientDrawable g = new GradientDrawable();
        g.setColor(c);
        g.setStroke(Math.max(1, dp(1.5f)), ink());
        g.setCornerRadius(dp(4));
        swatch.setBackground(g);
        swatch.setTag(DokshoViews.NO_SKIN);
        swatch.setLayoutParams(new LinearLayout.LayoutParams(dp(38), dp(38)));
        LinearLayout holder = new LinearLayout(this);
        holder.addView(swatch, new LinearLayout.LayoutParams(dp(38), dp(38)));
        holder.setTag(DokshoViews.NO_SKIN);
        final String title = getString(titleRes);
        itemRow(IND_L2, title, String.format(Locale.ROOT, "#%08X", c), holder,
                v -> ColorPickerDialog.show(this, title, DokshoUi.i(key), color -> {
                    if (color == DokshoUi.i(key)) return;
                    DokshoUi.set(key, color);
                    if (touchesLibrera) {
                        DokshoSkin.applyToLibrera();
                        libreraDirty = true;
                    }
                    rebuild();
                }));
    }

    interface Label {
        String of(int value);
    }

    /** A slider row: title with the value on the right, the bar beneath; live while dragging. */
    private void slider(int titleRes, final String key, final int min, final int max, final int step,
                        final Label label, @Nullable final Runnable live) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(IND_L2), dp(4), dp(16), dp(4));
        row.setTag(DokshoViews.NO_SKIN);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.addView(body(getString(titleRes), 16, ink(), false, false),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        final TextView value = body(label.of(DokshoUi.i(key)), 15, ink(), false, false);
        value.setGravity(Gravity.END);
        top.addView(value, new LinearLayout.LayoutParams(dp(72), ViewGroup.LayoutParams.WRAP_CONTENT));
        row.addView(top);

        SeekBar bar = new SeekBar(this);
        bar.setTag(DokshoViews.NO_SKIN);
        bar.setMax((max - min) / step);
        bar.setProgress((DokshoUi.i(key) - min) / step);
        ColorPickerDialog.tint(bar, accent());
        bar.setPadding(dp(8), dp(2), dp(8), dp(2));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser) return;
                int v = min + progress * step;
                DokshoUi.set(key, v);
                value.setText(label.of(v));
                if (live != null) live.run();
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                rebuild(); // the page itself repaints in the new value
            }
        });
        row.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        page.addView(row);
    }

    /** The font row: the chosen font's name, set in that very font. */
    private void fontRow(final String familyKey, final String weightKey) {
        String id = DokshoUi.s(familyKey);
        TextView valueView = body(DokshoFonts.displayName(this, id), 17, ink(), false, false);
        valueView.setTypeface(DokshoFonts.at(this, id, DokshoUi.i(weightKey), false));
        itemRow(IND_L2, getString(R.string.doksho_ui_font_family), null, valueView,
                v -> FontPickerDialog.show(this, getString(R.string.doksho_ui_font_family), DokshoUi.s(familyKey),
                        DokshoUi.i(weightKey), chosen -> {
                            DokshoUi.set(familyKey, chosen);
                            rebuild();
                        }, () -> importFont(familyKey)));
    }

    /** A preview block, indented like the rows it previews. */
    private void preview(View content) {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setPadding(dp(IND_L2), dp(4), dp(16), dp(6));
        wrap.setTag(DokshoViews.NO_SKIN);
        wrap.addView(body(getString(R.string.doksho_ui_preview), 12, ink2(), false, false));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(2);
        wrap.addView(content, lp);
        page.addView(wrap);
    }

    // ---- previews ---------------------------------------------------------------------------------

    private View generalPreview() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(12), dp(8), dp(12), dp(8));
        box.setBackground(boxShape(bg(), DokshoUi.i(DokshoUi.C_BOX_BORDER), DokshoUi.BOX_BORDER_W, DokshoUi.BOX_RADIUS));
        box.setTag(DokshoViews.NO_SKIN);
        box.addView(body(getString(R.string.doksho_ui_pv_title), 16, ink(), false, true));
        box.addView(body(getString(R.string.doksho_ui_pv_secondary), 13, ink2(), false, false));
        LinearLayout line = new LinearLayout(this);
        line.setGravity(Gravity.CENTER_VERTICAL);
        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.glyphicons_589_book_open_icon);
        icon.setColorFilter(accent());
        line.addView(icon, new LinearLayout.LayoutParams(dp(24), dp(24)));
        TextView link = body(getString(R.string.doksho_ui_pv_link), 14, accent(), false, false);
        link.setPadding(dp(8), 0, 0, 0);
        line.addView(link);
        box.addView(line);
        return box;
    }

    private View barPreview() {
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(12), dp(6), dp(12), dp(6));
        int fg = DokshoUi.i(DokshoUi.C_BAR_FG);
        bar.setBackground(boxShape(DokshoUi.i(DokshoUi.C_BAR_BG), fg, DokshoUi.BOX_BORDER_W, DokshoUi.BOX_RADIUS));
        bar.setTag(DokshoViews.NO_SKIN);
        int[] icons = {R.drawable.glyphicons_600_menu, R.drawable.glyphicons_589_book_open_icon};
        for (int res : icons) {
            ImageView iv = new ImageView(this);
            iv.setImageResource(res);
            iv.setColorFilter(fg);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(24), dp(24));
            lp.rightMargin = dp(12);
            bar.addView(iv, lp);
        }
        bar.addView(body(getString(R.string.doksho_ui_pv_bar), 15, fg, false, false));
        return bar;
    }

    private View dialogPreview() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(12), dp(16), dp(12));
        box.setTag(DokshoViews.NO_SKIN);
        refreshDialog(box);
        return box;
    }

    private void refreshDialog(View v) {
        if (!(v instanceof LinearLayout)) return;
        LinearLayout box = (LinearLayout) v;
        box.removeAllViews();
        box.setBackground(boxShape(DokshoUi.i(DokshoUi.C_DLG_BG), DokshoUi.i(DokshoUi.C_DLG_BORDER),
                DokshoUi.DLG_BORDER_W, DokshoUi.DLG_RADIUS));
        box.addView(body(getString(R.string.doksho_ui_pv_dialog_title), 17, DokshoUi.i(DokshoUi.C_DLG_TEXT), false, true));
        box.addView(body(getString(R.string.doksho_ui_pv_dialog_body), 14, DokshoUi.i(DokshoUi.C_DLG_TEXT), false, false));
        LinearLayout buttons = DokshoViews.buttonRow(this);
        buttons.setPadding(0, dp(8), 0, 0);
        buttons.addView(DokshoViews.pill(this, getString(R.string.doksho_eim_ok), null));
        box.addView(buttons);
    }

    private View boxPreview() {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setTag(DokshoViews.NO_SKIN);
        refreshBox(row);
        return row;
    }

    private void refreshBox(View v) {
        if (!(v instanceof LinearLayout)) return;
        LinearLayout row = (LinearLayout) v;
        row.removeAllViews();
        TextView box = body(getString(R.string.doksho_ui_pv_box), 15, ink(), false, false);
        box.setPadding(dp(14), dp(10), dp(14), dp(10));
        box.setBackground(boxShape(bg(), DokshoUi.i(DokshoUi.C_BOX_BORDER), DokshoUi.BOX_BORDER_W, DokshoUi.BOX_RADIUS));
        row.addView(box);
        TextView pill = body(getString(R.string.doksho_ui_pv_pill), 15, ink(), false, false);
        pill.setPadding(dp(18), dp(6), dp(18), dp(6));
        GradientDrawable p = new GradientDrawable();
        p.setColor(bg());
        int w = DokshoUi.borderPx(this, DokshoUi.BOX_BORDER_W);
        if (w > 0) p.setStroke(w, DokshoUi.i(DokshoUi.C_BOX_BORDER));
        p.setCornerRadius(dp(50));
        pill.setBackground(p);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(12);
        row.addView(pill, lp);
    }

    private GradientDrawable boxShape(int fill, int stroke, String widthKey, String radiusKey) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        int w = DokshoUi.borderPx(this, widthKey);
        if (w > 0) g.setStroke(w, stroke);
        g.setCornerRadius(DokshoUi.dp(this, DokshoUi.i(radiusKey)));
        return g;
    }

    // ---------------------------------------------------------------------------------------------
    // pickers (SAF) — the Export / Import panel's host side, and font import
    // ---------------------------------------------------------------------------------------------

    @Override
    public void pickExportDir(@Nullable Uri initial) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        if (initial != null) i.putExtra(DocumentsContract.EXTRA_INITIAL_URI, initial);
        startActivityForResult(i, REQ_DIR);
    }

    @Override
    public void pickImportFile(@Nullable Uri initial) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/zip", "application/octet-stream", "*/*"});
        if (initial != null) i.putExtra(DocumentsContract.EXTRA_INITIAL_URI, initial);
        startActivityForResult(i, REQ_IMPORT);
    }

    @Override
    public void onChainFinished() {
        finish();
    }

    private void importFont(@Nullable String familyKey) {
        pendingFontKey = familyKey;
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        startActivityForResult(i, REQ_FONT);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        Uri uri = (resultCode == RESULT_OK && data != null) ? data.getData() : null;
        if (requestCode == REQ_DIR) {
            panel.onDirPicked(uri);
            rebuild();
        } else if (requestCode == REQ_IMPORT) {
            panel.onImportFilePicked(uri);
        } else if (requestCode == REQ_FONT && uri != null) {
            try {
                String id = DokshoFonts.importFont(this, uri);
                if (pendingFontKey != null) DokshoUi.set(pendingFontKey, id);
                DokshoViews.toast(this, getString(R.string.doksho_font_imported, DokshoFonts.displayName(this, id)));
                rebuild();
            } catch (Exception e) {
                DokshoViews.showInfo(this, getString(R.string.doksho_font_import_fail_title),
                        String.valueOf(e.getMessage()), true, null);
            }
        }
    }
}
