// Language packs from R2 `PACKS` (ADR 0003, issue #5, format in docs/packs.md).
//
//   GET|HEAD /v1/packs/catalog.json       what is published: versions, sizes, SHA-256 of every file
//   GET|HEAD /v1/packs/<lang>/<file>      a pack file, e.g. en/index-3.bin, en/delta-2-3.json.gz
//   GET|HEAD /v1/packs/models/<file>      the shared recognition model, named by its hash
//
// Everything except catalog.json is versioned and never overwritten, so it is cached for a year. ETag/If-None-Match
// are answered by R2; a single byte range (resuming a large index download) is validated here first.

const CATALOG = "catalog.json";
/** The catalog changes when a pack is published; apps poll it, so keep it short. */
const CACHE_CATALOG = "public, max-age=300";
/** Versioned files: the key changes with the content (docs/packs.md, "Immutability"). */
const CACHE_IMMUTABLE = "public, max-age=31536000, immutable";

/** TCGdex language codes (en, ja, pt-br, zh-tw, …) or the model directory. */
const DIR = /^(?:[a-z]{2}(?:-[a-z]{2})?|models)$/;
/** Plain file names: no slashes, no leading dot, so no traversal or hidden keys. */
const FILE = /^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/;

/** The R2 key for a /v1/packs/... path, or null if the path cannot name a pack file. */
export function packKey(pathname: string): string | null {
  const rest = pathname.slice("/v1/packs/".length);
  if (rest === CATALOG) return CATALOG;
  const parts = rest.split("/");
  if (parts.length !== 2) return null;
  const [dir, file] = parts as [string, string];
  if (!DIR.test(dir) || !FILE.test(file) || file.includes("..")) return null;
  return `${dir}/${file}`;
}

export async function servePack(request: Request, bucket: R2Bucket, key: string): Promise<Response> {
  const head = request.method === "HEAD";
  const wanted = head ? null : parseRange(request.headers.get("Range"));
  let range: { offset: number; length: number } | undefined;
  if (wanted) {
    // Range requests are rare (a resumed download), so one extra metadata read to validate them is fine.
    const meta = await bucket.head(key);
    if (!meta) return notFound();
    range = resolveRange(wanted, meta.size) ?? undefined;
    if (!range) return unsatisfiable(meta.size);
  }
  const object = head ? await bucket.head(key) : await bucket.get(key, { onlyIf: request.headers, range });
  if (!object) return notFound();

  const headers = new Headers();
  object.writeHttpMetadata(headers);
  headers.set("ETag", object.httpEtag);
  headers.set("Last-Modified", object.uploaded.toUTCString());
  headers.set("Accept-Ranges", "bytes");
  headers.set("Cache-Control", key === CATALOG ? CACHE_CATALOG : CACHE_IMMUTABLE);
  if (!headers.has("Content-Type")) headers.set("Content-Type", "application/octet-stream");

  if (head) {
    headers.set("Content-Length", String(object.size));
    return new Response(null, { headers });
  }
  if (!hasBody(object)) {
    // A precondition failed: If-None-Match / If-Modified-Since mean "you already have it", If-Match does not.
    const notModified = request.headers.has("If-None-Match") || request.headers.has("If-Modified-Since");
    return new Response(null, { status: notModified ? 304 : 412, headers });
  }
  if (range) {
    headers.set("Content-Range", `bytes ${range.offset}-${range.offset + range.length - 1}/${object.size}`);
    return new Response(object.body, { status: 206, headers });
  }
  return new Response(object.body, { headers });
}

type WantedRange = { start: number; end?: number } | { suffix: number };

/** One `bytes=` range; null means "no usable Range header", which RFC 9110 lets us answer with the whole file. */
export function parseRange(header: string | null): WantedRange | null {
  const m = header?.trim().match(/^bytes=(\d*)-(\d*)$/);
  if (!m || (m[1] === "" && m[2] === "")) return null;
  if (m[1] === "") return { suffix: Number(m[2]) };
  const start = Number(m[1]);
  if (m[2] === "") return { start };
  const end = Number(m[2]);
  return end < start ? null : { start, end };
}

/** The bytes to send for a file of `size`, or null when nothing of the range exists (416). */
export function resolveRange(r: WantedRange, size: number): { offset: number; length: number } | null {
  if ("suffix" in r) {
    const length = Math.min(r.suffix, size);
    return length > 0 ? { offset: size - length, length } : null;
  }
  if (r.start >= size) return null;
  const end = Math.min(r.end ?? size - 1, size - 1);
  return { offset: r.start, length: end - r.start + 1 };
}

/** R2 returns the object without a body when an `onlyIf` precondition failed. */
function hasBody(object: R2Object): object is R2ObjectBody {
  return "body" in object;
}

function unsatisfiable(size: number): Response {
  return new Response(null, { status: 416, headers: { "Content-Range": `bytes */${size}` } });
}

function notFound(): Response {
  return Response.json({ error: "not found" }, { status: 404 });
}
