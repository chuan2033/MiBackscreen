package hook.HyperBackscreen.ui.config

import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class AppListLoaderTest {
    @Test fun visibleSubsetIsReturnedWithoutInventingMissingApplications() {
        assertEquals(AppListLoadResult.Loaded(listOf("visible.package")), loadAppList { listOf("visible.package") })
    }
    @Test fun emptyVisibilityIsDifferentFromAQueryFailure() {
        assertEquals(AppListLoadResult.Loaded(emptyList<String>()), loadAppList { emptyList<String>() })
        val denied = SecurityException("denied")
        assertEquals(AppListLoadResult.Failed(denied), loadAppList<String> { throw denied })
    }
    @Test fun failedQueryCanBeRetried() {
        var attempts = 0
        val query = {
            if (++attempts == 1) throw IllegalStateException("temporary failure")
            listOf("visible.package")
        }
        assertTrue(loadAppList(query) is AppListLoadResult.Failed)
        assertEquals(AppListLoadResult.Loaded(listOf("visible.package")), loadAppList(query))
        assertEquals(2, attempts)
    }
    @Test fun leavingThePagePropagatesCancellation() {
        val cancelled = CancellationException("page closed")
        try {
            loadAppList<String> { throw cancelled }
            fail("Cancellation must not become an error card")
        } catch (actual: CancellationException) {
            assertSame(cancelled, actual)
        }
    }
}
