// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.data.contributions

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException

/** What the backend returns for an upload; [withdrawToken] is shown only this once (the server keeps its hash). */
class UploadedContribution(val id: String, val withdrawToken: String) {
    override fun toString() = "UploadedContribution(id=$id, withdrawToken=…)"
}

/** HTTP 429: wait [retryAfterSeconds] (the backend sends 60). */
class ContributionRateLimitedException(val retryAfterSeconds: Long) : IOException("Rate limited, retry after $retryAfterSeconds s")

/** The backend refused the request for good (400, 401, 409, 413, 415, 422): sending it again won't help. */
class ContributionRejectedException(val code: Int) : IOException("Contribution refused: HTTP $code")

/** The backend's contribution routes (issue #14, backend/README.md "Scan contributions"). */
interface ContributionApi {
    /**
     * Uploads under the client's own [id] (UUID v4) and [withdrawToken], both stored before the first attempt. The
     * same id and token again answer with the stored contribution instead of a second copy, so a retry after a lost
     * answer is safe (`201` new, `200` known, `409` the id belongs to another token).
     */
    suspend fun upload(
        cardId: String,
        lang: String,
        consent: String,
        id: String,
        withdrawToken: String,
        jpeg: ByteArray,
    ): UploadedContribution

    /** Withdraws an upload. A `404` (unknown id, or a token that doesn't fit) counts as done: nothing is left to do. */
    suspend fun withdraw(id: String, withdrawToken: String)
}

/**
 * Sends only what the contract names: the card id the user confirmed, the print's language, the consent version and
 * the image. No device, account or location data, and the token never goes into a log or an exception message.
 */
class HttpContributionApi(private val baseUrl: String, private val client: Call.Factory) : ContributionApi {
    override suspend fun upload(
        cardId: String,
        lang: String,
        consent: String,
        id: String,
        withdrawToken: String,
        jpeg: ByteArray,
    ): UploadedContribution =
        withContext(Dispatchers.IO) {
            val url = (baseUrl.trimEnd('/') + "/v1/contributions").toHttpUrl().newBuilder()
                .addQueryParameter("cardId", cardId)
                .addQueryParameter("lang", lang)
                .addQueryParameter("consent", consent)
                .addQueryParameter("id", id)
                .build()
            val request = Request.Builder().url(url).post(jpeg.toRequestBody(JPEG))
                .header("Authorization", "Bearer $withdrawToken")
                .build()
            client.newCall(request).execute().use { response ->
                ensureOk(response)
                val body = JSONObject(response.body!!.string())
                UploadedContribution(body.getString("id"), body.getString("withdrawToken"))
            }
        }

    override suspend fun withdraw(id: String, withdrawToken: String) = withContext(Dispatchers.IO) {
        val url = (baseUrl.trimEnd('/') + "/v1/contributions").toHttpUrl().newBuilder().addPathSegment(id).build()
        val request = Request.Builder().url(url).delete().header("Authorization", "Bearer $withdrawToken").build()
        client.newCall(request).execute().use { response ->
            if (response.code != 404) ensureOk(response)
        }
    }

    private fun ensureOk(response: Response) {
        val code = response.code
        when {
            code in 200..299 -> Unit
            code == 429 -> throw ContributionRateLimitedException(response.header("Retry-After")?.toLongOrNull()?.coerceIn(1, 3600) ?: 60)
            code in PERMANENT -> throw ContributionRejectedException(code)
            else -> throw IOException("Contribution service: HTTP $code")
        }
    }

    private companion object {
        val JPEG = "image/jpeg".toMediaType()
        val PERMANENT = setOf(400, 401, 409, 413, 415, 422)
    }
}
