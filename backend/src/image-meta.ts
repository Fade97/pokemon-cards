// Metadata stripping for contributed card crops (#14, ADR 0008): no EXIF, XMP, IPTC or comments may be stored.
//
// The image is not decoded or re-encoded. The container is walked and every segment or chunk that can carry
// metadata is dropped; anything that cannot be parsed with certainty is rejected.

export type ImageType = "image/jpeg" | "image/webp";

export interface CleanImage {
  bytes: Uint8Array<ArrayBuffer>;
  width: number;
  height: number;
  /** Names of the dropped segments/chunks, e.g. ["APP1", "COM"]; for tests and logging. */
  stripped: string[];
}

export class ImageError extends Error {}

export function sniffType(bytes: Uint8Array): ImageType | null {
  if (bytes.length >= 3 && bytes[0] === 0xff && bytes[1] === 0xd8 && bytes[2] === 0xff) return "image/jpeg";
  if (bytes.length >= 12 && ascii(bytes, 0, 4) === "RIFF" && ascii(bytes, 8, 4) === "WEBP") return "image/webp";
  return null;
}

/** Returns the image without metadata, or throws ImageError. */
export function stripMetadata(bytes: Uint8Array, type: ImageType): CleanImage {
  return type === "image/jpeg" ? stripJpeg(bytes) : stripWebp(bytes);
}

// ---------------------------------------------------------------------------------------------------------------
// JPEG

/**
 * Every APPn segment goes: decoders need none of them for a YCbCr JPEG, and kept payloads (a JFIF thumbnail, an ICC
 * profile's text tags) could carry anything. The one exception is APP14 in its fixed 12-byte Adobe form, which
 * tells decoders the colour transform of RGB/CMYK files and has no room for free text.
 */
function keepApp(src: Uint8Array, pos: number, marker: number, length: number): boolean {
  return marker === 0xee && length === 14 && ascii(src, pos + 4, 5) === "Adobe";
}

function stripJpeg(src: Uint8Array): CleanImage {
  const out: Uint8Array[] = [src.subarray(0, 2)];
  const stripped: string[] = [];
  let width = 0;
  let height = 0;
  let pos = 2;
  for (;;) {
    if (pos + 4 > src.length || src[pos] !== 0xff) throw new ImageError("broken JPEG segment structure");
    let marker = src[pos + 1]!;
    while (marker === 0xff && pos + 2 < src.length) marker = src[++pos + 1]!; // fill bytes
    if (marker === 0xd8 || marker === 0xd9 || (marker >= 0xd0 && marker <= 0xd7) || marker === 0x01) {
      throw new ImageError("unexpected JPEG marker before the image data");
    }
    const length = (src[pos + 2]! << 8) | src[pos + 3]!;
    const end = pos + 2 + length;
    if (length < 2 || end > src.length) throw new ImageError("JPEG segment runs past the end");
    const isApp = marker >= 0xe0 && marker <= 0xef;
    if ((isApp && !keepApp(src, pos, marker, length)) || marker === 0xfe) {
      stripped.push(marker === 0xfe ? "COM" : `APP${marker - 0xe0}`);
    } else {
      out.push(src.subarray(pos, end));
    }
    // SOF0..SOF15 except DHT (C4), JPG (C8) and DAC (CC) carry the frame size.
    if (marker >= 0xc0 && marker <= 0xcf && marker !== 0xc4 && marker !== 0xc8 && marker !== 0xcc) {
      if (length < 7) throw new ImageError("short JPEG frame header");
      height = (src[pos + 5]! << 8) | src[pos + 6]!;
      width = (src[pos + 7]! << 8) | src[pos + 8]!;
    }
    pos = end;
    if (marker === 0xda) break; // start of scan: entropy-coded data follows
  }
  if (!width || !height) throw new ImageError("JPEG without frame header");
  // Entropy-coded data escapes 0xFF as FF00 and only uses FFD0-FFD7 as restart markers, so the first FFD9 is the
  // end of the image. Bytes after it (where some tools append data) are dropped. Between progressive scans, more
  // segments (DHT, SOS, ...) may follow; metadata segments are not allowed there.
  let eoi = -1;
  for (let i = pos; i + 1 < src.length; i++) {
    if (src[i] !== 0xff) continue;
    const m = src[i + 1]!;
    if (m === 0xd9) {
      eoi = i;
      break;
    }
    if ((m >= 0xe0 && m <= 0xef) || m === 0xfe) throw new ImageError("metadata segment between JPEG scans");
  }
  if (eoi < 0) throw new ImageError("JPEG without end marker");
  out.push(src.subarray(pos, eoi + 2));
  if (eoi + 2 < src.length) stripped.push("trailing data");
  return { bytes: concat(out), width, height, stripped };
}

// ---------------------------------------------------------------------------------------------------------------
// WebP (RIFF): https://developers.google.com/speed/webp/docs/riff_container

const VP8X_ALPHA = 0x10;

