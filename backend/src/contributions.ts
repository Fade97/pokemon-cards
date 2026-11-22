// Opt-in scan contributions (#14, ADR 0016): pictures to train and test recognition, never published.
//
//   POST   /v1/contributions?cardId=<tcgdex id>&lang=<code>&consent=<version>[&id=<uuid>]   body: the rectified
//          crop (image/jpeg or image/webp, at most 256 KiB, card-shaped)  ->  201 { id, withdrawToken }
//          Optional `predicted=<tcgdex id>&predictedLang=<code>`: the scanner's top match when the user picked
//          another card (#244). `kind=not_card` (no cardId/lang, consent `debug-1` only) is a scan the user marked as
//          not a card, a negative for training; `predicted` then names the card it was taken for.
//          With `id` the client also sends its own token (Authorization: Bearer <43 chars base64url>); a repeated
//          POST with the same id and token answers 200 with the same values instead of storing a second copy, so a
//          retry after a lost response never leaves a copy the client cannot withdraw.
//   DELETE /v1/contributions/:id   Authorization: Bearer <withdrawToken>  ->  200 { id, withdrawnAt }
//
// Metadata segments (EXIF, XMP, IPTC, comments) are stripped before storing. No IP address or device data is
// stored; the client IP is only used as the in-memory rate-limit key.
//
// Storage is capped twice (#135): per client by CONTRIBUTION_RATE_LIMIT and for everyone by a daily quota
// (CONTRIBUTION_DAILY_QUOTA uploads per UTC day, 503 once it is used up). Every upload starts in quarantine
// (`review = 'pending'`); only an admin's approval (src/contribution-review.ts) lets a training set take it.

import { ImageError, sniffType, stripMetadata } from "./image-meta";
import { isCardId } from "./prices";

/**
 * The app sends its rectified 252 x 352 crop as JPEG quality 90 (CardDetector.CARD_W/H, ScanViewModel.jpeg), which
 * is about 20-60 KB; even quality 100 of a noisy frame stays under 200 KB. 256 KiB leaves headroom for that and for
 * a somewhat larger crop later, and is a quarter of the earlier 1 MiB (#135).
 */
export const MAX_UPLOAD_BYTES = 256 * 1024;
const MIN_SIDE = 64;
const MAX_SIDE = 2048;
/**
 * Short side over long side. A card is 63 x 88 mm (0.716) and the app always rectifies to that; anything far off is
 * not a card crop. Either orientation passes, so a future landscape crop needs no server change.
 */
const CARD_RATIO_MIN = 0.66;
const CARD_RATIO_MAX = 0.78;
/** Uploads per UTC day for everyone together, unless CONTRIBUTION_DAILY_QUOTA says otherwise; 0 pauses uploads. */
export const DEFAULT_DAILY_QUOTA = 500;
/**
 * Consent texts the app may show. A new text gets a new version here before the app ships it. "train-1" (ADR 0016):
 * training and testing only, never published. "cc0-1" came from debug builds before it; those pictures are used the
 * same narrower way.
 */
export const CONSENT_VERSIONS: ReadonlySet<string> = new Set(["train-1", "cc0-1"]);
/**
 * Consent for not-a-card scans (#244). Such a crop may show anything in a home, which "train-1" doesn't cover, so
 * for now only debug builds send them, under this version; a release needs its own consent text first.
 */
export const NOT_CARD_CONSENT_VERSIONS: ReadonlySet<string> = new Set(["debug-1"]);
export type ContributionKind = "card" | "not_card";
const LANG = /^[a-z]{2}(?:-[a-z]{2})?$/;
const ID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
/** A claim this old without its image is taken to be abandoned; a retry with the same token may take it over. */
const STALE_CLAIM_MS = 2 * 60 * 1000;
/** 32 random bytes as base64url, like the tokens the server makes. */
const TOKEN = /^[A-Za-z0-9_-]{43}$/;

