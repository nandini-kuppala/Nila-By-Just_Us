package com.nila.skin.ui.components

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FlashOff
import androidx.compose.material.icons.outlined.FlashOn
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.nila.skin.core.image.NCircle
import com.nila.skin.core.measure.Mask
import com.nila.skin.ui.SkinServices
import com.nila.skin.ui.theme.Tone
import java.util.concurrent.Executors
import kotlin.math.min

/** A bundled sample photo for demos: assets/demo/<category>/<file>. */
data class Sample(val category: String, val file: String, val label: String, val credit: String) {
    val asset: String get() = "demo/$category/$file"
}

object Samples {
    private var cache: List<Sample>? = null
    fun all(): List<Sample> = cache ?: runCatching {
        SkinServices.appContext.assets.open("demo/index.tsv").bufferedReader().readLines()
            .drop(1).filter { it.isNotBlank() }
            .map { val c = it.split('\t'); Sample(c[0], c[1], c[2], c.getOrElse(3) { "" }) }
    }.getOrDefault(emptyList()).also { cache = it }
    fun of(category: String) = all().filter { it.category == category }
}

/**
 * Where a photo comes from: the camera, the gallery, or (for demos) a bundled
 * sample. Camera opens full screen, with an optional ghost of the last photo
 * so the same spot is framed the same way each day.
 */
@Composable
fun PhotoInput(
    category: String,
    onPhoto: (Bitmap, String?) -> Unit,
    hint: String,
    ghost: Bitmap? = null,
    flash: Boolean = false,
) {
    val context = LocalContext.current
    var camera by remember { mutableStateOf(false) }
    val askCamera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) camera = true }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { SkinServices.photos.loadUri(it)?.let { b -> onPhoto(b, null) } }
    }
    NilaCard(modifier = Modifier.fillMaxWidth()) {
        Text(hint, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PillButton("Camera", onClick = {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) camera = true
                else askCamera.launch(Manifest.permission.CAMERA)
            }, icon = Icons.Outlined.PhotoCamera, modifier = Modifier.weight(1f))
            OutlinePillButton("Gallery", onClick = {
                pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }, icon = Icons.Outlined.PhotoLibrary, modifier = Modifier.weight(1f))
        }
        val samples = Samples.of(category)
        if (samples.isNotEmpty()) {
            Text("Demo photos", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                samples.forEach { s ->
                    val bmp = remember(s.asset) { SkinServices.photos.loadAsset(s.asset) }
                    // Nappy thumbnails are shown as a soft colour mosaic: the colour is what matters.
                    val thumb = remember(s.asset, bmp) { if (category == "stool") bmp?.let { Bitmap.createScaledBitmap(it, 9, 9, true) } else bmp }
                    Column(Modifier.width(96.dp).clickable { bmp?.let { onPhoto(it, s.label) } }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        thumb?.let {
                            Image(it.asImageBitmap(), contentDescription = s.label, contentScale = ContentScale.Crop,
                                filterQuality = if (category == "stool") androidx.compose.ui.graphics.FilterQuality.None else androidx.compose.ui.graphics.FilterQuality.Low,
                                modifier = Modifier.size(96.dp).clip(RoundedCornerShape(12.dp)))
                        }
                        Text(s.label, style = MaterialTheme.typography.labelSmall, maxLines = 2, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
    if (camera) CameraCapture(ghost = ghost, flashOn = flash, onDismiss = { camera = false }) { b -> camera = false; onPhoto(b, null) }
}

@Composable
fun CameraCapture(ghost: Bitmap?, flashOn: Boolean, onDismiss: () -> Unit, onResult: (Bitmap) -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var flash by remember { mutableStateOf(flashOn) }
    val capture = remember { ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build() }
    val executor = remember { Executors.newSingleThreadExecutor() }
    var busy by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AndroidView(factory = { ctx ->
                val view = PreviewView(ctx).apply { scaleType = PreviewView.ScaleType.FIT_CENTER }
                val future = ProcessCameraProvider.getInstance(ctx)
                future.addListener({
                    val provider = future.get()
                    val preview = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
                    runCatching {
                        provider.unbindAll()
                        provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture)
                    }
                }, ContextCompat.getMainExecutor(ctx))
                view
            }, modifier = Modifier.fillMaxSize())
            ghost?.let {
                Image(it.asImageBitmap(), contentDescription = "Last photo", contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().alpha(0.35f))
            }
            Row(Modifier.statusBarsPadding().padding(12.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, contentDescription = "Close", tint = Color.White) }
                IconButton(onClick = { flash = !flash }) {
                    Icon(if (flash) Icons.Outlined.FlashOn else Icons.Outlined.FlashOff, contentDescription = "Flash", tint = Color.White)
                }
            }
            if (ghost != null) Text("Line up with the faint last photo", color = Color.White, style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 64.dp))
            Box(
                Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 72.dp).size(78.dp)
                    .clip(CircleShape).background(Color.White.copy(alpha = if (busy) 0.4f else 1f)).border(4.dp, Color.White.copy(alpha = 0.5f), CircleShape)
                    .clickable(enabled = !busy) {
                        busy = true
                        capture.flashMode = if (flash) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF
                        capture.takePicture(executor, object : ImageCapture.OnImageCapturedCallback() {
                            override fun onCaptureSuccess(image: ImageProxy) {
                                val bmp = upright(image)
                                image.close()
                                ContextCompat.getMainExecutor(context).execute { busy = false; onResult(bmp) }
                            }
                            override fun onError(exception: ImageCaptureException) {
                                ContextCompat.getMainExecutor(context).execute { busy = false }
                            }
                        })
                    },
            )
        }
    }
    DisposableEffect(Unit) { onDispose { runCatching { ProcessCameraProvider.getInstance(context).get().unbindAll() }; executor.shutdown() } }
}

