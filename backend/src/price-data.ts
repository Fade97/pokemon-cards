// The price files in R2: written nightly by the importer on Vulcan (importer/), read by the Worker (ADR 0004).
//
//   current/meta.json         PriceMeta: when the files were written and the source dates
//   current/<shard>.json      PriceShard: the prices of every card whose ID maps to that shard (one TCGdex set)
//   snapshots/YYYY/MM/DD.json.gz   the day's full mapping and prices, kept from day one for Pro history
//   history/<shard>/YYYY-MM.json   PriceHistoryFile: one month of daily prices per set, derived from the snapshots
//
// Shared by both sides, so the shard a card is written to is the shard the Worker reads.

export const PRICE_FILES_VERSION = 1;

const CARD_ID = /^[A-Za-z0-9._-]{1,40}$/;

export function isCardId(value: unknown): value is string {
  return typeof value === "string" && CARD_ID.test(value);
}

/**
 * The shard of a TCGdex card ID: the part before the last '-', which is the set ID ('sv03.5-025' → 'sv03.5').
 * Grouping by set keeps a collection refresh to a few files, since owned cards cluster in sets.
 * IDs without a usable prefix share one fallback shard.
 */
export function shardOf(cardId: string): string {
  const cut = cardId.lastIndexOf("-");
  const set = cut > 0 ? cardId.slice(0, cut) : "";
  return set && !set.startsWith(".") ? set : "_misc";
}

export function shardKey(shard: string): string {
  return `current/${shard}.json`;
}

export const META_KEY = "current/meta.json";

export function snapshotKey(date: Date): string {
  const iso = date.toISOString();
  return `snapshots/${iso.slice(0, 4)}/${iso.slice(5, 7)}/${iso.slice(8, 10)}.json.gz`;
}

export interface PricePoints {
  low: number | null;
  trend: number | null;
  avg: number | null;
  avg1: number | null;
  avg7: number | null;
  avg30: number | null;
}

export interface CardmarketPrice {
  productId: number;
  currency: "EUR";
  /** The free price guide mixes all card languages; the app labels the price that way. */
  mixedLanguages: true;
  priceDate: string | null;
  normal: PricePoints | null;
  /** The guide's foil ("-holo") price, reverse holo for most Pokémon cards. */
  holo: PricePoints | null;
}

export interface TcgplayerVariantPrice {
  low: number | null;
  mid: number | null;
  high: number | null;
  market: number | null;
  directLow: number | null;
}

export interface TcgplayerPrice {
  productId: number;
  currency: "USD";
  priceDate: string | null;
  /** Keyed by TCGplayer printing, e.g. "Normal", "Holofoil", "Reverse Holofoil". */
  variants: Record<string, TcgplayerVariantPrice>;
}

export interface CardPrice {
  id: string;
  cardmarket: CardmarketPrice | null;
  tcgplayer: TcgplayerPrice | null;
}

export interface PriceShard {
  version: number;
  generatedAt: string;
  cards: Record<string, CardPrice>;
}

export interface PriceMeta {
  version: number;
  generatedAt: string;
  cards: number;
  shards: number;
  /** Newest price date per market across all cards, null when that market has no prices yet. */
  cardmarketPriceDate: string | null;
  tcgplayerPriceDate: string | null;
}

// --- price history (Pro, #17) ---
//
// One file per set and calendar month (UTC), rebuilt by the importer from the daily snapshots. Amounts are the
// headline price the app shows (Cardmarket trend, TCGplayer market) in integer cents; each array lines up with
// `days`, and null means no price that day. A series that is null on every day of the month is left out.

export const HISTORY_VERSION = 1;

const MONTH = /^\d{4}-(0[1-9]|1[0-2])$/;

/** A calendar month as `YYYY-MM`. */
export function isMonth(value: unknown): value is string {
  return typeof value === "string" && MONTH.test(value);
}

/** A shard name as `shardOf` produces it: the card-ID alphabet, not starting with a dot. */
export function isShard(value: unknown): value is string {
  return isCardId(value) && !value.startsWith(".");
}

export function historyKey(shard: string, month: string): string {
  return `history/${shard}/${month}.json`;
}

/** Cents per day, aligned with `PriceHistoryFile.days`. */
export type DailyCents = (number | null)[];

export interface CardHistory {
  /** Cardmarket trend: `normal` the card as printed, `holo` the guide's foil (reverse holo) price. EUR. */
  cardmarket?: { normal?: DailyCents; holo?: DailyCents };
  /** TCGplayer market price per printing (`Normal`, `Holofoil`, `Reverse Holofoil`, …). USD. */
  tcgplayer?: Record<string, DailyCents>;
}

export interface PriceHistoryFile {
  version: number;
  /** The shard (TCGdex set ID) the file covers. */
  set: string;
  month: string;
  generatedAt: string;
  /** The days with a snapshot, ascending, as `YYYY-MM-DD`. */
  days: string[];
  cards: Record<string, CardHistory>;
}
