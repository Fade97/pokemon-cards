// Reads current prices for TCGdex card IDs from D1 and shapes them for the app.

/** D1 allows at most 100 bound parameters per query, so one batch request can ask for 100 cards. */
export const MAX_BATCH = 100;

const CARD_ID = /^[A-Za-z0-9._-]{1,40}$/;

export function isCardId(value: unknown): value is string {
  return typeof value === "string" && CARD_ID.test(value);
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

/**
 * Looks up prices for up to {@link MAX_BATCH} distinct card IDs.
 * Cards without a mapping row are left out of the result, so callers can report them as unknown.
 */
export async function getPrices(db: D1Database, ids: readonly string[]): Promise<Map<string, CardPrice>> {
  if (ids.length === 0) return new Map();
  if (ids.length > MAX_BATCH) throw new RangeError(`at most ${MAX_BATCH} ids per query`);

  const placeholders = ids.map(() => "?").join(", ");
  const [mappings, tcgplayer] = (await db.batch([
    db
      .prepare(
        `SELECT m.tcgdex_id, m.cardmarket_id, m.tcgplayer_id, p.price_date,
                p.low, p.trend, p.avg, p.avg1, p.avg7, p.avg30,
                p.holo_low, p.holo_trend, p.holo_avg, p.holo_avg1, p.holo_avg7, p.holo_avg30
           FROM card_mapping m
           LEFT JOIN cardmarket_prices p ON p.id_product = m.cardmarket_id
          WHERE m.tcgdex_id IN (${placeholders})`,
      )
      .bind(...ids),
    db
      .prepare(
        `SELECT m.tcgdex_id, t.sub_type, t.low, t.mid, t.high, t.market, t.direct_low, t.price_date
           FROM card_mapping m
           JOIN tcgplayer_prices t ON t.product_id = m.tcgplayer_id
          WHERE m.tcgdex_id IN (${placeholders})
          ORDER BY t.sub_type`,
      )
      .bind(...ids),
  ])) as [D1Result<MappingRow>, D1Result<TcgplayerRow>];

  const result = new Map<string, CardPrice>();
  for (const row of mappings.results) {
    result.set(row.tcgdex_id, {
      id: row.tcgdex_id,
      cardmarket: row.cardmarket_id === null ? null : cardmarketPrice(row, row.cardmarket_id),
      tcgplayer:
        row.tcgplayer_id === null
          ? null
          : { productId: row.tcgplayer_id, currency: "USD", priceDate: null, variants: {} },
    });
  }
  for (const row of tcgplayer.results) {
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
  return result;
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
