package shiroikuma.doksho.automation;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import shiroikuma.doksho.backup.ShiroikumaExport;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The data door (contract v2 §2a): export this app's own state, and put it back, for a caller we
 * can identify. It sits <i>alongside</i> {@link StateExportReceiver} and replaces nothing. Ported
 * from raikidoban's {@code AutomationProvider}.
 *
 * <h3>Why a provider and not the broadcast receiver next to it</h3>
 *
 * <p><b>A broadcast cannot tell you who sent it.</b> A provider gets the caller's identity from the
 * framework — see {@link AutomationCallers} for what is checked and why a package-name prefix would
 * have been worse than the token it replaced. And a list needs a synchronous answer: 応用管理 draws a
 * row per installed app before any export exists.
 *
 * <h3>What does NOT happen here</h3>
 *
 * <p>The payload. {@link #call} validates, starts a foreground service and returns; the bytes go
 * through a file descriptor the caller opened (never a path — 応用管理 renames, encrypts and
 * checksums per file it knows about), and the terminal answer comes back on a broadcast.
 *
 * <h3>{@code import} exists ONLY here</h3>
 *
 * <p>It never gets a broadcast action: the §1 receiver is exported with no permission, and an
 * import there would let any app on the phone rewrite this one's settings.
 *
 * <p>{@code describe} answers from the manifest, the enum and SharedPreferences only — a provider
 * is published before {@code Application.onCreate}, and on a clean phone this call is what starts
 * the process at all.
 */
public class AutomationProvider extends ContentProvider {

    public static final String METHOD_DESCRIBE = "describe";
    public static final String METHOD_EXPORT = "export";
    public static final String METHOD_IMPORT = "import";
    public static final String METHOD_CANCEL = "cancel";

    public static final String KEY_RESULT = "result";
    public static final String KEY_FD = "fd";
    public static final String KEY_TOKEN = "token";
    public static final String KEY_JOB_ID = "job_id";
    /** The broadcast door's correlation extra; mirrored here so one reader serves both doors. */
    public static final String KEY_REPLY_ID = "reply_id";
    public static final String KEY_ITEMS = "items";
    public static final String KEY_REPLY_ACTION = "reply_action";
    public static final String KEY_REPLY_PACKAGE = "reply_package";
    public static final String KEY_PROGRESS_ACTION = "progress_action";

    /** This app's archive format; bumped when an older build could no longer read what we write. */
    public static final int FORMAT = ShiroikumaExport.VERSION;
    /** The oldest archive this build can still read — lets a restore be refused at discovery time. */
    public static final int MIN_FORMAT_READABLE = 1;

    @Override
    public boolean onCreate() {
        return true;
    }

    /**
     * Every method answers a {@link Bundle} with {@link #KEY_RESULT} — {@code OK…} or
     * {@code ERROR:…}, the same vocabulary the broadcast contract uses. <b>A refusal is returned,
     * never thrown</b>: an exception across a binder reaches the caller as a stack trace.
     */
    @Nullable
    @Override
    public Bundle call(@NonNull String method, @Nullable String arg, @Nullable Bundle extras) {
        Context ctx = getContext();
        if (ctx == null) return answer("ERROR:not ready");
        ctx = ctx.getApplicationContext();

        // WHO, before WHAT. A caller we cannot identify gets the same answer whatever it asked for.
        // (getCallingPackage() itself throws when the declared package does not belong to the
        // calling uid — that too is a refusal to return, never a stack trace to leak.)
        String declared;
        try {
            declared = getCallingPackage();
        } catch (SecurityException e) {
            return answer("ERROR:caller unknown");
        }
        String refusedCaller = AutomationCallers.verify(ctx, declared);
        if (refusedCaller != null) return answer(refusedCaller);
        // Then this app's own switches — a token is ignored unless this app asks for one (§2).
        String refused = AutomationAuth.refuse(ctx, extras == null ? null : extras.getString(KEY_TOKEN));
        if (refused != null) return answer(refused);

        if (METHOD_DESCRIBE.equals(method)) return answer(describe(ctx));
        if (METHOD_EXPORT.equals(method)) return start(ctx, extras, false);
        if (METHOD_IMPORT.equals(method)) return start(ctx, extras, true);
        if (METHOD_CANCEL.equals(method)) {
            AutomationJobs.cancel(extras == null ? null : extras.getString(KEY_JOB_ID));
            return answer("OK:cancelled");
        }
        return answer("ERROR:unknown method: " + method);
    }

    /** The header — returned from the call, and deliberately NOT put inside the archive. */
    private String describe(Context ctx) {
        try {
            JSONObject header = new JSONObject();
            header.put("app_id", ctx.getPackageName());
            long code = 0;
            String name = "";
            try {
                PackageInfo info = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
                @SuppressWarnings("deprecation")
                int legacy = info.versionCode;
                code = legacy;
                name = info.versionName == null ? "" : info.versionName;
            } catch (Exception ignored) {
                // a header without a version is still a usable header
            }
            header.put("version_code", code);
            header.put("version_name", name);
            header.put("format", FORMAT);
            header.put("min_format_readable", MIN_FORMAT_READABLE);
            // The import merges SharedPreferences with commit() and 応用管理 force-stops us the
            // moment we report success, so a never-launched install is fine.
            header.put("requires_launch_first", false);
            // The restore writes only this app's own SharedPreferences — no permission-guarded
            // system provider is touched. (Never list MANAGE_EXTERNAL_STORAGE here.)
            header.put("requires_permissions", new JSONArray());
            JSONArray contains = new JSONArray();
            for (ShiroikumaExport.Cat cat : ShiroikumaExport.Cat.defaults()) {
                if (cat.parentId == null) contains.put(ctx.getString(cat.labelRes));
            }
            header.put("contains", contains);
            return "OK:" + header;
        } catch (Exception e) {
            return "ERROR:" + AutomationForeground.oneLine(e);
        }
    }

    /**
     * Hand the descriptor to the foreground service and get out of the way. The descriptor is
     * <b>duplicated</b> before it leaves this method: the one in {@code extras} belongs to the
     * binder transaction and is closed when {@code call()} returns.
     *
     * <p>A refused service start is answered as the return value — never {@code OK:<job_id>} for a
     * job that will not run — with the dup closed and the job dropped first, so the caller's file
     * is never stranded in a map nothing will read.
     */
    private Bundle start(Context ctx, @Nullable Bundle extras, boolean importing) {
        ParcelFileDescriptor fd = extras == null ? null : extras.getParcelable(KEY_FD);
        if (fd == null) return answer("ERROR:no descriptor");
        ParcelFileDescriptor dup;
        try {
            dup = fd.dup();
        } catch (Exception e) {
            return answer("ERROR:descriptor unusable");
        }
        String jobId = AutomationJobs.begin();
        try {
            AutomationDataService.start(ctx, jobId, dup, importing, extras);
        } catch (Throwable t) {
            AutomationJobs.finish(jobId);
            closeQuietly(dup);
            return answer(AutomationForeground.refusal(ctx, t));
        }
        return answer("OK:" + jobId);
    }

    private static void closeQuietly(ParcelFileDescriptor fd) {
        try {
            fd.close();
        } catch (Exception ignored) {
            // pass
        }
    }

    private static Bundle answer(String result) {
        Bundle b = new Bundle();
        b.putString(KEY_RESULT, result);
        return b;
    }

    // A provider that is only ever call()ed still has to answer these. Refusing loudly beats
    // returning an empty cursor, which reads downstream as "there is no data" rather than "wrong door".

    @Nullable
    @Override
    public Cursor query(@NonNull Uri uri, @Nullable String[] projection, @Nullable String selection,
                        @Nullable String[] args, @Nullable String order) {
        throw new UnsupportedOperationException("automation is call() only");
    }

    @Nullable
    @Override
    public String getType(@NonNull Uri uri) {
        return null;
    }

    @Nullable
    @Override
    public Uri insert(@NonNull Uri uri, @Nullable ContentValues values) {
        throw new UnsupportedOperationException("automation is call() only");
    }

    @Override
    public int delete(@NonNull Uri uri, @Nullable String selection, @Nullable String[] args) {
        throw new UnsupportedOperationException("automation is call() only");
    }

    @Override
    public int update(@NonNull Uri uri, @Nullable ContentValues values, @Nullable String selection,
                      @Nullable String[] args) {
        throw new UnsupportedOperationException("automation is call() only");
    }
}
