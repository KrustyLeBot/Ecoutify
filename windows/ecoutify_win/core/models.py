"""Tracks and playlists, with the same JSON shape as the Android app (playlists.json)."""
from __future__ import annotations

import re
from dataclasses import dataclass, field, replace
from typing import Any

_VIDEO_ID = re.compile(r"[?&]v=([A-Za-z0-9_-]{11})")


def video_id(url: str) -> str | None:
    match = _VIDEO_ID.search(url)
    return match.group(1) if match else None


def watch_url(vid: str) -> str:
    return f"https://www.youtube.com/watch?v={vid}"


def normalize_url(url: str) -> str:
    """Canonical watch URL, so the same video always has the same URL (YouTube Music links use another host)."""
    vid = video_id(url)
    return watch_url(vid) if vid else url


@dataclass(frozen=True)
class Track:
    url: str
    title: str
    artist: str = ""
    duration: int = 0
    thumb: str | None = None

    @property
    def id(self) -> str:
        return video_id(self.url) or self.url

    def to_json(self) -> dict[str, Any]:
        return {"url": self.url, "title": self.title, "artist": self.artist, "duration": self.duration, "thumb": self.thumb}

    @staticmethod
    def from_json(o: dict[str, Any]) -> "Track":
        return Track(
            url=normalize_url(o["url"]),
            title=o["title"],
            artist=o.get("artist") or "",
            duration=int(o.get("duration") or 0),
            thumb=o.get("thumb") or None,
        )


@dataclass(frozen=True)
class Playlist:
    id: str
    name: str
    tracks: tuple[Track, ...] = field(default_factory=tuple)
    keep_offline: bool = False

    def with_tracks(self, tracks) -> "Playlist":
        return replace(self, tracks=tuple(tracks))

    def to_json(self) -> dict[str, Any]:
        return {"id": self.id, "name": self.name, "keepOffline": self.keep_offline, "tracks": [t.to_json() for t in self.tracks]}

    @staticmethod
    def from_json(o: dict[str, Any]) -> "Playlist":
        return Playlist(
            id=o["id"],
            name=o["name"],
            tracks=tuple(Track.from_json(t) for t in o.get("tracks", [])),
            keep_offline=bool(o.get("keepOffline", False)),
        )


def format_duration(seconds: int) -> str:
    if seconds <= 0:
        return ""
    return f"{seconds // 60}:{seconds % 60:02d}"
