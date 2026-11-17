// Daily price snapshot to R2, kept from day one so Pro price history has data when Pro launches (ADR 0004).
//
// One gzipped JSON file per day at `prices/YYYY/MM/DD.json.gz`. Tables are written as column lists plus row arrays,
// which keeps a day at a few MB before compression:
//
//   { "version": 1, "date": "2026-11-17", "createdAt": "…",
//     "mapping":    { "columns": ["tcgdexId", "cardmarketId", "tcgplayerId"], "rows": [[…], …] },
//     "cardmarket": { "columns": ["idProduct", "low", …, "priceDate"], "rows": […] },
//     "tcgplayer":  { "columns": ["productId", "subType", …, "priceDate"], "rows": […] } }

import type { ImportContext } from "./context";

export const SNAPSHOT_VERSION = 1;
/** Rows per D1 query while reading a table. */
const PAGE = 10_000;

const TABLES = {
  mapping: {
    from: "card_mapping",
    columns: { tcgdexId: "tcgdex_id", cardmarketId: "cardmarket_id", tcgplayerId: "tcgplayer_id" },
  },
  cardmarket: {
    from: "cardmarket_prices",
    columns: {
      idProduct: "id_product", low: "low", trend: "trend", avg: "avg", avg1: "avg1", avg7: "avg7", avg30: "avg30",
      holoLow: "holo_low", holoTrend: "holo_trend", holoAvg: "holo_avg", holoAvg1: "holo_avg1",
      holoAvg7: "holo_avg7", holoAvg30: "holo_avg30", priceDate: "price_date",
    },
  },
  tcgplayer: {
    from: "tcgplayer_prices",
    columns: {
      productId: "product_id", subType: "sub_type", low: "low", mid: "mid", high: "high", market: "market",
      directLow: "direct_low", priceDate: "price_date",
    },
  },
} as const;

export function snapshotKey(date: Date): string {
  const iso = date.toISOString();
  return `prices/${iso.slice(0, 4)}/${iso.slice(5, 7)}/${iso.slice(8, 10)}.json.gz`;
}

export async function writeSnapshot(ctx: ImportContext): Promise<string> {
  const now = ctx.now();
  const parts: string[] = [
    `{"version":${SNAPSHOT_VERSION},"date":"${now.toISOString().slice(0, 10)}","createdAt":"${now.toISOString()}"`,
  ];
  const counts: string[] = [];
  for (const [name, table] of Object.entries(TABLES)) {
    const rows = await readTable(ctx.db, table.from, Object.values(table.columns));
    parts.push(`,"${name}":{"columns":${JSON.stringify(Object.keys(table.columns))},"rows":[`);
    rows.forEach((row, i) => parts.push((i ? "," : "") + JSON.stringify(row)));
    parts.push("]}");
    counts.push(`${rows.length} ${name}`);
  }
  parts.push("}");

  const gzipped = await new Response(
    new Blob(parts).stream().pipeThrough(new CompressionStream("gzip")),
  ).arrayBuffer();
  const key = snapshotKey(now);
  await ctx.snapshots.put(key, gzipped, {
    httpMetadata: { contentType: "application/json", contentEncoding: "gzip" },
  });
  return `${key}: ${counts.join(", ")}, ${gzipped.byteLength} bytes`;
}

/** Reads a whole table in pages by rowid, so no single D1 result gets large. */
async function readTable(db: D1Database, table: string, columns: readonly string[]): Promise<unknown[][]> {
  const rows: unknown[][] = [];
  let after = 0;
  for (;;) {
    const page = await db
      .prepare(`SELECT rowid, ${columns.join(", ")} FROM ${table} WHERE rowid > ? ORDER BY rowid LIMIT ${PAGE}`)
      .bind(after)
      .raw();
    for (const r of page) rows.push(r.slice(1));
    if (page.length < PAGE) return rows;
    after = page[page.length - 1]![0] as number;
  }
}
