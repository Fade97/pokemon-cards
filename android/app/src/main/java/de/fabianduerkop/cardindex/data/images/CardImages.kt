// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.data.images

/** TCGdex serves each card image in two sizes below its base URL (ADR 0009). */
enum class ImageQuality(val file: String) {
    /** ~245 px wide; grids and lists. */
    LOW("low.webp"),

    /** ~600 px wide; the card detail screen. */
    HIGH("high.webp"),
}

/**
 * The CDN URL of a card image. [base] is the card's `imageUrl` from TCGdex, e.g.
 * `https://assets.tcgdex.net/en/sv/sv03.5/025`; TCGdex leaves it out for cards without an image (many JP cards).
 */
fun cardImageUrl(base: String?, quality: ImageQuality): String? {
    val trimmed = base?.trim()?.trimEnd('/')
    if (trimmed.isNullOrEmpty()) return null
    return "$trimmed/${quality.file}"
}
