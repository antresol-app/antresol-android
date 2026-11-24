package app.antresol.core.tvsetup

import app.antresol.core.api.AntresolClient
import app.antresol.core.api.ApiException
import app.antresol.core.api.InvalidServerAddressException
import app.antresol.core.model.PairApproved
import app.antresol.core.model.PairLookup
import app.antresol.core.pairing.PairCode
import app.antresol.core.pairing.PairingTicket
import app.antresol.core.pairing.StartedPairing
import app.antresol.core.session.NotAntresolServerException
import app.antresol.core.session.ServerNeedsSetupException
import app.antresol.core.session.SessionManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * The TV side of `POST /setup`: checks the posted address like a typed one ([SessionManager.check]:
 * Antresol's `/api/health`, set up), compares `serverId` when the phone sent one, starts pairing
 * (`POST /api/auth/pair/start`) and answers with the code. After the answer is sent the server
 * becomes the TV's server ([SessionManager.use]) and the started code goes to the pairing QR
 * screen through [ticket], so the code the phone approves is the one the TV shows and polls.
 */
class TvSetupCoordinator(
    private val session: SessionManager,
    private val ticket: PairingTicket,
    private val now: () -> Long = System::currentTimeMillis,
) : TvSetupHandler {
    override suspend fun setUp(server: String, serverId: String?): TvSetupAccepted {
        val checked = try {
            session.check(server)
        } catch (e: CancellationException) {
            throw e
        } catch (e: InvalidServerAddressException) {
            throw TvSetupException(TvSetupError.BadRequest, cause = e)
        } catch (e: NotAntresolServerException) {
            throw TvSetupException(TvSetupError.NotAntresol, cause = e)
        } catch (e: ServerNeedsSetupException) {
            // Not in the contract's list: for the phone it is "not a usable Antresol server";
            // the TV's own HTML page says what to do.
            throw TvSetupException(TvSetupError.NotAntresol, TvSetupDetail.ServerNeedsSetup, e)
        } catch (e: IOException) {
            throw TvSetupException(TvSetupError.Unreachable, cause = e)
        }
        val sid = checked.health.serverId.lowercase()
        if (serverId != null && !serverId.equals(sid, ignoreCase = true)) throw TvSetupException(TvSetupError.ServerMismatch)
        val device = session.deviceInfo()
        val start = try {
            session.anonymousClient(checked.baseUrl).pairStart(device)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            throw TvSetupException(if (e.status == 429) TvSetupError.RateLimited else TvSetupError.Unreachable, cause = e)
        } catch (e: IOException) {
            throw TvSetupException(TvSetupError.Unreachable, cause = e)
        }
        val started = StartedPairing(start, now())
        val answer = TvSetupAnswer(
            code = PairCode.normalize(start.code) ?: start.code,
            sid = start.serverId.ifBlank { sid }.lowercase(),
            server = checked.baseUrl,
            device = device.name,
        )
        return TvSetupAccepted(answer) {
            ticket.offer(started)
            session.use(checked)
        }
    }
}

/** Why the phone could not set the TV up: the TV's [error] code ([status] its HTTP status), or null for a network failure. */
class TvSetupFailure(val error: TvSetupError?, val status: Int, cause: Throwable? = null) :
    IOException(error?.code ?: "tv_setup_failed_$status", cause)

/**
 * The phone side: `POST /setup` JSON to the TV of a scanned [TvSetupLink], then `lookup` +
 * `approve` of the returned code on the phone's own signed-in server.
 */
class TvSetupSender(httpClient: OkHttpClient = AntresolClient.defaultHttpClient()) {
    // The TV checks the server and starts pairing before it answers: give it time.
    private val http = httpClient.newBuilder().readTimeout(40, TimeUnit.SECONDS).callTimeout(45, TimeUnit.SECONDS).build()

    /**
     * Tells the TV to use [server] (id [serverId]). Throws [TvSetupFailure] for the TV's error
     * answers and [IOException] when the TV cannot be reached.
     */
    suspend fun send(link: TvSetupLink, server: String, serverId: String): TvSetupAnswer = withContext(Dispatchers.IO) {
        val body = TvSetupProtocol.json.encodeToString(TvSetupRequest.serializer(), TvSetupRequest(link.nonce, server, serverId))
        val request = Request.Builder()
            .url(link.setupUrl)
            .post(body.toRequestBody("application/json".toMediaType()))
            .header("Accept", "application/json")
            .build()
        http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (response.code == 200) {
                runCatching { TvSetupProtocol.json.decodeFromString(TvSetupAnswer.serializer(), text) }.getOrNull()
                    ?.takeIf { PairCode.normalize(it.code) != null }
                    ?: throw TvSetupFailure(null, response.code)
            } else {
                val code = runCatching { TvSetupProtocol.json.decodeFromString(TvSetupErrorBody.serializer(), text).error }.getOrNull()
                throw TvSetupFailure(TvSetupError.fromCode(code), response.code)
            }
        }
    }
}

/** The approving calls on the phone's own server (as [app.antresol.core.api.AntresolClient.pairLookup] / `pairApprove`). */
interface TvSetupApprover {
    suspend fun lookup(code: String): PairLookup

    suspend fun approve(code: String, userId: Long): PairApproved
}

/** The TV answered with a code of another server than the phone's own. */
class TvSetupOtherServerException : IOException("The TV joined another server")

/**
 * The first step of the phone flow after «Настроить»: [send] (the TV call, [TvSetupSender.send]) the phone's
 * [serverUrl] / [serverId] to the TV of [link] and check that the TV joined that server (its answer's `sid`).
 * Returns the TV's answer; its code is then approved with [approveTvCode], which a retry may do alone.
 */
suspend fun sendTvSetup(
    send: suspend (TvSetupLink, String, String) -> TvSetupAnswer,
    link: TvSetupLink,
    serverUrl: String,
    serverId: String,
): TvSetupAnswer {
    val answer = send(link, serverUrl, serverId)
    if (!answer.sid.equals(serverId, ignoreCase = true)) throw TvSetupOtherServerException()
    return answer
}

/** `lookup` then `approve` of a TV's [code] on the phone's server (the step that may be retried alone). */
suspend fun approveTvCode(approver: TvSetupApprover, code: String, userId: Long) {
    approver.lookup(code)
    approver.approve(code, userId)
}
