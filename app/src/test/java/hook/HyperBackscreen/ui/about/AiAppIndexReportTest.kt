package hook.HyperBackscreen.ui.about

import org.junit.Assert.*
import org.junit.Test

class AiAppIndexReportTest {
    @Test fun comparesProductIdToResIdAndAllowsOtherHostCards() {
        val report = AiAppIndexReport.build(
            """[{"productId":"a","resLocalPath":"/a"},{"productId":"b","resLocalPath":"/b"}]""",
            """[{"resId":"a"},{"resId":"stock-card"}]""",
        )
        assertTrue(report.contains("missing_in_app_info=[\"b\"]"))
        assertTrue(report.contains("comparison=missing_ids"))
    }

    @Test fun missingOrTruncatedDataNeverReportsCompleteComparison() {
        for (raw in listOf("MISSING: /index", "Collection timed out", "[{", "{}", "")) {
            assertTrue(AiAppIndexReport.build(raw, "[]").contains("comparison=unavailable;"))
            assertTrue(AiAppIndexReport.build("[]", raw).contains("comparison=unavailable;"))
        }
    }

    @Test fun malformedEntriesAreReportedEvenWhenKnownIdsMatch() {
        val report = AiAppIndexReport.build("""[{"productId":"a"},null,{"productId":7}]""", """[{"resId":"a"},{}]""")
        assertTrue(report.contains("runtime_invalid_entries=2"))
        assertTrue(report.contains("app_info_invalid_entries=1"))
        assertTrue(report.contains("comparison=incomplete"))
        assertTrue(report.contains("runtime_entries_without_resource_path=3"))
    }

    @Test fun duplicateIdsDoNotInflateMissingCount() {
        val report = AiAppIndexReport.build("""[{"productId":"a"},{"productId":"a"}]""", "[]")
        assertTrue(report.contains("runtime_duplicate_ids=1"))
        assertTrue(report.contains("missing_in_app_info_count=1"))
    }

    @Test fun emptyArraysAreValidAndMatchedIdsDoNotClaimRenderingSuccess() {
        assertTrue(AiAppIndexReport.build("[]", "[]").contains("comparison=all_runtime_ids_present"))
        val report = AiAppIndexReport.build("""[{"productId":"a"}]""", """[{"resId":"a"}]""")
        assertTrue(report.contains("comparison=all_runtime_ids_present"))
        assertTrue(report.contains("does not verify resource files or runtime rendering"))
    }
}
