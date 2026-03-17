package com.appdpadtester

import android.view.KeyEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import com.appdpadtester.utils.TestHelper
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Advanced D-pad scenarios that stress-test real-world TV/STB usage.
 *
 * Covers: nested scroll containers, grid layouts, focus memory across tabs,
 * long-press, TV remote keys (channel, media, menu), popups, deep navigation,
 * animation resilience, rotation, and scroll position preservation.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class DpadAdvancedTest {

    private lateinit var device: UiDevice
    private var screenW = 0
    private var screenH = 0
    private val p = TestHelper.screenPrefix()

    @Before
    fun setUp() {
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val launched = TestHelper.launchApp(device)
        assertTrue("Failed to launch target app", launched)
        TestHelper.waitForIdle(device, 3000)
        val dims = TestHelper.getScreenDimensions(device)
        screenW = dims.first
        screenH = dims.second
    }

    // ================================================================
    // 1. NESTED SCROLL CONTAINERS
    // ================================================================

    @Test
    fun nested_scrollContainers_focusMovesCorrectly() {
        println("${p}[AUDIT] === Nested Scroll Container Analysis ===")

        val scrollables = TestHelper.getScrollableContainers(device)
        println("${p}[AUDIT] Scrollable containers found: ${scrollables.size}")

        if (scrollables.isEmpty()) {
            println("${p}[AUDIT] No scrollable containers — skipping nested scroll test")
            return
        }

        for ((idx, container) in scrollables.withIndex()) {
            val b = container.visibleBounds
            val id = container.resourceName ?: "scrollable-$idx"
            println("${p}[AUDIT] Container '$id': ${b.width()}x${b.height()} at (${b.left},${b.top})")
        }

        // Navigate into a scrollable area and test vertical movement
        var enteredScroll = false
        for (i in 1..15) {
            TestHelper.pressDown(device, 250)
            val focused = TestHelper.getFocusedElement(device)
            if (focused != null) {
                val fb = focused.visibleBounds
                // Check if focus is inside any scrollable container
                for (container in scrollables) {
                    val cb = container.visibleBounds
                    if (fb.left >= cb.left && fb.right <= cb.right &&
                        fb.top >= cb.top && fb.bottom <= cb.bottom) {
                        if (!enteredScroll) {
                            println("${p}[AUDIT] Focus entered scrollable container at step $i")
                            enteredScroll = true
                        }
                    }
                }
            }
        }

        // Try to exit the scrollable container
        val beforeExit = TestHelper.getFocusedBounds(device)
        TestHelper.pressDown(device, 300)
        TestHelper.pressDown(device, 300)
        TestHelper.pressDown(device, 300)
        val afterExit = TestHelper.getFocusedBounds(device)

        if (beforeExit != null && afterExit != null && beforeExit != afterExit) {
            println("${p}[OK] Focus can move beyond scrollable container boundaries")
        } else if (enteredScroll) {
            println("${p}[WARN] Focus may be trapped inside scrollable container — check nextFocusDown on last item")
        }

        // Test horizontal exit from scrollable
        val beforeRight = TestHelper.getFocusedElementId(device)
        TestHelper.pressRight(device, 300)
        val afterRight = TestHelper.getFocusedElementId(device)

        if (beforeRight != afterRight) {
            println("${p}[OK] D-pad Right exits/moves within scrollable container")
        } else {
            println("${p}[AUDIT] D-pad Right has no effect inside scrollable (may be expected for vertical-only list)")
        }

        TestHelper.takeScreenshot(device, "nested_scroll")
    }

    // ================================================================
    // 2. GRID LAYOUT NAVIGATION
    // ================================================================

    @Test
    fun gridLayout_horizontalAndVerticalNavigation() {
        println("${p}[AUDIT] === Grid Layout Navigation ===")

        val gridRows = TestHelper.detectGridLayout(device)

        if (gridRows.isEmpty()) {
            println("${p}[AUDIT] No grid layout detected on current screen — skipping")
            return
        }

        println("${p}[AUDIT] Grid detected: ${gridRows.size} row(s)")
        for ((rowIdx, row) in gridRows.withIndex()) {
            val labels = row.map { it.text ?: it.resourceName?.substringAfterLast('/') ?: "?" }
            println("${p}[AUDIT]   Row $rowIdx: ${row.size} items — [${labels.joinToString(", ")}]")
        }

        // Navigate to the first grid element
        TestHelper.launchApp(device)
        TestHelper.waitForIdle(device, 2000)

        // Find grid area — navigate until we reach a grid row element
        var inGrid = false
        for (i in 1..20) {
            TestHelper.pressDown(device, 250)
            val focused = TestHelper.getFocusedElement(device)
            if (focused != null) {
                for (row in gridRows) {
                    if (row.any { it.resourceName == focused.resourceName && focused.resourceName != null }) {
                        inGrid = true
                        break
                    }
                }
            }
            if (inGrid) break
        }

        if (!inGrid) {
            println("${p}[AUDIT] Could not navigate into grid via D-pad — grid may not be D-pad accessible")
            return
        }

        // Test horizontal navigation within grid row
        val rowPositions = mutableSetOf<String>()
        val startId = TestHelper.getFocusedElementId(device)
        if (startId != null) rowPositions.add(startId)

        for (i in 1..10) {
            TestHelper.pressRight(device, 250)
            TestHelper.getFocusedElementId(device)?.let { rowPositions.add(it) }
        }
        println("${p}[AUDIT] Horizontal grid navigation: reached ${rowPositions.size} unique positions")

        // Test row-to-row navigation
        val startBounds = TestHelper.getFocusedBounds(device)
        TestHelper.pressDown(device, 300)
        val nextRowBounds = TestHelper.getFocusedBounds(device)

        if (startBounds != null && nextRowBounds != null) {
            val movedToNewRow = nextRowBounds.centerY() > startBounds.centerY() + 10
            if (movedToNewRow) {
                println("${p}[OK] D-pad Down moves to next grid row")
                // Check column alignment
                val xDrift = Math.abs(nextRowBounds.centerX() - startBounds.centerX())
                if (xDrift < screenW / 6) {
                    println("${p}[OK] Column position preserved across rows (drift: ${xDrift}px)")
                } else {
                    println("${p}[WARN] Column position NOT preserved across rows (drift: ${xDrift}px) — focus jumped to a different column")
                }
            } else {
                println("${p}[WARN] D-pad Down didn't move to next row — may stay on same row")
            }
        }

        TestHelper.takeScreenshot(device, "grid_nav")
    }

    // ================================================================
    // 3. FOCUS MEMORY AFTER TAB SWITCH
    // ================================================================

    @Test
    fun focusMemory_afterTabSwitch() {
        println("${p}[AUDIT] === Focus Memory (Tab Switch) ===")

        // Navigate down on first tab to establish a position
        TestHelper.pressDown(device, 300)
        TestHelper.pressDown(device, 300)
        TestHelper.pressDown(device, 300)
        val positionOnTab1 = TestHelper.getFocusedElementId(device)
        val boundsOnTab1 = TestHelper.getFocusedBounds(device)
        val labelOnTab1 = TestHelper.getFocusedLabel(device)

        println("${p}[AUDIT] Tab 1 position: '$labelOnTab1'")

        // Switch to another tab
        TestHelper.pressRight(device, 300)
        TestHelper.pressRight(device, 300)
        TestHelper.pressRight(device, 300)

        val onTab2 = TestHelper.getFocusedElementId(device)
        if (onTab2 == positionOnTab1) {
            println("${p}[AUDIT] Right presses didn't change tab — may not have tab navigation")
            return
        }

        // Navigate down on tab 2
        TestHelper.pressDown(device, 300)
        TestHelper.pressDown(device, 300)
        val labelOnTab2 = TestHelper.getFocusedLabel(device)
        println("${p}[AUDIT] Tab 2 position: '$labelOnTab2'")

        // Switch back to tab 1
        TestHelper.pressLeft(device, 300)
        TestHelper.pressLeft(device, 300)
        TestHelper.pressLeft(device, 300)

        val returnedPosition = TestHelper.getFocusedElementId(device)
        val returnedBounds = TestHelper.getFocusedBounds(device)
        val returnedLabel = TestHelper.getFocusedLabel(device)

        println("${p}[AUDIT] After returning to tab 1: '$returnedLabel'")

        if (returnedPosition == positionOnTab1) {
            println("${p}[OK] Focus position remembered perfectly after tab switch")
        } else if (boundsOnTab1 != null && returnedBounds != null) {
            val yDiff = Math.abs(returnedBounds.centerY() - boundsOnTab1.centerY())
            if (yDiff < screenH / 4) {
                println("${p}[OK] Focus returned to approximately the same position (${yDiff}px off)")
            } else {
                println("${p}[WARN] Focus NOT remembered after tab switch — returned to '$returnedLabel' instead of '$labelOnTab1'")
                println("${p}[WARN] Fix: save focus position per tab and restore with requestFocus() on tab switch")
            }
        } else {
            println("${p}[WARN] Could not verify focus memory — positions may have changed")
        }
    }

    // ================================================================
    // 4. LONG-PRESS CENTER BUTTON
    // ================================================================

    @Test
    fun longPress_centerButton() {
        println("${p}[AUDIT] === Long-Press Center/OK Analysis ===")

        val focused = TestHelper.getFocusedElement(device)
        if (focused == null) {
            println("${p}[ISSUE] No focused element to test long-press on")
            return
        }

        val label = TestHelper.getFocusedLabel(device)
        val hierarchyBefore = TestHelper.dumpHierarchy(device)
        val packageBefore = device.currentPackageName

        // Short press first — record what it does
        TestHelper.pressCenter(device)
        TestHelper.waitForIdle(device, 1500)
        val hierarchyAfterShort = TestHelper.dumpHierarchy(device)
        val shortPressEffect = hierarchyBefore != hierarchyAfterShort

        // Restore state
        if (shortPressEffect) {
            TestHelper.pressBack(device)
            TestHelper.waitForIdle(device, 1000)
        }
        if (!TestHelper.isAppInForeground(device)) {
            TestHelper.launchApp(device)
            TestHelper.waitForIdle(device, 2000)
        }

        // Navigate back to same element
        TestHelper.pressDown(device)

        // Now try long-press
        val hierarchyBeforeLong = TestHelper.dumpHierarchy(device)
        TestHelper.pressLongCenter(device, 1500)
        TestHelper.waitForIdle(device, 2000)
        val hierarchyAfterLong = TestHelper.dumpHierarchy(device)
        val longPressEffect = hierarchyBeforeLong != hierarchyAfterLong

        val appAlive = TestHelper.isAppInForeground(device) || device.currentPackageName == packageBefore

        if (!appAlive) {
            println("${p}[ISSUE] App crashed during long-press on '$label' — handle long-press events gracefully")
            TestHelper.launchApp(device)
            TestHelper.waitForIdle(device, 2000)
            return
        }

        println("${p}[AUDIT] Short press on '$label': ${if (shortPressEffect) "had effect" else "no effect"}")
        println("${p}[AUDIT] Long press on '$label': ${if (longPressEffect) "had effect" else "no effect"}")

        if (longPressEffect && shortPressEffect) {
            println("${p}[OK] Both short and long press produce different actions — good UX")
        } else if (!longPressEffect && shortPressEffect) {
            println("${p}[AUDIT] Long press has no additional effect — acceptable (not all elements need long-press)")
        } else if (longPressEffect && !shortPressEffect) {
            println("${p}[WARN] Long press works but short press doesn't — unusual, consider adding short-press handler")
        }

        // Restore
        if (longPressEffect) {
            TestHelper.pressBack(device)
            TestHelper.waitForIdle(device, 1000)
        }
    }

    // ================================================================
    // 5. CHANNEL UP/DOWN KEYS
    // ================================================================

    @Test
    fun channelUpDown_keys() {
        println("${p}[AUDIT] === Channel Up/Down Key Analysis ===")

        val hierarchyBefore = TestHelper.dumpHierarchy(device)
        val focusBefore = TestHelper.getFocusedElementId(device)

        // Test Channel Up
        TestHelper.pressChannelUp(device, 500)
        val hierarchyAfterUp = TestHelper.dumpHierarchy(device)
        val focusAfterUp = TestHelper.getFocusedElementId(device)
        val chUpEffect = hierarchyBefore != hierarchyAfterUp || focusBefore != focusAfterUp

        println("${p}[AUDIT] Channel Up: ${if (chUpEffect) "handled (UI changed)" else "no effect"}")

        // Restore if needed
        if (hierarchyBefore != hierarchyAfterUp) {
            TestHelper.pressBack(device)
            TestHelper.waitForIdle(device, 1000)
        }

        // Test Channel Down
        val hierarchyBeforeDown = TestHelper.dumpHierarchy(device)
        TestHelper.pressChannelDown(device, 500)
        val hierarchyAfterDown = TestHelper.dumpHierarchy(device)
        val focusAfterDown = TestHelper.getFocusedElementId(device)
        val chDownEffect = hierarchyBeforeDown != hierarchyAfterDown

        println("${p}[AUDIT] Channel Down: ${if (chDownEffect) "handled (UI changed)" else "no effect"}")

        val appAlive = TestHelper.isAppInForeground(device)
        if (!appAlive) {
            println("${p}[ISSUE] App crashed or exited on Channel key press")
            TestHelper.launchApp(device)
            TestHelper.waitForIdle(device, 2000)
        } else {
            println("${p}[OK] App handles Channel keys without crashing")
        }

        if (!chUpEffect && !chDownEffect) {
            println("${p}[AUDIT] Channel keys have no effect — acceptable if not a TV channel app")
        }
    }

    // ================================================================
    // 6. MEDIA KEYS
    // ================================================================

    @Test
    fun mediaKeys_playPauseStopRewind() {
        println("${p}[AUDIT] === Media Key Analysis ===")

        data class KeyResult(val name: String, val hadEffect: Boolean, val crashed: Boolean)
        val results = mutableListOf<KeyResult>()

        val mediaKeys = listOf(
            Triple("Play/Pause", KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, 500L),
            Triple("Stop", KeyEvent.KEYCODE_MEDIA_STOP, 500L),
            Triple("Rewind", KeyEvent.KEYCODE_MEDIA_REWIND, 500L),
            Triple("Fast Forward", KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, 500L)
        )

        for ((name, keyCode, settle) in mediaKeys) {
            val before = TestHelper.dumpHierarchy(device)
            TestHelper.pressKey(device, keyCode, settle)
            val after = TestHelper.dumpHierarchy(device)
            val alive = TestHelper.isAppInForeground(device)

            results.add(KeyResult(name, before != after, !alive))

            if (!alive) {
                println("${p}[ISSUE] App crashed on $name key!")
                TestHelper.launchApp(device)
                TestHelper.waitForIdle(device, 2000)
            } else if (before != after) {
                TestHelper.pressBack(device)
                TestHelper.waitForIdle(device, 500)
            }
        }

        for (r in results) {
            val status = when {
                r.crashed -> "[ISSUE]"
                r.hadEffect -> "[OK]"
                else -> "[AUDIT]"
            }
            val desc = when {
                r.crashed -> "CRASHED"
                r.hadEffect -> "handled"
                else -> "no effect"
            }
            println("${p}$status Media ${r.name}: $desc")
        }

        val crashes = results.count { it.crashed }
        if (crashes == 0) {
            println("${p}[OK] All media keys handled without crashing")
        } else {
            println("${p}[ISSUE] $crashes media key(s) caused crashes — add key event handlers")
        }
    }

    // ================================================================
    // 7. MENU KEY
    // ================================================================

    @Test
    fun menuKey_opensMenu() {
        println("${p}[AUDIT] === Menu Key Analysis ===")

        val hierarchyBefore = TestHelper.dumpHierarchy(device)
        TestHelper.pressMenu(device, 800)
        val hierarchyAfter = TestHelper.dumpHierarchy(device)

        val hadEffect = hierarchyBefore != hierarchyAfter
        val alive = TestHelper.isAppInForeground(device)

        if (!alive) {
            println("${p}[ISSUE] App crashed on Menu key press")
            TestHelper.launchApp(device)
            TestHelper.waitForIdle(device, 2000)
            return
        }

        if (hadEffect) {
            val hasFocus = TestHelper.getFocusedElement(device) != null
            println("${p}[OK] Menu key opens a menu/overlay")
            if (hasFocus) {
                println("${p}[OK] Menu has focus set — navigable via D-pad")
            } else {
                println("${p}[ISSUE] Menu opened but has NO FOCUS — D-pad users can't navigate it")
            }

            TestHelper.pressBack(device)
            TestHelper.waitForIdle(device, 500)
        } else {
            println("${p}[AUDIT] Menu key has no effect — acceptable if no options menu exists")
        }
    }

    // ================================================================
    // 8. FOCUS AFTER POPUP/TOAST/SNACKBAR
    // ================================================================

    @Test
    fun focusAfterPopup_toast_snackbar() {
        println("${p}[AUDIT] === Focus Persistence After Popup ===")

        val focusBefore = TestHelper.getFocusedElementId(device)
        val labelBefore = TestHelper.getFocusedLabel(device)

        // Trigger an action that might show a toast/snackbar
        TestHelper.pressCenter(device)
        TestHelper.waitForIdle(device, 1000)

        // Check if we're on a new screen or just got a toast
        val hierarchyChanged = TestHelper.dumpHierarchy(device) != TestHelper.dumpHierarchy(device)

        // Wait for toast/snackbar to disappear
        Thread.sleep(3500)
        TestHelper.waitForIdle(device, 1000)

        val focusAfter = TestHelper.getFocusedElementId(device)
        val labelAfter = TestHelper.getFocusedLabel(device)

        if (focusAfter == null) {
            println("${p}[ISSUE] Focus LOST after popup/action — no element has focus")
            println("${p}[ISSUE] Fix: ensure toast/snackbar doesn't steal or destroy focus")
        } else if (focusBefore == focusAfter) {
            println("${p}[OK] Focus preserved after action ('$labelAfter')")
        } else {
            println("${p}[AUDIT] Focus changed from '$labelBefore' to '$labelAfter' (may be expected if action navigated)")
        }

        // Restore
        TestHelper.pressBack(device)
        TestHelper.waitForIdle(device, 1000)
        if (!TestHelper.isAppInForeground(device)) {
            TestHelper.launchApp(device)
            TestHelper.waitForIdle(device, 2000)
        }
    }

    // ================================================================
    // 9. DEEP NESTED NAVIGATION (5 LEVELS)
    // ================================================================

    @Test
    fun deepNestedNavigation_5levels() {
        println("${p}[AUDIT] === Deep Navigation (5 Levels) ===")

        val depths = mutableListOf<String>()
        depths.add("Launch: ${TestHelper.getFocusedLabel(device)}")
        var maxDepth = 0

        for (level in 1..5) {
            // Navigate down to find something to enter
            TestHelper.pressDown(device, 250)
            TestHelper.pressDown(device, 250)

            val before = TestHelper.dumpHierarchy(device)
            TestHelper.pressCenter(device)
            TestHelper.waitForIdle(device, 2000)
            val after = TestHelper.dumpHierarchy(device)

            if (before == after) {
                println("${p}[AUDIT] Navigation stopped at depth $level — no deeper screen")
                break
            }

            maxDepth = level
            val hasFocus = TestHelper.getFocusedElement(device) != null
            val label = TestHelper.getFocusedLabel(device)
            depths.add("Depth $level: '$label' (focus: $hasFocus)")

            if (!hasFocus) {
                println("${p}[ISSUE] No focus at depth $level — D-pad users stranded")
            }

            if (!TestHelper.isAppInForeground(device)) {
                println("${p}[ISSUE] App exited at depth $level")
                TestHelper.launchApp(device)
                TestHelper.waitForIdle(device, 2000)
                break
            }
        }

        println("${p}[AUDIT] Reached depth: $maxDepth")
        for (d in depths) {
            println("${p}[AUDIT]   $d")
        }

        // Unwind with Back
        println("${p}[AUDIT] Unwinding with Back:")
        for (level in maxDepth downTo 1) {
            TestHelper.pressBack(device)
            TestHelper.waitForIdle(device, 1500)

            val alive = TestHelper.isAppInForeground(device)
            val hasFocus = TestHelper.getFocusedElement(device) != null

            if (!alive) {
                println("${p}[ISSUE] App exited during Back at depth $level (should return to previous screen)")
                TestHelper.launchApp(device)
                TestHelper.waitForIdle(device, 2000)
                break
            }

            if (!hasFocus) {
                println("${p}[ISSUE] Focus lost after Back from depth $level")
            } else {
                println("${p}[OK] Back from depth $level: focus present ('${TestHelper.getFocusedLabel(device)}')")
            }
        }
    }

    // ================================================================
    // 10. FOCUS DURING ANIMATION/TRANSITION
    // ================================================================

    @Test
    fun focusDuringAnimation() {
        println("${p}[AUDIT] === Focus During Animation ===")

        // Trigger a transition
        val hierarchyBefore = TestHelper.dumpHierarchy(device)
        device.pressKeyCode(KeyEvent.KEYCODE_DPAD_CENTER)

        // Immediately press D-pad during transition (no wait!)
        Thread.sleep(100) // tiny delay to let transition start
        device.pressKeyCode(KeyEvent.KEYCODE_DPAD_DOWN)
        Thread.sleep(50)
        device.pressKeyCode(KeyEvent.KEYCODE_DPAD_DOWN)
        Thread.sleep(50)
        device.pressKeyCode(KeyEvent.KEYCODE_DPAD_RIGHT)

        // Now wait for everything to settle
        TestHelper.waitForIdle(device, 3000)

        val alive = TestHelper.isAppInForeground(device)
        if (!alive) {
            println("${p}[ISSUE] App crashed when D-pad pressed during animation/transition")
            TestHelper.launchApp(device)
            TestHelper.waitForIdle(device, 2000)
            return
        }

        val hasFocus = TestHelper.getFocusedElement(device) != null
        if (hasFocus) {
            println("${p}[OK] Focus is correct after D-pad input during animation")
        } else {
            println("${p}[WARN] Focus lost after D-pad input during animation — transitions may not handle concurrent input well")
        }

        // Restore
        TestHelper.pressBack(device)
        TestHelper.waitForIdle(device, 1000)
        if (!TestHelper.isAppInForeground(device)) {
            TestHelper.launchApp(device)
            TestHelper.waitForIdle(device, 2000)
        }
    }

    // ================================================================
    // 11. FOCUS AFTER SCREEN ROTATION
    // ================================================================

    @Test
    fun dpad_afterScreenRotation() {
        println("${p}[AUDIT] === D-pad After Screen Rotation ===")

        // Record focus before rotation
        val focusBefore = TestHelper.getFocusedElementId(device)
        val labelBefore = TestHelper.getFocusedLabel(device)
        val boundsBefore = TestHelper.getFocusedBounds(device)

        println("${p}[AUDIT] Before rotation: '$labelBefore'")

        // Rotate to landscape
        try {
            device.setOrientationLeft()
            TestHelper.waitForIdle(device, 3000)
        } catch (e: Exception) {
            println("${p}[AUDIT] Rotation not supported or failed — skipping")
            return
        }

        val alive = TestHelper.isAppInForeground(device)
        if (!alive) {
            println("${p}[ISSUE] App crashed on rotation")
            device.setOrientationNatural()
            TestHelper.launchApp(device)
            TestHelper.waitForIdle(device, 2000)
            return
        }

        val focusAfterRotation = TestHelper.getFocusedElement(device) != null
        if (!focusAfterRotation) {
            println("${p}[ISSUE] Focus LOST after rotation — save and restore focus in onSaveInstanceState/onRestoreInstanceState")
        } else {
            println("${p}[OK] Focus present after rotation ('${TestHelper.getFocusedLabel(device)}')")
        }

        // Test D-pad works after rotation
        val beforeDpad = TestHelper.getFocusedElementId(device)
        TestHelper.pressDown(device, 300)
        val afterDpad = TestHelper.getFocusedElementId(device)

        if (beforeDpad != afterDpad || afterDpad != null) {
            println("${p}[OK] D-pad navigation works after rotation")
        } else {
            println("${p}[ISSUE] D-pad navigation broken after rotation")
        }

        // Rotate back
        device.setOrientationNatural()
        TestHelper.waitForIdle(device, 2000)

        if (!TestHelper.isAppInForeground(device)) {
            TestHelper.launchApp(device)
            TestHelper.waitForIdle(device, 2000)
        }
    }

    // ================================================================
    // 12. SCROLL POSITION PRESERVATION
    // ================================================================

    @Test
    fun scrollPositionPreservation() {
        println("${p}[AUDIT] === Scroll Position Preservation ===")

        // Scroll down a long list
        val startBounds = TestHelper.getFocusedBounds(device)
        for (i in 1..15) {
            TestHelper.pressDown(device, 200)
        }

        val scrolledLabel = TestHelper.getFocusedLabel(device)
        val scrolledBounds = TestHelper.getFocusedBounds(device)
        val scrolledId = TestHelper.getFocusedElementId(device)

        if (scrolledBounds == null || startBounds == null) {
            println("${p}[AUDIT] Could not track scroll position — skipping")
            return
        }

        println("${p}[AUDIT] Scrolled to: '$scrolledLabel' at Y=${scrolledBounds.centerY()}")

        // Select the item
        TestHelper.pressCenter(device)
        TestHelper.waitForIdle(device, 2000)

        val navigated = TestHelper.dumpHierarchy(device) != TestHelper.dumpHierarchy(device)

        // Go back
        TestHelper.pressBack(device)
        TestHelper.waitForIdle(device, 2000)

        if (!TestHelper.isAppInForeground(device)) {
            TestHelper.launchApp(device)
            TestHelper.waitForIdle(device, 2000)
            println("${p}[WARN] App exited on Back — can't verify scroll preservation")
            return
        }

        val returnedId = TestHelper.getFocusedElementId(device)
        val returnedBounds = TestHelper.getFocusedBounds(device)
        val returnedLabel = TestHelper.getFocusedLabel(device)

        if (returnedId == scrolledId) {
            println("${p}[OK] Scroll position AND focus perfectly preserved after Back ('$returnedLabel')")
        } else if (returnedBounds != null) {
            val yDiff = Math.abs(returnedBounds.centerY() - scrolledBounds.centerY())
            if (yDiff < screenH / 3) {
                println("${p}[OK] Scroll position approximately preserved (${yDiff}px off, focus on '$returnedLabel')")
            } else {
                println("${p}[WARN] Scroll position NOT preserved — returned to '$returnedLabel' (${yDiff}px from original)")
                println("${p}[WARN] Fix: save scroll position + focused item ID in onPause(), restore in onResume()")
            }
        } else {
            println("${p}[ISSUE] Focus lost after Back from selected item")
        }
    }
}
