package app.ecoutify.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * Recommandations : on part uniquement de tes titres téléchargés et de tes playlists
 * (ni recherches ni historique d'écoute) et on reprend les « titres similaires » proposés par YouTube.
 * Chaque actualisation tire d'autres titres de départ, donc d'autres suggestions.
 */
class Recommender(private val catalog: Catalog, private val store: Store) {

    suspend fun forYou(): List<Track> = withContext(Dispatchers.IO) {
        val known = (store.downloads() + store.playlists().flatMap { it.tracks }).distinctBy { it.url }
        val seen = (known + store.history()).map { it.url }.toMutableSet()

        val seeds = known.shuffled().distinctBy { it.artist.lowercase() }.take(5)
        if (seeds.isEmpty()) {
            return@withContext runCatching { catalog.search("top hits musique") }.getOrDefault(emptyList())
        }
        val lists = coroutineScope {
            seeds.map { s -> async { runCatching { catalog.related(s.url) }.getOrDefault(emptyList()) } }.awaitAll()
        }
        // entrelace les listes pour varier les artistes
        val out = mutableListOf<Track>()
        for (i in 0 until (lists.maxOfOrNull { it.size } ?: 0)) {
            for (l in lists) l.getOrNull(i)?.let { if (seen.add(it.url)) out += it }
        }
        out.take(40)
    }
}
