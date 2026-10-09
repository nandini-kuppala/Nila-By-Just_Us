package com.nila.ui.screens

import androidx.camera.core.Preview
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.AccessibilityNew
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CropFree
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Face
import androidx.compose.material.icons.outlined.Gesture
import androidx.compose.material.icons.outlined.HourglassTop
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.SentimentSatisfied
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.nila.data.Severity
import com.nila.monitor.WatchService
import com.nila.ui.AppState
import com.nila.ui.components.CardShape
import com.nila.ui.components.CotBabyArt
import com.nila.ui.components.IconBadge
import com.nila.ui.components.InfoNote
import com.nila.ui.components.ListRow
import com.nila.ui.components.NilaCard
import com.nila.ui.components.OutlinePillButton
import com.nila.ui.components.PhonePlacementArt
import com.nila.ui.components.PillButton
import com.nila.ui.components.ScreenHero
import com.nila.ui.components.SeverityChip
import com.nila.ui.components.TonePill
import com.nila.ui.components.WarningBox
import com.nila.ui.theme.SeverityUrgent
import com.nila.ui.theme.Tone
import com.nila.ui.theme.ZoneLine
import com.nila.ui.theme.calmColors
import com.nila.ui.theme.severityColors
import com.nila.ui.theme.toneColors
import com.nila.vision.ActivityRules
import com.nila.vision.HazardRules
import com.nila.vision.LightMode as SceneLight
import com.nila.vision.CameraWatch
import com.nila.vision.DemoFootage
import com.nila.vision.FaceWatcher
import com.nila.vision.PoseStatus
import com.nila.vision.Posture
import com.nila.vision.SafeZone
import com.nila.vision.WatchState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The camera lane.
 *
 * ### What changed, and why
 *
 * This screen used to *be* the safety watch: it held the camera, the detectors
 * and the rules in `remember {}`, so all three were destroyed when the
 * composable left the tree. The watch therefore stopped when the screen turned
 * off, when the user changed tabs, and when Android dimmed the display -- and
 * it stopped without saying so, which for a monitor is the worst available
 * failure. The camera now lives in [WatchService] and this screen only observes
 * it and lends it a preview surface.
 *
 * ### What it is allowed to claim
 *
 * More than it used to, and still nothing about breathing. Face presence and
 * motion energy answered one question -- can I see the face -- and reported a
 * blanket over the lens, a roll to prone and a knocked phone as the same
 * urgent event. Pose adds the coarse posture and the movement across the frame
 * needed to tell those apart, and the accelerometer settles the third. Every
 * claim on this screen carries what it is read from, and the two things a
 * single camera genuinely cannot establish -- a distance in centimetres, and
 * the difference between leaving the room and being under a blanket -- are
 * written on the screen as limits rather than left for the reader to discover.
 */
