#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
# Creates the LiteRT export environment. litert-torch needs litert-converter, which has no Windows wheels,
# so on Windows run this inside WSL:  wsl -d Ubuntu -- bash /mnt/d/AI/pokemon-cards/ml/export_env.sh
set -euo pipefail
command -v uv >/dev/null 2>&1 || [ -x ~/.local/bin/uv ] || curl -LsSf https://astral.sh/uv/install.sh | sh
UV=$(command -v uv || echo ~/.local/bin/uv)
ENV=${CARDINDEX_EXPORT_ENV:-$HOME/cardindex-export}
mkdir -p "$ENV"
cd "$ENV"
[ -d .venv ] || "$UV" venv -q -p 3.12
"$UV" pip install -q timm litert-torch opencv-python-headless numpy
# PyPI torchvision may not match the CPU torch build ("operator torchvision::nms does not exist"): pin both.
"$UV" pip install -q --reinstall 'torch==2.14.1+cpu' 'torchvision==0.29.1+cpu' --index-url https://download.pytorch.org/whl/cpu
.venv/bin/python -c 'import litert_torch, torch, timm; print("ok", torch.__version__, timm.__version__)'
