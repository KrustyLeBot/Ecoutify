# Ecoutify

Application Android de musique : recherche et écoute de morceaux YouTube / YouTube Music **sans pub, sans compte**,
téléchargement hors ligne, et recommandations basées sur ton historique d'écoute.

## Comment ça marche

| Besoin | Solution retenue |
|---|---|
| Récupérer l'audio de YouTube | [NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor) (lib Java/Kotlin utilisée par NewPipe) : recherche, flux audio, titres similaires. Aucune clé d'API, aucune pub. |
| Lecture | Media3 / ExoPlayer, en service d'arrière-plan avec notification et contrôles écran verrouillé |
| Hors ligne | Le flux audio d'origine (M4A/AAC ou Opus) est téléchargé tel quel dans `Android/data/app.ecoutify/files/music`. Pas de conversion MP3 : aucune perte de qualité, pas de ffmpeg. |
| Recommandations (sans compte) | Dernier historique d'écoute → titres « similaires » de YouTube pour chaque artiste récent, entrelacés et dédoublonnés. Bouton « radio » (📻) sur chaque titre + enchaînement automatique en fin de file. |

Pourquoi pas `yt-dlp` ? C'est un programme Python : l'embarquer dans un APK est lourd et fragile (Chaquopy, mises à jour constantes).
NewPipeExtractor fait la même chose nativement en JVM et se met à jour par simple changement de version.

## Obtenir l'APK

Pas besoin d'Android Studio : la CI GitHub (`.github/workflows/build-apk.yml`) compile l'APK à chaque push.
Onglet **Actions** → dernier run → artefact `ecoutify-debug-apk` → installe `app-debug.apk` sur le téléphone.

En local : `./gradlew assembleDebug` (JDK 17 + Android SDK 35).

## Pistes pour la suite

- **Comptes** : le compte YouTube Music n'a pas d'API officielle ; se connecter par cookies est possible mais fragile (et risqué pour le compte).
  Spotify a fermé ses endpoints de recommandation aux nouvelles applis fin 2024 ; l'import de playlists (export CSV) reste faisable.
  Alternatives sans compte : ListenBrainz / Last.fm (scrobbles + recommandations ouvertes), Bandcamp (pas d'API publique).
- Playlists et favoris locaux (Room), file d'attente éditable, écran lecteur plein écran.
- Téléchargement en tâche de fond (WorkManager) avec notification de progression.
- Conversion MP3 optionnelle (ffmpeg-kit) si un format précis est nécessaire.
- Pré-chargement du morceau suivant, normalisation du volume, minuteur de sommeil.

## Avertissement

Contourner la publicité et télécharger depuis YouTube va à l'encontre de ses conditions d'utilisation, et les contenus restent protégés par le droit d'auteur.
Ce projet est destiné à un usage personnel ; à toi de vérifier ce qui est autorisé dans ton pays. Pour soutenir les artistes, achète/stream légalement ce que tu aimes vraiment.
