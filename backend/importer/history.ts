// Price history for Pro (#17): turns the daily snapshots (publish.ts) into one file per set and month, the format in
// src/price-data.ts. The snapshots stay the source of truth; history files are derived and can be rebuilt at any
// time (`--rebuild-history`). The Worker serves them as they are, without parsing them.
//
// Read from the local copy of the published files on Vulcan (`<data>/files/snapshots/YYYY/MM/DD.json.gz`), so a
// rebuild needs no R2 reads.

import { readdir, readFile } from "node:fs/promises";
import { join } from "node:path";
import { gunzipSync } from "node:zlib";
import {
  historyKey,
  HISTORY_VERSION,
  shardOf,
  type CardHistory,
  type DailyCents,
  type PriceHistoryFile,
} from "../src/price-data";
import { mapLimit } from "./jobs/context";
import type { Sink } from "./publish";

/** A snapshot table: column names plus rows of values in that order (publish.ts, "daily snapshot"). */
interface Table {
  columns: string[];
  rows: unknown[][];
}

export interface Snapshot {
  version: number;
  date: string;
  mapping: Table;
  cardmarket: Table;
  tcgplayer: Table;
}

export function parseSnapshot(gzipped: Uint8Array): Snapshot {
  return JSON.parse(gunzipSync(gzipped).toString("utf8")) as Snapshot;
}

/** Reads columns by name, so a snapshot that gained columns still works. */
function columns<const K extends string>(table: Table, names: readonly K[]): Record<K, number> {
  const result = {} as Record<K, number>;
  for (const name of names) {
    const index = table.columns.indexOf(name);
    if (index < 0) throw new Error(`snapshot table lacks column ${name}`);
    result[name] = index;
  }
  return result;
}

/** Euros or dollars to cents; no price, 0 and junk become null (a 0 in the guide means no price). */
function cents(value: unknown): number | null {
  return typeof value === "number" && Number.isFinite(value) && value > 0 ? Math.round(value * 100) : null;
}

/**
 * Collects one month of snapshots, one day at a time, so a rebuild holds a single parsed snapshot in memory.
 * Days are the snapshot dates; a card missing from a day's mapping or without a price there gets null that day.
 */
export class HistoryMonthBuilder {
  private readonly files = new Map<string, PriceHistoryFile>();

  /** `dates` are the snapshot days of the month, ascending; `add` takes their snapshots by index. */
  constructor(
    private readonly month: string,
    private readonly dates: readonly string[],
    private readonly generatedAt: Date,
  ) {}

  add(i: number, day: Snapshot): void {
    const n = this.dates.length;
    const series = (owner: Record<string, DailyCents>, key: string): DailyCents =>
      (owner[key] ??= new Array<number | null>(n).fill(null));

    const m = columns(day.mapping, ["tcgdexId", "cardmarketId", "tcgplayerId"] as const);
    const cm = columns(day.cardmarket, ["idProduct", "trend", "holoTrend"] as const);
    const tp = columns(day.tcgplayer, ["productId", "subType", "market"] as const);

    const cardmarket = new Map<number, unknown[]>();
    for (const row of day.cardmarket.rows) cardmarket.set(row[cm.idProduct] as number, row);
    const tcgplayer = new Map<number, unknown[][]>();
    for (const row of day.tcgplayer.rows) {
      const id = row[tp.productId] as number;
      const rows = tcgplayer.get(id);
      if (rows) rows.push(row);
      else tcgplayer.set(id, [row]);
    }

    for (const row of day.mapping.rows) {
      const id = row[m.tcgdexId] as string;
      const shard = shardOf(id);
      let file = this.files.get(shard);
      if (!file) {
        file = {
          version: HISTORY_VERSION,
          set: shard,
          month: this.month,
          generatedAt: this.generatedAt.toISOString(),
          days: [...this.dates],
          cards: {},
        };
        this.files.set(shard, file);
      }
      const card: CardHistory = (file.cards[id] ??= {});

      const cardmarketId = row[m.cardmarketId] as number | null;
      const price = cardmarketId === null ? undefined : cardmarket.get(cardmarketId);
      if (price) {
        const market = (card.cardmarket ??= {}) as Record<string, DailyCents>;
        const normal = cents(price[cm.trend]);
        const holo = cents(price[cm.holoTrend]);
        if (normal !== null) series(market, "normal")[i] = normal;
        if (holo !== null) series(market, "holo")[i] = holo;
      }

      const tcgplayerId = row[m.tcgplayerId] as number | null;
      for (const variant of (tcgplayerId === null ? undefined : tcgplayer.get(tcgplayerId)) ?? []) {
        const market = cents(variant[tp.market]);
        if (market !== null) series((card.tcgplayer ??= {}), variant[tp.subType] as string)[i] = market;
      }
    }
  }

