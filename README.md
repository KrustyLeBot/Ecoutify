# Ecoutify

A tiny Spotify-style music player for Android: search and stream YouTube / YouTube Music
**with no ads and no account**, download tracks for offline listening, build playlists, and get recommendations.
The app UI is in French.

## How it works

- **Search & live playback**: [NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor) queries YouTube (using the YouTube Music "Songs" filter) and returns the audio stream, which is played with Media3 / ExoPlayer, including in the background and on the lock screen. No API key is needed and no ads are ever loaded.
- **Offline**: the original audio stream (M4A/AAC or Opus) is downloaded as is, with no conversion, into an `Ecoutify/` folder at the root of the phone storage (not in "Music"). It survives uninstalling: after a reinstall, music and playlists (`Ecoutify/music/`, `Ecoutify/playlists.json`) are detected again. The app asks for "all files access" for this. Without a connection only downloaded tracks can be played.
- **Covers**: shown in lists and in the player. They are saved in `Ecoutify/covers/` when a track is added to a playlist or downloaded, so they stay available offline and come back after a reinstall (missing ones are fetched in the background when online).
- **Playlists**: local, and can contain downloaded or not-yet-downloaded tracks ("Download all" for a whole playlist).
- **Quick start**: a 1x1 home-screen widget tied to a playlist (picked when the widget is placed) starts it in shuffle mode with one tap, without opening the app. Long-pressing the app icon offers the same for the first four playlists. Playlists can be renamed from their page (pencil icon); widgets and shortcuts follow.
- **Player**: play/pause, previous/next, seek bar, shuffle on/off. The queue is simply the list a track was started from and always loops (there is no repeat setting); with shuffle on, the next track is picked at random.
- **Recommendations** ("Pour toi" tab): based only on your downloaded tracks and playlists (not on searches or listening history). For some of those tracks the app takes the YouTube Music "radio" (a mix made of songs), keeps only music (song-length tracks, no talks, mixes or long videos) and puts official audio first. The last suggestions stay visible offline.

## Getting the APK

GitHub Actions (`.github/workflows/build-apk.yml`) builds the APK on every push to `main`. When the build succeeds, the APK is published as a plain `ecoutify.apk` in the **`latest` release** (https://github.com/KrustyLeBot/Ecoutify/releases/tag/latest), replacing the previous one. Download it from there and install it on the phone (a zip copy also stays in the run's artifacts for 7 days as a fallback).

**Updates from the app**: Library tab → "Mise à jour" → "Vérifier" (the app also checks quietly at startup and shows a dot on the Library tab when a newer build exists). It downloads the APK into the app cache and opens Android's installer; the first time, Android asks to allow Ecoutify to install apps. The downloaded file is deleted at the next app start once the installed build has caught up with it.

Every build is signed with the same key (`app/ecoutify.jks`, committed on purpose for this personal app) and its version code is the CI run number, so a newer APK installs as an update over the previous one.

Locally: `./gradlew assembleDebug` (JDK 17 + Android SDK 35).

## Disclaimer

Bypassing ads and downloading from YouTube goes against YouTube's terms of service, and the content remains protected by copyright. Personal use only.
