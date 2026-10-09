package com.nila.monitor

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.util.Size
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.nila.actions.Notifier
import com.nila.data.EventKind
import com.nila.data.EventRecord
import com.nila.data.NilaDatabase
import com.nila.data.Severity
import com.nila.vision.ActivityRules
import com.nila.vision.CameraWatch
import com.nila.vision.DayNight
import com.nila.vision.FaceWatcher
import com.nila.vision.LightMode
import com.nila.vision.RoomSensors
import com.nila.vision.SafeZone
import com.nila.vision.SafeZoneStore
import com.nila.vision.VisionSettings
import com.nila.vision.WatchAlert
import com.nila.vision.WatchState
import com.nila.vision.guard.AlarmController
import com.nila.vision.guard.AlertMusic
import com.nila.vision.guard.AlertSource
import com.nila.vision.guard.BabyDetector
import com.nila.vision.guard.BabyTracker
import com.nila.vision.guard.BitmapFrame
import com.nila.vision.guard.CameraXFrame
import com.nila.vision.guard.FrameAnalyzer
import com.nila.vision.guard.GuardFrame
import com.nila.vision.guard.GuardStatus
import com.nila.vision.guard.LookResult
import com.nila.vision.guard.NightEnhancer
import com.nila.vision.guard.NormBox
import com.nila.vision.guard.ParentAlert
import com.nila.vision.guard.RemoteVlm
import com.nila.vision.guard.Verdict
import com.nila.vision.guard.VisionLoop
import com.nila.vision.guard.VlmStatus
import com.nila.vision.guard.WideCamera
import com.nila.vision.guard.ZoneRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

/**
 * The camera lane, as a foreground service: the safety watch.
 *
 * ### Three modes, each on its own or together
 *
 * The watch logic is Baby Guard CCTV's, moved into Nila's service:
 *
 *  - **Base** -- face presence, pose and movement ([ActivityRules]), no vision model. Cheap enough for a
 *    whole night.
 *  - **Danger AI** -- Intern3.5-VL-2B on the NPU looks back to back ([VisionLoop]); its four-line answer
 *    goes through Baby Guard's guard rails ([com.nila.vision.guard.SafetyGuard]) and the first DANGER
 *    raises the alert. A still scene is looked at less often when the phone is warm.
 *  - **Safe zone** -- EfficientDet-Lite0 finds the baby four times a second on the GPU, a tracker
 *    carries the box between hits with motion, and [ZoneRule] alerts when 40 % of the baby is outside
 *    the drawn zone for a second.
 *
 * Whatever is switched on, the lane shares one camera, one automatic night-vision decision (the light
 * sensor and the picture, held while an alert's own lights are on) and one alert: the flashing screen,
 * the ring light, the torch strobe and the lullaby at the phone's media volume -- or, when the frames come
 * from a CCTV camera and the phone is with the parent, a notification, a chime and vibration instead.
 * "Look now" asks the model once in any mode; "Distract" plays the lullaby and lights the ring.
 *
 * ### Sources
 *
 * The phone's main lens (CameraX, bound here so it keeps running with the screen off), the ultra-wide
 * lens (Camera2, camera 3, which vivo hides from CameraX), or a CCTV camera over RTSP. The RTSP player
 * needs a surface, so it lives on the Watch screen and hands its frames to [offerCctvFrame].
 *
 * ### Running alongside the microphone
 *
 * This is a second service beside [MonitorService]; it subscribes to the audio lane so a posture event
 * and a cry are reported as one thing in the base mode.
 */
class WatchService : LifecycleService() {

