// Manual mapping fixes (`mapping_overrides`). They are applied every night and after each card sync, so a fix
// added with `sqlite3` on Vulcan takes effect at the next run without touching the import code.

import type { Db, Stmt } from "../db";

/**
 * Statements that write all overrides into `card_mapping` and release markets whose override was removed.
 * An override needs the card in `cards` (foreign key), so one for a card the import has not seen yet waits
 * until that card's set is synced.
 */
export function applyOverridesStatements(db: Db, at: string): Stmt[] {
  const release = (market: "cardmarket" | "tcgplayer") =>
    db.prepare(
      // The ID stays until the next sync of the card replaces it; only the 'override' lock is dropped.
      `UPDATE card_mapping SET ${market}_source = NULL
        WHERE ${market}_source = 'override'
          AND tcgdex_id NOT IN (SELECT tcgdex_id FROM mapping_overrides WHERE ${market}_id IS NOT NULL)`,
    );
  return [
    release("cardmarket"),
    release("tcgplayer"),
    db
      .prepare(
        `INSERT INTO card_mapping (tcgdex_id, cardmarket_id, tcgplayer_id, cardmarket_source, tcgplayer_source, updated_at)
         SELECT o.tcgdex_id, o.cardmarket_id, o.tcgplayer_id,
                CASE WHEN o.cardmarket_id IS NULL THEN NULL ELSE 'override' END,
                CASE WHEN o.tcgplayer_id IS NULL THEN NULL ELSE 'override' END, ?
           FROM mapping_overrides o JOIN cards c ON c.tcgdex_id = o.tcgdex_id
          WHERE true
         ON CONFLICT (tcgdex_id) DO UPDATE SET
           cardmarket_id = COALESCE(excluded.cardmarket_id, card_mapping.cardmarket_id),
           cardmarket_source = CASE WHEN excluded.cardmarket_id IS NULL THEN card_mapping.cardmarket_source
                                    ELSE 'override' END,
           tcgplayer_id = COALESCE(excluded.tcgplayer_id, card_mapping.tcgplayer_id),
           tcgplayer_source = CASE WHEN excluded.tcgplayer_id IS NULL THEN card_mapping.tcgplayer_source
                                   ELSE 'override' END,
           updated_at = excluded.updated_at`,
      )
      .bind(at),
  ];
}
