// Nightly Cardmarket price guide import (ADR 0004). The guide is public, keyless and rebuilt once a night.

import type { Stmt } from "../db";
import { getOk, type ImportContext } from "./context";
import { JsonArrayStream } from "./json-stream";

export const PRICE_GUIDE_URL = "https://downloads.s3.cardmarket.com/productCatalog/priceGuide/price_guide_6.json";

/** Guide rows per statement. They travel as one JSON parameter (~100 KB), not as bound values. */
const ROWS_PER_STATEMENT = 1000;
/** Statements per D1 batch. */
const STATEMENTS_PER_BATCH = 10;

export interface GuideRow {
  idProduct: number;
  low?: number | null;
  trend?: number | null;
  avg?: number | null;
  avg1?: number | null;
  avg7?: number | null;
  avg30?: number | null;
  "low-holo"?: number | null;
  "trend-holo"?: number | null;
  "avg-holo"?: number | null;
  "avg1-holo"?: number | null;
  "avg7-holo"?: number | null;
  "avg30-holo"?: number | null;
}

const COLUMNS = [
  "low", "trend", "avg", "avg1", "avg7", "avg30",
  "holo_low", "holo_trend", "holo_avg", "holo_avg1", "holo_avg7", "holo_avg30",
] as const;

// D1 caps bound parameters at 100 per statement, so rows go in as a JSON array and SQLite unpacks them.
// Only products some card maps to are kept: the guide has ~80k rows, most of them for cards we don't price.
const UPSERT = `
  INSERT INTO cardmarket_prices (id_product, ${COLUMNS.join(", ")}, price_date)
  SELECT value ->> 0, ${COLUMNS.map((_, i) => `value ->> ${i + 1}`).join(", ")}, ?2
    FROM json_each(?1)
   WHERE value ->> 0 IN (SELECT cardmarket_id FROM card_mapping WHERE cardmarket_id IS NOT NULL)
  ON CONFLICT (id_product) DO UPDATE SET
    ${COLUMNS.map((c) => `${c} = excluded.${c}`).join(", ")}, price_date = excluded.price_date`;

/** Streams the price guide into `cardmarket_prices`. Returns a summary for `import_runs`. */
export async function importCardmarket(ctx: ImportContext): Promise<string> {
  const res = await getOk(ctx, PRICE_GUIDE_URL);
  const guide = new JsonArrayStream<GuideRow>("priceGuides");
  const upsert = ctx.db.prepare(UPSERT);

  let priceDate: string | null = null;
  let rows: unknown[][] = [];
  let statements: Stmt[] = [];
  let read = 0;
  let stored = 0;

  const flushBatch = async () => {
    if (statements.length === 0) return;
    const results = await ctx.db.batch(statements);
    stored += results.reduce((n, r) => n + (r.meta.changes ?? 0), 0);
    statements = [];
  };
  const flushRows = async () => {
    if (rows.length === 0) return;
    statements.push(upsert.bind(JSON.stringify(rows), priceDate));
    rows = [];
    if (statements.length >= STATEMENTS_PER_BATCH) await flushBatch();
  };

  for await (const row of guide.items(res.body!)) {
    priceDate ??= guideDate(guide.prefix) ?? ctx.now().toISOString();
    read++;
    if (typeof row.idProduct !== "number") continue;
    rows.push(guideRow(row));
    if (rows.length >= ROWS_PER_STATEMENT) await flushRows();
  }
  await flushRows();
  await flushBatch();

  if (read === 0) throw new Error("price guide has no rows");
  return `${stored} of ${read} guide rows stored, guide from ${priceDate}`;
}

/** The guide writes 0 where it has no price (e.g. `trend-holo` of a card without a foil print). */
function price(value: number | null | undefined): number | null {
  return typeof value === "number" && value > 0 ? value : null;
}

export function guideRow(r: GuideRow): (number | null)[] {
  return [
    r.idProduct,
    price(r.low), price(r.trend), price(r.avg), price(r.avg1), price(r.avg7), price(r.avg30),
    price(r["low-holo"]), price(r["trend-holo"]), price(r["avg-holo"]),
    price(r["avg1-holo"]), price(r["avg7-holo"]), price(r["avg30-holo"]),
  ];
}

/** `createdAt` from the guide header, e.g. "2026-11-16T02:48:57+0100", as UTC ISO 8601. */
export function guideDate(header: string): string | null {
  const m = /"createdAt"\s*:\s*"([^"]+)"/.exec(header);
  if (!m) return null;
  // Offsets come without a colon ("+0100"), which not every Date parser accepts.
  const date = new Date(m[1]!.replace(/([+-]\d{2})(\d{2})$/, "$1:$2"));
  return Number.isNaN(date.getTime()) ? null : date.toISOString();
}
