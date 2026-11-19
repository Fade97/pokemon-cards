// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.data.licenses

import android.content.Context
import de.fabianduerkop.cardindex.R
import org.json.JSONObject

/** A license of one or more libraries: the full text when the build found it, else only where to read it. */
data class LicenseInfo(val name: String, val url: String?, val text: String?)

/** One library the app ships, e.g. "OkHttp 5.5.1 · Apache License 2.0". */
data class LibraryLicense(
    /** Maven coordinates without version, e.g. `com.squareup.okhttp3:okhttp`. */
    val id: String,
    val name: String,
    val version: String?,
    val website: String?,
    val licenses: List<LicenseInfo>,
)

/**
 * The open-source licenses screen's data (#136). The AboutLibraries Gradle plugin writes `res/raw/aboutlibraries.json`
 * for each variant at build time from the dependencies' POMs, so the list always matches what the APK contains and
 * nothing is fetched at runtime. Read with org.json: AboutLibraries' own reader (aboutlibraries-core 15) is built
 * for Java 21, which the JVM unit tests (Java 17) can't load, and the format is two flat lists.
 */
object OpenSourceLicenses {
    fun load(context: Context): List<LibraryLicense> =
        parse(context.resources.openRawResource(R.raw.aboutlibraries).bufferedReader().use { it.readText() })

    /** Sorted by name; a license without text keeps its link. */
    fun parse(json: String): List<LibraryLicense> {
        val root = JSONObject(json)
        val licenses = root.optJSONObject("licenses") ?: JSONObject()
        val libraries = root.optJSONArray("libraries")
        return (0 until (libraries?.length() ?: 0))
            .map { i ->
                val lib = libraries!!.getJSONObject(i)
                val id = lib.getString("uniqueId")
                val keys = lib.optJSONArray("licenses")
                LibraryLicense(
                    id = id,
                    name = lib.text("name") ?: id,
                    version = lib.text("artifactVersion"),
                    website = lib.text("website"),
                    licenses = (0 until (keys?.length() ?: 0))
                        .mapNotNull { k ->
                            val key = keys!!.getString(k)
                            licenses.optJSONObject(key)?.let { LicenseInfo(it.text("name") ?: key, it.text("url"), it.text("content")) }
                        }
                        .sortedBy { it.name },
                )
            }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, LibraryLicense::name).thenBy(LibraryLicense::id))
    }

    /** A non-blank string field, else null (org.json's optString turns a missing field into ""). */
    private fun JSONObject.text(key: String): String? =
        if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }
}
