package com.appdpadtester.utils

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.view.KeyEvent
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Shared test utilities for D-pad and focus testing.
 *
 * Provides helpers for app launch, D-pad key simulation, focus tracking,
 * hierarchy analysis, and screenshot capture. All UI Automator test classes
 * use this to keep test code DRY and consistent.
 */
object TestHelper {

    /** Read the target package from instrumentation arguments. */
    val targetPackage: String by lazy {
        InstrumentationRegistry.getArguments()
            .getString("targetPackage", "com.example.megalife.f1")
    }

    /** Optional: specific activity to launch (for per-screen auditing). */
    val targetActivity: String? by lazy {
        InstrumentationRegistry.getArguments()
            .getString("targetActivity", null)
    }

    /** Screen label for per-screen audit output prefixing. */
    val screenLabel: String by lazy {
        targetActivity?.substringAfterLast('.')?.removeSuffix("Activity") ?: "Main"
    }

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    // ---- App Launch ----

    /**
     * Launches the target app and waits for it to appear.
     * If targetActivity is set via instrumentation args, launches that
     * specific activity instead of the default launcher intent.
     * @return true if app launched successfully
     */
    fun launchApp(device: UiDevice, packageName: String = targetPackage): Boolean {
        val launcherPackage = device.launcherPackageName
        device.pressHome()
        device.wait(Until.hasObject(By.pkg(launcherPackage).depth(0)), 3000)

        val activity = targetActivity
        if (activity != null) {
            return launchActivity(device, activity, packageName)
        }

        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)

