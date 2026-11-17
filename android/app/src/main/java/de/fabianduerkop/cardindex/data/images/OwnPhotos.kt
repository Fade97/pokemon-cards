// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.data.images

import java.io.File

/** Which copies have an own photo, and whether the user prefers those photos over the CDN images. */
data class OwnPhotos(
    val copyUuids: Set<String> = emptySet(),
    val preferOwn: Boolean = false,
    private val fileFor: (String) -> File = ::File,
) {
    /** The photo of the first of [copies] (uuids, best first) that has one. */
    fun firstOf(copies: List<String>): File? = copies.firstOrNull { it in copyUuids }?.let(fileFor)

    fun of(copyUuid: String): File? = if (copyUuid in copyUuids) fileFor(copyUuid) else null
}
