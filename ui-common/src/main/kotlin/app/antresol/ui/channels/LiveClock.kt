package app.antresol.ui.channels

import android.content.Context
import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import app.antresol.core.channels.Channels
import java.util.Date
import kotlinx.coroutines.delay

/** The current time, updated every [intervalMs]: drives the live progress bars. */
@Composable
fun rememberNowMs(intervalMs: Long = 5_000L): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(intervalMs) {
        while (true) {
            delay(intervalMs)
            now = System.currentTimeMillis()
        }
    }
    return now
}

/** A programme time ("23:15", or "11:15 PM" with a 12-hour system clock); "" when it cannot be read. */
fun programClock(context: Context, iso: String): String {
    val ms = Channels.epochMs(iso) ?: return ""
    return DateFormat.getTimeFormat(context).format(Date(ms))
}
