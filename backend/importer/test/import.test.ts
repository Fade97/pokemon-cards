import { gunzipSync } from "node:zlib";
import { beforeEach, describe, expect, it } from "vitest";
import { shardOf, snapshotKey, type PriceMeta, type PriceShard } from "../../src/price-data";
import { SqliteDb } from "../db";
import { guideDate, PRICE_GUIDE_URL } from "../jobs/cardmarket";
import type { ImportContext, ImportMessage } from "../jobs/context";
import { JsonArrayStream } from "../jobs/json-stream";
import { LocalQueue, runJob, startNightly } from "../jobs/nightly";
import { normalizeNumber, TCGCSV } from "../jobs/tcgcsv";
import { pickProduct, TCGDEX_API } from "../jobs/tcgdex";
import { publish, readAllPrices, type Sink } from "../publish";

const NOW = new Date("2026-11-17T04:30:00.000Z");

/** Serves canned bodies by URL and counts requests; anything else is a 404. */
function fakeFetch(routes: Record<string, unknown>) {
  const calls: string[] = [];
  const fn = (async (input: RequestInfo | URL) => {
    const url = String(input instanceof Request ? input.url : input);
    calls.push(url);
    if (!(url in routes)) return new Response("not found", { status: 404 });
    const body = routes[url];
    if (body instanceof Response) return body.clone();
    return new Response(typeof body === "string" ? body : JSON.stringify(body));
  }) as typeof fetch;
  return { fn, calls };
}

function makeContext(routes: Record<string, unknown> = {}) {
  const sent: { body: ImportMessage; delaySeconds?: number }[] = [];
  const net = fakeFetch(routes);
  const ctx: ImportContext = {
    db,
    queue: {
      async sendBatch(messages) {
        for (const m of messages) sent.push({ body: m.body, delaySeconds: m.delaySeconds });
      },
    },
    fetch: net.fn,
    now: () => NOW,
    maxSetsPerNight: 2,
  };
  return { ctx, sent, calls: net.calls };
}

/**
 * Runs every queued message once, including the ones jobs queue themselves. Like the real queue, a delayed
 * message only runs once nothing undelayed is left.
 */
async function drain(ctx: ImportContext, sent: { body: ImportMessage; delaySeconds?: number }[]) {
  const done = new Set<number>();
  for (;;) {
    let next = -1;
    sent.forEach((m, i) => {
      if (!done.has(i) && (next < 0 || (m.delaySeconds ?? 0) < (sent[next]!.delaySeconds ?? 0))) next = i;
    });
    if (next < 0) return;
    done.add(next);
    await runJob(sent[next]!.body, ctx);
  }
}

/** A fresh in-memory database with the importer schema for every test. */
let db: SqliteDb;
beforeEach(() => {
  db = new SqliteDb(":memory:");
});

function streamOf(text: string, chunkSize: number): ReadableStream<Uint8Array> {
  const bytes = new TextEncoder().encode(text);
  let offset = 0;
  return new ReadableStream({
    pull(controller) {
      if (offset >= bytes.length) return controller.close();
      controller.enqueue(bytes.slice(offset, offset + chunkSize));
      offset += chunkSize;
    },
  });
}

describe("JsonArrayStream", () => {
  const doc = JSON.stringify({
    version: 1,
    createdAt: "2026-11-16T02:48:57+0100",
    priceGuides: [
      { idProduct: 1, name: 'a "quoted" } brace ] and \\ backslash' },
      { idProduct: 2, nested: { list: [1, [2, 3]], s: "Pokémon é" } },
      [3, 4],
    ],
    after: { ignored: true },
  });

  for (const size of [1, 3, 7, 64, 100_000]) {
    it(`yields every item with ${size}-byte chunks`, async () => {
      const stream = new JsonArrayStream<any>("priceGuides");
      const items = [];
      for await (const item of stream.items(streamOf(doc, size))) items.push(item);
      expect(items).toEqual(JSON.parse(doc).priceGuides);
      expect(guideDate(stream.prefix)).toBe("2026-11-16T01:48:57.000Z");
    });
  }

  it("rejects a truncated array and a missing key", async () => {
    const drainAll = async (text: string) => {
      for await (const _ of new JsonArrayStream("priceGuides").items(streamOf(text, 5)));
    };
    await expect(drainAll(doc.slice(0, 120))).rejects.toThrow("truncated");
    await expect(drainAll('{"other":[1]}')).rejects.toThrow('no "priceGuides" array');
  });
});

