package com.nila.monitor

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.camera.core.Preview
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.nila.actions.Distraction
import com.nila.actions.Notifier
import com.nila.actions.SootheMemory
import com.nila.actions.SoothePlayer
import com.nila.actions.Soother
import com.nila.actions.VoiceRecorder
import com.nila.data.EventKind
import com.nila.data.EventRecord
import com.nila.data.NilaDatabase
import com.nila.data.Severity
import com.nila.vision.ActivityRules
import com.nila.vision.CameraWatch
import com.nila.vision.DayNight
import com.nila.vision.FaceWatcher
import com.nila.vision.HazardRules
import com.nila.vision.RoomSensors
import com.nila.vision.SafeZone
import com.nila.vision.SafeZoneStore
import com.nila.vision.SceneDescriber
import com.nila.vision.VisionSettings
import com.nila.vision.WatchAlert
import com.nila.vision.WatchPolicy
import com.nila.vision.WatchState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The camera lane, as a service instead of a screen.
 *
 * ### Why this exists
 *
 * The safety watch used to live entirely inside `WatchScreen`: the camera, the
 * detectors and the rules were all held in `remember {}`, so they were torn
 * down when the composable left the tree. That meant the watch stopped when the
 * screen turned off, when the user switched tabs, and when Android decided to
 * dim -- and it stopped *silently*, which for a monitor is the worst way to
 * fail. A parent who put the phone on the shelf and walked away was being
 * watched over by nothing.
 *
 * A foreground service with `FOREGROUND_SERVICE_TYPE_CAMERA` is the only way
 * Android allows sustained camera access with the screen off, and it comes with
 * a persistent notification, which is the honest trade: an app that watches a
 * room continuously should be visibly doing so.
 *
 * ### Running alongside the microphone
 *
 * This is a second service, not an extension of [MonitorService], and the two
 * run at once: the audio lane holds the microphone and this one holds the
 * camera. It subscribes to [MonitorService.state] so that a posture event and a
 * cry can be reported as one thing -- a baby who rolls over in their sleep is a
 * notification, and a baby who rolls over and starts crying has probably fallen
 * or got stuck.
 */
class WatchService : LifecycleService() {

