package hook.HyperBackscreen.bridge;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Process;

import hook.HyperBackscreen.common.Constants;

/** Narrow read-only bridge for the theme store, whose port lacks MANAGE_BIOMETRIC. */
public final class FaceEnrollmentProvider extends ContentProvider {
    public static final String AUTHORITY = Constants.MODULE_PACKAGE + ".FaceEnrollment";
    public static final String METHOD = "get_enrolled_face_count";
    public static final String COUNT = "count";

    @Override public boolean onCreate() { return true; }

    @Override public Bundle call(String method, String arg, Bundle extras) {
        if (!METHOD.equals(method)) throw new IllegalArgumentException("Unknown method");
        int uid = Binder.getCallingUid();
        int userId = uid / 100000;
        if (getContext() == null || userId != Process.myUid() / 100000) {
            throw new SecurityException("Cross-user query denied");
        }
        boolean allowed = uid == Process.myUid();
        String[] packages = getContext().getPackageManager().getPackagesForUid(uid);
        if (packages != null) {
            for (String name : packages) {
                allowed |= Constants.THEME_STORE_PACKAGE.equals(name);
            }
        }
        if (!allowed) throw new SecurityException("Theme store only");
        // Enrollment is queried afresh; never retain a positive result after faces are removed.
        long token = Binder.clearCallingIdentity();
        try {
            Bundle result = new Bundle();
            result.putInt(COUNT, FaceEnrollmentReader.read(userId));
            return result;
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    @Override public Cursor query(Uri u, String[] p, String s, String[] a, String o) {
        throw new UnsupportedOperationException("call only");
    }
    @Override public String getType(Uri u) { return null; }
    @Override public Uri insert(Uri u, ContentValues v) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri u, String s, String[] a) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri u, ContentValues v, String s, String[] a) {
        throw new UnsupportedOperationException();
    }
}
