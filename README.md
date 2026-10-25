# Ecoutify

A tiny Spotify-style music player for Android: search and stream YouTube / YouTube Music
**with no ads and no account**, download tracks for offline listening, build playlists, and get recommendations.
The app UI is in French.

## How it works

- **Search & live playback**: [NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor) queries YouTube (using the YouTube Music "Songs" filter) and returns the audio stream, which is played with Media3 / ExoPlayer, including in the background and on the lock screen. No API key is needed and no ads are ever loaded.
- **Offline**: the original audio stream (M4A/AAC or Opus) is downloaded as is, with no conversion, into an `Ecoutify/` folder at the root of the phone storage (not in "Music"). It survives uninstalling: after a reinstall, music and playlists (`Ecoutify/music/`, `Ecoutify/playlists.json`) are detected again. The app asks for "all files access" for this. Without a connection only downloaded tracks can be played.
- **Covers**: shown in lists and in the player. They are saved in `Ecoutify/covers/` when a track is added to a playlist or downloaded, so they stay available offline and come back after a reinstall (missing ones are fetched in the background when online).
- **Background downloads**: downloads run in WorkManager with a progress notification: they continue when the app is closed and are retried when the connection drops. A playlist can be set to **keep offline**: all its tracks (and the ones added later) are downloaded automatically on an unmetered network (Wi-Fi), now and every few hours.
- **Import**: the link button in the Library imports a YouTube / YouTube Music playlist link as a playlist (also offered when a playlist link is shared to the app).
- **Resume**: the last queue is remembered; a "play" from a car, Bluetooth device or headset restarts it (from a random track) even if the app was closed.
- **Playlists**: local, and can contain downloaded or not-yet-downloaded tracks ("Download all" for a whole playlist).
- **Quick start**: a 1x1 home-screen widget tied to a playlist (picked when the widget is placed) starts it in shuffle mode with one tap, without opening the app. Long-pressing the app icon offers the same for the first four playlists. Playlists can be renamed from their page (pencil icon); widgets and shortcuts follow.
- **Player**: play/pause, previous/next, seek bar. Playback is always shuffled and the queue always loops (there is no shuffle or repeat setting): the queue is the list a track was started from, and the next track is picked at random.
- **Recommendations** ("Pour toi" tab): based only on your downloaded tracks and playlists (not on searches or listening history). For some of those tracks the app takes the YouTube Music "radio" (a mix made of songs), keeps only music (song-length tracks, no talks, mixes or long videos) and puts official audio first. The last suggestions stay visible offline.

## Windows client

`windows/` holds a Windows version written in Python + PySide6 with the same features: search, playlists, downloads, covers, recommendations, playlist import, "keep offline", always-shuffled looping playback and self-update.
It works on the **same `Ecoutify/` folder** as the phone (`music/`, `covers/`, `playlists.json`, same file formats): sync that folder between the two with any file-sync tool (e.g. Proton Drive) and both apps see the same library. The folder is chosen at first launch (menu *Ecoutify → Dossier de travail…*) and remembered between runs; changes synced into it are picked up automatically.

- Audio: `yt-dlp` (streams and downloads), `ytmusicapi` (search, music radio, playlist import), Qt Multimedia (playback).
- Run from source: `cd windows && pip install -r requirements.txt && python run.py` (Python 3.12). Tests: `python -m pytest tests`.
- The CI builds `Ecoutify.exe` with PyInstaller (one file, no console). It is unsigned, so Windows SmartScreen may warn on first launch.

## Getting the apps

GitHub Actions (`.github/workflows/build-apk.yml`) builds the APK and the Windows exe on every push to `main`. When both succeed, they are published as plain `ecoutify.apk` and `Ecoutify.exe` in the **`latest` release** (https://github.com/KrustyLeBot/Ecoutify/releases/tag/latest), replacing the previous one. Download them from there (the run's artifacts also keep a copy for 7 days). The APK installs on the phone; `Ecoutify.exe` runs on Windows and updates itself from the same release (menu *Ecoutify → Rechercher une mise à jour*, plus a quiet check at startup).

**Updates from the app**: Library tab → "Mise à jour" → "Vérifier" (the app also checks quietly at startup and shows a dot on the Library tab when a newer build exists). It downloads the APK into the app cache and opens Android's installer; the first time, Android asks to allow Ecoutify to install apps. The downloaded file is deleted at the next app start once the installed build has caught up with it.

Every build is signed with the same key (`app/ecoutify.jks`, committed on purpose for this personal app) and its version code is the CI run number, so a newer APK installs as an update over the previous one.

Locally: `./gradlew assembleRelease` (JDK 17 + Android SDK 35).

## Disclaimer

Bypassing ads and downloading from YouTube goes against YouTube's terms of service, and the content remains protected by copyright. Personal use only.
