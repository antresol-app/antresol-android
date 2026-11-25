package app.antresol.ui.requests

import android.text.format.DateUtils
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import app.antresol.core.model.Kind
import app.antresol.core.model.RequestSearchKind
import app.antresol.core.model.RequestStatus
import app.antresol.ui.R
import app.antresol.ui.components.AntresolIcons
import app.antresol.ui.components.kindLabel
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

/** The words for a request's [status] («Waiting for an answer», «A parent said no», …). */
@StringRes
fun requestStatusLabel(status: String): Int = when (status) {
    RequestStatus.PENDING -> R.string.request_pending
    RequestStatus.DECLINED -> R.string.request_declined
    RequestStatus.IN_LIBRARY -> R.string.request_in_library
    else -> R.string.request_approved
}

/** The name of a request's kind («Movie», «Album», «Book»); an album is asked for as `music`. */
@Composable
fun requestKindLabel(kind: String): String? = kindLabel(if (kind == RequestSearchKind.MUSIC) Kind.ALBUM else kind)

/** The icon a request's [status] shows beside its words. */
fun requestStatusIcon(status: String): ImageVector = when (status) {
    RequestStatus.PENDING -> AntresolIcons.Clock
    RequestStatus.DECLINED -> AntresolIcons.Info
    else -> AntresolIcons.CheckCircle
}

/** An answered request is shown in the accent colour of its pill, the others in the neutral one. */
fun requestStatusAccent(status: String): Boolean = status == RequestStatus.APPROVED || status == RequestStatus.IN_LIBRARY

/** "5 minutes ago" for the server's RFC 3339 [iso] time; "" when it is not one. */
fun relativeTime(iso: String, now: Long = System.currentTimeMillis()): String {
    val at = try {
        OffsetDateTime.parse(iso).toInstant().toEpochMilli()
    } catch (e: DateTimeParseException) {
        return ""
    }
    return DateUtils.getRelativeTimeSpanString(at, now, DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE).toString()
}
