package com.nila.asklab

import android.app.Application
import androidx.lifecycle.AndroidViewModel

/**
 * A thin view onto [LabEngine], which owns everything that must outlive the
 * screen. Nothing is held here, so an activity being destroyed costs nothing.
 */
class LabViewModel(app: Application) : AndroidViewModel(app) {

    init {
        LabEngine.start(app)
    }

    val engine = LabEngine.engine
    val messages = LabEngine.messages
    val busy = LabEngine.busy
    val evalProgress = LabEngine.progress
    val lastReport = LabEngine.lastReport

    val modelPath: String get() = LabEngine.modelPath

    fun ask(question: String) = LabEngine.ask(question)
    fun clearChat() = LabEngine.clearChat()
    fun retryEngine() = LabEngine.retry()

    /** Run through the service, so the run is not tied to this screen. */
    fun runEval() = LabService.run(getApplication(), run = "eval")

    private val obeyed = mutableSetOf<Int>()

    /** Act on a command-line intent, once per intent. */
    fun obey(intent: android.content.Intent?) {
        val marker = intent?.hashCode() ?: return
        if (!obeyed.add(marker)) return
        intent.getStringExtra("run")?.let { if (it == "eval") runEval() }
        intent.getStringExtra("ask")?.let { LabService.run(getApplication(), ask = it) }
    }
}
