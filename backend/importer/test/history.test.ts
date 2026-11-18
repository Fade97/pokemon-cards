import { mkdtempSync, mkdirSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterEach, describe, expect, it } from "vitest";
import { snapshotKey, type PriceHistoryFile } from "../../src/price-data";
import { SqliteDb } from "../db";
import { buildHistoryMonth, monthsToRebuild, publishHistory, snapshotMonths, type Snapshot } from "../history";
import { snapshot, type Sink } from "../publish";

const GENERATED = new Date("2026-11-18T04:40:00.000Z");

const MAPPING_COLUMNS = ["tcgdexId", "cardmarketId", "tcgplayerId"];
const CARDMARKET_COLUMNS = [
  "idProduct", "low", "trend", "avg", "avg1", "avg7", "avg30",
  "holoLow", "holoTrend", "holoAvg", "holoAvg1", "holoAvg7", "holoAvg30", "priceDate",
];
const TCGPLAYER_COLUMNS = ["productId", "subType", "low", "mid", "high", "market", "directLow", "priceDate"];

/** A snapshot in the shape publish.ts writes, with only the columns history reads filled in. */
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
const UNPRICED = "sv03.5-200";

const NOV_17 = day(
  "2026-11-17",
  [[PIKACHU, 1001, 2001], [MEW, 1002, null], [JP, 1003, null], [UNPRICED, null, null]],
  [[1001, 1.2, 2.4], [1002, 17.6, null], [1003, 0.5, null]],
  [[2001, "Normal", 0.8], [2001, "Reverse Holofoil", 1.6]],
);
// Mew's guide row has a 0 (no price), the JP card dropped out of the mapping, Pikachu's reverse holo has no market.
const NOV_18 = day(
  "2026-11-18",
  [[PIKACHU, 1001, 2001], [MEW, 1002, null], [UNPRICED, null, null]],
  [[1001, 1.25, 2.31], [1002, 0, null]],
  [[2001, "Normal", 0.82], [2001, "Reverse Holofoil", null]],
);
const OCT_31 = day("2026-10-31", [[PIKACHU, 1001, null]], [[1001, 9.99, null]], []);

describe("price history", () => {
  it("builds one file per set with cents per day, nulls for gaps and only priced series", () => {
    // Out of order and with a day of another month: the month's days are sorted, the other one ignored.
    const files = buildHistoryMonth("2026-11", [NOV_18, OCT_31, NOV_17], GENERATED);
    expect([...files.keys()].sort()).toEqual(["SV2a", "sv03.5"]);

    const set = files.get("sv03.5")!;
    expect(set).toEqual<PriceHistoryFile>({
      version: 1,
      set: "sv03.5",
      month: "2026-11",
      generatedAt: GENERATED.toISOString(),
      days: ["2026-11-17", "2026-11-18"],
      cards: {
        [PIKACHU]: {
          cardmarket: { normal: [120, 125], holo: [240, 231] },
          tcgplayer: { Normal: [80, 82], "Reverse Holofoil": [160, null] },
        },
        [MEW]: { cardmarket: { normal: [1760, null] } },
      },
    });
    // The JP card was only mapped on the first day; it still gets both days.
    expect(files.get("SV2a")!.cards[JP]).toEqual({ cardmarket: { normal: [50, null] } });
  });

  it("returns no files for a month without snapshots", () => {
    expect(buildHistoryMonth("2026-12", [NOV_17], GENERATED).size).toBe(0);
  });

  it("rebuilds the current month, and the previous one during a month's first days", () => {
    expect(monthsToRebuild(new Date("2026-11-18T04:30:00Z"))).toEqual(["2026-11"]);
    expect(monthsToRebuild(new Date("2026-12-02T04:30:00Z"))).toEqual(["2026-11", "2026-12"]);
    expect(monthsToRebuild(new Date("2027-01-01T04:30:00Z"))).toEqual(["2026-12", "2027-01"]);
  });
});

describe("publishHistory", () => {
  let dir: string | undefined;
  afterEach(() => {
    if (dir) rmSync(dir, { recursive: true, force: true });
    dir = undefined;
  });

  /** Writes the importer's real snapshot of `db` for `date` where the DirSink would put it. */
  async function writeSnapshot(db: SqliteDb, filesDir: string, date: Date) {
    const path = join(filesDir, snapshotKey(date));
    mkdirSync(join(path, ".."), { recursive: true });
    writeFileSync(path, await snapshot(db, date));
  }

  it("reads the snapshots the publish wrote and writes history files to every sink", async () => {
    dir = mkdtempSync(join(tmpdir(), "history-"));
    const db = new SqliteDb(":memory:");
    const now = "2026-11-17T00:00:00Z";
    db.sqlite.exec(`
      INSERT INTO cards VALUES ('${PIKACHU}', 'en', 'sv03.5', '025', 'Pikachu', '${now}');
      INSERT INTO card_mapping VALUES ('${PIKACHU}', 1001, 2001, 'tcgdex', 'tcgdex', '${now}');
      INSERT INTO cardmarket_prices (id_product, trend, holo_trend, price_date) VALUES (1001, 1.2, 2.4, '${now}');
      INSERT INTO tcgplayer_prices (product_id, sub_type, market, price_date) VALUES (2001, 'Normal', 0.8, '${now}');
    `);
    await writeSnapshot(db, dir, new Date("2026-11-17T04:30:00Z"));
    db.sqlite.exec("UPDATE cardmarket_prices SET trend = 1.3; UPDATE tcgplayer_prices SET market = 0.9");
    await writeSnapshot(db, dir, new Date("2026-11-18T04:30:00Z"));
    await writeSnapshot(db, dir, new Date("2026-10-30T04:30:00Z"));
    db.close();

    expect(await snapshotMonths(dir)).toEqual(["2026-10", "2026-11"]);
    expect(await snapshotMonths(join(dir, "nothing-here"))).toEqual([]);

    const written = new Map<string, string>();
    const sink: Sink = {
      name: "memory",
      async put(key, body, type) {
        expect(type).toBe("application/json");
        written.set(key, new TextDecoder().decode(body));
      },
    };
    expect(await publishHistory(dir, [sink], ["2026-11"], GENERATED)).toBe("1 history files for 2026-11 to memory");
    const file: PriceHistoryFile = JSON.parse(written.get("history/sv03.5/2026-11.json")!);
    expect(file.days).toEqual(["2026-11-17", "2026-11-18"]);
    expect(file.cards[PIKACHU]).toEqual({
      cardmarket: { normal: [120, 130], holo: [240, 240] },
      tcgplayer: { Normal: [80, 90] },
    });

    written.clear();
    expect(await publishHistory(dir, [sink], ["2026-10", "2026-11", "2026-12"], GENERATED)).toBe(
      "2 history files for 2026-10, 2026-11, 2026-12 to memory",
    );
    expect([...written.keys()].sort()).toEqual(["history/sv03.5/2026-10.json", "history/sv03.5/2026-11.json"]);
  });
});
