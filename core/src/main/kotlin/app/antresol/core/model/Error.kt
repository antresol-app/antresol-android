package app.antresol.core.model

import kotlinx.serialization.Serializable

/** The server's error body: `{"error": "...", "code": "..."}`. */
@Serializable
data class ErrorBody(
    val error: String = "",
    val code: String? = null,
)