    companion object {
        private const val TAG = "WatchService"

        const val ACTION_START = "com.nila.WATCH_START"
        const val ACTION_STOP = "com.nila.WATCH_STOP"

        /** A parent asking the scene model to look at this moment. */
        const val ACTION_LOOK = "com.nila.WATCH_LOOK"

        /** A parent asking the phone to hold the baby's attention now. */
        const val ACTION_DISTRACT = "com.nila.WATCH_DISTRACT"

        private val _state = MutableStateFlow(WatchState())
        val state: StateFlow<WatchState> = _state.asStateFlow()

        /**
         * The lifted picture of a dark room, for a screen that is open.
         *
         * Only produced while a preview is attached and the lane is in night
         * mode, and only twice a second: it exists so a parent who opens the app
         * at 2am sees the cot rather than a black rectangle, and nothing about
         * the watch depends on it. Null in daylight, and null when nobody is
         * looking at the screen.
         */
        private val _nightFrame = MutableStateFlow<android.graphics.Bitmap?>(null)
        val nightFrame: StateFlow<android.graphics.Bitmap?> = _nightFrame.asStateFlow()

        /** How often the night view is refreshed while a screen is open. */
        private const val NIGHT_FRAME_INTERVAL_MS = 500L

        fun lookNow(context: Context) {
            context.startService(
                Intent(context, WatchService::class.java).setAction(ACTION_LOOK)
            )
        }

        fun distractNow(context: Context) {
            context.startService(
                Intent(context, WatchService::class.java).setAction(ACTION_DISTRACT)
            )
        }

        /**
         * The preview surface, handed over by whichever screen is showing.
         *
         * The service owns the camera because it has to outlive the screen, and
         * CameraX will not bind the same camera to two lifecycles. So the
         * screen does not bind a preview of its own -- it lends the service a
         * surface provider, and gets it back by clearing this.
         */
        @Volatile
        private var pendingPreview: Preview? = null

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, WatchService::class.java).setAction(ACTION_START),
            )
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, WatchService::class.java).setAction(ACTION_STOP)
            )
        }

        /**
         * Attach or detach a live preview without disturbing the analysis.
         *
         * Rebinding the camera is the only way to add a preview use case, so
         * this costs a bind -- a few hundred milliseconds of the watch not
         * analysing, once, when a screen opens. The alternative is either no
         * preview or no continuity, and continuity is the whole point of this
         * class.
         */
        fun attachPreview(context: Context, preview: Preview?) {
            pendingPreview = preview
            if (_state.value.running) start(context)
        }
    }

    private lateinit var notifier: Notifier
    private lateinit var db: NilaDatabase
    private lateinit var zones: SafeZoneStore
    private lateinit var sensors: RoomSensors

    private var watch: CameraWatch? = null
    private val rules = ActivityRules()
    private var wakeLock: PowerManager.WakeLock? = null

    // ---- the scene model lane
    //
    // The cheap lane above answers "is there a face, is there a body, has it
    // moved". This one answers "what is the baby actually doing, and is there
    // anything in reach that could hurt them" -- which costs a model, and so is
    // asked rarely and on evidence. [WatchPolicy] is the thing that decides
    // rarely; everything else here is what happens when it says yes.
    private val dayNight = DayNight()
    private val policy = WatchPolicy()
    private val hazardConfirmer = HazardRules.Confirmer()
    private lateinit var settings: VisionSettings
    private lateinit var sootheMemory: SootheMemory
    private var describer: SceneDescriber? = null
    private var distraction: Distraction? = null
    private var lookJob: Job? = null

    /** The parent's switches, re-read as they change. */
    @Volatile private var options = VisionSettings.Values()

    /** When the night view was last refreshed, so it is not rebuilt per frame. */
    @Volatile private var lastNightFrameAt = 0L

    /**
     * The other phone, shared with the microphone lane.
     *
     * This lane is the one that produces level 4 -- climbing, out of the safe
     * zone, face-down and not recovering -- so it is the lane whose alerts can
     * take the other phone's screen. See [com.nila.phonelink.LinkProtocol.tierFor].
     */
    private var phoneLink: com.nila.phonelink.GuardianLink? = null

    /**
     * The link to the other phone, taken the first time it can be had.
     *
     * Lazy for the reason given on [com.nila.monitor.MonitorService.link]: the
     * second phone is usually paired after this one has already been set
     * watching.
     */
    private fun link(): com.nila.phonelink.GuardianLink? {
        phoneLink?.let { if (it.isRunning) return it }
        phoneLink = runCatching {
            com.nila.phonelink.GuardianLink.acquire(this)
        }.getOrNull()
        return phoneLink
    }

    /** Last time each event alerted, so the same one does not repeat all night. */
    private val alertedAt = mutableMapOf<ActivityRules.Event, Long>()

    override fun onCreate() {
        super.onCreate()
        notifier = Notifier(this)
        db = NilaDatabase.get(this)
        zones = SafeZoneStore(this)
        sensors = RoomSensors(this)
        settings = VisionSettings(this)
        sootheMemory = SootheMemory(this)
        notifier.ensureChannels()
        link()

        // Read once synchronously so the first frame is governed by what the
        // parent actually chose, then follow the changes.
        options = runCatching { settings.current }.getOrDefault(VisionSettings.Values())
        dayNight.preference = options.nightPreference
        lifecycleScope.launch {
            settings.values.collect { chosen ->
                options = chosen
                dayNight.preference = chosen.nightPreference
            }
        }

        // The audio lane, folded in as it changes. Collected for the life of
        // the service so a cry that starts after a posture event still promotes
        // it -- the fall usually comes first and the crying a second later.
        lifecycleScope.launch {
            MonitorService.state.collect { audio ->
                val crying = audio.currentEvidence != null
                if (crying != _state.value.cryingNow) {
                    _state.value = _state.value.copy(cryingNow = crying)
                    // Re-run the decision with the cry folded in, which is what
                    // turns an ATTENTION posture into an URGENT one.
                    _state.value.assessment?.let { raiseIfNeeded(_state.value) }
                }
            }
        }

        lifecycleScope.launch {
            zones.zone.collect { zone ->
                _state.value = _state.value.copy(zone = zone)
            }
        }
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> { stopWatching(); stopSelf() }
            // A tap is not rate-limited the way the automatic cadence is: the
            // parent is looking at the screen and has asked.
            ACTION_LOOK -> if (watch != null && lookJob?.isActive != true) {
                look(WatchPolicy.Reason.MANUAL, System.currentTimeMillis())
            }
            ACTION_DISTRACT -> lifecycleScope.launch { distract(null) }
            else -> startWatching()
        }
        return START_STICKY
    }

    private fun startWatching() {
        val notification = notifier.monitoringNotification(
            "Nila is watching the cot",
            "Frames are analysed on this phone and never leave it.",
        )
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        } else 0
        ServiceCompat.startForeground(this, Notifier.ID_WATCH, notification, type)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            fault("Camera permission was not granted")
            return
        }

        // A rebind, for a preview being attached or detached mid-watch. The
        // rules and the distance baseline deliberately survive it: they are
        // about the baby, not about the camera binding.
        val existing = watch
        if (existing != null) {
            existing.stop()
            bind(existing)
            return
        }

        val camera = CameraWatch(this, ::onFrame)
        watch = camera
        rules.reset()
        alertedAt.clear()
        sensors.start()

        // The scene lane starts cold: no model is loaded until the policy asks
        // for the first look, which is what keeps a watch that is only ever
        // looking at a sleeping baby from paying for a model it never uses.
        policy.reset()
        dayNight.reset()
        hazardConfirmer.clear()
        describer = SceneDescriber(this).also { engine ->
            _state.value = _state.value.copy(visionModelInstalled = engine.anyModelInstalled)
        }
        distraction = Distraction(
            context = this,
            player = SoothePlayer(this),
            config = Distraction.Config(useTorch = options.torchEnabled),
        ).apply {
            // The torch belongs to whoever holds the camera, and that is the
            // watch. Handing the control over rather than letting Distraction
            // open its own is what stops the two fighting for the device.
            torchControl = { on -> camera.torch(on) }
        }

        acquireWakeLock()
        bind(camera)

        _state.value = _state.value.copy(
            running = true,
            fault = null,
            zone = zones.current,
        )
        Log.i(TAG, "watch started")
    }

    /**
     * Bind, and let the frames report what the detectors turned out to be.
     *
     * This used to read `camera.poseAvailable` on the line after `start()`,
     * which could only ever be false: binding the camera goes through a
     * listener on the main executor, so nothing has been built yet at the
     * moment this returns, and the answer was never asked for again. The
     * screen therefore claimed posture was unavailable for the whole session
     * while displaying the postures the pose model was producing. The truth
     * arrives with each frame instead -- see [onFrame].
     */
    private fun bind(camera: CameraWatch) {
        camera.start(this, pendingPreview)
    }

    /**
     * One analysed frame, through the rules and out to the state.
     *
     * Runs on the camera executor. Nothing here touches the database or the
     * notification manager directly -- [raiseIfNeeded] does, and it is
     * deliberately the only place that can, so there is one path to an alarm.
     */
    private fun onFrame(frame: CameraWatch.Frame) {
        val assessment = rules.update(
            ActivityRules.Observation(
                pose = frame.pose,
                faceFound = frame.face is FaceWatcher.State.FaceVisible,
                motion = frame.motion,
                baselineReady = frame.motion.relativeToBaseline > 0f,
                zone = _state.value.zone,
            )
        )
        val now = System.currentTimeMillis()
        // Two witnesses, because each is wrong in its own way: the light sensor
        // faces the ceiling and the frame is what the model will be shown.
        val luma = watch?.lastLuma ?: 0f
        val mode = dayNight.update(sensors.lux, luma, now)
        val next = _state.value.copy(
            face = frame.face,
            assessment = assessment,
            motion = frame.motion,
            framesAnalysed = watch?.framesAnalysed ?: 0L,
            lightMode = mode,
            frameLuma = luma,
            // Cheap field reads, and this is the earliest point at which they
            // are meaningful: a frame only reaches here once both detectors
            // have loaded.
            poseAvailable = watch?.poseAvailable == true,
            poseDelegate = watch?.poseDelegate ?: "-",
            cameraMoved = sensors.cameraMoved,
            lightChanged = sensors.lightChanged,
            roomDark = sensors.roomDark,
            lux = sensors.lux,
        )
        _state.value = next
        raiseIfNeeded(next)
        considerLooking(next, frame, now)
        refreshNightView(mode, now)
    }

    /**
     * Keep a lifted picture flowing while somebody is watching the screen.
     *
     * Guarded three ways, because this is the one piece of work here that is
     * purely cosmetic: only in the dark, only when a preview is attached, and
     * only twice a second. With the screen off it does nothing at all, which is
     * the state the watch spends the night in.
     */
    private fun refreshNightView(mode: com.nila.vision.LightMode, now: Long) {
        if (mode != com.nila.vision.LightMode.NIGHT || pendingPreview == null) {
            if (_nightFrame.value != null) _nightFrame.value = null
            return
        }
        if (now - lastNightFrameAt < NIGHT_FRAME_INTERVAL_MS) return
        lastNightFrameAt = now
        _nightFrame.value = runCatching { watch?.nightView() }.getOrNull()
    }

    /**
     * Ask [WatchPolicy] whether this frame is worth a model, and act on the answer.
     *
     * Runs on the camera executor and does almost nothing: the decision is a
     * handful of comparisons, and the look itself is launched onto IO. A frame
     * is never held up waiting for a model.
     */
    private fun considerLooking(state: WatchState, frame: CameraWatch.Frame, now: Long) {
        if (!options.modelEnabled) return
        val engine = describer ?: return
        if (!engine.isInstalled && !engine.npuInstalled) return

        val reason = policy.decide(
            WatchPolicy.Inputs(
                now = now,
                mode = state.lightMode,
                motionRelative = frame.motion.relativeToBaseline,
                crying = state.cryingNow,
                event = state.assessment?.primary,
                severity = state.assessment?.severity ?: Severity.NOTE,
                cameraMoved = state.cameraMoved,
                modelReady = true,
                lookInProgress = lookJob?.isActive == true,
            )
        ) ?: run {
            _state.value = _state.value.copy(asleep = policy.asleep, nextLookAt = policy.nextDueAt)
            return
        }
        look(reason, now)
    }

    /**
     * One look, on the IO dispatcher, at whatever the camera last saw.
     *
     * The frame handed over is the enhanced one in the dark -- see
     * [com.nila.vision.NightVision] -- because a model given the raw frame of an
     * unlit nursery answers about a black rectangle, confidently.
     */
    private fun look(reason: WatchPolicy.Reason, now: Long) {
        val camera = watch ?: return
        val engine = describer ?: return
        policy.looked(now)
        _state.value = _state.value.copy(
            looking = true,
            asleep = policy.asleep,
            lastLookAt = now,
            nextLookAt = policy.nextDueAt,
        )
        lookJob = lifecycleScope.launch(Dispatchers.IO) {
            val mode = _state.value.lightMode
            val bitmap = runCatching { camera.snapshotFor(mode) }.getOrNull()
            if (bitmap == null) {
                _state.value = _state.value.copy(looking = false)
                return@launch
            }
            val seen = runCatching { engine.lookNow(bitmap) }
                .onFailure { Log.w(TAG, "the scene model failed", it) }
                .getOrNull()
            runCatching { bitmap.recycle() }
            onSeen(seen, reason, engine.backendName())
        }
    }

    /**
     * What the model said, through the guard rails, and out to the parent.
     *
     * A single look never raises an alarm on its own: [HazardRules.Confirmer]
     * wants the same danger twice. One look at one frame of a dim nursery is
     * exactly the evidence a 2B model is worst at, and a monitor that cries
     * wolf is a monitor that gets turned off.
     */
    private fun onSeen(
        seen: SceneDescriber.Observation?,
        reason: WatchPolicy.Reason,
        backend: String,
    ) {
        val verdict = seen?.hazardVerdict
        val confirmed = verdict != null && hazardConfirmer.add(verdict) && verdict.isDanger
        _state.value = _state.value.copy(
            looking = false,
            observation = seen ?: _state.value.observation,
            hazard = if (confirmed) verdict else null,
            visionBackend = backend,
            nextLookAt = policy.nextDueAt,
            asleep = policy.asleep,
        )
        if (seen == null) return
        Log.i(
            TAG,
            "look ($reason, $backend): ${seen.summary}; hazard=${verdict?.level} " +
                "${verdict?.kind ?: ""} ${verdict?.hazard.orEmpty()}"
        )
        if (confirmed && verdict != null) raiseHazard(verdict, seen)
    }

    /**
     * A confirmed hazard: tell the parent, try to hold the baby's attention, log it.
     *
     * In that order. The notification goes first because it is the thing that
     * cannot fail, and the distraction second because it is the thing that
     * might: a phone with no ring light, no flash and the sound switched off
     * still wakes somebody.
     */
    private fun raiseHazard(verdict: HazardRules.Verdict, seen: SceneDescriber.Observation) {
        val body = buildString {
            append(seen.doing.ifBlank { verdict.hazard })
            if (verdict.hazard.isNotBlank() && seen.doing.isNotBlank()) {
                append(" (").append(verdict.hazard).append(")")
            }
        }
        notifier.alert(verdict.headline, body, Severity.URGENT, id = Notifier.ID_SAFETY)
        runCatching {
            link()?.sendAlert(verdict.headline, body, Severity.URGENT, seconds = 0)
        }
        _state.value = _state.value.copy(lastAlert = body)

        lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                db.events().insert(
                    EventRecord(
                        kind = EventKind.HAZARD_SEEN.name,
                        severityLevel = Severity.URGENT.level,
                        startedAtMs = System.currentTimeMillis(),
                        note = "${verdict.kind?.label ?: "hazard"}: $body",
                    )
                )
            }.onFailure { Log.w(TAG, "could not log a hazard", it) }

            if (options.distractionEnabled) distract(verdict)
        }
    }

    /**
     * Make the phone more interesting than whatever the baby is reaching for.
     *
     * The sound is chosen by [SootheMemory], which already knows which of them
     * has settled *this* baby before -- including the caregiver's own recorded
     * voice, which is the one that works. Suspending, so the choice can be read
     * from its store before anything plays.
     */
    private suspend fun distract(verdict: HazardRules.Verdict?) {
        val player = distraction ?: return
        val recorded = runCatching {
            VoiceRecorder.existing(this).map {
                Soother.Recorded(it.nameWithoutExtension, it.nameWithoutExtension, it)
            }
        }.getOrDefault(emptyList())
        val choice = runCatching { sootheMemory.choose(Soother.builtIns + recorded) }
            .getOrNull() ?: Soother.WHITE_NOISE
        val outcome = player.start(choice) ?: return

        _state.value = _state.value.copy(distraction = outcome.summary)
        runCatching {
            db.events().insert(
                EventRecord(
                    kind = EventKind.DISTRACTION_PLAYED.name,
                    severityLevel = Severity.ATTENTION.level,
                    startedAtMs = System.currentTimeMillis(),
                    note = "${verdict?.kind?.label ?: "asked for"}: ${outcome.summary}",
                )
            )
        }.onFailure { Log.w(TAG, "could not log a distraction", it) }
    }

    /** The only route from a camera frame to a woken parent. */
    private fun raiseIfNeeded(state: WatchState) {
        val event = state.assessment?.primary ?: return
        val now = System.currentTimeMillis()
        fun since(e: ActivityRules.Event) =
            alertedAt[e]?.let { now - it } ?: Long.MAX_VALUE
        val decision = WatchAlert.decide(
            state,
            sinceSameEventMs = since(event),
            sinceCameraMovedMs = since(ActivityRules.Event.CAMERA_MOVED),
        ) ?: return

        alertedAt[decision.event] = System.currentTimeMillis()
        notifier.alert(decision.title, decision.body, decision.severity,
                       id = Notifier.ID_SAFETY)
        runCatching {
            link()?.sendAlert(
                decision.title, decision.body, decision.severity, seconds = 0
            )
        }
        _state.value = _state.value.copy(lastAlert = decision.body)

        val kind = kindOf(decision.event) ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                db.events().insert(
                    EventRecord(
                        kind = kind.name,
                        severityLevel = decision.severity.level,
                        startedAtMs = System.currentTimeMillis(),
                        note = decision.body,
                    )
                )
            }.onFailure { Log.w(TAG, "could not log a safety event", it) }
        }
    }

    /**
     * The rule that fired, as a row in the log.
     *
     * Distinct kinds rather than one catch-all, because the timeline is what
     * gets shown to a doctor and "rolled onto her front, four times this week"
     * is a sentence a catch-all cannot produce.
     */
    private fun kindOf(event: ActivityRules.Event): EventKind? = when (event) {
        ActivityRules.Event.ROLLED_TO_FRONT -> EventKind.ROLLED_TO_FRONT
        ActivityRules.Event.ON_SIDE -> EventKind.ROLLED_TO_SIDE
        ActivityRules.Event.CRAWLING -> EventKind.CRAWLING
        ActivityRules.Event.SITTING_UP -> EventKind.SITTING_UP
        ActivityRules.Event.STANDING_UP -> EventKind.STANDING_UP
        ActivityRules.Event.LEFT_SAFE_ZONE -> EventKind.LEFT_SAFE_ZONE
        ActivityRules.Event.MOVED_AWAY,
        ActivityRules.Event.FAR_FROM_START -> EventKind.MOVED_AWAY
        ActivityRules.Event.OUT_OF_VIEW -> EventKind.OUT_OF_VIEW
        ActivityRules.Event.UNUSUALLY_STILL -> EventKind.STILLNESS
        ActivityRules.Event.CAMERA_MOVED -> EventKind.CAMERA_MOVED
        // These never raise an alert, so they never reach the log either.
        // Returning null rather than inventing a kind keeps the timeline free
        // of rows saying nothing happened.
        ActivityRules.Event.SETTLED,
        ActivityRules.Event.ON_BACK,
        ActivityRules.Event.FACE_ONLY,
        ActivityRules.Event.CALIBRATING -> null
    }

    private fun fault(message: String) {
        Log.e(TAG, "fault: $message")
        _state.value = _state.value.copy(running = false, fault = message)
        // A watch that stops quietly is worse than no watch, so a failure is
        // surfaced the same way an event would be.
        notifier.alert("Nila stopped watching", message, Severity.URGENT)
        stopSelf()
    }

    /**
     * A partial wake lock, for the same reason the audio lane holds one.
     *
     * Without it the CPU sleeps between frames and analysis becomes
     * unpredictable exactly when the screen is off -- which is the condition
     * this service exists for.
     */
    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "nila:watch")
            .apply { setReferenceCounted(false); acquire() }
    }

    private fun stopWatching() {
        // The model lane first: a look in flight holds a bitmap and, on the NPU
        // path, a DSP session. Cancelling before the camera goes away is what
        // keeps it from finishing against a camera that no longer exists.
        lookJob?.cancel()
        lookJob = null
        runCatching { distraction?.close() }
        distraction = null
        runCatching { describer?.close() }
        describer = null
        policy.reset()
        dayNight.reset()
        hazardConfirmer.clear()
        _nightFrame.value = null
        lastNightFrameAt = 0L

        watch?.shutdown()
        watch = null
        sensors.stop()
        rules.reset()
        alertedAt.clear()
        wakeLock?.runCatching { if (isHeld) release() }
        wakeLock = null
        pendingPreview = null
        // No goodbye: the microphone lane may still be running, and from the
        // other phone's point of view nothing has stopped. release() only tears
        // the socket down once both lanes have let go.
        if (phoneLink != null) {
            runCatching { com.nila.phonelink.GuardianLink.release(phoneLink) }
            phoneLink = null
        }
        _state.value = WatchState(zone = zones.current)
        Log.i(TAG, "watch stopped")
    }

    override fun onDestroy() {
        stopWatching()
        super.onDestroy()
    }
}
