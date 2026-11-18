import { env, exports } from "cloudflare:workers";
import { beforeEach, describe, expect, it } from "vitest";
import { TestTokenEntitlements, sha256Hex } from "../src/entitlements";
import type { PriceHistoryFile } from "../src/price-data";

/** Its SHA-256 is the PRO_TEST_TOKENS binding in vitest.config.ts. */
const PRO_TOKEN = "test-pro-token-0123456789-abcdefghijklmnop";
const OTHER_TOKEN = "someone-else-0123456789-abcdefghijklmnopqr";

let clients = 0;

function get(path: string, token: string | null = PRO_TOKEN, method = "GET") {
  const headers = new Headers({ "CF-Connecting-IP": `203.0.113.${++clients}` });
  if (token) headers.set("Authorization", `Bearer ${token}`);
  return exports.default.fetch(`https://api.test${path}`, { method, headers });
}

const SV035: PriceHistoryFile = {
  version: 1,
  set: "sv03.5",
  month: "2026-11",
  generatedAt: "2026-11-18T04:40:00.000Z",
  days: ["2026-11-17", "2026-11-18"],
  cards: {
    "sv03.5-025": {
      cardmarket: { normal: [120, 125], holo: [240, null] },
      tcgplayer: { Normal: [80, 82] },
    },
  },
};
const SV2A: PriceHistoryFile = {
  ...SV035,
  set: "SV2a",
  cards: { "SV2a-025": { cardmarket: { normal: [310, 305] } } },
};

beforeEach(async () => {
  const listed = await env.PRICES.list({ prefix: "history/" });
  await Promise.all(listed.objects.map((o) => env.PRICES.delete(o.key)));
  await env.PRICES.put("history/sv03.5/2026-11.json", JSON.stringify(SV035));
  await env.PRICES.put("history/SV2a/2026-11.json", JSON.stringify(SV2A));
  await env.PRICES.put("history/sv03.5/2026-10.json", JSON.stringify({ ...SV035, month: "2026-10" }));
});

describe("GET /v1/history/:month", () => {
  it("streams the month's file of every requested set, null where there is none", async () => {
    const res = await get("/v1/history/2026-11?sets=sv03.5,sv08,SV2a,sv03.5");
    expect(res.status).toBe(200);
    expect(res.headers.get("Content-Type")).toBe("application/json");
    expect(res.headers.get("Cache-Control")).toBe("private, max-age=3600");
    expect(await res.json()).toEqual({ month: "2026-11", sets: { "sv03.5": SV035, sv08: null, SV2a: SV2A } });
  });

  it("answers 401 without a token and 403 for a token without Pro", async () => {
    const anonymous = await get("/v1/history/2026-11?sets=sv03.5", null);
    expect(anonymous.status).toBe(401);
    expect(anonymous.headers.get("WWW-Authenticate")).toBe("Bearer");
    expect((await get("/v1/history/2026-11?sets=sv03.5", OTHER_TOKEN)).status).toBe(403);
    // A malformed header is no token at all.
    expect((await get("/v1/history/2026-11?sets=sv03.5", "short")).status).toBe(401);
  });

  it("rejects bad months, set lists and methods", async () => {
    for (const path of [
      "/v1/history/2026-13?sets=sv03.5",
      "/v1/history/2026-1?sets=sv03.5",
      "/v1/history/2026-11/x?sets=sv03.5",
      "/v1/history/2026-11",
      "/v1/history/2026-11?sets=",
      "/v1/history/2026-11?sets=.hidden",
      "/v1/history/2026-11?sets=a/b",
      `/v1/history/2026-11?sets=${Array.from({ length: 21 }, (_, i) => `s${i}`).join(",")}`,
    ]) {
      expect((await get(path)).status, path).toBe(400);
    }
    const post = await get("/v1/history/2026-11?sets=sv03.5", PRO_TOKEN, "POST");
    expect(post.status).toBe(405);
    expect(post.headers.get("Allow")).toBe("GET");
  });

  it("checks the token before the input", async () => {
    expect((await get("/v1/history/bad", null)).status).toBe(401);
  });
});

describe("TestTokenEntitlements", () => {
  const request = (token: string) => new Request("https://api.test/", { headers: { Authorization: `Bearer ${token}` } });

  it("knows nobody when the secret is unset or empty", async () => {
    expect(await new TestTokenEntitlements(undefined).check(request(PRO_TOKEN))).toBe("free");
    expect(await new TestTokenEntitlements(" ,\n").check(request(PRO_TOKEN))).toBe("free");
  });

  it("accepts digests separated by commas or whitespace, in either case", async () => {
    const digest = (await sha256Hex(PRO_TOKEN)).toUpperCase();
    const entitlements = new TestTokenEntitlements(`${"0".repeat(64)},\n ${digest}`);
    expect(await entitlements.check(request(PRO_TOKEN))).toBe("pro");
    expect(await entitlements.check(request(OTHER_TOKEN))).toBe("free");
    expect(await entitlements.check(new Request("https://api.test/"))).toBe("anonymous");
  });
});