@Composable
fun WatchScreen(state: AppState) {
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()

    val live by WatchService.state.collectAsState()
    val monitor by state.monitor.collectAsState()
    // The lifted picture of a dark room. Null in daylight and null when the
    // service is not running, so the preview below is unaffected either way.
    val nightFrame by WatchService.nightFrame.collectAsState()

    var previewView by remember { mutableStateOf<PreviewView?>(null) }
    var editingZone by remember { mutableStateOf<SafeZone?>(null) }

    // ---- the demo path, which stays in the UI on purpose
    //
    // The service owns the real camera and must keep owning it; the demo needs
    // the detectors with the camera *off*, or an emulator shows a rendered
    // living room underneath a video of a baby. So the demo runs its own
    // headless watcher and its own rules, and produces the same [WatchState]
    // the service does -- which is what keeps the card below identical either
    // way.
    var demo by remember { mutableStateOf(false) }
    var demoState by remember { mutableStateOf(WatchState()) }
    var shown by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    val demoRules = remember { ActivityRules() }

    // The audio lane, folded in for display. The service does the same fold
    // for the alert path -- this one only ever reaches the screen, so the two
    // are deliberately independent and neither can break the other.
    val shownState =
        if (demo) demoState
        else live.copy(cryingNow = monitor.currentEvidence != null)

    /**
     * Keep the display awake while this screen is open.
     *
     * The watch itself no longer depends on the screen -- that is the point of
     * the service -- but a parent who has deliberately opened the live view to
     * watch their baby should not have it black out in twenty seconds. Scoped
     * to this composable, so leaving the screen gives the display back to
     * Android's own timeout.
     */
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    // Lend the service a surface while the screen is showing, and take it back
    // on the way out so the watch is not rendering into a dead view all night.
    LaunchedEffect(previewView, live.running) {
        val target = previewView
        if (live.running && target != null) {
            val preview = Preview.Builder().build()
            preview.surfaceProvider = target.surfaceProvider
            WatchService.attachPreview(context, preview)
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            WatchService.attachPreview(context, null)
            state.releaseSceneDescriber()
        }
    }

    val footage = remember { DemoFootage(context) }
    LaunchedEffect(demo) {
        if (!demo) { shown = null; return@LaunchedEffect }
        // Opening the clip is a MediaMetadataRetriever setup plus a decoded
        // frame to measure the letterbox bars, which is far too much to do on
        // the thread that has to draw the result.
        if (!withContext(Dispatchers.IO) { footage.open() }) {
            demo = false
            return@LaunchedEffect
        }
        demoRules.reset()
        val camera = CameraWatch(context) { frame ->
            val assessment = demoRules.update(
                ActivityRules.Observation(
                    pose = frame.pose,
                    faceFound = frame.face is FaceWatcher.State.FaceVisible,
                    motion = frame.motion,
                    baselineReady = frame.motion.relativeToBaseline > 0f,
                    zone = live.zone,
                )
            )
            demoState = demoState.copy(
                running = true, simulated = true,
                face = frame.face, motion = frame.motion,
                assessment = assessment, zone = live.zone,
            )
        }
        demoState = WatchState(running = true, simulated = true, zone = live.zone)
        try {
            // Everything below is video decoding and two neural networks, and
            // none of it may run on the main thread. It used to: opening the
            // clip, building the detectors and then decoding and analysing
            // every frame all happened in this coroutine on the UI dispatcher,
            // so Compose could not draw and the screen stayed blank for a
            // minute or more before the first frame appeared.
            withContext(Dispatchers.Default) {
                // The first frame, before the models. It is the thing somebody
                // is waiting to see and it needs nothing loaded to show -- so
                // the clip starts playing immediately and the detectors catch
                // up behind it, which is the same order the live camera path
                // now uses.
                footage.frameAt(0L)?.let { shown = it }
                camera.startHeadless()

                var position = 0L
                while (demo) {
                    val frame = footage.frameAt(position)
                    if (frame != null) {
                        // Shown before it is analysed, never after. The two
                        // detectors take longer than the frame interval on a
                        // cold start, and making the picture wait on them is
                        // what turns a video into a slideshow.
                        shown = frame
                        camera.offerFrame(frame)
                        demoState = demoState.copy(
                            framesAnalysed = camera.framesAnalysed,
                            // Read per frame, not once at the start: the
                            // detectors are built behind the first frames and
                            // are not loaded yet when the demo begins.
                            poseAvailable = camera.poseAvailable,
                            poseDelegate = camera.poseDelegate,
                        )
                    }
                    position += DemoFootage.FRAME_INTERVAL_MS
                    if (position >= footage.durationMs) position = 0L
                    kotlinx.coroutines.delay(DemoFootage.FRAME_INTERVAL_MS)
                }
            }
        } finally {
            withContext(NonCancellable + Dispatchers.Default) {
                runCatching { camera.shutdown() }
            }
            demoState = WatchState()
        }
    }

    DisposableEffect(Unit) {
        onDispose { demo = false; footage.close() }
    }

    // A still from the demo clip, for the safe-zone card while nothing is
    // running. A real frame of a real cot -- read once, off the main thread --
    // shows what drawing a zone means far better than an empty grey box.
    var poster by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(Unit) {
        poster = withContext(Dispatchers.IO) {
            runCatching {
                DemoFootage(context).use { clip -> if (clip.open()) clip.frameAt(0L) else null }
            }.getOrNull()
        }
    }

    val active = live.running || demo

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        if (!active) {
            ScreenHero(
                title = "Safety watch",
                body = "Point the camera at the cot. Frames are analysed on this " +
                    "phone and never leave it, and the watch keeps running with " +
                    "the screen off.",
                art = { CotBabyArt() },
            )
            IdleCard(
                onStart = { WatchService.start(context) },
                onDemo = { demo = true },
            )
            live.fault?.let { WarningBox("Camera unavailable", it, Modifier.fillMaxWidth()) }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(3f / 4f)
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color.Black),
            ) {
                if (live.running) {
                    AndroidView(
                        factory = { ctx -> PreviewView(ctx).also { previewView = it } },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                // The clip, in the same place the camera preview would be.
                // Showing the frames the detectors are reading is the whole
                // point -- numbers moving beside a blank space prove nothing.
                shown?.takeIf { demo }?.let { frame ->
                    Image(
                        bitmap = frame.asImageBitmap(),
                        contentDescription = "Recorded clip being analysed",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                }
                // And the night view over the top of the live preview, for the
                // same reason: in an unlit nursery the raw preview is black, and
                // a black rectangle tells a parent nothing about whether the
                // watch is working.
                nightFrame?.takeIf { !demo && live.running }?.let { frame ->
                    Image(
                        bitmap = frame.asImageBitmap(),
                        contentDescription = "Night view of the cot",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                }
                ZoneOverlay(
                    zone = editingZone ?: shownState.zone,
                    pose = shownState.assessment,
                    editing = editingZone != null,
                    onChange = { editingZone = it },
                    modifier = Modifier.fillMaxSize(),
                    handles = true,
                )
                // Stop, on the view itself: the controls below scroll off the
                // first screen, and the way out of a camera must never need a
                // scroll to find.
                OverlayPill(
                    text = "Stop",
                    icon = Icons.Filled.Stop,
                    iconDescription = if (demo) "Stop the demo" else "Stop watching",
                    onClick = { if (demo) demo = false else WatchService.stop(context) },
                    modifier = Modifier.align(Alignment.TopEnd).padding(14.dp),
                )
                OverlayPill(
                    text = when {
                        demo -> "Demo footage"
                        live.lightMode == SceneLight.NIGHT -> "Live · night view"
                        else -> "Live"
                    },
                    dot = if (demo) calmColors().accent
                          else severityColors(Severity.URGENT).accent,
                    modifier = Modifier.align(Alignment.TopStart).padding(14.dp),
                )
                if (editingZone == null) {
                    OverlayPill(
                        text = zoneLabel(shownState.zone),
                        icon = Icons.Outlined.Edit,
                        onClick = { editingZone = shownState.zone },
                        modifier = Modifier.align(Alignment.BottomStart).padding(14.dp),
                    )
                }
            }

            if (editingZone != null) {
                ZoneEditor(
                    onSave = {
                        editingZone?.let { z -> scope.launch { state.saveSafeZone(z) } }
                        editingZone = null
                    },
                    onCancel = { editingZone = null },
                    onReset = { scope.launch { state.resetSafeZone() }; editingZone = null },
                    configured = shownState.zone.configured,
                )
            }

            LiveStatusCard(shownState)
            StatsCard(shownState)
            // The model looks at the real camera, not the demo clip.
            if (!demo) {
                SceneCard(
                    state = shownState,
                    onLook = { WatchService.lookNow(context) },
                    onDistract = { WatchService.distractNow(context) },
                )
            }
            ChipsRow(shownState)

            // Only once a frame has been through the models. Before that the
            // pose landmarker is still loading, and saying it failed is both
            // wrong and the first thing a parent reads.
            if (shownState.poseStatus == PoseStatus.UNAVAILABLE && shownState.running && !demo) {
                WarningBox(
                    "Posture is unavailable on this phone",
                    "The pose model could not run, so the watch is reporting face " +
                        "presence and movement only – what it did before.",
                )
            }

            if (demo) {
                OutlinePillButton("Stop the demo", { demo = false }, Modifier.fillMaxWidth(),
                                  icon = Icons.Filled.Stop)
            } else {
                OutlinePillButton("Stop watching", { WatchService.stop(context) },
                                  Modifier.fillMaxWidth(), icon = Icons.Filled.Stop,
                                  danger = true)
            }
        }

        SafeZoneSection(
            zone = shownState.zone,
            poster = poster,
            active = active,
            onEdit = { editingZone = shownState.zone },
        )

        PlacementCard()
        TipsRow()
        LimitsCard()
    }
}

// ----------------------------------------------------------------- idle

@Composable
private fun IdleCard(onStart: () -> Unit, onDemo: () -> Unit) {
    NilaCard(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(18.dp),
             verticalSpacing = 16.dp) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            IconBadge(Icons.Outlined.VisibilityOff, Tone.SLATE, size = 56.dp, iconSize = 28.dp)
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("Not watching", style = MaterialTheme.typography.titleLarge,
                     fontWeight = FontWeight.Bold)
                Text("Start the watch to analyse your baby's position and movement.",
                     style = MaterialTheme.typography.bodyMedium,
                     color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        PillButton("Start safety watch", onStart, Modifier.fillMaxWidth(),
                   icon = Icons.Filled.PlayArrow)
        Text("Not a breathing monitor. Best with a night light.",
             style = MaterialTheme.typography.bodySmall,
             color = MaterialTheme.colorScheme.onSurfaceVariant,
             textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        NilaCard(
            modifier = Modifier.fillMaxWidth(),
            tone = Tone.TEAL,
            onClick = onDemo,
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
        ) {
            ListRow(
                title = "Play demo footage",
                subtitle = "A real clip through the real detectors",
                icon = Icons.Outlined.Videocam,
                tone = Tone.TEAL,
                badgeSize = 40.dp,
                chevron = true,
            )
        }
    }
}

// ----------------------------------------------------------- the scene model

/**
 * What the scene model saw, and when it will look again.
 *
 * Separate from [LiveStatusCard] on purpose. That card is the always-on lane --
 * face, posture, movement, five frames a second -- and this one is a model that
 * is asked occasionally and can be wrong in a way the cheap lane cannot: it
 * produces sentences. Keeping them apart means a parent can see which of the two
 * said what, and the hedge at the bottom belongs to this one alone.
 */
@Composable
private fun SceneCard(
    state: WatchState,
    onLook: () -> Unit,
    onDistract: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val hazard = state.hazard
    NilaCard(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(16.dp),
             verticalSpacing = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconBadge(Icons.Outlined.Visibility, Tone.PLUM, size = 40.dp)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text("What the camera sees", style = MaterialTheme.typography.titleMedium,
                     fontWeight = FontWeight.Bold)
                Text(
                    when {
                        !state.visionModelInstalled -> "Scene model not installed"
                        state.looking -> "Looking now…"
                        state.asleep -> "Resting while your baby is settled"
                        else -> "Looks again when something changes"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
            }
        }

        // A confirmed hazard is the one thing in this card allowed to shout, and
        // it has been seen twice before it gets here.
        if (hazard != null && hazard.isDanger) {
            HazardBanner(hazard.headline, hazard.hazard)
        } else if (hazard != null && hazard.level == HazardRules.Level.UNSURE) {
            InfoNote(
                title = hazard.headline,
                text = "It mentioned “${hazard.hazard}”, which is not on the list " +
                    "of things worth waking you for.",
                icon = Icons.Outlined.Lightbulb,
                tone = Tone.PEACH,
            )
        }

        val seen = state.observation
        when {
            !state.visionModelInstalled -> Text(
                "Posture, movement and the safe zone above work without it. With the " +
                    "model on this phone, it would also describe the cot in a sentence.",
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant,
            )
            seen != null -> Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(seen.summary.replaceFirstChar { it.uppercase() },
                     style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                // Only when it adds something: with nothing in view the model
                // answers "not visible" for both lines.
                if (seen.doing.isNotBlank() &&
                    !seen.doing.trimEnd('.').equals(seen.summary, ignoreCase = true)) {
                    Text(seen.doing, style = MaterialTheme.typography.bodyMedium,
                         color = scheme.onSurfaceVariant)
                }
                if (state.lastLookAt > 0) {
                    Text(lookedAgo(state.lastLookAt), style = MaterialTheme.typography.labelMedium,
                         color = scheme.onSurfaceVariant)
                }
            }
            state.lookFailed -> InfoNote(
                "The model could not describe the last picture. Posture, movement and " +
                    "the safe zone are unaffected.",
                icon = Icons.Outlined.WarningAmber,
                tone = Tone.PEACH,
            )
            else -> Text(
                "Nothing yet. It looks when something changes – a cry, a roll, or your " +
                    "baby waking – and stays idle otherwise.",
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant,
            )
        }

        state.distraction?.let {
            // The player's own summary, e.g. "played Amma-humming, lit the ring".
            TonePill(it.replace('-', ' ').replaceFirstChar { c -> c.uppercase() }, Tone.TEAL,
                     icon = Icons.Outlined.MusicNote)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            // Disabled rather than silently doing nothing: with no model on the
            // phone a look has nothing to ask, and a button that answers with
            // nothing reads as a broken one.
            PillButton("Look now", onLook, Modifier.weight(1f), icon = Icons.Outlined.Visibility,
                       enabled = !state.looking && state.visionModelInstalled)
            OutlinePillButton("Distract", onDistract, Modifier.weight(1f),
                              icon = Icons.Outlined.MusicNote)
        }

        if (state.visionModelInstalled) {
            Text(
                "A description from one picture, and sometimes wrong. Only a hazard it " +
                    "names twice, and that is on Nila's list, can wake you.",
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
            )
        }
    }
}

/** A confirmed hazard: the rules' headline, then the model's own words. */
@Composable
private fun HazardBanner(headline: String, words: String) {
    val colors = severityColors(Severity.URGENT)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(colors.container)
            .padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Outlined.WarningAmber, contentDescription = null, tint = colors.accent,
             modifier = Modifier.size(24.dp))
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(headline, style = MaterialTheme.typography.titleSmall,
                 fontWeight = FontWeight.Bold, color = colors.accent)
            if (words.isNotBlank()) {
                Text("It saw: $words", style = MaterialTheme.typography.bodyMedium,
                     color = colors.onContainer)
            }
            Text("Go and look. Seen twice before Nila said anything.",
                 style = MaterialTheme.typography.bodySmall, color = colors.onContainerMuted)
        }
    }
}

