package app.ecoutify.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * Recommandations sans compte : on part de tes titres téléchargés et de tes playlists
 * (à défaut, de l'historique), on tire quelques artistes au hasard et on récupère les titres
 * « similaires » de YouTube pour chacun. Chaque actualisation donne donc une sélection différente.
 */
class Recommender(private val catalog: Catalog, private val store: Store) {

    suspend fun forYou(): List<Track> = withContext(Dispatchers.IO) {
        val known = (store.downloads() + store.playlists().flatMap { it.tracks }).distinctBy { it.url }
        val pool = known.ifEmpty { store.history() }
        if (pool.isEmpty()) {
            return@withContext runCatching { catalog.search("top hits musique") }.getOrDefault(emptyList())
        }
        val seeds = pool.shuffled().distinctBy { it.artist }.take(5)
        val lists = coroutineScope {
            seeds.map { seed -> async { runCatching { catalog.related(seed.url) }.getOrDefault(emptyList()) } }.awaitAll()
        }
        val seen = (pool + store.history()).map { it.url }.toMutableSet()
        val out = mutableListOf<Track>()
        // entrelace les listes pour varier les artistes
        for (i in 0 until (lists.maxOfOrNull { it.size } ?: 0)) {
            for (l in lists) l.getOrNull(i)?.let { if (seen.add(it.url)) out += it }
        }
        out.take(40)
    }
}
