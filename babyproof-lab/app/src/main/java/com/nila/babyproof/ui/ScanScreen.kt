package com.nila.babyproof.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nila.babyproof.pipeline.Finding
import com.nila.babyproof.pipeline.Scan
import com.nila.babyproof.pipeline.Severity
import com.nila.babyproof.pipeline.Status

private val Teal = Color(0xFF1E6B5C)
private val Cream = Color(0xFFF7F4EE)
private val Ink = Color(0xFF16202E)
private val Muted = Color(0xFF5B6270)
private val Urgent = Color(0xFFC0392B)
private val High = Color(0xFFE07B00)
private val Medium = Color(0xFFB8860B)
private val Done = Color(0xFF2E8B57)
private val Check = Color(0xFF3F6FB5)

@Composable
fun Theme(content: @Composable () -> Unit) = MaterialTheme(
    colorScheme = lightColorScheme(primary = Teal, background = Cream, surface = Color.White, onSurface = Ink),
    content = content,
)

private fun colorOf(f: Finding) = when (f.status) {
    Status.PROTECTED -> Done
    Status.CHECK -> Check
    Status.FIX -> when (f.severity) { Severity.URGENT -> Urgent; Severity.HIGH -> High; Severity.MEDIUM -> Medium }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ScanScreen(vm: ScanViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    val (detectorIn, vlmIn) = remember { vm.modelsInstalled() }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        uri?.let(vm::scanUri)
    }
    Column(
        Modifier.fillMaxSize().background(Cream).verticalScroll(rememberScrollState())
            .statusBarsPadding().navigationBarsPadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Shield, null, tint = Teal, modifier = Modifier.size(32.dp))
            Spacer(Modifier.width(10.dp))
            Column {
                Text("Babyproof a room", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Ink)
                Text("Photograph a room; Nila marks what to fix before your baby crawls. All on this phone.",
                    fontSize = 14.sp, color = Muted)
            }
        }
        if (!detectorIn || !vlmIn) {
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3E0))) {
                Text(buildString {
                    if (!detectorIn) append("Detector not installed (files/models/owl_vit). ")
                    if (!vlmIn) append("Vision model not installed (files/models/intern-vl): detector only.")
                }, Modifier.padding(12.dp), fontSize = 13.sp, color = Ink)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                enabled = !state.running, colors = ButtonDefaults.buttonColors(containerColor = Teal),
            ) {
                Icon(Icons.Outlined.PhotoLibrary, null); Spacer(Modifier.width(8.dp)); Text("Choose a photo")
            }
            FilterChip(selected = state.full, onClick = { vm.setFull(!state.full) },
                label = { Text(if (state.full) "Detector + vision model" else "Detector only") })
        }
        if (vm.samples.isNotEmpty()) {
            Text("Try a sample room", fontWeight = FontWeight.SemiBold, color = Ink)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                vm.samples.forEach { s ->
                    OutlinedButton(onClick = { vm.scanSample(s) }, enabled = !state.running) {
                        Text(s.substringBeforeLast('.').replace('-', ' ').replaceFirstChar { it.uppercase() }, fontSize = 13.sp)
                    }
                }
            }
        }
        state.photo?.let { photo ->
            PhotoWithBoxes(photo, state.scan)
        }
        if (state.running) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(22.dp), color = Teal, strokeWidth = 3.dp)
                Spacer(Modifier.width(12.dp))
                Text(state.step, color = Ink)
            }
        }
        state.error?.let { Text("Something went wrong: $it", color = Urgent) }
        state.scan?.let { Results(it) }
    }
}

@Composable
private fun PhotoWithBoxes(photo: android.graphics.Bitmap, scan: Scan?) {
    val numbered = scan?.let { it.toFix + it.done }.orEmpty()
    Box(Modifier.fillMaxWidth().aspectRatio(photo.width.toFloat() / photo.height).clip(RoundedCornerShape(14.dp))) {
        Image(photo.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
        Canvas(Modifier.fillMaxSize()) {
            numbered.forEachIndexed { i, f ->
                val b = f.box ?: return@forEachIndexed
                val c = colorOf(f)
                val tl = Offset(b.l * size.width, b.t * size.height)
                val sz = Size(b.w * size.width, b.h * size.height)
                drawRect(c, tl, sz, style = Stroke(width = 3.dp.toPx(),
                    pathEffect = if (f.status == Status.CHECK) PathEffect.dashPathEffect(floatArrayOf(14f, 10f)) else null))
                val r = 11.dp.toPx()
                drawCircle(c, r, tl + Offset(r, r))
                drawContext.canvas.nativeCanvas.drawText("${i + 1}", tl.x + r, tl.y + r * 1.4f,
                    android.graphics.Paint().apply {
                        color = android.graphics.Color.WHITE; textSize = 13.sp.toPx()
                        textAlign = android.graphics.Paint.Align.CENTER; isFakeBoldText = true
                    })
            }
        }
    }
}

@Composable
private fun Results(scan: Scan) {
    val fix = scan.toFix
    val urgent = fix.count { it.status == Status.FIX && it.severity == Severity.URGENT }
    val secs = (scan.timings["total"] ?: 0L) / 1000.0
    Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(if (fix.isEmpty()) "Nothing to fix found" else "${fix.size} things to babyproof", fontSize = 22.sp,
                fontWeight = FontWeight.Bold, color = Ink)
            Text(buildString {
                if (urgent > 0) append("$urgent urgent · ")
                if (scan.done.isNotEmpty()) append("${scan.done.size} already done · ")
                append("checked in ${"%.1f".format(secs)} s")
            }, color = Muted, fontSize = 14.sp)
        }
    }
    fix.forEachIndexed { i, f -> FindingCard(i + 1, f) }
    if (scan.done.isNotEmpty()) {
        Text("Already babyproofed", fontWeight = FontWeight.SemiBold, color = Done)
        scan.done.forEachIndexed { i, f -> FindingCard(fix.size + i + 1, f) }
    }
    Text("No list can promise a room is safe: walk the room at your baby's height too.", fontSize = 12.sp, color = Muted)
    Text("Lab: " + scan.timings.entries.joinToString(" · ") { "${it.key} ${it.value}" } + " · " + scan.notes.joinToString(),
        fontSize = 11.sp, color = Muted)
}

@Composable
private fun FindingCard(n: Int, f: Finding) {
    val ctx = LocalContext.current
    val c = colorOf(f)
    Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, c.copy(alpha = 0.35f))) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(26.dp).clip(CircleShape).background(c), contentAlignment = Alignment.Center) {
                    Text("$n", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
                Spacer(Modifier.width(10.dp))
                Text(f.hazard.label, fontWeight = FontWeight.Bold, fontSize = 17.sp, color = Ink, modifier = Modifier.weight(1f))
                Text(if (f.status == Status.FIX) f.severity.label else f.status.label, color = c, fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold)
            }
            if (f.status == Status.PROTECTED) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.CheckCircle, null, tint = Done, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp)); Text(f.why, fontSize = 14.sp, color = Muted)
                }
            } else {
                Text(f.hazard.fix, fontSize = 15.sp, color = Ink)
                Text(f.why, fontSize = 12.sp, color = Muted)
            }
            Text("Source: ${f.hazard.source.name}", fontSize = 12.sp, color = Teal,
                modifier = Modifier.clickable {
                    ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(f.hazard.source.url)))
                })
        }
    }
}
