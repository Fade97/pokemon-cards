import { env } from "cloudflare:workers";
import { beforeEach, describe, expect, it } from "vitest";
import { guideDate, PRICE_GUIDE_URL } from "../src/import/cardmarket";
import type { ImportContext, ImportMessage } from "../src/import/context";
import { JsonArrayStream } from "../src/import/json-stream";
import { snapshotKey } from "../src/import/snapshot";
import { normalizeNumber, TCGCSV } from "../src/import/tcgcsv";
import { pickProduct, TCGDEX_API } from "../src/import/tcgdex";
import { consumeBatch, runJob, runScheduled, startNightly } from "../src/nightly";
import { getPrices } from "../src/prices";

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
    db: env.DB,
    snapshots: env.SNAPSHOTS,
    queue: {
      async send(body, options) {
        sent.push({ body, delaySeconds: options?.delaySeconds });
      },
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

async function reset() {
  await env.DB.batch(
    [
      "DELETE FROM tcgplayer_prices",
      "DELETE FROM cardmarket_prices",
      "DELETE FROM card_mapping",
      "DELETE FROM mapping_overrides",
      "DELETE FROM cards",
      "DELETE FROM tcgdex_sets",
      "DELETE FROM import_runs",
    ].map((sql) => env.DB.prepare(sql)),
  );
}

beforeEach(reset);

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
    expect(snapshotKey(new Date("2026-01-05T23:59:59Z"))).toBe("prices/2026/01/05.json.gz");
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
    expect(sent.filter((m) => m.body.type === "tcgdex-sets").map((m) => m.delaySeconds)).toEqual(Array(9).fill(0));
    expect(sent.filter((m) => m.body.type !== "tcgdex-sets")).toEqual([
      { body: { type: "cardmarket" }, delaySeconds: 1800 },
      { body: { type: "tcgcsv-groups" }, delaySeconds: 1800 },
    ]);
    const run = await env.DB.prepare("SELECT job, status FROM import_runs").first();
    expect(run).toEqual({ job: "nightly", status: "ok" });
  });

  it("maps cards, stores only mapped prices and answers the price API", async () => {
    await runNight();

    const mapping = await env.DB.prepare(
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
    const sets = await env.DB.prepare("SELECT lang, set_id, abbreviation FROM tcgdex_sets ORDER BY set_id").all();
    expect(sets.results).toEqual([
      { lang: "ja", set_id: "SV2a", abbreviation: null },
      { lang: "en", set_id: "base1", abbreviation: null },
      { lang: "en", set_id: "sv03.5", abbreviation: "MEW" },
    ]);

    const cm = await env.DB.prepare("SELECT id_product FROM cardmarket_prices ORDER BY id_product").all();
    expect(cm.results.map((r) => r.id_product)).toEqual([719467, 733620]);
    const tp = await env.DB.prepare("SELECT product_id, sub_type FROM tcgplayer_prices ORDER BY 1, 2").all();
    expect(tp.results.map((r) => `${r.product_id} ${r.sub_type}`)).toEqual([
      "42382 Holofoil",
      "517033 Normal",
      "517033 Reverse Holofoil",
      "517034 Normal",
      "517035 Normal",
    ]);

    const prices = await getPrices(env.DB, ["sv03.5-025", "SV2a-025"]);
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

    const runs = await env.DB.prepare("SELECT job, status FROM import_runs ORDER BY id").all();
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
    await env.DB.prepare(
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
    await env.DB.prepare(
      `INSERT INTO mapping_overrides (tcgdex_id, cardmarket_id, tcgplayer_id, note, created_at)
       VALUES ('sv03.5-025', 1, NULL, 'test', '2026-11-17')`,
    ).run();
    // The override is applied at the start of the next night and survives the card resync.
    const { ctx } = makeContext(ROUTES);
    await startNightly(ctx);
    await runJob({ type: "tcgdex-cards", lang: "en", setId: "sv03.5", cardIds: ["sv03.5-025"] }, ctx);
    const row = () =>
      env.DB.prepare("SELECT cardmarket_id, cardmarket_source, tcgplayer_id FROM card_mapping WHERE tcgdex_id = 'sv03.5-025'").first();
    expect(await row()).toEqual({ cardmarket_id: 1, cardmarket_source: "override", tcgplayer_id: 517033 });

    await env.DB.prepare("DELETE FROM mapping_overrides").run();
    await startNightly(ctx);
    await runJob({ type: "tcgdex-cards", lang: "en", setId: "sv03.5", cardIds: ["sv03.5-025"] }, ctx);
    expect(await row()).toEqual({ cardmarket_id: 733620, cardmarket_source: "tcgdex", tcgplayer_id: 517033 });
  });

  it("writes a gzipped daily snapshot to R2", async () => {
    const { ctx } = await runNight();
    await runScheduled("30 7 * * *", ctx);
    const object = await env.SNAPSHOTS.get("prices/2026/11/17.json.gz");
    expect(object?.httpMetadata?.contentEncoding).toBe("gzip");
    const text = await new Response(object!.body.pipeThrough(new DecompressionStream("gzip"))).text();
    const snapshot = JSON.parse(text);
    expect(snapshot.version).toBe(1);
    expect(snapshot.date).toBe("2026-11-17");
    expect(snapshot.mapping.rows).toHaveLength(5);
    expect(snapshot.cardmarket.columns[0]).toBe("idProduct");
    expect(snapshot.cardmarket.rows.map((r: unknown[]) => r[0]).sort()).toEqual([719467, 733620]);
    expect(snapshot.tcgplayer.rows).toHaveLength(5);
    const run = await env.DB.prepare("SELECT status, detail FROM import_runs WHERE job = 'snapshot'").first<any>();
    expect(run.status).toBe("ok");
    expect(run.detail).toMatch(/^prices\/2026\/11\/17\.json\.gz: 5 mapping, 2 cardmarket, 5 tcgplayer/);
  });
});

