package shiroikuma.doksho.automation;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;

/**
 * The gate in front of the automation surface — the {@link StateExportReceiver} broadcasts and the
 * {@link AutomationProvider} data door — as the sister-app contract <b>v2</b> defines it: a master
 * switch that is <b>ON</b> by default, a 「Use authorization token?」 switch that is <b>OFF</b> by
 * default, and the token itself.
 *
 * <p>Device-local by design: these values live in their OWN SharedPreferences file, which
 * {@code ShiroikumaExport} excludes, so the token never travels inside a backup ZIP.
 *
 * <h3>Why the token is opt-in (contract v2)</h3>
 *
 * <p>v1 shipped every sister app closed: the switch defaulted to false and a caller also had to
 * present a 48-character secret 白い熊 had pasted from the app's settings into the caller's. A pasted
 * secret cannot survive a wipe, and the case this family now serves is 応用管理 restoring apps and
 * their data onto a clean phone, where nothing has been configured yet. The switch stays because it
 * is the only way to close one app off; the token becomes an extra a caller <i>may</i> be asked for.
 *
 * <h3>A token sent to an app that does not want one is IGNORED, never refused</h3>
 *
 * <p>Tokens live in task arguments that outlive the setting they were pasted for. That is why
 * {@link #refuse} only looks at the candidate when {@link #isTokenRequired} says so, and why the
 * whole decision lives in that one function rather than in two checks per entry point.
 *
 * <h3>Every write is {@code commit()}</h3>
 *
 * <p>Because this gate fails <b>open</b>: with the switch defaulting to true, an {@code apply()}
 * lost to the SIGKILL 応用管理 sends after an import falls back to ON, silently reopening the door
 * 白い熊 just closed. The three writes are tiny and infrequent; synchronous costs nothing.
 */
public final class AutomationAuth {

    /** Device-local prefs; deliberately outside the export map. */
    public static final String PREFS = "shiroikuma_automation";

    private static final String KEY_ENABLED = "automation_enabled";
    private static final String KEY_REQUIRE_TOKEN = "automation_require_token";
    private static final String KEY_TOKEN = "automation_token";

    private static final boolean DEFAULT_ENABLED = true;
    private static final boolean DEFAULT_REQUIRE_TOKEN = false;
    private static final int TOKEN_BYTES = 24;

    private AutomationAuth() {
    }

    private static SharedPreferences prefs(@NonNull Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // --- the one gate ---------------------------------------------------------------------------

    /**
     * The single authorization check for every automation surface.
     *
     * @return {@code null} when the caller may proceed, otherwise the exact {@code ERROR:} line to
     * answer with. "automation disabled" and "bad token" stay distinct because they debug
     * differently.
     */
    @Nullable
    public static String refuse(@NonNull Context context, @Nullable String candidate) {
        if (!isEnabled(context)) return "ERROR:automation disabled";
        if (isTokenRequired(context) && !isTokenValid(context, candidate)) return "ERROR:bad token";
        return null;
    }

    // --- the two switches -----------------------------------------------------------------------

    /** The master switch. Default ON — a clean phone has nothing to turn on. */
    public static boolean isEnabled(@NonNull Context context) {
        return prefs(context).getBoolean(KEY_ENABLED, DEFAULT_ENABLED);
    }

    @SuppressWarnings("ApplySharedPref")
    public static void setEnabled(@NonNull Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).commit();
    }

    /** Whether a caller must also present the token. Default OFF. */
    public static boolean isTokenRequired(@NonNull Context context) {
        return prefs(context).getBoolean(KEY_REQUIRE_TOKEN, DEFAULT_REQUIRE_TOKEN);
    }

    @SuppressWarnings("ApplySharedPref")
    public static void setTokenRequired(@NonNull Context context, boolean required) {
        prefs(context).edit().putBoolean(KEY_REQUIRE_TOKEN, required).commit();
    }

    // --- the token ------------------------------------------------------------------------------

    /** The stored token — 24 random bytes, hex — generated on first read so the row is never empty. */
    @NonNull
    public static synchronized String token(@NonNull Context context) {
        String stored = prefs(context).getString(KEY_TOKEN, null);
        if (stored != null && !stored.isEmpty()) return stored;
        return regenerateToken(context);
    }

    /** Replaces the token; every pasted copy stops working immediately. */
    @NonNull
    @SuppressWarnings("ApplySharedPref")
    public static synchronized String regenerateToken(@NonNull Context context) {
        byte[] raw = new byte[TOKEN_BYTES];
        new SecureRandom().nextBytes(raw);
        StringBuilder sb = new StringBuilder(raw.length * 2);
        for (byte b : raw) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        String token = sb.toString();
        prefs(context).edit().putString(KEY_TOKEN, token).commit();
        return token;
    }

    /** Back to the defaults (switch ON, token not required, a fresh token on next read). */
    @SuppressWarnings("ApplySharedPref")
    public static void reset(@NonNull Context context) {
        prefs(context).edit().clear().commit();
    }

    /** Constant-time comparison against the stored token; only consulted when the token is required. */
    public static boolean isTokenValid(@NonNull Context context, @Nullable String candidate) {
        if (candidate == null || candidate.isEmpty()) return false;
        return MessageDigest.isEqual(
                candidate.getBytes(StandardCharsets.UTF_8),
                token(context).getBytes(StandardCharsets.UTF_8));
    }

    /** {@code 80922d8c…4c49a87c} — what the settings row shows; the tap copies the whole thing. */
    @NonNull
    public static String abbreviate(@Nullable String token) {
        if (token == null) return "";
        if (token.length() <= 20) return token;
        return token.substring(0, 8) + "…" + token.substring(token.length() - 8);
    }
}
