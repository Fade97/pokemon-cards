// Price history for Pro (#17): the per-set, per-month files the importer derives from the daily snapshots
// (importer/history.ts, format in price-data.ts).
//
//   GET /v1/history/<YYYY-MM>?sets=sv03.5,sv08   Authorization: Bearer <token>
//   → { "month": "2026-11", "sets": { "sv03.5": <PriceHistoryFile>, "sv08": null } }
//
// A set without a file for that month is null. The Worker never parses the files: it writes the envelope and streams
// each R2 object into it, so the cost doesn't grow with file size and a whole collection's month is one request.

import { entitlements } from "./entitlements";
import { historyKey, isMonth, isShard } from "./price-data";

/** Sets per request. Each is one R2 read; a collection spans a few dozen sets at most. */
export const MAX_HISTORY_SETS = 20;

/** Past months never change and the current one once a night; the answer depends on the caller's token. */
const CACHE_HISTORY = "private, max-age=3600";

export async function handleHistory(
  request: Request,
  env: Env,
  ctx: ExecutionContext,
  pathname: string,
): Promise<Response> {
  if (request.method !== "GET") return json({ error: "method not allowed" }, 405, { Allow: "GET" });

  const entitlement = await entitlements(env).check(request);
  if (entitlement === "anonymous") return json({ error: "sign-in required" }, 401, { "WWW-Authenticate": "Bearer" });
  if (entitlement !== "pro") return json({ error: "pro required" }, 403);

  const month = pathname.slice("/v1/history/".length);
  if (!isMonth(month)) return json({ error: "month must be YYYY-MM" }, 400);
  const param = new URL(request.url).searchParams.get("sets") ?? "";
  const sets = [...new Set(param.split(",").filter(Boolean))];
  if (sets.length === 0) return json({ error: "sets must list at least one set" }, 400);
  if (sets.length > MAX_HISTORY_SETS) return json({ error: `at most ${MAX_HISTORY_SETS} sets per request` }, 400);
  if (!sets.every(isShard)) return json({ error: "invalid set id" }, 400);

  const { readable, writable } = new TransformStream<Uint8Array, Uint8Array>();
  ctx.waitUntil(writeEnvelope(writable, env.PRICES, month, sets));
  return new Response(readable, {
    headers: { "Content-Type": "application/json", "Cache-Control": CACHE_HISTORY, Vary: "Authorization" },
  });
}

async function writeEnvelope(writable: WritableStream<Uint8Array>, bucket: R2Bucket, month: string, sets: string[]) {
  const writer = writable.getWriter();
  const encoder = new TextEncoder();
  const write = (text: string) => writer.write(encoder.encode(text));
  try {
    await write(`{"month":${JSON.stringify(month)},"sets":{`);
    for (const [i, set] of sets.entries()) {
      await write(`${i ? "," : ""}${JSON.stringify(set)}:`);
      const object = await bucket.get(historyKey(set, month));
      if (!object) {
        await write("null");
        continue;
      }
      const reader = object.body.getReader();
      for (;;) {
        const { done, value } = await reader.read();
        if (done) break;
        await writer.write(value);
      }
    }
    await write("}}");
    await writer.close();
  } catch (e) {
    // The status is already sent; a cut-off body is invalid JSON, which the app treats as a failed request.
    console.error("history stream", e);
    await writer.abort(e);
  }
}

function json(data: unknown, status: number, headers: Record<string, string> = {}): Response {
  return Response.json(data, { status, headers });
}
