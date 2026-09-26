package shiroikuma.doksho.automation;

import android.content.Context;
import android.content.Intent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import shiroikuma.doksho.Doksho;
import shiroikuma.doksho.backup.ShiroikumaExport;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The <b>one</b> §3 progress sender, shared by both automation doors (ported from raikidoban's
 * {@code AutomationProgress}).
 *
 * <p>The only difference between the two doors is which extra carries the correlation id: the
 * broadcast door echoes {@code reply_id}, the provider door hands out a {@code job_id}. So the id
 * is written into <b>every</b> name in {@code correlationExtras} — the provider passes both, so one
 * progress reader on the caller's side serves both doors.
 *
 * <h3>The heartbeat is the point, not a nicety</h3>
 *
 * <p>自由作業盤 treats every progress broadcast as proof the app is still alive and presumes an app
 * silent for two minutes to be dead. This app's export is a settings-only ZIP and finishes in
 * milliseconds — but on the data door it writes into <b>a descriptor the caller supplied</b>, which
 * may be a pipe, so a write blocks for exactly as long as 応用管理 is slow to drain it. The
 * heartbeat re-sends the last true line every {@link #HEARTBEAT_MS} for that case; it never invents
 * a moving number.
 *
 * <p>Inert when the caller passed no {@code progress_action} (or no reply package to aim it at —
 * since API 26 an implicit broadcast never reaches a manifest receiver), so both callers construct
 * one unconditionally.
 */
public final class AutomationProgress implements ShiroikumaExport.Progress {

    /** What this app counts: categories. */
    private static final String UNIT = "区分";
    private static final long MIN_INTERVAL_MS = 500;
    /** Comfortably inside §3's 30 s floor, itself well inside the caller's two-minute patience. */
    private static final long HEARTBEAT_MS = 20_000;
    private static final long HEARTBEAT_TICK_MS = 5_000;

    /** Bytes written so far, when the destination is one we count. */
    public interface Bytes {
        long written();
    }

    private final Context context;
    private final String action;
    private final String replyPackage;
    private final String[] correlationExtras;
    private final String correlationId;
    private final String appLabel;
    private volatile List<ShiroikumaExport.Cat> ordered;
    @Nullable
    private final Bytes bytes;
    private final boolean active;

    private volatile String lastItem;
    private volatile String lastText;
    private volatile long lastCurrent;
    private volatile long lastTotal;
    private volatile long lastSentMs;
    private volatile boolean running;
    private Thread heartbeat;

    public AutomationProgress(@NonNull Context context, @Nullable String action, @Nullable String replyPackage,
                              @Nullable String correlationId, @NonNull String[] correlationExtras,
                              @NonNull Set<ShiroikumaExport.Cat> cats, @Nullable Bytes bytes) {
        this.context = context.getApplicationContext();
        this.action = action == null ? "" : action.trim();
        this.replyPackage = replyPackage == null ? "" : replyPackage.trim();
        this.correlationExtras = correlationExtras;
        this.correlationId = correlationId == null ? "" : correlationId;
        this.appLabel = Doksho.NAME;
        this.ordered = ordered(cats);
        this.bytes = bytes;
        this.active = !this.action.isEmpty() && !this.replyPackage.isEmpty();
    }

    /** Narrow the category list once it is actually known (the import learns it from the archive). */
    public void setCategories(@NonNull Set<ShiroikumaExport.Cat> cats) {
        this.ordered = ordered(cats);
    }

    /** The order {@link ShiroikumaExport#export} walks them in, so a count can name the category it means. */
    public static List<ShiroikumaExport.Cat> ordered(Set<ShiroikumaExport.Cat> cats) {
        List<ShiroikumaExport.Cat> list = new ArrayList<>();
        for (ShiroikumaExport.Cat c : ShiroikumaExport.Cat.values()) {
            if (cats.contains(c)) list.add(c);
        }
        return list;
    }

    /** Begin the heartbeat. Safe to call on an inert sender (it does nothing). */
    public void start() {
        if (!active || running) return;
        running = true;
        lastSentMs = System.currentTimeMillis();
        heartbeat = new Thread(() -> {
            while (running) {
                try {
                    Thread.sleep(HEARTBEAT_TICK_MS);
                } catch (InterruptedException e) {
                    return;
                }
                if (!running || lastText == null) continue;
                if (System.currentTimeMillis() - lastSentMs >= HEARTBEAT_MS) {
                    // Same numbers, sent again: "still here", which is all the caller needs.
                    emit(lastItem, lastText, lastCurrent, lastTotal);
                }
            }
        }, "shiroikuma-automation-heartbeat");
        heartbeat.setDaemon(true);
        heartbeat.start();
    }

    /** Stop the heartbeat. Always call this in a {@code finally}. */
    public void stop() {
        running = false;
        Thread t = heartbeat;
        if (t != null) {
            t.interrupt();
            heartbeat = null;
        }
    }

    @Override
    public void onProgress(int done, int total, String categoryLabel) {
        if (!active) return;
        long now = System.currentTimeMillis();
        // At most one every 500 ms — but the final one always goes out.
        if (done < total && now - lastSentMs < MIN_INTERVAL_MS) return;
        List<ShiroikumaExport.Cat> list = ordered;
        String item = (done >= 1 && done <= list.size()) ? list.get(done - 1).id : null;
        emit(item, UNIT + " " + done + "/" + total + " — " + categoryLabel, done, total);
    }

    /** A free-form line for a step with no honest count of its own (spooling an import). */
    public void note(@NonNull String text) {
        if (!active) return;
        emit(null, text, 0, 0);
    }

    private void emit(@Nullable String item, String text, long current, long total) {
        lastItem = item;
        lastText = text;
        lastCurrent = current;
        lastTotal = total;
        lastSentMs = System.currentTimeMillis();

        Intent out = new Intent(action);
        out.setPackage(replyPackage);
        out.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
        for (String extra : correlationExtras) {
            out.putExtra(extra, correlationId);
        }
        out.putExtra("app", appLabel);
        if (item != null) out.putExtra("item", item);
        out.putExtra("text", text);
        out.putExtra("current", current);
        out.putExtra("total", total);
        out.putExtra("unit", UNIT);
        if (bytes != null) out.putExtra("bytes", bytes.written());
        try {
            context.sendBroadcast(out);
        } catch (Exception ignored) {
            // a progress line that cannot be sent is not worth failing the export over
        }
    }
}
