"""Screen the photo and normalize the print PDF to the exact physical dimensions."""

import sys
from pathlib import Path

import numpy as np
from PIL import Image, ImageOps
import pymupdf


ROOT = Path(__file__).resolve().parent
POINTS_PER_MM = 72 / 25.4


def screen_photo():
    source = Image.open(ROOT / "card-photo.png").convert("L")
    light = np.asarray(ImageOps.autocontrast(source, cutoff=(7, 0.5))) / 255
    height, width = light.shape
    y, x = np.ogrid[:height, :width]
    pitch = 7.2
    # Rotate the screen so the dots stay distinct from vertical card edges.
    diag_x = (x + y) / (pitch * np.sqrt(2))
    diag_y = (y - x) / (pitch * np.sqrt(2))
    distance = np.hypot(diag_x % 1 - 0.5, diag_y % 1 - 0.5)
    radius = 0.73 * np.maximum(0, (light - 0.026) / 0.974) ** 0.79
    coverage = np.clip((radius - distance) * pitch + 0.5, 0, 1)
    coverage *= np.clip((light - 0.026) * 16, 0, 1)
    screened = Image.new("RGBA", source.size, "#faf2df")
    screened.putalpha(Image.fromarray(np.uint8(coverage * 255)))
    screened.save(ROOT / "card-screened.png")


def export_print():
    with pymupdf.open(ROOT / ".browser-print.pdf") as source, pymupdf.open() as target:
        if source.page_count != 1:
            raise ValueError(f"Expected 1 page, got {source.page_count}")
        page = target.new_page(width=111 * POINTS_PER_MM, height=154 * POINTS_PER_MM)
        page.show_pdf_page(page.rect, source, 0, keep_proportion=False)
        page.set_bleedbox(page.rect)
        trim = pymupdf.Rect(*(value * POINTS_PER_MM for value in (3, 3, 108, 151)))
        page.set_trimbox(trim)
        target.set_metadata({"title": "Foilark · Karte vor die Linse"})
        target.xref_set_key(target.pdf_catalog(), "Lang", "(de)")
        target.save(ROOT / "out/flyer-a6-bleed.pdf", deflate=True, garbage=4)
        page.get_pixmap(dpi=300, clip=trim).save(ROOT / "out/flyer-a6.png")


if __name__ == "__main__":
    {"screen": screen_photo, "print": export_print}[sys.argv[1]]()
