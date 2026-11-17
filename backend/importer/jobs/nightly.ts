// One night of the price import (issue #4, ADR 0004): TCGdex cards + marketplace IDs, the Cardmarket price
// guide and TCGCSV prices. It runs on Vulcan from a systemd timer (../run.ts); publishing the files to R2
// happens after it (../publish.ts).
//
// The work is split into small jobs that queue follow-up jobs: a TCGdex set list per language, a set, a chunk of
// 25 cards, the price guide, the TCGCSV group list, one TCGCSV group. A failed job is retried and then recorded
// in `import_runs` without stopping the others, so one broken set or group never costs the whole night.
//
//   start      apply overrides, queue tcgdex-sets × language, then (delayed) cardmarket and tcgcsv-groups
//   jobs       undelayed jobs run first, so tonight's new cards are mapped before prices are matched

import { importCardmarket } from "./cardmarket";
import {
  errorText,
  recordFailure,
  tracked,
  type ImportContext,
  type ImportMessage,
  type ImportQueue,
} from "./context";
import { applyOverridesStatements } from "./overrides";
import { importGroup, queueGroups } from "./tcgcsv";
import { queueDueSets, syncCards, syncSet } from "./tcgdex";

/** v1 languages (DESIGN.md). Non-English ones only contribute sets English lacks. */
export const IMPORT_LANGS = ["en", "ja", "de", "fr", "it", "es", "pt", "nl", "pl"] as const;

/** Orders the price jobs after the TCGdex jobs; the in-process queue only uses it for ordering. */
const PRICE_JOB_DELAY_SECONDS = 30 * 60;

/** Attempts per job before it is recorded as failed. */
export const MAX_ATTEMPTS = 4;

export async function startNightly(ctx: ImportContext, langs: readonly string[] = IMPORT_LANGS): Promise<void> {
  await tracked(ctx, "nightly", async () => {
    await ctx.db.batch(applyOverridesStatements(ctx.db, ctx.now().toISOString()));
    await ctx.queue.sendBatch(langs.map((lang) => ({ body: { type: "tcgdex-sets", lang } as ImportMessage })));
    await ctx.queue.sendBatch([
      { body: { type: "cardmarket" }, delaySeconds: PRICE_JOB_DELAY_SECONDS },
      { body: { type: "tcgcsv-groups" }, delaySeconds: PRICE_JOB_DELAY_SECONDS },
    ]);
    return `queued ${langs.length} TCGdex languages, Cardmarket and TCGCSV`;
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

interface Pending {
  body: ImportMessage;
  delaySeconds: number;
  seq: number;
}

/** An in-process queue: jobs run one at a time, undelayed ones first, each retried with a growing pause. */
export class LocalQueue implements ImportQueue {
  private readonly pending: Pending[] = [];
  private seq = 0;
  /** Jobs that failed every attempt, by name. */
  readonly failed: string[] = [];
  done = 0;

  constructor(private readonly retryPauseMs = 5_000) {}

  async sendBatch(messages: Iterable<{ body: ImportMessage; delaySeconds?: number }>): Promise<void> {
    for (const m of messages) this.pending.push({ body: m.body, delaySeconds: m.delaySeconds ?? 0, seq: this.seq++ });
  }

  async drain(ctx: ImportContext, log: (line: string) => void = () => {}): Promise<void> {
    for (;;) {
      if (this.pending.length === 0) return;
      // Lowest delay first, then in the order sent.
      let next = 0;
      for (let i = 1; i < this.pending.length; i++) {
        const a = this.pending[i]!;
        const b = this.pending[next]!;
        if (a.delaySeconds < b.delaySeconds || (a.delaySeconds === b.delaySeconds && a.seq < b.seq)) next = i;
      }
      const [job] = this.pending.splice(next, 1);
      await this.runWithRetries(job!.body, ctx, log);
    }
  }

  private async runWithRetries(msg: ImportMessage, ctx: ImportContext, log: (line: string) => void): Promise<void> {
    for (let attempt = 1; ; attempt++) {
      try {
        await runJob(msg, ctx);
        this.done++;
        return;
      } catch (e) {
        log(`${jobName(msg)} attempt ${attempt} failed: ${errorText(e)}`);
        if (attempt >= MAX_ATTEMPTS) {
          this.failed.push(jobName(msg));
          await recordFailure(ctx, jobName(msg), errorText(e)).catch(() => {});
          return;
        }
        await new Promise((r) => setTimeout(r, this.retryPauseMs * attempt));
      }
    }
  }
}