private fun upright(image: ImageProxy): Bitmap {
    val raw = image.toBitmap()
    val deg = image.imageInfo.rotationDegrees
    val scale = min(1f, 1600f / maxOf(raw.width, raw.height))
    val m = Matrix().apply { postScale(scale, scale); if (deg != 0) postRotate(deg.toFloat()) }
    return Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
}

/** One circle the parent places on the photo. */
data class Marker(val circle: NCircle, val color: Color, val label: String)

/**
 * The photo with circles on it. Tap to move the active circle; the slider
 * below sets its size. Optionally draws the measured outline.
 */
@Composable
fun MarkablePhoto(
    bitmap: Bitmap,
    markers: List<Marker>,
    active: Int,
    onMove: (Int, NCircle) -> Unit,
    mask: Mask? = null,
    showSlider: Boolean = true,
    /** Pixels used as the white reference, drawn as small dots so the parent can check them. */
    dots: List<com.nila.skin.core.image.NPoint> = emptyList(),
) {
    val img = remember(bitmap) { bitmap.asImageBitmap() }
    val aspect = bitmap.width.toFloat() / bitmap.height
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(aspect).clip(RoundedCornerShape(16.dp))
                .pointerInput(active, markers) {
                    detectTapGestures { p ->
                        val m = markers.getOrNull(active) ?: return@detectTapGestures
                        onMove(active, m.circle.copy(cx = (p.x / size.width).toDouble().coerceIn(0.0, 1.0), cy = (p.y / size.height).toDouble().coerceIn(0.0, 1.0)))
                    }
                }
        ) {
            Image(img, contentDescription = null, contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize())
            androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                if (mask != null) {
                    val cw = size.width / mask.width; val ch = size.height / mask.height
                    for (y in 0 until mask.height step 2) for (x in 0 until mask.width step 2) {
                        if (mask.bits[y * mask.width + x]) drawRect(Color(0x554FC3F7), Offset(x * cw, y * ch), androidx.compose.ui.geometry.Size(cw * 2, ch * 2))
                    }
                }
                dots.forEachIndexed { k, d ->
                    if (k % 4 == 0) {
                        val o = Offset((d.x * size.width).toFloat(), (d.y * size.height).toFloat())
                        drawCircle(Color.Black.copy(alpha = 0.55f), 6f, o)
                        drawCircle(Color.White, 4f, o)
                    }
                }
                val short = min(size.width, size.height)
                markers.forEachIndexed { i, m ->
                    val c = Offset((m.circle.cx * size.width).toFloat(), (m.circle.cy * size.height).toFloat())
                    val r = (m.circle.r * short).toFloat()
                    drawCircle(Color.Black.copy(alpha = 0.35f), r, c, style = Stroke(width = 9f))
                    drawCircle(m.color, r, c, style = Stroke(width = if (i == active) 6f else 3.5f))
                }
            }
        }
        if (showSlider) markers.getOrNull(active)?.let { m ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Size", style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(44.dp))
                Slider(value = m.circle.r.toFloat(), onValueChange = { onMove(active, m.circle.copy(r = it.toDouble())) }, valueRange = 0.02f..0.45f, modifier = Modifier.weight(1f))
            }
        }
    }
}

/** Toggle for which marker is being placed. */
@Composable
fun MarkerTabs(markers: List<Marker>, active: Int, onSelect: (Int) -> Unit) {
    if (markers.size < 2) return
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        markers.forEachIndexed { i, m ->
            Row(
                Modifier.clip(CircleShape).background(if (i == active) m.color.copy(alpha = 0.22f) else MaterialTheme.colorScheme.surfaceContainerLow)
                    .clickable { onSelect(i) }.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(m.color))
                Text(m.label, style = MaterialTheme.typography.labelLarge, fontWeight = if (i == active) FontWeight.SemiBold else FontWeight.Medium)
            }
        }
    }
}

/** A thumbnail from a saved path. */
@Composable
fun Thumb(path: String?, size: Int = 64, modifier: Modifier = Modifier) {
    val bmp = remember(path) { path?.let { SkinServices.photos.load(it, 300) } }
    Box(modifier.size(size.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainer)) {
        bmp?.let { Image(it.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
    }
}

