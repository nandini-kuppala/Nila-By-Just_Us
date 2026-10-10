package com.nila.skin.ai

import android.graphics.Bitmap
import kotlinx.coroutines.flow.StateFlow

/**
 * The on-device vision-language model, as the feature sees it.
 *
 * An interface so the feature runs three ways: standalone with its own NPU
 * model ([NpuVisionModel]), inside Nila sharing Nila's already-loaded model
 * and NPU arbiter (an adapter over Nila's NpuVlm), or with no model at all
 * ([NoVisionModel]) -- in which case every check still works on maths and
 * checklists, and the screen says the description is unavailable.
 */
interface VisionModel {
    val status: StateFlow<Status>

    /** Load if needed. Returns false when the model is absent or would not load. */
    suspend fun ensureLoaded(): Boolean

    /** One look at one picture. Null when unavailable or generation failed. */
    suspend fun look(frame: Bitmap, systemPrompt: String, userPrompt: String): String?

    fun close() {}

    sealed interface Status {
        data object Absent : Status
        data object Installed : Status
        data class Loading(val attempt: Int, val of: Int) : Status
        data class Ready(val name: String, val loadMs: Long) : Status
        data class Failed(val reason: String) : Status
    }
}

/** Lets a host app (Nila) keep one model on the NPU at a time. */
interface NpuGate {
    suspend fun claimForLoad(): Boolean = true
    fun requestLook(): Boolean = true
    fun releaseLook() {}
    fun unloaded() {}

    object Open : NpuGate
}

object NoVisionModel : VisionModel {
    override val status = kotlinx.coroutines.flow.MutableStateFlow<VisionModel.Status>(VisionModel.Status.Absent)
    override suspend fun ensureLoaded() = false
    override suspend fun look(frame: Bitmap, systemPrompt: String, userPrompt: String): String? = null
}
