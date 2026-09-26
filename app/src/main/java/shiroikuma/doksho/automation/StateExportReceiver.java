package shiroikuma.doksho.automation;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Environment;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.documentfile.provider.DocumentFile;

import shiroikuma.doksho.backup.ShiroikumaExport;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The sister-app <b>state-export automation contract</b> (保存復元, v2 §1) — the wire shape every
 * 白い熊 app exposes so one 自由作業盤 task can back them all up headlessly. Ported from
 * raikidoban's {@code StateExportReceiver}.
 *
 * <ul>
 * <li>{@code shiroikuma.doksho.action.EXPORT_STATE}: run the category ZIP ({@link ShiroikumaExport})
 * with no UI. Extras (all String): {@code token} (optional — checked only while 「Use authorization
 * token?」 is on, ignored otherwise), {@code path} (optional absolute directory), {@code items}
 * (optional comma list of category ids; absent = the default set), {@code progress_action}
 * (optional), plus the reply trio {@code reply_action} / {@code reply_package} / {@code reply_id}.</li>
 * <li>{@code shiroikuma.doksho.action.LIST_CATEGORIES}: gated the same way, instant. One
 * {@code id<TAB>label<TAB>parent<TAB>on|off} line per category.</li>
 * <li>{@code shiroikuma.doksho.action.CANCEL_EXPORT}: stop a running export. Fire-and-forget — it never
 * replies, and is a silent no-op when nothing is running. The export unwinds at the next entry
 * boundary, deletes the half-written archive, and answers its ORIGINAL request with
 * {@code ERROR:cancelled}.</li>
 * </ul>
 *
 * <h3>Why the export runs inside {@code goAsync()} and not in a foreground service</h3>
 *
 * <p>The contract allows {@code goAsync()} only for an export that <b>cannot</b> exceed a few
 * seconds. This app's export is a handful of JSON files (settings, bookmarks, progress, …) plus the
 * few imported UI fonts — typically well under ten MB, written in a second or two. Running it here
 * rather than in a service also means the cold, unattended batch — a background start on API 31+ — can never be
 * refused with {@code ForegroundServiceStartNotAllowedException}: there is no service to start. The
 * data door ({@link AutomationProvider}) does use {@link AutomationDataService}, because there the
 * bytes go into a caller-supplied descriptor that may be a pipe.
 *
 * <h3>Storage</h3>
 *
 * <p>This app <b>declares</b> {@code MANAGE_EXTERNAL_STORAGE} (upstream, to open books anywhere), so
 * per §1 an absolute {@code path} is honoured only when All-files access is actually held
 * ({@code Environment.isExternalStorageManager()}), and refused with exactly
 * {@code ERROR:no-storage-access} otherwise — the keyed line that earns 白い熊 a
 * 「全ファイルアクセスを許可」 button on the failed row. Falling back to the SAF directory would
 * write the archive outside the batch's set and look successful. Without a {@code path}, the
 * configured SAF directory is used, and with none: {@code ERROR:no-directory}.
 *
 * <p>Reply: a FRESH broadcast to {@code reply_package} with {@code reply_id} echoed verbatim and
 * {@code result} = {@code OK:<path>|<bytes>|<human size>|<n> categories}, {@code OK:} + the
 * category lines, or {@code ERROR:<reason>}. Exactly one terminal reply per request. No binders,
 * no reliance on the ordered-broadcast result (EMUI severs both between third-party apps);
 * {@code FLAG_INCLUDE_STOPPED_PACKAGES} so a stopped caller still hears us.
 *
 * <p>Exported with NO {@code android:permission}: in v2 this receiver is deliberately the
 * unauthenticated half of the surface — it only ever writes where it was told to and reports what
 * it did. Everything that moves data through a caller-supplied descriptor lives behind
 * {@link AutomationProvider}, which knows who is calling.
 */
public class StateExportReceiver extends BroadcastReceiver {

    private static final String TAG = "ShiroikumaStateExport";

    // <applicationId>.action.* — the fork's id, fixed (shiroikuma/fork.gradle).
    private static final String PKG = "shiroikuma.doksho";
    public static final String ACTION_EXPORT_STATE = PKG + ".action.EXPORT_STATE";
    public static final String ACTION_LIST_CATEGORIES = PKG + ".action.LIST_CATEGORIES";
    public static final String ACTION_CANCEL_EXPORT = PKG + ".action.CANCEL_EXPORT";

