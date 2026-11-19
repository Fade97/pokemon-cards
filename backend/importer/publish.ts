// Turns the importer's database into the price files the Worker serves (src/price-data.ts), plus the daily
// snapshot kept for Pro price history (ADR 0004). Files go to every configured sink: a local directory on Vulcan
// (always, as a backup) and the R2 bucket once its credentials are set.

import { mkdir, writeFile } from "node:fs/promises";
import { dirname, join } from "node:path";
import { AwsClient } from "aws4fetch";
import {
  META_KEY,
  PRICE_FILES_VERSION,
  shardKey,
  shardOf,
  snapshotKey,
  type CardmarketPrice,
  type CardPrice,
  type PriceMeta,
  type PricePoints,
  type PriceShard,
} from "../src/price-data";
import type { Db } from "./db";
import { mapLimit } from "./jobs/context";
import type { WeekAgo } from "./week-ago";

export interface Sink {
  readonly name: string;
  put(key: string, body: Uint8Array, contentType: string, contentEncoding?: string): Promise<void>;
}

/** Writes files below a directory, mirroring the bucket layout. */
export class DirSink implements Sink {
  readonly name: string;
  constructor(private readonly dir: string) {
    this.name = `dir:${dir}`;
  }
  async put(key: string, body: Uint8Array): Promise<void> {
    const path = join(this.dir, key);
    await mkdir(dirname(path), { recursive: true });
    await writeFile(path, body);
  }
}

export interface R2Config {
  /** S3 API endpoint of the account, e.g. https://<account id>.r2.cloudflarestorage.com */
  endpoint: string;
  accessKeyId: string;
  secretAccessKey: string;
  bucket: string;
}

/** Uploads through R2's S3-compatible API, so Vulcan needs no Wrangler login, only an R2 API token. */
export class R2Sink implements Sink {
  readonly name: string;
  private readonly client: AwsClient;
  constructor(private readonly config: R2Config) {
    this.name = `r2:${config.bucket}`;
    this.client = new AwsClient({
      accessKeyId: config.accessKeyId,
      secretAccessKey: config.secretAccessKey,
      service: "s3",
      region: "auto",
    });
  }
  async put(key: string, body: Uint8Array, contentType: string, contentEncoding?: string): Promise<void> {
    const url = `${this.config.endpoint.replace(/\/$/, "")}/${this.config.bucket}/${key}`;
    const headers: Record<string, string> = { "Content-Type": contentType };
    if (contentEncoding) headers["Content-Encoding"] = contentEncoding;
    const res = await this.client.fetch(url, { method: "PUT", body: body as Uint8Array<ArrayBuffer>, headers });
    if (!res.ok) throw new Error(`PUT ${key} to ${this.name}: HTTP ${res.status} ${(await res.text()).slice(0, 200)}`);
  }
}

/** Parallel uploads per sink; R2 handles more, but the nightly run has no hurry. */
const UPLOAD_CONCURRENCY = 8;

/**
 * Writes the current price files, the day's snapshot and meta.json last. With `weekAgo` (week-ago.ts), each market of
 * a card that had prices a week ago carries them for the free weekly direction (#186); without it the files are as
 * before.
 */
export async function publish(db: Db, sinks: Sink[], now: Date, weekAgo?: WeekAgo | null): Promise<string> {
  const generatedAt = now.toISOString();
  const prices = await readAllPrices(db);

  const shards = new Map<string, Record<string, CardPrice>>();
  for (const price of prices) {
    if (weekAgo) {
      const week = weekAgo.of(price.id);
      if (price.cardmarket && week.cardmarket) price.cardmarket.weekAgo = week.cardmarket;
      if (price.tcgplayer && week.tcgplayer) price.tcgplayer.weekAgo = week.tcgplayer;
    }
    const shard = shardOf(price.id);
    if (!shards.has(shard)) shards.set(shard, {});
    shards.get(shard)![price.id] = price;
  }

  const files: { key: string; body: Uint8Array; type: string; encoding?: string }[] = [];
  const encode = (value: unknown) => new TextEncoder().encode(JSON.stringify(value));
  for (const [shard, cards] of shards) {
    const file: PriceShard = { version: PRICE_FILES_VERSION, generatedAt, cards };
    files.push({ key: shardKey(shard), body: encode(file), type: "application/json" });
  }
  files.push({ key: snapshotKey(now), body: await snapshot(db, now), type: "application/json", encoding: "gzip" });

  const meta: PriceMeta = {
    version: PRICE_FILES_VERSION,
    generatedAt,
    cards: prices.length,
    shards: shards.size,
    cardmarketPriceDate: newest(prices.map((p) => p.cardmarket?.priceDate)),
    tcgplayerPriceDate: newest(prices.map((p) => p.tcgplayer?.priceDate)),
  };

  for (const sink of sinks) {
    await mapLimit(files, UPLOAD_CONCURRENCY, (f) => sink.put(f.key, f.body, f.type, f.encoding));
    // Last, so a reader that sees a new meta.json also sees the files it describes.
    await sink.put(META_KEY, encode(meta), "application/json");
  }
  return `${prices.length} cards in ${shards.size} files to ${sinks.map((s) => s.name).join(", ")}`;
}

