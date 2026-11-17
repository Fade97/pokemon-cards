// Card list and marketplace IDs from TCGdex, the seed of the mapping (ADR 0004).
//
// TCGdex only exposes the Cardmarket/TCGplayer IDs on the full card record (`variants_detailed[].thirdParty`), so
// syncing means one request per card: ~24k for English, ~13k for Japanese. The work is spread out three ways:
// a set list job per language picks the sets that are due, a set job splits a set into chunks, and each chunk job
// fetches a few dozen cards. Every queue message stays well inside the Worker's subrequest and time limits.

import { isCardId } from "../prices";
import { enqueue, getJson, mapLimit, type ImportContext, type ImportMessage } from "./context";
import { applyOverridesStatements } from "./overrides";

export const TCGDEX_API = "https://api.tcgdex.net/v2";

/** Cards fetched per chunk job. */
export const CARDS_PER_CHUNK = 25;
/** Parallel TCGdex requests inside one chunk job; with a few consumers running this stays polite. */
const CARD_FETCH_CONCURRENCY = 4;
/** A set is synced again after this long, which picks up corrected IDs and newly listed products. */
const RESYNC_AFTER_DAYS = 30;

interface SetBrief {
  id: string;
  cardCount?: { total?: number };
}

interface SetDetail {
  id: string;
  cardCount?: { total?: number };
  abbreviation?: { official?: string };
  cards: { id: string }[];
}

export interface TcgdexVariant {
  type?: string;
  subtype?: string;
  size?: string;
  stamp?: string[];
  foil?: string;
  thirdParty?: { cardmarket?: number; tcgplayer?: number };
}

interface CardDetail {
  id: string;
  localId: string;
  name: string;
  set: { id: string };
  variants_detailed?: TcgdexVariant[];
}

/** Picks the sets of one language that need a sync tonight and queues a job for each. */
export async function queueDueSets(ctx: ImportContext, lang: string): Promise<string> {
  let sets = await getJson<SetBrief[]>(ctx, `${TCGDEX_API}/${lang}/sets`);
  if (lang !== "en") {
    // International prints share the English IDs; only sets English lacks are read in other languages.
    const english = new Set((await getJson<SetBrief[]>(ctx, `${TCGDEX_API}/en/sets`)).map((s) => s.id));
    sets = sets.filter((s) => !english.has(s.id));
  }
  sets = sets.filter((s) => (s.cardCount?.total ?? 0) > 0);

  const { results: known } = await ctx.db
    .prepare("SELECT set_id, card_count, synced_at FROM tcgdex_sets WHERE lang = ?")
    .bind(lang)
    .all<{ set_id: string; card_count: number; synced_at: string }>();
  const byId = new Map(known.map((r) => [r.set_id, r]));
  const staleBefore = new Date(ctx.now().getTime() - RESYNC_AFTER_DAYS * 86_400_000).toISOString();

  // New sets first, then sets whose card count changed, then the longest-unsynced ones.
  const due = sets
    .map((s) => {
      const row = byId.get(s.id);
      const rank = !row ? 0 : row.card_count !== s.cardCount!.total ? 1 : row.synced_at < staleBefore ? 2 : -1;
      return { id: s.id, rank, syncedAt: row?.synced_at ?? "" };
    })
    .filter((s) => s.rank >= 0)
    .sort((a, b) => a.rank - b.rank || a.syncedAt.localeCompare(b.syncedAt))
    .slice(0, ctx.maxSetsPerNight);

  await enqueue(
    ctx,
    due.map((s): ImportMessage => ({ type: "tcgdex-set", lang, setId: s.id })),
  );
  return `${lang}: queued ${due.length} of ${sets.length} sets`;
}

/** Reads one set's card list, queues the card chunks and records the set as synced. */
export async function syncSet(ctx: ImportContext, lang: string, setId: string): Promise<void> {
  const set = await getJson<SetDetail>(ctx, `${TCGDEX_API}/${lang}/sets/${encodeURIComponent(setId)}`);
  // A handful of IDs hold characters such as '?' that the API does not accept; they cannot be priced anyway.
  const ids = set.cards.map((c) => c.id).filter(isCardId);
  const chunks: ImportMessage[] = [];
  for (let i = 0; i < ids.length; i += CARDS_PER_CHUNK) {
    chunks.push({ type: "tcgdex-cards", lang, setId, cardIds: ids.slice(i, i + CARDS_PER_CHUNK) });
  }
  await enqueue(ctx, chunks);
  await ctx.db
    .prepare(
      `INSERT INTO tcgdex_sets (lang, set_id, card_count, abbreviation, synced_at) VALUES (?, ?, ?, ?, ?)
       ON CONFLICT (lang, set_id) DO UPDATE SET
         card_count = excluded.card_count, abbreviation = excluded.abbreviation, synced_at = excluded.synced_at`,
    )
    .bind(lang, setId, set.cardCount?.total ?? ids.length, set.abbreviation?.official ?? null, ctx.now().toISOString())
    .run();
}

