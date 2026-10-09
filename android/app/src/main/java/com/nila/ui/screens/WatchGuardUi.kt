package com.nila.ui.screens

import android.media.AudioManager
import android.view.TextureView
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.CropFree
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Face
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.nila.data.Severity
import com.nila.monitor.WatchService
import com.nila.ui.components.IconBadge
import com.nila.ui.components.InfoNote
import com.nila.ui.components.NilaCard
import com.nila.ui.components.OutlinePillButton
import com.nila.ui.components.PillButton
import com.nila.ui.components.TonePill
import com.nila.ui.theme.Tone
import com.nila.ui.theme.severityColors
import com.nila.vision.DayNight
import com.nila.vision.VisionSettings
import com.nila.vision.guard.CctvSource
import com.nila.vision.guard.GuardStatus
import com.nila.vision.guard.NormBox
import com.nila.vision.guard.Verdict
import com.nila.vision.guard.VlmStatus
import com.nila.vision.guard.ZoneRule
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

// The safety watch's guard modes (from Baby Guard CCTV): the switches, the alert, and the cards that say
// what each mode is seeing.

/** The three modes, the alert switches, night vision and the video source. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun GuardModesCard(values: VisionSettings.Values, running: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = remember { VisionSettings(context) }
    val scheme = MaterialTheme.colorScheme

    NilaCard(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(16.dp), verticalSpacing = 12.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconBadge(Icons.Outlined.Shield, Tone.TEAL, size = 40.dp)
            Column(Modifier.weight(1f)) {
                Text("What to watch for", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("Turn on any mix. Each one alerts on its own.", style = MaterialTheme.typography.bodySmall,
                     color = scheme.onSurfaceVariant)
            }
        }

        // Quick choices for the common days and nights.
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Preset("Night: base", values.baseOn && !values.vlmOn && !values.zoneOn) {
                scope.launch { settings.setModes(base = true, vlm = false, zone = false) }
            }
            Preset("Playing: safe zone", !values.baseOn && !values.vlmOn && values.zoneOn) {
                scope.launch { settings.setModes(base = false, vlm = false, zone = true) }
            }
            Preset("Full: AI + zone", !values.baseOn && values.vlmOn && values.zoneOn) {
                scope.launch { settings.setModes(base = false, vlm = true, zone = true) }
            }
            Preset("All three", values.baseOn && values.vlmOn && values.zoneOn) {
                scope.launch { settings.setModes(base = true, vlm = true, zone = true) }
            }
        }

        ModeRow(Icons.Outlined.Face, Tone.TEAL, "Base watch",
                "Face, posture and movement. No AI model, lightest on the battery: good for the night.",
                values.baseOn) { scope.launch { settings.setModes(base = it) } }
        ModeRow(Icons.Outlined.Visibility, Tone.PLUM, "Danger AI",
                "The vision model on the NPU looks again and again for stairs, cables, sockets, heat, sharp " +
                    "or small objects, and alerts at the first danger.",
                values.vlmOn) { scope.launch { settings.setModes(vlm = it) } }
        if (values.vlmOn && !values.modelEnabled) {
            InfoNote("The vision model is turned off in Settings › Camera (Describe what it sees).",
                     icon = Icons.Outlined.WarningAmber, tone = Tone.PEACH)
        }
        ModeRow(Icons.Outlined.CropFree, Tone.BLUE, "Safe zone",
                "Finds the baby four times a second and alerts when they leave the zone you draw on the picture.",
                values.zoneOn) { scope.launch { settings.setModes(zone = it) } }
        if (values.noMode) {
            InfoNote("Nothing is switched on, so the watch only shows the picture.", tone = Tone.PEACH)
        }

        HorizontalDivider(color = scheme.outlineVariant)

        ModeRow(Icons.Outlined.NotificationsActive, Tone.PINK, "Lights and sound on alerts",
                "Flashing screen, ring light" + (if (values.torchEnabled) ", torch" else "") +
                    (if (values.distractionEnabled) " and the lullaby" else "") +
                    ". A notification is always sent.",
                values.alertsOn) { scope.launch { settings.setAlertsOn(it) } }
        val audio = remember { context.getSystemService(AudioManager::class.java) }
        val vol = audio?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0
        val max = audio?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 15
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            FilterChip(selected = values.distractionEnabled,
                       onClick = { scope.launch { settings.setDistractionEnabled(!values.distractionEnabled) } },
                       label = { Text("Lullaby") },
                       leadingIcon = { Icon(Icons.Outlined.MusicNote, null, Modifier.size(18.dp)) })
            FilterChip(selected = values.torchEnabled,
                       onClick = { scope.launch { settings.setTorchEnabled(!values.torchEnabled) } },
                       label = { Text("Flash") })
        }
        Text("The lullaby follows the phone's media volume (now $vol of $max)." +
                 if (vol == 0) " At 0 it is silent." else "",
             style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)

        Text("Night vision", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                DayNight.Preference.AUTO to "Automatic",
                DayNight.Preference.ALWAYS_NIGHT to "Always on",
                DayNight.Preference.ALWAYS_DAY to "Off",
            ).forEach { (pref, label) ->
                FilterChip(selected = values.nightPreference == pref,
                           onClick = { scope.launch { settings.setNightPreference(pref) } },
                           label = { Text(label) },
                           leadingIcon = if (pref == DayNight.Preference.ALWAYS_NIGHT) {
                               { Icon(Icons.Outlined.DarkMode, null, Modifier.size(18.dp)) }
                           } else null)
            }
        }

        Text("Camera", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            FilterChip(selected = values.source == VisionSettings.Source.PHONE,
                       onClick = { scope.launch { settings.setSource(VisionSettings.Source.PHONE) } },
                       label = { Text("This phone") })
            FilterChip(selected = values.source == VisionSettings.Source.CCTV,
                       onClick = { scope.launch { settings.setSource(VisionSettings.Source.CCTV) } },
                       label = { Text("CCTV camera") },
                       leadingIcon = { Icon(Icons.Outlined.Videocam, null, Modifier.size(18.dp)) })
        }
        if (values.source == VisionSettings.Source.PHONE) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = values.lens == VisionSettings.Lens.MAIN,
                           onClick = { scope.launch { settings.setLens(VisionSettings.Lens.MAIN) } },
                           label = { Text("Main lens") })
                FilterChip(selected = values.lens == VisionSettings.Lens.WIDE,
                           onClick = { scope.launch { settings.setLens(VisionSettings.Lens.WIDE) } },
                           label = { Text("Ultra-wide (whole cot)") })
            }
        } else {
            var url by remember(values.cctvUrl) { mutableStateOf(values.cctvUrl) }
            val demo = url == VisionSettings.DEMO_CCTV_URL
            OutlinedTextField(
                value = if (demo) "Built-in demo video" else url,
                onValueChange = { url = it },
                label = { Text("RTSP address") },
                placeholder = { Text("rtsp://user:pass@192.168.1.20:554/stream1") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { scope.launch { settings.setCctvUrl(url.trim()) } },
                           enabled = !demo && url.isNotBlank() && url != values.cctvUrl) {
                    Text("Use this address")
                }
                TextButton(onClick = {
                    url = VisionSettings.DEMO_CCTV_URL
                    scope.launch { settings.setCctvUrl(VisionSettings.DEMO_CCTV_URL) }
                }, enabled = values.cctvUrl != VisionSettings.DEMO_CCTV_URL) { Text("Demo video") }
                TextButton(onClick = {
                    url = VisionSettings.LAPTOP_SIM_URL
                    scope.launch { settings.setCctvUrl(VisionSettings.LAPTOP_SIM_URL) }
                }, enabled = values.cctvUrl != VisionSettings.LAPTOP_SIM_URL) { Text("Laptop stream") }
            }
            Text("The phone stays with you; the camera watches the baby. Alerts here are a notification, a " +
                     "chime and vibration. The picture comes in while this screen is open.",
                 style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
        }
        if (!running) {
            Text("Changes apply at once, even while watching.", style = MaterialTheme.typography.bodySmall,
                 color = scheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Preset(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(label) })
}

@Composable
private fun ModeRow(
    icon: ImageVector,
    tone: Tone,
    title: String,
    body: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        IconBadge(icon, tone, size = 36.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/**
 * The alert, over everything: a full-screen red/white flash at full brightness, as Baby Guard CCTV does it.
 * Press and hold (or the button) stops it.
 */
