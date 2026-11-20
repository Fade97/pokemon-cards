import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterEach, describe, expect, it } from "vitest";
import { snapshotKey, type PriceShard } from "../../src/price-data";
import { SqliteDb } from "../db";
import type { Snapshot } from "../history";
import { publish, snapshot, type Sink } from "../publish";
import { buildWeekAgo, readWeekAgo } from "../week-ago";

const NOW = new Date("2026-11-19T04:30:00.000Z");

const MAPPING_COLUMNS = ["tcgdexId", "cardmarketId", "tcgplayerId"];
const CARDMARKET_COLUMNS = [
  "idProduct", "low", "trend", "avg", "avg1", "avg7", "avg30",
  "holoLow", "holoTrend", "holoAvg", "holoAvg1", "holoAvg7", "holoAvg30", "priceDate",
];
const TCGPLAYER_COLUMNS = ["productId", "subType", "low", "mid", "high", "market", "directLow", "priceDate"];

/** A snapshot in the shape publish.ts writes, with only the columns the week-ago prices read filled in. */
function day(
  date: string,
  mapping: [string, number | null, number | null][],
  cardmarket: [number, number | null, number | null][],
  tcgplayer: [number, string, number | null][],
): Snapshot {
  return {
    version: 1,
    date,
    mapping: { columns: MAPPING_COLUMNS, rows: mapping },
    cardmarket: {
      columns: CARDMARKET_COLUMNS,
      rows: cardmarket.map(([id, trend, holo]) => [id, null, trend, null, null, null, null, null, holo, null, null, null, null, date]),
    },
    tcgplayer: {
      columns: TCGPLAYER_COLUMNS,
      rows: tcgplayer.map(([id, subType, market]) => [id, subType, null, null, null, market, null, date]),
    },
  };
}

const PIKACHU = "sv03.5-025";
const MEW = "sv03.5-151";
const JP = "SV2a-025";

describe("week-ago prices", () => {
  it("takes each printing's price 7 days back, carried over from at most 3 days before", () => {
    const week = buildWeekAgo(NOW, [
      // Outside the window: too old, and newer than a week.
      day("2026-11-08", [[MEW, 1002, null]], [[1002, 99, null]], []),
      day("2026-11-13", [[PIKACHU, 1001, 2001]], [[1001, 99, 99]], [[2001, "Normal", 99]]),
      // The window, out of order: Nov 12 is the target day, Nov 9–11 fill printings it lacks.
      day(
        "2026-11-12",
        [[PIKACHU, 1001, 2001], [MEW, 1002, null]],
        [[1001, 1.25, null], [1002, 0, null]],
        [[2001, "Normal", 0.82], [2001, "Reverse Holofoil", null]],
      ),
      day(
        "2026-11-09",
        [[PIKACHU, 1001, 2001], [MEW, 1002, null], [JP, 1003, null]],
        [[1001, 1.1, 2.3], [1002, 17.6, null], [1003, 0.5, null]],
        [[2001, "Normal", 0.7], [2001, "Reverse Holofoil", 1.6]],
      ),
    ]);

    expect(week.date).toBe("2026-11-12");
    expect(week.of(PIKACHU)).toEqual({
      cardmarket: { date: "2026-11-12", prices: { normal: 1.25, holo: 2.3 } },
      tcgplayer: { date: "2026-11-12", prices: { Normal: 0.82, "Reverse Holofoil": 1.6 } },
    });
    // A 0 on the target day is no price, so Nov 9's price stands; a card mapped only on Nov 9 keeps it too.
    expect(week.of(MEW)).toEqual({ cardmarket: { date: "2026-11-12", prices: { normal: 17.6 } }, tcgplayer: undefined });
    expect(week.of(JP).cardmarket?.prices).toEqual({ normal: 0.5 });
    expect(week.of("sv03.5-999")).toEqual({});
  });

  it("has nothing when the window has no snapshot", () => {
    const week = buildWeekAgo(NOW, [day("2026-11-08", [[MEW, 1002, null]], [[1002, 5, null]], [])]);
    expect(week.size).toBe(0);
    expect(week.of(MEW)).toEqual({});
  });
});

describe("publish with week-ago prices", () => {
  let dir: string | undefined;
  afterEach(() => {
    if (dir) rmSync(dir, { recursive: true, force: true });
    dir = undefined;
  });

  async function writeSnapshot(db: SqliteDb, filesDir: string, date: Date) {
    const path = join(filesDir, snapshotKey(date));
    mkdirSync(join(path, ".."), { recursive: true });
    writeFileSync(path, await snapshot(db, date));
  }

  it("reads the snapshots a week back and adds them to the price files, leaving the rest as it was", async () => {
    dir = mkdtempSync(join(tmpdir(), "week-ago-"));
    const db = new SqliteDb(":memory:");
    const at = "2026-11-10T00:00:00Z";
    db.sqlite.exec(`
      INSERT INTO cards VALUES ('${PIKACHU}', 'en', 'sv03.5', '025', 'Pikachu', '${at}');
      INSERT INTO cards VALUES ('${MEW}', 'en', 'sv03.5', '151', 'Mew', '${at}');
      INSERT INTO card_mapping VALUES ('${PIKACHU}', 1001, 2001, 'tcgdex', 'tcgdex', '${at}');
      INSERT INTO card_mapping VALUES ('${MEW}', 1002, NULL, 'tcgdex', 'tcgdex', '${at}');
      INSERT INTO cardmarket_prices (id_product, trend, holo_trend, price_date) VALUES (1001, 1.2, 2.4, '${at}');
      INSERT INTO tcgplayer_prices (product_id, sub_type, market, price_date) VALUES (2001, 'Normal', 0.8, '${at}');
    `);
    expect(await readWeekAgo(dir, NOW)).toBeNull();

    await writeSnapshot(db, dir, new Date("2026-11-11T04:30:00Z"));
    // Mew is priced only after the week-ago window; today Pikachu moved on both markets.
    db.sqlite.exec(`INSERT INTO cardmarket_prices (id_product, trend, price_date) VALUES (1002, 17.6, '${at}')`);
    await writeSnapshot(db, dir, new Date("2026-11-14T04:30:00Z"));
    db.sqlite.exec("UPDATE cardmarket_prices SET trend = 1.5 WHERE id_product = 1001; UPDATE tcgplayer_prices SET market = 0.7");

    const week = await readWeekAgo(dir, NOW);
    expect(week?.date).toBe("2026-11-12");

    const files = new Map<string, Uint8Array>();
    const sink: Sink = { name: "memory", async put(key, body) { files.set(key, body); } };
    await publish(db, [sink], NOW, week);
    db.close();

    const shard: PriceShard = JSON.parse(new TextDecoder().decode(files.get("current/sv03.5.json")!));
    const pikachu = shard.cards[PIKACHU]!;
    expect(pikachu.cardmarket!.normal!.trend).toBe(1.5);
    expect(pikachu.cardmarket!.weekAgo).toEqual({ date: "2026-11-12", prices: { normal: 1.2, holo: 2.4 } });
    expect(pikachu.tcgplayer!.variants.Normal!.market).toBe(0.7);
    expect(pikachu.tcgplayer!.weekAgo).toEqual({ date: "2026-11-12", prices: { Normal: 0.8 } });
    // No price in the window: no weekAgo key at all, the market as before #186.
    expect(Object.keys(shard.cards[MEW]!.cardmarket!).sort()).toEqual(
      ["currency", "holo", "mixedLanguages", "normal", "priceDate", "productId"],
    );
  });
});