private fun lookedAgo(at: Long): String {
    val minutes = ((System.currentTimeMillis() - at) / 60_000L).toInt()
    return when {
        minutes < 1 -> "Looked just now"
        minutes == 1 -> "Looked 1 minute ago"
        minutes < 60 -> "Looked $minutes minutes ago"
        else -> "Looked over an hour ago"
    }
}

// --------------------------------------------------------------- status

/**
 * What the watch believes, sized to how much it matters.
 *
 * Calm is a green card with a smile; attention is amber; urgent is red with
 * every fact listed, not just the worst one -- a baby who has rolled onto their
 * front *and* crossed the cot line is two facts, and the parent walking in
 * should know both.
 */
@Composable
private fun LiveStatusCard(state: WatchState) {
    val scheme = MaterialTheme.colorScheme
    val severe = state.severity.level >= Severity.ATTENTION.level
    val colors = if (severe) severityColors(state.severity) else calmColors()
    // A dark room is not "all clear" -- it is "cannot tell". The rules already
    // refuse to raise an alarm from a view they cannot trust; the card must not
    // raise a reassurance from one either.
    val paused = state.roomDark && !severe
    val calibrating = (state.calibrating || paused) && !severe
    val outside = state.assessment?.zoneOvershoot?.let { it > 0f } == true

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(if (calibrating) scheme.surfaceContainerLowest else colors.container)
            .border(1.dp, if (calibrating) scheme.outlineVariant
                          else colors.accent.copy(alpha = 0.25f), CardShape)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(if (calibrating) scheme.surfaceContainer
                                else colors.accent.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    when {
                        paused -> Icons.Outlined.DarkMode
                        calibrating -> Icons.Outlined.HourglassTop
                        severe -> Icons.Outlined.WarningAmber
                        else -> Icons.Outlined.SentimentSatisfied
                    },
                    contentDescription = null,
                    tint = if (calibrating) scheme.onSurfaceVariant else colors.accent,
                    modifier = Modifier.size(28.dp),
                )
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        when {
                            paused -> "Paused – too dark"
                            calibrating -> "Getting a baseline"
                            severe -> state.headline
                            else -> "Nothing unusual"
                        },
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = if (calibrating) scheme.onSurface else colors.accent,
                        modifier = Modifier.weight(1f),
                    )
                    if (severe) SeverityChip(state.severity)
                    else TonePill(if (state.simulated) "Demo" else "Live", Tone.TEAL, dot = true)
                }
                Text(
                    when {
                        paused -> "Nila will not guess until there is more light."
                        calibrating -> "Learning what the scene looks like."
                        severe -> if (outside) "Outside the safe zone" else "Needs a look"
                        else -> state.headline + if (!outside) " · inside the safe zone" else ""
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (calibrating) scheme.onSurfaceVariant else colors.onContainerMuted,
                )
            }
        }

        // Everything believed, not just the worst of it. In the calm state the
        // headline is already the first of these, so only the rest are listed.
        val extra = if (severe) state.descriptions
                    else state.descriptions.filter { it != state.headline }
        if (!calibrating && extra.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                extra.forEach { line ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowForward, contentDescription = null,
                             tint = colors.accent, modifier = Modifier.padding(top = 2.dp).size(18.dp))
                        Text(line, style = MaterialTheme.typography.bodyLarge,
                             color = colors.onContainer)
                    }
                }
            }
        }

        state.caveat?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium,
                 color = if (calibrating) scheme.onSurfaceVariant else colors.onContainerMuted)
        }
    }
}

