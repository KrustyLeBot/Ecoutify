"""The three screens: For you, Search, Library."""
from __future__ import annotations

import random

from PySide6.QtCore import Qt
from PySide6.QtWidgets import (QCheckBox, QHBoxLayout, QInputDialog, QLabel, QLineEdit, QListWidget, QListWidgetItem,
                               QMessageBox, QPushButton, QSplitter, QVBoxLayout, QWidget)

from ..core.models import Playlist
from ..core.recommender import recommend
from ..services.async_util import run_async
from .track_table import TrackTable


def _header(title: str, subtitle: str = "") -> tuple[QHBoxLayout, QLabel, QLabel]:
    row = QHBoxLayout()
    column = QVBoxLayout()
    title_label = QLabel(title)
    title_label.setObjectName("title")
    sub = QLabel(subtitle)
    sub.setObjectName("dim")
    column.addWidget(title_label)
    column.addWidget(sub)
    row.addLayout(column, 1)
    return row, title_label, sub


class ForYouPage(QWidget):
    def __init__(self, ctx):
        super().__init__()
        self.ctx = ctx
        self.loading = False
        row, _, self.subtitle = _header("Pour toi", "D'après tes titres téléchargés et tes playlists")
        self.refresh_button = QPushButton("Actualiser")
        self.refresh_button.clicked.connect(self.refresh)
        row.addWidget(self.refresh_button)
        self.table = TrackTable(ctx)
        self.table.play_requested.connect(ctx.player.play_queue)
        layout = QVBoxLayout(self)
        layout.addLayout(row)
        layout.addWidget(self.table)
        self.table.set_tracks(ctx.local_state.recommendations())  # the last ones, available offline
        ctx.library_changed.connect(self.table.refresh_state)

    def refresh(self) -> None:
        if self.loading or not self.ctx.online():
            return
        self.loading = True
        self.subtitle.setText("Recherche de suggestions…")

        def done(result) -> None:
            self.loading = False
            self.subtitle.setText("D'après tes titres téléchargés et tes playlists")
            if isinstance(result, Exception):
                self.ctx.message.emit(f"Suggestions impossibles : {result}")
            elif result:
                self.table.set_tracks(result)
                self.ctx.local_state.save_recommendations(result)

        run_async(lambda: recommend(self.ctx.catalog, self.ctx.store), done, done)


class SearchPage(QWidget):
    def __init__(self, ctx):
        super().__init__()
        self.ctx = ctx
        row, _, _ = _header("Recherche")
        self.input = QLineEdit()
        self.input.setPlaceholderText("Titre, artiste…")
        self.input.returnPressed.connect(self.search)
        self.table = TrackTable(ctx)
        self.table.play_requested.connect(ctx.player.play_queue)
        layout = QVBoxLayout(self)
        layout.addLayout(row)
        layout.addWidget(self.input)
        layout.addWidget(self.table)
        ctx.library_changed.connect(self.table.refresh_state)

    def search(self) -> None:
        query = self.input.text().strip()
        if not query:
            return
        if not self.ctx.online():
            self.ctx.message.emit("La recherche nécessite une connexion")
            return

        def done(result) -> None:
            if isinstance(result, Exception):
                self.ctx.message.emit(f"Recherche impossible : {result}")
            else:
                self.table.set_tracks(result)
                if not result:
                    self.ctx.message.emit("Aucun résultat")

        run_async(lambda: self.ctx.catalog.search(query), done, done)


