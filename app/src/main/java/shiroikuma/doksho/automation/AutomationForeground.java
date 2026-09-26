package shiroikuma.doksho.automation;

import android.content.Context;
import android.os.PowerManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * The one place that decides how a refused foreground start is reported (contract §1).
 *
 * <p>A broadcast or a provider {@code call()} is a <b>background</b> start on API 31+, so
 * {@code startForegroundService} and the service's own {@code startForeground} can both throw
 * {@code ForegroundServiceStartNotAllowedException}. The allowance comes from recent interaction,
 * which is why none of this appears by hand: open the app, run a backup, it works. It fails in the
 * cold unattended batch and on a restore onto a clean phone — the case the contract exists for.
 *
 * <p>In this app the §1 export never starts a service (a settings-only ZIP finishes in
 * milliseconds inside the receiver's own {@code goAsync()} window), so the two sites that remain
 * are the §2a provider's {@code startForegroundService} and {@link AutomationDataService}'s own
 * {@code startForeground}. Both answer through here.
 */
public final class AutomationForeground {

    /**
     * The keyed refusal 応用管理 / 自由作業盤 match to put a 「電池最適化を除外」 button on the failed
     * row. Reserved for the case that button can actually fix: on EMUI a refused start can equally
     * be アプリ起動管理 sitting on 自動管理, which no app can change for itself, and a button that
     * cannot repair the fault is worse than a line that names it.
     */
    public static final String NO_FOREGROUND_START = "ERROR:no-foreground-start";

    private AutomationForeground() {
    }

    /**
     * The reply for a refused start: the key when <b>both</b> hold — the throwable is a
     * {@code ForegroundServiceStartNotAllowedException} (matched by class <b>name</b>: the class is
     * API 31 and {@code instanceof} would not load on an older device) and the app is not already
     * battery-exempt — and a descriptive one-line {@code ERROR:} otherwise.
     */
    @NonNull
    public static String refusal(@NonNull Context context, @Nullable Throwable t) {
        if (isNotAllowed(t) && !isBatteryExempt(context)) return NO_FOREGROUND_START;
        return "ERROR:cannot start export service: " + oneLine(t);
    }

    private static boolean isNotAllowed(@Nullable Throwable t) {
        return t != null && "ForegroundServiceStartNotAllowedException".equals(t.getClass().getSimpleName());
    }

    private static boolean isBatteryExempt(@NonNull Context context) {
        try {
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            // If we cannot tell, do not promise a repair the button may not deliver.
            return pm == null || pm.isIgnoringBatteryOptimizations(context.getPackageName());
        } catch (Exception e) {
            return true;
        }
    }

    /** One short line, whatever the throwable carried — a reply is a single line by contract. */
    @NonNull
    public static String oneLine(@Nullable Throwable t) {
        if (t == null) return "foreground service refused";
        String message = t.getMessage();
        if (message == null || message.trim().isEmpty()) message = t.getClass().getSimpleName();
        message = message.replace('\n', ' ').replace('\r', ' ').trim();
        return message.length() > 160 ? message.substring(0, 160) : message;
    }
}