describe("queue consumer", () => {
  function message(body: ImportMessage, attempts: number) {
    const state = { acked: false, retried: false };
    return {
      state,
      msg: {
        id: "1", timestamp: NOW, body, attempts,
        ack: () => { state.acked = true; },
        retry: () => { state.retried = true; },
      } as unknown as Message<ImportMessage>,
    };
  }
  const batch = (...messages: Message<ImportMessage>[]) =>
    ({ queue: "pokemon-cards-import", messages }) as unknown as MessageBatch<ImportMessage>;

  it("acks finished jobs", async () => {
    const { ctx } = makeContext(ROUTES);
    const m = message({ type: "tcgdex-set", lang: "en", setId: "base1" }, 1);
    await consumeBatch(batch(m.msg), ctx);
    expect(m.state).toEqual({ acked: true, retried: false });
  });

  it("retries failed jobs and records the last failed attempt", async () => {
    const { ctx } = makeContext({}); // every request is a 404
    const early = message({ type: "tcgcsv-group", groupId: 1, abbreviation: null, priceDate: "x" }, 1);
    await consumeBatch(batch(early.msg), ctx);
    expect(early.state).toEqual({ acked: false, retried: true });
    expect(await env.DB.prepare("SELECT COUNT(*) AS n FROM import_runs").first()).toEqual({ n: 0 });

    const last = message({ type: "tcgcsv-group", groupId: 1, abbreviation: null, priceDate: "x" }, 4);
    await consumeBatch(batch(last.msg), ctx);
    expect(last.state.retried).toBe(true);
    const run = await env.DB.prepare("SELECT job, status, detail FROM import_runs").first();
    expect(run).toEqual({
      job: "tcgcsv-group:1",
      status: "failed",
      detail: `GET ${TCGCSV}/tcgplayer/3/1/products: HTTP 404`,
    });
  });
});
