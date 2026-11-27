# Karte vor die Linse.

Der Flyer wirkt wie ein zweifarbiger Fotoabzug, auf dem eine Hand eine blanke Foil-Karte aus dem Stapel in die roten Ecken eines Kamerasuchers hebt. Leuchtrot hebt den Testeraufruf hervor, und der Griff an die Kartenkante greift eine vertraute Bewegung vom Spieltisch auf. Auf der hellen Anmeldefläche stehen die drei Schritte, die 14 Tage und der QR-Code zusammen.

## Bildprompt

Ein Bild mit Codex ImageGen über `codex-image.mjs`. Der Build rastert das Foto als cremefarbene Druckpunkte.

> A bold high-contrast analog black-and-white editorial photograph, landscape 3:2, of a single adult human hand lifting one completely blank, rectangular brushed metallic trading-card-sized foil blank from a small stack of completely blank cards, like taking a collectible out of a stack. Seen from directly above with hard direct flash against a very dark, nearly black untextured table. The blank foil face reflects diagonal bands of hard light, with tiny flecks of fine photographic grain. The main card is centered at 58% from left, rotated about 15 degrees clockwise, clearly a thin card with gently rounded corners, with room around its upper edge. The hand enters naturally from bottom right and holds the card by the lower-right corner in a simple, believable pinch. The short stack is beneath and slightly left of the raised card. Deep shadows, sharply defined silver paper, photocopy zine energy, strong legible silhouette. Pure monochrome. All cards have featureless edge-to-edge silver faces without borders, patterns, pictures, designs, symbols, or writing. No text, no logos, no watermark, no creatures or characters, no birds, no balls, no circular graphic, no phone and no other props.

## Build

```sh
node design/marketing/council/sol/build.mjs
```

Der Build nutzt den Playwright- und Chromium-Setup von `marketing-images` und Python mit Pillow, NumPy und PyMuPDF. Auf anderen Rechnern lassen sich diese Pfade über `FOILARK_PLAYWRIGHT` (ESM-Einstiegspunkt von playwright-core), `CHROME_PATH` und `FOILARK_PYTHON` (Python-Executable) setzen. QR-Code und Schibsted Grotesk kommen aus dem Foilark-Repo.
