package com.ftpflix.core.config

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.recentSearchDataStore by preferencesDataStore(name = "recent_searches")

/** The last few search terms, newest first. */
@Singleton
class RecentSearchStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val keyRecent = stringPreferencesKey("recent")

    val recent: Flow<List<String>> = context.recentSearchDataStore.data.map { prefs ->
        prefs[keyRecent]
            ?.split('\n')
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?.distinctBy { it.lowercase() }
            ?.take(MAX_ENTRIES)
            .orEmpty()
    }

    /** Records [term] as the newest search, keeping the list short. */
    suspend fun record(term: String) {
        val value = term.trim()
        if (value.length < MIN_LENGTH) return
        context.recentSearchDataStore.edit { prefs ->
            val current = prefs[keyRecent]
                ?.split('\n')
                ?.map { it.trim() }
                ?.filter { it.isNotBlank() }
                .orEmpty()
            val next = (listOf(value) + current.filterNot { it.equals(value, ignoreCase = true) })
                .take(MAX_ENTRIES)
            prefs[keyRecent] = next.joinToString("\n")
        }
    }

    /** Drops every stored search term. */
    suspend fun clear() {
        context.recentSearchDataStore.edit { prefs -> prefs.remove(keyRecent) }
    }

    private companion object {
        const val MAX_ENTRIES = 8
        const val MIN_LENGTH = 2
    }
}
