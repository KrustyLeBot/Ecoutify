"""Launcher used by PyInstaller (a package's __main__ cannot be the entry script because of relative imports)."""
import sys

from ecoutify_win.__main__ import main

sys.exit(main())
