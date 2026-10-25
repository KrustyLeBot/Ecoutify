"""The list of tracks used by every screen: cover, title, artist, duration, offline state, context menu."""
from __future__ import annotations

from PySide6.QtCore import QSize, Qt, Signal
from PySide6.QtGui import QFont, QIcon
from PySide6.QtWidgets import QHeaderView, QInputDialog, QMenu, QTreeWidget, QTreeWidgetItem

from ..core.models import Playlist, Track, format_duration

ICON = 40


class TrackTable(QTreeWidget):
    play_requested = Signal(list, int)  # tracks, index

    def __init__(self, ctx):
        super().__init__()
        self.ctx = ctx
        self.playlist: Playlist | None = None
        self.tracks: list[Track] = []
        self._items: dict[str, list[QTreeWidgetItem]] = {}
        self.setHeaderLabels(["Titre", "Artiste", "Durée", "Hors ligne"])
        self.setRootIsDecorated(False)
        self.setUniformRowHeights(True)
        self.setIconSize(QSize(ICON, ICON))
        self.setSelectionMode(QTreeWidget.SelectionMode.ExtendedSelection)
        self.setContextMenuPolicy(Qt.ContextMenuPolicy.CustomContextMenu)
        self.customContextMenuRequested.connect(self._menu)
        self.itemDoubleClicked.connect(lambda item, _: self.play_requested.emit(self.tracks, self.indexOfTopLevelItem(item)))
        header = self.header()
        header.setStretchLastSection(False)
        header.setSectionResizeMode(0, QHeaderView.ResizeMode.Stretch)
        header.setSectionResizeMode(1, QHeaderView.ResizeMode.Interactive)
        header.setSectionResizeMode(2, QHeaderView.ResizeMode.ResizeToContents)
        header.setSectionResizeMode(3, QHeaderView.ResizeMode.ResizeToContents)
        self.setColumnWidth(1, 240)
        self.setHorizontalScrollBarPolicy(Qt.ScrollBarPolicy.ScrollBarAlwaysOff)
        ctx.downloads.progress.connect(self._on_progress)
        ctx.downloads.queue_changed.connect(self.refresh_state)
        ctx.player.track_changed.connect(lambda _: self.refresh_state())

    # --- content ---
    def set_tracks(self, tracks, playlist: Playlist | None = None) -> None:
        self.playlist = playlist
        self.tracks = list(tracks)
        self.clear()
        self._items.clear()
        for track in self.tracks:
            item = QTreeWidgetItem([track.title, track.artist, format_duration(track.duration), ""])
            item.setData(0, Qt.ItemDataRole.UserRole, track)
            self.addTopLevelItem(item)
            self._items.setdefault(track.url, []).append(item)
        self.refresh_state()

    def refresh_state(self) -> None:
        """Updates covers, the offline column and the 'now playing' marker without rebuilding the list."""
        current = self.ctx.player.current
        for url, items in self._items.items():
            for item in items:
                track: Track = item.data(0, Qt.ItemDataRole.UserRole)
                pixmap = self.ctx.covers.pixmap(track, ICON)
                if pixmap is not None:
                    item.setIcon(0, QIcon(pixmap))
                item.setText(3, self._offline_text(url))
                font = QFont(item.font(0))
                font.setBold(current is not None and current.url == url)
                item.setFont(0, font)

    def _offline_text(self, url: str) -> str:
        if self.ctx.store.is_downloaded(url):
            return "✓"
        if self.ctx.downloads.is_pending(url):
            pct = self.ctx.downloads.percent(url)
            return f"↓ {pct} %" if pct else "↓ …"
        return ""

    def _on_progress(self, url: str, pct: int) -> None:
        for item in self._items.get(url, []):
            item.setText(3, f"↓ {pct} %")

    # --- context menu ---
    def selected_tracks(self) -> list[Track]:
        return [i.data(0, Qt.ItemDataRole.UserRole) for i in self.selectedItems()]

    def _menu(self, pos) -> None:
        selected = self.selected_tracks()
        if not selected:
            return
        menu = QMenu(self)
        first = selected[0]
        menu.addAction("Lire", lambda: self.play_requested.emit(self.tracks, self.tracks.index(first)))
        add = menu.addMenu("Ajouter à une playlist")
        for playlist in self.ctx.store.playlists():
            add.addAction(playlist.name, lambda p=playlist: self._add_to(p, selected))
        add.addSeparator()
        add.addAction("Nouvelle playlist…", lambda: self._add_to_new(selected))
        if self.playlist is not None:  # downloads only exist for tracks that are in a playlist
            menu.addSeparator()
            todo = [t for t in selected if not self.ctx.store.is_downloaded(t.url)]
            if todo:
                menu.addAction("Télécharger", lambda: self.ctx.download(todo))
            for track in (t for t in selected if self.ctx.store.is_downloaded(t.url)):
                menu.addAction(f"Supprimer le téléchargement : {track.title[:30]}", lambda t=track: self.ctx.delete_download(t))
            menu.addAction("Retirer de la playlist", lambda: [self.ctx.remove_from_playlist(self.playlist, t) for t in selected])
        menu.exec(self.viewport().mapToGlobal(pos))

    def _add_to(self, playlist: Playlist, tracks) -> None:
        for track in tracks:
            self.ctx.add_to_playlist(playlist, track)
        self.ctx.message.emit(f"Ajouté à « {playlist.name} »")

    def _add_to_new(self, tracks) -> None:
        name, ok = QInputDialog.getText(self, "Nouvelle playlist", "Nom :")
        if ok and name.strip():
            self.ctx.create_playlist(name, tracks)