describe("helpers", () => {
  it("picks the plain standard print over stamped and special-foil ones", () => {
    // Pikachu 151 (EN): normal + reverse share a product, stamped promos are separate products.
    expect(
      pickProduct(
        [
          { type: "normal", stamp: ["pokemon-together"], thirdParty: { cardmarket: 748474 } },
          { type: "reverse", size: "standard", thirdParty: { cardmarket: 733620, tcgplayer: 517033 } },
          { type: "normal", size: "standard", thirdParty: { cardmarket: 733620, tcgplayer: 517033 } },
          { type: "reverse", foil: "cosmos", thirdParty: { cardmarket: 794911 } },
        ],
        "cardmarket",
      ),
    ).toBe(733620);
    // Base Set Charizard: unlimited holo before the 1st edition shadowless print.
    expect(
      pickProduct(
        [
          { type: "holo", subtype: "shadowless", stamp: ["1st-edition"], thirdParty: { tcgplayer: 1 } },
          { type: "holo", subtype: "unlimited", thirdParty: { cardmarket: 273699, tcgplayer: 42382 } },
        ],
        "tcgplayer",
      ),
    ).toBe(42382);
    expect(pickProduct(undefined, "cardmarket")).toBeNull();
    expect(pickProduct([{ type: "normal" }], "tcgplayer")).toBeNull();
  });

  it("normalizes collector numbers", () => {
    expect(normalizeNumber("025")).toBe("25");
    expect(normalizeNumber(" 25 ")).toBe("25");
    expect(normalizeNumber("TG01")).toBe("TG1");
    expect(normalizeNumber("swsh001")).toBe("SWSH1");
    expect(normalizeNumber("0")).toBe("0");
  });

  it("builds one snapshot key per UTC day", () => {
    expect(snapshotKey(new Date("2026-01-05T23:59:59Z"))).toBe("snapshots/2026/01/05.json.gz");
  });

  it("shards card IDs by set", () => {
    expect(shardOf("sv03.5-025")).toBe("sv03.5");
    expect(shardOf("SV2a-025")).toBe("SV2a");
    expect(shardOf("swsh12pt5gg-GG01")).toBe("swsh12pt5gg");
    expect(shardOf("nodash")).toBe("_misc");
    expect(shardOf("..-1")).toBe("_misc");
  });
});

// --- one night against canned upstream data ---

const SETS_EN = [
  { id: "sv03.5", cardCount: { total: 3 } },
  { id: "base1", cardCount: { total: 1 } },
  { id: "empty", cardCount: { total: 0 } },
];
const SETS_JA = [{ id: "SV2a", cardCount: { total: 1 } }, { id: "sv03.5", cardCount: { total: 3 } }];

const variants = (cm: number | null, tp: number | null) => [
  { type: "normal", size: "standard", thirdParty: { ...(cm ? { cardmarket: cm } : {}), ...(tp ? { tcgplayer: tp } : {}) } },
];