export async function handleContributions(
  request: Request,
  env: Env,
  ctx: ExecutionContext,
  pathname: string,
  client: string,
) {
  if (pathname === "/v1/contributions") {
    if (request.method !== "POST") return methodNotAllowed("POST");
    // Counted before the body is read, so a client over the limit costs almost nothing.
    const { success } = await env.CONTRIBUTION_RATE_LIMIT.limit({ key: client });
    if (!success) return json({ error: "too many uploads" }, 429, { "Retry-After": "60" });
    return create(request, env, ctx);
  }
  const m = pathname.match(/^\/v1\/contributions\/([^/]+)$/);
  if (m) {
    if (request.method !== "DELETE") return methodNotAllowed("DELETE");
    return withdraw(request, env, m[1]!);
  }
  return json({ error: "not found" }, 404);
}

async function create(request: Request, env: Env, ctx: ExecutionContext): Promise<Response> {
  const q = new URL(request.url).searchParams;
  const kind = q.get("kind") ?? "card";
  const consent = q.get("consent");
  let cardId: string;
  let lang: string;
  if (kind === "card") {
    const c = q.get("cardId");
    const l = q.get("lang");
    if (!isCardId(c)) return json({ error: "invalid cardId" }, 400);
    if (!l || !LANG.test(l)) return json({ error: "invalid lang" }, 400);
    if (!consent || !CONSENT_VERSIONS.has(consent)) return json({ error: "unknown consent version" }, 400);
    cardId = c;
    lang = l;
  } else if (kind === "not_card") {
    if (q.has("cardId") || q.has("lang")) return json({ error: "a not_card scan has no cardId or lang" }, 400);
    if (!consent || !NOT_CARD_CONSENT_VERSIONS.has(consent)) return json({ error: "unknown consent version" }, 400);
    cardId = "";
    lang = "";
  } else {
    return json({ error: "kind must be card or not_card" }, 400);
  }
  const predictedCardId = q.get("predicted");
  const predictedLang = q.get("predictedLang");
  if ((predictedCardId === null) !== (predictedLang === null)) {
    return json({ error: "predicted and predictedLang go together" }, 400);
  }
  if (predictedCardId !== null && !isCardId(predictedCardId)) return json({ error: "invalid predicted" }, 400);
  if (predictedLang !== null && !LANG.test(predictedLang)) return json({ error: "invalid predictedLang" }, 400);

  // Client-chosen id and token make the upload idempotent; without them the server picks both.
  const clientId = q.get("id");
  let id: string;
  let token: string;
  if (clientId !== null) {
    const bearer = bearerToken(request);
    if (!ID.test(clientId)) return json({ error: "invalid id" }, 400);
    if (!TOKEN.test(bearer)) return json({ error: "id needs a 43-character withdraw token" }, 400);
    const existing = await existingUpload(env, clientId, bearer);
    if (existing) return existing;
    id = clientId;
    token = bearer;
  } else {
    id = crypto.randomUUID();
    token = randomToken();
  }
  // Checked before the body is read, so a full day costs one query per request. A retry of an upload that is
  // already stored was answered above and never counts. The claim below checks again in the same statement.
  const quota = dailyQuota(env);
  if ((await uploadsToday(env)) >= quota) return quotaReached();

  const declared = (request.headers.get("Content-Type") ?? "").split(";")[0]!.trim().toLowerCase();
  if (declared !== "image/jpeg" && declared !== "image/webp") {
    return json({ error: "body must be image/jpeg or image/webp" }, 415);
  }
  const raw = await readBytes(request, MAX_UPLOAD_BYTES);
  if (raw === null) return json({ error: `image larger than ${MAX_UPLOAD_BYTES} bytes` }, 413);
  if (sniffType(raw) !== declared) return json({ error: `body is not ${declared}` }, 415);

  let clean;
  try {
    clean = stripMetadata(raw, declared);
  } catch (e) {
    if (e instanceof ImageError) return json({ error: `invalid image: ${e.message}` }, 422);
    throw e;
  }
  const { width, height } = clean;
  if (Math.min(width, height) < MIN_SIDE || Math.max(width, height) > MAX_SIDE) {
    return json({ error: `image must be ${MIN_SIDE}-${MAX_SIDE} px per side, got ${width}x${height}` }, 422);
  }
  const ratio = Math.min(width, height) / Math.max(width, height);
  if (ratio < CARD_RATIO_MIN || ratio > CARD_RATIO_MAX) {
    return json({ error: `image is not card-shaped (63:88), got ${width}x${height}` }, 422);
  }

  // One key per id, whatever the type: every writer of this id (an original upload, a takeover) and every
  // withdrawal hit the same object, so none can leave an image behind under another name.
  const key = `scans/${id}`;
  const tokenHash = await sha256Hex(new TextEncoder().encode(token));
  const sha256 = await sha256Hex(clean.bytes);
  // The row claims the id before the image is written, so of two requests with one id only one writes, and a
  // withdrawal (or its tombstone, see withdraw) always wins over an upload still on its way.
  // claim → write → re-check runs under waitUntil: a client that gives up mid-request must not stop the Worker
  // between the steps (a claimed row without its image, or a withdrawn row whose image stays).
  const store = storeClaimed(env, {
    id, token, tokenHash, key, kind, cardId, lang, predictedCardId, predictedLang, consent, declared, clean, width,
    height, sha256, quota,
  });
  ctx.waitUntil(store.catch(() => {}));
  return store;
}

