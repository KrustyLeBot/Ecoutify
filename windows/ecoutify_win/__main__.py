"""Entry point."""
from __future__ import annotations

import io
import sys
from pathlib import Path

ASSETS = Path(__file__).parent / "assets"


def main() -> int:
    # A windowed program has no console: give libraries something to write to.
    if sys.stdout is None:
        sys.stdout = io.StringIO()
    if sys.stderr is None:
        sys.stderr = io.StringIO()

    from PySide6.QtGui import QIcon
    from PySide6.QtWidgets import QApplication, QFileDialog, QMessageBox

    from .config import Config
    from .context import AppContext
    from .ui.main_window import MainWindow
    from .ui.theme import apply_theme

    app = QApplication(sys.argv)
    app.setApplicationName("Ecoutify")
    app.setOrganizationName("Ecoutify")
    app.setWindowIcon(QIcon(str(ASSETS / "icon.png")))
    apply_theme(app)

    config = Config()
    if config.workdir is None:
        QMessageBox.information(None, "Ecoutify", "Choisis le dossier de travail : le dossier « Ecoutify » synchronisé avec ton téléphone (il contient music/, covers/ et playlists.json).")
        path = QFileDialog.getExistingDirectory(None, "Dossier de travail d'Ecoutify", str(Path.home() / "Ecoutify"))
        if not path:
            return 0
        config.workdir = Path(path)

    ctx = AppContext(config)
    window = MainWindow(ctx)
    window.show()
    return app.exec()


if __name__ == "__main__":
    sys.exit(main())