class LibraryPage(QWidget):
    def __init__(self, ctx):
        super().__init__()
        self.ctx = ctx
        self.selected_id: str | None = None

        self.playlists = QListWidget()
        self.playlists.setObjectName("playlists")
        self.playlists.currentItemChanged.connect(self._selected)
        new = QPushButton("Nouvelle playlist")
        new.clicked.connect(self._new)
        import_button = QPushButton("Importer…")
        import_button.clicked.connect(self._import)
        buttons = QHBoxLayout()
        buttons.addWidget(new)
        buttons.addWidget(import_button)
        left = QWidget()
        left_layout = QVBoxLayout(left)
        left_layout.addWidget(self.playlists)
        left_layout.addLayout(buttons)

        row, self.name, self.info = _header("Bibliothèque", "Crée une playlist, ajoute-y des titres, puis télécharge-les")
        self.play = QPushButton("Lecture")
        self.play.setObjectName("primary")
        self.play.clicked.connect(self._play)
        self.rename = QPushButton("Renommer")
        self.rename.clicked.connect(self._rename)
        self.delete = QPushButton("Supprimer")
        self.delete.clicked.connect(self._delete)
        self.download_all = QPushButton("Tout télécharger")
        self.download_all.clicked.connect(self._download_all)
        self.keep = QCheckBox("Garder hors ligne (téléchargement automatique)")
        self.keep.clicked.connect(self._keep)
        for button in (self.play, self.rename, self.delete, self.download_all):
            row.addWidget(button)
        self.table = TrackTable(ctx)
        self.table.play_requested.connect(ctx.player.play_queue)
        right = QWidget()
        right_layout = QVBoxLayout(right)
        right_layout.addLayout(row)
        right_layout.addWidget(self.keep)
        right_layout.addWidget(self.table)

        splitter = QSplitter()
        splitter.addWidget(left)
        splitter.addWidget(right)
        splitter.setStretchFactor(1, 1)
        splitter.setSizes([260, 900])
        layout = QVBoxLayout(self)
        layout.addWidget(splitter)

        ctx.library_changed.connect(self.reload)
        self.reload()

    # --- data ---
    def current(self) -> Playlist | None:
        return next((p for p in self.ctx.store.playlists() if p.id == self.selected_id), None)

    def reload(self) -> None:
        """Rebuilds the playlist list and the open playlist from the folder."""
        playlists = self.ctx.store.playlists()
        self.playlists.blockSignals(True)
        self.playlists.clear()
        for playlist in playlists:
            offline = sum(1 for t in playlist.tracks if self.ctx.store.is_downloaded(t.url))
            item = QListWidgetItem(f"{playlist.name}\n{offline}/{len(playlist.tracks)} hors ligne")
            item.setData(Qt.ItemDataRole.UserRole, playlist.id)
            self.playlists.addItem(item)
            if playlist.id == self.selected_id:
                self.playlists.setCurrentItem(item)
        self.playlists.blockSignals(False)
        if self.current() is None and playlists:
            self.selected_id = playlists[0].id
            self.playlists.setCurrentRow(0)
        self._show(self.current())

    def _show(self, playlist: Playlist | None) -> None:
        for widget in (self.play, self.rename, self.delete, self.download_all, self.keep):
            widget.setEnabled(playlist is not None)
        if playlist is None:
            self.name.setText("Bibliothèque")
            self.info.setText("Crée une playlist, ajoute-y des titres, puis télécharge-les")
            self.table.set_tracks([])
            return
        offline = sum(1 for t in playlist.tracks if self.ctx.store.is_downloaded(t.url))
        self.name.setText(playlist.name)
        self.info.setText(f"{offline}/{len(playlist.tracks)} hors ligne")
        self.keep.setChecked(playlist.keep_offline)
        # keep the table (and the selection) when nothing changed in the list itself
        if [t for t in self.table.tracks] != list(playlist.tracks) or self.table.playlist is None or self.table.playlist.id != playlist.id:
            self.table.set_tracks(playlist.tracks, playlist)
        else:
            self.table.playlist = playlist
            self.table.refresh_state()

    def _selected(self, item: QListWidgetItem | None, _previous=None) -> None:
        self.selected_id = item.data(Qt.ItemDataRole.UserRole) if item else None
        self._show(self.current())

    # --- actions ---
    def _new(self) -> None:
        name, ok = QInputDialog.getText(self, "Nouvelle playlist", "Nom :")
        if ok and name.strip():
            self.selected_id = self.ctx.create_playlist(name).id
            self.reload()

    def _import(self) -> None:
        link, ok = QInputDialog.getText(self, "Importer une playlist", "Lien d'une playlist YouTube ou YouTube Music :")
        if ok and "list=" in link:
            self.ctx.import_playlist(link)

    def _play(self) -> None:
        playlist = self.current()
        if playlist and playlist.tracks:
            self.ctx.player.play_shuffled(playlist.tracks)

    def _rename(self) -> None:
        playlist = self.current()
        if playlist:
            name, ok = QInputDialog.getText(self, "Renommer la playlist", "Nom :", text=playlist.name)
            if ok and name.strip():
                self.ctx.rename_playlist(playlist, name)

    def _delete(self) -> None:
        playlist = self.current()
        if playlist and QMessageBox.question(self, "Supprimer", f"Supprimer « {playlist.name} » ?\nLes titres téléchargés qui ne sont dans aucune autre playlist seront supprimés.") == QMessageBox.StandardButton.Yes:
            self.selected_id = None
            self.ctx.delete_playlist(playlist)

    def _download_all(self) -> None:
        playlist = self.current()
        if playlist:
            self.ctx.download(playlist.tracks)

    def _keep(self, checked: bool) -> None:
        playlist = self.current()
        if playlist:
            self.ctx.set_keep_offline(playlist, checked)
