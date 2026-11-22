// Review of scan contributions before training (#135, ADR 0016). Every upload lands in quarantine
// (`review = 'pending'`); a training set takes only rows an admin approved.
//
//   GET  /v1/admin/contributions[?review=pending|approved|rejected][&kind=card|not_card][&limit=1-200][&offset=n]
//        -> 200 { contributions: [{ id, kind, cardId, lang, predictedCardId, predictedLang, consent, contentType, size,
//                 width, height, createdAt, review, reviewedAt }] }
//        Stored, not withdrawn rows, oldest first; `review` defaults to pending, `kind` to both (#244).
//   GET  /v1/admin/contributions/:id/image   -> the stored crop (not for rejected rows: their image is gone)
//   POST /v1/admin/contributions/:id/approve -> 200 { id, review, reviewedAt }
//   POST /v1/admin/contributions/:id/reject  -> 200 { id, review, reviewedAt }; the image is deleted, the row stays
//
// Authorization: Bearer <admin token>. The Worker secret CONTRIBUTION_ADMIN_TOKENS lists the SHA-256 hex digests
// of the admin tokens. Without the secret, or with a missing or wrong token, every admin path answers 404, so the
// routes are neither usable nor visible on a deployment that hasn't set one up.

import { sha256Hex, tokenDigests } from "./entitlements";

const ID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
/** Same shape as Pro test tokens: URL-safe, long enough not to be guessed. */
const TOKEN = /^Bearer ([A-Za-z0-9_-]{32,128})$/;
const REVIEWS = new Set(["pending", "approved", "rejected"]);
const KINDS = new Set(["card", "not_card"]);
type Decision = "approved" | "rejected";

export async function handleContributionReview(request: Request, env: Env, pathname: string): Promise<Response> {
  if (!(await isAdmin(request, env))) return json({ error: "not found" }, 404);

  if (pathname === "/v1/admin/contributions") {
    if (request.method !== "GET") return methodNotAllowed("GET");
    return list(request, env);
  }
  const image = pathname.match(/^\/v1\/admin\/contributions\/([^/]+)\/image$/);
  if (image) {
    if (request.method !== "GET") return methodNotAllowed("GET");
    return serveImage(env, image[1]!);
  }
  const decision = pathname.match(/^\/v1\/admin\/contributions\/([^/]+)\/(approve|reject)$/);
  if (decision) {
    if (request.method !== "POST") return methodNotAllowed("POST");
    return decide(env, decision[1]!, decision[2] === "approve" ? "approved" : "rejected");
  }
  return json({ error: "not found" }, 404);
}

async function isAdmin(request: Request, env: Env): Promise<boolean> {
  const digests = tokenDigests(env.CONTRIBUTION_ADMIN_TOKENS);
  if (digests.size === 0) return false;
  const token = request.headers.get("Authorization")?.match(TOKEN)?.[1];
  return token !== undefined && digests.has(await sha256Hex(token));
}

interface Row {
  id: string;
  kind: string;
  card_id: string;
  lang: string;
  predicted_card_id: string | null;
  predicted_lang: string | null;
  consent_version: string;
  content_type: string;
  size: number;
  width: number;
  height: number;
  created_at: string;
  review: string;
  reviewed_at: string | null;
}

async function list(request: Request, env: Env): Promise<Response> {
  const q = new URL(request.url).searchParams;
  const review = q.get("review") ?? "pending";
  if (!REVIEWS.has(review)) return json({ error: "review must be pending, approved or rejected" }, 400);
  const kind = q.get("kind");
  if (kind !== null && !KINDS.has(kind)) return json({ error: "kind must be card or not_card" }, 400);
  const limit = int(q.get("limit"), 50);
  const offset = int(q.get("offset"), 0);
  if (limit === null || limit < 1 || limit > 200) return json({ error: "limit must be 1-200" }, 400);
  if (offset === null) return json({ error: "offset must be a whole number" }, 400);
  const { results } = await env.DB.prepare(
    `SELECT id, kind, card_id, lang, predicted_card_id, predicted_lang, consent_version, content_type, size, width,
       height, created_at, review, reviewed_at FROM contributions
     WHERE review = ?1 AND (?2 IS NULL OR kind = ?2) AND stored_at IS NOT NULL AND withdrawn_at IS NULL
     ORDER BY created_at, id LIMIT ?3 OFFSET ?4`,
  )
    .bind(review, kind, limit, offset)
    .all<Row>();
  return json({
    contributions: results.map((r) => ({
      id: r.id, kind: r.kind, cardId: r.card_id, lang: r.lang, predictedCardId: r.predicted_card_id,
      predictedLang: r.predicted_lang, consent: r.consent_version, contentType: r.content_type, size: r.size, width: r.width,
      height: r.height, createdAt: r.created_at, review: r.review, reviewedAt: r.reviewed_at,
    })),
  });
}

