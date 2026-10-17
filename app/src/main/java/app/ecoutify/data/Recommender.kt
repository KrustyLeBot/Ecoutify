package app.ecoutify.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * Recommandations sans compte : on prend ce que tu as écouté récemment et on récupère
 * les titres « similaires » de YouTube pour chacun, puis on mélange et on retire les doublons.
 * Sans historique : suggestions génériques par recherche.
 */
class Recommender(private val catalog: Catalog, private val store: Store) {

    suspend fun forYou(): List<Track> = withContext(Dispatchers.IO) {
        val history = store.history()
        if (history.isEmpty()) {
            return@withContext runCatching { catalog.search("top hits musique") }.getOrDefault(emptyList())
        }
        val seeds = history.distinctBy { it.artist }.take(4)
        val lists = coroutineScope {
            seeds.map { seed -> async { runCatching { catalog.related(seed.url) }.getOrDefault(emptyList()) } }.awaitAll()
        }
        val seen = history.map { it.url }.toMutableSet()
        val out = mutableListOf<Track>()
        // entrelace les listes pour varier les artistes
        for (i in 0 until (lists.maxOfOrNull { it.size } ?: 0)) {
            for (l in lists) l.getOrNull(i)?.let { if (seen.add(it.url)) out += it }
        }
        out.take(40)
    }
}
