package shiroikuma.doksho;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import android.app.AlertDialog;
import androidx.documentfile.provider.DocumentFile;

import com.foobnix.pdf.info.R;
import shiroikuma.doksho.backup.ShiroikumaExport;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.LinkedHashSet;
import java.util.Set;

import static shiroikuma.doksho.DokshoViews.checkbox;
import static shiroikuma.doksho.DokshoViews.divider;
import static shiroikuma.doksho.DokshoViews.pill;
import static shiroikuma.doksho.DokshoViews.text;

/**
 * The Export / Import window — one black-yellow box that backs up and restores the app settings,
 * in the family's shared visual format (ported from termux-api's {@code ExportImportPanel}): a
 * centred title, a dim description, a bordered tappable directory box (red when unset, yellow once
 * set), the last-export line, a divider, 全選択 + the category checkboxes, a divider, and the pill
 * row — Cancel alone on the left, Import + Export on the right.
 *
 * <p>All work goes through {@link ShiroikumaExport}, the same core the automation doors use. A
 * successful export ends in a bordered info dialog whose OK closes the whole chain (info dialog →
 * this panel → the UI page, via {@link Host#onChainFinished()}); failures leave the panel open. An
 * import ends in a result dialog with 「Later」 (closes the chain) and 「Restart now」.
 *
 * <p>The SAF pickers are registered by the hosting fragment, which forwards their results back
 * through {@link #onDirPicked} / {@link #onImportFilePicked}.
 */
public class ExportImportPanel {

    private static final int WARN = shiroikuma.doksho.DokshoUi.WARN;

    private static int ink() {
        return DokshoViews.ink();
    }

    private static int dim() {
        return (DokshoViews.ink() & 0x00FFFFFF) | 0xC8000000;
    }


    /** What the host must provide: the two SAF pickers and the "close everything" hook. */
    public interface Host {
        void pickExportDir(@Nullable Uri initial);

        void pickImportFile(@Nullable Uri initial);

        void onChainFinished();
    }

    private final Activity mActivity;
    private final Host mHost;

    // Seeded from the categories' own defaultSelected flag — the same answer LIST_CATEGORIES gives
    // the automation picker, so the in-app sheet and 保存復元 open on an identical selection.
    private final Set<ShiroikumaExport.Cat> mSelected = new LinkedHashSet<>(ShiroikumaExport.Cat.defaults());

    @Nullable
    private AlertDialog mDialog;
    @Nullable
    private LinearLayout mBox;
    @Nullable
    private TextView mStatusLine;

    public ExportImportPanel(@NonNull Activity activity, @NonNull Host host) {
        mActivity = activity;
        mHost = host;
    }

    public boolean isShowing() {
        return mDialog != null && mDialog.isShowing();
    }

    public void show() {
        mBox = new LinearLayout(mActivity);
        mBox.setOrientation(LinearLayout.VERTICAL);
        mBox.setPadding(dp(20), dp(16), dp(20), dp(20));
        mBox.setBackground(DokshoViews.panelBackground(mActivity));

        ScrollView scroll = new ScrollView(mActivity);
        int m = dp(10);
        scroll.setPadding(m, m, m, m);
        scroll.setClipToPadding(false);
        scroll.addView(mBox, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        mDialog = new AlertDialog.Builder(mActivity).setView(scroll).create();
        mDialog.setOnDismissListener(d -> {
            mBox = null;
            mStatusLine = null;
            mDialog = null;
        });
        mDialog.show();
        DokshoViews.transparentWindow(mDialog);
        rebuild();
    }

    public void dismiss() {
        AlertDialog dialog = mDialog;
        mDialog = null;
        if (dialog != null) {
            try {
                dialog.dismiss();
            } catch (Exception ignored) {
                // the activity may already be finishing
            }
        }
    }

    // ---- content -------------------------------------------------------------------------------

    public void rebuild() {
        LinearLayout box = mBox;
        if (box == null) return;
        box.removeAllViews();

        TextView title = text(mActivity, mActivity.getString(R.string.doksho_eim_title), 18, ink(), true);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, dp(2), 0, dp(6));
        box.addView(title);

        TextView desc = text(mActivity, mActivity.getString(R.string.doksho_eim_desc), 13, dim(), false);
        desc.setPadding(0, 0, 0, dp(10));
        box.addView(desc);

        box.addView(dirBox());
        box.addView(statusLine());

        box.addView(divider(mActivity, 0));

        final CheckBox selectAll = checkbox(mActivity, mActivity.getString(R.string.doksho_eim_select_all), true, 0);
        selectAll.setChecked(mSelected.size() == ShiroikumaExport.Cat.values().length);
        selectAll.setOnClickListener(v -> {
            if (selectAll.isChecked()) mSelected.addAll(ShiroikumaExport.Cat.all());
            else mSelected.clear();
            rebuild();
        });
        box.addView(selectAll);

        for (ShiroikumaExport.Cat cat : ShiroikumaExport.Cat.values()) {
            box.addView(categoryRow(cat));
        }

        box.addView(divider(mActivity, 8));
        box.addView(buttonRow());
    }