async function serveImage(env: Env, id: string): Promise<Response> {
  if (!ID.test(id)) return json({ error: "not found" }, 404);
  const row = await env.DB.prepare(
    `SELECT object_key, content_type FROM contributions
     WHERE id = ? AND stored_at IS NOT NULL AND withdrawn_at IS NULL AND review <> 'rejected'`,
  )
    .bind(id)
    .first<{ object_key: string; content_type: string }>();
  const obj = row ? await env.CONTRIBUTIONS.get(row.object_key) : null;
  if (!row || !obj) return json({ error: "not found" }, 404);
  return new Response(obj.body, {
    headers: {
      "Content-Type": row.content_type,
      "Cache-Control": "private, no-store",
      "X-Content-Type-Options": "nosniff",
      "Content-Security-Policy": "default-src 'none'",
    },
  });
}

/**
 * pending -> approved or rejected, and approved -> rejected (a later look can still take a scan out of future
 * releases). A rejected scan can't be approved again: its image is deleted. Repeating a decision answers the same
 * and, for a rejection, deletes the image again, so a delete that failed once is retried.
 */
async function decide(env: Env, id: string, decision: Decision): Promise<Response> {
  if (!ID.test(id)) return json({ error: "not found" }, 404);
  // Only stored rows: an upload still writing (stored_at IS NULL) could otherwise put its image back after a
  // rejection deleted it. A withdrawal wins over both decisions; the export checks withdrawn_at anyway.
  const changed = await env.DB.prepare(
    `UPDATE contributions SET review = ?1, reviewed_at = ?2
     WHERE id = ?3 AND stored_at IS NOT NULL AND withdrawn_at IS NULL
       AND (review = 'pending' OR (review = 'approved' AND ?1 = 'rejected'))
     RETURNING object_key, reviewed_at`,
  )
    .bind(decision, new Date().toISOString(), id)
    .first<{ object_key: string; reviewed_at: string }>();
  if (changed) {
    if (decision === "rejected") await env.CONTRIBUTIONS.delete(changed.object_key);
    return json({ id, review: decision, reviewedAt: changed.reviewed_at });
  }
  const row = await env.DB.prepare(
    "SELECT object_key, stored_at, withdrawn_at, review, reviewed_at FROM contributions WHERE id = ?",
  )
    .bind(id)
    .first<{ object_key: string; stored_at: string | null; withdrawn_at: string | null; review: string; reviewed_at: string | null }>();
  if (!row || !row.object_key) return json({ error: "not found" }, 404); // unknown, or a tombstone
  if (row.withdrawn_at) return json({ error: "withdrawn by the contributor" }, 409);
  if (!row.stored_at) return json({ error: "upload not finished" }, 409);
  if (row.review === decision) {
    if (decision === "rejected") await env.CONTRIBUTIONS.delete(row.object_key);
    return json({ id, review: decision, reviewedAt: row.reviewed_at });
  }
  return json({ error: "rejected scans can't be approved: the image is deleted" }, 409);
}

function int(value: string | null, fallback: number): number | null {
  if (value === null) return fallback;
  return /^\d{1,9}$/.test(value) ? Number(value) : null;
}

function json(data: unknown, status = 200, headers: Record<string, string> = {}): Response {
  return Response.json(data, { status, headers });
}

function methodNotAllowed(allow: string): Response {
  return json({ error: "method not allowed" }, 405, { Allow: allow });
}
