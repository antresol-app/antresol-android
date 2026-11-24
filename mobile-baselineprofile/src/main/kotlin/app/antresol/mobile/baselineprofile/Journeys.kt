package app.antresol.mobile.baselineprofile

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.Until

/** The phone app's package (one Play listing with the TV app). */
const val PACKAGE = "app.antresol.mobile"

/** The tabs of the floating bar, in their order, and Home again. */
val TABS = listOf("Movies", "Shows", "TV", "Music", "Books", "Home")

private const val WAIT_MS = 30_000L

/** The fake server (scripts/fake-server.py) as the emulator reaches the computer; `-e antresolServer <url>` for another. */
private val server: String
    get() = InstrumentationRegistry.getArguments().getString("antresolServer") ?: "http://10.0.2.2:8421"

/**
 * Signs in to [server] as «Alex» (the fake server's passwordless profile) when the app is not signed in yet. The address
 * is typed, never picked from the servers found on the network: those may be real ones.
 */
fun MacrobenchmarkScope.signInIfNeeded() {
    device.wait(Until.hasObject(By.text("Continue Watching").pkg(PACKAGE)), 5_000)
    if (device.hasObject(By.text("Continue Watching"))) return
    val field = checkNotNull(device.wait(Until.findObject(By.clazz("android.widget.EditText")), WAIT_MS)) { "no address field" }
    field.text = server
    hideKeyboard()
    checkNotNull(device.wait(Until.findObject(By.text("Connect")), WAIT_MS)).click()
    val host = server.substringAfter("://").trimEnd('/')
    check(device.wait(Until.hasObject(By.text("Connected to $host")), WAIT_MS)) { "not connected to $host" }
    checkNotNull(device.wait(Until.findObject(By.text("Alex")), WAIT_MS)).click()
    check(device.wait(Until.hasObject(By.text("Continue Watching")), WAIT_MS)) { "Home did not load" }
}

/** Home is shown with its shelves. */
fun MacrobenchmarkScope.waitForHome() {
    check(device.wait(Until.hasObject(By.text("Continue Watching")), WAIT_MS)) { "Home did not load" }
}

/** Taps through every tab once (each one shown until idle). */
fun MacrobenchmarkScope.goRoundTheTabs() {
    for (tab in TABS) {
        clickText(tab)
        device.waitForIdle()
    }
}

/** Scrolls Home's shelves, opens a title's page (the poster zoom) and comes back; a grid is scrolled too. */
fun MacrobenchmarkScope.browse() {
    scroll(Direction.DOWN)
    scroll(Direction.UP)
    device.findObject(By.text("The Test Show"))?.let { show ->
        show.click()
        device.wait(Until.hasObject(By.text("Episodes")), 5_000)
        device.waitForIdle()
        device.pressBack()
        device.waitForIdle()
    }
    clickText("Movies")
    device.wait(Until.hasObject(By.text("Movie 1")), 5_000)
    device.waitForIdle()
    scroll(Direction.DOWN)
    clickText("Home")
    device.waitForIdle()
}

/** Clicks the element showing [text], looked up again when it was replaced in between. */
private fun MacrobenchmarkScope.clickText(text: String) {
    repeat(3) {
        try {
            checkNotNull(device.findObject(By.text(text))) { "nothing shows «$text»" }.click()
            return
        } catch (_: StaleObjectException) {
            device.waitForIdle()
        }
    }
}

/** Scrolls the page's list once; a list that was just replaced (a page change) is looked up again. */
private fun MacrobenchmarkScope.scroll(direction: Direction) {
    repeat(3) {
        try {
            val list = device.findObject(By.scrollable(true)) ?: return
            list.setGestureMargin(device.displayHeight / 5)
            list.scroll(direction, 1f)
            device.waitForIdle()
            return
        } catch (_: StaleObjectException) {
            device.waitForIdle()
        }
    }
}

/** Back closes the keyboard when it shows; without one it would leave the screen. */
private fun MacrobenchmarkScope.hideKeyboard() {
    device.waitForIdle()
    if (device.executeShellCommand("dumpsys input_method").contains("mInputShown=true")) device.pressBack()
    device.waitForIdle()
}
