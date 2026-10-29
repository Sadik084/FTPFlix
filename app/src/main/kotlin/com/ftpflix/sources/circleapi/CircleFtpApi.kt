package com.ftpflix.sources.circleapi

import com.ftpflix.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

// CircleFTP search API: search + detail return direct stream URLs.
@Serializable
data class CirclePostsResponse(val posts: List<CirclePost> = emptyList())

@Serializable
data class CirclePost(
    val id: Long = 0,
    val name: String? = null,
    val title: String? = null,
    val type: String? = null,
    val year: String? = null,
    val quality: String? = null,
) {
    val displayName: String get() = name ?: title.orEmpty()
}

@Serializable
data class CirclePostDetail(
    val id: Long = 0,
    val title: String? = null,
    val type: String? = null,
    val content: JsonElement? = null,
)

@Serializable
data class CircleSeason(
    @SerialName("seasonName") val seasonName: String? = null,
    val episodes: List<CircleEpisode> = emptyList()
)

@Serializable
data class CircleEpisode(
    val link: String? = null,
    val title: String? = null
)

@Singleton
class CircleFtpApi @Inject constructor(
    okHttp: OkHttpClient,
    private val json: Json
) {
    private val client = okHttp.newBuilder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    suspend fun search(term: String): List<CirclePost>? = withContext(Dispatchers.IO) {
        runCatching {
            val url = "$API_BASE/posts?searchTerm=${android.net.Uri.encode(term)}&order=desc"
            client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                if (!resp.isSuccessful) return@runCatching null
                val body = resp.body.string()
                json.decodeFromString<CirclePostsResponse>(body).posts
            }
        }.getOrNull()
    }

    // Detail: content is a URL for movies, a season array for series.
    suspend fun detail(id: Long): CirclePostDetail? = withContext(Dispatchers.IO) {
        runCatching {
            val url = "$API_BASE/posts/$id"
            client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                if (!resp.isSuccessful) return@runCatching null
                val body = resp.body.string()
                json.decodeFromString<CirclePostDetail>(body)
            }
        }.getOrNull()
    }

    fun movieUrl(detail: CirclePostDetail): String? =
        (detail.content as? JsonPrimitive)?.takeIf { it.isString }?.content

    fun seasons(detail: CirclePostDetail): List<CircleSeason> =
        (detail.content as? JsonArray)?.let { array ->
            runCatching { json.decodeFromJsonElement<List<CircleSeason>>(array) }.getOrNull()
        } ?: emptyList()

    companion object {
        /** Overridable via CIRCLEFTP_API_BASE in local.properties. */
        const val API_BASE: String = BuildConfig.CIRCLEFTP_API_BASE
        const val HOST_LABEL = "circleapi"
        const val TYPE_MOVIE = "singleVideo"
        const val TYPE_SERIES = "series"
    }
}
