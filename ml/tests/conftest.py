# SPDX-License-Identifier: GPL-3.0-or-later
import sys
from pathlib import Path

# The ml/ scripts import each other as top-level modules (python build_packs.py ...).
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