const ROUTES: Record<string, unknown> = {
  [`${TCGDEX_API}/en/sets`]: SETS_EN,
  [`${TCGDEX_API}/ja/sets`]: SETS_JA,
  [`${TCGDEX_API}/en/sets/sv03.5`]: {
    id: "sv03.5",
    cardCount: { total: 3 },
    abbreviation: { official: "MEW" },
    cards: [{ id: "sv03.5-025" }, { id: "sv03.5-026" }, { id: "sv03.5-%3F" }, { id: "sv03.5-027" }],
  },
  [`${TCGDEX_API}/en/sets/base1`]: { id: "base1", cardCount: { total: 1 }, cards: [{ id: "base1-4" }] },
  [`${TCGDEX_API}/ja/sets/SV2a`]: { id: "SV2a", cardCount: { total: 1 }, cards: [{ id: "SV2a-025" }] },
  [`${TCGDEX_API}/en/cards/sv03.5-025`]: {
    id: "sv03.5-025", localId: "025", name: "Pikachu", set: { id: "sv03.5" }, variants_detailed: variants(733620, 517033),
  },
  // TCGdex knows the Cardmarket product but not the TCGplayer one: set + number matching fills it.
  [`${TCGDEX_API}/en/cards/sv03.5-026`]: {
    id: "sv03.5-026", localId: "026", name: "Raichu", set: { id: "sv03.5" }, variants_detailed: variants(733621, null),
  },
  // No marketplace IDs at all, and two TCGplayer products share its number: only the name decides.
  [`${TCGDEX_API}/en/cards/sv03.5-027`]: {
    id: "sv03.5-027", localId: "027", name: "Sandshrew", set: { id: "sv03.5" },
  },
  [`${TCGDEX_API}/en/cards/base1-4`]: {
    id: "base1-4", localId: "4", name: "Charizard", set: { id: "base1" }, variants_detailed: variants(273699, 42382),
  },
  // JP cards never get a TCGplayer ID, even if TCGdex had one.
  [`${TCGDEX_API}/ja/cards/SV2a-025`]: {
    id: "SV2a-025", localId: "025", name: "ピカチュウ", set: { id: "SV2a" }, variants_detailed: variants(719467, 999),
  },
  [PRICE_GUIDE_URL]: JSON.stringify({
    version: 1,
    createdAt: "2026-11-17T02:48:57+0100",
    priceGuides: [
      { idProduct: 733620, idCategory: 51, avg: 0.15, low: 0.02, trend: 0.15, avg1: 0.12, avg7: 0.16, avg30: 0.15,
        "avg-holo": 0.53, "low-holo": 0.04, "trend-holo": 0.64, "avg1-holo": 0.72, "avg7-holo": 0.62, "avg30-holo": 0.54 },
      { idProduct: 719467, idCategory: 51, avg: 2.22, low: 0.02, trend: 2.63, avg1: null, avg7: 0.78, avg30: 1.75,
        "avg-holo": null, "low-holo": null, "trend-holo": 0, "avg1-holo": null, "avg7-holo": null, "avg30-holo": null },
      { idProduct: 111111, idCategory: 52, avg: 99, low: 99, trend: 99 },
    ],
  }),
  [`${TCGCSV}/last-updated.txt`]: "2026-11-16T20:05:58+0000",
  [`${TCGCSV}/tcgplayer/3/groups`]: {
    results: [
      { groupId: 23237, abbreviation: "MEW" },
      { groupId: 604, abbreviation: "BS" },
    ],
  },
  [`${TCGCSV}/tcgplayer/3/23237/products`]: {
    results: [
      { productId: 501999, name: "151 Elite Trainer Box", extendedData: [] },
      { productId: 517033, name: "Pikachu - 025/165", extendedData: [{ name: "Number", value: "025/165" }] },
      { productId: 517034, name: "Raichu - 026/165", extendedData: [{ name: "Number", value: "026/165" }] },
      { productId: 517035, name: "Sandshrew - 027/165", extendedData: [{ name: "Number", value: "027/165" }] },
      { productId: 517036, name: "Nidoran F - 27/165", extendedData: [{ name: "Number", value: "27/165" }] },
      { productId: 517037, name: "Sandslash - 028/165", extendedData: [{ name: "Number", value: "028/165" }] },
    ],
  },
  [`${TCGCSV}/tcgplayer/3/23237/prices`]: {
    results: [
      { productId: 501999, subTypeName: "Normal", lowPrice: 100, midPrice: 120, highPrice: 300, marketPrice: 110, directLowPrice: null },
      { productId: 517033, subTypeName: "Normal", lowPrice: 0.05, midPrice: 0.3, highPrice: 19.66, marketPrice: 0.26, directLowPrice: null },
      { productId: 517033, subTypeName: "Reverse Holofoil", lowPrice: 0.2, midPrice: 0.79, highPrice: 75, marketPrice: 0.69, directLowPrice: 0.72 },
      { productId: 517034, subTypeName: "Normal", lowPrice: 0.1, midPrice: 0.2, highPrice: 1, marketPrice: 0.15, directLowPrice: null },
      { productId: 517035, subTypeName: "Normal", lowPrice: 0.1, midPrice: 0.2, highPrice: 1, marketPrice: 0.12, directLowPrice: null },
    ],
  },
  [`${TCGCSV}/tcgplayer/3/604/products`]: { results: [] },
  [`${TCGCSV}/tcgplayer/3/604/prices`]: {
    results: [{ productId: 42382, subTypeName: "Holofoil", lowPrice: 499, midPrice: 675, highPrice: 3499, marketPrice: 1001, directLowPrice: null }],
  },
};

