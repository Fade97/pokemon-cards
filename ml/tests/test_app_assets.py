# SPDX-License-Identifier: GPL-3.0-or-later
"""app_assets.py cards.json: the app only gets TCGdex image URLs, never a fallback reference source (#60)."""
from __future__ import annotations

import app_assets
from common import Card


def card(key: str, image_url: str, holdout: bool = True) -> Card:
    lang, card_id = key.split("/")
    set_id, local_id = card_id.rsplit("-", 1)
    return Card(key, card_id, lang, set_id, "Set", local_id, 16, "Name", "Energie", (), image_url, holdout)


def test_card_entry_keeps_tcgdex_url_and_blanks_fallback_url():
    tcgdex = card("de/me04-001", "https://assets.tcgdex.net/de/me/me04/001", holdout=False)
    mee = card("de/mee-009", "https://limitlesstcg.nyc3.cdn.digitaloceanspaces.com/tpci/MEE/MEE_009_R_DE_LG.png")
    assert app_assets.card_entry(tcgdex)["imageUrl"] == tcgdex.image_url
    entry = app_assets.card_entry(mee)
    assert entry["imageUrl"] == ""
    assert entry == {"key": "de/mee-009", "cardId": "mee-009", "lang": "de", "setId": "mee", "setName": "Set",
                     "localId": "009", "official": 16, "name": "Name", "imageUrl": "", "unseen": True}
