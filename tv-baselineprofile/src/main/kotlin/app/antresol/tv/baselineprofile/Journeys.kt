package app.antresol.tv.baselineprofile

import android.view.KeyEvent
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Until

/** The TV app ships under the phone app's package (one Play listing). */
const val PACKAGE = "app.antresol.mobile"

private const val WAIT_MS = 30_000L

/** Focus rests this long on a tab to open it (the nav's select delay, and a little more). */
private const val TAB_REST_MS = 1_200L

private val server: String
    get() = InstrumentationRegistry.getArguments().getString("antresolServer") ?: "http://10.0.2.2:8421"

private fun MacrobenchmarkScope.focusedHas(selector: BySelector): Boolean =
    device.hasObject(By.focused(true).hasDescendant(selector)) || device.hasObject(selector.focused(true))

/** Moves focus with [key] until the focused element shows [text]. */
private fun MacrobenchmarkScope.focus(text: String, key: Int = KeyEvent.KEYCODE_DPAD_DOWN, tries: Int = 8) {
    repeat(tries) {
        if (focusedHas(By.text(text))) return
        device.pressKeyCode(key)
        device.waitForIdle()
    }
    check(focusedHas(By.text(text))) { "could not focus «$text»" }
}

/**
 * Signs in to [server] as «Alex» with the remote when the TV is not signed in yet. The address is typed, never picked
 * from the servers found on the network: those may be real ones.
 */
fun MacrobenchmarkScope.signInIfNeeded() {
    device.wait(Until.hasObject(By.text("Continue Watching")), 5_000)
    if (device.hasObject(By.text("Continue Watching"))) return
    checkNotNull(device.wait(Until.findObject(By.text("Enter the Address with the Remote")), WAIT_MS))
    focus("Enter the Address with the Remote")
    device.pressDPadCenter()
    checkNotNull(device.wait(Until.findObject(By.clazz("android.widget.EditText")), WAIT_MS))
    repeat(8) {
        if (device.hasObject(By.clazz("android.widget.EditText").focused(true))) return@repeat
        device.pressDPadDown()
        device.waitForIdle()
    }
    device.findObject(By.clazz("android.widget.EditText")).text = server
    hideKeyboard()
    focus("Connect")
    device.pressDPadCenter()
    val host = server.substringAfter("://").trimEnd('/')
    check(device.wait(Until.hasObject(By.text("Connected to $host")), WAIT_MS)) { "not connected to $host" }
    focus("Sign In with the Remote")
    device.pressDPadCenter()
    checkNotNull(device.wait(Until.findObject(By.text("Alex")), WAIT_MS))
    focus("Alex", KeyEvent.KEYCODE_DPAD_LEFT)
    device.pressDPadCenter()
    waitForHome()
}

fun MacrobenchmarkScope.waitForHome() {
    check(device.wait(Until.hasObject(By.text("Continue Watching")), WAIT_MS)) { "Home did not load" }
    device.waitForIdle()
}

/** From Home: up into the nav, right across every tab (focus opens each), and back left to Home. */
fun MacrobenchmarkScope.goRoundTheTabs() {
    device.pressDPadUp()
    device.waitForIdle()
    repeat(4) {
        device.pressDPadRight()
        Thread.sleep(TAB_REST_MS)
        device.waitForIdle()
    }
    repeat(4) {
        device.pressDPadLeft()
        Thread.sleep(TAB_REST_MS)
        device.waitForIdle()
    }
    device.pressDPadDown()
    device.waitForIdle()
}

/** Home's shelves, a title's page and back, the Movies grid. */
fun MacrobenchmarkScope.browse() {
    repeat(3) {
        device.pressDPadDown()
        device.waitForIdle()
    }
    repeat(3) {
        device.pressDPadRight()
        device.waitForIdle()
    }
    device.pressDPadCenter()
    Thread.sleep(1_500)
    device.waitForIdle()
    device.pressDPadDown()
    device.waitForIdle()
    device.pressBack()
    Thread.sleep(1_000)
    device.waitForIdle()
    device.pressBack()
    device.waitForIdle()
    device.pressDPadUp()
    device.pressDPadRight()
    Thread.sleep(TAB_REST_MS)
    device.pressDPadDown()
    repeat(4) {
        device.pressDPadDown()
        device.waitForIdle()
    }
    device.pressBack()
    device.waitForIdle()
}

/** Back closes the keyboard when it shows; without one it would leave the screen. */
private fun MacrobenchmarkScope.hideKeyboard() {
    device.waitForIdle()
    if (device.executeShellCommand("dumpsys input_method").contains("mInputShown=true")) device.pressBack()
    device.waitForIdle()
}
