package app.ecoutify.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * Recommandations : on part uniquement de tes titres téléchargés et de tes playlists
 * (ni recherches ni historique d'écoute), on demande à ListenBrainz
 * quels artistes y ressemblent, puis on cherche leurs titres sur YouTube.
 * Chaque actualisation tire d'autres artistes de départ, donc d'autres suggestions.
 */
class Recommender(
    private val catalog: Catalog,
    private val store: Store,
    private val discovery: Discovery,
) {
    private val gate = Semaphore(4)

    suspend fun forYou(): List<Track> = withContext(Dispatchers.IO) {
        val known = (store.downloads() + store.playlists().flatMap { it.tracks }).distinctBy { it.url }
        val seen = (known + store.history()).map { it.url }.toMutableSet()

        val trackSeeds = known.shuffled().distinctBy { it.artist.lowercase() }.take(5)
        val seeds = trackSeeds.map { Discovery.Seed(it.artist, it.title) }
        if (seeds.isEmpty()) {
            return@withContext runCatching { catalog.search("top hits musique") }.getOrDefault(emptyList())
        }

        // 1) artistes similaires
        val similar = coroutineScope {
            seeds.map { s -> async { gate.withPermit { runCatching { discovery.similarArtists(s) }.getOrDefault(emptyList()) } } }.awaitAll()
        }
        val seedNames = seeds.map { it.name.lowercase() }.toSet()
        val artists = interleave(similar).filter { it.lowercase() !in seedNames }.distinct().take(14)

        // 2) leurs titres sur YouTube
        val found = coroutineScope {
            artists.map { a -> async { gate.withPermit { runCatching { topSongs(a) }.getOrDefault(emptyList()) } } }.awaitAll()
        }
        val out = mutableListOf<Track>()
        interleave(found).forEach { if (seen.add(it.url)) out += it }

        // 3) complément : « titres similaires » de YouTube si les services ci-dessus n'ont rien donné
        if (out.size < 12) {
            val related = coroutineScope {
                trackSeeds.take(3).map { t -> async { runCatching { catalog.related(t.url) }.getOrDefault(emptyList()) } }.awaitAll()
            }
            interleave(related).forEach { if (seen.add(it.url)) out += it }
        }
        out.take(40)
    }

    private fun topSongs(artist: String): List<Track> =
        catalog.search(artist).filter { it.artist.contains(artist, ignoreCase = true) }.take(2)

    private fun <T> interleave(lists: List<List<T>>): List<T> {
        val out = mutableListOf<T>()
        for (i in 0 until (lists.maxOfOrNull { it.size } ?: 0)) for (l in lists) l.getOrNull(i)?.let(out::add)
        return out
    }
}