@Composable
internal fun AlertFlash(guard: GuardStatus, onStop: () -> Unit) {
    var red by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(350)
            red = !red
        }
    }
    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = false,
                                      dismissOnClickOutside = false, decorFitsSystemWindows = false),
    ) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        DisposableEffect(window) {
            window?.attributes = window?.attributes?.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL }
            onDispose { }
        }
        val bg = if (red) Color(0xFFE00000) else Color.White
        val fg = if (red) Color.White else Color(0xFFE00000)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(bg)
                .pointerInput(Unit) { detectTapGestures(onLongPress = { onStop() }) },
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp),
                   modifier = Modifier.padding(28.dp)) {
                Text(if (guard.alert == com.nila.vision.guard.AlertSource.ZONE) "SAFE ZONE" else "DANGER",
                     color = fg, fontSize = 52.sp, fontWeight = FontWeight.Black)
                Text(guard.alertReason, color = fg, fontSize = 26.sp, fontWeight = FontWeight.Bold,
                     textAlign = TextAlign.Center)
                Text(guard.alert?.label ?: "", color = fg, fontSize = 18.sp)
                Spacer(Modifier.height(12.dp))
                Text("Press and hold to stop the alert", color = fg, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                     textAlign = TextAlign.Center)
                OutlinePillButton("Stop alert", onStop, icon = Icons.Filled.Stop)
            }
        }
    }
}

