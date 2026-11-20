// The free weekly direction (#186): each printing's price of a week ago, read from the daily snapshots and written
// into the current price files next to today's price (`weekAgo`, see WeekAgoPrices in src/price-data.ts). The app
// compares the two and shows only which way a set or card moved; the amounts per day stay in the Pro history files.
//
// Read from the local copy of the published files on Vulcan, like the history (history.ts), so it needs no R2 reads.

import { readFile } from "node:fs/promises";
import { join } from "node:path";
import { snapshotKey, type WeekAgoPrices } from "../src/price-data";
import { columns, parseSnapshot, type Snapshot } from "./history";

/** How far back the comparison price is. */
export const WEEK_DAYS = 7;

/**
 * A printing without a price on the day itself takes the last one at most this many days before it, as the app does
 * with the Pro history (`WeeklyPriceChange.MAX_GAP_DAYS`): a skipped night must not hide the direction, an older
 * price says nothing about this week.
 */
export const MAX_GAP_DAYS = 3;

/** A price as the snapshot has it; no price, 0 and junk become null (a 0 in the guide means no price). */
function price(value: unknown): number | null {
  return typeof value === "number" && Number.isFinite(value) && value > 0 ? value : null;
}

interface CardWeekAgo {
  cardmarket?: Record<string, number>;
  tcgplayer?: Record<string, number>;
}

/** The week-ago prices per card, collected from the snapshots of [date − MAX_GAP_DAYS, date]. */
export class WeekAgo {
  private readonly cards = new Map<string, CardWeekAgo>();

  /** `date` is the day the prices are for, `YYYY-MM-DD`. */
  constructor(readonly date: string) {}

  /** Adds one day's snapshot. Days must come in ascending order, so a later day's price replaces an earlier one. */
  add(day: Snapshot): void {
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

    const set = (id: string, market: keyof CardWeekAgo, printing: string, value: number | null) => {
      if (value === null) return;
      let card = this.cards.get(id);
      if (!card) this.cards.set(id, (card = {}));
      // No prototype: a printing like "constructor" must not hit Object.prototype.
      (card[market] ??= Object.create(null) as Record<string, number>)[printing] = value;
    };

    for (const row of day.mapping.rows) {
      const id = row[m.tcgdexId] as string;
      const cardmarketId = row[m.cardmarketId] as number | null;
      const guide = cardmarketId === null ? undefined : cardmarket.get(cardmarketId);
      if (guide) {
        set(id, "cardmarket", "normal", price(guide[cm.trend]));
        set(id, "cardmarket", "holo", price(guide[cm.holoTrend]));
      }
      const tcgplayerId = row[m.tcgplayerId] as number | null;
      for (const variant of (tcgplayerId === null ? undefined : tcgplayer.get(tcgplayerId)) ?? []) {
        set(id, "tcgplayer", variant[tp.subType] as string, price(variant[tp.market]));
      }
    }
  }

  /** The card's week-ago prices per market; a market without any is missing. */
  of(cardId: string): { cardmarket?: WeekAgoPrices; tcgplayer?: WeekAgoPrices } {
    const card = this.cards.get(cardId);
    if (!card) return {};
    const wrap = (prices: Record<string, number> | undefined) =>
      prices ? { date: this.date, prices: { ...prices } } : undefined;
    return { cardmarket: wrap(card.cardmarket), tcgplayer: wrap(card.tcgplayer) };
  }

  get size(): number {
    return this.cards.size;
  }
}

/** The day `days` before `now` (UTC), as a Date at midnight. */
function daysBefore(now: Date, days: number): Date {
  return new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), now.getUTCDate() - days));
}

/** Builds [WeekAgo] for `now` from snapshots already in memory (any order; days outside the window are ignored). */
export function buildWeekAgo(now: Date, snapshots: readonly Snapshot[]): WeekAgo {
  const target = daysBefore(now, WEEK_DAYS).toISOString().slice(0, 10);
  const first = daysBefore(now, WEEK_DAYS + MAX_GAP_DAYS).toISOString().slice(0, 10);
  const result = new WeekAgo(target);
  snapshots
    .filter((s) => s.date >= first && s.date <= target)
    .sort((a, b) => a.date.localeCompare(b.date))
    .forEach((s) => result.add(s));
  return result;
}

/**
 * Reads the snapshots of the week-ago window below `filesDir` (`snapshots/YYYY/MM/DD.json.gz`), one at a time.
 * Null when none of those days has a snapshot, e.g. in the importer's first week.
 */
export async function readWeekAgo(filesDir: string, now: Date): Promise<WeekAgo | null> {
  const result = new WeekAgo(daysBefore(now, WEEK_DAYS).toISOString().slice(0, 10));
  let found = 0;
  for (let back = WEEK_DAYS + MAX_GAP_DAYS; back >= WEEK_DAYS; back--) {
    let gzipped: Buffer;
    try {
      gzipped = await readFile(join(filesDir, snapshotKey(daysBefore(now, back))));
    } catch (e) {
      if ((e as NodeJS.ErrnoException).code === "ENOENT") continue;
      throw e;
    }
    result.add(parseSnapshot(gzipped));
    found++;
  }
  return found ? result : null;
}
