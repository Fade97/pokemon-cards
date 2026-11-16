// Price API for the app plus the nightly import trigger (ADR 0005).
//
//   GET  /health                 D1 round trip, for uptime checks
//   GET  /v1/prices/:tcgdexId    prices of one card
//   POST /v1/prices {ids: [...]} prices of up to 100 cards, e.g. to refresh a collection

import { runNightlyImport } from "./nightly";
import { getPrices, isCardId, MAX_BATCH } from "./prices";

/** Prices change once a day, so clients and the edge may reuse a single-card answer for an hour. */
const CACHE_ONE_CARD = "public, max-age=3600";

export default {
  async fetch(request, env): Promise<Response> {
    const { pathname } = new URL(request.url);

    if (pathname === "/health") {
      if (request.method !== "GET") return methodNotAllowed("GET");
      await env.DB.prepare("SELECT 1").first();
      return json({ ok: true });
    }

    if (pathname === "/v1/prices") {
      if (request.method !== "POST") return methodNotAllowed("POST");
      const ids = await readIds(request);
      if (typeof ids === "string") return error(400, ids);
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
      const price = (await getPrices(env.DB, [id])).get(id);
      if (!price) return error(404, "unknown card");
      return json(price, { "Cache-Control": CACHE_ONE_CARD });
    }

    return error(404, "not found");
  },

  async scheduled(_controller, env, ctx): Promise<void> {
    ctx.waitUntil(runNightlyImport(env));
  },
} satisfies ExportedHandler<Env>;

/** Parses `{ "ids": [...] }`, dropping duplicates. Returns an error message for bad input. */
async function readIds(request: Request): Promise<string[] | string> {
  let body: unknown;
  try {
    body = await request.json();
  } catch {
    return "body must be JSON";
  }
  const ids = (body as { ids?: unknown } | null)?.ids;
  if (!Array.isArray(ids) || ids.length === 0) return "ids must be a non-empty array";
  if (!ids.every(isCardId)) return "invalid card id";
  const unique = [...new Set(ids)];
  if (unique.length > MAX_BATCH) return `at most ${MAX_BATCH} ids per request`;
  return unique;
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
