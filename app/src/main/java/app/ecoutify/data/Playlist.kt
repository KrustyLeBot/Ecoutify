package app.ecoutify.data

/** [keepOffline]: every track of the playlist is downloaded automatically (on an unmetered network). */
data class Playlist(val id: String, val name: String, val tracks: List<Track>, val keepOffline: Boolean = false)
