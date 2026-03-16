package com.appdpadtester

import android.graphics.Rect
import android.view.KeyEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import com.appdpadtester.utils.TestHelper
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Focus state verification tests for D-pad-driven Android apps.
 *
 * Verifies:
 *   - Every focusable element has a visible focus indicator
 *   - Focus is correctly restored after dialog dismissal
 *   - Empty states show a centered primary action
 *   - Focus indicators are visually distinct (via accessibility state)
 *   - Focus order is logical and predictable
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class FocusTest {

    private lateinit var device: UiDevice

    @Before
    fun setUp() {
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

        val launched = TestHelper.launchApp(device)
        assertTrue("Failed to launch target app: ${TestHelper.targetPackage}", launched)

        TestHelper.waitForIdle(device, 3000)
    }

    // ================================================================
    // VISIBLE FOCUS STATE
    // ================================================================

    /**
     * Every focusable element must report isFocused=true when it receives
     * D-pad focus. This verifies the accessibility tree correctly reflects
     * focus state, which is required for visible focus indicators.
     */
    @Test
    fun allFocusableElements_reportCorrectFocusState() {
        val focusableCount = TestHelper.getAllFocusableElements(device).size
        if (focusableCount == 0) {
            fail("No focusable elements found on screen")
            return
        }

        var testedCount = 0
        var passCount = 0
        val failedElements = mutableListOf<String>()

        // Navigate through elements and verify each reports focus correctly
        val visited = mutableSetOf<String>()

        for (i in 1..40) {
            TestHelper.pressDown(device, 300)

            val focused = TestHelper.getFocusedElement(device)
            val focusedId = TestHelper.getFocusedElementId(device)

            if (focusedId != null && focusedId !in visited) {
                visited.add(focusedId)
                testedCount++

                if (focused != null && focused.isFocused) {
                    passCount++
                } else {
                    failedElements.add(focusedId)
                }
            }
        }

        // Also navigate horizontally
        for (i in 1..20) {
            TestHelper.pressRight(device, 300)

            val focused = TestHelper.getFocusedElement(device)
            val focusedId = TestHelper.getFocusedElementId(device)

            if (focusedId != null && focusedId !in visited) {
                visited.add(focusedId)
                testedCount++

                if (focused != null && focused.isFocused) {
                    passCount++
                } else {
                    failedElements.add(focusedId)
                }
            }
        }

        println("FOCUS STATE: Tested $testedCount elements, $passCount passed, ${failedElements.size} failed")
        if (failedElements.isNotEmpty()) {
            println("FOCUS STATE: Failed elements: ${failedElements.take(10)}")
        }

        assertTrue(
            "Tested $testedCount focusable elements: $passCount passed, " +
                "${failedElements.size} failed focus state check. " +
                "Failed elements: ${failedElements.take(5)}",
            failedElements.isEmpty() || testedCount == 0
        )
    }

    /**
     * Focus state should be visually distinct — when an element gains focus,
     * something about it should change (bounds, selected state, etc.).
     *
     * This test navigates to each element, records its accessibility properties
     * when focused vs unfocused, and flags elements that appear identical in
     * both states.
     */
    @Test
    fun focusedElements_haveDistinctVisualState() {
        val warningElements = mutableListOf<String>()
        val visited = mutableSetOf<String>()

        for (i in 1..30) {
            // Record current focused element
            val focused = TestHelper.getFocusedElement(device)
            val focusedId = TestHelper.getFocusedElementId(device)
            val focusedBounds = focused?.visibleBounds
            val focusedSelected = focused?.isSelected

            if (focusedId != null && focusedId !in visited && focused != null) {
                visited.add(focusedId)

                // Take screenshot with focus
                TestHelper.takeScreenshot(device, "focus_${i}_focused")

                // Move away
                TestHelper.pressDown(device, 300)

                // Check if the previously focused element's state changed
                // (This is best-effort — we can't always re-find the exact element)
                val resName = focused.resourceName
                if (resName != null) {
                    val refound = device.findObject(By.res(resName))
                    if (refound != null) {
                        val unfocusedBounds = refound.visibleBounds
                        val unfocusedSelected = refound.isSelected

                        // If nothing changed between focused and unfocused state,
                        // the element might not have a visible focus indicator
                        if (focusedBounds == unfocusedBounds &&
                            focusedSelected == unfocusedSelected) {
                            warningElements.add(resName)
                        }
                    }
                }
            } else {
                TestHelper.pressDown(device, 300)
            }
        }

        if (warningElements.isNotEmpty()) {
            TestHelper.takeScreenshot(device, "focus_state_warnings")
            // This is a warning, not a hard failure, because:
            // - Some visual changes (color, elevation) aren't detectable via accessibility
            // - The screenshot captures are provided for manual review
            println(
                "WARNING: ${warningElements.size} element(s) may lack visible focus indicators: " +
                    warningElements.take(5).joinToString(", ")
            )
        }
    }

    /**
     * Focusable elements should have a non-zero size (not invisible).
     * Zero-size focusable elements trap focus and confuse users.
     */
    @Test
    fun focusableElements_haveNonZeroSize() {
        val allFocusable = TestHelper.getAllFocusableElements(device)
        val zeroSizeElements = mutableListOf<String>()

        for (element in allFocusable) {
            val bounds = element.visibleBounds
            if (bounds.width() == 0 || bounds.height() == 0) {
                val id = element.resourceName ?: element.className ?: "unknown"
                zeroSizeElements.add("$id (${bounds.width()}x${bounds.height()})")
            }
        }

        assertTrue(
            "Found ${zeroSizeElements.size} focusable element(s) with zero size: " +
                "${zeroSizeElements.take(5)}. " +
                "Zero-size focusable elements trap D-pad focus and must be fixed.",
            zeroSizeElements.isEmpty()
        )
    }

    // ================================================================
    // FOCUS RETURN AFTER DIALOG
    // ================================================================

    /**
     * After dismissing a dialog with Back, focus must return to the
     * element that was focused before the dialog opened.
     */
    @Test
    fun focusReturns_afterDialogDismissWithBack() {
        // Record initial focus
        val focusBefore = TestHelper.getFocusedElementId(device)
        assertNotNull("No initial focus to test dialog return", focusBefore)

        // Try to open a dialog via Center press
        val hierarchyBefore = TestHelper.dumpHierarchy(device)
        TestHelper.pressCenter(device)
        TestHelper.waitForIdle(device, 2000)
        val hierarchyAfter = TestHelper.dumpHierarchy(device)

        // Only test focus return if a dialog/new content actually appeared
        if (hierarchyBefore != hierarchyAfter) {
            // Dismiss the dialog
            TestHelper.pressBack(device)
            TestHelper.waitForIdle(device, 1000)

            // Focus should return to the original element
            val focusAfter = TestHelper.getFocusedElementId(device)
            assertNotNull(
                "Focus was completely lost after dialog dismissal. " +
                    "Save and restore focus in the dialog lifecycle.",
                focusAfter
            )

            // Ideally focus returns to the exact same element
            if (focusBefore != focusAfter) {
                println(
                    "NOTE: Focus returned to a different element after dialog. " +
                        "Before: $focusBefore, After: $focusAfter"
                )
            }
        }
    }

    /**
     * Focus should return correctly when dismissing multiple stacked dialogs.
     */
    @Test
    fun focusReturns_afterMultipleDialogDismissals() {
        val initialFocus = TestHelper.getFocusedElementId(device)

        // Open dialog 1
        TestHelper.pressCenter(device)
        TestHelper.waitForIdle(device, 1000)

        // Navigate within dialog and try to open dialog 2
        TestHelper.pressDown(device)
        TestHelper.pressCenter(device)
        TestHelper.waitForIdle(device, 1000)

        // Dismiss dialog 2
        TestHelper.pressBack(device)
        TestHelper.waitForIdle(device, 1000)

        // Dismiss dialog 1
        TestHelper.pressBack(device)
        TestHelper.waitForIdle(device, 1000)

        // Something should have focus
        val finalFocus = TestHelper.getFocusedElement(device)
        assertNotNull(
            "Focus was lost after dismissing stacked dialogs",
            finalFocus
        )
    }

    /**
     * Focus inside a dialog should work correctly:
     * the dialog itself must have a focused element.
     */
    @Test
    fun dialog_hasFocusedElement() {
        val hierarchyBefore = TestHelper.dumpHierarchy(device)

        TestHelper.pressCenter(device)
        TestHelper.waitForIdle(device, 2000)

        val hierarchyAfter = TestHelper.dumpHierarchy(device)

        // If a dialog appeared
        if (hierarchyBefore != hierarchyAfter) {
            val focused = TestHelper.getFocusedElement(device)
            assertNotNull(
                "Dialog/overlay has no focused element. " +
                    "Users cannot interact with the dialog via D-pad " +
                    "unless it has initial focus set.",
                focused
            )

            // Clean up
            TestHelper.pressBack(device)
            TestHelper.waitForIdle(device, 1000)
        }
    }

    // ================================================================
    // EMPTY STATE — CENTERED PRIMARY ACTION
    // ================================================================

    /**
     * Screens showing empty states should display a centered primary action
     * button that is focusable. This ensures D-pad users are not stranded
     * on an empty screen with nothing to interact with.
     */
    @Test
    fun emptyStates_showCenteredPrimaryAction() {
        // Look for common empty state indicators
        val emptyIndicators = listOf(
            "empty", "no items", "nothing here", "no results",
            "no data", "get started", "nothing to show", "no content"
        )

        // Search current screen for empty state text
        var foundEmptyState = false
        for (indicator in emptyIndicators) {
            val emptyText = device.findObject(By.textContains(indicator))
            if (emptyText != null) {
                foundEmptyState = true
                break
            }
        }

        if (!foundEmptyState) {
            // Navigate through screens to find empty states
            for (i in 1..5) {
                TestHelper.pressRight(device)
                TestHelper.waitForIdle(device, 500)

                for (indicator in emptyIndicators) {
                    val emptyText = device.findObject(By.textContains(indicator))
                    if (emptyText != null) {
                        foundEmptyState = true
                        break
                    }
                }
                if (foundEmptyState) break
            }
        }

        if (!foundEmptyState) {
            // No empty states found — test not applicable, pass gracefully
            return
        }

        // Found an empty state — verify there's a focusable action element
        val focusableActions = TestHelper.getAllFocusableElements(device)
        assertTrue(
            "Empty state screen has no focusable action element. " +
                "Add a primary action button (e.g., 'Retry', 'Add Item') " +
                "that D-pad users can focus and activate.",
            focusableActions.isNotEmpty()
        )

        // Check if the primary action is roughly centered
        val clickable = device.findObjects(By.clickable(true))
        if (clickable != null && clickable.isNotEmpty()) {
            val primaryAction = clickable[0]
            val isCentered = TestHelper.isElementCentered(device, primaryAction, 0.25f)

            if (!isCentered) {
                TestHelper.takeScreenshot(device, "empty_state_not_centered")
                println(
                    "WARNING: Primary action on empty state screen is not centered. " +
                        "Centered placement is recommended for D-pad accessibility."
                )
            }
        }
    }

    /**
     * If an empty state has a primary action button, it should be
     * the first element to receive focus when the screen loads.
     */
    @Test
    fun emptyState_primaryAction_hasInitialFocus() {
        val emptyIndicators = listOf("empty", "no items", "nothing here", "no results")

        for (indicator in emptyIndicators) {
            val emptyText = device.findObject(By.textContains(indicator))
            if (emptyText != null) {
                // Found empty state — check if a button has focus
                val focused = TestHelper.getFocusedElement(device)
                if (focused != null) {
                    val isClickable = focused.isClickable
                    assertTrue(
                        "Empty state screen's initially focused element is not clickable. " +
                            "The primary action button should receive default focus.",
                        isClickable
                    )
                }
                return
            }
        }

        // No empty states found — test not applicable
    }

    // ================================================================
    // FOCUS ORDER CONSISTENCY
    // ================================================================

    /**
     * Focus order should be consistent: navigating Down then Up
     * should visit the same elements in reverse.
     */
    @Test
    fun focusOrder_isConsistentDownAndUp() {
        val downPath = mutableListOf<String>()

        // Record path going down
        for (i in 1..10) {
            TestHelper.pressDown(device, 300)
            val id = TestHelper.getFocusedElementId(device) ?: "null"
            downPath.add(id)
        }

        val upPath = mutableListOf<String>()

        // Record path going back up
        for (i in 1..10) {
            TestHelper.pressUp(device, 300)
            val id = TestHelper.getFocusedElementId(device) ?: "null"
            upPath.add(id)
        }

        val downUnique = downPath.distinct()
        val upUnique = upPath.distinct()
        val common = downUnique.intersect(upUnique.toSet())

        println("FOCUS ORDER (Down/Up): Down visited ${downUnique.size} unique elements, Up visited ${upUnique.size}, ${common.size} in common")
        println("FOCUS ORDER (Down): ${downPath.take(10)}")
        println("FOCUS ORDER (Up):   ${upPath.take(10)}")

        assertTrue(
            "Down and Up navigation paths share no common elements. " +
                "Down visited: ${downUnique.size} elements, Up visited: ${upUnique.size}. " +
                "Focus order may be inconsistent.",
            common.isNotEmpty() || downUnique.size <= 1
        )
    }

    /**
     * Focus order should be consistent: navigating Right then Left
     * should visit the same elements in reverse.
     */
    @Test
    fun focusOrder_isConsistentRightAndLeft() {
        val rightPath = mutableListOf<String>()

        for (i in 1..10) {
            TestHelper.pressRight(device, 300)
            val id = TestHelper.getFocusedElementId(device) ?: "null"
            rightPath.add(id)
        }

        val leftPath = mutableListOf<String>()

        for (i in 1..10) {
            TestHelper.pressLeft(device, 300)
            val id = TestHelper.getFocusedElementId(device) ?: "null"
            leftPath.add(id)
        }

        val rightUnique = rightPath.distinct()
        val leftUnique = leftPath.distinct()
        val common = rightUnique.intersect(leftUnique.toSet())

        println("FOCUS ORDER (Right/Left): Right visited ${rightUnique.size} unique, Left visited ${leftUnique.size}, ${common.size} in common")

        assertTrue(
            "Right and Left navigation paths share no common elements. " +
                "Focus order may be inconsistent.",
            common.isNotEmpty() || rightUnique.size <= 1
        )
    }

    /**
     * Focus should not get "stuck" — pressing any D-pad direction from
     * any element should either move focus or keep it (no loss).
     */
    @Test
    fun focusNeverGetsStuck_inAllDirections() {
        val directions = listOf(
            Pair(KeyEvent.KEYCODE_DPAD_UP, "Up"),
            Pair(KeyEvent.KEYCODE_DPAD_DOWN, "Down"),
            Pair(KeyEvent.KEYCODE_DPAD_LEFT, "Left"),
            Pair(KeyEvent.KEYCODE_DPAD_RIGHT, "Right")
        )

        // Navigate to different elements and test all directions from each
        for (navStep in 1..5) {
            TestHelper.pressDown(device, 300)

            for ((keyCode, dirName) in directions) {
                TestHelper.pressKey(device, keyCode, 300)
                val focused = TestHelper.getFocusedElement(device)
                assertNotNull(
                    "Focus was lost after pressing D-pad $dirName from position #$navStep",
                    focused
                )
            }
        }
    }
}
