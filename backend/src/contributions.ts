// Opt-in scan contributions (#14, ADR 0008).
//
//   POST   /v1/contributions?cardId=<tcgdex id>&lang=<code>&consent=<version>[&id=<uuid>]   body: the rectified
//          crop (image/jpeg or image/webp, at most 1 MiB)  ->  201 { id, withdrawToken }
//          With `id` the client also sends its own token (Authorization: Bearer <43 chars base64url>); a repeated
//          POST with the same id and token answers 200 with the same values instead of storing a second copy, so a
//          retry after a lost response never leaves a copy the client cannot withdraw.
//   DELETE /v1/contributions/:id   Authorization: Bearer <withdrawToken>  ->  200 { id, withdrawnAt }
//
// Metadata segments (EXIF, XMP, IPTC, comments) are stripped before storing. No IP address or device data is
// stored; the client IP is only used as the in-memory rate-limit key.

import { ImageError, sniffType, stripMetadata } from "./image-meta";
import { isCardId } from "./prices";

/** A rectified crop is a few hundred pixels per side; 1 MiB leaves room for high JPEG quality. */
export const MAX_UPLOAD_BYTES = 1024 * 1024;
const MIN_SIDE = 64;
const MAX_SIDE = 2048;
/** Consent texts the app may show. A new text gets a new version here before the app ships it. */
export const CONSENT_VERSIONS: ReadonlySet<string> = new Set(["cc0-1"]);
const LANG = /^[a-z]{2}(?:-[a-z]{2})?$/;
const ID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
/** 32 random bytes as base64url, like the tokens the server makes. */
const TOKEN = /^[A-Za-z0-9_-]{43}$/;
const EXT = { "image/jpeg": "jpg", "image/webp": "webp" } as const;

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
  const cardId = q.get("cardId");
  const lang = q.get("lang");
  const consent = q.get("consent");
  if (!isCardId(cardId)) return json({ error: "invalid cardId" }, 400);
  if (!lang || !LANG.test(lang)) return json({ error: "invalid lang" }, 400);
  if (!consent || !CONSENT_VERSIONS.has(consent)) return json({ error: "unknown consent version" }, 400);

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

  const key = `scans/${id}.${EXT[declared]}`;
  const tokenHash = await sha256Hex(new TextEncoder().encode(token));
  const sha256 = await sha256Hex(clean.bytes);
  // The row claims the id before the image is written, so of two requests with one id only one writes, and a
  // withdrawal (or its tombstone, see withdraw) always wins over an upload still on its way.
  // claim → write → re-check runs under waitUntil: a client that gives up mid-request must not stop the Worker
  // between the steps (a claimed row without its image, or a withdrawn row whose image stays).
  const store = storeClaimed(env, { id, token, tokenHash, key, cardId, lang, consent, declared, clean, width, height, sha256 });
  ctx.waitUntil(store.catch(() => {}));
  return store;
}

interface Claim {
  id: string;
  token: string;
  tokenHash: string;
  key: string;
  cardId: string;
  lang: string;
  consent: string;
  declared: keyof typeof EXT;
  clean: { bytes: Uint8Array<ArrayBuffer> };
  width: number;
  height: number;
  sha256: string;
}

async function storeClaimed(env: Env, c: Claim): Promise<Response> {
  const { id, token, tokenHash, key, cardId, lang, consent, declared, clean, width, height, sha256 } = c;
  const claimed = await env.DB.prepare(
    `INSERT INTO contributions (id, token_hash, card_id, lang, consent_version, object_key, content_type, size,
       width, height, sha256, created_at)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
     ON CONFLICT (id) DO NOTHING`,
  )
    .bind(id, tokenHash, cardId, lang, consent, key, declared, clean.bytes.length, width, height, sha256,
      new Date().toISOString())
    .run();
  if (claimed.meta.changes === 0) {
    return (await existingUpload(env, id, token)) ?? json({ error: "id already used" }, 409);
  }
  try {
    await env.CONTRIBUTIONS.put(key, clean.bytes, { httpMetadata: { contentType: declared }, sha256 });
  } catch (e) {
    // No record without its image; a withdrawal that came in meanwhile keeps its row.
    await env.DB.prepare("DELETE FROM contributions WHERE id = ? AND withdrawn_at IS NULL").bind(id).run();
    throw e;
  }
  // A withdrawal between the claim and the write found no image to delete; delete it now.
  const after = await env.DB.prepare("SELECT withdrawn_at FROM contributions WHERE id = ?")
    .bind(id)
    .first<{ withdrawn_at: string | null }>();
  if (after?.withdrawn_at) {
    await env.CONTRIBUTIONS.delete(key);
    return json({ id, withdrawToken: token, withdrawnAt: after.withdrawn_at }, 200);
  }
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
  const row = await env.DB.prepare("SELECT token_hash, withdrawn_at FROM contributions WHERE id = ?")
    .bind(id)
    .first<{ token_hash: string; withdrawn_at: string | null }>();
  if (!row) return null;
  if (row.token_hash !== (await sha256Hex(new TextEncoder().encode(token)))) {
    return json({ error: "id already used" }, 409);
  }
  return json({ id, withdrawToken: token, ...(row.withdrawn_at ? { withdrawnAt: row.withdrawn_at } : {}) }, 200);
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
  if (row.withdrawn_at) return json({ id, withdrawnAt: row.withdrawn_at });
  const now = new Date().toISOString();
  // The image goes first: if marking fails, a retry deletes again (a no-op) and marks then. The row stays, marked,
  // so dataset releases can skip it.
  await env.CONTRIBUTIONS.delete(row.object_key);
  await env.DB.prepare("UPDATE contributions SET withdrawn_at = ? WHERE id = ? AND withdrawn_at IS NULL")
    .bind(now, id)
    .run();
  return json({ id, withdrawnAt: now });
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