    /** The directory box: bordered, clearly tappable — small caption over the value, red when unset. */
    private View dirBox() {
        LinearLayout box = new LinearLayout(mActivity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setClickable(true);
        box.setPadding(dp(12), dp(10), dp(12), dp(10));
        String label = ShiroikumaExport.dirLabel(mActivity);
        // Red until a folder is chosen: a backup with nowhere to go says so loudly.
        GradientDrawable bg = DokshoViews.boxBackground(mActivity, label == null ? WARN : DokshoViews.border());
        box.setBackground(bg);
        box.setOnClickListener(v -> mHost.pickExportDir(ShiroikumaExport.exportDirUri(mActivity)));

        box.addView(text(mActivity, mActivity.getString(R.string.doksho_eim_dir_caption), 12, dim(), false));
        box.addView(text(mActivity, label != null ? label : mActivity.getString(R.string.doksho_eim_dir_unset_long),
                15, label != null ? ink() : WARN, true));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        lp.bottomMargin = dp(6);
        box.setLayoutParams(lp);
        return box;
    }

    /** The last-export line — queried off the main thread when the panel opens or the directory changes. */
    private View statusLine() {
        TextView tv = text(mActivity, "", 14, ink(), false);
        tv.setPadding(dp(2), 0, 0, dp(8));
        mStatusLine = tv;
        if (ShiroikumaExport.exportDir(mActivity) == null) {
            tv.setText(R.string.doksho_eim_warn_nodir);
            tv.setTextColor(WARN);
            return tv;
        }
        final Context app = mActivity.getApplicationContext();
        new Thread(() -> {
            DocumentFile newest = ShiroikumaExport.newestExport(app);
            final String msg;
            final boolean warn;
            if (newest == null) {
                msg = app.getString(R.string.doksho_eim_warn_none);
                warn = true;
            } else {
                msg = app.getString(R.string.doksho_eim_last_line,
                        formatTs(newest.lastModified()) + " · " + ShiroikumaExport.humanSize(newest.length()));
                warn = false;
            }
            ui(() -> {
                if (mStatusLine != tv) return; // the panel was rebuilt meanwhile
                tv.setText(msg);
                tv.setTextColor(warn ? WARN : ink());
                tv.setAlpha(warn ? 1f : 0.8f);
            });
        }, "shiroikuma-eim-status").start();
        return tv;
    }

    private String formatTs(long ts) {
        return DateFormat.getDateFormat(mActivity).format(ts) + " " + DateFormat.getTimeFormat(mActivity).format(ts);
    }

    private View categoryRow(final ShiroikumaExport.Cat cat) {
        // Sub-options sit one step in, under their parent, and follow the parent's toggle.
        boolean isChild = cat.parentId != null;
        CheckBox cb = checkbox(mActivity, mActivity.getString(cat.labelRes), false, isChild ? dp(28) : 0);
        boolean parentOn = !isChild || mSelected.contains(ShiroikumaExport.Cat.byId(cat.parentId));
        cb.setChecked(mSelected.contains(cat) && parentOn);
        cb.setEnabled(parentOn);
        cb.setAlpha(parentOn ? 1f : 0.5f);
        cb.setOnClickListener(v -> {
            boolean checked = cb.isChecked();
            if (checked) mSelected.add(cat);
            else mSelected.remove(cat);
            boolean hasChildren = false;
            for (ShiroikumaExport.Cat other : ShiroikumaExport.Cat.values()) {
                if (cat.id.equals(other.parentId)) {
                    hasChildren = true;
                    if (checked) mSelected.add(other);
                    else mSelected.remove(other);
                }
            }
            if (hasChildren) rebuild();
        });
        return cb;
    }

    /** The button bar: Cancel alone on the left, Import + Export grouped on the right. */
    private View buttonRow() {
        LinearLayout row = new LinearLayout(mActivity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(14), 0, 0);

        row.addView(pill(mActivity, mActivity.getString(R.string.doksho_eim_cancel), v -> dismiss()));
        View spacer = new View(mActivity);
        row.addView(spacer, new LinearLayout.LayoutParams(0, 0, 1f));
        Button importButton = pill(mActivity, mActivity.getString(R.string.doksho_eim_import), v -> onImportClicked());
        ((LinearLayout.LayoutParams) importButton.getLayoutParams()).rightMargin = dp(8);
        row.addView(importButton);
        row.addView(pill(mActivity, mActivity.getString(R.string.doksho_eim_export), v -> onExportClicked()));
        return row;
    }

    // ---- export --------------------------------------------------------------------------------

    private void onExportClicked() {
        if (mSelected.isEmpty()) {
            showInfo(mActivity.getString(R.string.doksho_eim_export_fail_title),
                    mActivity.getString(R.string.doksho_eim_none_selected), false);
            return;
        }
        final DocumentFile dir = ShiroikumaExport.exportDir(mActivity);
        if (dir == null) {
            mHost.pickExportDir(null); // no folder yet: ask for one instead of failing
            return;
        }
        final Set<ShiroikumaExport.Cat> cats = new LinkedHashSet<>(mSelected);
        final Context app = mActivity.getApplicationContext();
        new Thread(() -> {
            final ShiroikumaExport.Written written;
            try {
                written = ShiroikumaExport.exportToDirectory(app, dir, cats, null, null);
            } catch (Throwable t) {
                final String message = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
                ui(() -> showInfo(
                        mActivity.getString(R.string.doksho_eim_export_fail_title),
                        mActivity.getString(R.string.doksho_eim_export_fail, message), false));
                return;
            }
            ui(() -> showInfo(
                    mActivity.getString(R.string.doksho_eim_export_done_title),
                    mActivity.getString(R.string.doksho_eim_export_done_body,
                            written.path, ShiroikumaExport.humanSize(written.bytes), cats.size()),
                    true));
        }, "shiroikuma-export").start();
    }

    // ---- import --------------------------------------------------------------------------------

    private void onImportClicked() {
        if (mSelected.isEmpty()) {
            showInfo(mActivity.getString(R.string.doksho_eim_import_fail_title),
                    mActivity.getString(R.string.doksho_eim_none_selected), false);
            return;
        }
        mHost.pickImportFile(ShiroikumaExport.exportDirUri(mActivity));
    }

    /** Called by the host after the SAF file picker returns. */
    public void onImportFilePicked(@Nullable Uri uri) {
        if (uri != null) runImport(uri);
    }

    private void runImport(final Uri uri) {
        final Set<ShiroikumaExport.Cat> cats = new LinkedHashSet<>(mSelected);
        final Context app = mActivity.getApplicationContext();
        new Thread(() -> {
            String summary;
            File tmp = null;
            try {
                // Spool to a cache file: ZipFile needs random access, and nothing is applied until
                // the whole archive has been read and recognised as ours.
                tmp = File.createTempFile("shiroikuma-import", ".zip", app.getCacheDir());
                InputStream in = app.getContentResolver().openInputStream(uri);
                if (in == null) throw new Exception("no input stream");
                try {
                    OutputStream out = new FileOutputStream(tmp);
                    try {
                        byte[] buf = new byte[64 * 1024];
                        int n;
                        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    } finally {
                        out.close();
                    }
                } finally {
                    in.close();
                }
                summary = ShiroikumaExport.importZip(app, tmp, cats);
            } catch (Throwable t) {
                final String message = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
                ui(() -> showInfo(
                        mActivity.getString(R.string.doksho_eim_import_fail_title),
                        mActivity.getString(R.string.doksho_eim_import_fail, message), false));
                return;
            } finally {
                if (tmp != null) //noinspection ResultOfMethodCallIgnored
                    tmp.delete();
            }
            final String body = summary;
            ui(() -> showImportResult(body));
        }, "shiroikuma-import").start();
    }

    /**
     * The import result: a persistent bordered dialog with an explicit restart button — never a
     * toast. 「Later」 closes the whole chain; 「Restart now」 relaunches the process so every
     * reader re-reads the restored preferences from disk.
     */
    private void showImportResult(String summary) {
        String body = summary + "\n\n" + mActivity.getString(R.string.doksho_eim_restart_hint);
        LinearLayout box = DokshoViews.infoBox(mActivity, mActivity.getString(R.string.doksho_eim_import_done_title), body);
        final AlertDialog dialog = DokshoViews.boxDialog(mActivity, box, false);

        LinearLayout buttons = DokshoViews.buttonRow(mActivity);
        Button later = pill(mActivity, mActivity.getString(R.string.doksho_eim_restart_later), v -> {
            dialog.dismiss();
            dismiss();
            mHost.onChainFinished();
        });
        ((LinearLayout.LayoutParams) later.getLayoutParams()).rightMargin = dp(10);
        buttons.addView(later);
        buttons.addView(pill(mActivity, mActivity.getString(R.string.doksho_eim_restart_now), v -> restartApp()));
        box.addView(buttons);
        dialog.show();
        DokshoViews.transparentWindow(dialog);
    }

    /**
     * {@code makeRestartActivityTask} on the launcher component, then a hard exit. The launcher
     * alias may be disabled (the main page offers that), in which case the main activity itself is
     * the target.
     */
    private void restartApp() {
        Context app = mActivity.getApplicationContext();
        Intent launch = app.getPackageManager().getLaunchIntentForPackage(app.getPackageName());
        ComponentName component = launch != null ? launch.getComponent() : null;
        if (component == null) component = new ComponentName(app, com.foobnix.ui2.MainTabs2.class);
        app.startActivity(Intent.makeRestartActivityTask(component));
        Runtime.getRuntime().exit(0);
    }

    // ---- the export directory ------------------------------------------------------------------

    /** Called by the host after the SAF folder picker returns. */
    public void onDirPicked(@Nullable Uri uri) {
        if (uri == null) return;
        ShiroikumaExport.setExportDirUri(mActivity, uri);
        rebuild();
    }

    // ---- info dialogs --------------------------------------------------------------------------

    /**
     * A bordered black-yellow info dialog with a single OK. When {@code closeChain} is set (a
     * successful export), acknowledging it closes this panel and the UI page too; failures only
     * dismiss the dialog, leaving the panel open to retry.
     */
    private void showInfo(String title, String body, final boolean closeChain) {
        DokshoViews.showInfo(mActivity, title, body, !closeChain, () -> {
            if (closeChain) {
                dismiss();
                mHost.onChainFinished();
            }
        });
    }

    /** Runs on the main thread unless the page is already gone — a dialog on a dead window would throw. */
    private void ui(@NonNull Runnable r) {
        mActivity.runOnUiThread(() -> {
            if (mActivity.isFinishing() || mActivity.isDestroyed()) return;
            r.run();
        });
    }

    private int dp(float v) {
        return DokshoViews.dp(mActivity, v);
    }
}
