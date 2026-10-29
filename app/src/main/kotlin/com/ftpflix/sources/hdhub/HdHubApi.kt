package com.ftpflix.sources.hdhub

import com.ftpflix.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

// Subset of a Stremio addon stream entry.
@Serializable
data class HdHubStream(
    val name: String? = null,
    val description: String? = null,
    val url: String? = null,
    val externalUrl: String? = null,
    @SerialName("behaviorHints") val behaviorHints: HdHubBehaviorHints? = null,
)

@Serializable
data class HdHubBehaviorHints(
    val videoSize: Long? = null,
    @SerialName("notWebReady") val notWebReady: Boolean? = null,
)

@Serializable
data class HdHubResponse(
    val streams: List<HdHubStream> = emptyList(),
    val cacheMaxAge: Long = 0L,
)

// HTTP client for the HdHub Stremio addon (movie/series stream endpoints).
@Singleton
class HdHubApi @Inject constructor(
    okHttp: OkHttpClient,
    private val json: Json,
) {
    private val client = okHttp.newBuilder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    // Raw stream entries; empty on any failure.
    suspend fun fetchStreams(type: String, id: String): List<HdHubStream> = withContext(Dispatchers.IO) {
        if (BASE_URL.isBlank()) return@withContext emptyList()
        runCatching {
            val url = "$BASE_URL/stream/$type/$id.json"
            val request = Request.Builder().url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .header("Referer", BASE_URL)
                .build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@use emptyList()
                val body = resp.body
                val text = body.string()
                if (text.isBlank()) return@use emptyList()
                json.decodeFromString<HdHubResponse>(text).streams
            }
        }.getOrDefault(emptyList())
    }

    companion object {
        const val HOST_LABEL = "hdhub"

        /** Configured in local.properties (HDHUB_ADDON_URL); blank disables the source. */
        const val BASE_URL: String = BuildConfig.HDHUB_ADDON_URL
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/150 Safari/537.36"
    }
}
