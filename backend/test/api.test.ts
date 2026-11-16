import { createExecutionContext, createScheduledController, waitOnExecutionContext } from "cloudflare:test";
import { env, exports } from "cloudflare:workers";
import { beforeEach, describe, expect, it } from "vitest";
import worker from "../src/index";

const NOW = "2026-11-16T04:30:00.000Z";

function call(path: string, init?: RequestInit) {
  return exports.default.fetch(`https://api.test${path}`, init);
}

function postIds(ids: unknown) {
  return call("/v1/prices", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ ids }),
  });
}

/** Pikachu 151 (EN prices on both markets), a JP card (Cardmarket only) and a card without any product. */
async function seed() {
  await env.DB.batch(
    [
      "DELETE FROM tcgplayer_prices",
      "DELETE FROM cardmarket_prices",
      "DELETE FROM card_mapping",
      "DELETE FROM cards",
      "DELETE FROM import_runs",
    ].map((sql) => env.DB.prepare(sql)),
  );
  const card = env.DB.prepare(
    "INSERT INTO cards (tcgdex_id, set_id, local_id, name, updated_at) VALUES (?, ?, ?, ?, ?)",
  );
  const mapping = env.DB.prepare(
    "INSERT INTO card_mapping (tcgdex_id, cardmarket_id, tcgplayer_id, source, updated_at) VALUES (?, ?, ?, ?, ?)",
  );
  await env.DB.batch([
    card.bind("sv03.5-025", "sv03.5", "025", "Pikachu", NOW),
    card.bind("SV2a-025", "SV2a", "025", "Pikachu", NOW),
    card.bind("sv03.5-999", "sv03.5", "999", "Nobody", NOW),
    mapping.bind("sv03.5-025", 712345, 501234, "tcgdex", NOW),
    mapping.bind("SV2a-025", 698765, null, "match", NOW),
    mapping.bind("sv03.5-999", null, null, "match", NOW),
    env.DB.prepare(
      `INSERT INTO cardmarket_prices (id_product, low, trend, avg, avg1, avg7, avg30,
         holo_low, holo_trend, holo_avg, holo_avg1, holo_avg7, holo_avg30, price_date)
       VALUES (712345, 0.5, 1.2, 1.1, 1.0, 1.15, 1.3, 1.5, 2.4, 2.2, 2.0, 2.3, 2.5, '2026-11-16T02:00:00Z'),
              (698765, 2.0, 3.1, 3.0, NULL, 3.2, 3.3, NULL, NULL, NULL, NULL, NULL, NULL, '2026-11-16T02:00:00Z')`,
    ),
    env.DB.prepare(
      `INSERT INTO tcgplayer_prices (product_id, sub_type, low, mid, high, market, direct_low, price_date)
       VALUES (501234, 'Normal', 0.4, 0.9, 5.0, 0.8, NULL, '2026-11-15T20:00:00Z'),
              (501234, 'Reverse Holofoil', 1.0, 1.8, 9.0, 1.6, 1.5, '2026-11-15T20:00:00Z')`,
    ),
  ]);
}

beforeEach(seed);

describe("GET /health", () => {
  it("answers after a D1 round trip", async () => {
    const res = await call("/health");
    expect(res.status).toBe(200);
    expect(await res.json()).toEqual({ ok: true });
  });
});