/** The nightly cron plus every job it queues, with only EN and JA so the canned data stays small. */
async function runNight() {
  const { ctx, sent, calls } = makeContext(ROUTES);
  await startNightly(ctx);
  // Keep the languages with canned data; the rest would only see 404s.
  const kept = sent.filter((m) => m.body.type !== "tcgdex-sets" || ["en", "ja"].includes(m.body.lang));
  sent.length = 0;
  sent.push(...kept);
  await drain(ctx, sent);
  return { ctx, sent, calls };
}

describe("nightly import", () => {
  it("queues TCGdex per language now and the price jobs 30 minutes later", async () => {
    const { ctx, sent } = makeContext();
    await startNightly(ctx);
    expect(sent.filter((m) => m.body.type === "tcgdex-sets").map((m) => m.delaySeconds ?? 0)).toEqual(Array(9).fill(0));
    expect(sent.filter((m) => m.body.type !== "tcgdex-sets")).toEqual([
      { body: { type: "cardmarket" }, delaySeconds: 1800 },
      { body: { type: "tcgcsv-groups" }, delaySeconds: 1800 },
    ]);
    const run = await db.prepare("SELECT job, status FROM import_runs").first();
    expect(run).toEqual({ job: "nightly", status: "ok" });
  });

  it("maps cards, stores only mapped prices and answers the price API", async () => {
    await runNight();

    const mapping = await db.prepare(
      "SELECT tcgdex_id, cardmarket_id, tcgplayer_id, cardmarket_source, tcgplayer_source FROM card_mapping ORDER BY tcgdex_id",
    ).all();
    expect(mapping.results).toEqual([
      { tcgdex_id: "SV2a-025", cardmarket_id: 719467, tcgplayer_id: null, cardmarket_source: "tcgdex", tcgplayer_source: null },
      { tcgdex_id: "base1-4", cardmarket_id: 273699, tcgplayer_id: 42382, cardmarket_source: "tcgdex", tcgplayer_source: "tcgdex" },
      { tcgdex_id: "sv03.5-025", cardmarket_id: 733620, tcgplayer_id: 517033, cardmarket_source: "tcgdex", tcgplayer_source: "tcgdex" },
      { tcgdex_id: "sv03.5-026", cardmarket_id: 733621, tcgplayer_id: 517034, cardmarket_source: "tcgdex", tcgplayer_source: "match" },
      { tcgdex_id: "sv03.5-027", cardmarket_id: null, tcgplayer_id: 517035, cardmarket_source: null, tcgplayer_source: "match" },
    ]);

    // JA only adds the set English lacks; the empty set is skipped; the bad ID is dropped.
    const sets = await db.prepare("SELECT lang, set_id, abbreviation FROM tcgdex_sets ORDER BY set_id").all();
    expect(sets.results).toEqual([
      { lang: "ja", set_id: "SV2a", abbreviation: null },
      { lang: "en", set_id: "base1", abbreviation: null },
      { lang: "en", set_id: "sv03.5", abbreviation: "MEW" },
    ]);

    const cm = await db.prepare("SELECT id_product FROM cardmarket_prices ORDER BY id_product").all();
    expect(cm.results.map((r) => r.id_product)).toEqual([719467, 733620]);
    const tp = await db.prepare("SELECT product_id, sub_type FROM tcgplayer_prices ORDER BY 1, 2").all();
    expect(tp.results.map((r) => `${r.product_id} ${r.sub_type}`)).toEqual([
      "42382 Holofoil",
      "517033 Normal",
      "517033 Reverse Holofoil",
      "517034 Normal",
      "517035 Normal",
    ]);

    const prices = new Map((await readAllPrices(db)).map((p) => [p.id, p]));
    expect(prices.get("sv03.5-025")).toEqual({
      id: "sv03.5-025",
      cardmarket: {
        productId: 733620, currency: "EUR", mixedLanguages: true, priceDate: "2026-11-17T01:48:57.000Z",
        normal: { low: 0.02, trend: 0.15, avg: 0.15, avg1: 0.12, avg7: 0.16, avg30: 0.15 },
        holo: { low: 0.04, trend: 0.64, avg: 0.53, avg1: 0.72, avg7: 0.62, avg30: 0.54 },
      },
      tcgplayer: {
        productId: 517033, currency: "USD", priceDate: "2026-11-16T20:05:58.000Z",
        variants: {
          Normal: { low: 0.05, mid: 0.3, high: 19.66, market: 0.26, directLow: null },
          "Reverse Holofoil": { low: 0.2, mid: 0.79, high: 75, market: 0.69, directLow: 0.72 },
        },
      },
    });
    // A zero in the guide means "no price".
    expect(prices.get("SV2a-025")!.cardmarket!.holo).toBeNull();

    const runs = await db.prepare("SELECT job, status FROM import_runs ORDER BY id").all();
    expect(runs.results).toEqual([
      { job: "nightly", status: "ok" },
      { job: "tcgdex:en", status: "ok" },
      { job: "tcgdex:ja", status: "ok" },
      { job: "cardmarket", status: "ok" },
      { job: "tcgcsv", status: "ok" },
    ]);
  });

  it("syncs only due sets, at most maxSetsPerNight per language", async () => {
    const { ctx, sent } = makeContext(ROUTES);
    ctx.maxSetsPerNight = 1;
    await runJob({ type: "tcgdex-sets", lang: "en" }, ctx);
    expect(sent.map((m) => m.body)).toEqual([{ type: "tcgdex-set", lang: "en", setId: "sv03.5" }]);

    // Synced sets are skipped until their card count changes or they are 30 days old.
    await db.prepare(
      `INSERT INTO tcgdex_sets (lang, set_id, card_count, synced_at) VALUES
         ('en', 'sv03.5', 3, '2026-11-16T00:00:00Z'), ('en', 'base1', 1, '2026-10-01T00:00:00Z')`,
    ).run();
    sent.length = 0;
    ctx.maxSetsPerNight = 5;
    await runJob({ type: "tcgdex-sets", lang: "en" }, ctx);
    expect(sent.map((m) => m.body)).toEqual([{ type: "tcgdex-set", lang: "en", setId: "base1" }]);
  });

  it("lets overrides win over TCGdex and matches, and releases them when removed", async () => {
    await runNight();
    await db.prepare(
      `INSERT INTO mapping_overrides (tcgdex_id, cardmarket_id, tcgplayer_id, note, created_at)
       VALUES ('sv03.5-025', 1, NULL, 'test', '2026-11-17')`,
    ).run();
    // The override is applied at the start of the next night and survives the card resync.
    const { ctx } = makeContext(ROUTES);
    await startNightly(ctx);
    await runJob({ type: "tcgdex-cards", lang: "en", setId: "sv03.5", cardIds: ["sv03.5-025"] }, ctx);
    const row = () =>
      db.prepare("SELECT cardmarket_id, cardmarket_source, tcgplayer_id FROM card_mapping WHERE tcgdex_id = 'sv03.5-025'").first();
    expect(await row()).toEqual({ cardmarket_id: 1, cardmarket_source: "override", tcgplayer_id: 517033 });

    await db.prepare("DELETE FROM mapping_overrides").run();
    await startNightly(ctx);
    await runJob({ type: "tcgdex-cards", lang: "en", setId: "sv03.5", cardIds: ["sv03.5-025"] }, ctx);
    expect(await row()).toEqual({ cardmarket_id: 733620, cardmarket_source: "tcgdex", tcgplayer_id: 517033 });
  });

  it("publishes one price file per set, the snapshot and meta.json last", async () => {
    await runNight();
    const files = new Map<string, { body: Uint8Array; encoding?: string }>();
    const order: string[] = [];
    const sink: Sink = {
      name: "memory",
      async put(key, body, _type, encoding) {
        files.set(key, { body, encoding });
        order.push(key);
      },
    };
    expect(await publish(db, [sink], NOW)).toBe("5 cards in 3 files to memory");
    expect(order.at(-1)).toBe("current/meta.json");
    expect([...files.keys()].sort()).toEqual([
      "current/SV2a.json",
      "current/base1.json",
      "current/meta.json",
      "current/sv03.5.json",
      "snapshots/2026/11/17.json.gz",
    ]);

    const json = (key: string) => JSON.parse(new TextDecoder().decode(files.get(key)!.body));
    const shard: PriceShard = json("current/sv03.5.json");
    expect(Object.keys(shard.cards).sort()).toEqual(["sv03.5-025", "sv03.5-026", "sv03.5-027"]);
    expect(shard.cards["sv03.5-025"]!.tcgplayer!.variants.Normal!.market).toBe(0.26);
    const meta: PriceMeta = json("current/meta.json");
    expect(meta).toEqual({
      version: 1,
      generatedAt: NOW.toISOString(),
      cards: 5,
      shards: 3,
      cardmarketPriceDate: "2026-11-17T01:48:57.000Z",
      tcgplayerPriceDate: "2026-11-16T20:05:58.000Z",
    });

    const gz = files.get("snapshots/2026/11/17.json.gz")!;
    expect(gz.encoding).toBe("gzip");
    const snapshot = JSON.parse(gunzipSync(gz.body).toString("utf8"));
    expect(snapshot.version).toBe(1);
    expect(snapshot.date).toBe("2026-11-17");
    expect(snapshot.mapping.rows).toHaveLength(5);
    expect(snapshot.cardmarket.columns[0]).toBe("idProduct");
    expect(snapshot.cardmarket.rows.map((r: unknown[]) => r[0]).sort()).toEqual([719467, 733620]);
    expect(snapshot.tcgplayer.rows).toHaveLength(5);
  });
});

