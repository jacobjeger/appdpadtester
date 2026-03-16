package com.appdpadtester.utils

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

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    // ---- App Launch ----

    /**
     * Launches the target app and waits for it to appear.
     * @return true if app launched successfully
     */
    fun launchApp(device: UiDevice, packageName: String = targetPackage): Boolean {
        val launcherPackage = device.launcherPackageName
        device.pressHome()
        device.wait(Until.hasObject(By.pkg(launcherPackage).depth(0)), 3000)

        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)

        return device.wait(Until.hasObject(By.pkg(packageName).depth(0)), 5000) ?: false
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
}