    // Contract extras — bare names, shared verbatim by every sister app.
    static final String EXTRA_TOKEN = "token";
    static final String EXTRA_PATH = "path";
    static final String EXTRA_ITEMS = "items";
    static final String EXTRA_PROGRESS_ACTION = "progress_action";
    static final String EXTRA_REPLY_ACTION = "reply_action";
    static final String EXTRA_REPLY_PACKAGE = "reply_package";
    static final String EXTRA_REPLY_ID = "reply_id";
    static final String EXTRA_RESULT = "result";

    /**
     * The exports currently writing, so a CANCEL_EXPORT arriving on a fresh receiver instance can
     * reach them. Static because a receiver is rebuilt per delivery; empties itself in the export
     * thread's finally, so "nothing is running" is the normal state. Never persisted.
     */
    private static final List<Run> sRunning = new CopyOnWriteArrayList<>();

    /** One in-flight export: the request it answers, and the flag that stops it. */
    private static final class Run {
        final String replyId;
        final AtomicBoolean cancelled = new AtomicBoolean(false);

        Run(String replyId) {
            this.replyId = replyId;
        }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;
        final Context app = context.getApplicationContext();
        final String action = intent.getAction();
        final String token = intent.getStringExtra(EXTRA_TOKEN);
        final String replyAction = trimmed(intent.getStringExtra(EXTRA_REPLY_ACTION));
        final String replyPackage = trimmed(intent.getStringExtra(EXTRA_REPLY_PACKAGE));
        final String replyId = trimmed(intent.getStringExtra(EXTRA_REPLY_ID));
        final String progressAction = trimmed(intent.getStringExtra(EXTRA_PROGRESS_ACTION));
        final String pathOverride = trimmed(intent.getStringExtra(EXTRA_PATH));
        final String items = trimmed(intent.getStringExtra(EXTRA_ITEMS));

        // Cancel is handled ahead of the replying gate: it never answers anything, and a rejected
        // token is silence too. Safe to send at any time — when nothing matches, nothing happens.
        if (ACTION_CANCEL_EXPORT.equals(action)) {
            if (AutomationAuth.refuse(app, token) == null) {
                for (Run run : sRunning) {
                    if (replyId.isEmpty() || replyId.equals(run.replyId)) run.cancelled.set(true);
                }
            }
            return;
        }
        if (!ACTION_EXPORT_STATE.equals(action) && !ACTION_LIST_CATEGORIES.equals(action)) return;

        if (replyAction.isEmpty() || replyPackage.isEmpty()) {
            // Nobody to answer: do NOT degrade to an implicit broadcast (setPackage(null) reaches
            // no manifest receiver since API 26 anyway).
            Log.w(TAG, "ignoring " + action + " — no reply channel (reply_action / reply_package)");
            return;
        }

        final boolean ordered = isOrderedBroadcast();
        final AtomicBoolean replied = new AtomicBoolean(false);
        final Replier reply = result -> {
            if (!replied.compareAndSet(false, true)) return;
            try {
                Intent out = new Intent(replyAction);
                out.setPackage(replyPackage);
                out.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
                out.putExtra(EXTRA_REPLY_ID, replyId);
                out.putExtra(EXTRA_RESULT, result);
                app.sendBroadcast(out);
                Log.i(TAG, "replied to " + replyPackage + " [" + replyId + "]: " + result);
            } catch (Throwable t) {
                Log.w(TAG, "could not deliver the reply", t);
            }
        };

        // Gate first, in ONE place (contract §2).
        String refusal = AutomationAuth.refuse(app, token);
        if (refusal != null) {
            reply.send(refusal);
            return;
        }

        if (ACTION_LIST_CATEGORIES.equals(action)) {
            reply.send(listCategories(app));
            return;
        }

        final Set<ShiroikumaExport.Cat> cats;
        try {
            cats = resolveItems(items);
        } catch (IllegalArgumentException e) {
            reply.send("ERROR:unknown category in items: " + items);
            return;
        }

        // The shared §3 sender; this door's correlation id is the echoed reply_id.
        final AutomationProgress progress = new AutomationProgress(app, progressAction, replyPackage,
                replyId, new String[]{EXTRA_REPLY_ID}, cats, null);

