// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.data.images

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Turns a picked photo into the JPEG the [ScanImageStore] keeps: at most [maxSide] px on the long side, so a 12 MP
 * gallery photo doesn't cost 5 MB per copy. ImageDecoder applies the EXIF rotation; the API 26–27 fallback doesn't,
 * which only matters for photos the camera stored sideways.
 */
suspend fun ScanImageStore.importPhoto(
    resolver: ContentResolver,
    uri: Uri,
    copyUuid: String,
    maxSide: Int = 1200,
) {
    val jpeg = withContext(Dispatchers.IO) {
        val bitmap = decodeScaled(resolver, uri, maxSide)
        try {
            ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 88, it) }.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }
    save(copyUuid, ByteArrayInputStream(jpeg))
}

private fun decodeScaled(resolver: ContentResolver, uri: Uri, maxSide: Int): Bitmap {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        return ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
            val longSide = maxOf(info.size.width, info.size.height)
            if (longSide > maxSide) {
                val scale = maxSide.toFloat() / longSide
                decoder.setTargetSize((info.size.width * scale).toInt(), (info.size.height * scale).toInt())
            }
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    }
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, bounds) }
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    return resolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, options) }
        ?: error("Not an image")
}
