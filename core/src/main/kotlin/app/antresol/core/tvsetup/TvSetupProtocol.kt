package app.antresol.core.tvsetup

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/** `POST /setup` JSON from the phone app: its server and (optionally) that server's id. */
@Serializable
data class TvSetupRequest(val n: String, val server: String, val serverId: String? = null)

/** `200` of `POST /setup` (JSON): the TV's pairing [code] on [server] (id [sid]); [device] is the TV's name. */
@Serializable
data class TvSetupAnswer(val code: String, val sid: String, val server: String, val device: String)

/** `{"error": "<code>"}` of a failed `POST /setup`. */
@Serializable
data class TvSetupErrorBody(val error: String)

/** The errors of `POST /setup`, with their HTTP status and wire code. */
enum class TvSetupError(val status: Int, val code: String) {
    BadRequest(400, "bad_request"),
    ExpiredNonce(404, "expired_nonce"),
    ServerMismatch(409, "server_mismatch"),
    NotAntresol(422, "not_antresol"),
    RateLimited(429, "rate_limited"),
    Unreachable(502, "unreachable"),
    ;

    companion object {
        fun fromCode(code: String?): TvSetupError? = entries.firstOrNull { it.code == code }
    }
}

/**
 * A failed setup. [error] is the wire error; [detail] an optional finer reason for the TV's own
 * HTML page (e.g. the server exists but is not set up yet), never sent as JSON.
 */
class TvSetupException(val error: TvSetupError, val detail: TvSetupDetail? = null, cause: Throwable? = null) :
    Exception(error.code, cause)

enum class TvSetupDetail { ServerNeedsSetup }

/** How a `POST /setup` body was sent: the answer has the same shape (JSON, or HTML/redirect for the form). */
enum class BodyKind { Json, Form }

/** A validated `POST /setup` body. */
data class ParsedSetupRequest(val kind: BodyKind, val nonce: String, val server: String, val serverId: String?)

object TvSetupProtocol {
    /** Max request body (spec: 4 KB). */
    const val MAX_BODY_BYTES = 4096
    private const val MAX_SERVER_CHARS = 512
    private val SERVER_ID = Regex("^[0-9a-fA-F]{32}$")

    val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    /**
     * Parses a `POST /setup` body by its content type: `application/json` (`n`, `server`,
     * `serverId`?) or `application/x-www-form-urlencoded` (`n`, `server`). Throws
     * [TvSetupException] ([TvSetupError.BadRequest]) for anything else or missing fields.
     */
    fun parseRequest(contentType: String?, body: ByteArray): ParsedSetupRequest {
        val type = contentType?.substringBefore(';')?.trim()?.lowercase()
        if (body.size > MAX_BODY_BYTES) throw TvSetupException(TvSetupError.BadRequest)
        val text = String(body, StandardCharsets.UTF_8)
        val (kind, fields) = when (type) {
            "application/json" -> BodyKind.Json to jsonFields(text)
            "application/x-www-form-urlencoded" -> BodyKind.Form to formFields(text)
            else -> throw TvSetupException(TvSetupError.BadRequest)
        }
        val nonce = fields["n"]?.trim().orEmpty()
        val server = fields["server"]?.trim().orEmpty()
        val serverId = fields["serverId"]?.trim()?.takeIf { it.isNotEmpty() }
        if (!SetupNonces.looksLikeNonce(nonce) || server.isEmpty() || server.length > MAX_SERVER_CHARS) {
            throw TvSetupException(TvSetupError.BadRequest)
        }
        if (serverId != null && !SERVER_ID.matches(serverId)) throw TvSetupException(TvSetupError.BadRequest)
        return ParsedSetupRequest(kind, nonce, server, serverId?.lowercase())
    }

    private fun jsonFields(text: String): Map<String, String> {
        val obj = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull()
            ?: throw TvSetupException(TvSetupError.BadRequest)
        return obj.mapNotNull { (key, value) -> (value as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.let { key to it } }.toMap()
    }

    /** `a=1&b=x%20y` → map; the first value of a repeated key wins. */
    fun formFields(text: String): Map<String, String> {
        val fields = LinkedHashMap<String, String>()
        for (pair in text.split('&')) {
            if (pair.isEmpty()) continue
            val key = decode(pair.substringBefore('='))
            val value = decode(pair.substringAfter('=', ""))
            fields.putIfAbsent(key, value)
        }
        return fields
    }

    private fun decode(part: String): String = try {
        // The String overload: the Charset one needs Android 13.
        URLDecoder.decode(part, "UTF-8")
    } catch (_: IllegalArgumentException) {
        throw TvSetupException(TvSetupError.BadRequest)
    }

    fun answerJson(answer: TvSetupAnswer): String = json.encodeToString(TvSetupAnswer.serializer(), answer)

    fun errorJson(error: TvSetupError): String = json.encodeToString(TvSetupErrorBody.serializer(), TvSetupErrorBody(error.code))
}
