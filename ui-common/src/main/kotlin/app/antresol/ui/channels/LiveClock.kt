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

/**
 * A programme time ("23:15", or "11:15 PM" with a 12-hour system clock), rounded to the nearest
 * minute ([Channels.displayMs]); "" when it cannot be read.
 */
fun programClock(context: Context, iso: String): String {
    val ms = Channels.displayMs(iso) ?: return ""
    return timeFormat(context).format(Date(ms))
}

/**
 * The clock format of the interface: 24-hour «21:40» when it is in Russian (whatever the device's
 * 12/24-hour setting, as a Russian interface always reads), else the device's own format.
 */
fun timeFormat(context: Context): java.text.DateFormat {
    val locale = context.resources.configuration.locales[0]
    return if (locale.language == "ru") java.text.SimpleDateFormat("HH:mm", locale) else DateFormat.getTimeFormat(context)
}

/**
 * The guide's day for the time at its left edge: «Сегодня», «Завтра», «Вчера», or the date
 * («Чт, 13 нояб.») further away.
 */
@Composable
fun guideDayLabel(atMs: Long, nowMs: Long): String {
    val zone = java.time.ZoneId.systemDefault()
    val day = java.time.Instant.ofEpochMilli(atMs).atZone(zone).toLocalDate()
    val today = java.time.Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
    return when (java.time.temporal.ChronoUnit.DAYS.between(today, day)) {
        0L -> androidx.compose.ui.res.stringResource(app.antresol.ui.R.string.tv_today)
        1L -> androidx.compose.ui.res.stringResource(app.antresol.ui.R.string.tv_tomorrow)
        -1L -> androidx.compose.ui.res.stringResource(app.antresol.ui.R.string.tv_yesterday)
        else -> DateFormat.format("EEE, d MMM", Date(atMs)).toString()
    }
}
