"""Foilark logo masters: the "foil sweep" card mark, its small-size cut, lockups and the Android drawables.

The mark is a trading card (63:88, rounded corners) crossed by a curved foil reflection: concentric circular bands
cut out of the card. All geometry is exact: straight edges plus SVG arcs, no polygon approximations.

Run from the repo root:  uv run --with fonttools python design/logo/build_logo.py
"""
import math
import pathlib

from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.transformPen import TransformPen
from fontTools.ttLib import TTFont
from fontTools.varLib.instancer import instantiateVariableFont

ROOT = pathlib.Path(__file__).resolve().parents[2]
OUT = ROOT / "design" / "logo"
RES = ROOT / "android" / "app" / "src" / "main" / "res"
FONT = RES / "font" / "schibsted_grotesk.ttf"

# Pearl colours (ADR 0014)
SEA_GLASS = "#356C6C"
SEA_GLASS_NIGHT = "#8CCBC2"
INK = "#26353C"
PORCELAIN = "#FAFBFC"
NIGHT_SURFACE = "#111718"

# Card on the 256 canvas: 148 x 208 (63:88 is 0.716, this is 0.712), corner radius 18. Its centre sits at y=126,
# 2 units above the canvas centre (optical centring).
X0, X1, Y0, Y1, R = 54, 202, 22, 230, 18
# The sweep: circles around a centre outside the card's lower right corner. Each band is (outer, inner) radius.
CX, CY = 310, 290
FULL_BANDS = [(230, 206), (195, 186)]   # wide band 24, gap 11, thin band 9
SMALL_BANDS = [(232, 204)]              # one band, 28 wide: survives 16 px


def f(v):
    s = f"{v:.2f}".rstrip("0").rstrip(".")
    return "0" if s == "-0" else s


def on_bottom(r):
    """Where the circle of radius r meets the card's bottom edge (y = Y1)."""
    x = CX - math.sqrt(r * r - (CY - Y1) ** 2)
    assert X0 + R < x < X1 - R, f"radius {r} misses the straight bottom edge ({x:.1f})"
    return x


def on_right(r):
    """Where the circle of radius r meets the card's right edge (x = X1)."""
    y = CY - math.sqrt(r * r - (CX - X1) ** 2)
    assert Y0 + R < y < Y1 - R, f"radius {r} misses the straight right edge ({y:.1f})"
    return y


def corner(x, y):
    return f"A{R},{R} 0 0 1 {f(x)},{f(y)}"


def mark_path(bands):
    """The card minus the bands, as one closed sub-path per remaining piece (no holes, so no fill-rule needed)."""
    radii = [r for band in bands for r in band]           # outer, inner, outer, inner ... (decreasing)
    pieces = []
    # piece above the first band: most of the card
    r = radii[0]
    pieces.append(
        f"M{f(on_bottom(r))},{Y1}H{X0 + R}{corner(X0, Y1 - R)}V{Y0 + R}{corner(X0 + R, Y0)}"
        f"H{X1 - R}{corner(X1, Y0 + R)}V{f(on_right(r))}"
        f"A{r},{r} 0 0 0 {f(on_bottom(r))},{Y1}Z")
    # pieces between bands: from the inner edge of one band to the outer edge of the next
    for inner, outer in zip(radii[1::2][:-1], radii[2::2]):
        pieces.append(
            f"M{X1},{f(on_right(inner))}V{f(on_right(outer))}A{outer},{outer} 0 0 0 {f(on_bottom(outer))},{Y1}"
            f"H{f(on_bottom(inner))}A{inner},{inner} 0 0 1 {X1},{f(on_right(inner))}Z")
    # piece below the last band: the lower right corner
    r = radii[-1]
    pieces.append(
        f"M{X1},{f(on_right(r))}V{Y1 - R}{corner(X1 - R, Y1)}H{f(on_bottom(r))}"
        f"A{r},{r} 0 0 1 {X1},{f(on_right(r))}Z")
    return "".join(pieces)


FULL = mark_path(FULL_BANDS)
SMALL = mark_path(SMALL_BANDS)