/** Four readings in a row, each with the thing it is read from. */
@Composable
private fun StatsCard(state: WatchState) {
    val scheme = MaterialTheme.colorScheme
    val movement = state.motion.activeFraction.coerceIn(0f, 1f)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(scheme.surfaceContainerLowest)
            .border(1.dp, scheme.outlineVariant, CardShape)
            .height(IntrinsicSize.Min)
            .padding(vertical = 14.dp),
    ) {
        val cells = listOf(
            Triple(Icons.Outlined.AccessibilityNew, "Posture", posture(state.assessment?.posture)),
            Triple(Icons.Outlined.Place, "Distance", distance(state.assessment?.distanceRatio)),
            Triple(Icons.Outlined.Gesture, "Movement", when {
                movement < 0.05f -> "Still"
                movement < 0.3f -> "A little"
                else -> "A lot"
            }),
            Triple(Icons.Outlined.Image, "Frames", state.framesAnalysed.toString()),
        )
        cells.forEachIndexed { i, (icon, label, value) ->
            if (i > 0) Box(Modifier.width(1.dp).fillMaxHeight().background(scheme.outlineVariant))
            Column(
                modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(icon, contentDescription = null, tint = scheme.onSurfaceVariant,
                     modifier = Modifier.size(22.dp))
                Text(label, style = MaterialTheme.typography.labelMedium,
                     color = scheme.onSurfaceVariant)
                Text(value, style = MaterialTheme.typography.titleSmall,
                     fontWeight = FontWeight.Bold, maxLines = 1,
                     overflow = TextOverflow.Ellipsis)
                if (i == 2) {
                    Box(
                        Modifier
                            .padding(top = 2.dp)
                            .width(44.dp)
                            .height(5.dp)
                            .clip(CircleShape)
                            .background(scheme.surfaceContainerHighest)
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth(movement)
                                .fillMaxHeight()
                                .clip(CircleShape)
                                .background(calmColors().accent)
                        )
                    }
                }
            }
        }
    }
}

