package com.ftpflix.core.config

import com.ftpflix.data.repository.MetadataRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentLinkedQueue
import javax.inject.Inject
import javax.inject.Singleton

/** Background enrichment of poster age-rating tags: browse lists only carry TMDB ids, so ratings are fetched here before the user opens a title. */
@Singleton
class RatingWarmup @Inject constructor(
    private val metadata: MetadataRepository,
    private val ageRatings: AgeRatingStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queue = ConcurrentLinkedQueue<String>()
    private val inFlight = mutableSetOf<String>()
    private var job: Job? = null

    /** Queue ids ("movie:123"/"tv:123" or "tmdb:movie:123") to enrich in the background. */
    fun request(ids: Collection<String>) {
        if (ids.isEmpty()) return
        synchronized(this) {
            ids.asSequence()
                .mapNotNull(::normalize)
                .filter { it !in inFlight && ageRatings.get(it) == null }
                .distinct()
                .forEach(queue::add)
            if (job?.isActive != true) job = scope.launch { drain() }
        }
    }

    private suspend fun drain() {
        val batch: List<String>
        synchronized(this) {
            batch = buildList {
                while (size < MAX_PER_BATCH) {
                    val id = queue.poll() ?: break
                    if (id !in inFlight) {
                        add(id)
                        inFlight += id
                    }
                }
            }
        }
        if (batch.isEmpty()) return
        try {
            batch.chunked(CONCURRENCY).forEach { chunk ->
                coroutineScope {
                    chunk.map { id ->
                        async {
                            try {
                                metadata.ageRatingFor(id)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: Exception) {
                                // offline / unknown title: negative cache handles retries
                            }
                        }
                    }.awaitAll()
                }
            }
        } finally {
            synchronized(this) {
                inFlight.removeAll(batch.toSet())
                if (queue.isNotEmpty()) job = scope.launch { drain() }
            }
        }
    }

    private fun normalize(raw: String): String? {
        val id = if (raw.startsWith("tmdb:")) raw.removePrefix("tmdb:") else raw
        return id.takeIf { it.startsWith("movie:") || it.startsWith("tv:") }
    }

    private companion object {
        const val MAX_PER_BATCH = 48
        const val CONCURRENCY = 6
    }
}
