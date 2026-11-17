// What an import job needs from the outside world. Jobs get it passed in, so tests can swap the network for a
// fake while the database stays a real (in-memory) SQLite.

import type { Db } from "../db";

export type ImportMessage =
  | { type: "tcgdex-sets"; lang: string }
  | { type: "tcgdex-set"; lang: string; setId: string }
  | { type: "tcgdex-cards"; lang: string; setId: string; cardIds: string[] }
  | { type: "cardmarket" }
  | { type: "tcgcsv-groups" }
  | { type: "tcgcsv-group"; groupId: number; abbreviation: string | null; priceDate: string };

/**
 * Jobs queue follow-up jobs (a set queues its card chunks). The importer runs them in-process (../queue.ts);
 * a delay only orders them: undelayed jobs run first.
 */
export interface ImportQueue {
  sendBatch(messages: Iterable<{ body: ImportMessage; delaySeconds?: number }>): Promise<unknown>;
}

export interface ImportContext {
  db: Db;
  queue: ImportQueue;
  fetch: typeof fetch;
  now: () => Date;
  /** Upper bound of TCGdex sets synced per language and night; spreads the first backfill if wanted. */
  maxSetsPerNight: number;
}

/** Upstream sources ask for no particular identification; a generic name keeps the logs readable for them. */
const USER_AGENT = "card-price-import/1";

/** GETs JSON and fails loudly on non-2xx answers, so the queue retries the job. */
export async function getJson<T>(ctx: ImportContext, url: string): Promise<T> {
  const res = await getOk(ctx, url);
  return (await res.json()) as T;
}

export async function getOk(ctx: ImportContext, url: string): Promise<Response> {
  const res = await ctx.fetch(url, { headers: { "User-Agent": USER_AGENT, Accept: "application/json" } });
  if (!res.ok) {
    await res.body?.cancel();
    throw new Error(`GET ${url}: HTTP ${res.status}`);
  }
  return res;
}

/** Sends messages in groups of 100, the most `sendBatch` takes at once. */
export async function enqueue(ctx: ImportContext, messages: ImportMessage[], delaySeconds = 0): Promise<void> {
  for (let i = 0; i < messages.length; i += 100) {
    await ctx.queue.sendBatch(messages.slice(i, i + 100).map((body) => ({ body, delaySeconds })));
  }
}

/** Runs `fn` over `items` with at most `limit` calls in flight. */
export async function mapLimit<T, R>(items: readonly T[], limit: number, fn: (item: T) => Promise<R>): Promise<R[]> {
  const results = new Array<R>(items.length);
  let next = 0;
  const workers = Array.from({ length: Math.min(limit, items.length) }, async () => {
    while (next < items.length) {
      const i = next++;
      results[i] = await fn(items[i]!);
    }
  });
  await Promise.all(workers);
  return results;
}

// --- import_runs ---

export async function startRun(ctx: ImportContext, job: string): Promise<number> {
  const row = await ctx.db
    .prepare("INSERT INTO import_runs (job, started_at, status) VALUES (?, ?, 'running') RETURNING id")
    .bind(job, ctx.now().toISOString())
    .first<{ id: number }>();
  return row!.id;
}

export async function finishRun(
  ctx: ImportContext,
  id: number,
  status: "ok" | "failed" | "skipped",
  detail: string,
): Promise<void> {
  await ctx.db
    .prepare("UPDATE import_runs SET finished_at = ?, status = ?, detail = ? WHERE id = ?")
    .bind(ctx.now().toISOString(), status, detail, id)
    .run();
}

/** Wraps a job in a run row: running, then ok with the job's summary or failed with the error. */
export async function tracked(ctx: ImportContext, job: string, fn: () => Promise<string>): Promise<void> {
  const id = await startRun(ctx, job);
  try {
    await finishRun(ctx, id, "ok", await fn());
  } catch (e) {
    await finishRun(ctx, id, "failed", errorText(e));
    throw e;
  }
}

export async function recordFailure(ctx: ImportContext, job: string, detail: string): Promise<void> {
  const at = ctx.now().toISOString();
  await ctx.db
    .prepare("INSERT INTO import_runs (job, started_at, finished_at, status, detail) VALUES (?, ?, ?, 'failed', ?)")
    .bind(job, at, at, detail)
    .run();
}

export function errorText(e: unknown): string {
  return (e instanceof Error ? e.message : String(e)).slice(0, 500);
}