        final Run run = new Run(replyId);
        sRunning.add(run);
        final PendingResult pending = goAsync();
        new Thread(() -> {
            String result;
            progress.start();
            try {
                result = runExport(app, pathOverride, cats, progress, run);
            } catch (ShiroikumaExport.CancelledException e) {
                result = "ERROR:cancelled";
            } catch (Throwable t) {
                Log.w(TAG, "headless export failed", t);
                result = "ERROR:" + AutomationForeground.oneLine(t);
            } finally {
                progress.stop();
                sRunning.remove(run);
            }
            try {
                if (ordered) {
                    pending.setResultCode(Activity.RESULT_OK);
                    pending.setResultData(result);
                }
            } catch (Throwable ignored) {
                // EMUI severs the ordered result between third-party apps; the broadcast is the reply.
            }
            reply.send(result);
            pending.finish();
        }, "shiroikuma-state-export").start();
    }

    // --- LIST_CATEGORIES ------------------------------------------------------------------------

    /** {@code OK:} + one {@code id<TAB>label<TAB>parent<TAB>on|off} line per category. */
    static String listCategories(@NonNull Context app) {
        StringBuilder sb = new StringBuilder("OK:");
        boolean first = true;
        for (ShiroikumaExport.Cat cat : ShiroikumaExport.Cat.values()) {
            if (!first) sb.append('\n');
            first = false;
            // The parent field stays present but empty for a top-level category, because the
            // "starts ticked" flag after it is positional.
            sb.append(cat.id).append('\t').append(app.getString(cat.labelRes))
                    .append('\t').append(cat.parentId == null ? "" : cat.parentId)
                    .append('\t').append(cat.defaultSelected ? "on" : "off");
        }
        return sb.toString();
    }

    // --- EXPORT_STATE ---------------------------------------------------------------------------

    /**
     * The categories an {@code items} extra asks for; absent/empty = the default set. Package
     * visible because {@link AutomationDataService} resolves the same grammar for the data door.
     *
     * @throws IllegalArgumentException naming the first unknown id
     */
    @NonNull
    static Set<ShiroikumaExport.Cat> resolveItems(@Nullable String items) {
        if (items == null || items.trim().isEmpty()) return ShiroikumaExport.Cat.defaults();
        Set<ShiroikumaExport.Cat> resolved = new LinkedHashSet<>();
        List<String> unknown = new ArrayList<>();
        for (String raw : items.split(",")) {
            String id = raw.trim();
            if (id.isEmpty()) continue;
            ShiroikumaExport.Cat cat = ShiroikumaExport.Cat.byId(id);
            if (cat == null) unknown.add(id);
            else resolved.add(cat);
        }
        if (!unknown.isEmpty()) throw new IllegalArgumentException(unknown.get(0));
        return resolved.isEmpty() ? ShiroikumaExport.Cat.defaults() : resolved;
    }

    /** Directory precedence: {@code path} extra → configured SAF directory → {@code ERROR:no-directory}. */
    private static String runExport(Context app, String pathOverride, Set<ShiroikumaExport.Cat> cats,
                                    AutomationProgress progress, Run run) throws Exception {
        ShiroikumaExport.Written written;
        if (!pathOverride.isEmpty()) {
            if (!hasAllFilesAccess(app)) return "ERROR:no-storage-access";
            written = ShiroikumaExport.exportToFile(app, new File(pathOverride), cats, progress, run.cancelled::get);
        } else {
            DocumentFile dir = ShiroikumaExport.exportDir(app);
            if (dir == null) return "ERROR:no-directory";
            written = ShiroikumaExport.exportToDirectory(app, dir, cats, progress, run.cancelled::get);
        }
        return "OK:" + written.path + "|" + written.bytes + "|" + ShiroikumaExport.humanSize(written.bytes)
                + "|" + cats.size() + " categories";
    }

    /**
     * Whether a caller-supplied absolute path may be written: All-files access on API 30+ (the
     * manifest declares it; the grant is a Settings page, per app), the legacy write permission
     * below that.
     */
    static boolean hasAllFilesAccess(@NonNull Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) return Environment.isExternalStorageManager();
        return context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    private static String trimmed(@Nullable String s) {
        return s == null ? "" : s.trim();
    }

    private interface Replier {
        void send(String result);
    }
}
