// Nightly TCGplayer prices from TCGCSV's daily dumps (ADR 0004), one queue job per TCGplayer group (set).
// Each group job also fills TCGplayer IDs that TCGdex lacks by matching set code + collector number.

import { enqueue, getJson, getOk, type ImportContext, type ImportMessage } from "./context";

export const TCGCSV = "https://tcgcsv.com";
/** TCGplayer category 3 is Pokémon (EN). Category 85, Pokémon Japan, is out of scope: the design prices JP via Cardmarket. */
const CATEGORY = 3;
const ROWS_PER_STATEMENT = 1000;

interface Group {
  groupId: number;
  abbreviation?: string | null;
}

interface Product {
  productId: number;
  name: string;
  extendedData?: { name: string; value: string }[];
}

interface PriceRow {
  productId: number;
  subTypeName: string;
  lowPrice: number | null;
  midPrice: number | null;
  highPrice: number | null;
  marketPrice: number | null;
  directLowPrice: number | null;
}

/** Queues one job per group. TCGCSV's own update time becomes the price date of every row. */
export async function queueGroups(ctx: ImportContext): Promise<string> {
  const updated = (await (await getOk(ctx, `${TCGCSV}/last-updated.txt`)).text()).trim();
  const date = new Date(updated);
  const priceDate = Number.isNaN(date.getTime()) ? ctx.now().toISOString() : date.toISOString();
  const { results } = await getJson<{ results: Group[] }>(ctx, `${TCGCSV}/tcgplayer/${CATEGORY}/groups`);
  await enqueue(
    ctx,
    results.map(
      (g): ImportMessage => ({
        type: "tcgcsv-group",
        groupId: g.groupId,
        abbreviation: g.abbreviation?.trim() || null,
        priceDate,
      }),
    ),
  );
  return `queued ${results.length} groups, TCGCSV updated ${priceDate}`;
}

const UPSERT = `
  INSERT INTO tcgplayer_prices (product_id, sub_type, low, mid, high, market, direct_low, price_date)
  SELECT value ->> 0, value ->> 1, value ->> 2, value ->> 3, value ->> 4, value ->> 5, value ->> 6, ?2
    FROM json_each(?1)
   WHERE value ->> 0 IN (SELECT tcgplayer_id FROM card_mapping WHERE tcgplayer_id IS NOT NULL)
  ON CONFLICT (product_id, sub_type) DO UPDATE SET
    low = excluded.low, mid = excluded.mid, high = excluded.high, market = excluded.market,
    direct_low = excluded.direct_low, price_date = excluded.price_date`;

export async function importGroup(
  ctx: ImportContext,
  msg: Extract<ImportMessage, { type: "tcgcsv-group" }>,
): Promise<void> {
  const base = `${TCGCSV}/tcgplayer/${CATEGORY}/${msg.groupId}`;
  const [{ results: products }, { results: prices }] = await Promise.all([
    getJson<{ results: Product[] }>(ctx, `${base}/products`),
    getJson<{ results: PriceRow[] }>(ctx, `${base}/prices`),
  ]);

  // Matches go first, so their prices are stored in the same run.
  const statements = await matchStatements(ctx, msg.abbreviation, products);
  const upsert = ctx.db.prepare(UPSERT);
  for (let i = 0; i < prices.length; i += ROWS_PER_STATEMENT) {
    const rows = prices
      .slice(i, i + ROWS_PER_STATEMENT)
      .map((p) => [p.productId, p.subTypeName, p.lowPrice, p.midPrice, p.highPrice, p.marketPrice, p.directLowPrice]);
    statements.push(upsert.bind(JSON.stringify(rows), msg.priceDate));
  }
  if (statements.length > 0) await ctx.db.batch(statements);
}

/**
 * Mapping rows for English cards of this group's set that have no TCGplayer ID yet. The group is tied to a TCGdex
 * set by the official set code (e.g. MEW for 151); a card matches a product when the collector numbers agree and,
 * if several products share the number, the product name starts with the card name.
 */
async function matchStatements(
  ctx: ImportContext,
  abbreviation: string | null,
  products: Product[],
): Promise<D1PreparedStatement[]> {
  if (!abbreviation) return [];
  const { results: sets } = await ctx.db
    .prepare("SELECT set_id FROM tcgdex_sets WHERE lang = 'en' AND abbreviation = ? COLLATE NOCASE")
    .bind(abbreviation)
    .all<{ set_id: string }>();
  // A code shared by several TCGdex sets would make numbers ambiguous.
  if (sets.length !== 1) return [];

  const { results: cards } = await ctx.db
    .prepare(
      `SELECT c.tcgdex_id, c.local_id, c.name
         FROM cards c LEFT JOIN card_mapping m ON m.tcgdex_id = c.tcgdex_id
        WHERE c.lang = 'en' AND c.set_id = ? AND m.tcgplayer_id IS NULL
          AND (m.tcgplayer_source IS NULL OR m.tcgplayer_source <> 'override')`,
    )
    .bind(sets[0]!.set_id)
    .all<{ tcgdex_id: string; local_id: string; name: string }>();
  if (cards.length === 0) return [];

  const byNumber = new Map<string, Product[]>();
  for (const p of products) {
    const number = p.extendedData?.find((e) => e.name === "Number")?.value;
    if (!number) continue; // sealed products have no collector number
    const key = normalizeNumber(number.split("/")[0]!);
    byNumber.set(key, [...(byNumber.get(key) ?? []), p]);
  }

  const at = ctx.now().toISOString();
  const upsert = ctx.db.prepare(
    `INSERT INTO card_mapping (tcgdex_id, tcgplayer_id, tcgplayer_source, updated_at) VALUES (?, ?, 'match', ?)
     ON CONFLICT (tcgdex_id) DO UPDATE SET
       tcgplayer_id = excluded.tcgplayer_id, tcgplayer_source = 'match', updated_at = excluded.updated_at
     WHERE card_mapping.tcgplayer_id IS NULL`,
  );
  const statements: D1PreparedStatement[] = [];
  for (const card of cards) {
    let candidates = byNumber.get(normalizeNumber(card.local_id)) ?? [];
    if (candidates.length > 1) {
      const name = normalizeName(card.name);
      candidates = candidates.filter((p) => normalizeName(p.name).startsWith(name));
    }
    if (candidates.length === 1) statements.push(upsert.bind(card.tcgdex_id, candidates[0]!.productId, at));
  }
  return statements;
}

/** "025" and "25" are the same number, as are "TG01" and "TG1". */
export function normalizeNumber(n: string): string {
  return n.trim().toUpperCase().replace(/^([A-Z-]*)0+(?=\d)/, "$1");
}

function normalizeName(n: string): string {
  return n
    .normalize("NFKD")
    .toLowerCase()
    .replace(/[^a-z0-9]/g, "");
}
