package app.ecoutify.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * Recommendations: start only from downloaded tracks and playlists (no searches, no listening
 * history) and reuse the YouTube Music "radio" of each seed track, keeping music only.
 * Each refresh draws different seed tracks, hence different suggestions.
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
            seeds.map { s -> async { runCatching { catalog.similar(s.url) }.getOrDefault(emptyList()) } }.awaitAll()
        }
        // interleave the lists to vary artists
        val out = mutableListOf<Track>()
        val songs = HashSet<String>() // same song uploaded by several channels
        for (i in 0 until (lists.maxOfOrNull { it.size } ?: 0)) {
            for (l in lists) l.getOrNull(i)?.let {
                val key = (it.artist + it.title).lowercase().filter(Char::isLetterOrDigit)
                if (seen.add(it.url) && songs.add(key)) out += it
            }
        }
        out.take(40)
    }
}
