"""
The shared Ecoutify/ folder, laid out exactly like on Android:

- music/<artist> - <title> [<YouTube id>].m4a : one file per downloaded track (artist, title and id are read back
  from the file name, so a folder synced from the phone is understood as is);
- covers/<YouTube id>.jpg : cover images;
- playlists.json : the playlists.

Everything is read from disk (with a small cache), so changes made by the Android app and synced into the
folder show up as soon as the cache is invalidated.
"""
from __future__ import annotations

import json
import os
import re
import uuid
from pathlib import Path

from . import atomic
from .models import Playlist, Track, normalize_url, video_id, watch_url

FILE_RE = re.compile(r"^(?P<base>.*) \[(?P<id>[A-Za-z0-9_-]{11})\]\.(?P<ext>m4a|webm|opus|mp3|ogg|mp4|aac)$")
_FORBIDDEN = re.compile(r'[\\/:*?"<>|\n\r]')


def _clean(part: str) -> str:
    return _FORBIDDEN.sub("_", part).replace(" - ", " ").strip()


def track_from_file(path: Path) -> Track | None:
    match = FILE_RE.match(path.name)
    if not match:
        return None
    base = match["base"].strip()
    if " - " in base:
        artist, _, title = base.partition(" - ")
    else:
        artist, title = "", base
    return Track(url=watch_url(match["id"]), title=title, artist=artist)


class Store:
    def __init__(self, root: Path):
        self.root = Path(root)
        self._files: dict[str, Path] | None = None
        self._playlists: list[Playlist] | None = None
        self._playlists_mtime: float | None = None

    # --- folders ---
    @property
    def music_dir(self) -> Path:
        return self.root / "music"

    @property
    def covers_dir(self) -> Path:
        return self.root / "covers"

    @property
    def playlists_file(self) -> Path:
        return self.root / "playlists.json"

    def ensure_dirs(self) -> None:
        self.music_dir.mkdir(parents=True, exist_ok=True)
        self.covers_dir.mkdir(parents=True, exist_ok=True)

    def invalidate(self) -> None:
        """Forget what was read from disk (call when the folder changed)."""
        self._files = None
        self._playlists = None

    # --- downloads ---
    def _index(self) -> dict[str, Path]:
        if self._files is None:
            files: dict[str, tuple[float, Path]] = {}
            try:
                with os.scandir(self.music_dir) as entries:
                    for entry in entries:
                        match = FILE_RE.match(entry.name)
                        if match and entry.is_file():
                            files[match["id"]] = (entry.stat().st_mtime, Path(entry.path))
            except FileNotFoundError:
                pass
            self._files = {vid: p for vid, (_, p) in sorted(files.items(), key=lambda kv: -kv[1][0])}
        return self._files

    def downloads(self) -> list[Track]:
        return [t for t in (track_from_file(p) for p in self._index().values()) if t]

    def downloaded_file(self, url: str) -> Path | None:
        vid = video_id(url)
        return self._index().get(vid) if vid else None

    def is_downloaded(self, url: str) -> bool:
        return self.downloaded_file(url) is not None

    def file_for(self, track: Track, ext: str) -> Path:
        vid = video_id(track.url)
        if not vid:
            raise ValueError("Unknown URL")
        base = " - ".join(p for p in (_clean(track.artist), _clean(track.title)) if p)[:100].strip()
        return self.music_dir / f"{base} [{vid}].{ext}"

    def remove_download(self, track: Track) -> None:
        path = self.downloaded_file(track.url)
        if path:
            path.unlink(missing_ok=True)
        self.invalidate()

    # --- covers ---
    def cover_file(self, url: str) -> Path | None:
        vid = video_id(url)
        return self.covers_dir / f"{vid}.jpg" if vid else None

    # --- playlists ---
    def playlists(self) -> list[Playlist]:
        try:
            mtime = self.playlists_file.stat().st_mtime
        except FileNotFoundError:
            return []
        if self._playlists is None or mtime != self._playlists_mtime:
            try:
                data = atomic.read_json(self.playlists_file) or []
                self._playlists = [Playlist.from_json(o) for o in data]
            except (OSError, ValueError, KeyError, TypeError):
                self._playlists = []  # unreadable: shown empty, and _save_playlists refuses to overwrite it
            self._playlists_mtime = mtime
        return list(self._playlists)

    def _save_playlists(self, playlists: list[Playlist]) -> None:
        atomic.read_json(self.playlists_file)  # raises UnreadableFile rather than overwrite it
        atomic.write_text(self.playlists_file, json.dumps([p.to_json() for p in playlists], ensure_ascii=False))
        self._playlists = None

    def _update(self, playlist_id: str, fn) -> None:
        self._save_playlists([fn(p) if p.id == playlist_id else p for p in self.playlists()])

    def create_playlist(self, name: str, tracks=()) -> Playlist:
        playlist = Playlist(str(uuid.uuid4()), name.strip(), tuple(dict.fromkeys(tracks)))
        self._save_playlists(self.playlists() + [playlist])
        return playlist

    def rename_playlist(self, playlist_id: str, name: str) -> None:
        self._update(playlist_id, lambda p: Playlist(p.id, name.strip(), p.tracks, p.keep_offline))

    def delete_playlist(self, playlist_id: str) -> None:
        self._save_playlists([p for p in self.playlists() if p.id != playlist_id])

    def add_to_playlist(self, playlist_id: str, track: Track) -> None:
        def add(p: Playlist) -> Playlist:
            return p if any(t.url == track.url for t in p.tracks) else p.with_tracks((*p.tracks, track))
        self._update(playlist_id, add)

    def remove_from_playlist(self, playlist_id: str, track: Track) -> None:
        self._update(playlist_id, lambda p: p.with_tracks(t for t in p.tracks if t.url != track.url))

    def set_keep_offline(self, playlist_id: str, on: bool) -> None:
        self._update(playlist_id, lambda p: Playlist(p.id, p.name, p.tracks, on))

    def in_any_playlist(self, url: str) -> bool:
        return any(t.url == normalize_url(url) for p in self.playlists() for t in p.tracks)