def wordmark():
    """'foilark' in Schibsted Grotesk 650, tracked -0.04 em as in the app (WordmarkStyle). Font units, y down."""
    font = instantiateVariableFont(TTFont(FONT), {"wght": 650})
    cmap, glyphs, hmtx = font.getBestCmap(), font.getGlyphSet(), font["hmtx"]
    upm = font["head"].unitsPerEm
    x, parts = 0.0, []
    for ch in "foilark":
        name = cmap[ord(ch)]
        pen = SVGPathPen(glyphs, ntos=lambda v: f"{v:.1f}".rstrip("0").rstrip("."))
        glyphs[name].draw(TransformPen(pen, (1, 0, 0, -1, x, 0)))
        parts.append(pen.getCommands())
        x += hmtx[name][0] - 0.04 * upm
    x += 0.04 * upm
    return "".join(parts), x, font["OS/2"].sxHeight, font["OS/2"].sCapHeight, upm


WORD, WORD_ADV, XH, CAP, UPM = wordmark()
# The "f" overhangs a little to the left; measure the ink box for exact spacing.
_lsb = TTFont(FONT)["hmtx"][TTFont(FONT).getBestCmap()[ord("f")]][1]


def svg(w, h, body, title="Foilark logo"):
    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {f(w)} {f(h)}" width="{f(w)}" height="{f(h)}" '
            f'role="img" aria-label="{title}"><title>{title}</title>{body}</svg>\n')


def symbol(fill, d=FULL, bg=None, rounded=0, scale=1.0):
    tile = (f'<rect width="256" height="256" rx="{rounded}" fill="{bg}"/>' if bg else "")
    t = f' transform="translate({f(128 - 128 * scale)},{f(128 - 128 * scale)}) scale({scale})"' if scale != 1 else ""
    return svg(256, 256, f'{tile}<path fill="{fill}"{t} d="{d}"/>')


ICON_SCALE = 0.68  # the card on a full app-icon tile: 141 of 256 tall (55 %)


def horizontal(mark_fill, word_fill):
    # wordmark cap height = 0.5 x card height; text baseline aligned with the card's bottom edge
    s = (Y1 - Y0) * 0.5 / CAP
    gap = 0.18 * (X1 - X0) + 26        # about the width of the wide band plus a bit: optical, not maths
    pad = 8
    tx = pad + (X1 - X0) + gap - _lsb * s
    width = tx + WORD_ADV * s + pad
    body = (f'<path fill="{mark_fill}" transform="translate({pad - X0},0)" d="{FULL}"/>'
            f'<path fill="{word_fill}" transform="translate({f(tx)},{Y1}) scale({s:.5f})" d="{WORD}"/>')
    return svg(math.ceil(width), 256, body)


def stacked(mark_fill, word_fill):
    s = (Y1 - Y0) * 0.36 / CAP
    ww = WORD_ADV * s
    width = max(ww, 256) + 8
    baseline = 256 + 28 + CAP * s
    body = (f'<path fill="{mark_fill}" transform="translate({f((width - 256) / 2)},0)" d="{FULL}"/>'
            f'<path fill="{word_fill}" transform="translate({f((width - ww) / 2 - _lsb * s)},{f(baseline)}) '
            f'scale({s:.5f})" d="{WORD}"/>')
    return svg(math.ceil(width), math.ceil(baseline + 12), body)


def wordmark_only(fill):
    s = 1.0 / UPM * 160
    w = WORD_ADV * s
    body = f'<path fill="{fill}" transform="translate({f(-_lsb * s + 2)},{f(CAP * s + 50)}) scale({s:.5f})" d="{WORD}"/>'
    return svg(math.ceil(w + 4), math.ceil(CAP * s + 50 + 0.22 * UPM * s), body)


def write(name, text):
    (OUT / name).write_text(text)