/** Where the analysis is running and how trustworthy the view is. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ChipsRow(state: WatchState) {
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TonePill("Analysed on this phone", Tone.TEAL, icon = Icons.Outlined.Shield)
        when (state.poseStatus) {
            PoseStatus.RUNNING -> Unit
            PoseStatus.LOADING -> TonePill("Warming up", Tone.SLATE,
                                           icon = Icons.Outlined.HourglassTop)
            PoseStatus.UNAVAILABLE -> TonePill("Face only", Tone.PEACH,
                                               icon = Icons.Outlined.Face)
        }
        if (state.lightMode == SceneLight.NIGHT && !state.simulated) {
            TonePill("Night vision", Tone.PLUM, icon = Icons.Outlined.DarkMode)
        }
        when {
            state.roomDark -> TonePill("Low light", Tone.PEACH, icon = Icons.Outlined.LightMode)
            state.lux != null -> TonePill("${state.lux.toInt()} lx", Tone.SLATE,
                                          icon = Icons.Outlined.LightMode)
        }
    }
}

/** A label floating over the camera view. */
@Composable
private fun OverlayPill(
    text: String,
    modifier: Modifier = Modifier,
    dot: Color? = null,
    icon: ImageVector? = null,
    iconDescription: String = "Adjust the safe zone",
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.55f))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        dot?.let { Box(Modifier.size(9.dp).clip(CircleShape).background(it)) }
        Text(text, style = MaterialTheme.typography.labelLarge, color = Color.White)
        icon?.let {
            Icon(it, contentDescription = iconDescription, tint = Color.White,
                 modifier = Modifier.size(16.dp))
        }
    }
}

