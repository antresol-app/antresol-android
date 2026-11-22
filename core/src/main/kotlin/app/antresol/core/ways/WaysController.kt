package app.antresol.core.ways

import app.antresol.core.api.ApiException
import app.antresol.core.model.AddonWay
import app.antresol.core.model.RunRequest
import app.antresol.core.model.RunResult
import app.antresol.core.model.StepRequest
import app.antresol.core.model.StepRow
import app.antresol.core.model.WayCancelRequest
import app.antresol.core.model.WayRun
import app.antresol.core.model.WayState
import app.antresol.core.model.WayStep
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A way run for a title, or for one of its episodes ([season] and [episode] together). */
data class WayCall(
    val way: AddonWay,
    val target: WayTarget,
    val season: Int? = null,
    val episode: Int? = null,
    /** Where the video starts, seconds: set when the choice is made from a player that is already playing it. */
    val startSec: Double? = null,
) {
    internal fun request(ref: String? = null, quick: Boolean? = null, maxHeight: Int? = null) = RunRequest(
        way = way.id,
        kind = target.kind,
        tmdbId = target.tmdbId,
        season = season,
        episode = episode,
        ref = ref,
        startSec = startSec,
        maxHeight = maxHeight,
        quick = quick,
    )

    /** What the quick run in progress is: this way for this subject. */
    internal val run: WayRun get() = WayRun(way.key, target.kind, target.tmdbId, season, episode)

    internal val display: PlaybackDisplay
        get() = PlaybackDisplay(
            title = target.title,
            kind = target.kind,
            poster = target.poster,
            backdrop = target.backdrop,
            season = season,
            episode = episode,
        )
}

/** What came of running a way. */
sealed interface WayOutcome {
    /** Play it: open a player with [playback]. */
    data class Play(val playback: WayPlayback) : WayOutcome

    /** The addon did what was asked (a copy was started): show [text]. */
    data class Done(val text: String) : WayOutcome

    /** It failed; an [ApiException]'s message is the server's own (translated) words. */
    data class Failed(val error: Throwable) : WayOutcome
}

/**
 * The choice sheet: the steps shown so far ([steps], the last one is the one on screen), whether the
 * next one is being asked for ([loading]) or the final run is going ([running]), and the last failure.
 */
data class WayPicker(
    val call: WayCall,
    val steps: List<WayStep> = emptyList(),
    val loading: Boolean = true,
    val running: Boolean = false,
    val error: Throwable? = null,
) {
    val step: WayStep? get() = steps.lastOrNull()

    /** There is an earlier step to return to. */
    val canGoBack: Boolean get() = steps.size > 1
}

/** The ways a title offers, as the buttons of its page. */
data class WayOffer(val watch: List<AddonWay> = emptyList(), val keep: List<AddonWay> = emptyList()) {
    val isEmpty: Boolean get() = watch.isEmpty() && keep.isEmpty()
}

/**
 * Everything a title's page does with the addons' ways, for one title ([attach]): which ways it
 * offers ([offer], by the kind and scope each way declares), the state of the ways that keep a copy
 * on the server ([states], asked every [pollMs] while one is active), a way run ([start]) with its
 * choice sheet ([picker]) and what came of it ([outcomes]). The screens only draw these.
 *
 * Calls are made on [scope]; leaving the page cancels it.
 */