function newest(dates: (string | null | undefined)[]): string | null {
  return dates.reduce<string | null>((a, d) => (d && (!a || d > a) ? d : a), null);
}

// --- reading prices ---

interface MappingRow {
  tcgdex_id: string;
  cardmarket_id: number | null;
  tcgplayer_id: number | null;
  price_date: string | null;
  low: number | null;
  trend: number | null;
  avg: number | null;
  avg1: number | null;
  avg7: number | null;
  avg30: number | null;
  holo_low: number | null;
  holo_trend: number | null;
  holo_avg: number | null;
  holo_avg1: number | null;
  holo_avg7: number | null;
  holo_avg30: number | null;
}

interface TcgplayerRow {
  tcgdex_id: string;
  sub_type: string;
  low: number | null;
  mid: number | null;
  high: number | null;
  market: number | null;
  direct_low: number | null;
  price_date: string;
}

/** Every mapped card with its current prices, in the shape the API returns. */
export async function readAllPrices(db: Db): Promise<CardPrice[]> {
  const { results: mappings } = await db
    .prepare(
      `SELECT m.tcgdex_id, m.cardmarket_id, m.tcgplayer_id, p.price_date,
              p.low, p.trend, p.avg, p.avg1, p.avg7, p.avg30,
              p.holo_low, p.holo_trend, p.holo_avg, p.holo_avg1, p.holo_avg7, p.holo_avg30
         FROM card_mapping m
         LEFT JOIN cardmarket_prices p ON p.id_product = m.cardmarket_id
        ORDER BY m.tcgdex_id`,
    )
    .all<MappingRow>();
  const { results: tcgplayer } = await db
    .prepare(
      `SELECT m.tcgdex_id, t.sub_type, t.low, t.mid, t.high, t.market, t.direct_low, t.price_date
         FROM card_mapping m
         JOIN tcgplayer_prices t ON t.product_id = m.tcgplayer_id
        ORDER BY m.tcgdex_id, t.sub_type`,
    )
    .all<TcgplayerRow>();

  const result = new Map<string, CardPrice>();
  for (const row of mappings) {
    result.set(row.tcgdex_id, {
      id: row.tcgdex_id,
      cardmarket: row.cardmarket_id === null ? null : cardmarketPrice(row, row.cardmarket_id),
      tcgplayer:
        row.tcgplayer_id === null
          ? null
          : { productId: row.tcgplayer_id, currency: "USD", priceDate: null, variants: {} },
    });
  }
  for (const row of tcgplayer) {
    const price = result.get(row.tcgdex_id)?.tcgplayer;
    if (!price) continue;
    price.variants[row.sub_type] = {
      low: row.low,
      mid: row.mid,
      high: row.high,
      market: row.market,
      directLow: row.direct_low,
    };
    if (price.priceDate === null || row.price_date > price.priceDate) price.priceDate = row.price_date;
  }
  return [...result.values()];
}

function cardmarketPrice(row: MappingRow, productId: number): CardmarketPrice {
  return {
    productId,
    currency: "EUR",
    mixedLanguages: true,
    priceDate: row.price_date,
    normal: points(row.low, row.trend, row.avg, row.avg1, row.avg7, row.avg30),
    holo: points(row.holo_low, row.holo_trend, row.holo_avg, row.holo_avg1, row.holo_avg7, row.holo_avg30),
  };
}

function points(...values: (number | null)[]): PricePoints | null {
  if (values.every((v) => v === null)) return null;
  const [low, trend, avg, avg1, avg7, avg30] = values as [
    number | null,
    number | null,
    number | null,
    number | null,
    number | null,
    number | null,
  ];
  return { low, trend, avg, avg1, avg7, avg30 };
}

// --- daily snapshot ---
//
// One gzipped JSON file per day. Tables are written as column lists plus row arrays, which keeps a day at a
// few MB before compression:
//
//   { "version": 1, "date": "2026-11-17", "createdAt": "…",
//     "mapping":    { "columns": ["tcgdexId", "cardmarketId", "tcgplayerId"], "rows": [[…], …] },
//     "cardmarket": { "columns": ["idProduct", "low", …, "priceDate"], "rows": […] },
//     "tcgplayer":  { "columns": ["productId", "subType", …, "priceDate"], "rows": […] } }

export const SNAPSHOT_VERSION = 1;

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

export async function snapshot(db: Db, now: Date): Promise<Uint8Array> {
  const parts: string[] = [
    `{"version":${SNAPSHOT_VERSION},"date":"${now.toISOString().slice(0, 10)}","createdAt":"${now.toISOString()}"`,
  ];
  for (const [name, table] of Object.entries(TABLES)) {
    const rows = await db
      .prepare(`SELECT ${Object.values(table.columns).join(", ")} FROM ${table.from} ORDER BY rowid`)
      .raw();
    parts.push(`,"${name}":{"columns":${JSON.stringify(Object.keys(table.columns))},"rows":[`);
    rows.forEach((row, i) => parts.push((i ? "," : "") + JSON.stringify(row)));
    parts.push("]}");
  }
  parts.push("}");
  const gzipped = new Response(new Blob(parts).stream().pipeThrough(new CompressionStream("gzip")));
  return new Uint8Array(await gzipped.arrayBuffer());
}
