package com.example.consoleswitcher3

import android.view.KeyEvent
import com.example.consoleswitcher3.controller.GamepadInputHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.regex.Pattern

class ConsoleSwitcherUnitTest {

    @Test
    fun testGamepadKeyNames() {
        assertEquals("Button A", GamepadInputHelper.getKeyName(KeyEvent.KEYCODE_BUTTON_A))
        assertEquals("Button B", GamepadInputHelper.getKeyName(KeyEvent.KEYCODE_BUTTON_B))
        assertEquals("Button X", GamepadInputHelper.getKeyName(KeyEvent.KEYCODE_BUTTON_X))
        assertEquals("Button Y", GamepadInputHelper.getKeyName(KeyEvent.KEYCODE_BUTTON_Y))
        assertEquals("L1 Bumper", GamepadInputHelper.getKeyName(KeyEvent.KEYCODE_BUTTON_L1))
        assertEquals("R1 Bumper", GamepadInputHelper.getKeyName(KeyEvent.KEYCODE_BUTTON_R1))
        assertEquals("L2 Trigger", GamepadInputHelper.getKeyName(KeyEvent.KEYCODE_BUTTON_L2))
        assertEquals("R2 Trigger", GamepadInputHelper.getKeyName(KeyEvent.KEYCODE_BUTTON_R2))
        assertEquals("D-Pad Left", GamepadInputHelper.getKeyName(KeyEvent.KEYCODE_DPAD_LEFT))
        assertEquals("D-Pad Right", GamepadInputHelper.getKeyName(KeyEvent.KEYCODE_DPAD_RIGHT))
    }

    @Test
    fun testDumpsysRecentsRegex() {
        val sampleDumpsys = """
            ACTIVITY MANAGER RECENT TASKS (dumpsys activity recents)
            * Recent #0: Task{41ea720 #32 type=standard I=com.retroarch.aarch64/com.retroarch.browser.retroactivity.RetroActivityFuture} userId=0
            * Recent #1: Task{91ab341 #45 type=standard I=xyz.aethersx2.android/.MainActivity} userId=0
            * Recent #2: Task{c52b123 #12 type=standard I=org.dolphinemu.dolphinemu} userId=0
        """.trimIndent()

        val pattern = Pattern.compile("\\*\\s+Recent\\s+#\\d+:\\s+Task\\{[0-9a-fA-F]+\\s+#(\\d+)\\s+.*?I=([^/\\s\\}]+)(?:/([^\\s\\}]+))?")
        val matcher = pattern.matcher(sampleDumpsys)

        val parsedTasks = mutableListOf<Triple<Int, String, String>>()
        while (matcher.find()) {
            val taskId = matcher.group(1)!!.toInt()
            val pkg = matcher.group(2)!!
            val comp = matcher.group(3) ?: ""
            parsedTasks.add(Triple(taskId, pkg, comp))
        }

        assertEquals(3, parsedTasks.size)
        assertEquals(32, parsedTasks[0].first)
        assertEquals("com.retroarch.aarch64", parsedTasks[0].second)
        assertEquals("com.retroarch.browser.retroactivity.RetroActivityFuture", parsedTasks[0].third)

        assertEquals(45, parsedTasks[1].first)
        assertEquals("xyz.aethersx2.android", parsedTasks[1].second)
        assertEquals(".MainActivity", parsedTasks[1].third)

        assertEquals(12, parsedTasks[2].first)
        assertEquals("org.dolphinemu.dolphinemu", parsedTasks[2].second)
    }

    @Test
    fun testDumpsysFiltersHomeAndRecentsTasks() {
        // Dumpsys when only home (Quickstep) is running (all apps closed)
        val homeOnlyDumpsys = """
            ACTIVITY MANAGER RECENT TASKS (dumpsys activity recents)
            * Recent #0: Task{41ea720 #1 type=home I=com.android.launcher3/.uioverrides.QuickstepLauncher} userId=0
        """.trimIndent()

        val pattern = Pattern.compile("\\*\\s+Recent\\s+#\\d+:\\s+Task\\{[0-9a-fA-F]+\\s+#(\\d+)\\s+.*?I=([^/\\s\\}]+)(?:/([^\\s\\}]+))?")
        val matcher = pattern.matcher(homeOnlyDumpsys)

        val parsedTasks = mutableListOf<String>()
        val homePackages = setOf("com.android.launcher3")
        while (matcher.find()) {
            val fullMatch = matcher.group(0) ?: ""
            if (fullMatch.contains("type=home") || fullMatch.contains("type=recents")) {
                continue
            }
            val pkg = matcher.group(2)!!
            if (com.example.consoleswitcher3.task.TaskManager.isHomeOrLauncher(pkg, null, homePackages)) {
                continue
            }
            parsedTasks.add(pkg)
        }

        // When all apps are closed and only Quickstep is in dumpsys, 0 tasks should be parsed
        assertEquals(0, parsedTasks.size)
    }

