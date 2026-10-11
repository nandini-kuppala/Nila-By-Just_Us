package com.nila.skin.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.nila.skin.core.image.PixelImage
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * Photos of the baby, kept in app-private storage and nowhere else.
 *
 * Nothing is uploaded and nothing goes to the gallery: these are pictures of a
 * child's body, and the only way they leave the phone is the parent sharing a
 * doctor report themselves.
 */
class PhotoStore(private val context: Context) {

    private val dir = File(context.filesDir, "nila-skin/photos").apply { mkdirs() }

    fun save(bitmap: Bitmap, prefix: String): String {
        val f = File(dir, "${prefix}_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}.jpg")
        FileOutputStream(f).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        return f.absolutePath
    }

    fun load(path: String, maxSide: Int = 1600): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        val opts = BitmapFactory.Options().apply { inSampleSize = sample(bounds.outWidth, bounds.outHeight, maxSide) }
        BitmapFactory.decodeFile(path, opts)
    }.getOrNull()

    /** A picked image, upright and no bigger than [maxSide]. */
    fun loadUri(uri: Uri, maxSide: Int = 1600): Bitmap? = runCatching {
        val cr = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample(bounds.outWidth, bounds.outHeight, maxSide) }
        val bmp = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
        val rotation = cr.openInputStream(uri)?.use { ExifInterface(it).rotationDegrees } ?: 0
        if (rotation == 0) bmp else Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rotation.toFloat()) }, true)
    }.getOrNull()

    fun loadAsset(name: String): Bitmap? = runCatching { context.assets.open(name).use { BitmapFactory.decodeStream(it) } }.getOrNull()

    fun deleteAll() { dir.listFiles()?.forEach { it.delete() } }

    private fun sample(w: Int, h: Int, maxSide: Int): Int {
        var s = 1
        while (maxOf(w, h) / (s * 2) >= maxSide) s *= 2
        return s
    }

    companion object {
        fun toPixels(bmp: Bitmap, maxSide: Int = 900): PixelImage {
            val scale = minOf(1f, maxSide.toFloat() / maxOf(bmp.width, bmp.height))
            val b = if (scale < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt().coerceAtLeast(1), (bmp.height * scale).toInt().coerceAtLeast(1), true) else bmp
            val px = IntArray(b.width * b.height)
            b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
            return PixelImage(b.width, b.height, px)
        }

        fun toBitmap(img: PixelImage): Bitmap = Bitmap.createBitmap(img.argb, img.width, img.height, Bitmap.Config.ARGB_8888)
    }
}