  /** The files by shard. Cards and markets without a single price in the month are dropped. */
  finish(): Map<string, PriceHistoryFile> {
    for (const file of this.files.values()) {
      for (const [id, card] of Object.entries(file.cards)) {
        if (card.cardmarket && Object.keys(card.cardmarket).length === 0) delete card.cardmarket;
        if (card.tcgplayer && Object.keys(card.tcgplayer).length === 0) delete card.tcgplayer;
        if (!card.cardmarket && !card.tcgplayer) delete file.cards[id];
      }
    }
    return this.files;
  }
}

/** The history files of one month from snapshots already in memory, keyed by shard. */
export function buildHistoryMonth(
  month: string,
  snapshots: readonly Snapshot[],
  generatedAt: Date,
): Map<string, PriceHistoryFile> {
  const days = snapshots.filter((s) => s.date.startsWith(`${month}-`)).sort((a, b) => a.date.localeCompare(b.date));
  const builder = new HistoryMonthBuilder(month, days.map((d) => d.date), generatedAt);
  days.forEach((day, i) => builder.add(i, day));
  return builder.finish();
}

/**
 * The months the nightly run rebuilds: the current one, plus the previous one during the first days of a month, so
 * a failed run on a month's last day is repaired by the next ones.
 */
export function monthsToRebuild(now: Date): string[] {
  const current = now.toISOString().slice(0, 7);
  if (now.getUTCDate() > 3) return [current];
  const previous = new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth() - 1, 1)).toISOString().slice(0, 7);
  return [previous, current];
}

/** Every month that has at least one snapshot below `filesDir`, ascending. */
export async function snapshotMonths(filesDir: string): Promise<string[]> {
  const months: string[] = [];
  for (const year of (await readdirOrEmpty(join(filesDir, "snapshots"))).filter((y) => /^\d{4}$/.test(y)).sort()) {
    for (const month of (await readdirOrEmpty(join(filesDir, "snapshots", year))).filter((m) => /^\d{2}$/.test(m)).sort()) {
      months.push(`${year}-${month}`);
    }
  }
  return months;
}

/** The month's snapshot files, ascending by day. */
async function snapshotFiles(filesDir: string, month: string): Promise<{ date: string; path: string }[]> {
  const dir = join(filesDir, "snapshots", month.slice(0, 4), month.slice(5, 7));
  return (await readdirOrEmpty(dir))
    .filter((f) => /^\d{2}\.json\.gz$/.test(f))
    .sort()
    .map((f) => ({ date: `${month}-${f.slice(0, 2)}`, path: join(dir, f) }));
}

async function readdirOrEmpty(dir: string): Promise<string[]> {
  try {
    return await readdir(dir);
  } catch (e) {
    if ((e as NodeJS.ErrnoException).code === "ENOENT") return [];
    throw e;
  }
}

const UPLOAD_CONCURRENCY = 8;

/**
 * Rebuilds the history files of `months` from the snapshots below `filesDir` and writes them to every sink. One
 * snapshot is parsed at a time; the month's history files stay in memory until they are written.
 */
export async function publishHistory(filesDir: string, sinks: Sink[], months: string[], now: Date): Promise<string> {
  let count = 0;
  for (const month of months) {
    const days = await snapshotFiles(filesDir, month);
    const builder = new HistoryMonthBuilder(month, days.map((d) => d.date), now);
    for (const [i, day] of days.entries()) builder.add(i, parseSnapshot(await readFile(day.path)));
    const files = builder.finish();
    const encoded = [...files.values()].map((f) => ({
      key: historyKey(f.set, f.month),
      body: new TextEncoder().encode(JSON.stringify(f)),
    }));
    for (const sink of sinks) {
      await mapLimit(encoded, UPLOAD_CONCURRENCY, (f) => sink.put(f.key, f.body, "application/json"));
    }
    count += encoded.length;
  }
  return `${count} history files for ${months.join(", ") || "no months"} to ${sinks.map((s) => s.name).join(", ")}`;
}
