package com.nila.ddi

import android.content.Context
import com.nila.assistant.agents.DrugInteractionAgent

/**
 * The adapter between the review pipeline and the model.
 *
 * It exists so that `com.nila.assistant.agents` never imports ONNX or an
 * Android `Context`: the pipeline is unit-tested on the JVM, and the moment it
 * depends on a 20 MB session it stops being. The interface it implements is
 * two methods wide for the same reason.
 *
 * Both methods swallow everything. A medicine verdict must not be able to fail
 * because an addon did.
 */
class DrugInteractionLookupImpl(
    context: Context,
) : DrugInteractionAgent.DrugInteractionLookup {

    private val engine = DrugInteractions.shared(context)

    fun prepare() = engine.prepare()

    override fun resolve(name: String): Int? =
        runCatching { engine.resolve(name) }.getOrNull()

    override fun between(drug1: Int, drug2: Int): DrugInteractionAgent.Finding? =
        runCatching {
            engine.between(drug1, drug2)?.let {
                DrugInteractionAgent.Finding(
                    subject = it.subject,
                    other = it.other,
                    description = it.description,
                    reliable = it.reliable,
                )
            }
        }.getOrNull()
}
