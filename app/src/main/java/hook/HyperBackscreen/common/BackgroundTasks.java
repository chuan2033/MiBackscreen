package hook.HyperBackscreen.common;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Per-process, bounded I/O worker. The worker exits after an idle period. */
public final class BackgroundTasks {
    private static final ThreadPoolExecutor WORKER = new ThreadPoolExecutor(1, 1, 30,
            TimeUnit.SECONDS, new ArrayBlockingQueue<>(32), task -> {
                Thread thread = new Thread(task, "MiBackscreen-IO");
                thread.setDaemon(true);
                return thread;
            });
    static { WORKER.allowCoreThreadTimeOut(true); }

    private BackgroundTasks() {}

    public static void execute(Runnable task) { WORKER.execute(task); }
}
