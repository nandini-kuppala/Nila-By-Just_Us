package com.nila.npu

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Who gets the Hexagon NPU when both the camera and the chat want it.
 *
 * ### Why anything has to decide
 *
 * There is one accelerator and two tenants: the scene model the camera lane asks
 * about the cot, and the 2B chat model behind Ask. Both reach it through the
 * same GenieX runtime, and the NPU does not time-share them the way a CPU would
 * -- each tenant pins its weights and its working buffers into a DSP session,
 * and the second one to arrive is the one that fails. Measured during the
 * experiments this came from: a 4096-token context is 277 MB of buffers alone,
 * and a model that could not fit reported a session error rather than falling
 * back on its own.
 *
 * ### The rule
 *
 * **Vision wins.** A parent asking a question is in the room with their phone in
 * their hand and can wait two seconds for an answer that is a little slower; a
 * baby reaching for a cable is not. So the camera takes the NPU when it needs
 * it, and the chat model is told to run on the CPU until the camera is done.
 *
 * Nothing is killed mid-sentence. [requestForVision] waits briefly for a reply
 * that is already generating, because cutting a half-written answer off is worse
 * for the parent reading it than the half second the camera waits. If the chat
 * is still busy after that, the camera says so and the caller decides -- the
 * scene lane's answer is to skip the look rather than queue up behind it, and
 * the next frame will ask again a few seconds later.
 *
 * ### Giving it back
 *
 * The chat model does not return to the NPU the moment the camera stops. A watch
 * that looks every sixty seconds would otherwise make the chat model reload its
 * weights every sixty seconds, which costs more than the CPU does. [graceMs] is
 * how long the NPU has to stay quiet before chat is told it may move back, and
 * the move itself happens at the chat model's next idle moment, never during a
 * generation.
 */
object NpuArbiter {

    private const val TAG = "NpuArbiter"

    /** How long the camera will wait for a generation in flight before giving up. */
    const val VISION_WAIT_MS = 2_000L

    /** How quiet the NPU has to be before the chat model may take it back. */
    const val GRACE_MS = 45_000L

    enum class Tenant { VISION, CHAT }

    private val lock = Any()

    @Volatile private var holder: Tenant? = null

    @Volatile private var chatBusy = false

    @Volatile private var visionReleasedAt = 0L

    /**
     * True from the moment the vision model starts loading until it is closed.
     *
     * Not the same as [holder], which is per look. This is about *residency*,
     * and it exists because of a crash measured on the phone: with Qwen already
     * built on the NPU, creating the vision model segfaulted inside Qualcomm's
     * HTP stub (`QnnApi::createDevice`, null dereference). Two models resident
     * on the NPU in one process is not a slow case, it is a dead process -- and
     * a native crash cannot be caught from Kotlin, so the only defence is never
     * to get there. One model on the NPU at a time, and the camera's goes first.
     */
    @Volatile private var visionClaim = false

    /**
     * How each chat engine gets itself off the NPU. Each returns true when it is
     * off (or was never on) and false when a reply is in flight.
     *
     * A set rather than one slot, because a recreated screen can briefly hold a
     * second engine, and the one that is not asked is the one that crashes.
     */
    private val chatEvictors = java.util.concurrent.CopyOnWriteArraySet<suspend () -> Boolean>()

    private val _chatOnCpu = MutableStateFlow(false)

    /**
     * True while the chat model should stay on the CPU.
     *
     * Exposed as a flow so the settings screen can say which processor is
     * answering questions right now, rather than claiming the NPU and being
     * quietly wrong.
     */
    val chatOnCpu: StateFlow<Boolean> = _chatOnCpu.asStateFlow()

    /** Who holds it, for the technical record and the settings screen. */
    val currentHolder: Tenant? get() = holder