describe("GET /v1/prices/:id", () => {
  it("returns both markets with variants", async () => {
    const res = await call("/v1/prices/sv03.5-025");
    expect(res.status).toBe(200);
    expect(res.headers.get("Cache-Control")).toBe("public, max-age=3600");
    expect(await res.json()).toEqual({
      id: "sv03.5-025",
      cardmarket: {
        productId: 712345,
        currency: "EUR",
        mixedLanguages: true,
        priceDate: "2026-11-16T02:00:00Z",
        normal: { low: 0.5, trend: 1.2, avg: 1.1, avg1: 1.0, avg7: 1.15, avg30: 1.3 },
        holo: { low: 1.5, trend: 2.4, avg: 2.2, avg1: 2.0, avg7: 2.3, avg30: 2.5 },
      },
      tcgplayer: {
        productId: 501234,
        currency: "USD",
        priceDate: "2026-11-15T20:00:00Z",
        variants: {
          Normal: { low: 0.4, mid: 0.9, high: 5.0, market: 0.8, directLow: null },
          "Reverse Holofoil": { low: 1.0, mid: 1.8, high: 9.0, market: 1.6, directLow: 1.5 },
        },
      },
    });
  });

  it("returns null for a market without a product and for missing holo prices", async () => {
    const body = (await (await call("/v1/prices/SV2a-025")).json()) as any;
    expect(body.tcgplayer).toBeNull();
    expect(body.cardmarket.holo).toBeNull();
    expect(body.cardmarket.normal.avg1).toBeNull();
  });

  it("keeps a mapped card whose markets have no product", async () => {
    const body = (await (await call("/v1/prices/sv03.5-999")).json()) as any;
    expect(body).toEqual({ id: "sv03.5-999", cardmarket: null, tcgplayer: null });
  });

  it("returns 404 for an unknown card and 400 for a malformed id", async () => {
    expect((await call("/v1/prices/sv99-001")).status).toBe(404);
    expect((await call("/v1/prices/a%20b")).status).toBe(400);
    expect((await call("/v1/prices/%E0%A4%A")).status).toBe(400);
  });

  it("only allows GET", async () => {
    const res = await call("/v1/prices/sv03.5-025", { method: "DELETE" });
    expect(res.status).toBe(405);
    expect(res.headers.get("Allow")).toBe("GET");
  });
});

describe("POST /v1/prices", () => {
  it("returns known cards and lists unknown ones, once each", async () => {
    const res = await postIds(["sv03.5-025", "SV2a-025", "sv99-001", "sv03.5-025"]);
    expect(res.status).toBe(200);
    const body = (await res.json()) as any;
    expect(Object.keys(body.prices).sort()).toEqual(["SV2a-025", "sv03.5-025"]);
    expect(body.prices["sv03.5-025"].tcgplayer.variants.Normal.market).toBe(0.8);
    expect(body.missing).toEqual(["sv99-001"]);
  });

  it("accepts 100 ids and rejects 101", async () => {
    const ids = Array.from({ length: 101 }, (_, i) => `sv03.5-${String(i).padStart(3, "0")}`);
    expect((await postIds(ids.slice(0, 100))).status).toBe(200);
    expect((await postIds(ids)).status).toBe(400);
  });

  it("rejects bad bodies", async () => {
    expect((await postIds([])).status).toBe(400);
    expect((await postIds("sv03.5-025")).status).toBe(400);
    expect((await postIds(["ok-1", 7])).status).toBe(400);
    const notJson = await call("/v1/prices", { method: "POST", body: "{" });
    expect(notJson.status).toBe(400);
  });

  it("counts ids before dropping duplicates", async () => {
    expect((await postIds(Array(101).fill("sv03.5-025"))).status).toBe(400);
  });

  it("rejects bodies over 16 KiB without parsing them", async () => {
    const big = JSON.stringify({ ids: ["sv03.5-025"], pad: "x".repeat(16 * 1024) });
    const res = await call("/v1/prices", { method: "POST", body: big });
    expect(res.status).toBe(413);
    expect(await res.json()).toEqual({ error: "body too large" });
  });
});

it("returns 404 for other paths", async () => {
  expect((await call("/")).status).toBe(404);
});

describe("cron", () => {
  it("records a nightly run", async () => {
    const ctx = createExecutionContext();
    await worker.scheduled(createScheduledController({ cron: "30 4 * * *" }), env, ctx);
    await waitOnExecutionContext(ctx);
    const run = await env.DB.prepare("SELECT status, detail FROM import_runs").first();
    expect(run).toEqual({ status: "skipped", detail: "import not implemented yet (#4)" });
  });
});
