"""Playback. Like the Android app, it is always shuffled and the queue always loops."""
from __future__ import annotations

import random
import shutil

from PySide6.QtCore import QObject, QUrl, Signal
from PySide6.QtMultimedia import QAudioOutput, QMediaPlayer

from ..core.models import Track
from .async_util import run_async


class PlayerEngine(QObject):
    track_changed = Signal(object)  # Track | None
    playing_changed = Signal(bool)
    position_changed = Signal(int)
    duration_changed = Signal(int)
    message = Signal(str)

    MAX_FAILURES = 5

    def __init__(self, ctx):
        super().__init__()
        self.ctx = ctx
        self.audio = QAudioOutput()
        self.audio.setVolume(0.7)
        self.media = QMediaPlayer()
        self.media.setAudioOutput(self.audio)
        self.media.playbackStateChanged.connect(lambda s: self.playing_changed.emit(s == QMediaPlayer.PlaybackState.PlayingState))
        self.media.positionChanged.connect(lambda ms: self.position_changed.emit(int(ms)))
        self.media.durationChanged.connect(lambda ms: self.duration_changed.emit(int(ms)))
        self.media.mediaStatusChanged.connect(self._on_status)
        self.media.errorOccurred.connect(self._on_error)

        self.queue: list[Track] = []
        self.current: Track | None = None
        self._order: list[int] = []
        self._pos = 0
        self._history: list[int] = []
        self._generation = 0
        self._failures = 0
        self._fallback_tried: set[str] = set()

    # --- queue ---
    def play_queue(self, tracks, start: int = 0) -> None:
        """Plays 'tracks' starting at 'start', then continues in random order, looping forever."""
        tracks = list(tracks)
        if not tracks:
            return
        tapped = tracks[start]
        if not self.ctx.online():
            tracks = [t for t in tracks if self.ctx.store.is_downloaded(t.url)]
            if tapped not in tracks:
                self.message.emit("Hors ligne : ce titre n'est pas téléchargé")
                return
        self.queue = tracks
        self._history.clear()
        self._failures = 0
        self._order = self._shuffled(first=tracks.index(tapped))
        self._pos = 0
        self._play_index(self._order[0])

    def play_shuffled(self, tracks) -> None:
        tracks = list(tracks)
        if tracks:
            self.play_queue(tracks, random.randrange(len(tracks)))

    def _shuffled(self, first: int | None = None, avoid: int | None = None) -> list[int]:
        order = list(range(len(self.queue)))
        random.shuffle(order)
        if first is not None:
            order.remove(first)
            order.insert(0, first)
        elif avoid is not None and len(order) > 1 and order[0] == avoid:
            order[0], order[1] = order[1], order[0]
        return order

    def next(self) -> None:
        if not self.queue:
            return
        self._history.append(self._order[self._pos])
        self._pos += 1
        if self._pos >= len(self._order):  # end of the lap: reshuffle and loop
            self._order = self._shuffled(avoid=self._order[-1])
            self._pos = 0
        self._play_index(self._order[self._pos])

    def previous(self) -> None:
        if self.media.position() > 3000 or not self._history:
            self.media.setPosition(0)
            return
        index = self._history.pop()
        self._pos = max(0, self._pos - 1)
        self._order[self._pos] = index
        self._play_index(index)

    # --- controls ---
    def toggle(self) -> None:
        if self.media.playbackState() == QMediaPlayer.PlaybackState.PlayingState:
            self.media.pause()
        elif self.current is not None:
            self.media.play()

    def seek(self, ms: int) -> None:
        self.media.setPosition(ms)

    def set_volume(self, percent: int) -> None:
        self.audio.setVolume(percent / 100)

    def stop(self) -> None:
        self._generation += 1
        self.media.stop()

    # --- loading a track ---
    def _play_index(self, index: int) -> None:
        track = self.queue[index]
        self.current = track
        self.track_changed.emit(track)
        self._generation += 1
        generation = self._generation
        path = self.ctx.store.downloaded_file(track.url)
        if path:
            self._start(QUrl.fromLocalFile(str(path)))
            return
        self.media.stop()
        run_async(
            lambda: self.ctx.catalog.audio_source(track.url),
            on_done=lambda source: self._start(QUrl(source.url)) if generation == self._generation else None,
            on_error=lambda exc: self._failed(generation, str(exc)),
        )

    def _start(self, url: QUrl) -> None:
        self.media.setSource(url)
        self.media.play()

    def _failed(self, generation: int, reason: str) -> None:
        if generation != self._generation:
            return
        self._failures += 1
        if self._failures >= self.MAX_FAILURES:
            self.message.emit(f"Lecture impossible : {reason}")
            return
        self.message.emit(f"Titre illisible, passage au suivant ({reason})")
        self.next()

    def _on_status(self, status) -> None:
        if status == QMediaPlayer.MediaStatus.EndOfMedia:
            self.next()
        elif status in (QMediaPlayer.MediaStatus.LoadedMedia, QMediaPlayer.MediaStatus.BufferedMedia):
            self._failures = 0

    def _on_error(self, error, text: str) -> None:
        track = self.current
        if track is None:
            return
        generation = self._generation
        if self.ctx.store.is_downloaded(track.url) or track.url in self._fallback_tried:
            self._failed(generation, text)
            return
        # The stream could not be played directly: download it to the cache and play that file instead.
        self._fallback_tried.add(track.url)
        cache = self.ctx.cache_dir / "stream"

        def fetch():
            return self.ctx.catalog.download_audio(track.url, cache)

        run_async(
            fetch,
            on_done=lambda path: self._start(QUrl.fromLocalFile(str(path))) if generation == self._generation else None,
            on_error=lambda exc: self._failed(generation, str(exc)),
        )

    def clear_cache(self) -> None:
        shutil.rmtree(self.ctx.cache_dir / "stream", ignore_errors=True)
