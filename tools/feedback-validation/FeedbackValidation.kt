package hook.HyperBackscreen.feedbacktest

import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.lifecycle.ViewModelStore
import hook.HyperBackscreen.app.ModuleApp
import hook.HyperBackscreen.ui.RearScreenApp
import hook.HyperBackscreen.ui.about.*
import hook.HyperBackscreen.ui.components.UiFeedback
import hook.HyperBackscreen.ui.pickup.*
import java.io.File
import java.lang.ref.WeakReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

/** Compiled only with isolated.init.gradle. All export contents and pickup stores are synthetic. */
class FeedbackTestApp : ModuleApp() {
    private var active = WeakReference<Activity>(null)
    override fun onCreate() {
        super.onCreate()
        getSharedPreferences("module_config", MODE_PRIVATE).edit()
            .putBoolean("check_updates", false).commit()
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityResumed(a: Activity) { active = WeakReference(a) }
            override fun onActivityCreated(a: Activity, s: Bundle?) {}
            override fun onActivityStarted(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, s: Bundle) {}
            override fun onActivityDestroyed(a: Activity) { if (active.get() === a) active.clear() }
        })
        registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.getStringExtra("command")) {
                    "fail" -> { ScriptedExport.fail = true; ScriptedExport.gate.countDown() }
                    "succeed" -> { ScriptedExport.fail = false; ScriptedExport.gate.countDown() }
                    "progress" -> ScriptedExport.progress?.invoke(intent.getIntExtra("stage", 2), 10)
                    "recreate" -> active.get()?.recreate()
                    "share-ok" -> ScriptedExport.shareFails = false
                    "delete-file" -> File(cacheDir, "feedback/synthetic.zip").delete()
                    "status" -> android.util.Log.i("Feedback13", "exports=${ScriptedExport.calls.get()} shares=${ScriptedExport.shares.get()}")
                }
            }
        }, IntentFilter("$packageName.CONTROL"), "android.permission.DUMP", null, RECEIVER_EXPORTED)
    }
}

private object ScriptedExport {
    @Volatile var gate = CountDownLatch(1)
    @Volatile var fail = false
    @Volatile var shareFails = true
    val calls = AtomicInteger()
    val shares = AtomicInteger()
    @Volatile var progress: ((Int, Int) -> Unit)? = null
    val operation = LogExportOperation { context, progress ->
        calls.incrementAndGet()
        gate = CountDownLatch(1)
        ScriptedExport.progress = progress
        progress(1, 10)
        check(gate.await(180, TimeUnit.SECONDS)) { "Test timed out" }
        ScriptedExport.progress = null
        if (fail) throw java.io.IOException("Synthetic export failure")
        progress(9, 10)
        syntheticZip(context)
    }
    val share = LogShareOperation { context, file ->
        shares.incrementAndGet()
        if (shareFails) throw android.content.ActivityNotFoundException("Synthetic share failure")
        FeedbackLogExporter.share(context, file)
    }
}

private fun syntheticZip(context: Context): File {
    val directory = File(context.cacheDir, "feedback").apply { mkdirs() }
    return File(directory, "synthetic.zip").also { file ->
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("synthetic.txt"))
            zip.write("Synthetic test data only".toByteArray())
            zip.closeEntry()
        }
    }
}

class FeedbackTestActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mode = intent.getStringExtra("bar") ?: "liquid"
        getSharedPreferences("module_config", MODE_PRIVATE).edit()
            .putBoolean("floating_nav_bar", mode != "normal")
            .putBoolean("liquid_glass", mode == "liquid").commit()
        setContent {
            CompositionLocalProvider(LocalLogExportOperation provides ScriptedExport.operation,
                LocalLogShareOperation provides ScriptedExport.share) { RearScreenApp() }
        }
    }
}

class FeedbackInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        val report = Bundle()
        try {
            runBlocking { runChecks() }
            report.putString("stream", "Feedback13: all lifecycle and failure checks passed\n")
            finish(Activity.RESULT_OK, report)
        } catch (error: Throwable) {
            report.putString("stream", error.stackTraceToString())
            finish(Activity.RESULT_CANCELED, report)
        }
    }

    private suspend fun <T> main(block: () -> T): T = withContext(Dispatchers.Main) { block() }
    private suspend fun logResult(vm: LogExportViewModel, test: (LogExportState) -> Boolean) =
        withTimeout(8000) { vm.state.first(test) }
    private suspend fun pickupResult(vm: PickupSelectionViewModel) =
        withTimeout(8000) { vm.state.first { !it.busy } }
    private fun passed(name: String) { android.util.Log.i("Feedback13", "PASS $name") }

    private suspend fun runChecks() {
        val app = targetContext.applicationContext as Application
        val store = ViewModelStore()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val log = main {
            LogExportViewModel(app, LogExportOperation { context, progress ->
                calls.incrementAndGet(); progress(3, 10); started.countDown()
                check(release.await(5, TimeUnit.SECONDS)); syntheticZip(context)
            }).also { store.put("log", it); it.start(); it.start() }
        }
        check(started.await(5, TimeUnit.SECONDS))
        check(log.state.value == LogExportState.Running(3, 10))
        check(calls.get() == 1)
        main { check(store["log"] === log) }
        release.countDown()
        logResult(log) { it is LogExportState.Ready }
        passed("slow progress, duplicate suppression, retained ViewModel, ready")

        val attempts = AtomicInteger()
        val retry = main {
            LogExportViewModel(app, LogExportOperation { context, _ ->
                if (attempts.incrementAndGet() == 1) throw java.io.IOException("synthetic")
                syntheticZip(context)
            }).also { store.put("retry", it); it.start() }
        }
        logResult(retry) { it == LogExportState.Failed }
        main { retry.start() }
        logResult(retry) { it is LogExportState.Ready }
        check(attempts.get() == 2)
        passed("export exception and retry")

        val missing = main { LogExportViewModel(app, LogExportOperation { _, _ -> File(app.cacheDir, "missing.zip") })
            .also { store.put("missing", it); it.start() } }
        logResult(missing) { it == LogExportState.Failed }
        passed("missing output rejected")

        val group = PickupGroup("Synthetic", listOf("10-11", "20-22"))
        val payload = PickupPayload(listOf(group))
        val writes = AtomicInteger()
        var failWrite = true
        var throwWrite = false
        var pending = false
        var refreshOk = true
        var readFails = false
        var encoded = ""
        var refreshes = 0
        var writeGate: CountDownLatch? = null
        val fake = object : PickupSelectionStore {
            override fun read(): String { if (readFails) error("read"); return encoded }
            override fun commit(value: String): PickupSaveResult {
                writes.incrementAndGet()
                writeGate?.await(5, TimeUnit.SECONDS)
                if (throwWrite) error("write")
                if (failWrite) return PickupSaveResult.Failed
                encoded = value
                return if (pending) PickupSaveResult.Pending else PickupSaveResult.Synced
            }
            override fun refresh(): Boolean { refreshes++; return refreshOk }
        }
        val vm = main { PickupSelectionViewModel(PickupSelectionRepository(fake)).also { store.put("pickup", it); it.bind(payload) } }
        pickupResult(vm)
        main { vm.save(payload, mapOf(group.identity to emptySet())) }
        check(pickupResult(vm).selections[group.identity] == group.codes.toSet())
        check(vm.state.value.result == PickupSaveResult.Failed && refreshes == 0)
        failWrite = false
        main { vm.retry(payload) }
        check(pickupResult(vm).selections[group.identity]!!.isEmpty())
        check(refreshes == 1)
        passed("commit false keeps checkmarks; retry commits then refreshes")

        throwWrite = true
        main { vm.save(payload, mapOf(group.identity to group.codes.toSet())) }
        check(pickupResult(vm).result == PickupSaveResult.Failed)
        throwWrite = false; pending = true
        main { vm.retry(payload) }
        check(pickupResult(vm).result == PickupSaveResult.Pending)
        check(refreshes == 1)
        passed("commit exception and cold-service pending result")

        pending = false; refreshOk = false
        main { vm.save(payload, mapOf(group.identity to emptySet())) }
        check(pickupResult(vm).result == PickupSaveResult.RefreshFailed)
        val countBeforeRefresh = writes.get()
        refreshOk = true
        main { vm.retry(payload) }
        check(pickupResult(vm).result == PickupSaveResult.Synced)
        check(writes.get() == countBeforeRefresh)
        passed("refresh retry does not repeat commit")

        writeGate = CountDownLatch(1)
        val other = PickupPayload(listOf(PickupGroup("Other", listOf("30-33"))))
        main { vm.save(payload, mapOf(group.identity to group.codes.toSet())); vm.bind(other) }
        writeGate!!.countDown()
        check(pickupResult(vm).payload == other)
        check(vm.state.value.selections.keys == setOf(other.groups.single().identity))
        val countBeforeStaleRetry = writes.get()
        main { vm.retry(payload) }
        check(writes.get() == countBeforeStaleRetry)
        passed("new payload waits for commit and rejects stale retry")

        readFails = true
        main { vm.bind(payload) }
        check(pickupResult(vm).loadFailed)
        readFails = false
        main { vm.retry(payload) }
        check(!pickupResult(vm).loadFailed)
        passed("read failure and retry")

        val feedbackScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val feedback = UiFeedback(feedbackScope)
        val actions = AtomicInteger()
        main { feedback.show("old", "Retry") { error("stale action") }; feedback.show("new", "Retry") { actions.incrementAndGet() } }
        withContext(Dispatchers.Main) {
            val newest = feedback.host.newestSnackbarData()!!
            check(newest.visuals.message == "new")
            newest.performAction(); newest.performAction()
        }
        delay(50)
        check(actions.get() == 1)
        passed("latest Snackbar replaces old; action fires once")
        main { store.clear(); feedbackScope.cancel() }
    }
}
