"""YouTube / YouTube Music access: search, music-only recommendations, playlist import and audio download."""
from __future__ import annotations

import re
import threading
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Callable

from .models import Track, normalize_url, video_id, watch_url

MAX_IMPORT = 300

_NOT_MUSIC = re.compile(
    r"podcast|interview|reaction|r[ée]action|trailer|tutorial|how to|review|vlog|news|gameplay|karaoke|"
    r"full album|compilation|playlist|live ?stream|episode|[0-9]+ ?(hours?|heures?)|megamix|non-?stop|"
    r"best of|top [0-9]+|explained|documentary|documentaire|behind the scenes|making of|teaser|asmr",
    re.IGNORECASE,
)


def _seconds(value) -> int:
    """'3:21' / '1:02:03' / 201 -> seconds."""
    if isinstance(value, (int, float)):
        return int(value)
    if not value:
        return 0
    seconds = 0
    for part in str(value).split(":"):
        if not part.isdigit():
            return 0
        seconds = seconds * 60 + int(part)
    return seconds


def _best_thumb(thumbs) -> str | None:
    """The smallest thumbnail that is at least 300 px wide, else the largest one."""
    sized = [t for t in thumbs or [] if t.get("url")]
    if not sized:
        return None
    big = sorted((t for t in sized if t.get("width", 0) >= 300), key=lambda t: t["width"])
    return (big[0] if big else max(sized, key=lambda t: t.get("width", 0)))["url"]


def track_from_ytmusic(d: dict) -> Track | None:
    vid = d.get("videoId")
    if not vid or d.get("isAvailable") is False:
        return None
    artists = ", ".join(a["name"] for a in d.get("artists") or [] if a.get("name"))
    duration = d.get("duration_seconds") or _seconds(d.get("duration") or d.get("length"))
    return Track(
        url=watch_url(vid),
        title=_clean_title(d.get("title") or ""),
        artist=artists,
        duration=int(duration or 0),
        thumb=_best_thumb(d.get("thumbnails") or d.get("thumbnail")),
    )


def _clean_title(raw: str) -> str:
    noise = re.compile(r"\s*[\(\[][^\)\]]*(official|video|audio|lyrics?|clip|visuali[sz]er|4k|hd)[^\)\]]*[\)\]]", re.IGNORECASE)
    return noise.sub("", raw).strip() or raw


def looks_like_music(track: Track) -> bool:
    """Songs are 1.5 to 8 minutes long; long mixes and talk content are dropped."""
    return 90 <= track.duration <= 480 and not _NOT_MUSIC.search(track.title)


@dataclass
class AudioSource:
    url: str
    ext: str
    headers: dict


class _Silent:
    """yt-dlp logger that stays quiet (a windowed app has no console)."""

    def debug(self, msg): pass
    def info(self, msg): pass
    def warning(self, msg): pass
    def error(self, msg): pass


class Catalog:
    def __init__(self):
        self._yt = None
        self._lock = threading.Lock()
        self._sources: dict[str, tuple[float, AudioSource]] = {}

    @property
    def yt(self):
        with self._lock:
            if self._yt is None:
                from ytmusicapi import YTMusic
                self._yt = YTMusic()
            return self._yt

    # --- YouTube Music ---
    def search(self, query: str) -> list[Track]:
        results = self.yt.search(query, filter="songs", limit=25)
        return [t for t in (track_from_ytmusic(r) for r in results) if t]

    def similar(self, url: str) -> list[Track]:
        """The YouTube Music radio of a track (a mix of songs), music only, official audio first."""
        vid = video_id(url)
        if not vid:
            return []
        tracks = self.yt.get_watch_playlist(videoId=vid, radio=True, limit=30).get("tracks", [])
        scored: list[tuple[int, Track]] = []
        for d in tracks:
            track = track_from_ytmusic(d)
            if not track or track.id == vid or not looks_like_music(track):
                continue
            kind = d.get("videoType") or ""
            scored.append((3 if kind.endswith("ATV") else 2 if kind.endswith("OMV") else 0, track))
        scored.sort(key=lambda s: -s[0])  # stable: keeps YouTube's order within a score
        return [t for _, t in scored]

    def playlist(self, link: str) -> tuple[str, list[Track]]:
        """A YouTube / YouTube Music playlist link: its name and tracks (at most MAX_IMPORT)."""
        match = re.search(r"[?&]list=([A-Za-z0-9_-]+)", link)
        if not match:
            raise ValueError("Lien de playlist invalide")
        playlist_id = match[1].removeprefix("VL")
        data = self.yt.get_playlist(playlist_id, limit=MAX_IMPORT)
        tracks = [t for t in (track_from_ytmusic(d) for d in data.get("tracks", [])) if t and t.duration > 0]
        unique = list(dict.fromkeys(tracks))
        return data.get("title") or "Playlist", unique[:MAX_IMPORT]

    # --- audio (yt-dlp) ---
    def _options(self, **extra) -> dict:
        return {"quiet": True, "no_warnings": True, "noplaylist": True, "logger": _Silent(),
                "format": "bestaudio[ext=m4a]/bestaudio/best", "socket_timeout": 20, **extra}

    def audio_source(self, url: str) -> AudioSource:
        """Direct URL of the best audio stream, cached ~2 h (URLs expire)."""
        cached = self._sources.get(url)
        if cached and cached[0] > time.time():
            return cached[1]
        from yt_dlp import YoutubeDL
        with YoutubeDL(self._options(skip_download=True)) as ydl:
            info = ydl.extract_info(url, download=False)
        source = AudioSource(info["url"], info.get("ext") or "m4a", info.get("http_headers") or {})
        self._sources[url] = (time.time() + 2 * 3600, source)
        return source

    def download_audio(self, url: str, dest_dir: Path, on_progress: Callable[[int], None] | None = None) -> Path:
        """Downloads the audio as is (no conversion) into dest_dir and returns the file."""
        from yt_dlp import YoutubeDL

        def hook(d):
            if on_progress and d.get("status") == "downloading":
                total = d.get("total_bytes") or d.get("total_bytes_estimate") or 0
                if total:
                    on_progress(min(100, int(d.get("downloaded_bytes", 0) * 100 / total)))

        dest_dir.mkdir(parents=True, exist_ok=True)
        options = self._options(outtmpl=str(dest_dir / "%(id)s.%(ext)s"), progress_hooks=[hook])
        with YoutubeDL(options) as ydl:
            info = ydl.extract_info(url, download=True)
            return Path(ydl.prepare_filename(info))
