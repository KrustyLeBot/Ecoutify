"""The main window: navigation, the three screens, the player bar, the menu (working folder, updates)."""
from __future__ import annotations

from pathlib import Path

from PySide6.QtCore import Qt, Signal
from PySide6.QtWidgets import (QApplication, QFileDialog, QHBoxLayout, QListWidget, QMainWindow, QMessageBox,
                               QProgressDialog, QStackedWidget, QVBoxLayout, QWidget)

from ..services import updater
from ..services.async_util import run_async
from .pages import ForYouPage, LibraryPage, SearchPage
from .player_bar import PlayerBar


class MainWindow(QMainWindow):
    update_progress = Signal(int)

    def __init__(self, ctx):
        super().__init__()
        self.ctx = ctx
        self.resize(1200, 760)
        self._update_dialog: QProgressDialog | None = None

        self.nav = QListWidget()
        self.nav.setObjectName("nav")
        self.nav.setFixedWidth(190)
        for label in ("Pour toi", "Recherche", "Bibliothèque"):
            self.nav.addItem(label)
        self.pages = QStackedWidget()
        self.for_you, self.search, self.library = ForYouPage(ctx), SearchPage(ctx), LibraryPage(ctx)
        for page in (self.for_you, self.search, self.library):
            self.pages.addWidget(page)
        self.nav.currentRowChanged.connect(self.pages.setCurrentIndex)
        self.nav.setCurrentRow(0)

        body = QHBoxLayout()
        body.setContentsMargins(0, 0, 0, 0)
        body.addWidget(self.nav)
        body.addWidget(self.pages, 1)
        central = QWidget()
        column = QVBoxLayout(central)
        column.setContentsMargins(0, 0, 0, 0)
        column.setSpacing(0)
        column.addLayout(body, 1)
        column.addWidget(PlayerBar(ctx))
        self.setCentralWidget(central)

        menu = self.menuBar().addMenu("Ecoutify")
        menu.addAction("Dossier de travail…", self.choose_workdir)
        menu.addAction("Rechercher une mise à jour", lambda: self.check_updates(silent=False))
        menu.addSeparator()
        menu.addAction("Quitter", QApplication.quit)

        ctx.message.connect(lambda text: self.statusBar().showMessage(text, 8000))
        ctx.player.message.connect(lambda text: self.statusBar().showMessage(text, 8000))
        self.update_progress.connect(self._on_update_progress)
        self._retitle()
        self.for_you.refresh()
        ctx.downloads.sync_keep_offline()
        self.check_updates(silent=True)

    def _retitle(self) -> None:
        self.setWindowTitle(f"Ecoutify — {self.ctx.store.root} (build {updater.current_build()})")

    # --- working folder ---
    def choose_workdir(self) -> None:
        path = QFileDialog.getExistingDirectory(self, "Dossier de travail d'Ecoutify", str(self.ctx.store.root))
        if path:
            self.ctx.set_workdir(Path(path))
            self._retitle()
            self.statusBar().showMessage(f"Dossier de travail : {path}", 8000)

    # --- updates ---
    def check_updates(self, silent: bool) -> None:
        if not self.ctx.online():
            if not silent:
                self.statusBar().showMessage("Mise à jour impossible hors ligne", 6000)
            return

        def done(result) -> None:
            if isinstance(result, Exception):
                if not silent:
                    self.statusBar().showMessage(f"Vérification impossible : {result}", 8000)
                return
            if result is None or result.build <= updater.current_build():
                if not silent:
                    QMessageBox.information(self, "Mise à jour", f"Tu as la dernière version (build {updater.current_build()}).")
                return
            self._offer(result)

        run_async(updater.latest, done, done)

    def _offer(self, release: updater.Release) -> None:
        if not updater.can_self_update():
            self.statusBar().showMessage(f"Mise à jour disponible : build {release.build} (le programme packagé peut l'installer)", 10000)
            return
        size = f" ({release.size // 1_000_000} Mo)" if release.size else ""
        if QMessageBox.question(self, "Mise à jour disponible", f"Le build {release.build} est disponible{size}. L'installer maintenant ?") != QMessageBox.StandardButton.Yes:
            return
        self._update_dialog = QProgressDialog("Téléchargement de la mise à jour…", None, 0, 100, self)
        self._update_dialog.setWindowModality(Qt.WindowModality.WindowModal)
        self._update_dialog.setAutoClose(False)
        self._update_dialog.show()
        target = self.ctx.cache_dir / "update" / "Ecoutify-new.exe"

        def done(result) -> None:
            if self._update_dialog:
                self._update_dialog.close()
            if isinstance(result, Exception):
                QMessageBox.warning(self, "Mise à jour", f"Téléchargement impossible : {result}")
                return
            self.ctx.player.stop()
            updater.apply_and_restart(result)
            QApplication.quit()

        run_async(lambda: updater.download(release, target, self.update_progress.emit), done, done)

    def _on_update_progress(self, pct: int) -> None:
        if self._update_dialog:
            self._update_dialog.setValue(pct)

    def closeEvent(self, event) -> None:
        self.ctx.player.stop()
        self.ctx.player.clear_cache()
        super().closeEvent(event)