/** The alert as a card in the list (also when lights and sound are off). */
@Composable
internal fun AlertBanner(guard: GuardStatus, onStop: () -> Unit) {
    val colors = severityColors(Severity.URGENT)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(colors.container)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Outlined.WarningAmber, null, tint = colors.accent, modifier = Modifier.size(26.dp))
            Column(Modifier.weight(1f)) {
                Text("${guard.alert?.label}: alert", style = MaterialTheme.typography.titleMedium,
                     fontWeight = FontWeight.Bold, color = colors.accent)
                Text(guard.alertReason, style = MaterialTheme.typography.bodyMedium, color = colors.onContainer)
            }
        }
        if (!guard.alertsOn) {
            Text("Lights and sound are off, so only the notification went out.",
                 style = MaterialTheme.typography.bodySmall, color = colors.onContainerMuted)
        }
        PillButton("Stop alert", onStop, Modifier.fillMaxWidth(), icon = Icons.Filled.Stop)
    }
}

/** What the vision model last said, its state, and the "Look now" / "Distract" buttons. Works in every mode. */
@Composable
internal fun DangerAiCard(
    guard: GuardStatus,
    onLook: () -> Unit,
    onDistract: () -> Unit,
    onStopDistract: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    NilaCard(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(16.dp), verticalSpacing = 12.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconBadge(Icons.Outlined.Visibility, Tone.PLUM, size = 40.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(if (guard.vlmOn) "Danger AI · watching" else "Danger AI", style = MaterialTheme.typography.titleMedium,
                     fontWeight = FontWeight.Bold)
                Text(
                    when (val s = guard.vlm) {
                        VlmStatus.Off -> if (guard.vlmOn) "Starting…" else "Off. “Look now” asks it once."
                        VlmStatus.NotInstalled -> "Intern3.5-VL-2B is not on this phone"
                        VlmStatus.DisabledInSettings -> "Turned off in Settings › Camera"
                        is VlmStatus.Loading -> "Loading onto the NPU (try ${s.attempt} of ${s.of}, up to ~20 s)"
                        VlmStatus.Ready -> when {
                            guard.looking -> "Looking now…"
                            guard.vlmOn -> "Looking again and again · ${guard.looks} looks"
                            else -> "Ready on the NPU"
                        }
                        is VlmStatus.Failed -> "Not available: ${s.reason}"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
            }
        }
        guard.lastLook?.let { look ->
            val (label, tone) = when (look.verdict) {
                Verdict.DANGER -> "Danger" to Tone.PINK
                Verdict.SAFE -> "Safe" to Tone.TEAL
                Verdict.UNSURE -> "Not sure" to Tone.PEACH
                Verdict.NO_BABY -> "No baby seen" to Tone.SLATE
                Verdict.UNKNOWN -> "No clear answer" to Tone.SLATE
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TonePill(label, tone, dot = true)
                if (look.category.isNotBlank()) TonePill(look.category, Tone.PEACH)
                if (look.nightEnhanced) TonePill("night picture", Tone.PLUM)
            }
            if (look.activity.isNotBlank()) {
                Text(look.activity.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.titleSmall,
                     fontWeight = FontWeight.SemiBold)
            }
            if (look.hazard.isNotBlank()) {
                Text("Hazard: ${look.hazard}", style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
            }
            Text("${agoText(look.at)} · answered in ${"%.1f".format(look.modelMs / 1000f)} s",
                 style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PillButton("Look now", onLook, Modifier.weight(1f), icon = Icons.Outlined.Visibility,
                       enabled = guard.vlm !is VlmStatus.NotInstalled && guard.vlm !is VlmStatus.DisabledInSettings &&
                           !guard.looking)
            if (guard.distracting) {
                OutlinePillButton("Stop", onStopDistract, Modifier.weight(1f), icon = Icons.Filled.Stop)
            } else {
                OutlinePillButton("Distract", onDistract, Modifier.weight(1f), icon = Icons.Outlined.MusicNote)
            }
        }
        Text("Answers go through the guard rails tested on real photos (17 of 18 right, no false alarms). " +
                 "It is a second pair of eyes, not a medical device.",
             style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
    }
}

/** The safe zone's state: where the baby is relative to the zone. */
@Composable
internal fun ZoneStatusCard(guard: GuardStatus) {
    val scheme = MaterialTheme.colorScheme
    val tone = when (guard.zoneStatus) {
        ZoneRule.Status.INSIDE -> Tone.TEAL
        ZoneRule.Status.PARTLY -> Tone.PEACH
        ZoneRule.Status.OUT -> Tone.PINK
        else -> Tone.SLATE
    }
    NilaCard(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(16.dp), verticalSpacing = 8.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconBadge(Icons.Outlined.CropFree, Tone.BLUE, size = 40.dp)
            Column(Modifier.weight(1f)) {
                Text("Safe zone · ${guard.zoneStatus.label}", style = MaterialTheme.typography.titleMedium,
                     fontWeight = FontWeight.Bold)
                Text(
                    if (guard.babyBox != null) "${ZoneRule.pct(guard.outFraction)} of the baby outside · alert at 40% for 1 s"
                    else "Alert when 40% of the baby is outside for 1 s, or the baby vanishes at the edge",
                    style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
                )
            }
            TonePill(when (guard.zoneStatus) {
                ZoneRule.Status.INSIDE -> "Inside"
                ZoneRule.Status.PARTLY -> "Partly out"
                ZoneRule.Status.OUT -> "Out"
                ZoneRule.Status.LOST -> "Lost"
                ZoneRule.Status.NOT_SEEN -> "Looking"
                ZoneRule.Status.NONE -> "Off"
            }, tone, dot = true)
        }
        Text("Person detector on the ${guard.detector.uppercase()} · the tracker fills gaps with movement",
             style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
    }
}

/** The tracked baby's box over the picture, coloured by how much of it is outside the zone. */
@Composable
internal fun BabyBoxOverlay(box: NormBox?, outFraction: Float, modifier: Modifier = Modifier) {
    if (box == null) return
    androidx.compose.foundation.Canvas(modifier) {
        val c = when {
            outFraction >= ZoneRule.OUT_FRACTION -> Color(0xFFFF2828)
            outFraction >= 0.1f -> Color(0xFFFF9800)
            else -> Color(0xFF3CE65A)
        }
        drawRect(
            color = c,
            topLeft = Offset(box.left * size.width, box.top * size.height),
            size = androidx.compose.ui.geometry.Size((box.right - box.left) * size.width, (box.bottom - box.top) * size.height),
            style = Stroke(width = 6f),
        )
    }
}

/**
 * The CCTV picture: an RTSP player on this screen (it needs a surface), handing about 10 frames a second
 * to the watch service. Runs while the screen is open.
 */
@Composable
internal fun CctvPreview(url: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val executor = remember { Executors.newSingleThreadExecutor { r -> Thread(r, "nila-cctv-frames") } }
    var source by remember { mutableStateOf<CctvSource?>(null) }
    AndroidView(
        factory = { ctx ->
            TextureView(ctx).also { view ->
                source = CctvSource(
                    context = ctx,
                    view = view,
                    analysis = executor,
                    onFrame = { bmp -> WatchService.offerCctvFrame(bmp) },
                    log = { android.util.Log.i("NilaGuard", it) },
                ).apply { onVideoSize = { w, h -> WatchService.reportCctv(connected, w, h) } }
            }
        },
        modifier = modifier,
    )
    LaunchedEffect(source, url) {
        val s = source ?: return@LaunchedEffect
        s.start(url)
        while (true) {
            delay(1_000)
            WatchService.reportCctv(s.connected, s.videoWidth, s.videoHeight)
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            source?.stop()
            WatchService.reportCctv(false, 0, 0)
            executor.shutdown()
        }
    }
}

private fun agoText(at: Long): String {
    val s = ((System.currentTimeMillis() - at) / 1000L).toInt()
    return when {
        s < 5 -> "Just now"
        s < 60 -> "$s s ago"
        s < 3600 -> "${s / 60} min ago"
        else -> "Over an hour ago"
    }
}