    companion object {
        private const val TAG = "NilaGuard"

        const val ACTION_START = "com.nila.WATCH_START"
        const val ACTION_STOP = "com.nila.WATCH_STOP"

        /** A parent asking the vision model to look at this moment. */
        const val ACTION_LOOK = "com.nila.WATCH_LOOK"

        /** A parent asking the phone to hold the baby's attention now. */
        const val ACTION_DISTRACT = "com.nila.WATCH_DISTRACT"

        /** A parent stopping the alert (or a distraction) that is on. */
        const val ACTION_STOP_ALERT = "com.nila.WATCH_STOP_ALERT"

        /** Change the guard options from extras (test hook); also read on START. */
        const val ACTION_CONFIG = "com.nila.WATCH_CONFIG"

        private val _state = MutableStateFlow(WatchState())
        val state: StateFlow<WatchState> = _state.asStateFlow()

        /** The night-vision picture of a dark room, for a screen that is open. Null in daylight. */
        private val _nightFrame = MutableStateFlow<Bitmap?>(null)
        val nightFrame: StateFlow<Bitmap?> = _nightFrame.asStateFlow()

        /** Frames of the ultra-wide lens, which has no CameraX preview, for a screen that is open. */
        private val _liveFrame = MutableStateFlow<Bitmap?>(null)
        val liveFrame: StateFlow<Bitmap?> = _liveFrame.asStateFlow()

        /** How often a picture is pushed to an open screen. */
        private const val SCREEN_FRAME_INTERVAL_MS = 90L

        /** The base lane (face + pose) runs at about 5 frames a second, as it always has. */
        private const val BASE_INTERVAL_MS = 200L

        /** Frames are analysed at most about 15 times a second. */
        private const val ANALYSE_INTERVAL_MS = 66L

        private const val DETECT_INTERVAL_MS = 250L
        private const val VISION_ALERT_MIN_MS = 10_000L
        private const val PARENT_COOLDOWN_MS = 0L
        private const val POKE_CELLS = 3
        private const val DISTRACT_MS = 20_000L
        private const val CCTV_LOST_MS = 6_000L
        private const val CCTV_GRACE_MS = 10_000L
        private const val MAX_VLM_RETRIES = 5

        /** A base-mode alert ends once nothing urgent has been seen for this long. */
        private const val BASE_CLEAR_MS = 5_000L

        @Volatile private var instance: WatchService? = null

        /** True while the Watch screen is on screen (pictures are only made for it then). */
        @Volatile var screenVisible: Boolean = false

        fun lookNow(context: Context) {
            context.startService(Intent(context, WatchService::class.java).setAction(ACTION_LOOK))
        }

        fun distractNow(context: Context) {
            context.startService(Intent(context, WatchService::class.java).setAction(ACTION_DISTRACT))
        }

        fun stopAlert(context: Context) {
            context.startService(Intent(context, WatchService::class.java).setAction(ACTION_STOP_ALERT))
        }

        @Volatile
        private var pendingPreview: Preview? = null

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, WatchService::class.java).setAction(ACTION_START),
            )
        }

        fun stop(context: Context) {
            context.startService(Intent(context, WatchService::class.java).setAction(ACTION_STOP))
        }

        /** Attach or detach the main lens's live preview (costs one rebind of the camera). */
        fun attachPreview(context: Context, preview: Preview?) {
            pendingPreview = preview
            if (_state.value.running) instance?.rebindMainIfActive()
        }

        /** A frame from the CCTV player on the Watch screen. The service owns it from here. */
        fun offerCctvFrame(bitmap: Bitmap) {
            val svc = instance
            if (svc == null) bitmap.recycle() else svc.onCctvBitmap(bitmap)
        }

        /** What the CCTV player on the Watch screen knows: connection and picture size. */
        fun reportCctv(connected: Boolean, width: Int, height: Int) {
            instance?.onCctvReport(connected, width, height)
        }
    }

    private lateinit var notifier: Notifier
    private lateinit var db: NilaDatabase
    private lateinit var zones: SafeZoneStore
    private lateinit var cctvZones: SafeZoneStore
    private lateinit var sensors: RoomSensors
    private lateinit var settings: VisionSettings
    private lateinit var alarm: AlarmController
    private lateinit var music: AlertMusic
    private lateinit var parent: ParentAlert

    private val main = Handler(Looper.getMainLooper())
    private val analysisExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "nila-guard-frames") }
    private val detectExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "nila-guard-detect") }

    @Volatile private var running = false
    private var wakeLock: PowerManager.WakeLock? = null

    /** The parent's switches, re-read as they change. */
    @Volatile private var options = VisionSettings.Values()
    @Volatile private var phoneZone: SafeZone = SafeZone.DEFAULT
    @Volatile private var cctvZone: SafeZone = SafeZone.DEFAULT
    private val zone: SafeZone get() = if (options.source == VisionSettings.Source.CCTV) cctvZone else phoneZone

    // ---- camera sources
    private var provider: ProcessCameraProvider? = null
    @Volatile private var camera: androidx.camera.core.Camera? = null
    private var wide: WideCamera? = null
    @Volatile private var activeSource: String = "none"
    @Volatile private var lensNote: String? = null
    @Volatile private var iso = 0
    @Volatile private var exposureMs = 0.0
    @Volatile private var lastAnalysedAt = 0L

    // ---- CCTV (frames come from the Watch screen)
    @Volatile private var cctvLastFrameAt = 0L
    @Volatile private var cctvConnected = false
    @Volatile private var cctvWidth = 0
    @Volatile private var cctvHeight = 0
    @Volatile private var feedLost = false
    @Volatile private var sourceStartedAt = 0L
    @Volatile private var vlmInstalled = false

    // ---- shared per-frame processing (Baby Guard's FrameAnalyzer: night picture + motion)
    private val analyzer = FrameAnalyzer()
    @Volatile private var nightActive = false
    @Volatile private var nightAuto = false
    @Volatile private var nightFrozenUntil = 0L
    @Volatile private var meanLuma = -1.0
    @Volatile private var fps = 0f
    private var frameCount = 0
    private var fpsWindowStart = 0L
    private var lastStatusLog = 0L
    private var lastScreenFrameAt = 0L
    @Volatile private var frameRequest: CompletableDeferred<Bitmap?>? = null
    @Volatile private var thermal = 0

    // ---- base lane (Nila's face + pose + movement rules)
    private var baseWatch: CameraWatch? = null
    private val rules = ActivityRules()
    @Volatile private var lastBaseAt = 0L
    @Volatile private var lastBaseUrgentAt = 0L
    private val alertedAt = mutableMapOf<ActivityRules.Event, Long>()

    // ---- safe zone lane
    private val tracker = BabyTracker()
    private val zoneRule = ZoneRule()
    @Volatile private var detector: BabyDetector? = null
    @Volatile private var detectBusy = false
    @Volatile private var lastDetectAt = 0L
    @Volatile private var detectMs = 0L

    // ---- Danger AI lane
    private var vlm: RemoteVlm? = null
    private var vision: VisionLoop? = null
    private var vlmLoadJob: Job? = null
    @Volatile private var vlmStatus: VlmStatus = VlmStatus.Off
    @Volatile private var pendingLook = false
    @Volatile private var vlmRetries = 0
    @Volatile private var lastLook: LookResult? = null
    @Volatile private var looks = 0
    @Volatile private var safeStreak = 0

    // ---- the alert
    @Volatile private var alertSource: AlertSource? = null
    @Volatile private var alertReason = ""
    @Volatile private var alertStartedAt = 0L
    @Volatile private var cooldownUntil = 0L
    @Volatile private var musicWithAlert = false
    @Volatile private var distractUntil = 0L

    private var tickerJob: Job? = null
    private var thermalListener: Any? = null

    /** The other phone, shared with the microphone lane. */
    private var phoneLink: com.nila.phonelink.GuardianLink? = null

    private fun link(): com.nila.phonelink.GuardianLink? {
        phoneLink?.let { if (it.isRunning) return it }
        phoneLink = runCatching { com.nila.phonelink.GuardianLink.acquire(this) }.getOrNull()
        return phoneLink
    }

    private fun log(msg: String) = Log.i(TAG, msg)

    override fun onCreate() {
        super.onCreate()
        instance = this
        notifier = Notifier(this)
        db = NilaDatabase.get(this)
        zones = SafeZoneStore(this)
        cctvZones = SafeZoneStore(this, SafeZoneStore.CCTV)
        sensors = RoomSensors(this)
        settings = VisionSettings(this)
        alarm = AlarmController(this, ::log).apply { onAutoStop = { main.post { stopAlert(byParent = false) } } }
        music = AlertMusic(this, ::log)
        parent = ParentAlert(this, ::log)
        notifier.ensureChannels()
        link()
        analysisExecutor.execute { music.prepare() }

        options = runCatching { settings.current }.getOrDefault(VisionSettings.Values())
        phoneZone = runCatching { zones.current }.getOrDefault(SafeZone.DEFAULT)
        cctvZone = runCatching { cctvZones.current }.getOrDefault(SafeZone.DEFAULT)
        lifecycleScope.launch {
            settings.values.collect { chosen ->
                val before = options
                options = chosen
                if (running) main.post { onOptionsChanged(before, chosen) } else publish()
            }
        }

        // The audio lane, folded in as it changes: in the base mode a roll plus a cry is louder.
        lifecycleScope.launch {
            MonitorService.state.collect { audio ->
                val crying = audio.currentEvidence != null
                if (crying != _state.value.cryingNow) {
                    _state.update { it.copy(cryingNow = crying) }
                    _state.value.assessment?.let { raiseIfNeeded(_state.value) }
                }
            }
        }
        lifecycleScope.launch { zones.zone.collect { z -> phoneZone = z; publishZone() } }
        lifecycleScope.launch { cctvZones.zone.collect { z -> cctvZone = z; publishZone() } }
        publish()
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> { stopWatching(); stopSelf() }
            ACTION_LOOK -> if (running) lookNow()
            ACTION_DISTRACT -> if (running) distract()
            ACTION_STOP_ALERT -> { stopAlert(byParent = true); stopDistract() }
            ACTION_CONFIG -> applyExtras(intent)
            else -> {
                applyExtras(intent)
                startWatching()
            }
        }
        return START_STICKY
    }

    // ------------------------------------------------------------------ start / stop

    private fun startWatching() {
        val notification = notifier.monitoringNotification(
            "Nila is watching the cot",
            "Frames are analysed on this phone and never leave it.",
        )
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA else 0
        ServiceCompat.startForeground(this, Notifier.ID_WATCH, notification, type)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            fault("Camera permission was not granted")
            return
        }
        if (running) {
            // A START while running: a preview may have been attached; rebind if the main lens is in use.
            rebindMainIfActive()
            return
        }
        running = true
        rules.reset()
        alertedAt.clear()
        tracker.reset()
        zoneRule.reset()
        analyzer.reset()
        nightActive = false
        nightAuto = false
        sensors.start()
        acquireWakeLock()
        registerThermal()
        // One engine for the life of the watch; it only goes onto the NPU when a mode or a look needs it.
        vlm = RemoteVlm(this, ::log, onState = { st -> vlmStatus = st; publish() }, onDied = ::onVlmDied)
            .also { vlmInstalled = runCatching { it.isInstalled() }.getOrDefault(false) }
        _state.update { WatchState(running = true, zone = zone, cryingNow = it.cryingNow) }

        applyCamera()
        applyModes()
        tickerJob = lifecycleScope.launch {
            while (isActive) {
                delay(250)
                tick()
            }
        }
        log("WATCH started base=${options.baseOn} vlm=${options.vlmOn} zone=${options.zoneOn} " +
            "source=${options.source} lens=${options.lens}")
        publish()
    }

    private fun stopWatching() {
        if (!running && alertSource == null) {
            _state.update { WatchState(zone = zone) }
            return
        }
        running = false
        tickerJob?.cancel()
        tickerJob = null
        stopAlert(byParent = false)
        stopDistract()
        vision?.stop()
        vision = null
        vlmLoadJob?.cancel()
        vlmLoadJob = null
        runCatching { vlm?.close() }
        vlm = null
        vlmStatus = VlmStatus.Off
        pendingLook = false
        vlmRetries = 0
        lastLook = null
        stopCamera()
        closeBase()
        closeDetector()
        tracker.reset()
        zoneRule.reset()
        unregisterThermal()
        sensors.stop()
        runCatching { parent.feedLost(false) }
        feedLost = false
        _nightFrame.value = null
        _liveFrame.value = null
        wakeLock?.runCatching { if (isHeld) release() }
        wakeLock = null
        pendingPreview = null
        if (phoneLink != null) {
            runCatching { com.nila.phonelink.GuardianLink.release(phoneLink) }
            phoneLink = null
        }
        _state.update { WatchState(zone = zone, cryingNow = it.cryingNow) }
        publish()
        log("WATCH stopped")
    }

    override fun onDestroy() {
        stopWatching()
        runCatching { music.release() }
        runCatching { parent.release() }
        analysisExecutor.shutdown()
        detectExecutor.shutdown()
        if (instance === this) instance = null
        super.onDestroy()
    }

    private fun fault(message: String) {
        Log.e(TAG, "fault: $message")
        _state.update { it.copy(running = false, fault = message) }
        notifier.alert("Nila stopped watching", message, Severity.URGENT)
        stopSelf()
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "nila:watch")
            .apply { setReferenceCounted(false); acquire() }
    }

    // ------------------------------------------------------------------ options

    private fun onOptionsChanged(before: VisionSettings.Values, now: VisionSettings.Values) {
        if (before.source != now.source || before.lens != now.lens ||
            (now.source == VisionSettings.Source.CCTV && before.cctvUrl != now.cctvUrl)
        ) {
            applyCamera()
        }
        if (before.baseOn != now.baseOn || before.vlmOn != now.vlmOn || before.zoneOn != now.zoneOn ||
            before.modelEnabled != now.modelEnabled
        ) {
            applyModes()
        }
        if (before.alertsOn && !now.alertsOn) quietAlert()
        alarm.torchEnabled = now.torchEnabled
        if (!now.distractionEnabled && musicWithAlert) {
            musicWithAlert = false
            music.stop()
        }
        log("WATCH options base=${now.baseOn} vlm=${now.vlmOn} zone=${now.zoneOn} alerts=${now.alertsOn} " +
            "music=${now.distractionEnabled} flash=${now.torchEnabled} night=${now.nightPreference} " +
            "source=${now.source} lens=${now.lens}")
        publish()
    }

    /** Starts / stops each lane for the modes that are on. Main thread. */
    private fun applyModes() {
        val o = options
        // Base
        if (o.baseOn) ensureBase() else closeBase()
        // Safe zone
        if (o.zoneOn) {
            ensureDetector()
        } else {
            if (alertSource == AlertSource.ZONE) stopAlert(byParent = false)
            tracker.reset()
            zoneRule.reset()
        }
        // Danger AI
        applyVision()
        if (!o.baseOn && alertSource == AlertSource.BASE) stopAlert(byParent = false)
        publish()
    }

    /** Test hook: options from intent extras, saved like the screen's switches. */
    private fun applyExtras(intent: Intent?) {
        val x = intent?.extras ?: return
        lifecycleScope.launch {
            if (x.containsKey("base") || x.containsKey("vlm") || x.containsKey("zone")) {
                settings.setModes(
                    base = if (x.containsKey("base")) x.getBoolean("base") else null,
                    vlm = if (x.containsKey("vlm")) x.getBoolean("vlm") else null,
                    zone = if (x.containsKey("zone")) x.getBoolean("zone") else null,
                )
            }
            if (x.containsKey("alerts")) settings.setAlertsOn(x.getBoolean("alerts"))
            if (x.containsKey("music")) settings.setDistractionEnabled(x.getBoolean("music"))
            if (x.containsKey("flash")) settings.setTorchEnabled(x.getBoolean("flash"))
            x.getString("night")?.let { n ->
                val pref = when (n.lowercase()) {
                    "on", "night" -> DayNight.Preference.ALWAYS_NIGHT
                    "off", "day" -> DayNight.Preference.ALWAYS_DAY
                    else -> DayNight.Preference.AUTO
                }
                settings.setNightPreference(pref)
            }
            x.getString("source")?.let { s ->
                VisionSettings.Source.entries.firstOrNull { it.name.equals(s, true) }?.let { settings.setSource(it) }
            }
            x.getString("lens")?.let { l ->
                VisionSettings.Lens.entries.firstOrNull { it.name.equals(l, true) }?.let { settings.setLens(it) }
            }
            x.getString("cctv_url")?.let { settings.setCctvUrl(it) }
            x.getString("zonebox")?.let { spec ->
                val v = spec.split(',').mapNotNull { it.trim().toFloatOrNull() }
                if (v.size == 4) {
                    val z = SafeZone(v[0], v[1], v[2], v[3], configured = true)
                    val src = x.getString("source")?.let { s ->
                        VisionSettings.Source.entries.firstOrNull { it.name.equals(s, true) }
                    } ?: options.source
                    if (src == VisionSettings.Source.CCTV) cctvZones.save(z) else zones.save(z)
                }
            }
            if (x.getBoolean("look")) main.postDelayed({ if (running) lookNow() }, 300)
            if (x.getBoolean("distract")) main.postDelayed({ if (running) distract() }, 300)
            if (x.getBoolean("stop_alert")) main.post { stopAlert(byParent = true); stopDistract() }
        }
    }

    // ------------------------------------------------------------------ camera sources

    /** Phone main lens: CameraX. Ultra-wide: [WideCamera]. CCTV: frames from the screen. Main thread. */
    private fun applyCamera() {
        if (!running) return
        stopCamera()
        analyzer.reset()
        tracker.reset()
        lensNote = null
        sourceStartedAt = System.currentTimeMillis()
        when {
            options.source == VisionSettings.Source.CCTV -> {
                activeSource = "cctv"
                alarm.cameraTorch = null // the phone's own torch (CameraManager): the phone camera is not in use
                cctvLastFrameAt = 0L
                log("WATCH source=CCTV url=${options.cctvUrl} (the player runs on the Watch screen)")
            }
            options.lens == VisionSettings.Lens.WIDE -> {
                val w = WideCamera(this, ::analyzeFrameThrottled, ::log)
                if (w.start()) {
                    wide = w
                    activeSource = "wide"
                    alarm.cameraTorch = if (w.hasFlash) ({ on -> w.setTorch(on) }) else null
                    log("WATCH lens=ultra-wide (camera ${WideCamera.ULTRA_WIDE_ID})")
                } else {
                    lensNote = "The ultra-wide lens could not be opened; using the main lens"
                    bindMain()
                }
            }
            else -> bindMain()
        }
        publish()
    }

    fun rebindMainIfActive() {
        main.post { if (running && activeSource == "main") bindMain() }
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun bindMain() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                // The source may have changed while CameraX was getting ready: two video sources must never
                // feed one pipeline.
                if (!running || options.source != VisionSettings.Source.PHONE ||
                    (options.lens == VisionSettings.Lens.WIDE && lensNote == null)
                ) {
                    log("WATCH phone camera bind skipped (source=${options.source} lens=${options.lens})")
                    return@addListener
                }
                val p = future.get()
                provider = p
                p.unbindAll()
                val ratio = AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
                val analysisBuilder = ImageAnalysis.Builder()
                    .setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setAspectRatioStrategy(ratio)
                            .setResolutionStrategy(
                                ResolutionStrategy(Size(1280, 960), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER),
                            )
                            .build(),
                    )
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                // The camera's own exposure: logged so a dark-room test has real numbers.
                Camera2Interop.Extender(analysisBuilder).setSessionCaptureCallback(
                    object : CameraCaptureSession.CaptureCallback() {
                        override fun onCaptureCompleted(
                            session: CameraCaptureSession,
                            request: CaptureRequest,
                            result: TotalCaptureResult,
                        ) {
                            result.get(CaptureResult.SENSOR_SENSITIVITY)?.let { iso = it }
                            result.get(CaptureResult.SENSOR_EXPOSURE_TIME)?.let { exposureMs = it / 1e6 }
                        }
                    },
                )
                val analysis = analysisBuilder.build()
                analysis.setAnalyzer(analysisExecutor) { image -> analyzeFrameThrottled(CameraXFrame(image)) }
                val useCases = listOfNotNull(pendingPreview, analysis).toTypedArray()
                val cam = p.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, *useCases)
                camera = cam
                activeSource = "main"
                alarm.cameraTorch = if (cam.cameraInfo.hasFlashUnit()) ({ on -> cam.cameraControl.enableTorch(on) }) else null
                log("WATCH camera bound: main lens, preview=${pendingPreview != null} has_flash=${cam.cameraInfo.hasFlashUnit()}")
            } catch (t: Throwable) {
                Log.e(TAG, "camera bind failed", t)
                _state.update { it.copy(fault = "Camera failed: ${t.message}") }
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun stopCamera() {
        runCatching { camera?.cameraControl?.enableTorch(false) }
        runCatching { provider?.unbindAll() }
        camera = null
        wide?.let { w -> runCatching { w.stop() } }
        wide = null
        alarm.cameraTorch = null
        activeSource = "none"
        _liveFrame.value = null
    }

    // ------------------------------------------------------------------ CCTV frames from the screen

    fun onCctvBitmap(bitmap: Bitmap) {
        if (!running || options.source != VisionSettings.Source.CCTV) {
            bitmap.recycle()
            return
        }
        cctvLastFrameAt = System.currentTimeMillis()
        // Already on the screen's analysis executor (see CctvSource); run the pipeline here.
        analyzeFrame(BitmapFrame(bitmap) { bitmap.recycle() })
    }

    fun onCctvReport(connected: Boolean, width: Int, height: Int) {
        cctvConnected = connected
        if (width > 0 && height > 0) {
            cctvWidth = width
            cctvHeight = height
        }
        publish()
    }

    // ------------------------------------------------------------------ the frame pipeline (Baby Guard's)

    private fun analyzeFrameThrottled(frame: GuardFrame) {
        val now = System.currentTimeMillis()
        if (now - lastAnalysedAt < ANALYSE_INTERVAL_MS) {
            frame.close()
            return
        }
        lastAnalysedAt = now
        analyzeFrame(frame)
    }

    private fun analyzeFrame(frame: GuardFrame) {
        try {
            if (!running) return
            wide?.let { iso = it.iso; exposureMs = it.exposureMs }
            val tNow = System.currentTimeMillis()
            // Our own torch / flashing screen change both the light sensor and the picture: hold the
            // day/night state during an alert and 3 s after it.
            if (alarm.active) nightFrozenUntil = tNow + 3_000
            val night =
                when (options.nightPreference) {
                    DayNight.Preference.ALWAYS_NIGHT -> true
                    DayNight.Preference.ALWAYS_DAY -> false
                    DayNight.Preference.AUTO -> if (tNow < nightFrozenUntil) nightActive else autoNight()
                }
            if (night != nightActive) {
                analyzer.reset()
                nightActive = night
                log("WATCH night_vision=$night (${options.nightPreference.name.lowercase()}) lux=${sensors.lux} " +
                    "camera_luma=${"%.1f".format(meanLuma)} iso=$iso exposure_ms=${"%.1f".format(exposureMs)}")
                if (!night) _nightFrame.value = null
            }
            val r = analyzer.process(frame, night)
            meanLuma = r.meanLuma

            frameRequest?.let { req ->
                frameRequest = null
                req.complete(runCatching { frame.uprightBitmap(768) }.getOrNull())
            }

            // A change in the picture wakes the vision loop (it otherwise rests between looks).
            if (r.globalChange || r.activeCells >= POKE_CELLS) vision?.poke()

            val now = System.currentTimeMillis()
            if (options.zoneOn) tracker.onMotion(if (r.globalChange) null else r.box, now)
            maybeDetect(frame, r, now)
            evaluateZone()

            // The base lane: face + pose on a 640 px picture, about five times a second.
            val base = baseWatch
            if (options.baseOn && base != null && now - lastBaseAt >= BASE_INTERVAL_MS) {
                lastBaseAt = now
                val bmp = runCatching { frame.uprightBitmap(640) }.getOrNull()
                if (bmp != null) {
                    runCatching { base.offerFrame(bmp) }.onFailure { Log.w(TAG, "base lane failed", it) }
                    bmp.recycle()
                }
            }

            // Pictures for an open screen only.
            if (screenVisible && now - lastScreenFrameAt >= SCREEN_FRAME_INTERVAL_MS) {
                lastScreenFrameAt = now
                if (night && r.bitmap != null) _nightFrame.value = r.bitmap
                else if (_nightFrame.value != null) _nightFrame.value = null
                if (activeSource == "wide") {
                    _liveFrame.value = runCatching { frame.uprightBitmap(640) }.getOrNull()
                }
            }

            frameCount++
            if (fpsWindowStart == 0L) fpsWindowStart = now
            if (now - fpsWindowStart >= 1_000) {
                fps = frameCount * 1000f / (now - fpsWindowStart)
                frameCount = 0
                fpsWindowStart = now
            }
            if (now - lastStatusLog >= 5_000) {
                lastStatusLog = now
                log(
                    "WATCH_STATUS base=${options.baseOn} vlm=${options.vlmOn} zone=${options.zoneOn} source=$activeSource " +
                        "lux=${sensors.lux?.let { "%.0f".format(it) }} luma=${"%.1f".format(r.meanLuma)} night=$night iso=$iso " +
                        "fps=${"%.1f".format(fps)} motion_cells=${r.activeCells} zonebox=${zone.encode()} " +
                        "baby=${ZoneRule.fmt(tracker.box)} src=${tracker.source} out=${ZoneRule.pct(zoneRule.outFraction)} " +
                        "status=${zoneRule.status} detect_ms=$detectMs alert=$alertSource thermal=$thermal",
                )
            }
        } catch (t: Throwable) {
            Log.e(TAG, "frame analysis failed", t)
        } finally {
            frame.close()
        }
    }

    /**
     * Baby Guard's night rule, which switches at once: night vision on below 10 lux or when the camera picture
     * is very dark; off again above 30 lux with a bright picture. A CCTV camera is in another room, so only its
     * picture counts.
     */
    private fun autoNight(): Boolean {
        val l = if (options.source == VisionSettings.Source.CCTV) null else sensors.lux
        val m = meanLuma
        nightAuto =
            if (nightAuto) {
                !((l == null || l > 30f) && m > 70)
            } else {
                (l != null && l < 10f) || (m in 0.0..40.0)
            }
        return nightAuto
    }

    // ------------------------------------------------------------------ base lane

    private fun ensureBase() {
        if (baseWatch != null) return
        rules.reset()
        val watch = CameraWatch(this, ::onBaseFrame)
        baseWatch = watch
        analysisExecutor.execute { runCatching { watch.startHeadless() }.onFailure { Log.e(TAG, "base detectors failed", it) } }
        log("BASE on (face + pose + movement)")
    }

    private fun closeBase() {
        val watch = baseWatch ?: return
        baseWatch = null
        analysisExecutor.execute { runCatching { watch.shutdown() } }
        rules.reset()
        _state.update {
            it.copy(assessment = null, face = FaceWatcher.State.Starting, poseAvailable = false, framesAnalysed = 0)
        }
        log("BASE off")
    }

    /** One base-lane frame through Nila's activity rules. The zone is the guard's job, so it is the whole frame here. */
    private fun onBaseFrame(frame: CameraWatch.Frame) {
        if (!options.baseOn) return
        val assessment = rules.update(
            ActivityRules.Observation(
                pose = frame.pose,
                faceFound = frame.face is FaceWatcher.State.FaceVisible,
                motion = frame.motion,
                baselineReady = frame.motion.relativeToBaseline > 0f,
                zone = FULL_FRAME,
            )
        )
        val watch = baseWatch
        _state.update {
            it.copy(
                face = frame.face,
                assessment = assessment,
                motion = frame.motion,
                framesAnalysed = watch?.framesAnalysed ?: 0L,
                poseAvailable = watch?.poseAvailable == true,
                poseDelegate = watch?.poseDelegate ?: "-",
                cameraMoved = sensors.cameraMoved,
                lightChanged = sensors.lightChanged,
                roomDark = sensors.roomDark,
                lux = sensors.lux,
            )
        }
        val now = System.currentTimeMillis()
        if (_state.value.severity.level >= Severity.URGENT.level) lastBaseUrgentAt = now
        raiseIfNeeded(_state.value)
    }

    /** The base lane's route to a notification (Nila's rules), plus the guard's lights for urgent events. */
    private fun raiseIfNeeded(state: WatchState) {
        if (!options.baseOn) return
        val event = state.assessment?.primary ?: return
        val now = System.currentTimeMillis()
        fun since(e: ActivityRules.Event) = alertedAt[e]?.let { now - it } ?: Long.MAX_VALUE
        val decision = WatchAlert.decide(
            state,
            sinceSameEventMs = since(event),
            sinceCameraMovedMs = since(ActivityRules.Event.CAMERA_MOVED),
        ) ?: return

        alertedAt[decision.event] = System.currentTimeMillis()
        notifier.alert(decision.title, decision.body, decision.severity, id = Notifier.ID_SAFETY)
        runCatching { link()?.sendAlert(decision.title, decision.body, decision.severity, seconds = 0) }
        _state.update { it.copy(lastAlert = decision.body) }
        if (decision.severity.level >= Severity.URGENT.level) {
            main.post { raiseAlert(AlertSource.BASE, decision.title, notify = false) }
        }

        val kind = kindOf(decision.event) ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                db.events().insert(
                    EventRecord(kind = kind.name, severityLevel = decision.severity.level,
                                startedAtMs = System.currentTimeMillis(), note = decision.body)
                )
            }.onFailure { Log.w(TAG, "could not log a safety event", it) }
        }
    }

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
        ActivityRules.Event.SETTLED,
        ActivityRules.Event.ON_BACK,
        ActivityRules.Event.FACE_ONLY,
        ActivityRules.Event.CALIBRATING -> null
    }

    // ------------------------------------------------------------------ safe zone lane

    private fun ensureDetector() {
        if (detector != null) return
        detectExecutor.execute {
            if (detector == null) detector = BabyDetector(this, ::log)
            publish()
        }
    }

    private fun closeDetector() {
        detectExecutor.execute {
            runCatching { detector?.close() }
            detector = null
        }
    }

    /** Runs the person detector on the latest frame about 4x a second, on its own thread. */
    private fun maybeDetect(frame: GuardFrame, r: FrameAnalyzer.Result, now: Long) {
        val det = detector ?: return
        if (!det.ready || !options.zoneOn || detectBusy || now - lastDetectAt < DETECT_INTERVAL_MS) return
        lastDetectAt = now
        // At night the detector sees the brightened picture (it found more babies that way).
        val input: Bitmap =
            if (nightActive && r.bitmap != null) r.bitmap
            else runCatching { frame.uprightBitmap(480) }.getOrNull() ?: return
        detectBusy = true
        detectExecutor.execute {
            try {
                val t0 = System.currentTimeMillis()
                val hits = det.detect(input)
                detectMs = System.currentTimeMillis() - t0
                tracker.onDetections(hits, System.currentTimeMillis())
            } catch (e: Throwable) {
                log("DETECTOR failed: ${e.message}")
            } finally {
                detectBusy = false
            }
        }
    }

    private fun evaluateZone() {
        val decision = zoneRule.update(
            now = System.currentTimeMillis(),
            zone = zone,
            tracker = tracker,
            active = running && options.zoneOn,
            zoneAlertOn = alertSource == AlertSource.ZONE,
            alertIdle = alertSource == null,
            log = ::log,
        )
        when (decision) {
            is ZoneRule.Decision.Raise -> main.post {
                if (alertSource == null && running && options.zoneOn) raiseAlert(AlertSource.ZONE, decision.reason)
            }
            ZoneRule.Decision.BackInside -> main.post { if (alertSource == AlertSource.ZONE) stopAlert(byParent = false) }
            ZoneRule.Decision.Nothing -> Unit
        }
    }

    // ------------------------------------------------------------------ Danger AI lane

    /** Starts / stops the vision loop for the current options. Main thread. */
    private fun applyVision() {
        val o = options
        val want = running && o.vlmOn
        if (!o.modelEnabled) {
            vision?.stop()
            vlmStatus = if (o.vlmOn) VlmStatus.DisabledInSettings else VlmStatus.Off
            publish()
            return
        }
        if (want) {
            if (ensureVlmLoaded()) startLoop()
        } else {
            vision?.stop()
            if (alertSource == AlertSource.VISION) stopAlert(byParent = false)
            if (vlm?.ready != true && vlmLoadJob?.isActive != true && !pendingLook) vlmStatus = VlmStatus.Off
        }
        publish()
    }

    /**
     * True when the model is on the NPU now; otherwise starts loading it (in its own process) and returns
     * false. A load that fails, or a vision process that dies, is tried again a few times: on this phone the
     * first attempt in a process fails and a retry can crash the process, so it can take two or three goes.
     */
    private fun ensureVlmLoaded(): Boolean {
        val engine = vlm ?: return false
        if (engine.ready) {
            vlmStatus = VlmStatus.Ready
            return true
        }
        if (!vlmInstalled) {
            vlmStatus = VlmStatus.NotInstalled
            log("VLM bundle not installed")
            return false
        }
        if (vlmLoadJob?.isActive == true) return false
        vlmStatus = VlmStatus.Loading(1, 8)
        vlmLoadJob = lifecycleScope.launch {
            val t0 = System.currentTimeMillis()
            val ok = runCatching { engine.load() }.getOrDefault(false)
            log("WATCH model ready=$ok load_ms=${System.currentTimeMillis() - t0} status=$vlmStatus retries=$vlmRetries")
            vlmLoadJob = null
            if (!running) return@launch
            if (ok) {
                vlmRetries = 0
                vlmStatus = VlmStatus.Ready
                if (options.vlmOn && options.modelEnabled) startLoop()
                if (pendingLook) {
                    pendingLook = false
                    lookNow()
                }
            } else if ((options.vlmOn || pendingLook) && vlmRetries < MAX_VLM_RETRIES) {
                vlmRetries++
                log("VLM load did not succeed; trying again ($vlmRetries of $MAX_VLM_RETRIES) in 4 s")
                vlmStatus = VlmStatus.Loading(1, 8)
                publish()
                delay(4_000)
                if (running && ensureVlmLoaded()) applyVision()
            } else {
                pendingLook = false
                if (vlmStatus !is VlmStatus.Failed) vlmStatus = VlmStatus.Failed("the model would not load on the NPU")
            }
            publish()
        }
        publish()
        return false
    }

    /** The vision process ended (a native crash while loading, or the system). Main thread. */
    private fun onVlmDied() {
        if (!running) return
        vision?.stop()
        if (alertSource == AlertSource.VISION) stopAlert(byParent = false)
        // A load in flight sees the failure itself and retries; otherwise start a fresh process here.
        if (vlmLoadJob?.isActive != true && (options.vlmOn || pendingLook) && vlmRetries < MAX_VLM_RETRIES) {
            vlmRetries++
            log("VLM process ended; starting a fresh one ($vlmRetries of $MAX_VLM_RETRIES)")
            vlmStatus = VlmStatus.Loading(1, 8)
            main.postDelayed({ if (running && ensureVlmLoaded()) applyVision() }, 3_000)
        }
        publish()
    }

    private fun loop(): VisionLoop {
        vision?.let { return it }
        return VisionLoop(
            scope = lifecycleScope,
            engine = { b, sys, user -> vlm?.look(b, sys, user) },
            frameSource = { grabFrame() },
            preprocess = { b -> if (nightActive) NightEnhancer.enhance(b) else b },
            onLook = { onLook(it) },
            onStuck = { reason -> main.post { onVisionStuck(reason) } },
            log = ::log,
        ).also { v ->
            applyThermal(v, thermal)
            vision = v
        }
    }

    private fun startLoop() {
        if (!running || !options.vlmOn || vlm?.ready != true) return
        loop().start()
        publish()
    }

    /** One look on demand, in any mode. Loads the model first if it is not on the NPU yet. */
    private fun lookNow() {
        if (!options.modelEnabled) {
            vlmStatus = VlmStatus.DisabledInSettings
            publish()
            return
        }
        if (vlm?.ready == true) {
            val v = loop()
            if (v.running) {
                log("LOOK_NOW: the Danger AI is already looking continuously")
                return
            }
            v.lookNow()
        } else {
            pendingLook = true
            ensureVlmLoaded()
        }
        publish()
    }

    /** The vision loop asks for the next camera frame; the frame pipeline fills it in. */
    private suspend fun grabFrame(): Bitmap? {
        val req = CompletableDeferred<Bitmap?>()
        frameRequest = req
        return req.await()
    }

    private suspend fun onLook(l: VisionLoop.Look) {
        val a = l.assessment
        log(
            "LOOK n=${l.n} src=${l.source} verdict=${a.verdict} raw=${a.rawSafety} cat=${a.category} " +
                "frame_ms=${l.frameMs} prep_ms=${l.prepMs} model_ms=${l.modelMs} note=${a.note} | " +
                l.text.replace(Regex("""\s*\n+\s*"""), " | "),
        )
        lastLook = LookResult(a.verdict, a.activity, a.hazard, a.category, a.note, l.modelMs, l.answeredAt,
                              l.source.endsWith("night_enhanced"))
        looks++
        main.post {
            if (a.verdict == Verdict.DANGER) {
                safeStreak = 0
                if (running) raiseAlert(AlertSource.VISION, a.reason().ifBlank { "danger" }, l)
            } else if (alertSource == AlertSource.VISION) {
                safeStreak++
                // The vision alert ends by itself once the danger is gone: 3 clean looks and at least 10 s on.
                if (safeStreak >= 3 && System.currentTimeMillis() - alertStartedAt >= VISION_ALERT_MIN_MS) {
                    log("ALERT_END vision: $safeStreak looks without danger")
                    stopAlert(byParent = false)
                }
            }
            publish()
        }
    }

    private fun onVisionStuck(reason: String) {
        log("WATCH vision model stopped: $reason")
        vision?.stop()
        vision = null
        // As Baby Guard does with the whole app: only a fresh process frees a stuck NPU. Here that is just the
        // vision process, so the camera, the zone and the alerts carry on meanwhile.
        runCatching { vlm?.close() }
        if (running && options.vlmOn && vlmRetries < MAX_VLM_RETRIES) {
            vlmRetries++
            vlmStatus = VlmStatus.Loading(1, 8)
            log("VLM restarting its process ($vlmRetries of $MAX_VLM_RETRIES)")
            main.postDelayed({ if (running && ensureVlmLoaded()) applyVision() }, 3_000)
        } else {
            vlmStatus = VlmStatus.Failed("the model stopped answering ($reason). Stop and start the watch to reload it.")
            notifier.alert("Danger AI stopped", "The vision model stopped answering. Base and safe zone keep running.",
                           Severity.ATTENTION, id = Notifier.ID_SAFETY)
        }
        publish()
    }

    // ------------------------------------------------------------------ the alert

    /** Main thread. */
    private fun raiseAlert(src: AlertSource, reason: String, look: VisionLoop.Look? = null, notify: Boolean = true) {
        val now = System.currentTimeMillis()
        when {
            alertSource != null -> return
            now < cooldownUntil -> {
                log("ALERT_SKIPPED cooldown ${(cooldownUntil - now) / 1000} s ($src)")
                return
            }
        }
        alertSource = src
        alertReason = reason
        alertStartedAt = now
        vision?.urgent = true
        stopDistract()
        val title = when (src) {
            AlertSource.VISION -> "Danger near the baby"
            AlertSource.ZONE -> "Baby leaving the safe zone"
            AlertSource.BASE -> "Check on the baby"
        }
        if (options.alertsOn) {
            alarm.torchEnabled = options.torchEnabled
            alarm.start(reason)
            if (options.source == VisionSettings.Source.CCTV) {
                parent.start(reason) // notification + chime + vibration for the parent
            } else if (options.distractionEnabled) {
                musicWithAlert = true
                music.start() // after the lights, so the lights are never delayed
            }
        }
        val after = look?.let {
            " after_answer_ms=${alertStartedAt - it.answeredAt} frame_to_alert_ms=${alertStartedAt - it.answeredAt + it.modelMs + it.prepMs}"
        } ?: ""
        log("ALERT_ON src=$src reason=$reason$after lights=${options.alertsOn} | ${alarm.componentReport()}")

        if (notify) {
            notifier.alert(title, reason, Severity.URGENT, id = Notifier.ID_SAFETY)
            runCatching { link()?.sendAlert(title, reason, Severity.URGENT, seconds = 0) }
            _state.update { it.copy(lastAlert = reason) }
            val kind = when (src) {
                AlertSource.VISION -> EventKind.HAZARD_SEEN
                AlertSource.ZONE -> if (reason.contains("out of view")) EventKind.OUT_OF_VIEW else EventKind.LEFT_SAFE_ZONE
                AlertSource.BASE -> null
            }
            if (kind != null) {
                lifecycleScope.launch(Dispatchers.IO) {
                    runCatching {
                        db.events().insert(
                            EventRecord(kind = kind.name, severityLevel = Severity.URGENT.level,
                                        startedAtMs = System.currentTimeMillis(), note = "$title: $reason")
                        )
                    }.onFailure { Log.w(TAG, "could not log the alert", it) }
                }
            }
        }
        publish()
    }

    /** Main thread. */
    private fun stopAlert(byParent: Boolean) {
        val src = alertSource ?: return
        alarm.stop()
        musicWithAlert = false
        music.stop()
        parent.stop()
        log("ALERT_OFF src=$src by=${if (byParent) "parent" else "auto"} on_ms=${System.currentTimeMillis() - alertStartedAt}")
        alertSource = null
        alertReason = ""
        vision?.urgent = false
        safeStreak = 0
        if (byParent) cooldownUntil = System.currentTimeMillis() + PARENT_COOLDOWN_MS
        publish()
    }

    /** Alerts switched off while one is on: the lights and sound stop, the alert itself stays on the screen. */
    private fun quietAlert() {
        alarm.stop()
        musicWithAlert = false
        music.stop()
        parent.stop()
    }

    /** The "Distract" button: the lullaby (media volume) and the ring light, for 20 s. Main thread. */
    private fun distract() {
        if (alertSource != null) return
        if (options.source == VisionSettings.Source.CCTV) {
            log("DISTRACT skipped: in CCTV mode this phone is with the parent, not the baby")
            _state.update { it.copy(distraction = "Not in CCTV mode: this phone is with you, not the baby") }
            return
        }
        distractUntil = System.currentTimeMillis() + DISTRACT_MS
        alarm.torchEnabled = options.torchEnabled
        alarm.distract(DISTRACT_MS)
        if (options.distractionEnabled) music.start()
        log("DISTRACT on for ${DISTRACT_MS / 1000} s music=${options.distractionEnabled} flash=${options.torchEnabled}")
        _state.update { it.copy(distraction = "Playing the lullaby and lighting the ring") }
        publish()
    }

    private fun stopDistract() {
        if (distractUntil == 0L) return
        distractUntil = 0L
        alarm.stopDistract()
        if (!musicWithAlert) music.stop()
        log("DISTRACT off")
        _state.update { it.copy(distraction = null) }
        publish()
    }

    // ------------------------------------------------------------------ housekeeping, 4x a second

    private fun tick() {
        if (!running) return
        val now = System.currentTimeMillis()
        evaluateZone()
        checkFeed(now)
        // The lights stop by themselves after 60 s: the alert's music stops with them.
        if (musicWithAlert && music.playing && !alarm.active) {
            musicWithAlert = false
            music.stop()
        }
        if (parent.active && !alarm.active) parent.stop()
        if (distractUntil != 0L && now > distractUntil) stopDistract()
        // A base alert ends once nothing urgent has been seen for a few seconds.
        if (alertSource == AlertSource.BASE && now - lastBaseUrgentAt > BASE_CLEAR_MS &&
            now - alertStartedAt > BASE_CLEAR_MS
        ) {
            log("ALERT_END base: nothing urgent for ${BASE_CLEAR_MS / 1000} s")
            stopAlert(byParent = false)
        }
        publish()
    }

    /** CCTV: no new picture for 6 s = the camera is not reachable (or the Watch screen was closed). */
    private fun checkFeed(now: Long) {
        if (options.source != VisionSettings.Source.CCTV) {
            if (feedLost) {
                feedLost = false
                parent.feedLost(false)
            }
            return
        }
        val since = if (cctvLastFrameAt == 0L) Long.MAX_VALUE else now - cctvLastFrameAt
        // Give the stream time to connect after the source starts before calling it lost (Baby Guard called
        // the feed lost 0.25 s after launch, before RTSP had had a chance).
        val lost = since > CCTV_LOST_MS && now - sourceStartedAt > CCTV_GRACE_MS
        if (lost != feedLost) {
            feedLost = lost
            log(if (lost) "CCTV_FEED_LOST no picture for ${CCTV_LOST_MS / 1000} s screen=$screenVisible" else "CCTV_FEED_BACK")
            parent.feedLost(
                lost,
                if (screenVisible) "Nila cannot see the camera. Check the camera and the Wi-Fi."
                else "CCTV watching is paused: open Nila's Watch screen to see the camera.",
            )
        }
    }

    // ------------------------------------------------------------------ heat

    private fun registerThermal() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val pm = getSystemService(PowerManager::class.java) ?: return
        val listener = PowerManager.OnThermalStatusChangedListener { status ->
            thermal = status
            vision?.let { applyThermal(it, status) }
            log("WATCH thermal_status=$status idle_gap_ms=${vision?.idleGapMs} pause_ms=${vision?.pauseMs}")
            publish()
        }
        runCatching { pm.addThermalStatusListener(listener) }
        thermalListener = listener
        thermal = runCatching { pm.currentThermalStatus }.getOrDefault(0)
    }

    private fun unregisterThermal() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val l = thermalListener as? PowerManager.OnThermalStatusChangedListener ?: return
        runCatching { getSystemService(PowerManager::class.java)?.removeThermalStatusListener(l) }
        thermalListener = null
    }

    /**
     * Cool phone: look back to back. Warm: rest a little between looks of a still scene (movement always
     * triggers a look at once). Only a critical phone gets an extra pause. Baby Guard's numbers.
     */
    private fun applyThermal(v: VisionLoop, status: Int) {
        v.idleGapMs = when {
            status >= PowerManager.THERMAL_STATUS_SEVERE -> 3_000
            status >= PowerManager.THERMAL_STATUS_MODERATE -> 1_500
            else -> 0
        }
        v.pauseMs = if (status >= PowerManager.THERMAL_STATUS_CRITICAL) 2_000 else 0
    }

    // ------------------------------------------------------------------ state for the screen

    private fun publishZone() {
        _state.update { it.copy(zone = zone) }
        publish()
    }

    private fun publish() {
        val o = options
        val box = if (o.zoneOn) tracker.box?.takeIf { tracker.visible(System.currentTimeMillis()) } else null
        val g = GuardStatus(
            baseOn = o.baseOn,
            vlmOn = o.vlmOn,
            zoneOn = o.zoneOn,
            alertsOn = o.alertsOn,
            musicOn = o.distractionEnabled,
            flashOn = o.torchEnabled,
            source = o.source,
            lens = o.lens,
            cctvUrl = o.cctvUrl,
            night = nightActive,
            lux = sensors.lux,
            luma = meanLuma.toFloat(),
            fps = fps,
            thermal = thermal,
            vlm = if (!o.modelEnabled && (o.vlmOn || pendingLook)) VlmStatus.DisabledInSettings else vlmStatus,
            looking = vision?.busy == true,
            lastLook = lastLook,
            looks = looks,
            zoneStatus = zoneRule.status,
            outFraction = zoneRule.outFraction,
            babyBox = box?.let { NormBox(it.left, it.top, it.right, it.bottom) },
            detector = detector?.backend ?: "-",
            alert = alertSource,
            alertReason = alertReason,
            alertStartedAt = alertStartedAt,
            distracting = distractUntil != 0L,
            cctvConnected = cctvConnected,
            cctvFeedLost = feedLost,
            cctvWidth = cctvWidth,
            cctvHeight = cctvHeight,
            lensNote = lensNote,
        )
        _state.update {
            it.copy(
                running = running,
                guard = g,
                zone = zone,
                lightMode = if (nightActive) LightMode.NIGHT else LightMode.DAY,
                frameLuma = meanLuma.toFloat().coerceAtLeast(0f),
                lux = sensors.lux,
                looking = vision?.busy == true,
                visionModelInstalled = vlmInstalled,
                simulated = false,
            )
        }
    }
}

/** The base lane's rules see no zone: the safe zone is the guard's job, so nothing is ever "outside". */
private val FULL_FRAME = SafeZone(0f, 0f, 1f, 1f, configured = true)
