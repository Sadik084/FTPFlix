package com.ftpflix.sources.hyper

import com.ftpflix.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

// Subset of a Stremio addon stream entry.
@Serializable
data class HyperStream(
    val title: String? = null,
    val url: String? = null,
    val externalUrl: String? = null,
    @SerialName("behaviorHints") val behaviorHints: HyperBehaviorHints? = null,
)

@Serializable
data class HyperBehaviorHints(
    val videoSize: Long? = null,
    val proxyHeaders: HyperProxyHeaders? = null,
)

@Serializable
data class HyperProxyHeaders(
    val request: JsonObject? = null,
)

@Serializable
data class HyperResponse(
    val streams: List<HyperStream> = emptyList(),
)

// HTTP client for the Hyper Stremio addon (movie/series stream endpoints).
@Singleton
class HyperApi @Inject constructor(
    okHttp: OkHttpClient,
    private val json: Json,
) {
    private val client = okHttp.newBuilder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    // Raw stream entries; empty on any failure.
    suspend fun fetchStreams(type: String, id: String): List<HyperStream> = withContext(Dispatchers.IO) {
        if (BASE_URL.isBlank()) return@withContext emptyList()
        runCatching {
            val url = "$BASE_URL/stream/$type/$id.json"
            val request = Request.Builder().url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@use emptyList()
                val text = resp.body.string()
                if (text.isBlank()) return@use emptyList()
                json.decodeFromString<HyperResponse>(text).streams
            }
        }.getOrDefault(emptyList())
    }

    companion object {
        const val HOST_LABEL = "hyper"

        /** Configured in local.properties (HYPER_ADDON_URL); blank disables the source. */
        const val BASE_URL: String = BuildConfig.HYPER_ADDON_URL
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/150 Safari/537.36"
    }
}
