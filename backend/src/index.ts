// Price API for the app plus the nightly import (ADR 0005).
//
//   GET  /health                 D1 round trip, for uptime checks
//   GET  /v1/prices/:tcgdexId    prices of one card
//   POST /v1/prices {ids: [...]} prices of up to 100 cards, e.g. to refresh a collection
//
// Every route is rate-limited per client IP (#30). The cron and the queue consumer run the nightly import.

import { contextFromEnv, type ImportMessage } from "./import/context";
import { consumeBatch, runScheduled } from "./nightly";
import { getPrices, isCardId, MAX_BATCH } from "./prices";

/** Prices change once a day: clients and Cloudflare's edge cache both keep a single-card answer for an hour. */
const CACHE_ONE_CARD = "public, max-age=3600";

/** Generous for 100 ids of ~20 characters; bigger bodies are not read at all. */
const MAX_BODY_BYTES = 16 * 1024;

export default {
  async fetch(request, env, ctx): Promise<Response> {
    try {
      return await route(request, env, ctx);
    } catch (e) {
      // Keep the API contract (JSON errors) instead of Cloudflare's HTML error page.
      console.error("unhandled", e);
      return error(500, "internal error");
    }
  },

  async scheduled(controller, env, ctx): Promise<void> {
    ctx.waitUntil(runScheduled(controller.cron, contextFromEnv(env)));
  },

  async queue(batch, env): Promise<void> {
    await consumeBatch(batch, contextFromEnv(env));
  },
} satisfies ExportedHandler<Env, ImportMessage>;

async function route(request: Request, env: Env, ctx: ExecutionContext): Promise<Response> {
  const { pathname } = new URL(request.url);

  // Cloudflare sets CF-Connecting-IP on every request that reaches the Worker from the internet.
  const client = request.headers.get("CF-Connecting-IP") ?? "unknown";
  const { success } = await env.API_RATE_LIMIT.limit({ key: client });
  if (!success) return json({ error: "too many requests" }, { "Retry-After": "60" }, 429);

  if (pathname === "/health") {
    if (request.method !== "GET") return methodNotAllowed("GET");
    await env.DB.prepare("SELECT 1").first();
    return json({ ok: true });
  }

  if (pathname === "/v1/prices") {
    if (request.method !== "POST") return methodNotAllowed("POST");
    const ids = await readIds(request);
    if (ids instanceof Response) return ids;
    const prices = await getPrices(env.DB, ids);
    return json({
      prices: Object.fromEntries(prices),
      missing: ids.filter((id) => !prices.has(id)),
    });
  }

  const single = pathname.match(/^\/v1\/prices\/([^/]+)$/);
  if (single) {
    if (request.method !== "GET") return methodNotAllowed("GET");
    // Valid IDs need no percent-decoding, so the raw path segment is checked as is.
    const id = single[1]!;
    if (!isCardId(id)) return error(400, "invalid card id");
    // Cache-Control alone does not make Cloudflare cache a Worker's response; the Cache API does.
    // Keyed by URL only, so request headers cannot split or poison the entry.
    const cacheKey = new Request(request.url);
    const cached = await caches.default.match(cacheKey);
    if (cached) return cached;
    const price = (await getPrices(env.DB, [id])).get(id);
    if (!price) return error(404, "unknown card");
    const response = json(price, { "Cache-Control": CACHE_ONE_CARD });
    ctx.waitUntil(caches.default.put(cacheKey, response.clone()));
    return response;
  }

  return error(404, "not found");
}

/** Parses `{ "ids": [...] }`, dropping duplicates. Returns the error response for bad input. */
async function readIds(request: Request): Promise<string[] | Response> {
  const text = await readText(request, MAX_BODY_BYTES);
  if (text === null) return error(413, "body too large");
  let body: unknown;
  try {
    body = JSON.parse(text);
  } catch {
    return error(400, "body must be JSON");
  }
  const ids = (body as { ids?: unknown } | null)?.ids;
  if (!Array.isArray(ids) || ids.length === 0) return error(400, "ids must be a non-empty array");
  // Counted before dedupe, so a client cannot make the Worker validate an unbounded list.
  if (ids.length > MAX_BATCH) return error(400, `at most ${MAX_BATCH} ids per request`);
  if (!ids.every(isCardId)) return error(400, "invalid card id");
  return [...new Set(ids)];
}

/** The body as text, or null once it exceeds `limit` bytes; stops reading at the limit. */
async function readText(request: Request, limit: number): Promise<string | null> {
  if (Number(request.headers.get("Content-Length") ?? 0) > limit) return null;
  if (!request.body) return "";
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
  return new TextDecoder().decode(bytes);
}

function json(data: unknown, headers: Record<string, string> = {}, status = 200): Response {
  return Response.json(data, { status, headers });
}

function error(status: number, message: string): Response {
  return json({ error: message }, {}, status);
}

function methodNotAllowed(allow: string): Response {
  return json({ error: "method not allowed" }, { Allow: allow }, 405);
}
