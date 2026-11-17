// Nightly price import on Vulcan: import into the local SQLite database, then publish the price files.
//
//   node dist/importer.mjs [--data <dir>] [--langs en,ja] [--sets-per-night 100] [--no-import] [--no-publish]
//
// <data> (default ~/.local/share/pokemon-cards-prices) holds `prices.db` and `files/`, a local copy of
// everything published. With R2_ENDPOINT, R2_ACCESS_KEY_ID, R2_SECRET_ACCESS_KEY and R2_BUCKET set, the files
// also go to R2, where the Worker reads them.
//
// Exit code 1 when a top-level job (nightly, tcgdex:<lang>, cardmarket, tcgcsv) or the publish failed, so the
// systemd unit shows up as failed; a failed single set or group is only logged and recorded in `import_runs`.

import { mkdirSync } from "node:fs";
import { homedir } from "node:os";
import { join } from "node:path";
import { parseArgs } from "node:util";
import { SqliteDb } from "./db";
import { IMPORT_LANGS, LocalQueue, startNightly } from "./jobs/nightly";
import { DirSink, publish, R2Sink, type Sink } from "./publish";

const { values: args } = parseArgs({
  options: {
    data: { type: "string", default: process.env.POKEMON_PRICES_DIR ?? join(homedir(), ".local/share/pokemon-cards-prices") },
    langs: { type: "string", default: IMPORT_LANGS.join(",") },
    "sets-per-night": { type: "string", default: "100" },
    "no-import": { type: "boolean", default: false },
    "no-publish": { type: "boolean", default: false },
  },
});

const log = (line: string) => console.log(`${new Date().toISOString()} ${line}`);

async function main(): Promise<number> {
  mkdirSync(args.data, { recursive: true });
  const db = new SqliteDb(join(args.data, "prices.db"));
  const now = new Date();
  let exitCode = 0;

  if (!args["no-import"]) {
    const queue = new LocalQueue();
    const ctx = {
      db,
      queue,
      fetch: globalThis.fetch.bind(globalThis),
      now: () => new Date(),
      maxSetsPerNight: Number(args["sets-per-night"]) || 100,
    };
    await startNightly(ctx, args.langs.split(",").map((l) => l.trim()).filter(Boolean));
    await queue.drain(ctx, log);
    log(`import: ${queue.done} jobs done, ${queue.failed.length} failed${queue.failed.length ? `: ${queue.failed.join(", ")}` : ""}`);

    const { results: runs } = await db
      .prepare("SELECT job, status, detail FROM import_runs WHERE started_at >= ? AND job NOT LIKE '%-%' ORDER BY id")
      .bind(now.toISOString())
      .all<{ job: string; status: string; detail: string | null }>();
    for (const r of runs) log(`${r.job}: ${r.status} ${r.detail ?? ""}`);
    if (runs.some((r) => r.status === "failed")) exitCode = 1;
  }

  if (!args["no-publish"]) {
    const sinks: Sink[] = [new DirSink(join(args.data, "files"))];
    const { R2_ENDPOINT, R2_ACCESS_KEY_ID, R2_SECRET_ACCESS_KEY, R2_BUCKET } = process.env;
    if (R2_ENDPOINT && R2_ACCESS_KEY_ID && R2_SECRET_ACCESS_KEY && R2_BUCKET) {
      sinks.push(
        new R2Sink({
          endpoint: R2_ENDPOINT,
          accessKeyId: R2_ACCESS_KEY_ID,
          secretAccessKey: R2_SECRET_ACCESS_KEY,
          bucket: R2_BUCKET,
        }),
      );
    } else {
      log("publish: no R2 credentials, writing local files only");
    }
    try {
      log(`publish: ${await publish(db, sinks, new Date())}`);
    } catch (e) {
      log(`publish failed: ${e instanceof Error ? e.message : String(e)}`);
      exitCode = 1;
    }
  }

  db.close();
  return exitCode;
}

process.exitCode = await main();