/** Fetches full card records and stores each card with its TCGdex marketplace IDs. */
export async function syncCards(ctx: ImportContext, lang: string, cardIds: string[]): Promise<void> {
  const cards = await mapLimit(cardIds, CARD_FETCH_CONCURRENCY, (id) =>
    getJson<CardDetail>(ctx, `${TCGDEX_API}/${lang}/cards/${encodeURIComponent(id)}`),
  );
  const at = ctx.now().toISOString();
  const card = ctx.db.prepare(
    `INSERT INTO cards (tcgdex_id, lang, set_id, local_id, name, updated_at) VALUES (?, ?, ?, ?, ?, ?)
     ON CONFLICT (tcgdex_id) DO UPDATE SET
       lang = excluded.lang, set_id = excluded.set_id, local_id = excluded.local_id, name = excluded.name,
       updated_at = excluded.updated_at`,
  );
  // An override is never replaced here, and a set+number match stays until TCGdex has an ID of its own.
  const mapping = ctx.db.prepare(
    `INSERT INTO card_mapping (tcgdex_id, cardmarket_id, tcgplayer_id, cardmarket_source, tcgplayer_source, updated_at)
     VALUES (?1, ?2, ?3, CASE WHEN ?2 IS NULL THEN NULL ELSE 'tcgdex' END,
             CASE WHEN ?3 IS NULL THEN NULL ELSE 'tcgdex' END, ?4)
     ON CONFLICT (tcgdex_id) DO UPDATE SET
       cardmarket_id = CASE WHEN ${keep("cardmarket")} THEN card_mapping.cardmarket_id ELSE excluded.cardmarket_id END,
       cardmarket_source = CASE WHEN ${keep("cardmarket")} THEN card_mapping.cardmarket_source
                                ELSE excluded.cardmarket_source END,
       tcgplayer_id = CASE WHEN ${keep("tcgplayer")} THEN card_mapping.tcgplayer_id ELSE excluded.tcgplayer_id END,
       tcgplayer_source = CASE WHEN ${keep("tcgplayer")} THEN card_mapping.tcgplayer_source
                               ELSE excluded.tcgplayer_source END,
       updated_at = excluded.updated_at`,
  );
  const statements: D1PreparedStatement[] = [];
  for (const c of cards) {
    statements.push(card.bind(c.id, lang, c.set.id, c.localId, c.name, at));
    statements.push(
      mapping.bind(
        c.id,
        pickProduct(c.variants_detailed, "cardmarket"),
        lang === "ja" ? null : pickProduct(c.variants_detailed, "tcgplayer"),
        at,
      ),
    );
  }
  await ctx.db.batch([...statements, ...applyOverridesStatements(ctx.db, at)]);
}

function keep(market: "cardmarket" | "tcgplayer"): string {
  return `(card_mapping.${market}_source = 'override'
           OR (card_mapping.${market}_source = 'match' AND excluded.${market}_id IS NULL))`;
}

/**
 * The marketplace product that stands for the card itself. Stamped prints (league, prerelease, 1st edition) and
 * special foils (JP Poké Ball / Master Ball reverses) are separate products, so the plain standard print wins:
 * no stamp, no special foil, unlimited, and normal before holo before reverse. Normal and reverse share one
 * product on both markets, which carry the reverse price as a sub-price.
 */
export function pickProduct(variants: TcgdexVariant[] | undefined, market: "cardmarket" | "tcgplayer"): number | null {
  const withId = (variants ?? []).filter((v) => typeof v.thirdParty?.[market] === "number");
  if (withId.length === 0) return null;
  const typeRank = (t?: string) => ({ normal: 0, holo: 1, reverse: 2 })[t ?? ""] ?? 3;
  const score = (v: TcgdexVariant) =>
    (v.stamp?.length ? 100 : 0) +
    (v.foil ? 50 : 0) +
    (v.size && v.size !== "standard" ? 50 : 0) +
    (v.subtype && v.subtype !== "unlimited" ? 20 : 0) +
    typeRank(v.type);
  const best = withId.reduce((a, b) => (score(b) < score(a) ? b : a));
  return best.thirdParty![market]!;
}
