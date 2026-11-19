package app.antresol.core.channels

/**
 * Channel numbers typed on the remote, as TiviMate takes them: each digit adds to the number shown
 * as «23_»; the channel switches [COMMIT_MS] after the last digit or at once on OK. At most
 * [MAX_DIGITS] digits: a fifth starts a new number; leading zeros are dropped.
 */
object ChannelDigits {
    const val MAX_DIGITS = 4
    const val COMMIT_MS = 1_500L

    /** [typed] with [digit] added. */
    fun add(typed: String, digit: Int): String {
        require(digit in 0..9)
        val base = if (typed.length >= MAX_DIGITS) "" else typed
        return (base + digit).trimStart('0')
    }

    /** What the overlay shows: the digits and a cursor while more may come. */
    fun display(typed: String): String = if (typed.length < MAX_DIGITS) typed + "_" else typed

    /** The number to tune in, null for nothing typed. */
    fun number(typed: String): Int? = typed.toIntOrNull()

    /** A remote's digit key ([keyCode]: KEYCODE_0…9 = 7…16, NUMPAD_0…9 = 144…153), else null. */
    fun digitOf(keyCode: Int): Int? = when (keyCode) {
        in 7..16 -> keyCode - 7
        in 144..153 -> keyCode - 144
        else -> null
    }
}