describe("LocalQueue", () => {
  it("runs undelayed jobs first, including the ones jobs queue themselves", async () => {
    const { ctx } = makeContext(ROUTES);
    const queue = new LocalQueue(0);
    ctx.queue = queue;
    await startNightly(ctx, ["en"]);
    await queue.drain(ctx);
    expect(queue.failed).toEqual([]);
    // The price jobs ran after the cards were mapped, so the new cards have prices.
    const n = await db.prepare("SELECT COUNT(*) AS n FROM cardmarket_prices").first();
    expect(n).toEqual({ n: 1 });
  });

  it("makes a set due again when one of its card chunks fails for good", async () => {
    const routes = { ...ROUTES };
    delete routes[`${TCGDEX_API}/en/cards/sv03.5-026`];
    const { ctx } = makeContext(routes);
    const queue = new LocalQueue(0);
    ctx.queue = queue;
    await queue.sendBatch([{ body: { type: "tcgdex-set", lang: "en", setId: "sv03.5" } }]);
    await queue.drain(ctx);
    expect(queue.failed).toEqual(["tcgdex-cards:en/sv03.5:sv03.5-025"]);
    const set = await db.prepare("SELECT card_count FROM tcgdex_sets WHERE set_id = 'sv03.5'").first();
    expect(set).toEqual({ card_count: -1 });

    // The next night picks it up again, even though it was synced moments ago.
    const sent: ImportMessage[] = [];
    ctx.queue = { async sendBatch(ms) { for (const m of ms) sent.push(m.body); } };
    await runJob({ type: "tcgdex-sets", lang: "en" }, ctx);
    expect(sent).toContainEqual({ type: "tcgdex-set", lang: "en", setId: "sv03.5" });
  });

  it("retries a failing job, records its final failure and carries on", async () => {
    const { ctx } = makeContext({}); // every request is a 404
    const queue = new LocalQueue(0);
    ctx.queue = queue;
    await queue.sendBatch([
      { body: { type: "tcgcsv-group", groupId: 1, abbreviation: null, priceDate: "x" } },
      { body: { type: "tcgcsv-group", groupId: 2, abbreviation: null, priceDate: "x" } },
    ]);
    const lines: string[] = [];
    await queue.drain(ctx, (l) => lines.push(l));
    expect(queue.failed).toEqual(["tcgcsv-group:1", "tcgcsv-group:2"]);
    expect(lines.filter((l) => l.startsWith("tcgcsv-group:1 attempt"))).toHaveLength(4);
    const runs = await db.prepare("SELECT job, status, detail FROM import_runs ORDER BY id").all();
    expect(runs.results).toEqual([
      { job: "tcgcsv-group:1", status: "failed", detail: `GET ${TCGCSV}/tcgplayer/3/1/products: HTTP 404` },
      { job: "tcgcsv-group:2", status: "failed", detail: `GET ${TCGCSV}/tcgplayer/3/2/products: HTTP 404` },
    ]);
  });
});