function stripWebp(src: Uint8Array): CleanImage {
  const riffSize = u32(src, 4);
  if (riffSize + 8 > src.length) throw new ImageError("WebP RIFF size runs past the end");
  const end = Math.min(src.length, riffSize + 8); // bytes after the RIFF container are dropped
  const chunks: { fourcc: string; data: Uint8Array }[] = [];
  let pos = 12;
  while (pos < end) {
    if (pos + 8 > end) throw new ImageError("broken WebP chunk header");
    const fourcc = ascii(src, pos, 4);
    const size = u32(src, pos + 4);
    const next = pos + 8 + size + (size & 1);
    if (pos + 8 + size > end) throw new ImageError("WebP chunk runs past the end");
    chunks.push({ fourcc, data: src.subarray(pos, Math.min(next, end)) });
    pos = next;
  }
  const kinds = chunks.map((c) => c.fourcc);
  if (kinds.includes("ANIM") || kinds.includes("ANMF")) throw new ImageError("animated WebP");
  const stripped: string[] = [];
  const kept = chunks.filter((c) => {
    // ICCP goes too: a colour profile's text tags can carry anything, and WebP from the app has none.
    if (c.fourcc === "EXIF" || c.fourcc === "XMP " || c.fourcc === "ICCP") {
      stripped.push(c.fourcc.trim());
      return false;
    }
    if (!["VP8X", "VP8 ", "VP8L", "ALPH"].includes(c.fourcc)) {
      stripped.push(c.fourcc.trim()); // unknown chunks could carry anything
      return false;
    }
    return true;
  });
  // A still image has one frame chunk, at most one VP8X (first) and at most one ALPH, which needs the VP8X. Anything
  // else could hide a second picture, or a size the bounds check never sees (#128).
  const count = (fourcc: string) => kept.filter((c) => c.fourcc === fourcc).length;
  const images = kept.filter((c) => c.fourcc === "VP8 " || c.fourcc === "VP8L");
  if (images.length === 0) throw new ImageError("WebP without image data");
  if (images.length > 1) throw new ImageError("WebP with more than one image chunk");
  const image = images[0]!;
  const { width, height } = webpSize(image.fourcc, image.data.subarray(8));
  if (count("VP8X") > 1 || count("ALPH") > 1) throw new ImageError("duplicate WebP chunk");

  const vp8x = kept.find((c) => c.fourcc === "VP8X");
  if (vp8x) {
    if (kept[0] !== vp8x) throw new ImageError("VP8X is not the first WebP chunk");
    // 8 bytes of chunk header and exactly 10 of payload: no room for anything but flags and the canvas size.
    // The declared size, not the padded slice: a 9-byte VP8X plus its pad byte is 18 bytes long too.
    if (u32(vp8x.data, 4) !== 10 || vp8x.data.length !== 18) throw new ImageError("VP8X chunk of the wrong size");
    const copy = vp8x.data.slice();
    copy[8] = copy[8]! & VP8X_ALPHA; // clears the ICC, EXIF, XMP and animation flags
    copy[9] = copy[10] = copy[11] = 0; // reserved bytes
    vp8x.data = copy;
    const canvasWidth = 1 + (copy[12]! | (copy[13]! << 8) | (copy[14]! << 16));
    const canvasHeight = 1 + (copy[15]! | (copy[16]! << 8) | (copy[17]! << 16));
    if (canvasWidth !== width || canvasHeight !== height) throw new ImageError("WebP canvas and frame sizes differ");
  } else if (count("ALPH") > 0) {
    throw new ImageError("ALPH chunk without VP8X");
  }
  const body = concat(kept.map((c) => c.data));
  const header = new Uint8Array(12);
  header.set(src.subarray(0, 12));
  new DataView(header.buffer).setUint32(4, body.length + 4, true);
  return { bytes: concat([header, body]), width, height, stripped };
}

function webpSize(fourcc: string, d: Uint8Array): { width: number; height: number } {
  if (fourcc === "VP8 ") {
    // 3-byte frame tag, start code 9D 01 2A, then 14-bit width and height.
    if (d.length < 10 || d[3] !== 0x9d || d[4] !== 0x01 || d[5] !== 0x2a) throw new ImageError("bad VP8 header");
    return { width: (d[6]! | (d[7]! << 8)) & 0x3fff, height: (d[8]! | (d[9]! << 8)) & 0x3fff };
  }
  // VP8L: signature 0x2F, then 14 bits width - 1 and 14 bits height - 1.
  if (d.length < 5 || d[0] !== 0x2f) throw new ImageError("bad VP8L header");
  const bits = d[1]! | (d[2]! << 8) | (d[3]! << 16) | (d[4]! << 24);
  return { width: (bits & 0x3fff) + 1, height: ((bits >>> 14) & 0x3fff) + 1 };
}

// ---------------------------------------------------------------------------------------------------------------

function ascii(b: Uint8Array, at: number, n: number): string {
  return String.fromCharCode(...b.subarray(at, at + n));
}

function u32(b: Uint8Array, at: number): number {
  return (b[at]! | (b[at + 1]! << 8) | (b[at + 2]! << 16) | (b[at + 3]! << 24)) >>> 0;
}

function concat(parts: Uint8Array[]): Uint8Array<ArrayBuffer> {
  const out = new Uint8Array(parts.reduce((n, p) => n + p.length, 0));
  let at = 0;
  for (const p of parts) {
    out.set(p, at);
    at += p.length;
  }
  return out;
}
