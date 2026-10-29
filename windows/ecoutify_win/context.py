"""Everything the screens share: the folder store, the catalog, covers, downloads, the player and the playlist actions."""
from __future__ import annotations

from pathlib import Path

from PySide6.QtCore import QFileSystemWatcher, QObject, QStandardPaths, QTimer, Signal
from PySide6.QtNetwork import QNetworkInformation

from .config import Config
from .core import atomic
from .core.catalog import Catalog
from .core.localstate import LocalState
from .core.models import Playlist, Track
from .core.store import Store
from .services.async_util import run_async
from .services.covers import Covers
from .services.downloads import DownloadManager
from .services.player import PlayerEngine


class AppContext(QObject):
    library_changed = Signal()  # playlists / downloads / covers changed on disk or through the app
    message = Signal(str)

    def __init__(self, config: Config, catalog=None):
        super().__init__()
        self.config = config
        self.cache_dir = Path(QStandardPaths.writableLocation(QStandardPaths.StandardLocation.CacheLocation))
        data_dir = Path(QStandardPaths.writableLocation(QStandardPaths.StandardLocation.AppDataLocation))
        self.local_state = LocalState(data_dir / "state.json")
        self.catalog = catalog or Catalog()
        self.store = Store(config.workdir)
        self.store.ensure_dirs()
        self.covers = Covers(self, self.cache_dir / "covers")
        self.downloads = DownloadManager(self)
        self.player = PlayerEngine(self)
        QNetworkInformation.loadDefaultBackend()

        self._watcher = QFileSystemWatcher(self)
        self._debounce = QTimer(self)
        self._debounce.setSingleShot(True)
        self._debounce.setInterval(800)
        self._debounce.timeout.connect(self._disk_changed)
        self._watcher.directoryChanged.connect(lambda _: self._debounce.start())
        self._watcher.fileChanged.connect(lambda _: self._debounce.start())
        self.covers.changed.connect(self.library_changed)
        self.downloads.finished.connect(lambda _: self.library_changed.emit())
        self.downloads.failed.connect(lambda url, msg: self.message.emit(f"Téléchargement impossible : {msg}"))
        self._watch()

    # --- folder ---
    def set_workdir(self, path: Path) -> None:
        """Switches to another working folder (kept between runs)."""
        self.config.workdir = path
        self.store = Store(path)
        self.store.ensure_dirs()
        self.player.stop()
        self._watch()
        self.library_changed.emit()
        self.downloads.sync_keep_offline()

    def _watch(self) -> None:
        old = self._watcher.files() + self._watcher.directories()
        if old:
            self._watcher.removePaths(old)
        for path in (self.store.root, self.store.music_dir, self.store.covers_dir, self.store.playlists_file):
            if path.exists():
                self._watcher.addPath(str(path))

    def _disk_changed(self) -> None:
        """Something changed in the folder (e.g. the sync brought the phone's changes): reload everything."""
        self.store.invalidate()
        self._watch()  # an atomically replaced file loses its watch
        self.library_changed.emit()
        self.downloads.sync_keep_offline()

    def online(self) -> bool:
        info = QNetworkInformation.instance()
        return info is None or info.reachability() != QNetworkInformation.Reachability.Disconnected

    # --- playlist actions (the same rules as on Android) ---
    def _changed(self, tracks=()) -> None:
        self.store.invalidate()
        self._watch()
        self.library_changed.emit()
        if tracks:
            self.covers.save_shared(tracks)
        self.downloads.sync_keep_offline()

    def _edit(self, change) -> bool:
        """Runs a playlist change; an unreadable playlists.json is reported, never overwritten."""
        try:
            change()
            return True
        except atomic.UnreadableFile as e:
            self.message.emit(str(e))
            return False

    def create_playlist(self, name: str, tracks=()) -> Playlist | None:
        created = []
        if not self._edit(lambda: created.append(self.store.create_playlist(name, tracks))):
            return None
        self._changed(tracks)
        return created[0]

    def rename_playlist(self, playlist: Playlist, name: str) -> None:
        if self._edit(lambda: self.store.rename_playlist(playlist.id, name)):
            self._changed()

    def delete_playlist(self, playlist: Playlist) -> None:
        if not self._edit(lambda: self.store.delete_playlist(playlist.id)):
            return
        for track in playlist.tracks:
            self._free_if_orphan(track)
        self._changed()

    def add_to_playlist(self, playlist: Playlist, track: Track) -> None:
        if self._edit(lambda: self.store.add_to_playlist(playlist.id, track)):
            self._changed([track])

    def remove_from_playlist(self, playlist: Playlist, track: Track) -> None:
        if not self._edit(lambda: self.store.remove_from_playlist(playlist.id, track)):
            return
        self._free_if_orphan(track)
        self._changed()

    def set_keep_offline(self, playlist: Playlist, on: bool) -> None:
        if self._edit(lambda: self.store.set_keep_offline(playlist.id, on)):
            self._changed()

    def _free_if_orphan(self, track: Track) -> None:
        """Downloads only exist for tracks in a playlist: free the files once no playlist has the track."""
        if not self.store.in_any_playlist(track.url):
            self.store.remove_download(track)
            cover = self.store.cover_file(track.url)
            if cover:
                cover.unlink(missing_ok=True)

    def download(self, tracks) -> None:
        for track in tracks:
            self.downloads.enqueue(track)

    def delete_download(self, track: Track) -> None:
        self.store.remove_download(track)
        self.library_changed.emit()

    def import_playlist(self, link: str) -> None:
        self.message.emit("Import de la playlist en cours…")

        def done(result):
            if isinstance(result, Exception):
                self.message.emit(f"Import impossible : {result}")
                return
            name, tracks = result
            if not tracks:
                self.message.emit("Aucun titre trouvé dans cette playlist")
                return
            self.create_playlist(name, tracks)
            self.message.emit(f"Playlist « {name} » importée ({len(tracks)} titres)")

        run_async(lambda: self.catalog.playlist(link.strip()), done, done)