// ------------------------------------------------------------- safe zone

@Composable
private fun ZoneEditor(
    onSave: () -> Unit,
    onCancel: () -> Unit,
    onReset: () -> Unit,
    configured: Boolean,
) {
    NilaCard(modifier = Modifier.fillMaxWidth(), tone = Tone.TEAL) {
        Text("Drag the corners over the cot rails, then save.",
             style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PillButton("Save", onSave, Modifier.weight(1f), icon = Icons.Outlined.Check)
            OutlinePillButton("Cancel", onCancel, Modifier.weight(1f))
        }
        if (configured) {
            TextButton(onClick = onReset) { Text("Reset to the default zone") }
        }
    }
}

/**
 * The safe zone, explained on a real frame.
 *
 * While the watch is off this shows a still from the demo clip with the zone
 * drawn on it, because "the inner 70% of the frame" means nothing until you see
 * it; while it is on, the zone is on the live view above and this is only the
 * sentence that says what the current one is.
 */
@Composable
private fun SafeZoneSection(
    zone: SafeZone,
    poster: android.graphics.Bitmap?,
    active: Boolean,
    onEdit: () -> Unit,
) {
    var help by remember { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Safe zone", style = MaterialTheme.typography.titleLarge,
                 fontWeight = FontWeight.Bold)
            IconButton(onClick = { help = !help }, modifier = Modifier.size(40.dp)) {
                Icon(Icons.AutoMirrored.Outlined.HelpOutline,
                     contentDescription = "What the safe zone is",
                     tint = scheme.onSurfaceVariant)
            }
        }
        Text(
            "The area of the camera view the baby should stay inside. Leaving it " +
                "is an urgent alert.",
            style = MaterialTheme.typography.bodyMedium,
            color = scheme.onSurfaceVariant,
        )
        AnimatedVisibility(visible = help) {
            InfoNote(
                "Movement and posture are read across the whole frame. The zone only " +
                    "decides one thing: whether the baby has left the cot. Draw it over " +
                    "the rails, not the mattress, so a roll to the edge is not an alert.",
                icon = Icons.Outlined.Lightbulb,
                tone = Tone.TEAL,
            )
        }
        if (!active && poster != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(4f / 3f)
                    .clip(RoundedCornerShape(20.dp)),
            ) {
                Image(
                    bitmap = poster.asImageBitmap(),
                    contentDescription = "An example camera view with the safe zone drawn on it",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
                ZoneOverlay(zone = zone, pose = null, editing = false, onChange = {},
                            modifier = Modifier.fillMaxSize(), handles = true)
                OverlayPill(zoneLabel(zone), modifier = Modifier.align(Alignment.BottomCenter)
                    .padding(bottom = 14.dp))
            }
        }
        if (!active) {
            InfoNote("Start the watch, then tap the zone label on the camera view to drag " +
                         "it over the cot rails.", icon = Icons.Outlined.Edit)
        } else {
            InfoNote(
                if (zone.configured) "Set over the cot. The baby leaving it is an urgent alert."
                else "Using the default zone: the middle of the frame, not the cot. Tap the " +
                    "zone label on the view to drag it over the cot rails.",
            )
        }
    }
}

