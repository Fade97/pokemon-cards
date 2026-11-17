// The price files in R2: written nightly by the importer on Vulcan (importer/), read by the Worker (ADR 0004).
//
//   current/meta.json         PriceMeta: when the files were written and the source dates
//   current/<shard>.json      PriceShard: the prices of every card whose ID maps to that shard (one TCGdex set)
//   snapshots/YYYY/MM/DD.json.gz   the day's full mapping and prices, kept from day one for Pro history
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
