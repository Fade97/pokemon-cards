// Reads current prices for TCGdex card IDs from the price files in R2 (see price-data.ts).

import { META_KEY, shardKey, shardOf, type CardPrice, type PriceMeta, type PriceShard } from "./price-data";

export { isCardId } from "./price-data";

/** Cards per batch request; with cards grouped by set this stays at a handful of R2 reads. */
export const MAX_BATCH = 100;

/**
 * Looks up prices for distinct card IDs. Cards the importer has no file entry for are left out of the result,
 * so callers can report them as unknown.
 */
export async function getPrices(bucket: R2Bucket, ids: readonly string[]): Promise<Map<string, CardPrice>> {
  if (ids.length > MAX_BATCH) throw new RangeError(`at most ${MAX_BATCH} ids per query`);
  const byShard = new Map<string, string[]>();
  for (const id of ids) {
    const shard = shardOf(id);
    byShard.set(shard, [...(byShard.get(shard) ?? []), id]);
  }

  const result = new Map<string, CardPrice>();
  await Promise.all(
    [...byShard].map(async ([shard, shardIds]) => {
      const object = await bucket.get(shardKey(shard));
      if (!object) return; // a set the importer has not priced yet
      const { cards } = await object.json<PriceShard>();
      for (const id of shardIds) {
        // Own-property check: an ID like "constructor" must not pick up Object.prototype members.
        if (Object.hasOwn(cards, id)) result.set(id, cards[id]!);
      }
    }),
  );
  return result;
}

export async function getMeta(bucket: R2Bucket): Promise<PriceMeta | null> {
  const object = await bucket.get(META_KEY);
  return object ? object.json<PriceMeta>() : null;
}
