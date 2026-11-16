// Nightly import entry point. The real import (Cardmarket price guide + TCGCSV + mapping + R2 snapshot)
// is issue #4; until then each run is recorded as skipped so the cron wiring can be watched in D1.

export async function runNightlyImport(env: Env, now: Date = new Date()): Promise<void> {
  const at = now.toISOString();
  await env.DB.prepare(
    "INSERT INTO import_runs (started_at, finished_at, status, detail) VALUES (?, ?, 'skipped', ?)",
  )
    .bind(at, at, "import not implemented yet (#4)")
    .run();
}
