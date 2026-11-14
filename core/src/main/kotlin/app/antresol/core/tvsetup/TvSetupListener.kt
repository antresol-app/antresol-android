package app.antresol.core.tvsetup

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.nio.charset.StandardCharsets
import java.util.ArrayDeque
import kotlin.concurrent.thread

/** What the TV does with a posted server address (validate it, start pairing). */
fun interface TvSetupHandler {
    /**
     * Checks [server] (and [serverId] when the phone sent one) and starts the TV's pairing.
     * Throws [TvSetupException] for the contract's errors. The returned [TvSetupAccepted.commit]
     * runs after the answer was sent (it switches the TV to its pairing QR).
     */
    suspend fun setUp(server: String, serverId: String?): TvSetupAccepted
}

class TvSetupAccepted(val answer: TvSetupAnswer, val commit: suspend () -> Unit = {})

/**
 * The TV's tiny HTTP listener of «TV setup by phone»: `GET /setup?n=` → the HTML form,
 * `POST /setup` (form or JSON) → [TvSetupHandler]. Only clients on private, link-local (or
 * loopback) addresses get an answer; at most [maxRequestsPerMinute] requests to `/setup` per
 * minute in total; bodies up to 4 KB; one nonce at a time, single use, 10 minutes.
 *
 * [start] binds a random free port on the given LAN address and returns the QR link; [renew]
 * issues a new nonce on the same port; [stop] closes the port (requests already being answered
 * finish). A successful setup stops the listener by itself after the answer was sent.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TvSetupListener(
    private val handler: TvSetupHandler,
    private val now: () -> Long = System::currentTimeMillis,
    private val maxRequestsPerMinute: Int = 10,
    private val handlerTimeoutMs: Long = 25_000,
    private val socketTimeoutMs: Int = 10_000,
) {
    private val nonces = SetupNonces(now)
    private val recent = ArrayDeque<Long>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(MAX_CONNECTIONS))

    @Volatile
    private var socket: ServerSocket? = null

    @Volatile
    private var host: String = ""

    /** True after [stop] (or a successful setup). */
    @Volatile
    var stopped = false
        private set

    /** Called once after a successful setup was answered and committed. */
    @Volatile
    var onFinished: () -> Unit = {}

    /** Binds [address]:[port] (0 = random free port) and returns the link for the QR. */
    @Synchronized
    fun start(address: InetAddress, port: Int = 0): TvSetupLink {
        check(socket == null && !stopped) { "Already started" }
        val server = ServerSocket()
        server.reuseAddress = true
        server.bind(InetSocketAddress(address, port), 8)
        socket = server
        host = address.hostAddress ?: error("No address")
        thread(name = "tv-setup-listener", isDaemon = true) { acceptLoop(server) }
        return TvSetupLink(host, server.localPort, nonces.issue())
    }

    /** A new nonce on the same port (the old QR stops working). */
    @Synchronized
    fun renew(): TvSetupLink {
        val server = checkNotNull(socket) { "Not started" }
        return TvSetupLink(host, server.localPort, nonces.issue())
    }

    /** When the current nonce expires ([now]'s clock): the screen renews the QR then. */
    fun nonceExpiresAt(): Long? = nonces.expiresAt()

    fun stop() {
        stopped = true
        runCatching { socket?.close() }
    }

    private fun acceptLoop(server: ServerSocket) {
        while (!server.isClosed) {
            val client = try {
                server.accept()
            } catch (_: SocketException) {
                break // closed
            } catch (_: IOException) {
                continue
            }
            val remote = client.inetAddress
            if (remote == null || !LocalAddresses.isPrivate(remote, allowLoopback = true)) {
                // Not from the home network: no answer at all.
                runCatching { client.close() }
                continue
            }
            scope.launch { client.use { serve(it) } }
        }
    }

    private suspend fun serve(client: Socket) {
        client.soTimeout = socketTimeoutMs
        val request = try {
            readRequest(client.getInputStream())
        } catch (e: BadHttpException) {
            respond(client, Response.text(400, "Bad Request"))
            return
        } catch (_: IOException) {
            return
        }
        val response = handle(request)
        try {
            respond(client, response.response)
        } catch (_: IOException) {
            // The phone went away; a setup still counts (it already started pairing on the TV).
        }
        response.afterAnswer?.let { commit ->
            runCatching { commit() }
            stop()
            onFinished()
        }
    }

    private class Handled(val response: Response, val afterAnswer: (suspend () -> Unit)? = null)

    private suspend fun handle(request: HttpRequest): Handled {
        if (request.path != "/setup") return Handled(Response.text(404, "Not Found"))
        val lang = TvSetupPage.language(request.headers["accept-language"])
        if (!allowRequest()) {
            val error = TvSetupError.RateLimited
            return Handled(
                if (request.method == "POST" && request.isJson) Response.json(error.status, TvSetupProtocol.errorJson(error))
                else Response.html(error.status, TvSetupPage.message(lang, error)),
            )
        }
        return when (request.method) {
            "GET" -> {
                val nonce = request.query["n"]
                Handled(
                    if (nonces.isValid(nonce)) Response.html(200, TvSetupPage.form(lang, nonce!!))
                    else Response.html(404, TvSetupPage.expired(lang)),
                )
            }
            "POST" -> post(request, lang)
            else -> Handled(Response.text(405, "Method Not Allowed").header("Allow", "GET, POST"))
        }
    }

    private suspend fun post(request: HttpRequest, lang: TvSetupPage.Lang): Handled {
        val parsed = try {
            TvSetupProtocol.parseRequest(request.headers["content-type"], request.body)
        } catch (e: TvSetupException) {
            if (request.isJson) return Handled(Response.json(e.error.status, TvSetupProtocol.errorJson(e.error)))
            // The form with an empty or broken field: show it again while the nonce works.
            val fields = runCatching { TvSetupProtocol.formFields(String(request.body, StandardCharsets.UTF_8)) }.getOrDefault(emptyMap())
            val nonce = fields["n"]
            return Handled(
                if (nonces.isValid(nonce)) Response.html(400, TvSetupPage.form(lang, nonce!!, fields["server"].orEmpty(), TvSetupError.BadRequest))
                else Response.html(404, TvSetupPage.expired(lang)),
            )
        }
        val json = parsed.kind == BodyKind.Json
        if (!nonces.claim(parsed.nonce)) {
            val error = TvSetupError.ExpiredNonce
            return Handled(if (json) Response.json(error.status, TvSetupProtocol.errorJson(error)) else Response.html(404, TvSetupPage.expired(lang)))
        }
        val accepted = try {
            withTimeout(handlerTimeoutMs) { handler.setUp(parsed.server, parsed.serverId) }
        } catch (e: Exception) {
            nonces.release(parsed.nonce)
            val failure = e as? TvSetupException ?: TvSetupException(TvSetupError.Unreachable, cause = e)
            return Handled(
                if (json) Response.json(failure.error.status, TvSetupProtocol.errorJson(failure.error))
                else Response.html(failure.error.status, TvSetupPage.form(lang, parsed.nonce, parsed.server, failure.error, failure.detail)),
            )
        }
        nonces.consume(parsed.nonce)
        val answer = accepted.answer
        val response = if (json) {
            Response.json(200, TvSetupProtocol.answerJson(answer))
        } else {
            // The server's own approval page (contract §2.6): the phone's browser signs in there if needed.
            val location = "${answer.server.trimEnd('/')}/pair?code=${answer.code}&sid=${answer.sid}"
            Response.text(303, "See Other").header("Location", location)
        }
        return Handled(response, accepted.commit)
    }

    @Synchronized
    private fun allowRequest(): Boolean {
        val time = now()
        while (recent.isNotEmpty() && time - recent.first() >= 60_000) recent.removeFirst()
        if (recent.size >= maxRequestsPerMinute) return false
        recent.addLast(time)
        return true
    }

    // region Minimal HTTP/1.1 (one request per connection, Connection: close)

    private class BadHttpException : IOException()

    private class HttpRequest(
        val method: String,
        val path: String,
        val query: Map<String, String>,
        val headers: Map<String, String>,
        val body: ByteArray,
    ) {
        val isJson: Boolean get() = headers["content-type"]?.substringBefore(';')?.trim()?.lowercase() == "application/json"
    }

    private fun readRequest(input: InputStream): HttpRequest {
        val head = ByteArrayOutputStream()
        var matched = 0
        while (matched < 4) {
            val b = input.read()
            if (b < 0) throw IOException("Closed")
            head.write(b)
            if (head.size() > MAX_HEAD_BYTES) throw BadHttpException()
            matched = when {
                b == '\r'.code && (matched == 0 || matched == 2) -> matched + 1
                b == '\n'.code && (matched == 1 || matched == 3) -> matched + 1
                b == '\r'.code -> 1
                else -> 0
            }
        }
        val lines = head.toString("ISO-8859-1").split("\r\n")
        val parts = lines.first().split(' ')
        if (parts.size != 3 || !parts[2].startsWith("HTTP/1.")) throw BadHttpException()
        val method = parts[0]
        val target = parts[1]
        val headers = lines.drop(1).filter { it.isNotEmpty() }.associate { line ->
            val colon = line.indexOf(':')
            if (colon <= 0) throw BadHttpException()
            line.substring(0, colon).trim().lowercase() to line.substring(colon + 1).trim()
        }
        if (headers.containsKey("transfer-encoding")) throw BadHttpException()
        val length = headers["content-length"]?.let { it.toIntOrNull() ?: throw BadHttpException() } ?: 0
        if (length < 0 || length > TvSetupProtocol.MAX_BODY_BYTES) throw BadHttpException()
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(body, read, length - read)
            if (n < 0) throw IOException("Closed")
            read += n
        }
        val path = target.substringBefore('?')
        val query = target.substringAfter('?', "").let { if (it.isEmpty()) emptyMap() else runCatching { TvSetupProtocol.formFields(it) }.getOrDefault(emptyMap()) }
        return HttpRequest(method, path, query, headers, body)
    }

    private class Response(val status: Int, val contentType: String, val body: ByteArray) {
        val extra = LinkedHashMap<String, String>()

        fun header(name: String, value: String) = apply { extra[name] = value }

        companion object {
            fun html(status: Int, text: String) = Response(status, "text/html; charset=utf-8", text.toByteArray(StandardCharsets.UTF_8))
            fun json(status: Int, text: String) = Response(status, "application/json", text.toByteArray(StandardCharsets.UTF_8))
            fun text(status: Int, text: String) = Response(status, "text/plain; charset=utf-8", text.toByteArray(StandardCharsets.UTF_8))
        }
    }

    private fun respond(client: Socket, response: Response) {
        val head = buildString {
            append("HTTP/1.1 ").append(response.status).append(' ').append(reason(response.status)).append("\r\n")
            append("Content-Type: ").append(response.contentType).append("\r\n")
            append("Content-Length: ").append(response.body.size).append("\r\n")
            append("Connection: close\r\n")
            append("Cache-Control: no-store\r\n")
            append("X-Content-Type-Options: nosniff\r\n")
            // The nonce is in the page URL: never send it on as a Referer.
            append("Referrer-Policy: no-referrer\r\n")
            append("Content-Security-Policy: default-src 'none'; style-src 'unsafe-inline'; form-action 'self' http: https:; frame-ancestors 'none'\r\n")
            response.extra.forEach { (name, value) -> append(name).append(": ").append(value).append("\r\n") }
            append("\r\n")
        }
        val out = client.getOutputStream()
        out.write(head.toByteArray(StandardCharsets.UTF_8))
        out.write(response.body)
        out.flush()
        client.shutdownOutput()
    }

    private fun reason(status: Int): String = when (status) {
        200 -> "OK"
        303 -> "See Other"
        400 -> "Bad Request"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        409 -> "Conflict"
        422 -> "Unprocessable Entity"
        429 -> "Too Many Requests"
        502 -> "Bad Gateway"
        else -> "Error"
    }

    // endregion

    private companion object {
        const val MAX_CONNECTIONS = 4
        const val MAX_HEAD_BYTES = 8192
    }
}
