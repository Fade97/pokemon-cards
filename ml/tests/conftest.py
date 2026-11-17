# SPDX-License-Identifier: GPL-3.0-or-later
"""Shared helpers for the CPU tests. pyproject.toml puts ml/ and tests/ on the import path."""
from __future__ import annotations

from common import Card


def make_card(key: str, local_id: str, official: int = 165, dex_ids: tuple = (), set_id: str | None = None) -> Card:
    lang, card_id = key.split("/")
    set_id = set_id or card_id.rsplit("-", 1)[0]
    return Card(key, card_id, lang, set_id, "Set", local_id, official, "Name", "Pokemon", dex_ids, "")
