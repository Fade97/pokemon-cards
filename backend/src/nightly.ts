// Nightly import (issue #4, ADR 0004): TCGdex cards + marketplace IDs, Cardmarket price guide, TCGCSV prices,
// and a daily snapshot to R2.
//
// The cron only queues work. Each queue message is one small job with its own CPU, memory and subrequest budget
// (#30): a TCGdex set list per language, a set, a chunk of 25 cards, the price guide, the TCGCSV group list, one
// TCGCSV group. Price jobs are delayed so tonight's new cards are mapped before their prices are looked up.
//
//   04:30 UTC  nightly   apply overrides, queue tcgdex-sets × language, cardmarket (+30 min), tcgcsv-groups (+30 min)
//   07:30 UTC  snapshot  write the day's prices to R2

import {
  enqueue,
  errorText,
  recordFailure,
  tracked,
  type ImportContext,
  type ImportMessage,
} from "./import/context";
import { importCardmarket } from "./import/cardmarket";
import { applyOverridesStatements } from "./import/overrides";
import { writeSnapshot } from "./import/snapshot";
import { importGroup, queueGroups } from "./import/tcgcsv";
import { queueDueSets, syncCards, syncSet } from "./import/tcgdex";

export const CRON_IMPORT = "30 4 * * *";
export const CRON_SNAPSHOT = "30 7 * * *";

/** v1 languages (DESIGN.md). Non-English ones only contribute sets English lacks. */
export const IMPORT_LANGS = ["en", "ja", "de", "fr", "it", "es", "pt", "nl", "pl"] as const;

/** Gives tonight's TCGdex jobs a head start before prices are matched against the mapping. */
const PRICE_JOB_DELAY_SECONDS = 30 * 60;

/** Must match `max_retries` of the queue consumer in wrangler.jsonc. */
export const MAX_RETRIES = 3;

export async function runScheduled(cron: string, ctx: ImportContext): Promise<void> {
  if (cron === CRON_IMPORT) await startNightly(ctx);
  else if (cron === CRON_SNAPSHOT) await tracked(ctx, "snapshot", () => writeSnapshot(ctx));
  else console.warn("unknown cron", cron);
}

export async function startNightly(ctx: ImportContext): Promise<void> {
  await tracked(ctx, "nightly", async () => {
    await ctx.db.batch(applyOverridesStatements(ctx.db, ctx.now().toISOString()));
    await enqueue(
      ctx,
      IMPORT_LANGS.map((lang): ImportMessage => ({ type: "tcgdex-sets", lang })),
    );
    await enqueue(ctx, [{ type: "cardmarket" }, { type: "tcgcsv-groups" }], PRICE_JOB_DELAY_SECONDS);
    return `queued ${IMPORT_LANGS.length} TCGdex languages, Cardmarket and TCGCSV`;
  });
}

export async function runJob(msg: ImportMessage, ctx: ImportContext): Promise<void> {
  switch (msg.type) {
    case "tcgdex-sets":
      return tracked(ctx, `tcgdex:${msg.lang}`, () => queueDueSets(ctx, msg.lang));
    case "tcgdex-set":
      return syncSet(ctx, msg.lang, msg.setId);
    case "tcgdex-cards":
      return syncCards(ctx, msg.lang, msg.cardIds);
    case "cardmarket":
      return tracked(ctx, "cardmarket", () => importCardmarket(ctx));
    case "tcgcsv-groups":
      return tracked(ctx, "tcgcsv", () => queueGroups(ctx));
    case "tcgcsv-group":
      return importGroup(ctx, msg);
  }
}

/** A short name for a job in `import_runs`, e.g. `tcgcsv-group:23237`. */
export function jobName(msg: ImportMessage): string {
  switch (msg.type) {
    case "tcgdex-set":
      return `tcgdex-set:${msg.lang}/${msg.setId}`;
    case "tcgdex-cards":
      return `tcgdex-cards:${msg.lang}/${msg.setId}:${msg.cardIds[0]}`;
    case "tcgcsv-group":
      return `tcgcsv-group:${msg.groupId}`;
    default:
      return msg.type;
  }
}

/**
 * Queue consumer. A failed job is retried with a delay; on its last attempt the failure is written to
 * `import_runs`, and the message then moves to the dead-letter queue.
 */
export async function consumeBatch(batch: MessageBatch<ImportMessage>, ctx: ImportContext): Promise<void> {
  for (const msg of batch.messages) {
    try {
      await runJob(msg.body, ctx);
      msg.ack();
    } catch (e) {
      console.error("import job failed", jobName(msg.body), `attempt ${msg.attempts}`, e);
      if (msg.attempts > MAX_RETRIES) {
        await recordFailure(ctx, jobName(msg.body), errorText(e)).catch(() => {});
      }
      msg.retry({ delaySeconds: 120 });
    }
  }
}
