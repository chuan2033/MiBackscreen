package hook.HyperBackscreen.hook;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;

import java.util.concurrent.Future;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import hook.HyperBackscreen.bridge.FaceEnrollmentProvider;
import hook.HyperBackscreen.bridge.FaceEnrollmentReader;
import hook.HyperBackscreen.common.Constants;

final class ThemeFaceEnrollment {
    private static final ThreadPoolExecutor QUERIES = new ThreadPoolExecutor(
            0, 1, 10, TimeUnit.SECONDS, new SynchronousQueue<>(), task -> {
                Thread thread = new Thread(task, "MiBackscreen-FaceCount");
                thread.setDaemon(true);
                return thread;
            });

    private ThemeFaceEnrollment() {}

    static int read(Context context) {
        Future<Integer> query = null;
        try {
            Context app = context.getApplicationContext();
            query = QUERIES.submit(() -> {
                Bundle result = app.getContentResolver().call(
                        Uri.parse("content://" + FaceEnrollmentProvider.AUTHORITY),
                        FaceEnrollmentProvider.METHOD, null, null);
                return result == null ? FaceEnrollmentReader.UNKNOWN
                        : result.getInt(FaceEnrollmentProvider.COUNT, FaceEnrollmentReader.UNKNOWN);
            });
            int count = query.get(1800, TimeUnit.MILLISECONDS);
            Log.i(Constants.LOG_TAG, "Theme face enrollment compatibility: count=" + count);
            return count;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return FaceEnrollmentReader.UNKNOWN;
        } catch (Exception e) {
            Log.w(Constants.LOG_TAG, "Theme face enrollment query unavailable", e);
            return FaceEnrollmentReader.UNKNOWN;
        } finally {
            if (query != null && !query.isDone()) query.cancel(true);
        }
    }
}
