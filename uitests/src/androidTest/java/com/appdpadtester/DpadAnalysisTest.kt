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
 * Deep D-pad navigation analysis — provides detailed diagnostic feedback
 * about how D-pad navigation works (or doesn't) across the app.
 *
 * Unlike DpadNavTest (pass/fail), this outputs structured observations
 * about navigation patterns, dead zones, traps, inconsistencies, and
 * spatial correctness using [AUDIT]/[WARN]/[ISSUE]/[OK] tags.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class DpadAnalysisTest {

    private lateinit var device: UiDevice
    private var screenW = 0
    private var screenH = 0
    private val p by lazy { TestHelper.screenPrefix() }

    // Navigation graph: tracks (fromLabel -> direction -> toLabel) for ASCII map
    private val navEdges = mutableListOf<Triple<String, String, String>>()
    private val navNodes = mutableMapOf<String, Pair<Int, Int>>() // label -> (x, y)

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
    // 1. FOCUS CHAIN MAPPING
    // ================================================================

    @Test
    fun analyze_focusChains() {
        println("${p}[AUDIT] === Focus Chain Map ===")
        println("${p}[AUDIT] Mapping the exact sequence of elements reached in each direction")

        data class FocusNode(
            val id: String,
            val label: String,
            val bounds: Rect,
            val className: String
        )

        fun captureCurrent(): FocusNode? {
            val el = TestHelper.getFocusedElement(device) ?: return null
            val b = el.visibleBounds
            return FocusNode(
                id = el.resourceName ?: "${el.className}@${b.left},${b.top}",
                label = el.text ?: el.contentDescription ?: el.resourceName?.substringAfterLast('/') ?: "(unlabeled)",
                bounds = b,
                className = el.className?.substringAfterLast('.') ?: "?"
            )
        }

        val directions = listOf(
            Triple(KeyEvent.KEYCODE_DPAD_DOWN, "DOWN", "↓"),
            Triple(KeyEvent.KEYCODE_DPAD_RIGHT, "RIGHT", "→"),
            Triple(KeyEvent.KEYCODE_DPAD_UP, "UP", "↑"),
            Triple(KeyEvent.KEYCODE_DPAD_LEFT, "LEFT", "←")
        )

        for ((keyCode, name, arrow) in directions) {
            // Reset to home state
            TestHelper.launchApp(device)
            TestHelper.waitForIdle(device, 2000)

            val chain = mutableListOf<FocusNode>()
            val start = captureCurrent()
            if (start != null) chain.add(start)

            var stuckCount = 0

            for (step in 1..25) {
                val prevNode = chain.lastOrNull()
                TestHelper.pressKey(device, keyCode, 250)
                val current = captureCurrent()
                if (current == null) {
                    println("${p}[ISSUE] Focus LOST at step $step pressing $name")
                    break
                }

                if (chain.isNotEmpty() && current.id == chain.last().id) {
                    stuckCount++
                    if (stuckCount >= 3) break // Hit boundary
                } else {
                    stuckCount = 0
                    chain.add(current)
                    // Record edge for navigation map
                    if (prevNode != null) {
                        val fromLabel = prevNode.label.take(15)
                        val toLabel = current.label.take(15)
                        navEdges.add(Triple(fromLabel, name, toLabel))
                        navNodes[fromLabel] = Pair(prevNode.bounds.centerX(), prevNode.bounds.centerY())
                        navNodes[toLabel] = Pair(current.bounds.centerX(), current.bounds.centerY())
                    }
                }
            }

            println("${p}[AUDIT] $arrow $name chain (${chain.size} elements):")
            for ((i, node) in chain.withIndex()) {
                val info = "{id:\"${node.id.substringBefore('|')}\", type:\"${node.className}\", bounds:\"${node.bounds.left},${node.bounds.top},${node.bounds.right},${node.bounds.bottom}\"}"
                println("${p}[AUDIT]   ${i + 1}. $info '${node.label.take(25)}'")
            }

            if (chain.size <= 1) {
                println("${p}[WARN] $name navigation reaches only ${chain.size} element — nothing to navigate to")
            } else if (stuckCount >= 3) {
                println("${p}[OK] $name chain ends at boundary (${chain.size} elements)")
            }
        }

        // Build ASCII navigation map
        printNavigationMap()

        TestHelper.takeScreenshot(device, "dpad_chain_map")
    }

    /**
     * Builds and prints an ASCII navigation map from collected edges.
     * Shows elements as boxes connected by arrows.
     */
    private fun printNavigationMap() {
        if (navNodes.isEmpty()) return

        println("${p}[AUDIT] ")
        println("${p}[AUDIT] NAVIGATION MAP:")

        // Sort nodes into a grid by Y then X
        val sortedNodes = navNodes.entries.sortedWith(compareBy({ it.value.second / (screenH / 4) }, { it.value.first }))

        // Group into rows by approximate Y position
        val rows = mutableListOf<MutableList<String>>()
        var currentRowY = -1
        val rowThreshold = screenH / 4

        for ((label, pos) in sortedNodes) {
            val rowBucket = pos.second / rowThreshold
            if (rowBucket != currentRowY) {
                currentRowY = rowBucket
                rows.add(mutableListOf())
            }
            rows.last().add(label)
        }

        // Build edge lookup
        val rightEdges = mutableSetOf<Pair<String, String>>()
        val downEdges = mutableSetOf<Pair<String, String>>()
        for ((from, dir, to) in navEdges) {
            when (dir) {
                "RIGHT" -> rightEdges.add(Pair(from, to))
                "DOWN" -> downEdges.add(Pair(from, to))
            }
        }

        // Print each row
        for ((rowIdx, row) in rows.withIndex()) {
            val line = StringBuilder("${p}[AUDIT]   ")
            for ((colIdx, label) in row.withIndex()) {
                val display = "[${label.take(12)}]"
                line.append(display)
                // Check if there's a right-edge to the next element
                if (colIdx < row.size - 1) {
                    val nextLabel = row[colIdx + 1]
                    val hasRight = rightEdges.any { it.first == label && it.second == nextLabel }
                    val hasLeft = rightEdges.any { it.first == nextLabel && it.second == label }
                    line.append(when {
                        hasRight && hasLeft -> " ↔ "
                        hasRight -> " → "
                        hasLeft -> " ← "
                        else -> "   "
                    })
                }
            }
            println(line.toString())

            // Print down arrows between rows
            if (rowIdx < rows.size - 1) {
                val arrowLine = StringBuilder("${p}[AUDIT]   ")
                for (label in row) {
                    val nextRow = rows[rowIdx + 1]
                    val hasDown = nextRow.any { target ->
                        downEdges.any { it.first == label && it.second == target }
                    }
                    val hasUp = nextRow.any { target ->
                        downEdges.any { it.first == target && it.second == label }
                    }
                    val pad = label.take(12).length + 2 // account for [ ]
                    val arrow = when {
                        hasDown && hasUp -> "↕"
                        hasDown -> "↓"
                        hasUp -> "↑"
                        else -> " "
                    }
                    val leftPad = pad / 2
                    arrowLine.append(" ".repeat(leftPad))
                    arrowLine.append(arrow)
                    arrowLine.append(" ".repeat(pad - leftPad - 1))
                    arrowLine.append("   ") // gap between columns
                }
                println(arrowLine.toString())
            }
        }
        println("${p}[AUDIT] ")
    }

    // ================================================================
    // 2. SPATIAL CORRECTNESS
    // ================================================================

    @Test
    fun analyze_spatialCorrectness() {
        println("${p}[AUDIT] === Spatial Correctness Analysis ===")
        println("${p}[AUDIT] Checks if D-pad directions move focus in the correct spatial direction")

        data class MoveResult(
            val direction: String,
            val fromX: Int, val fromY: Int,
            val toX: Int, val toY: Int,
            val correct: Boolean
        )

        val moves = mutableListOf<MoveResult>()
        var wrongDirectionCount = 0

        // Test DOWN moves
        TestHelper.launchApp(device)
        TestHelper.waitForIdle(device, 2000)
        for (i in 1..15) {
            val before = TestHelper.getFocusedBounds(device) ?: continue
            TestHelper.pressDown(device, 250)
            val after = TestHelper.getFocusedBounds(device) ?: continue
            if (before != after) {
                val correct = after.centerY() >= before.centerY() - 10
                moves.add(MoveResult("DOWN", before.centerX(), before.centerY(), after.centerX(), after.centerY(), correct))
                if (!correct) wrongDirectionCount++
            }
        }

        // Test RIGHT moves
        TestHelper.launchApp(device)
        TestHelper.waitForIdle(device, 2000)
        for (i in 1..15) {
            val before = TestHelper.getFocusedBounds(device) ?: continue
            TestHelper.pressRight(device, 250)
            val after = TestHelper.getFocusedBounds(device) ?: continue
            if (before != after) {
                val correct = after.centerX() >= before.centerX() - 10
                moves.add(MoveResult("RIGHT", before.centerX(), before.centerY(), after.centerX(), after.centerY(), correct))
                if (!correct) wrongDirectionCount++
            }
        }

        // Test UP moves
        for (i in 1..15) {
            val before = TestHelper.getFocusedBounds(device) ?: continue
            TestHelper.pressUp(device, 250)
            val after = TestHelper.getFocusedBounds(device) ?: continue
            if (before != after) {
                val correct = after.centerY() <= before.centerY() + 10
                moves.add(MoveResult("UP", before.centerX(), before.centerY(), after.centerX(), after.centerY(), correct))
                if (!correct) wrongDirectionCount++
            }
        }

        // Test LEFT moves
        for (i in 1..15) {
            val before = TestHelper.getFocusedBounds(device) ?: continue
            TestHelper.pressLeft(device, 250)
            val after = TestHelper.getFocusedBounds(device) ?: continue
            if (before != after) {
                val correct = after.centerX() <= before.centerX() + 10
                moves.add(MoveResult("LEFT", before.centerX(), before.centerY(), after.centerX(), after.centerY(), correct))
                if (!correct) wrongDirectionCount++
            }
        }

        // Report
        val byDirection = moves.groupBy { it.direction }
        for ((dir, dirMoves) in byDirection) {
            val correct = dirMoves.count { it.correct }
            val total = dirMoves.size
            val pct = if (total > 0) correct * 100 / total else 100
            if (pct == 100) {
                println("${p}[OK] $dir: all $total moves spatially correct")
            } else {
                println("${p}[WARN] $dir: $correct/$total moves spatially correct ($pct%)")
                for (m in dirMoves.filter { !it.correct }.take(3)) {
                    println("${p}[WARN]   Moved from (${m.fromX},${m.fromY}) to (${m.toX},${m.toY}) — wrong direction")
                }
            }
        }

        val totalMoves = moves.size
        val totalCorrect = moves.count { it.correct }
        println("${p}[AUDIT] Overall spatial accuracy: $totalCorrect/$totalMoves moves correct")

        if (wrongDirectionCount == 0) {
            println("${p}[OK] All D-pad movements go in the expected spatial direction")
        } else {
            println("${p}[ISSUE] $wrongDirectionCount D-pad moves went in the wrong spatial direction — check nextFocus attributes or layout order")
        }
    }

    // ================================================================
    // 3. FOCUS TRAPS & DEAD ZONES
    // ================================================================

    @Test
    fun analyze_focusTraps() {
        println("${p}[AUDIT] === Focus Trap & Dead Zone Detection ===")
        println("${p}[AUDIT] Looking for elements where D-pad gets stuck or lost")

        data class TrapInfo(
            val elementId: String,
            val label: String,
            val position: String,
            val blockedDirections: List<String>
        )

        val traps = mutableListOf<TrapInfo>()
        val visited = mutableSetOf<String>()

        // Navigate to various elements and test all 4 directions from each
        val navSequence = listOf(
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_DOWN
        )

        // Start from launch
        TestHelper.launchApp(device)
        TestHelper.waitForIdle(device, 2000)

        for (navKey in navSequence) {
            TestHelper.pressKey(device, navKey, 300)
            val currentEl = TestHelper.getFocusedElement(device) ?: continue
            val currentId = currentEl.resourceName ?: "${currentEl.className}@${currentEl.visibleBounds}"

            if (currentId in visited) continue
            visited.add(currentId)

            val label = currentEl.text ?: currentEl.contentDescription ?: "(unlabeled)"
            val bounds = currentEl.visibleBounds
            val blockedDirs = mutableListOf<String>()

            // Test each direction from this element
            val testDirs = listOf(
                Triple(KeyEvent.KEYCODE_DPAD_UP, "UP", KeyEvent.KEYCODE_DPAD_DOWN),
                Triple(KeyEvent.KEYCODE_DPAD_DOWN, "DOWN", KeyEvent.KEYCODE_DPAD_UP),
                Triple(KeyEvent.KEYCODE_DPAD_LEFT, "LEFT", KeyEvent.KEYCODE_DPAD_RIGHT),
                Triple(KeyEvent.KEYCODE_DPAD_RIGHT, "RIGHT", KeyEvent.KEYCODE_DPAD_LEFT)
            )

            for ((testKey, dirName, returnKey) in testDirs) {
                val beforeId = TestHelper.getFocusedElementId(device)
                TestHelper.pressKey(device, testKey, 200)
                val afterId = TestHelper.getFocusedElementId(device)

                if (afterId == null) {
                    blockedDirs.add("$dirName(LOST)")
                    // Try to recover
                    TestHelper.pressKey(device, returnKey, 200)
                    if (TestHelper.getFocusedElement(device) == null) {
                        // Really lost — re-navigate
                        TestHelper.launchApp(device)
                        TestHelper.waitForIdle(device, 2000)
                        break
                    }
                } else if (afterId == beforeId) {
                    blockedDirs.add(dirName)
                }

                // Return to the element
                if (afterId != beforeId && afterId != null) {
                    TestHelper.pressKey(device, returnKey, 200)
                }
            }

            if (blockedDirs.isNotEmpty()) {
                val pos = "(${bounds.centerX()}, ${bounds.centerY()})"
                traps.add(TrapInfo(currentId, label, pos, blockedDirs))
            }
        }

        // Report findings
        var fullTraps = 0
        var partialTraps = 0

        for (trap in traps) {
            val lostDirs = trap.blockedDirections.filter { it.endsWith("(LOST)") }
            val stuckDirs = trap.blockedDirections.filter { !it.endsWith("(LOST)") }

            if (lostDirs.isNotEmpty()) {
                println("${p}[ISSUE] FOCUS LOST from '${trap.label.take(25)}' ${trap.position} pressing: ${lostDirs.joinToString(", ")}")
            }

            if (trap.blockedDirections.size >= 4) {
                fullTraps++
                println("${p}[ISSUE] FULL TRAP: {id:\"${trap.elementId}\", pos:${trap.position}} '${trap.label.take(25)}' — blocked in ALL directions. Fix: add nextFocusUp/Down/Left/Right attributes")
            } else if (stuckDirs.size >= 2) {
                partialTraps++
                val fixes = stuckDirs.map { dir -> "nextFocus$dir" }.joinToString(", ")
                println("${p}[WARN] Partial dead zone: {id:\"${trap.elementId}\", pos:${trap.position}} '${trap.label.take(25)}' — blocked: ${stuckDirs.joinToString(", ")}. Fix: add $fixes")
            } else if (stuckDirs.size == 1) {
                // Single blocked direction at boundary is normal
                val isBoundary = when (stuckDirs[0]) {
                    "UP" -> trap.position.contains(Regex("\\d+,\\s*[0-9]{1,2}\\)"))
                    "LEFT" -> trap.position.contains(Regex("\\([0-9]{1,2},"))
                    else -> false
                }
                if (!isBoundary) {
                    println("${p}[AUDIT] '${trap.label.take(25)}' ${trap.position}: ${stuckDirs[0]} blocked (may be boundary)")
                }
            }
        }

        println("${p}[AUDIT] Tested ${visited.size} unique elements")
        println("${p}[AUDIT] Full traps: $fullTraps, Partial dead zones: $partialTraps")

        if (fullTraps == 0 && partialTraps == 0) {
            println("${p}[OK] No focus traps or dead zones detected")
        } else if (fullTraps > 0) {
            println("${p}[ISSUE] $fullTraps element(s) trap focus completely — user gets stuck with no way out")
        }
    }

    // ================================================================
    // 4. WRAPPING BEHAVIOR
    // ================================================================

    @Test
    fun analyze_wrappingBehavior() {
        println("${p}[AUDIT] === Wrapping Behavior Analysis ===")
        println("${p}[AUDIT] Checks whether D-pad wraps around at list/grid boundaries")

        val directions = listOf(
            Pair(KeyEvent.KEYCODE_DPAD_DOWN, "DOWN"),
            Pair(KeyEvent.KEYCODE_DPAD_RIGHT, "RIGHT"),
            Pair(KeyEvent.KEYCODE_DPAD_UP, "UP"),
            Pair(KeyEvent.KEYCODE_DPAD_LEFT, "LEFT")
        )

        for ((keyCode, name) in directions) {
            TestHelper.launchApp(device)
            TestHelper.waitForIdle(device, 2000)

            // Navigate to boundary
            var lastId = TestHelper.getFocusedElementId(device)
            var boundaryId: String? = null
            var stuckCount = 0

            for (step in 1..40) {
                TestHelper.pressKey(device, keyCode, 200)
                val currentId = TestHelper.getFocusedElementId(device)
                if (currentId == lastId) {
                    stuckCount++
                    if (stuckCount >= 3) {
                        boundaryId = currentId
                        break
                    }
                } else {
                    stuckCount = 0
                }
                lastId = currentId
            }

            if (boundaryId == null) {
                println("${p}[AUDIT] $name: no clear boundary found (focus may wrap or screen scrolls indefinitely)")
                continue
            }

            val boundaryBounds = TestHelper.getFocusedBounds(device)
            val boundaryLabel = TestHelper.getFocusedElement(device)?.let {
                it.text ?: it.contentDescription ?: it.resourceName ?: "?"
            } ?: "?"

            // Now press the direction one more time — does it wrap?
            TestHelper.pressKey(device, keyCode, 300)
            val afterBoundary = TestHelper.getFocusedElementId(device)
            val afterBounds = TestHelper.getFocusedBounds(device)

            val wraps = afterBoundary != null && afterBoundary != boundaryId
            if (wraps && afterBounds != null && boundaryBounds != null) {
                // Check if it wrapped to the opposite side
                val wentOpposite = when (name) {
                    "DOWN" -> afterBounds.centerY() < boundaryBounds.centerY() - screenH / 3
                    "UP" -> afterBounds.centerY() > boundaryBounds.centerY() + screenH / 3
                    "RIGHT" -> afterBounds.centerX() < boundaryBounds.centerX() - screenW / 3
                    "LEFT" -> afterBounds.centerX() > boundaryBounds.centerX() + screenW / 3
                    else -> false
                }
                if (wentOpposite) {
                    println("${p}[OK] $name wraps around at boundary '${boundaryLabel.take(20)}' — good for continuous navigation")
                } else {
                    println("${p}[AUDIT] $name: focus moved from boundary but did not wrap to opposite side (may jump to adjacent section)")
                }
            } else {
                println("${p}[AUDIT] $name: stops at boundary '${boundaryLabel.take(20)}' — no wrapping")
            }
        }
    }

    // ================================================================
    // 5. BIDIRECTIONAL CONSISTENCY
    // ================================================================

    @Test
    fun analyze_bidirectionalConsistency() {
        println("${p}[AUDIT] === Bidirectional Consistency ===")
        println("${p}[AUDIT] Checks if pressing opposite directions returns to the same element")

        var consistentPairs = 0
        var inconsistentPairs = 0
        val inconsistencies = mutableListOf<String>()

        // Test DOWN/UP pairs
        TestHelper.launchApp(device)
        TestHelper.waitForIdle(device, 2000)

        for (i in 1..10) {
            val beforeId = TestHelper.getFocusedElementId(device)
            TestHelper.pressDown(device, 250)
            val afterDown = TestHelper.getFocusedElementId(device)

            if (afterDown != null && afterDown != beforeId) {
                TestHelper.pressUp(device, 250)
                val afterReturn = TestHelper.getFocusedElementId(device)

                if (afterReturn == beforeId) {
                    consistentPairs++
                } else {
                    inconsistentPairs++
                    val beforeLabel = "(step $i)"
                    inconsistencies.add("DOWN/UP at $beforeLabel: expected return to original, went elsewhere")
                }
                // Move down again for next test
                TestHelper.pressDown(device, 250)
            } else {
                TestHelper.pressDown(device, 250)
            }
        }

        // Test RIGHT/LEFT pairs
        TestHelper.launchApp(device)
        TestHelper.waitForIdle(device, 2000)

        for (i in 1..10) {
            val beforeId = TestHelper.getFocusedElementId(device)
            TestHelper.pressRight(device, 250)
            val afterRight = TestHelper.getFocusedElementId(device)

            if (afterRight != null && afterRight != beforeId) {
                TestHelper.pressLeft(device, 250)
                val afterReturn = TestHelper.getFocusedElementId(device)

                if (afterReturn == beforeId) {
                    consistentPairs++
                } else {
                    inconsistentPairs++
                    inconsistencies.add("RIGHT/LEFT at step $i: expected return to original, went elsewhere")
                }
                TestHelper.pressRight(device, 250)
            } else {
                TestHelper.pressRight(device, 250)
            }
        }

        val total = consistentPairs + inconsistentPairs
        val pct = if (total > 0) consistentPairs * 100 / total else 100
        println("${p}[AUDIT] Bidirectional consistency: $consistentPairs/$total pairs ($pct%)")

        for (inc in inconsistencies.take(5)) {
            println("${p}[WARN] $inc")
        }
        if (inconsistencies.size > 5) {
            println("${p}[WARN] ... and ${inconsistencies.size - 5} more inconsistencies")
        }

        when {
            pct == 100 -> println("[OK] Perfect bidirectional consistency — opposite presses always return to the same element")
            pct >= 80 -> println("[OK] Good bidirectional consistency ($pct%) — minor navigation quirks")
            pct >= 50 -> println("[WARN] Moderate bidirectional consistency ($pct%) — users may get disoriented")
            else -> println("[ISSUE] Poor bidirectional consistency ($pct%) — navigation feels unpredictable")
        }
    }

    // ================================================================
    // 6. CROSS-AXIS FOCUS DRIFT
    // ================================================================

    @Test
    fun analyze_crossAxisDrift() {
        println("${p}[AUDIT] === Cross-Axis Focus Drift ===")
        println("${p}[AUDIT] Checks if vertical navigation causes unexpected horizontal drift (and vice versa)")

        // Test: press DOWN 10 times and check horizontal drift
        TestHelper.launchApp(device)
        TestHelper.waitForIdle(device, 2000)

        val startBounds = TestHelper.getFocusedBounds(device)
        if (startBounds == null) {
            println("${p}[ISSUE] No initial focus — cannot analyze drift")
            return
        }

        val startX = startBounds.centerX()
        var maxHorizontalDrift = 0
        var driftSteps = mutableListOf<Pair<Int, Int>>() // step -> drift amount

        for (step in 1..15) {
            TestHelper.pressDown(device, 250)
            val bounds = TestHelper.getFocusedBounds(device) ?: continue
            val drift = Math.abs(bounds.centerX() - startX)
            if (drift > maxHorizontalDrift) {
                maxHorizontalDrift = drift
            }
            if (drift > screenW / 6) {
                driftSteps.add(Pair(step, drift))
            }
        }

        println("${p}[AUDIT] Vertical navigation (DOWN x15):")
        println("${p}[AUDIT]   Starting X: $startX")
        println("${p}[AUDIT]   Max horizontal drift: ${maxHorizontalDrift}px (${maxHorizontalDrift * 100 / maxOf(screenW, 1)}% of screen width)")

        if (driftSteps.isEmpty()) {
            println("${p}[OK] No significant horizontal drift during vertical navigation")
        } else {
            println("${p}[WARN] Horizontal drift during vertical navigation at ${driftSteps.size} step(s):")
            for ((step, drift) in driftSteps.take(3)) {
                println("${p}[WARN]   Step $step: ${drift}px drift from starting position")
            }
            println("${p}[WARN] This can confuse users — focus should stay in the same column when pressing UP/DOWN")
        }

        // Test: press RIGHT 10 times and check vertical drift
        TestHelper.launchApp(device)
        TestHelper.waitForIdle(device, 2000)

        val startBounds2 = TestHelper.getFocusedBounds(device) ?: return
        val startY = startBounds2.centerY()
        var maxVerticalDrift = 0
        val vDriftSteps = mutableListOf<Pair<Int, Int>>()

        for (step in 1..15) {
            TestHelper.pressRight(device, 250)
            val bounds = TestHelper.getFocusedBounds(device) ?: continue
            val drift = Math.abs(bounds.centerY() - startY)
            if (drift > maxVerticalDrift) {
                maxVerticalDrift = drift
            }
            if (drift > screenH / 6) {
                vDriftSteps.add(Pair(step, drift))
            }
        }

        println("${p}[AUDIT] Horizontal navigation (RIGHT x15):")
        println("${p}[AUDIT]   Starting Y: $startY")
        println("${p}[AUDIT]   Max vertical drift: ${maxVerticalDrift}px (${maxVerticalDrift * 100 / maxOf(screenH, 1)}% of screen height)")

        if (vDriftSteps.isEmpty()) {
            println("${p}[OK] No significant vertical drift during horizontal navigation")
        } else {
            println("${p}[WARN] Vertical drift during horizontal navigation at ${vDriftSteps.size} step(s)")
            println("${p}[WARN] Focus should stay in the same row when pressing LEFT/RIGHT")
        }
    }

    // ================================================================
    // 7. CENTER/SELECT ACTION ANALYSIS
    // ================================================================

    @Test
    fun analyze_centerButtonBehavior() {
        println("${p}[AUDIT] === Center/OK Button Behavior Analysis ===")

        TestHelper.launchApp(device)
        TestHelper.waitForIdle(device, 2000)

        data class ActionResult(
            val elementLabel: String,
            val elementType: String,
            val hadEffect: Boolean,
            val effectType: String,
            val responseMs: Long
        )

        val results = mutableListOf<ActionResult>()

        for (step in 1..8) {
            val focused = TestHelper.getFocusedElement(device) ?: break
            val label = focused.text ?: focused.contentDescription ?: focused.resourceName ?: "element-$step"
            val className = focused.className?.substringAfterLast('.') ?: "?"
            val isClickable = focused.isClickable

            val hierarchyBefore = TestHelper.dumpHierarchy(device)
            val packageBefore = device.currentPackageName

            val startMs = System.currentTimeMillis()
            TestHelper.pressCenter(device)
            TestHelper.waitForIdle(device, 2000)
            val elapsed = System.currentTimeMillis() - startMs

            val hierarchyAfter = TestHelper.dumpHierarchy(device)
            val packageAfter = device.currentPackageName

            val hadEffect: Boolean
            val effectType: String

            when {
                packageAfter != packageBefore -> {
                    hadEffect = true
                    effectType = "navigated to different app/activity ($packageAfter)"
                }
                hierarchyBefore != hierarchyAfter -> {
                    hadEffect = true
                    // Try to classify the change
                    val newDialogs = hierarchyAfter.contains("android:id/alertTitle") ||
                        hierarchyAfter.contains("android:id/message") ||
                        (hierarchyAfter.contains("Dialog") && !hierarchyBefore.contains("Dialog"))
                    val focusChanged = TestHelper.getFocusedElementId(device) != (focused.resourceName ?: "")
                    effectType = when {
                        newDialogs -> "opened dialog"
                        hierarchyAfter.length > hierarchyBefore.length + 500 -> "navigated to new screen"
                        focusChanged -> "toggled selection/changed focus"
                        else -> "changed UI state"
                    }
                }
                else -> {
                    hadEffect = false
                    effectType = "no handler (element ${if (isClickable) "is" else "NOT"} clickable)"
                }
            }

            results.add(ActionResult(label.take(30), className, hadEffect, effectType, elapsed))

            // Restore state
            if (hadEffect) {
                TestHelper.pressBack(device)
                TestHelper.waitForIdle(device, 1000)
                if (device.currentPackageName != packageBefore) {
                    TestHelper.launchApp(device)
                    TestHelper.waitForIdle(device, 2000)
                }
            }

            // Move to next element
            TestHelper.pressDown(device, 300)
        }

        println("${p}[AUDIT] Center/OK results for ${results.size} elements:")
        for (r in results) {
            val tag = when {
                r.hadEffect -> "[OK]"
                else -> "[WARN]"
            }
            val clickableNote = ""
            println("${p}$tag '${r.elementLabel}' (${r.elementType}): ${r.effectType} [${r.responseMs}ms]")
        }

        val actionable = results.count { it.hadEffect }
        val noEffect = results.count { !it.hadEffect }
        println("${p}[AUDIT] ${actionable}/${results.size} elements responded to Center press")

        if (noEffect > actionable && results.size > 2) {
            println("${p}[WARN] More than half of tested elements don't respond to Center/OK — check click listeners")
        }

        val avgResponse = if (results.isNotEmpty()) results.map { it.responseMs }.average() else 0.0
        println("${p}[AUDIT] Average Center response time: ${avgResponse.toInt()}ms")
    }

    // ================================================================
    // 8. BACK KEY BEHAVIOR AT EACH DEPTH
    // ================================================================

    @Test
    fun analyze_backKeyBehavior() {
        println("${p}[AUDIT] === Back Key Behavior Analysis ===")

        TestHelper.launchApp(device)
        TestHelper.waitForIdle(device, 2000)

        val launchHierarchy = TestHelper.dumpHierarchy(device)
        val launchPackage = device.currentPackageName

        // Navigate 3 levels deep
        val depth = mutableListOf<String>()
        depth.add("Launch screen")

        for (level in 1..3) {
            TestHelper.pressDown(device)
            TestHelper.pressDown(device)
            TestHelper.pressCenter(device)
            TestHelper.waitForIdle(device, 1500)

            val currentHierarchy = TestHelper.dumpHierarchy(device)
            if (currentHierarchy != (if (depth.size > 0) launchHierarchy else "")) {
                depth.add("Level $level")
                val hasFocus = TestHelper.getFocusedElement(device) != null
                println("${p}[AUDIT] Navigated to depth $level — focus present: $hasFocus")
            } else {
                println("${p}[AUDIT] Center press at depth $level had no effect — stopping descent")
                break
            }
        }

        // Now unwind with Back
        println("${p}[AUDIT] Unwinding ${depth.size - 1} levels with Back key:")
        for (level in depth.size - 1 downTo 1) {
            val beforeBack = TestHelper.dumpHierarchy(device)
            TestHelper.pressBack(device)
            TestHelper.waitForIdle(device, 1500)

            val afterBack = TestHelper.dumpHierarchy(device)
            val hasFocus = TestHelper.getFocusedElement(device) != null
            val isInApp = TestHelper.isAppInForeground(device)

            if (!isInApp) {
                println("${p}[ISSUE] Back key EXITED the app at depth $level — should return to previous screen instead")
                TestHelper.launchApp(device)
                TestHelper.waitForIdle(device, 2000)
                break
            }

            if (beforeBack == afterBack) {
                println("${p}[WARN] Back key at depth $level had no visible effect")
            } else if (hasFocus) {
                println("${p}[OK] Back from depth $level: screen changed, focus present")
            } else {
                println("${p}[ISSUE] Back from depth $level: screen changed but FOCUS LOST")
            }
        }

        // Test Back on main screen
        println("${p}[AUDIT] Testing Back on main screen:")
        TestHelper.launchApp(device)
        TestHelper.waitForIdle(device, 2000)

        TestHelper.pressBack(device)
        TestHelper.waitForIdle(device, 1500)

        if (TestHelper.isAppInForeground(device)) {
            println("${p}[OK] Back on main screen keeps app in foreground (good — may show 'press again to exit')")
        } else {
            println("${p}[AUDIT] Back on main screen exits app (standard Android behavior)")
        }
    }

    // ================================================================
    // 9. SCROLL NAVIGATION ANALYSIS
    // ================================================================

    @Test
    fun analyze_scrollBehavior() {
        println("${p}[AUDIT] === Scroll Navigation Analysis ===")

        TestHelper.launchApp(device)
        TestHelper.waitForIdle(device, 2000)

        // Track Y positions as we navigate down — detect scrolling
        val yPositions = mutableListOf<Int>()
        val focusedBounds = mutableListOf<Rect>()
        var scrollDetected = false
        var prevMaxY = 0

        for (step in 1..40) {
            TestHelper.pressDown(device, 250)
            val bounds = TestHelper.getFocusedBounds(device) ?: continue
            yPositions.add(bounds.centerY())
            focusedBounds.add(bounds)

            // If focus Y position decreases after increasing, scrolling may have occurred
            if (yPositions.size >= 3) {
                val prev = yPositions[yPositions.size - 2]
                val curr = yPositions[yPositions.size - 1]
                // Focus near bottom then jumps back up = scroll happened
                if (prev > screenH * 0.7 && curr < prev - 100) {
                    if (!scrollDetected) {
                        scrollDetected = true
                        println("${p}[AUDIT] Scroll detected at step $step: focus Y went from $prev to $curr")
                    }
                }
            }

            // Also check: does max Y keep increasing? If so, list is scrolling with focus staying mid-screen
            if (bounds.bottom > prevMaxY + 50) {
                prevMaxY = bounds.bottom
            }
        }

        val minY = yPositions.minOrNull() ?: 0
        val maxY = yPositions.maxOrNull() ?: 0
        val range = maxY - minY

        println("${p}[AUDIT] Focus Y range during DOWN x40: ${minY}px to ${maxY}px (range: ${range}px)")

        if (range < screenH / 2) {
            println("${p}[AUDIT] Focus stays within a small vertical range — content may be scrolling behind the focus (good pattern for TV)")
        } else {
            println("${p}[AUDIT] Focus moves across a large portion of the screen — elements are spread vertically")
        }

        if (scrollDetected) {
            println("${p}[OK] Scrollable content detected — D-pad triggers scrolling as expected")
        } else if (yPositions.size > 10) {
            val allSimilar = yPositions.distinct().size <= 3
            if (allSimilar) {
                println("${p}[AUDIT] Focus stays at nearly the same Y position — content scrolls under fixed focus (good TV pattern)")
            } else {
                println("${p}[AUDIT] No clear scrolling pattern detected — content may fit on one screen")
            }
        }

        // Check if we can scroll back up
        val lastYBeforeUp = yPositions.lastOrNull() ?: 0
        for (step in 1..20) {
            TestHelper.pressUp(device, 200)
        }
        val afterScrollUp = TestHelper.getFocusedBounds(device)
        if (afterScrollUp != null && afterScrollUp.centerY() < lastYBeforeUp - 50) {
            println("${p}[OK] Upward scroll navigation works — user can return to top")
        }
    }

    // ================================================================
    // 10. RAPID INPUT STRESS TEST
    // ================================================================

    @Test
    fun analyze_rapidInputBehavior() {
        println("${p}[AUDIT] === Rapid Input Stress Analysis ===")

        TestHelper.launchApp(device)
        TestHelper.waitForIdle(device, 2000)

        // Phase 1: Rapid same-direction input
        println("${p}[AUDIT] Phase 1: Rapid DOWN x20 (50ms intervals)...")
        var focusLostCount = 0
        val startTime = System.currentTimeMillis()

        for (i in 1..20) {
            device.pressKeyCode(KeyEvent.KEYCODE_DPAD_DOWN)
            Thread.sleep(50)
            if (TestHelper.getFocusedElement(device) == null) focusLostCount++
        }
        device.waitForIdle(2000)
        val rapidDownMs = System.currentTimeMillis() - startTime

        println("${p}[AUDIT] Rapid DOWN completed in ${rapidDownMs}ms, focus lost $focusLostCount times")
        if (focusLostCount > 0) {
            println("${p}[WARN] Focus lost during rapid DOWN input — UI may not handle fast key repeat well")
        } else {
            println("${p}[OK] Focus maintained during rapid DOWN input")
        }

        // Phase 2: Rapid direction changes
        println("${p}[AUDIT] Phase 2: Rapid alternating directions x30 (50ms intervals)...")
        focusLostCount = 0
        val altKeys = listOf(
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_LEFT
        )
        val startTime2 = System.currentTimeMillis()

        for (i in 1..30) {
            device.pressKeyCode(altKeys[i % altKeys.size])
            Thread.sleep(50)
            if (TestHelper.getFocusedElement(device) == null) focusLostCount++
        }
        device.waitForIdle(2000)
        val rapidAltMs = System.currentTimeMillis() - startTime2

        println("${p}[AUDIT] Rapid alternating completed in ${rapidAltMs}ms, focus lost $focusLostCount times")
        if (focusLostCount > 0) {
            println("${p}[WARN] Focus lost during rapid direction changes")
        } else {
            println("${p}[OK] Focus stable during rapid direction changes")
        }

        // Phase 3: Check app is still alive
        val isAlive = TestHelper.isAppInForeground(device)
        if (isAlive) {
            println("${p}[OK] App survived rapid input stress test without crashing")
        } else {
            println("${p}[ISSUE] App crashed or lost foreground during rapid input stress test")
            TestHelper.launchApp(device)
            TestHelper.waitForIdle(device, 2000)
        }

        // Phase 4: Check UI is responsive after stress
        val recoveryStart = System.currentTimeMillis()
        TestHelper.pressDown(device, 300)
        val recoveryMs = System.currentTimeMillis() - recoveryStart
        println("${p}[AUDIT] Post-stress recovery response: ${recoveryMs}ms")
        if (recoveryMs > 1000) {
            println("${p}[WARN] UI is sluggish after rapid input — possible input queue buildup or rendering lag")
        } else {
            println("${p}[OK] UI recovers quickly after rapid input")
        }
    }

    // ================================================================
    // 11. MULTI-SCREEN NAVIGATION MAP
    // ================================================================

    @Test
    fun analyze_multiScreenNavigation() {
        println("${p}[AUDIT] === Multi-Screen Navigation Map ===")
        println("${p}[AUDIT] Exploring screens reachable from the main screen")

        TestHelper.launchApp(device)
        TestHelper.waitForIdle(device, 2000)

        data class ScreenInfo(
            val name: String,
            val focusableCount: Int,
            val hasFocus: Boolean,
            val reachMethod: String
        )

        val screens = mutableListOf<ScreenInfo>()
        val mainHierarchy = TestHelper.dumpHierarchy(device)
        val mainFocusable = TestHelper.getAllFocusableElements(device).size
        screens.add(ScreenInfo("Main Screen", mainFocusable, TestHelper.getFocusedElement(device) != null, "launch"))

        // Try navigating to sub-screens via Down+Center
        for (step in 0..6) {
            TestHelper.launchApp(device)
            TestHelper.waitForIdle(device, 2000)

            // Navigate to element at position 'step'
            for (i in 0 until step) {
                TestHelper.pressDown(device, 200)
            }

            val label = TestHelper.getFocusedElement(device)?.let {
                it.text ?: it.contentDescription ?: it.resourceName ?: "element-$step"
            } ?: continue

            val beforeHierarchy = TestHelper.dumpHierarchy(device)
            TestHelper.pressCenter(device)
            TestHelper.waitForIdle(device, 2000)
            val afterHierarchy = TestHelper.dumpHierarchy(device)

            if (afterHierarchy != beforeHierarchy) {
                val focusableCount = TestHelper.getAllFocusableElements(device).size
                val hasFocus = TestHelper.getFocusedElement(device) != null
                screens.add(ScreenInfo(
                    "Via '$label'",
                    focusableCount,
                    hasFocus,
                    "Down x$step → Center"
                ))

                TestHelper.pressBack(device)
                TestHelper.waitForIdle(device, 1000)
            }
        }

        // Also try Right-based navigation (tabs)
        TestHelper.launchApp(device)
        TestHelper.waitForIdle(device, 2000)
        for (step in 1..5) {
            TestHelper.pressRight(device, 300)
            val currentHierarchy = TestHelper.dumpHierarchy(device)
            if (currentHierarchy != mainHierarchy) {
                val label = TestHelper.getFocusedElement(device)?.let {
                    it.text ?: it.contentDescription ?: "tab-$step"
                } ?: "tab-$step"
                val focusableCount = TestHelper.getAllFocusableElements(device).size
                val hasFocus = TestHelper.getFocusedElement(device) != null
                screens.add(ScreenInfo("Tab '$label'", focusableCount, hasFocus, "Right x$step"))
            }
        }

        println("${p}[AUDIT] Discovered ${screens.size} unique screen states:")
        for (s in screens) {
            val focusTag = if (s.hasFocus) "focus OK" else "NO FOCUS"
            println("${p}[AUDIT]   ${s.name}: ${s.focusableCount} focusable elements, $focusTag (via: ${s.reachMethod})")
        }

        val noFocusScreens = screens.filter { !it.hasFocus }
        if (noFocusScreens.isNotEmpty()) {
            println("${p}[ISSUE] ${noFocusScreens.size} screen(s) have no initial focus:")
            for (s in noFocusScreens) {
                println("${p}[ISSUE]   ${s.name} (${s.reachMethod})")
            }
        } else {
            println("${p}[OK] All discovered screens have initial focus set")
        }

        val emptyScreens = screens.filter { it.focusableCount == 0 }
        if (emptyScreens.isNotEmpty()) {
            println("${p}[ISSUE] ${emptyScreens.size} screen(s) have zero focusable elements — D-pad users are stranded")
        }
    }
}
