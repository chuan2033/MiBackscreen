package hook.HyperBackscreen.ui.navigation

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
            """["Main","Settings","License","AppPicker","Donate","BatteryColor"]"""
        )

        // 旧版本保存的栈要能在当前枚举下解析，且顺序不变；
        // 新增路由只能追加在末尾，旧栈里各页的位置序号才不会错位。
        assertEquals(restored, HomeRoute.entries.take(restored.size))
    }
}