    /**
     * The camera asks for the NPU.
     *
     * @param now injected so the wait can be tested without sleeping.
     * @return true when the camera may use it. False means a chat reply is still
     *   generating and the caller should skip this look rather than block a lane
     *   that has frames arriving behind it.
     */
    fun requestForVision(now: Long = System.currentTimeMillis()): Boolean = synchronized(lock) {
        _chatOnCpu.value = true
        if (chatBusy) {
            Log.i(TAG, "chat is mid-generation; vision will try again")
            return false
        }
        holder = Tenant.VISION
        true
    }

    /** A chat engine says how to move it off the NPU. */
    fun registerChatEvictor(evictor: suspend () -> Boolean) {
        chatEvictors.add(evictor)
    }

    fun unregisterChatEvictor(evictor: suspend () -> Boolean) {
        chatEvictors.remove(evictor)
    }

    /**
     * The vision model is about to put itself on the NPU: make sure nothing else
     * is there first.
     *
     * The claim is taken *before* any chat engine is asked to leave, so a chat
     * engine that starts building after this point builds on the CPU. One that
     * started before is still holding its own lock, says so by returning false,
     * and this look is skipped -- the next one evicts it.
     *
     * @return true when the NPU is clear and the vision model may load.
     */
    suspend fun claimForVisionLoad(): Boolean {
        synchronized(lock) {
            visionClaim = true
            _chatOnCpu.value = true
        }
        for (evict in chatEvictors) {
            val off = runCatching { evict() }.getOrDefault(false)
            if (!off) {
                Log.i(TAG, "chat engine is busy on the NPU; vision load deferred")
                return false
            }
        }
        return true
    }

    /**
     * The doctor visit takes the NPU the way a vision load does.
     *
     * Its two models -- Whisper, then the visit's own Qwen engine -- run one
     * after the other and never beside anything else, so the chat engine is
     * moved to the CPU first and Ask stays there until [visitDone]. False while
     * a chat reply is still being written; the caller waits and asks again.
     */
    suspend fun claimForVisit(): Boolean = claimForVisionLoad()

    /** The doctor visit has let go of the NPU. */
    fun visitDone(now: Long = System.currentTimeMillis()) = visionUnloaded(now)

    /** The vision model is off the NPU -- closed, or it never got on. */
    fun visionUnloaded(now: Long = System.currentTimeMillis()) = synchronized(lock) {
        visionClaim = false
        if (holder == Tenant.VISION) holder = null
        visionReleasedAt = now
    }

    /** The camera is done. The clock on chat's return starts here. */
    fun releaseVision(now: Long = System.currentTimeMillis()) = synchronized(lock) {
        if (holder == Tenant.VISION) holder = null
        visionReleasedAt = now
    }

    /**
     * Whether the chat model may load onto the NPU at this moment.
     *
     * Called by [com.nila.assistant.NpuLlm] when it builds its engine, and again
     * when it considers moving back. False means: build on the CPU, and ask
     * again later.
     */
    fun chatMayUseNpu(now: Long = System.currentTimeMillis()): Boolean = synchronized(lock) {
        val quietFor = now - visionReleasedAt
        val free = !visionClaim && holder != Tenant.VISION && quietFor >= GRACE_MS
        _chatOnCpu.value = !free
        free
    }

    /** The chat model has started generating; the camera must wait rather than cut in. */
    fun chatGenerationStarted() = synchronized(lock) {
        chatBusy = true
        if (holder == null) holder = Tenant.CHAT
    }

    fun chatGenerationFinished() = synchronized(lock) {
        chatBusy = false
        if (holder == Tenant.CHAT) holder = null
    }

    /** The chat model has moved to the NPU, or back off it. */
    fun chatComputeUnit(onNpu: Boolean) = synchronized(lock) {
        _chatOnCpu.value = !onNpu
    }

    /** Test seam: forget everything. */
    fun reset() = synchronized(lock) {
        holder = null
        chatBusy = false
        visionReleasedAt = 0L
        visionClaim = false
        chatEvictors.clear()
        _chatOnCpu.value = false
    }
}
