// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.ui.share

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.core.content.FileProvider
import java.io.File

/**
 * Hands a rendered image to the Android share sheet (#163), the app's only way out for share images: no upload, no
 * account. The PNG goes into `cache/shares` (see `res/xml/share_paths.xml`); older ones are deleted first, so the
 * folder holds at most the image being shared.
 */
object ImageShare {
    private const val DIR = "shares"

    /** Writes [bitmap] as `<name>.png`; call off the main thread. */
    fun write(context: Context, bitmap: Bitmap, name: String): File {
        val dir = File(context.cacheDir, DIR).apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "$name.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return file
    }

    /** The share sheet for [file], readable by the chosen app only. */
    fun intent(context: Context, file: File, chooserTitle: String): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.share", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            // ClipData too: the share sheet's preview and some targets read the image from there.
            clipData = ClipData.newRawUri(null, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, chooserTitle)
    }
}