private fun zoneLabel(zone: SafeZone): String =
    if (zone.configured) "Safe zone"
    else "Default zone"

// ------------------------------------------------------------- guidance

@Composable
private fun PlacementCard() {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Where to put the phone", style = MaterialTheme.typography.titleLarge,
             fontWeight = FontWeight.Bold)
        NilaCard(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(14.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(0.9f).aspectRatio(0.82f)) { PhonePlacementArt() }
                Column(modifier = Modifier.weight(1.1f),
                       verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Step(1, "At the side of the cot, not at the head or the foot.")
                    Step(2, "So the baby lies across the picture, not up and down it.")
                    Step(3, "Steady, and a little above the rail.")
                }
            }
            Text(
                "A camera looking along the length of the cot sees a lying baby the " +
                    "same way it sees a standing one, and no single view can separate those.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Step(n: Int, text: String) {
    val colors = toneColors(Tone.TEAL)
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            Modifier.size(26.dp).clip(CircleShape).background(colors.container),
            contentAlignment = Alignment.Center,
        ) {
            Text("$n", style = MaterialTheme.typography.labelLarge, color = colors.ink,
                 fontWeight = FontWeight.Bold)
        }
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun TipsRow() {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Tips for the best view", style = MaterialTheme.typography.titleLarge,
             fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.height(IntrinsicSize.Max)) {
            Tip(Icons.Outlined.LightMode, "Some light",
                "A dark room pauses the watch. A night light helps.",
                Modifier.weight(1f).fillMaxHeight())
            Tip(Icons.Outlined.PhoneAndroid, "Keep it still",
                "A knocked phone pauses it until it settles.",
                Modifier.weight(1f).fillMaxHeight())
            Tip(Icons.Outlined.CropFree, "Whole cot",
                "Keep all four rails in the picture.",
                Modifier.weight(1f).fillMaxHeight())
        }
    }
}

@Composable
private fun Tip(icon: ImageVector, title: String, body: String, modifier: Modifier) {
    NilaCard(modifier = modifier, contentPadding = PaddingValues(12.dp), verticalSpacing = 8.dp) {
        IconBadge(icon, Tone.TEAL, size = 44.dp, modifier = Modifier.align(Alignment.CenterHorizontally))
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
             textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        Text(body, style = MaterialTheme.typography.labelMedium,
             color = MaterialTheme.colorScheme.onSurfaceVariant,
             textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    }
}

/** The limits, in the caution colour, as a list somebody can actually read. */
@Composable
private fun LimitsCard() {
    val colors = severityColors(Severity.ATTENTION)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(colors.container)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Outlined.WarningAmber, contentDescription = null, tint = colors.accent)
            Text("What this watch can and cannot do", style = MaterialTheme.typography.titleSmall,
                 fontWeight = FontWeight.Bold, color = colors.accent)
        }
        listOf(
            "Reads coarse posture, movement across the frame, and distance relative " +
                "to where the baby started.",
            "Is not a breathing or vital-signs monitor.",
            "Cannot tell a baby who has left the room from one hidden under a blanket.",
            "Pauses, rather than guessing, in a dark room or after the phone is knocked.",
        ).forEach {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.padding(top = 8.dp).size(5.dp).clip(CircleShape)
                        .background(colors.accent))
                Text(it, style = MaterialTheme.typography.bodyMedium, color = colors.onContainer)
            }
        }
    }
}

/**
 * The zone drawn over the preview, and dragged if the parent is editing it.
 *
 * Corner handles resize, the middle moves the whole rectangle. All arithmetic
 * is in normalised coordinates so the overlay lines up with the analysis frame
 * rather than with whatever size the preview happens to be laid out at.
 */