# ---------------- design/logo masters ----------------
write("foilark-symbol.svg", symbol(SEA_GLASS))
write("foilark-symbol-small.svg", symbol(SEA_GLASS, SMALL))
write("foilark-symbol-black.svg", symbol("#000000"))
write("foilark-symbol-white.svg", symbol("#FFFFFF"))
write("foilark-symbol-night.svg", symbol(SEA_GLASS_NIGHT))
write("foilark-horizontal.svg", horizontal(SEA_GLASS, INK))
write("foilark-horizontal-black.svg", horizontal("#000000", "#000000"))
write("foilark-horizontal-white.svg", horizontal("#FFFFFF", "#FFFFFF"))
write("foilark-horizontal-night.svg", horizontal(SEA_GLASS_NIGHT, "#E6ECEE"))
write("foilark-stacked.svg", stacked(SEA_GLASS, INK))
write("foilark-wordmark.svg", wordmark_only(INK))
# App icon: porcelain card on sea-glass. Square master for Play (Google applies the mask), rounded for previews.
write("foilark-app-icon.svg", symbol(PORCELAIN, FULL, bg=SEA_GLASS, scale=ICON_SCALE))
write("foilark-app-icon-rounded.svg", symbol(PORCELAIN, FULL, bg=SEA_GLASS, rounded=56, scale=ICON_SCALE))
(ROOT / "assets" / "icon.svg").write_text(symbol(PORCELAIN, FULL, bg=SEA_GLASS, rounded=56, scale=ICON_SCALE))


# ---------------- Android drawables ----------------
def vector(size_dp, viewport, paths, comment, group=None):
    body = "".join(f'\n{"        " if group else "    "}<path android:pathData="{d}" android:fillColor="{c}" />'
                   for d, c in paths)
    if group:
        body = f"\n    <group {group}>{body}\n    </group>"
    return (f'<?xml version="1.0" encoding="utf-8"?>\n<!-- {comment} Generated by design/logo/build_logo.py. -->\n'
            f'<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="{size_dp}dp" '
            f'android:height="{size_dp}dp" android:viewportWidth="{viewport}" android:viewportHeight="{viewport}">'
            f"{body}\n</vector>\n")


def argb(hex_rgb):
    return "#FF" + hex_rgb.lstrip("#").upper()


# Adaptive icon: 108 dp canvas, the card 50 dp tall (about 70 % of the 72 dp a launcher shows), centred.
# Its rounded corners stay inside the 66 dp safe circle.
k = 50 / (Y1 - Y0)
tx, ty = 54 - (X0 + X1) / 2 * k, 54 - (Y0 + Y1) / 2 * k
launcher_group = (f'android:scaleX="{k:.5f}" android:scaleY="{k:.5f}" '
                  f'android:translateX="{tx:.3f}" android:translateY="{ty:.3f}"')
corner_dist = math.hypot((X1 - X0) / 2 - R * (1 - 1 / math.sqrt(2)), (Y1 - Y0) / 2 - R * (1 - 1 / math.sqrt(2))) * k
assert corner_dist <= 33, f"card corners leave the 66 dp safe zone ({corner_dist:.1f} dp)"

draw = RES / "drawable"
(draw / "ic_launcher_foreground.xml").write_text(vector(
    108, 108, [(FULL, argb(PORCELAIN))], "Foilark launcher mark: a card with a curved foil sweep (design/logo).",
    group=launcher_group))
(draw / "ic_launcher_monochrome.xml").write_text(vector(
    108, 108, [(SMALL, "#FFFFFFFF")], "Themed-icon layer: the small-size cut of the mark, tinted by the launcher.",
    group=launcher_group))
# Notification: the small cut, white; the card fills 20 of the 24 dp.
n = 20 / (Y1 - Y0)
(draw / "ic_notification.xml").write_text(vector(
    24, 24, [(SMALL, "#FFFFFFFF")], "Notification icon (price alerts, #76): the small-size cut of the Foilark mark.",
    group=(f'android:scaleX="{n:.5f}" android:scaleY="{n:.5f}" '
           f'android:translateX="{12 - (X0 + X1) / 2 * n:.3f}" android:translateY="{12 - (Y0 + Y1) / 2 * n:.3f}"')))
# In-app mark next to the wordmark: 24 dp, tinted with LumifoilTheme.colors.mark. The card is 22 dp tall.
m = 22 / (Y1 - Y0)
(draw / "ic_foilark_mark.xml").write_text(vector(
    24, 24, [(FULL, "#FF000000")], "Foilark mark next to the wordmark; tinted with LumifoilTheme.colors.mark.",
    group=(f'android:scaleX="{m:.5f}" android:scaleY="{m:.5f}" '
           f'android:translateX="{12 - (X0 + X1) / 2 * m:.3f}" android:translateY="{12 - (Y0 + Y1) / 2 * m:.3f}"')))
print("wrote masters and drawables")
