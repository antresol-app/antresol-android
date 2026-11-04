package app.antresol.core.session

import java.util.UUID

/** Stable per-installation device ids for `POST /api/auth/token`. */
object DeviceIds {
    /** What the server accepts as `device.id`. */
    val PATTERN = Regex("^[A-Za-z0-9-]{8,64}$")

    fun generate(): String = UUID.randomUUID().toString()

    fun isValid(id: String): Boolean = PATTERN.matches(id)

    /** Returns [stored] when it is a valid id, otherwise a freshly generated one. */
    fun validOrNew(stored: String?): String = stored?.takeIf(::isValid) ?: generate()
}
