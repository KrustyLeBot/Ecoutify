# Ecoutify

Petit clone de Spotify pour Android : recherche et écoute de musique YouTube **sans pub, sans compte**,
téléchargement pour l'écoute hors ligne, playlists, et recommandations.

## Fonctionnement

- **Recherche & lecture en direct** : [NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor) interroge YouTube (filtre « Titres » de YouTube Music) et fournit le flux audio, lu par Media3/ExoPlayer, y compris en arrière-plan et écran verrouillé. Aucune clé d'API.
- **Hors ligne** : le flux audio d'origine (M4A/AAC ou Opus) est téléchargé tel quel, sans conversion. Sans connexion, seuls les titres téléchargés sont lus.
- **Playlists** : locales, avec des titres téléchargés ou non (« Tout télécharger » pour une playlist entière).
- **Lecteur** : lecture/pause, précédent/suivant, barre de progression, aléatoire, répétition (off / tout / un titre). La file est la liste d'où l'on lance un titre ; en aléatoire le titre suivant est tiré au hasard.
- **Recommandations** (onglet « Pour toi ») : elles partent uniquement de tes titres téléchargés et de tes playlists (ni recherches ni historique). [ListenBrainz](https://listenbrainz.org) (sans compte) et, si tu mets une clé d'API gratuite dans ⚙, [Last.fm](https://www.last.fm) donnent des artistes proches ; leurs titres sont cherchés sur YouTube. Les dernières suggestions restent visibles hors ligne.
- **Réglages facultatifs** (⚙) : clé d'API Last.fm (2e source de suggestions) et jeton ListenBrainz (enregistre tes écoutes sur ton profil, sans effet sur les suggestions).

## Obtenir l'APK

La CI GitHub (`.github/workflows/build-apk.yml`) compile l'APK à chaque push :
onglet **Actions** → dernier run réussi → artefact `ecoutify-debug-apk` → `app-debug.apk`, à installer sur le téléphone.

En local : `./gradlew assembleDebug` (JDK 17 + Android SDK 35).

## Avertissement

Contourner la publicité et télécharger depuis YouTube va à l'encontre de ses conditions d'utilisation, et les contenus restent protégés par le droit d'auteur. Usage personnel uniquement.