interface Claim {
  id: string;
  token: string;
  tokenHash: string;
  key: string;
  kind: ContributionKind;
  /** '' for a not_card scan. */
  cardId: string;
  lang: string;
  predictedCardId: string | null;
  predictedLang: string | null;
  consent: string;
  declared: "image/jpeg" | "image/webp";
  clean: { bytes: Uint8Array<ArrayBuffer> };
  width: number;
  height: number;
  sha256: string;
  /** The daily quota; the claim is only placed while today's uploads are below it. */
  quota: number;
}

async function storeClaimed(env: Env, c: Claim): Promise<Response> {
  const { id, token, tokenHash, key, kind, cardId, lang, predictedCardId, predictedLang, consent, declared, clean, width,
    height, sha256, quota } = c;
  const now = new Date();
  // The claim time doubles as this attempt's id: cleanup and "stored" below only touch the row while it still
  // carries it, so an attempt whose claim was taken over can't delete or complete the new owner's row.
  const claimAt = now.toISOString();
  const values = [
    kind, cardId, lang, predictedCardId, predictedLang, consent, key, declared, clean.bytes.length, width, height, sha256,
    claimAt,
  ];
  // The quota is checked in the claim itself, so concurrent uploads can't take the day past it. New rows start in
  // quarantine (`review` defaults to 'pending', migration 0003).
  const claimed = await env.DB.prepare(
    `INSERT INTO contributions (id, token_hash, kind, card_id, lang, predicted_card_id, predicted_lang, consent_version,
       object_key, content_type, size, width, height, sha256, created_at)
     SELECT ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
     WHERE (SELECT COUNT(*) FROM contributions WHERE created_at >= ? AND object_key <> '') < ?
     ON CONFLICT (id) DO NOTHING`,
  )
    .bind(id, tokenHash, ...values, dayStart(now), quota)
    .run();
  if (claimed.meta.changes === 0) {
    // Nothing inserted and no row with this id: the quota was used up since the check in create().
    if (!(await env.DB.prepare("SELECT 1 FROM contributions WHERE id = ?").bind(id).first())) return quotaReached();
    // A claim by the same client whose upload never finished (the Worker died, the write failed and its cleanup
    // too) is taken over once it is old enough; anything else answers as an existing upload.
    const takenOver = await env.DB.prepare(
      `UPDATE contributions SET kind = ?, card_id = ?, lang = ?, predicted_card_id = ?, predicted_lang = ?,
         consent_version = ?, object_key = ?, content_type = ?, size = ?, width = ?, height = ?, sha256 = ?, created_at = ?
       WHERE id = ? AND token_hash = ? AND stored_at IS NULL AND withdrawn_at IS NULL AND created_at < ?`,
    )
      .bind(...values, id, tokenHash, new Date(now.getTime() - STALE_CLAIM_MS).toISOString())
      .run();
    if (takenOver.meta.changes === 0) {
      return (await existingUpload(env, id, token)) ?? json({ error: "id already used" }, 409);
    }
  }
  try {
    await env.CONTRIBUTIONS.put(key, clean.bytes, { httpMetadata: { contentType: declared }, sha256 });
  } catch (e) {
    // No record without its image; a withdrawal that came in meanwhile keeps its row.
    await env.DB.prepare("DELETE FROM contributions WHERE id = ? AND withdrawn_at IS NULL AND created_at = ?")
      .bind(id, claimAt)
      .run();
    throw e;
  }
  // withdraw() marks before it deletes the image. So either the mark is already visible here and this request
  // deletes the image, or the mark comes later and the withdrawal's own delete removes the image written above.
  const after = await env.DB.prepare("SELECT withdrawn_at FROM contributions WHERE id = ?")
    .bind(id)
    .first<{ withdrawn_at: string | null }>();
  if (after?.withdrawn_at) {
    await env.CONTRIBUTIONS.delete(key);
    return json({ id, withdrawToken: token, withdrawnAt: after.withdrawn_at }, 200);
  }
  await env.DB.prepare(
    "UPDATE contributions SET stored_at = ? WHERE id = ? AND withdrawn_at IS NULL AND created_at = ?",
  )
    .bind(new Date().toISOString(), id, claimAt)
    .run();
  return json({ id, withdrawToken: token }, 201);
}