    @Test
    fun testIsHomeOrLauncher() {
        val homePackages = setOf("com.teslacoilsw.launcher")
        
        // Known AOSP / Quickstep launchers
        assertTrue(com.example.consoleswitcher3.task.TaskManager.isHomeOrLauncher("com.android.launcher3", null, homePackages))
        assertTrue(com.example.consoleswitcher3.task.TaskManager.isHomeOrLauncher("com.android.launcher3.quickstep", null, homePackages))
        assertTrue(com.example.consoleswitcher3.task.TaskManager.isHomeOrLauncher("com.google.android.apps.nexuslauncher", null, homePackages))
        assertTrue(com.example.consoleswitcher3.task.TaskManager.isHomeOrLauncher("some.custom.pkg", "Quickstep", homePackages))
        assertTrue(com.example.consoleswitcher3.task.TaskManager.isHomeOrLauncher("com.teslacoilsw.launcher", "Nova Launcher", homePackages))

        // Normal apps
        org.junit.Assert.assertFalse(com.example.consoleswitcher3.task.TaskManager.isHomeOrLauncher("com.retroarch.aarch64", "RetroArch", homePackages))
        org.junit.Assert.assertFalse(com.example.consoleswitcher3.task.TaskManager.isHomeOrLauncher("org.dolphinemu.dolphinemu", "Dolphin", homePackages))
    }

    @Test
    fun testCurrentTaskDetection() {
        // Dumpsys when RetroArch is the currently open app
        val appOpenDumpsys = """
            ACTIVITY MANAGER RECENT TASKS (dumpsys activity recents)
            * Recent #0: Task{41ea720 #32 type=standard I=com.retroarch.aarch64/.RetroActivity} userId=0
            * Recent #1: Task{91ab341 #45 type=standard I=xyz.aethersx2.android/.MainActivity} userId=0
        """.trimIndent()

        val pattern = Pattern.compile("\\*\\s+Recent\\s+#\\d+:\\s+Task\\{[0-9a-fA-F]+\\s+#(\\d+)\\s+.*?I=([^/\\s\\}]+)(?:/([^\\s\\}]+))?")
        var currentRunningTaskId: Int? = null
        val peekMatcher = pattern.matcher(appOpenDumpsys)
        while (peekMatcher.find()) {
            val fullMatch = peekMatcher.group(0) ?: ""
            val pkg = peekMatcher.group(2) ?: ""
            val taskId = peekMatcher.group(1)?.toIntOrNull() ?: continue
            if (pkg == "com.example.consoleswitcher3") continue

            if (fullMatch.contains("type=home") || com.example.consoleswitcher3.task.TaskManager.isHomeOrLauncher(pkg, null, emptySet())) {
                currentRunningTaskId = -1
            } else {
                currentRunningTaskId = taskId
            }
            break
        }

        assertEquals(32, currentRunningTaskId)

        // Dumpsys when on Home screen (RetroArch is backgrounded)
        val homeOpenDumpsys = """
            ACTIVITY MANAGER RECENT TASKS (dumpsys activity recents)
            * Recent #0: Task{41ea720 #1 type=home I=com.android.launcher3/.uioverrides.QuickstepLauncher} userId=0
            * Recent #1: Task{91ab341 #32 type=standard I=com.retroarch.aarch64/.RetroActivity} userId=0
        """.trimIndent()

        var homeRunningTaskId: Int? = null
        val homePeekMatcher = pattern.matcher(homeOpenDumpsys)
        while (homePeekMatcher.find()) {
            val fullMatch = homePeekMatcher.group(0) ?: ""
            val pkg = homePeekMatcher.group(2) ?: ""
            val taskId = homePeekMatcher.group(1)?.toIntOrNull() ?: continue
            if (pkg == "com.example.consoleswitcher3") continue

            if (fullMatch.contains("type=home") || com.example.consoleswitcher3.task.TaskManager.isHomeOrLauncher(pkg, null, setOf("com.android.launcher3"))) {
                homeRunningTaskId = -1
            } else {
                homeRunningTaskId = taskId
            }
            break
        }

        assertEquals(-1, homeRunningTaskId)
    }

    @Test
    fun testParseTopTaskFromDumpsysTop() {
        val homePackages = setOf("com.android.launcher3")

        // 1. When user is at Home (OverlayActivity on top, Launcher beneath it)
        val dumpsysTopAtHome = """
            ACTIVITY com.example.consoleswitcher3/.OverlayActivity 41a2b3 c0 ...
            TASK com.android.launcher3 id=1
              ACTIVITY com.android.launcher3/.uioverrides.QuickstepLauncher 99b112 ...
        """.trimIndent()

        val homeResult = com.example.consoleswitcher3.task.TaskManager.parseTopTaskFromDumpsysTop(
            dumpsysTopAtHome,
            "com.example.consoleswitcher3",
            homePackages
        )
        assertEquals(-1, homeResult)

        // 2. When user is inside an app (OverlayActivity on top, RetroArch beneath it)
        val dumpsysTopInApp = """
            ACTIVITY com.example.consoleswitcher3/.OverlayActivity 41a2b3 c0 ...
            TASK com.retroarch.aarch64 id=32
              ACTIVITY com.retroarch.aarch64/.RetroActivity 12f5a8 ...
        """.trimIndent()

        val appResult = com.example.consoleswitcher3.task.TaskManager.parseTopTaskFromDumpsysTop(
            dumpsysTopInApp,
            "com.example.consoleswitcher3",
            homePackages
        )
        assertEquals(32, appResult)
    }
}
