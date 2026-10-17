# Ecoutify

A tiny Spotify-style music player for Android: search and stream YouTube / YouTube Music
**with no ads and no account**, download tracks for offline listening, build playlists, and get recommendations.
The app UI is in French.

## How it works

- **Search & live playback**: [NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor) queries YouTube (using the YouTube Music "Songs" filter) and returns the audio stream, which is played with Media3 / ExoPlayer, including in the background and on the lock screen. No API key is needed and no ads are ever loaded.
- **Offline**: the original audio stream (M4A/AAC or Opus) is downloaded as is, with no conversion, into an `Ecoutify/` folder at the root of the phone storage (not in "Music"). It survives uninstalling: after a reinstall, music and playlists (`Ecoutify/music/`, `Ecoutify/playlists.json`) are detected again. The app asks for "all files access" for this. Without a connection only downloaded tracks can be played.
- **Playlists**: local, and can contain downloaded or not-yet-downloaded tracks ("Download all" for a whole playlist).
- **Player**: play/pause, previous/next, seek bar, shuffle, repeat (off / all / one). The queue is simply the list a track was started from; with shuffle on, the next track is picked at random.
- **Recommendations** ("Pour toi" tab): based only on your downloaded tracks and playlists (not on searches or listening history), reusing the "similar tracks" YouTube suggests. The last suggestions stay visible offline.

## Getting the APK

GitHub Actions (`.github/workflows/build-apk.yml`) builds the APK on every push:
**Actions** tab → latest successful run → `ecoutify-debug-apk` artifact → `app-debug.apk`, then install it on the phone.

Locally: `./gradlew assembleDebug` (JDK 17 + Android SDK 35).

## Disclaimer

Bypassing ads and downloading from YouTube goes against YouTube's terms of service, and the content remains protected by copyright. Personal use only.
