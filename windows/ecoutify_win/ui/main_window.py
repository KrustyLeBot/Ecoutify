"""The main window: navigation, the three screens, the player bar, the menu (working folder, updates)."""
from __future__ import annotations

from pathlib import Path

from PySide6.QtCore import QTimer
from PySide6.QtWidgets import (QApplication, QFileDialog, QHBoxLayout, QListWidget, QMainWindow, QMessageBox,
                               QStackedWidget, QVBoxLayout, QWidget)

from ..services import updater
from ..services.async_util import run_async
from .pages import ForYouPage, LibraryPage, SearchPage
from .player_bar import PlayerBar
from .update_toast import UpdateToast


class MainWindow(QMainWindow):
    def __init__(self, ctx):
        super().__init__()
        self.ctx = ctx
        self.resize(1200, 760)
        self.toast: UpdateToast | None = None
        self._cleanup_tries = 0

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
        menu.addAction("Rechercher une mise à jour", lambda: self.check_updates(manual=True))
        menu.addSeparator()
        menu.addAction("Quitter", QApplication.quit)

        ctx.message.connect(lambda text: self.statusBar().showMessage(text, 8000))
        ctx.player.message.connect(lambda text: self.statusBar().showMessage(text, 8000))
        self._retitle()
        self.for_you.refresh()
        ctx.downloads.sync_keep_offline()
        # the previous exe (after a self-update) is deleted once its process is gone
        self._cleanup_timer = QTimer(self)
        self._cleanup_timer.timeout.connect(self._cleanup_old_exe)
        if not updater.cleanup_previous():
            self._cleanup_timer.start(2000)
        # update check shortly after the start, then every 6 hours
        QTimer.singleShot(5000, self.check_updates)
        self._update_timer = QTimer(self)
        self._update_timer.timeout.connect(self.check_updates)
        self._update_timer.start(6 * 3600 * 1000)

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
    def check_updates(self, manual: bool = False) -> None:
        """Quiet check at startup and every 6 hours; 'manual' (menu) also says when there is nothing new."""
        if not self.ctx.online():
            if manual:
                self.statusBar().showMessage("Mise à jour impossible hors ligne", 6000)
            return

        def done(result) -> None:
            if isinstance(result, Exception):
                if manual:
                    QMessageBox.warning(self, "Mise à jour", f"Vérification impossible : {result}")
                return
            if result is None or not updater.is_newer(result):
                if manual:
                    QMessageBox.information(self, "Mise à jour", f"Tu as la dernière version (build {updater.current_build()}).")
                return
            if self.toast is None or self.toast.release.build != result.build:
                self.toast = UpdateToast(self, result)
            self.toast.place()
            self.toast.show()

        run_async(updater.latest, done, done)

    def _cleanup_old_exe(self) -> None:
        """The previous .exe stays locked while that process exits: retry every 2 s for 2 minutes."""
        self._cleanup_tries += 1
        if updater.cleanup_previous() or self._cleanup_tries >= 60:
            self._cleanup_timer.stop()

    def resizeEvent(self, event) -> None:
        super().resizeEvent(event)
        if self.toast is not None and self.toast.isVisible():
            self.toast.place()  # stays pinned to the top-right corner

    def closeEvent(self, event) -> None:
        self.ctx.player.stop()
        self.ctx.player.clear_cache()
        super().closeEvent(event)
