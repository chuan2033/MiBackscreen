package hook.HyperBackscreen.bridge

import hook.HyperBackscreen.common.Constants
import org.junit.Assert.*
import org.junit.Test

class PreferenceSyncReportTest {
    private val key = Constants.KEY_DISABLE_LONG_PRESS_EDIT

    @Test fun pendingUiValueIsReportedSeparatelyFromRemote() {
        val local = linkedMapOf(key to false, "__pending_ui__$key" to false)
        val before = local.toMap()
        val report = PreferenceSyncReport.build(local, mapOf(key to true))
        assertTrue(report.contains("pending_ui_count=1"))
        assertTrue(report.contains("pending_ui=false"))
        assertTrue(report.contains("ui_source=pending_ui\nui_value=false\nui_matches_remote=false"))
        assertEquals(before, local)
    }

    @Test fun unavailableRemoteIsNotTreatedAsEmptyPreferences() {
        val report = PreferenceSyncReport.build(mapOf(key to false), null)
        assertTrue(report.contains("remote_available=false"))
        assertTrue(report.contains("remote=<unavailable>"))
        assertTrue(report.contains("ui_source=local_cache\nui_value=false\nui_matches_remote=unknown"))
    }

    @Test fun absentRemoteKeyUsesHookDefaultAndRetainsLocalDifference() {
        val report = PreferenceSyncReport.build(mapOf(key to false), emptyMap<String, Any>())
        assertTrue(report.contains("local=false\nremote=<unset; default=true>"))
        assertTrue(report.contains("ui_source=remote\nui_value=true"))
    }

    @Test fun legacyPanelPendingDoesNotOverrideUiReadPriority() {
        val report = PreferenceSyncReport.build(mapOf("__pending_panel__$key" to false), mapOf(key to true))
        assertTrue(report.contains("pending_panel_count=1"))
        assertTrue(report.contains("pending_panel=false\nui_source=remote\nui_value=true"))
    }

    @Test fun stringValuesCannotInjectReportFieldsAndUnknownKeysAreExcluded() {
        val report = PreferenceSyncReport.build(mapOf(
            Constants.KEY_PICKUP_ISLAND_SELECTION to "a\nfake=value",
            "unrelated_secret" to "do-not-export",
        ), null)
        assertTrue(report.contains("a\\nfake=value"))
        assertFalse(report.contains("\nfake=value"))
        assertFalse(report.contains("do-not-export"))
    }
}
