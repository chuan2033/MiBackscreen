package hook.HyperBackscreen.ui

import org.junit.Assert.*
import org.junit.Test

class ScopeStopCommandTest {
    @Test fun systemScopeNeverProducesACommand() {
        assertNull(scopeStopCommand("system"))
        assertNull(scopeStopCommand("system_server"))
    }
    @Test fun applicationStopNeverBecomesAReboot() {
        listOf("com.xiaomi.subscreencenter", "com.miui.personalassistant").forEach { packageName ->
            assertArrayEquals(arrayOf("su", "-c", "am force-stop $packageName"),
                scopeStopCommand(packageName))
            assertArrayEquals(arrayOf("su", "-c", "am force-stop $packageName"),
                scopeActionCommands(listOf(packageName), true)!!.single())
        }
    }
    @Test fun malformedPackagesCannotAddShellCommands() {
        listOf("", "com.example; reboot", "com.example\nreboot", "$(reboot)", "com.example app")
            .forEach { assertNull(scopeStopCommand(it)) }
    }

    @Test fun confirmedSystemSelectionProducesOneRebootEvenWhenAppsAreAlsoSelected() {
        val commands = scopeActionCommands(listOf("system", "com.android.thememanager", "system"), true)!!
        assertEquals(1, commands.size)
        assertArrayEquals(arrayOf("su", "-c", "reboot"), commands.single())
    }

    @Test fun automaticRefreshCannotRequestSystemReboot() {
        assertNull(scopeActionCommands(listOf("system")))
        assertNull(scopeActionCommands(listOf("system", "com.android.thememanager")))
    }

    @Test fun appSelectionIsDeduplicatedAndNeverReboots() {
        val commands = scopeActionCommands(listOf("com.android.thememanager", "com.android.thememanager"), true)!!
        assertEquals(1, commands.size)
        assertArrayEquals(arrayOf("su", "-c", "am force-stop com.android.thememanager"), commands.single())
        assertTrue(scopeActionCommands(emptyList(), true)!!.isEmpty())
    }

    @Test fun malformedSelectionIsRejectedEvenWithSystemSelected() {
        assertNull(scopeActionCommands(listOf("system", "com.example; reboot"), true))
        assertNull(scopeActionCommands(listOf("system_server"), true))
    }
}
