package hook.HyperBackscreen.ui

import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeRouteTest {
    @Test
    fun everyDetailStackSurvivesSerialization() {
        HomeRoute.entries.filter { it != HomeRoute.Main }.forEach { detail ->
            val stack = listOf(HomeRoute.Main, detail)
            val restored = Json.decodeFromString<List<HomeRoute>>(Json.encodeToString(stack))

            assertEquals(stack, restored)
            assertEquals(HomeRoute.Main, restored.dropLast(1).single())
        }
    }

    @Test
    fun savedRouteNamesRemainCompatible() {
        val restored = Json.decodeFromString<List<HomeRoute>>(
            """["Main","Settings","License","AppPicker","Donate"]"""
        )

        assertEquals(HomeRoute.entries.toList(), restored)
    }
}
