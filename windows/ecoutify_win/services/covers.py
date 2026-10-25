"""Cover images: shown from Ecoutify/covers/ (shared with the phone) or from a local cache, fetched when missing."""
from __future__ import annotations

import shutil
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import requests
from PySide6.QtCore import QObject, Qt, Signal
from PySide6.QtGui import QPixmap

from ..core.models import Track, video_id

_UA = "Mozilla/5.0 (Windows NT 10.0; rv:128.0) Gecko/20100101 Firefox/128.0"


def fetch_cover(track: Track) -> bytes | None:
    """Tries the track's own image, then YouTube's video thumbnails."""
    vid = video_id(track.url)
    if not vid:
        return None
    for url in filter(None, [track.thumb, f"https://i.ytimg.com/vi/{vid}/maxresdefault.jpg", f"https://i.ytimg.com/vi/{vid}/mqdefault.jpg"]):
        try:
            response = requests.get(url, headers={"User-Agent": _UA}, timeout=15)
            if response.ok and len(response.content) >= 1024:
                return response.content
        except requests.RequestException:
            continue
    return None


class Covers(QObject):
    changed = Signal()

    def __init__(self, ctx, cache_dir: Path):
        super().__init__()
        self.ctx = ctx
        self.cache_dir = Path(cache_dir)
        self._pixmaps: dict[tuple[str, int], QPixmap] = {}
        self._inflight: set[str] = set()
        self._failed: set[str] = set()
        self._pool = ThreadPoolExecutor(max_workers=2)

    def _cache_file(self, track: Track) -> Path:
        return self.cache_dir / f"{track.id}.jpg"

    def path(self, track: Track) -> Path | None:
        shared = self.ctx.store.cover_file(track.url)
        for candidate in (shared, self._cache_file(track)):
            if candidate and candidate.exists():
                return candidate
        return None

    def pixmap(self, track: Track, size: int) -> QPixmap | None:
        """A square pixmap, or None while the cover is still being fetched (the 'changed' signal tells when)."""
        path = self.path(track)
        if path is None:
            self._fetch_in_background(track)
            return None
        key = (str(path), size)
        if key not in self._pixmaps:
            source = QPixmap(str(path))
            if source.isNull():
                return None
            scaled = source.scaled(size, size, Qt.AspectRatioMode.KeepAspectRatioByExpanding, Qt.TransformationMode.SmoothTransformation)
            self._pixmaps[key] = scaled.copy((scaled.width() - size) // 2, (scaled.height() - size) // 2, size, size)
        return self._pixmaps[key]

    def _fetch_in_background(self, track: Track) -> None:
        if track.id in self._inflight or track.id in self._failed:
            return
        self._inflight.add(track.id)
        self._pool.submit(self._fetch, track, self._cache_file(track))

    def _fetch(self, track: Track, target: Path) -> None:
        data = fetch_cover(track)
        if data:
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(data)
        else:
            self._failed.add(track.id)
        self._inflight.discard(track.id)
        if data:
            self.changed.emit()

    def save_shared(self, tracks) -> None:
        """Makes sure these tracks have their cover in Ecoutify/covers/ (so the phone has it too). Background."""
        self._pool.submit(self._save_shared, list(tracks))

    def _save_shared(self, tracks) -> None:
        store = self.ctx.store
        for track in tracks:
            target = store.cover_file(track.url)
            if target is None or target.exists():
                continue
            cached = self._cache_file(track)
            target.parent.mkdir(parents=True, exist_ok=True)
            if cached.exists():
                shutil.copyfile(cached, target)
            else:
                data = fetch_cover(track)
                if data:
                    target.write_bytes(data)
        self.changed.emit()
