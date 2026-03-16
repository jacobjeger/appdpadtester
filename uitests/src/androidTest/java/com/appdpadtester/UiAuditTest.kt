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
 * UI Audit — comprehensive usability analysis for D-pad-driven apps.
 *
 * Unlike pass/fail tests, these audits print detailed findings about every
 * aspect of the UI. Each test outputs structured observations prefixed with
 * tags like [AUDIT], [WARN], [ISSUE], [OK] so the shell runner can parse
 * and present them as a human-readable report.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class UiAuditTest {

    private lateinit var device: UiDevice

    @Before
    fun setUp() {
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val launched = TestHelper.launchApp(device)
        assertTrue("Failed to launch target app", launched)
        TestHelper.waitForIdle(device, 3000)
    }

    // ================================================================
    // 1. LAYOUT & ELEMENT SIZING
    // ================================================================

    @Test
    fun audit_elementSizes() {
        println("[AUDIT] === Element Size Analysis ===")

        val allFocusable = TestHelper.getAllFocusableElements(device)
        val allClickable = TestHelper.getAllClickableElements(device)
        val (screenW, screenH) = TestHelper.getScreenDimensions(device)

        println("[AUDIT] Screen: ${screenW}x${screenH}")
        println("[AUDIT] Focusable elements: ${allFocusable.size}")
        println("[AUDIT] Clickable elements: ${allClickable.size}")

        // Recommended minimum touch/focus target: 48dp (roughly 48px at mdpi)
        val minTargetPx = 48
        var tooSmallCount = 0
        var tooNarrowCount = 0
        var zeroSizeCount = 0

        for (el in allFocusable) {
            val b = el.visibleBounds
            val w = b.width()
            val h = b.height()
            val id = el.resourceName ?: el.text ?: el.className ?: "unknown"

            if (w == 0 || h == 0) {
                zeroSizeCount++
                println("[ISSUE] Zero-size focusable element: $id (${w}x${h}) — traps D-pad focus")
            } else if (w < minTargetPx && h < minTargetPx) {
                tooSmallCount++
                println("[WARN] Small focus target: $id (${w}x${h}px) — recommended minimum 48x48")
            } else if (w < minTargetPx || h < minTargetPx) {
                tooNarrowCount++
                println("[WARN] Narrow focus target: $id (${w}x${h}px) — one dimension below 48px")
            }
        }

        if (zeroSizeCount == 0 && tooSmallCount == 0 && tooNarrowCount == 0) {
            println("[OK] All ${allFocusable.size} focusable elements meet minimum size requirements")
        } else {
            println("[AUDIT] Summary: $zeroSizeCount zero-size, $tooSmallCount too small, $tooNarrowCount too narrow")
        }

        // Check for overlapping focusable elements
        var overlapCount = 0
        for (i in allFocusable.indices) {
            for (j in i + 1 until allFocusable.size) {
                val a = allFocusable[i].visibleBounds
                val b = allFocusable[j].visibleBounds
                if (Rect.intersects(a, b)) {
                    val overlapArea = calculateOverlap(a, b)
                    val aArea = a.width() * a.height()
                    val bArea = b.width() * b.height()
                    val minArea = minOf(aArea, bArea)
                    // Only flag significant overlaps (>25% of smaller element)
                    if (minArea > 0 && overlapArea > minArea * 0.25) {
                        overlapCount++
                        val idA = allFocusable[i].resourceName ?: allFocusable[i].text ?: "?"
                        val idB = allFocusable[j].resourceName ?: allFocusable[j].text ?: "?"
                        if (overlapCount <= 5) {
                            println("[WARN] Overlapping focusable elements: '$idA' and '$idB' — may confuse D-pad navigation")
                        }
                    }
                }
            }
        }
        if (overlapCount > 5) {
            println("[WARN] ... and ${overlapCount - 5} more overlapping pairs")
        }
        if (overlapCount == 0) {
            println("[OK] No overlapping focusable elements detected")
        }

        TestHelper.takeScreenshot(device, "audit_layout")
    }

    // ================================================================
    // 2. TEXT READABILITY
    // ================================================================

    @Test
    fun audit_textReadability() {
        println("[AUDIT] === Text Readability Analysis ===")

        val hierarchy = TestHelper.dumpHierarchy(device)
        val (screenW, _) = TestHelper.getScreenDimensions(device)

        // Find all text-bearing elements
        val allText = device.findObjects(By.textLength(1, 10000)) ?: emptyList()
        println("[AUDIT] Text elements found: ${allText.size}")

        var truncatedCount = 0
        var tinyTextCount = 0
        var emptyButtonCount = 0

        for (el in allText) {
            val text = el.text ?: continue
            val bounds = el.visibleBounds
            val w = bounds.width()
            val h = bounds.height()

            // Check for likely truncated text (element very narrow relative to text length)
            if (text.length > 10 && w < text.length * 5) {
                truncatedCount++
                if (truncatedCount <= 3) {
                    println("[WARN] Possibly truncated text: '${text.take(30)}...' in ${w}px wide container")
                }
            }

            // Check for very small text containers (likely unreadable from couch distance)
            if (h < 20 && text.isNotEmpty()) {
                tinyTextCount++
                if (tinyTextCount <= 3) {
                    println("[WARN] Very small text element (${h}px tall): '${text.take(30)}'")
                }
            }
        }

        // Check for buttons/clickable elements with no text or content description
        val clickables = TestHelper.getAllClickableElements(device)
        for (el in clickables) {
            val hasText = !el.text.isNullOrBlank()
            val hasDesc = !el.contentDescription.isNullOrBlank()
            if (!hasText && !hasDesc) {
                emptyButtonCount++
                val id = el.resourceName ?: el.className ?: "unknown"
                if (emptyButtonCount <= 5) {
                    println("[ISSUE] Clickable element with no text or description: $id — inaccessible to screen readers and unclear for users")
                }
            }
        }

        if (truncatedCount > 3) println("[WARN] $truncatedCount total possibly truncated text elements")
        if (tinyTextCount > 3) println("[WARN] $tinyTextCount total very small text elements")
        if (emptyButtonCount > 5) println("[WARN] $emptyButtonCount total unlabeled clickable elements")

        if (truncatedCount == 0 && tinyTextCount == 0 && emptyButtonCount == 0) {
            println("[OK] All text elements appear readable and all buttons are labeled")
        }
    }

    // ================================================================
    // 3. CONTENT DESCRIPTIONS (ACCESSIBILITY)
    // ================================================================

    @Test
    fun audit_accessibility() {
        println("[AUDIT] === Accessibility Analysis ===")

        val allFocusable = TestHelper.getAllFocusableElements(device)
        var missingDescCount = 0
        var hasDescCount = 0

        for (el in allFocusable) {
            val hasText = !el.text.isNullOrBlank()
            val hasDesc = !el.contentDescription.isNullOrBlank()
            val id = el.resourceName ?: el.className ?: "unknown"

            if (hasText || hasDesc) {
                hasDescCount++
            } else {
                missingDescCount++
                if (missingDescCount <= 5) {
                    val className = el.className?.substringAfterLast('.') ?: "?"
                    println("[ISSUE] Focusable $className '$id' has no text or contentDescription")
                }
            }
        }

        if (missingDescCount > 5) {
            println("[ISSUE] ... and ${missingDescCount - 5} more elements missing descriptions")
        }

        val total = hasDescCount + missingDescCount
        val pct = if (total > 0) (hasDescCount * 100 / total) else 100
        println("[AUDIT] Accessibility coverage: $hasDescCount/$total elements labeled ($pct%)")

        if (pct >= 90) {
            println("[OK] Good accessibility coverage ($pct%)")
        } else if (pct >= 60) {
            println("[WARN] Moderate accessibility coverage ($pct%) — consider adding contentDescription to unlabeled elements")
        } else {
            println("[ISSUE] Poor accessibility coverage ($pct%) — many elements lack text/contentDescription")
        }
    }

    // ================================================================
    // 4. NAVIGATION EFFICIENCY
    // ================================================================

    @Test
    fun audit_navigationEfficiency() {
        println("[AUDIT] === Navigation Efficiency Analysis ===")

        val (screenW, screenH) = TestHelper.getScreenDimensions(device)

        // Measure how many D-pad presses to reach each screen region
        val regionFirstReach = mutableMapOf<String, Int>()
        var totalPresses = 0

        // Phase 1: Navigate down+right pattern
        for (i in 1..30) {
            TestHelper.pressDown(device, 200)
            totalPresses++
            recordRegionReach(device, screenW, screenH, totalPresses, regionFirstReach)
        }

        // Reset to top
        for (i in 1..30) {
            TestHelper.pressUp(device, 100)
        }
        totalPresses = 0

        // Phase 2: Navigate right+down pattern
        for (i in 1..30) {
            TestHelper.pressRight(device, 200)
            totalPresses++
            recordRegionReach(device, screenW, screenH, totalPresses, regionFirstReach)

            if (i % 5 == 0) {
                TestHelper.pressDown(device, 200)
                totalPresses++
                recordRegionReach(device, screenW, screenH, totalPresses, regionFirstReach)
            }
        }

        println("[AUDIT] Screen regions reachable via D-pad: ${regionFirstReach.size}/9")
        for ((region, presses) in regionFirstReach.entries.sortedBy { it.value }) {
            val efficiency = when {
                presses <= 3 -> "excellent"
                presses <= 8 -> "good"
                presses <= 15 -> "acceptable"
                else -> "slow"
            }
            println("[AUDIT]   $region: $presses presses ($efficiency)")
        }

        val unreached = listOf(
            "top-left", "top-center", "top-right",
            "middle-left", "middle-center", "middle-right",
            "bottom-left", "bottom-center", "bottom-right"
        ).filter { it !in regionFirstReach }

        if (unreached.isNotEmpty()) {
            println("[WARN] Unreachable screen regions: ${unreached.joinToString(", ")}")
        }

        // Check average presses to reach content
        val avgPresses = if (regionFirstReach.isNotEmpty()) {
            regionFirstReach.values.average()
        } else 0.0

        println("[AUDIT] Average D-pad presses to reach content: %.1f".format(avgPresses))
        when {
            avgPresses <= 5 -> println("[OK] Navigation is efficient")
            avgPresses <= 10 -> println("[WARN] Navigation could be more efficient — consider reorganizing layout")
            else -> println("[ISSUE] Navigation is slow (avg ${avgPresses.toInt()} presses) — too many presses to reach content")
        }
    }

    // ================================================================
    // 5. INITIAL FOCUS & FIRST IMPRESSION
    // ================================================================

    @Test
    fun audit_initialFocus() {
        println("[AUDIT] === Initial Focus & First Impression ===")

        val (screenW, screenH) = TestHelper.getScreenDimensions(device)
        val focused = TestHelper.getFocusedElement(device)

        if (focused == null) {
            println("[ISSUE] NO INITIAL FOCUS — the app launches with nothing focused")
            println("[ISSUE] D-pad users cannot interact without initial focus")
            println("[ISSUE] Fix: add android:focusable='true' and requestFocus() to the primary element")
            fail("No initial focus on launch")
            return
        }

        val bounds = focused.visibleBounds
        val text = focused.text ?: focused.contentDescription ?: "(no label)"
        val className = focused.className?.substringAfterLast('.') ?: "?"
        val resName = focused.resourceName ?: "no-id"

        println("[AUDIT] Initially focused: $className '$text' (${resName})")
        println("[AUDIT] Position: ${bounds.left},${bounds.top} — ${bounds.right},${bounds.bottom}")

        // Is the initial focus in a sensible position?
        val centerX = bounds.centerX()
        val centerY = bounds.centerY()

        val verticalPosition = when {
            centerY < screenH / 3 -> "top"
            centerY < 2 * screenH / 3 -> "middle"
            else -> "bottom"
        }
        val horizontalPosition = when {
            centerX < screenW / 3 -> "left"
            centerX < 2 * screenW / 3 -> "center"
            else -> "right"
        }

        println("[AUDIT] Focus starts at: $verticalPosition-$horizontalPosition of screen")

        if (verticalPosition == "top" || verticalPosition == "middle") {
            println("[OK] Initial focus is at top/middle — good starting position")
        } else {
            println("[WARN] Initial focus is at bottom of screen — users may not see it immediately")
        }

        // Is the focused element clickable?
        if (focused.isClickable) {
            println("[OK] Initially focused element is clickable (interactive)")
        } else {
            println("[WARN] Initially focused element is not clickable — consider focusing an actionable element first")
        }

        // How many elements are visible on launch?
        val allVisible = TestHelper.getAllFocusableElements(device)
        println("[AUDIT] Focusable elements visible on launch: ${allVisible.size}")

        if (allVisible.isEmpty()) {
            println("[ISSUE] No focusable elements on launch screen — D-pad users are stuck")
        } else if (allVisible.size == 1) {
            println("[WARN] Only 1 focusable element — very limited interaction")
        } else {
            println("[OK] ${allVisible.size} focusable elements available")
        }

        TestHelper.takeScreenshot(device, "audit_initial_focus")
    }

    // ================================================================
    // 6. FOCUS INDICATOR VISIBILITY
    // ================================================================

    @Test
    fun audit_focusIndicators() {
        println("[AUDIT] === Focus Indicator Analysis ===")

        var testedCount = 0
        var noVisualChangeCount = 0
        val problematicElements = mutableListOf<String>()

        // Navigate through elements and check if focus produces visual changes
        for (i in 1..20) {
            val focused = TestHelper.getFocusedElement(device)
            if (focused == null) {
                println("[WARN] Focus lost at step $i during navigation")
                TestHelper.pressDown(device, 300)
                continue
            }

            val id = focused.resourceName ?: focused.text ?: focused.className?.substringAfterLast('.') ?: "element-$i"
            val isFocused = focused.isFocused
            val isSelected = focused.isSelected
            val bounds = focused.visibleBounds

            testedCount++

            if (!isFocused) {
                noVisualChangeCount++
                problematicElements.add(id)
                println("[WARN] Element '$id' has focus but isFocused=false — focus indicator may be missing")
            }

            // Move to next element
            TestHelper.pressDown(device, 300)

            // Check if previous element's state changed
            if (focused.resourceName != null) {
                val refound = device.findObject(By.res(focused.resourceName))
                if (refound != null) {
                    // If bounds/selected state are identical when focused vs unfocused,
                    // the focus indicator may be invisible
                    val afterBounds = refound.visibleBounds
                    val afterSelected = refound.isSelected
                    if (afterBounds == bounds && afterSelected == isSelected && !isSelected) {
                        // This might lack a visible focus indicator
                        // (Can't detect color changes via UiAutomator)
                        println("[AUDIT] Element '$id' — no detectable state change when unfocused (color changes not detectable)")
                    }
                }
            }
        }

        println("[AUDIT] Tested $testedCount elements for focus indicators")
        if (noVisualChangeCount > 0) {
            println("[WARN] $noVisualChangeCount element(s) may lack visible focus indicators")
        } else {
            println("[OK] All tested elements report correct focus state")
        }

        TestHelper.takeScreenshot(device, "audit_focus_indicators")
    }

    // ================================================================
    // 7. RESPONSE TIME
    // ================================================================

    @Test
    fun audit_responseTime() {
        println("[AUDIT] === UI Response Time Analysis ===")

        val timings = mutableListOf<Long>()

        // Measure how long focus transitions take
        for (i in 1..10) {
            val startTime = System.currentTimeMillis()
            device.pressKeyCode(KeyEvent.KEYCODE_DPAD_DOWN)
            device.waitForIdle(3000)
            val elapsed = System.currentTimeMillis() - startTime
            timings.add(elapsed)
        }

        val avgMs = timings.average()
        val maxMs = timings.maxOrNull() ?: 0
        val minMs = timings.minOrNull() ?: 0

        println("[AUDIT] D-pad response times (10 presses):")
        println("[AUDIT]   Average: ${avgMs.toInt()}ms")
        println("[AUDIT]   Fastest: ${minMs}ms")
        println("[AUDIT]   Slowest: ${maxMs}ms")

        when {
            avgMs <= 100 -> println("[OK] Excellent response time — feels instant")
            avgMs <= 300 -> println("[OK] Good response time — feels responsive")
            avgMs <= 600 -> println("[WARN] Moderate response time — slight lag noticeable")
            else -> println("[ISSUE] Slow response time (${avgMs.toInt()}ms avg) — feels sluggish, optimize layout/rendering")
        }

        if (maxMs > 1000) {
            println("[WARN] Worst-case response was ${maxMs}ms — occasional jank detected")
        }

        // Test Center button response
        val beforeHierarchy = TestHelper.dumpHierarchy(device)
        val centerStart = System.currentTimeMillis()
        TestHelper.pressCenter(device)
        device.waitForIdle(3000)
        val centerElapsed = System.currentTimeMillis() - centerStart
        val afterHierarchy = TestHelper.dumpHierarchy(device)

        if (beforeHierarchy != afterHierarchy) {
            println("[AUDIT] Center/OK response time: ${centerElapsed}ms")
            when {
                centerElapsed <= 300 -> println("[OK] Action response is snappy")
                centerElapsed <= 800 -> println("[OK] Action response is acceptable")
                else -> println("[WARN] Action response is slow (${centerElapsed}ms)")
            }
            TestHelper.pressBack(device)
            TestHelper.waitForIdle(device, 1000)
        } else {
            println("[AUDIT] Center press had no effect (no action bound to focused element)")
        }
    }

    // ================================================================
    // 8. SCREEN DENSITY & SPACING
    // ================================================================

    @Test
    fun audit_spacingAndDensity() {
        println("[AUDIT] === Spacing & Density Analysis ===")

        val allFocusable = TestHelper.getAllFocusableElements(device)
        val (screenW, screenH) = TestHelper.getScreenDimensions(device)

        if (allFocusable.size < 2) {
            println("[AUDIT] Too few focusable elements to analyze spacing")
            return
        }

        // Sort by Y position, then analyze vertical spacing
        val sortedByY = allFocusable.sortedBy { it.visibleBounds.top }
        val verticalGaps = mutableListOf<Int>()
        val tooCloseElements = mutableListOf<Pair<String, String>>()

        for (i in 0 until sortedByY.size - 1) {
            val gap = sortedByY[i + 1].visibleBounds.top - sortedByY[i].visibleBounds.bottom
            if (gap >= 0) {
                verticalGaps.add(gap)
            }
            if (gap in 0..4) {
                val a = sortedByY[i].resourceName ?: sortedByY[i].text ?: "?"
                val b = sortedByY[i + 1].resourceName ?: sortedByY[i + 1].text ?: "?"
                tooCloseElements.add(Pair(a, b))
            }
        }

        if (verticalGaps.isNotEmpty()) {
            val avgGap = verticalGaps.average()
            val minGap = verticalGaps.minOrNull() ?: 0
            val maxGap = verticalGaps.maxOrNull() ?: 0

            println("[AUDIT] Vertical gaps between elements:")
            println("[AUDIT]   Average: ${avgGap.toInt()}px")
            println("[AUDIT]   Smallest: ${minGap}px")
            println("[AUDIT]   Largest: ${maxGap}px")

            when {
                avgGap >= 16 -> println("[OK] Good vertical spacing between elements")
                avgGap >= 8 -> println("[WARN] Tight vertical spacing — may be hard to distinguish focused item")
                else -> println("[ISSUE] Very tight spacing — elements feel cramped")
            }
        }

        if (tooCloseElements.isNotEmpty()) {
            println("[WARN] ${tooCloseElements.size} element pair(s) have <5px gap:")
            for ((a, b) in tooCloseElements.take(3)) {
                println("[WARN]   '$a' and '$b'")
            }
        }

        // Check screen utilization
        val totalFocusableArea = allFocusable.sumOf {
            val b = it.visibleBounds
            b.width().toLong() * b.height().toLong()
        }
        val screenArea = screenW.toLong() * screenH.toLong()
        val utilization = if (screenArea > 0) (totalFocusableArea * 100 / screenArea).toInt() else 0

        println("[AUDIT] Interactive area coverage: $utilization% of screen")
        when {
            utilization < 5 -> println("[WARN] Very sparse layout — consider adding more interactive elements or making them larger")
            utilization > 80 -> println("[WARN] Very dense layout — may feel cluttered")
            else -> println("[OK] Reasonable layout density")
        }
    }

    // ================================================================
    // 9. D-PAD NAVIGATION COMPLETENESS AUDIT
    // ================================================================

    @Test
    fun audit_dpadCompleteness() {
        println("[AUDIT] === D-pad Navigation Completeness ===")

        // Count total focusable elements
        val totalFocusable = TestHelper.getAllFocusableElements(device)
        val totalCount = totalFocusable.size
        println("[AUDIT] Total focusable elements on screen: $totalCount")

        // Track all reachable elements via D-pad
        val reached = mutableSetOf<String>()
        val unreachedIds = mutableSetOf<String>()

        // Record all element IDs for comparison
        for (el in totalFocusable) {
            val id = el.resourceName ?: "${el.className}|${el.visibleBounds}"
            unreachedIds.add(id)
        }

        TestHelper.getFocusedElementId(device)?.let { reached.add(it) }

        // Exhaustive traversal
        val directions = listOf(
            Pair(KeyEvent.KEYCODE_DPAD_DOWN, "Down"),
            Pair(KeyEvent.KEYCODE_DPAD_RIGHT, "Right"),
            Pair(KeyEvent.KEYCODE_DPAD_UP, "Up"),
            Pair(KeyEvent.KEYCODE_DPAD_LEFT, "Left")
        )

        for (pass in 1..3) {
            for ((keyCode, _) in directions) {
                for (step in 1..20) {
                    TestHelper.pressKey(device, keyCode, 200)
                    val focused = TestHelper.getFocusedElement(device)
                    if (focused != null) {
                        val id = focused.resourceName ?: "${focused.className}|${focused.visibleBounds}"
                        reached.add(id)
                        unreachedIds.remove(id)

                        // Also record by the full ID format
                        TestHelper.getFocusedElementId(device)?.let { reached.add(it) }
                    }
                }
            }
        }

        val reachPct = if (totalCount > 0) (reached.size * 100 / totalCount) else 100
        println("[AUDIT] Reachable via D-pad: ${reached.size}/$totalCount ($reachPct%)")

        if (unreachedIds.isNotEmpty() && unreachedIds.size <= 10) {
            println("[WARN] Potentially unreachable elements:")
            for (id in unreachedIds.take(5)) {
                println("[WARN]   $id")
            }
        }

        when {
            reachPct >= 90 -> println("[OK] Excellent D-pad coverage ($reachPct%)")
            reachPct >= 70 -> println("[WARN] Good but incomplete D-pad coverage ($reachPct%) — some elements need nextFocus attributes")
            reachPct >= 50 -> println("[WARN] Partial D-pad coverage ($reachPct%) — significant navigation gaps exist")
            else -> println("[ISSUE] Poor D-pad coverage ($reachPct%) — many elements unreachable via D-pad")
        }

        assertTrue(
            "D-pad coverage is only $reachPct%. At least 50% of focusable elements must be reachable.",
            reachPct >= 50 || totalCount <= 3
        )
    }

    // ================================================================
    // 10. OVERALL USABILITY SCORE
    // ================================================================

    @Test
    fun audit_overallScore() {
        println("[AUDIT] === Overall Usability Score ===")

        var score = 100
        val deductions = mutableListOf<String>()

        // Check 1: Initial focus
        val hasFocus = TestHelper.getFocusedElement(device) != null
        if (!hasFocus) {
            score -= 30
            deductions.add("-30: No initial focus")
        }

        // Check 2: Number of focusable elements
        val focusableCount = TestHelper.getAllFocusableElements(device).size
        if (focusableCount == 0) {
            score -= 30
            deductions.add("-30: No focusable elements")
        } else if (focusableCount == 1) {
            score -= 10
            deductions.add("-10: Only 1 focusable element")
        }

        // Check 3: Basic navigation works
        val beforeId = TestHelper.getFocusedElementId(device)
        TestHelper.pressDown(device, 300)
        val afterDown = TestHelper.getFocusedElementId(device)
        if (beforeId == afterDown) {
            TestHelper.pressRight(device, 300)
            val afterRight = TestHelper.getFocusedElementId(device)
            if (beforeId == afterRight) {
                score -= 20
                deductions.add("-20: D-pad navigation has no effect (focus doesn't move)")
            }
        }

        // Check 4: Focus survives navigation
        var focusLostCount = 0
        for (i in 1..10) {
            TestHelper.pressDown(device, 200)
            if (TestHelper.getFocusedElement(device) == null) focusLostCount++
        }
        if (focusLostCount > 0) {
            val penalty = minOf(focusLostCount * 5, 20)
            score -= penalty
            deductions.add("-$penalty: Focus lost $focusLostCount times during navigation")
        }

        // Check 5: Accessibility labels
        val allFocusable = TestHelper.getAllFocusableElements(device)
        var unlabeledCount = 0
        for (el in allFocusable) {
            if (el.text.isNullOrBlank() && el.contentDescription.isNullOrBlank()) {
                unlabeledCount++
            }
        }
        if (allFocusable.isNotEmpty()) {
            val unlabeledPct = unlabeledCount * 100 / allFocusable.size
            if (unlabeledPct > 50) {
                score -= 10
                deductions.add("-10: ${unlabeledPct}% of elements lack labels")
            } else if (unlabeledPct > 20) {
                score -= 5
                deductions.add("-5: ${unlabeledPct}% of elements lack labels")
            }
        }

        // Check 6: Zero-size elements
        val zeroSize = allFocusable.count {
            val b = it.visibleBounds; b.width() == 0 || b.height() == 0
        }
        if (zeroSize > 0) {
            score -= 10
            deductions.add("-10: $zeroSize zero-size focusable element(s)")
        }

        // Clamp score
        score = maxOf(score, 0)

        println("[AUDIT] ================================")
        if (deductions.isEmpty()) {
            println("[AUDIT] No issues found!")
        } else {
            for (d in deductions) {
                println("[AUDIT]   $d")
            }
        }
        println("[AUDIT] ================================")
        println("[AUDIT] USABILITY SCORE: $score / 100")
        println("[AUDIT] ================================")

        when {
            score >= 90 -> println("[OK] Excellent D-pad usability")
            score >= 70 -> println("[OK] Good usability with minor issues")
            score >= 50 -> println("[WARN] Usability needs improvement")
            else -> println("[ISSUE] Significant usability problems — app is difficult to use with D-pad")
        }
    }

    // ================================================================
    // Helpers
    // ================================================================

    private fun calculateOverlap(a: Rect, b: Rect): Int {
        val overlapLeft = maxOf(a.left, b.left)
        val overlapTop = maxOf(a.top, b.top)
        val overlapRight = minOf(a.right, b.right)
        val overlapBottom = minOf(a.bottom, b.bottom)
        return if (overlapRight > overlapLeft && overlapBottom > overlapTop) {
            (overlapRight - overlapLeft) * (overlapBottom - overlapTop)
        } else 0
    }

    private fun recordRegionReach(
        device: UiDevice,
        screenW: Int,
        screenH: Int,
        pressCount: Int,
        regionMap: MutableMap<String, Int>
    ) {
        val bounds = TestHelper.getFocusedBounds(device) ?: return
        val col = when {
            bounds.centerX() < screenW / 3 -> "left"
            bounds.centerX() < 2 * screenW / 3 -> "center"
            else -> "right"
        }
        val row = when {
            bounds.centerY() < screenH / 3 -> "top"
            bounds.centerY() < 2 * screenH / 3 -> "middle"
            else -> "bottom"
        }
        val region = "$row-$col"
        if (region !in regionMap) {
            regionMap[region] = pressCount
        }
    }
}