class WaysController(
    private val api: WaysApi,
    private val source: WaysSource,
    private val scope: CoroutineScope,
    /** The tallest picture the device shows (a way may pick a quality by it); null: no limit. */
    private val maxHeight: suspend () -> Int? = { null },
    private val pollMs: Long = POLL_MS,
    /** What each title plays with «Play» (kept by the app); by default it is forgotten with the page. */
    private val defaults: WayDefaults = MemoryWayDefaults(),
) {
    private val everything = MutableStateFlow(AddonWays.NONE)
    private val offerFlow = MutableStateFlow(WayOffer())
    private val statesFlow = MutableStateFlow<Map<String, WayState>>(emptyMap())
    private val busyFlow = MutableStateFlow<WayRun?>(null)
    private val defaultFlow = MutableStateFlow<String?>(null)
    private val pickerFlow = MutableStateFlow<WayPicker?>(null)
    private val results = Channel<WayOutcome>(Channel.UNLIMITED)

    @Volatile private var target: WayTarget? = null
    private var loadJob: Job? = null
    private var pollJob: Job? = null
    private var quickJob: Job? = null
    private var pickerJob: Job? = null

    /** The ways of the title ([attach]ed), by role. */
    val offer: StateFlow<WayOffer> = offerFlow.asStateFlow()

    /** The state of the ways that keep a copy, by [AddonWay.key]; a way not in the map is idle. */
    val states: StateFlow<Map<String, WayState>> = statesFlow.asStateFlow()

    /** The quick run that is waiting for the addon (which way, for which title or episode), else null. */
    val busy: StateFlow<WayRun?> = busyFlow.asStateFlow()

    /** The [AddonWay.key] remembered for the attached title as the one «Play» runs, or null: the first way. */
    val defaultKey: StateFlow<String?> = defaultFlow.asStateFlow()

    /** The open choice sheet, or null. */
    val picker: StateFlow<WayPicker?> = pickerFlow.asStateFlow()

    /** What to do after a run: collect it once for the page's lifetime. */
    val outcomes: Flow<WayOutcome> = results.receiveAsFlow()

    /** Starts for [target] (again for another one): asks the session's ways and the state of its keep ways. */
    fun attach(target: WayTarget) {
        this.target = target
        defaultFlow.value = defaults.get(target.kind, target.tmdbId)
        loadJob?.cancel()
        loadJob = scope.launch {
            val all = source.get()
            everything.value = all
            offerFlow.value = WayOffer(all.watchWays(target), all.keepWays(target))
            statesFlow.value = emptyMap()
            refreshStates()
        }
    }

    /** The ways for one episode of the attached title ([inLibrary]: the episode is in the library). */
    fun forEpisode(inLibrary: Boolean): List<AddonWay> {
        val t = target ?: return emptyList()
        return everything.value.forTarget(t, episode = true, episodeInLibrary = inLibrary)
    }

    /** Asks the state of the keep ways now, and again every [pollMs] while one is active. */
    fun refreshStates() {
        val t = target ?: return
        pollJob?.cancel()
        pollJob = scope.launch {
            while (true) {
                val keeps = offerFlow.value.keep
                if (keeps.isEmpty()) return@launch
                var anyActive = false
                for (addonId in keeps.map { it.addonId }.distinct()) {
                    val fresh = try {
                        api.state(addonId, t.kind, t.tmdbId, null).ways
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // Keep what was shown; an addon that keeps failing is asked again next round.
                        statesFlow.value.filterKeys { it.startsWith("$addonId/") }.let { old -> anyActive = anyActive || old.values.any { it.active } }
                        continue
                    }
                    statesFlow.update { old ->
                        old.filterKeys { !it.startsWith("$addonId/") } + keeps.filter { it.addonId == addonId }.associate { way ->
                            way.key to (fresh[way.id] ?: WayState())
                        }
                    }
                    anyActive = anyActive || fresh.values.any { it.active }
                }
                if (!anyActive) return@launch
                delay(pollMs)
            }
        }
    }

    /**
     * Runs [call]: at once (`quick`; the addon uses what it remembers or finds best, and opens the
     * choice when it cannot) or, with [choose], through the choice sheet from its first step. A way
     * that keeps a copy on the server always goes through the sheet, as on the web: what is kept (the
     * quality, the release) is chosen first.
     */
    fun start(call: WayCall, choose: Boolean = false, makeDefault: Boolean = false) {
        if (makeDefault && !call.way.isKeep) rememberDefault(call)
        if (choose || call.way.isKeep) {
            openPicker(call)
            return
        }
        // A tap while a quick run is still going is the same tap twice.
        if (busyFlow.value != null) return
        busyFlow.value = call.run
        quickJob = scope.launch {
            try {
                val request = call.request(quick = true, maxHeight = maxHeight())
                deliver(call, request, api.run(call.way.addonId, request), fromPicker = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed(e)
            } finally {
                busyFlow.value = null
            }
        }
    }

    /** Remembers [call]'s way as the one «Play» runs for its title (and its episodes) from now on. */
    private fun rememberDefault(call: WayCall) {
        defaults.set(call.target.kind, call.target.tmdbId, call.way.key)
        if (call.target.kind == target?.kind && call.target.tmdbId == target?.tmdbId) defaultFlow.value = call.way.key
    }

    /** A row of the open sheet: the last step's rows run the way, the others open the next step. */
    fun choose(row: StepRow) {
        val picker = pickerFlow.value ?: return
        if (picker.loading || picker.running) return
        pickerJob?.cancel()
        if (row.isFinal) {
            pickerFlow.update { it?.copy(running = true, error = null) }
            pickerJob = scope.launch { runFinal(picker.call, row.ref) }
        } else {
            pickerFlow.update { it?.copy(loading = true, error = null) }
            pickerJob = scope.launch { loadStep(picker.call, row.ref) }
        }
    }

    /** Back to the step before; stops what is being asked first; with nothing before, closes the sheet. */
    fun back() {
        val picker = pickerFlow.value ?: return
        if (picker.loading || picker.running) {
            pickerJob?.cancel()
            if (picker.steps.isEmpty()) dismiss() else pickerFlow.update { it?.copy(loading = false, running = false, error = null) }
            return
        }
        if (picker.canGoBack) pickerFlow.update { it?.copy(steps = it.steps.dropLast(1), error = null) } else dismiss()
    }

    /** Closes the sheet (and stops what it waits for). */
    fun dismiss() {
        pickerJob?.cancel()
        pickerFlow.value = null
    }

    /** «Cancel» of a keep way that is active: stops it and shows what the server says. */
    fun cancel(way: AddonWay) {
        val t = target ?: return
        scope.launch {
            try {
                val result = api.cancel(way.addonId, WayCancelRequest(way.id, t.kind, t.tmdbId))
                result.done?.let { results.trySend(WayOutcome.Done(it)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed(e)
            }
            refreshStates()
        }
    }

    private fun openPicker(call: WayCall) {
        pickerJob?.cancel()
        pickerFlow.value = WayPicker(call)
        pickerJob = scope.launch { loadStep(call, ref = null) }
    }

    private suspend fun loadStep(call: WayCall, ref: String?) {
        try {
            val step = api.step(
                call.way.addonId,
                StepRequest(call.way.id, call.target.kind, call.target.tmdbId, call.season, call.episode, ref),
            )
            pickerFlow.update { it?.copy(steps = it.steps + step, loading = false, error = null) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (isGone(e)) {
                dismiss()
                failed(e)
            } else {
                pickerFlow.update { it?.copy(loading = false, error = e) }
            }
        }
    }

    private suspend fun runFinal(call: WayCall, ref: String) {
        try {
            val request = call.request(ref = ref, maxHeight = maxHeight())
            deliver(call, request, api.run(call.way.addonId, request), fromPicker = true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (isGone(e)) {
                dismiss()
                failed(e)
            } else {
                pickerFlow.update { it?.copy(running = false, error = e) }
            }
        }
    }

    private fun deliver(call: WayCall, request: RunRequest, result: RunResult, fromPicker: Boolean) {
        val outcome: WayOutcome? = when {
            result.input != null -> WayOutcome.Play(WayPlayback.Input(result.input, call.display, call))
            result.play != null -> WayOutcome.Play(WayPlayback.Direct(call.way.addonId, request, result.play, call.display, call))
            result.done != null -> WayOutcome.Done(result.done)
            result.pick && !fromPicker -> {
                openPicker(call)
                return
            }
            else -> null
        }
        if (outcome == null) {
            if (fromPicker) pickerFlow.update { it?.copy(running = false, error = ApiException(200, "Unexpected server response")) }
            else failed(ApiException(200, "Unexpected server response"))
            return
        }
        if (fromPicker) pickerFlow.value = null
        results.trySend(outcome)
        if (outcome is WayOutcome.Done) refreshStates()
    }

    /** A way the server no longer knows (404): the ways are asked for again, so it disappears. */
    private fun isGone(e: Exception): Boolean = (e as? ApiException)?.isNotFound == true

    private fun failed(e: Exception) {
        if (isGone(e)) {
            source.invalidate()
            target?.let(::attach)
        }
        results.trySend(WayOutcome.Failed(e))
    }

    companion object {
        /** How often the state of a running keep way is asked. */
        const val POLL_MS = 5_000L
    }
}