/**
 * A withdrawal for an id the server doesn't know (yet). With client-chosen ids the upload may still be on its way
 * (the app gave up waiting and the user took it back), so a withdrawn record is left: when that upload arrives, it
 * finds the id taken and stores nothing. The answer stays 404, so ids cannot be probed. Tombstones have no card
 * data, and dataset releases skip them like every withdrawn row.
 */
async function tombstone(request: Request, env: Env, id: string, token: string): Promise<Response> {
  const notFound = json({ error: "not found" }, 404);
  if (!TOKEN.test(token)) return notFound; // server-made ids are never unknown to the server
  // A tombstone is a write, so it counts against the upload limit.
  const client = request.headers.get("CF-Connecting-IP") ?? "unknown";
  if (!(await env.CONTRIBUTION_RATE_LIMIT.limit({ key: client })).success) {
    return json({ error: "too many requests" }, 429, { "Retry-After": "60" });
  }
  const now = new Date().toISOString();
  const placed = await env.DB.prepare(
    `INSERT INTO contributions (id, token_hash, card_id, lang, consent_version, object_key, content_type, size,
       width, height, sha256, created_at, withdrawn_at)
     VALUES (?, ?, '', '', '', '', '', 0, 0, 0, '', ?, ?)
     ON CONFLICT (id) DO NOTHING`,
  )
    .bind(id, await sha256Hex(new TextEncoder().encode(token)), now, now)
    .run();
  // The upload claimed the id a moment ago: withdraw it the normal way.
  if (placed.meta.changes === 0) return withdraw(request, env, id);
  return notFound;
}

/** The answer for an id that is already stored: 200 with the same values for its owner, 409 for anyone else. */
async function existingUpload(env: Env, id: string, token: string): Promise<Response | null> {
  const row = await env.DB.prepare("SELECT token_hash, withdrawn_at, stored_at, created_at FROM contributions WHERE id = ?")
    .bind(id)
    .first<{ token_hash: string; withdrawn_at: string | null; stored_at: string | null; created_at: string }>();
  if (!row) return null;
  if (row.token_hash !== (await sha256Hex(new TextEncoder().encode(token)))) {
    return json({ error: "id already used" }, 409);
  }
  if (row.withdrawn_at) return json({ id, withdrawToken: token, withdrawnAt: row.withdrawn_at }, 200);
  if (!row.stored_at) {
    // Only claimed so far. A stuck claim may be taken over by this retry (see storeClaimed); a recent one is
    // still being written, so the client is told to come back rather than that it succeeded.
    if (Date.now() - Date.parse(row.created_at) >= STALE_CLAIM_MS) return null;
    return json({ error: "upload in progress" }, 503, { "Retry-After": "30" });
  }
  return json({ id, withdrawToken: token }, 200);
}

/** CONTRIBUTION_DAILY_QUOTA as a whole number, or the default when it is unset or not a number. */
export function dailyQuota(env: Env): number {
  const raw = String(env.CONTRIBUTION_DAILY_QUOTA ?? "").trim();
  return /^\d+$/.test(raw) ? Number(raw) : DEFAULT_DAILY_QUOTA;
}

/** Midnight UTC of `now`'s day, as the ISO string `created_at` is compared with. */
function dayStart(now: Date): string {
  return `${now.toISOString().slice(0, 10)}T00:00:00.000Z`;
}

