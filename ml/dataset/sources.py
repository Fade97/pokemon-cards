# SPDX-License-Identifier: GPL-3.0-or-later
"""Where third-party reference images come from, and on what terms (ADR 0008/0009: URLs only, never re-hosted).

A manifest image URL is matched to a source by its host. An export refuses a URL whose host is not listed here, so
a new image source (like Limitless TCG for set MEE, #60) cannot slip into a release without its terms being written down.
"""
from __future__ import annotations

from dataclasses import asdict, dataclass
from urllib.parse import urlsplit

IMAGE_SUFFIXES = (".png", ".webp", ".jpg", ".jpeg")


@dataclass(frozen=True)
class Source:
    id: str
    name: str
    hosts: tuple[str, ...]
    homepage: str
    terms_url: str
    images: str             # who owns the images and what the source says about reuse
    credit: str             # the line to show when the images are used
    min_interval_s: float   # politeness: at most one request per this many seconds per host
    # A URL without a file suffix is a base URL; the first suffix is the primary file, the second the fallback.
    base_suffixes: tuple[str, ...] = ()

    def public(self) -> dict:
        d = asdict(self)
        d["hosts"] = list(self.hosts)
        d["base_suffixes"] = list(self.base_suffixes)
        return d


SOURCES: dict[str, Source] = {s.id: s for s in [
    Source(
        id="tcgdex",
        name="TCGdex",
        hosts=("assets.tcgdex.net",),
        homepage="https://tcgdex.dev",
        terms_url="https://github.com/tcgdex/cards-database/blob/master/LICENSE",
        images="Card images are (c) The Pokemon Company, Nintendo, Creatures and GAME FREAK. TCGdex serves them from "
               "its CDN; its MIT licence covers the card data, not the artwork. Download for your own research use; "
               "do not re-host the images.",
        credit="Card data and image URLs: TCGdex (https://tcgdex.dev), MIT licence.",
        min_interval_s=0.25,
        base_suffixes=("/high.png", "/high.webp"),
    ),
    Source(
        id="limitless",
        name="Limitless TCG card image CDN",
        hosts=("limitlesstcg.nyc3.cdn.digitaloceanspaces.com",),
        homepage="https://limitlesstcg.com",
        terms_url="https://limitlesstcg.com",
        images="Card images are (c) The Pokemon Company, Nintendo, Creatures and GAME FREAK, served by Limitless TCG "
               "(official card renders; its upstream for recent sets is the malie.io export of Pokemon TCG Live). No "
               "licence or terms of use are stated; limitlesstcg.com/robots.txt allows all agents (checked "
               "2026-11-18). Used only for sets TCGdex has no images for (e.g. MEE). Do not re-host the images.",
        credit="Images for sets without TCGdex images: Limitless TCG (https://limitlesstcg.com), from the malie.io "
               "export (https://malie.io).",
        min_interval_s=1.0,
    ),
]}


class UnknownSource(ValueError):
    pass


def source_for(url: str, sources: dict[str, Source] = SOURCES) -> Source:
    host = (urlsplit(url).hostname or "").lower()
    for s in sources.values():
        if host in s.hosts:
            return s
    raise UnknownSource(f"no source registered for host {host!r} ({url}); add it to dataset/sources.py with its terms")


def resolve(url: str, sources: dict[str, Source] = SOURCES) -> tuple[Source, str, str]:
    """(source, primary file URL, fallback file URL or '') for a manifest/pack image URL.

    TCGdex gives a base URL (`.../sv03.5/001`); the file is `<base>/high.png`, with `high.webp` as fallback (what
    fetch.py does). Other sources give a full file URL.
    """
    parts = urlsplit(url)
    if parts.scheme != "https":
        raise ValueError(f"image URL must be https: {url}")
    src = source_for(url, sources)
    if parts.path.lower().endswith(IMAGE_SUFFIXES):
        return src, url, ""
    if not src.base_suffixes:
        raise ValueError(f"{src.id} URL without a file suffix: {url}")
    base = url.rstrip("/")
    primary = base + src.base_suffixes[0]
    alt = base + src.base_suffixes[1] if len(src.base_suffixes) > 1 else ""
    return src, primary, alt
