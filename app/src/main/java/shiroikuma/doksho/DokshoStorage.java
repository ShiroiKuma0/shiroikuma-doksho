package shiroikuma.doksho;

import android.app.Activity;
import android.app.Application;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.foobnix.OpenerActivity;
import com.foobnix.android.utils.LOG;

/**
 * All files access, asked for on every open (白い熊 2026-10-05).
 *
 * <p>Without {@code MANAGE_EXTERNAL_STORAGE} the reader can see a PDF in shared storage but not
 * open it, so a book handed over by a file manager fails. Android has no runtime dialog for this
 * permission — the request is the system's "All files access" page for our package. It is shown
 * each time the app comes to the foreground while the access is missing; coming back from that
 * page is not counted as a new open (else a refusal would loop straight back into it).
 * {@link OpenerActivity} asks for itself, before it resolves the file it was sent.</p>
 */
public final class DokshoStorage {

    public static final int REQUEST_CODE = 0x5D0C;

    private static int started;
    private static boolean returningFromRequest;

    private DokshoStorage() {
    }

    public static boolean granted() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager();
    }

    /** Opens the system's All files access page; {@code forResult} reports back to the activity. */
    public static boolean request(@NonNull Activity a, boolean forResult) {
        if (granted()) return false;
        returningFromRequest = true;
        Intent page = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.fromParts("package", a.getPackageName(), null));
        try {
            if (forResult) a.startActivityForResult(page, REQUEST_CODE);
            else a.startActivity(page);
            return true;
        } catch (Exception e) {
            LOG.e(e);
        }
        try {
            Intent list = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
            if (forResult) a.startActivityForResult(list, REQUEST_CODE);
            else a.startActivity(list);
            return true;
        } catch (Exception e) {
            LOG.e(e);
        }
        returningFromRequest = false;
        return false;
    }

    public static void register(@NonNull Application app) {
        app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override
            public void onActivityStarted(@NonNull Activity activity) {
                if (started++ > 0) return;
                if (returningFromRequest) {
                    returningFromRequest = false;
                    return;
                }
                if (activity instanceof OpenerActivity) return;
                request(activity, false);
            }

            @Override
            public void onActivityStopped(@NonNull Activity activity) {
                if (started > 0) started--;
            }

            @Override
            public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle savedInstanceState) {
            }

            @Override
            public void onActivityResumed(@NonNull Activity activity) {
            }

            @Override
            public void onActivityPaused(@NonNull Activity activity) {
            }

            @Override
            public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle outState) {
            }

            @Override
            public void onActivityDestroyed(@NonNull Activity activity) {
            }
        });
    }
}