/** Uploads claimed today (UTC), withdrawn ones included: they took the writes. Tombstones have no key and don't count. */
async function uploadsToday(env: Env): Promise<number> {
  const row = await env.DB.prepare("SELECT COUNT(*) AS n FROM contributions WHERE created_at >= ? AND object_key <> ''")
    .bind(dayStart(new Date()))
    .first<{ n: number }>();
  return row?.n ?? 0;
}

/**
 * 503, not 429: the app waits out a 429 inside its upload job (seconds), while a 503 ends the run and lets the
 * system schedule the next one. Retry-After points at the next UTC midnight.
 */
function quotaReached(): Response {
  const now = new Date();
  const next = Date.parse(dayStart(now)) + 24 * 60 * 60 * 1000;
  const seconds = Math.max(1, Math.ceil((next - now.getTime()) / 1000));
  return json({ error: "daily upload quota reached" }, 503, { "Retry-After": String(seconds) });
}

function bearerToken(request: Request): string {
  const auth = request.headers.get("Authorization") ?? "";
  return auth.startsWith("Bearer ") ? auth.slice(7).trim() : "";
}

async function withdraw(request: Request, env: Env, id: string): Promise<Response> {
  const token = bearerToken(request);
  if (!token) return json({ error: "withdraw token required" }, 401, { "WWW-Authenticate": "Bearer" });
  // Unknown id and wrong token answer the same, so ids cannot be probed.
  if (!ID.test(id)) return json({ error: "not found" }, 404);
  const row = await env.DB.prepare(
    "SELECT token_hash, object_key, withdrawn_at FROM contributions WHERE id = ?",
  )
    .bind(id)
    .first<{ token_hash: string; object_key: string; withdrawn_at: string | null }>();
  if (!row) return tombstone(request, env, id, token);
  if (row.token_hash !== (await sha256Hex(new TextEncoder().encode(token)))) {
    return json({ error: "not found" }, 404);
  }
  // The mark goes first, then the image: an upload of this id that is still writing either sees the mark after
  // its write and deletes the image itself, or wrote before the mark and loses the image to the delete below.
  // The mark returns the row as it is now, so the key deleted is the current one. A repeated withdrawal keeps
  // the first time and deletes again, so a delete that failed once is retried. The row stays, marked, so dataset
  // releases skip it.
  const marked = await env.DB.prepare(
    `UPDATE contributions SET withdrawn_at = COALESCE(withdrawn_at, ?) WHERE id = ?
     RETURNING withdrawn_at, object_key`,
  )
    .bind(new Date().toISOString(), id)
    .first<{ withdrawn_at: string; object_key: string }>();
  // The row went away since it was read (a failed upload cleaned up its claim): leave a tombstone instead.
  if (!marked) return tombstone(request, env, id, token);
  if (marked.object_key) await env.CONTRIBUTIONS.delete(marked.object_key);
  return json({ id, withdrawnAt: marked.withdrawn_at });
}

/** The body, or null once it exceeds `limit` bytes; stops reading at the limit. */
async function readBytes(request: Request, limit: number): Promise<Uint8Array | null> {
  if (Number(request.headers.get("Content-Length") ?? 0) > limit) return null;
  if (!request.body) return new Uint8Array();
  const reader = request.body.getReader();
  const chunks: Uint8Array[] = [];
  let size = 0;
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    size += value.byteLength;
    if (size > limit) {
      await reader.cancel();
      return null;
    }
    chunks.push(value);
  }
  const bytes = new Uint8Array(size);
  let offset = 0;
  for (const c of chunks) {
    bytes.set(c, offset);
    offset += c.byteLength;
  }
  return bytes;
}

/** 32 random bytes as base64url (43 characters). */
function randomToken(): string {
  const b = crypto.getRandomValues(new Uint8Array(32));
  return btoa(String.fromCharCode(...b)).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

async function sha256Hex(data: Uint8Array): Promise<string> {
  // Both callers pass ArrayBuffer-backed arrays (TextEncoder output, stripMetadata output).
  const digest = await crypto.subtle.digest("SHA-256", data as Uint8Array<ArrayBuffer>);
  return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

function json(data: unknown, status = 200, headers: Record<string, string> = {}): Response {
  return Response.json(data, { status, headers });
}

function methodNotAllowed(allow: string): Response {
  return json({ error: "method not allowed" }, 405, { Allow: allow });
}