        return device.wait(Until.hasObject(By.pkg(packageName).depth(0)), 5000) ?: false
    }

    /**
     * Launches a specific activity by its fully-qualified class name.
     * @param activityName fully-qualified name (e.g. "com.example.app.SettingsActivity")
     * @return true if activity launched successfully
     */
    fun launchActivity(device: UiDevice, activityName: String, packageName: String = targetPackage): Boolean {
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
            component = ComponentName(packageName, activityName)
        }
        return try {
            context.startActivity(intent)
            device.wait(Until.hasObject(By.pkg(packageName).depth(0)), 5000) ?: false
        } catch (e: Exception) {
            false
        }
    }

    // ---- D-pad Key Simulation ----

    /** Press a single key and wait for UI to settle. */
    fun pressKey(device: UiDevice, keyCode: Int, settleMs: Long = 500) {
        device.pressKeyCode(keyCode)
        device.waitForIdle(settleMs)
    }

    /** Press a key multiple times with settle between each. */
    fun pressKeyRepeat(device: UiDevice, keyCode: Int, times: Int, settleMs: Long = 500) {
        repeat(times) {
            pressKey(device, keyCode, settleMs)
        }
    }

    /** Press D-pad Left. */
    fun pressLeft(device: UiDevice, settleMs: Long = 500) =
        pressKey(device, KeyEvent.KEYCODE_DPAD_LEFT, settleMs)

    /** Press D-pad Right. */
    fun pressRight(device: UiDevice, settleMs: Long = 500) =
        pressKey(device, KeyEvent.KEYCODE_DPAD_RIGHT, settleMs)

    /** Press D-pad Up. */
    fun pressUp(device: UiDevice, settleMs: Long = 500) =
        pressKey(device, KeyEvent.KEYCODE_DPAD_UP, settleMs)

    /** Press D-pad Down. */
    fun pressDown(device: UiDevice, settleMs: Long = 500) =
        pressKey(device, KeyEvent.KEYCODE_DPAD_DOWN, settleMs)

    /** Press D-pad Center (OK/Select). */
    fun pressCenter(device: UiDevice, settleMs: Long = 500) =
        pressKey(device, KeyEvent.KEYCODE_DPAD_CENTER, settleMs)

    /** Long-press D-pad Center via shell command. */
    fun pressLongCenter(device: UiDevice, holdMs: Long = 1500) {
        device.executeShellCommand("input keyevent --longpress ${KeyEvent.KEYCODE_DPAD_CENTER}")
        device.waitForIdle(holdMs)
    }

    /** Press Back key. */
    fun pressBack(device: UiDevice, settleMs: Long = 500) {
        device.pressBack()
        device.waitForIdle(settleMs)
    }

    /** Press a number key (0-9). */
    fun pressNumber(device: UiDevice, digit: Int, settleMs: Long = 500) {
        require(digit in 0..9) { "Digit must be 0-9, got $digit" }
        val keyCode = KeyEvent.KEYCODE_0 + digit
        pressKey(device, keyCode, settleMs)
    }

    /** Press Channel Up key (TV remote). */
    fun pressChannelUp(device: UiDevice, settleMs: Long = 500) =
        pressKey(device, KeyEvent.KEYCODE_CHANNEL_UP, settleMs)

    /** Press Channel Down key (TV remote). */
    fun pressChannelDown(device: UiDevice, settleMs: Long = 500) =
        pressKey(device, KeyEvent.KEYCODE_CHANNEL_DOWN, settleMs)

    /** Press Media Play/Pause key. */
    fun pressMediaPlayPause(device: UiDevice, settleMs: Long = 500) =
        pressKey(device, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, settleMs)

    /** Press Media Stop key. */
    fun pressMediaStop(device: UiDevice, settleMs: Long = 500) =
        pressKey(device, KeyEvent.KEYCODE_MEDIA_STOP, settleMs)

    /** Press Media Rewind key. */
    fun pressMediaRewind(device: UiDevice, settleMs: Long = 500) =
        pressKey(device, KeyEvent.KEYCODE_MEDIA_REWIND, settleMs)

    /** Press Media Fast Forward key. */
    fun pressMediaFastForward(device: UiDevice, settleMs: Long = 500) =
        pressKey(device, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, settleMs)

    /** Press Menu key. */
    fun pressMenu(device: UiDevice, settleMs: Long = 500) =
        pressKey(device, KeyEvent.KEYCODE_MENU, settleMs)

    // ---- Focus Tracking ----

    /**
     * Returns the currently focused element, or null if nothing has focus.
     * Uses the UI Automator focused(true) selector.
     */
    fun getFocusedElement(device: UiDevice): UiObject2? {
        return device.findObject(By.focused(true))
    }

    /**
     * Returns a unique identifier for the focused element, combining
     * resource ID, class name, text, and bounds.
     * Useful for tracking focus changes across D-pad presses.
     */
    fun getFocusedElementId(device: UiDevice): String? {
        val focused = getFocusedElement(device) ?: return null
        val resId = focused.resourceName ?: "no-id"
        val className = focused.className ?: "no-class"
        val text = focused.text ?: ""
        val bounds = focused.visibleBounds
        return "$resId|$className|$text|${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}"
    }

    /**
     * Returns the bounds of the currently focused element.
     */
    fun getFocusedBounds(device: UiDevice): Rect? {
        return getFocusedElement(device)?.visibleBounds
    }

    /**
     * Returns a short human-readable label for the focused element.
     */
    fun getFocusedLabel(device: UiDevice): String {
        val el = getFocusedElement(device) ?: return "(none)"
        return el.text?.take(20)
            ?: el.contentDescription?.take(20)
            ?: el.resourceName?.substringAfterLast('/')
            ?: el.className?.substringAfterLast('.')
            ?: "?"
    }

    /**
     * Returns structured info about an element for rich diagnostic output.
     * Format: {id:"resId", type:"ClassName", bounds:"l,t,r,b"}
     */
    fun elementInfo(el: UiObject2): String {
        val id = el.resourceName ?: "no-id"
        val type = el.className?.substringAfterLast('.') ?: "?"
        val b = el.visibleBounds
        return "{id:\"$id\", type:\"$type\", bounds:\"${b.left},${b.top},${b.right},${b.bottom}\"}"
    }

    // ---- Element Discovery ----

    /**
     * Returns all focusable elements currently visible on screen.
     * Searches the entire UI hierarchy for elements with focusable=true.
     */
    fun getAllFocusableElements(device: UiDevice): List<UiObject2> {
        return device.findObjects(By.focusable(true)) ?: emptyList()
    }

    /**
     * Returns all clickable elements currently visible on screen.
     */
    fun getAllClickableElements(device: UiDevice): List<UiObject2> {
        return device.findObjects(By.clickable(true)) ?: emptyList()
    }

    /**
     * Returns all scrollable containers on screen.
     */
    fun getScrollableContainers(device: UiDevice): List<UiObject2> {
        return device.findObjects(By.scrollable(true)) ?: emptyList()
    }

    /**
     * Detects grid-like layouts by finding elements that share the same Y
     * position (within tolerance) but have different X positions.
     * Returns groups of elements that form rows.
     */
    fun detectGridLayout(device: UiDevice, yTolerance: Int = 20): List<List<UiObject2>> {
        val focusable = getAllFocusableElements(device)
        if (focusable.size < 4) return emptyList()

        // Group by approximate Y center
        val rows = mutableMapOf<Int, MutableList<UiObject2>>()
        for (el in focusable) {
            val cy = el.visibleBounds.centerY()
            // Find existing row within tolerance
            val matchingRow = rows.keys.firstOrNull { Math.abs(it - cy) <= yTolerance }
            if (matchingRow != null) {
                rows[matchingRow]!!.add(el)
            } else {
                rows[cy] = mutableListOf(el)
            }
        }

        // Return only rows with 2+ elements (these form a grid)
        return rows.values
            .filter { it.size >= 2 }
            .map { row -> row.sortedBy { it.visibleBounds.left } }
    }

    /**
     * Dumps the window hierarchy as XML string for analysis.
     */
    fun dumpHierarchy(device: UiDevice): String {
        val os = ByteArrayOutputStream()
        device.dumpWindowHierarchy(os)
        return os.toString("UTF-8")
    }

    /**
     * Counts focusable elements in the hierarchy XML.
     * Useful when UiObject2 list is unreliable.
     */
    fun countFocusableInHierarchy(device: UiDevice): Int {
        val xml = dumpHierarchy(device)
        return Regex("focusable=\"true\"").findAll(xml).count()
    }

    // ---- Screen Info ----

    /** Returns screen width and height as a Pair. */
    fun getScreenDimensions(device: UiDevice): Pair<Int, Int> {
        return Pair(device.displayWidth, device.displayHeight)
    }

    /**
     * Checks if an element is approximately centered on screen.
     * "Approximately" means within 15% of the screen center.
     */
    fun isElementCentered(device: UiDevice, element: UiObject2, tolerancePercent: Float = 0.15f): Boolean {
        val (screenW, screenH) = getScreenDimensions(device)
        val bounds = element.visibleBounds
        val elementCenterX = (bounds.left + bounds.right) / 2
        val elementCenterY = (bounds.top + bounds.bottom) / 2

        val screenCenterX = screenW / 2
        val screenCenterY = screenH / 2

        val toleranceX = (screenW * tolerancePercent).toInt()
        val toleranceY = (screenH * tolerancePercent).toInt()

        return Math.abs(elementCenterX - screenCenterX) <= toleranceX &&
               Math.abs(elementCenterY - screenCenterY) <= toleranceY
    }

    // ---- Screenshots ----

    /**
     * Takes a screenshot and saves to device storage.
     * Shell scripts pull these after tests complete.
     */
    fun takeScreenshot(device: UiDevice, name: String): Boolean {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val dir = File("/sdcard/test_screenshots")
        dir.mkdirs()
        val file = File(dir, "${timestamp}_${name}.png")
        return device.takeScreenshot(file)
    }

    // ---- Waiting ----

    /** Wait for the UI to become idle. */
    fun waitForIdle(device: UiDevice, timeoutMs: Long = 3000) {
        device.waitForIdle(timeoutMs)
    }

    /**
     * Wait for a new window to appear (e.g., dialog or new activity).
     */
    fun waitForNewWindow(device: UiDevice, timeoutMs: Long = 3000): Boolean {
        return device.waitForWindowUpdate(null, timeoutMs)
    }

    /**
     * Checks if the target app is still in the foreground.
     */
    fun isAppInForeground(device: UiDevice, packageName: String = targetPackage): Boolean {
        return device.currentPackageName == packageName
    }

    // ---- Output Helpers ----

    /**
     * Returns the screen prefix for per-screen audit output.
     * Empty string when testing the default launch screen.
     */
    fun screenPrefix(): String {
        return if (targetActivity != null) "[SCREEN:${screenLabel}] " else ""
    }
}
