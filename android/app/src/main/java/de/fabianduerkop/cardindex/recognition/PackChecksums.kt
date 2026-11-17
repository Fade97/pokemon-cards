// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.recognition

import java.security.MessageDigest

/** A recognition pack file does not match the checksum its meta.json promises. */
class PackIntegrityException(message: String) : IllegalStateException(message)

/** Lowercase hex SHA-256, the format ml/app_assets.py writes into meta.json. */
fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

/**
 * Fails unless [bytes] hash to [expected]. A missing checksum fails too: a pack without one cannot prove that the
 * model and the index belong together, and a mismatched pair returns confident wrong cards instead of an error.
 */
fun verifyChecksum(file: String, bytes: ByteArray, expected: String?) {
    if (expected.isNullOrBlank()) throw PackIntegrityException("meta.json has no checksum for $file")
    val actual = sha256Hex(bytes)
    if (!actual.equals(expected.trim(), ignoreCase = true)) {
        throw PackIntegrityException("$file is damaged or from another pack: SHA-256 $actual, meta.json says $expected")
    }
}
