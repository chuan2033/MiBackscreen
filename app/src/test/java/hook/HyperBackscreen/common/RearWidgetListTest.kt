package hook.HyperBackscreen.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RearWidgetListTest {
    @Test
    fun parsesFlattenedComponentsInOrderWithoutDuplicates() {
        val raw = "com.android.calendar/com.android.calendar.widget.MonthWidgetProvider\n" +
            "com.miui.weather2/.widget.WeatherWidget, " +
            "com.android.calendar/com.android.calendar.widget.MonthWidgetProvider"
        assertEquals(
            listOf(
                "com.android.calendar/com.android.calendar.widget.MonthWidgetProvider",
                "com.miui.weather2/.widget.WeatherWidget",
            ),
            RearWidgetList.parse(raw),
        )
    }

    @Test
    fun dropsInvalidEntries() {
        assertEquals(emptyList<String>(), RearWidgetList.parse("not a component\ncom.example\n/Foo"))
        assertFalse(RearWidgetList.isValid("com.example/"))
        assertTrue(RearWidgetList.isValid("com.example/com.example.Widget\$Inner"))
    }

    @Test
    fun capsAtMaxWidgets() {
        val raw = (1..10).joinToString("\n") { "com.example.app$it/.W" }
        assertEquals(RearWidgetList.MAX_WIDGETS, RearWidgetList.parse(raw).size)
        assertEquals(RearWidgetList.MAX_WIDGETS, RearWidgetList.encode(RearWidgetList.parse(raw).plus("com.x.y/.Z")).lines().size)
    }

    @Test
    fun encodeRoundTrips() {
        val list = listOf("com.a.b/.C", "com.d.e/com.d.e.F")
        assertEquals(list, RearWidgetList.parse(RearWidgetList.encode(list)))
        assertEquals("", RearWidgetList.encode(emptyList()))
    }

    @Test
    fun moduleIdRange() {
        assertTrue(RearWidgetList.isModuleId(RearWidgetList.idFor(0)))
        assertTrue(RearWidgetList.isModuleId(RearWidgetList.idFor(5)))
        assertFalse(RearWidgetList.isModuleId(-1172057792))
        assertFalse(RearWidgetList.isModuleId(RearWidgetList.BASE_ID + RearWidgetList.ID_SPAN))
    }
}
