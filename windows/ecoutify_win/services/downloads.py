"""Background downloads into Ecoutify/music/, one at a time, with retries."""
from __future__ import annotations

import os
import shutil
import time
from concurrent.futures import ThreadPoolExecutor

from PySide6.QtCore import QObject, Signal

from ..core import atomic
from ..core.models import Track


class DownloadManager(QObject):
    progress = Signal(str, int)  # track url, percent
    finished = Signal(str)       # track url
    failed = Signal(str, str)    # track url, message
    queue_changed = Signal()

    ATTEMPTS = 3

    def __init__(self, ctx):
        super().__init__()
        self.ctx = ctx
        self._pending: dict[str, int] = {}  # url -> percent (queued = 0)
        self._executor = ThreadPoolExecutor(max_workers=1)

    def is_pending(self, url: str) -> bool:
        return url in self._pending

    def percent(self, url: str) -> int:
        return self._pending.get(url, 0)

    def enqueue(self, track: Track) -> None:
        if self.ctx.store.is_downloaded(track.url) or track.url in self._pending:
            return
        self._pending[track.url] = 0
        self.queue_changed.emit()
        self._executor.submit(self._run, track)

    def sync_keep_offline(self) -> None:
        """Queues every missing track of the playlists set to 'keep offline'."""
        for playlist in self.ctx.store.playlists():
            if playlist.keep_offline:
                for track in playlist.tracks:
                    self.enqueue(track)

    def _run(self, track: Track) -> None:
        error: Exception | None = None
        for attempt in range(self.ATTEMPTS):
            try:
                self._download(track)
                error = None
                break
            except Exception as exc:
                error = exc
                time.sleep(5 * (attempt + 1))
        self._pending.pop(track.url, None)
        self.queue_changed.emit()
        if error is None:
            self.finished.emit(track.url)
        else:
            self.failed.emit(track.url, str(error))

    def _download(self, track: Track) -> None:
        store = self.ctx.store
        if store.is_downloaded(track.url):
            return
        self.ctx.covers.save_shared([track])

        def on_progress(pct: int) -> None:
            self._pending[track.url] = pct
            self.progress.emit(track.url, pct)

        tmp = self.ctx.catalog.download_audio(track.url, self.ctx.cache_dir / "downloads", on_progress)
        final = store.file_for(track, tmp.suffix.lstrip(".") or "m4a")
        final.parent.mkdir(parents=True, exist_ok=True)
        # Copied next to the final file under a name Synchrotron never syncs, then renamed: atomic, even on SMB.
        part = final.with_name(atomic.temp_name(final.name))
        try:
            shutil.move(str(tmp), part)
            os.replace(part, final)
        except BaseException:
            part.unlink(missing_ok=True)
            raise
        store.invalidate()