@Composable
private fun ZoneOverlay(
    zone: SafeZone,
    pose: ActivityRules.Assessment?,
    editing: Boolean,
    onChange: (SafeZone) -> Unit,
    modifier: Modifier = Modifier,
    handles: Boolean = editing,
) {
    val outside = pose?.zoneOvershoot?.let { it > 0f } == true
    val line = when {
        outside -> SeverityUrgent
        editing -> Color.White
        else -> ZoneLine
    }

    androidx.compose.foundation.Canvas(
        modifier = modifier.then(
            if (!editing) Modifier else Modifier.pointerInput(zone) {
                var grabbed: Corner? = null
                detectDragGestures(
                    onDragStart = { start ->
                        grabbed = nearestCorner(start, zone, size.width.toFloat(),
                                                size.height.toFloat())
                    },
                    onDragEnd = { grabbed = null },
                ) { change, _ ->
                    val nx = (change.position.x / size.width).coerceIn(0f, 1f)
                    val ny = (change.position.y / size.height).coerceIn(0f, 1f)
                    onChange(
                        when (grabbed) {
                            Corner.TOP_LEFT -> zone.copy(left = nx, top = ny)
                            Corner.TOP_RIGHT -> zone.copy(right = nx, top = ny)
                            Corner.BOTTOM_LEFT -> zone.copy(left = nx, bottom = ny)
                            Corner.BOTTOM_RIGHT -> zone.copy(right = nx, bottom = ny)
                            null -> zone
                        }.normalised().copy(configured = true)
                    )
                }
            }
        )
    ) {
        val l = zone.left * size.width
        val t = zone.top * size.height
        val r = zone.right * size.width
        val b = zone.bottom * size.height

        // The outside of the zone dimmed, so the zone reads as the lit area
        // rather than as a thin line laid over the picture.
        val shade = Color.Black.copy(alpha = if (editing) 0.38f else 0.22f)
        drawRect(shade, topLeft = Offset(0f, 0f),
                 size = androidx.compose.ui.geometry.Size(size.width, t))
        drawRect(shade, topLeft = Offset(0f, b),
                 size = androidx.compose.ui.geometry.Size(size.width, size.height - b))
        drawRect(shade, topLeft = Offset(0f, t),
                 size = androidx.compose.ui.geometry.Size(l, b - t))
        drawRect(shade, topLeft = Offset(r, t),
                 size = androidx.compose.ui.geometry.Size(size.width - r, b - t))
        drawRect(line.copy(alpha = 0.10f), topLeft = Offset(l, t),
                 size = androidx.compose.ui.geometry.Size(r - l, b - t))

        drawRect(
            color = line,
            topLeft = Offset(l, t),
            size = androidx.compose.ui.geometry.Size(r - l, b - t),
            style = Stroke(
                width = if (editing) 4f else 5f,
                pathEffect = if (outside) androidx.compose.ui.graphics.PathEffect
                    .dashPathEffect(floatArrayOf(22f, 14f)) else null,
            ),
        )

        if (handles || outside) {
            val radius = if (editing) 20f else 13f
            listOf(l to t, r to t, l to b, r to b).forEach { (x, y) ->
                drawCircle(color = line, radius = radius, center = Offset(x, y))
                drawCircle(color = Color.White, radius = radius * 0.45f, center = Offset(x, y))
            }
        }
    }
}

private enum class Corner { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

private fun nearestCorner(
    at: Offset,
    zone: SafeZone,
    width: Float,
    height: Float,
): Corner {
    val candidates = mapOf(
        Corner.TOP_LEFT to Offset(zone.left * width, zone.top * height),
        Corner.TOP_RIGHT to Offset(zone.right * width, zone.top * height),
        Corner.BOTTOM_LEFT to Offset(zone.left * width, zone.bottom * height),
        Corner.BOTTOM_RIGHT to Offset(zone.right * width, zone.bottom * height),
    )
    return candidates.minByOrNull { (_, corner) ->
        (corner.x - at.x) * (corner.x - at.x) + (corner.y - at.y) * (corner.y - at.y)
    }!!.key
}

private fun posture(posture: Posture?): String = when (posture) {
    null, Posture.UNKNOWN -> "Not sure"
    Posture.ON_BACK -> "On back"
    Posture.ON_FRONT -> "On front"
    Posture.ON_SIDE -> "On side"
    Posture.SITTING -> "Sitting"
    Posture.UPRIGHT -> "Upright"
}

/**
 * Distance as a multiple of where the baby started, never as a length.
 *
 * A number in centimetres would need the camera's field of view and the baby's
 * real torso length, and getting either wrong produces a confident figure that
 * is simply false. "1.4x further than where they started" is what a single
 * camera can actually support.
 */
private fun distance(ratio: Float?): String = when {
    ratio == null || ratio <= 0f -> "-"
    ratio < 1.1f -> "As placed"
    else -> "%.1fx away".format(ratio)
}
