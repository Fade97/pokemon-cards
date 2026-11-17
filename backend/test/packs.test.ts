import { env, exports } from "cloudflare:workers";
import { beforeAll, describe, expect, it } from "vitest";
import { packKey, parseRange, resolveRange } from "../src/packs";

let clients = 0;

function call(path: string, init: RequestInit = {}) {
  const headers = new Headers(init.headers);
  headers.set("CF-Connecting-IP", `203.0.113.${++clients % 250}`);
  return exports.default.fetch(`https://api.test${path}`, { ...init, headers });
}

const CATALOG = JSON.stringify({ formatVersion: 1, packs: { en: { version: 2 } } });
const INDEX = new Uint8Array(1000).map((_, i) => i % 251);

beforeAll(async () => {
  await env.PACKS.put("catalog.json", CATALOG, { httpMetadata: { contentType: "application/json" } });
  await env.PACKS.put("en/index-2.bin", INDEX, { httpMetadata: { contentType: "application/octet-stream" } });
  await env.PACKS.put("en/cards-2.json.gz", new Uint8Array([31, 139, 8]), {
    httpMetadata: { contentType: "application/gzip" },
  });
  await env.PACKS.put("models/mnv3-1a2b3c4d5e6f.tflite", new Uint8Array([1, 2, 3]));
});

describe("packKey", () => {
  it("maps paths to R2 keys and refuses everything else", () => {
    expect(packKey("/v1/packs/catalog.json")).toBe("catalog.json");
    expect(packKey("/v1/packs/en/index-2.bin")).toBe("en/index-2.bin");
    expect(packKey("/v1/packs/pt-br/cards-1.json.gz")).toBe("pt-br/cards-1.json.gz");
    expect(packKey("/v1/packs/models/mnv3-1a2b3c4d5e6f.tflite")).toBe("models/mnv3-1a2b3c4d5e6f.tflite");
    for (const bad of [
      "/v1/packs/",
      "/v1/packs/en",
      "/v1/packs/en/",
      "/v1/packs/en/a/b",
      "/v1/packs/EN/x.bin",
      "/v1/packs/secret/x.bin",
      "/v1/packs/en/.hidden",
      "/v1/packs/en/a..b",
      "/v1/packs/en/a%2Fb",
      "/v1/packs/catalog.json/x",
    ]) {
      expect(packKey(bad), bad).toBeNull();
    }
  });
});

describe("GET /v1/packs/...", () => {
  it("serves the catalog with a short cache and an ETag", async () => {
    const res = await call("/v1/packs/catalog.json");
    expect(res.status).toBe(200);
    expect(res.headers.get("Content-Type")).toBe("application/json");
    expect(res.headers.get("Cache-Control")).toBe("public, max-age=300");
    expect(res.headers.get("ETag")).toMatch(/^".+"$/);
    expect(res.headers.get("Accept-Ranges")).toBe("bytes");
    expect(await res.text()).toBe(CATALOG);
  });

  it("serves versioned files as immutable", async () => {
    const res = await call("/v1/packs/en/index-2.bin");
    expect(res.status).toBe(200);
    expect(res.headers.get("Cache-Control")).toBe("public, max-age=31536000, immutable");
    expect(res.headers.get("Content-Type")).toBe("application/octet-stream");
    expect(new Uint8Array(await res.arrayBuffer())).toEqual(INDEX);

    const gz = await call("/v1/packs/en/cards-2.json.gz");
    expect(gz.headers.get("Content-Type")).toBe("application/gzip");
    // Stored compressed bytes are served as they are, so their SHA-256 matches the catalog.
    expect(gz.headers.get("Content-Encoding")).toBeNull();
    expect([...new Uint8Array(await gz.arrayBuffer())]).toEqual([31, 139, 8]);

    const model = await call("/v1/packs/models/mnv3-1a2b3c4d5e6f.tflite");
    expect(model.status).toBe(200);
    expect(model.headers.get("Content-Type")).toBe("application/octet-stream");
    await model.arrayBuffer();
  });

  it("answers If-None-Match with 304 and If-Match mismatches with 412", async () => {
    const first = await call("/v1/packs/en/index-2.bin");
    await first.arrayBuffer();
    const etag = first.headers.get("ETag")!;
    const again = await call("/v1/packs/en/index-2.bin", { headers: { "If-None-Match": etag } });
    expect(again.status).toBe(304);
    expect(again.headers.get("ETag")).toBe(etag);
    expect(await again.text()).toBe("");
    const changed = await call("/v1/packs/en/index-2.bin", { headers: { "If-None-Match": '"other"' } });
    expect(changed.status).toBe(200);
    await changed.arrayBuffer();
    const ifMatch = await call("/v1/packs/en/index-2.bin", { headers: { "If-Match": '"other"' } });
    expect(ifMatch.status).toBe(412);
  });

  it("serves byte ranges for resumed downloads", async () => {
    const res = await call("/v1/packs/en/index-2.bin", { headers: { Range: "bytes=100-199" } });
    expect(res.status).toBe(206);
    expect(res.headers.get("Content-Range")).toBe("bytes 100-199/1000");
    expect(new Uint8Array(await res.arrayBuffer())).toEqual(INDEX.slice(100, 200));

    const open = await call("/v1/packs/en/index-2.bin", { headers: { Range: "bytes=990-" } });
    expect(open.status).toBe(206);
    expect(open.headers.get("Content-Range")).toBe("bytes 990-999/1000");
    expect(new Uint8Array(await open.arrayBuffer())).toEqual(INDEX.slice(990));

    const suffix = await call("/v1/packs/en/index-2.bin", { headers: { Range: "bytes=-5" } });
    expect(suffix.status).toBe(206);
    expect(suffix.headers.get("Content-Range")).toBe("bytes 995-999/1000");
    expect(new Uint8Array(await suffix.arrayBuffer())).toEqual(INDEX.slice(995));
  });

  it("refuses unsatisfiable ranges with 416", async () => {
    const res = await call("/v1/packs/en/index-2.bin", { headers: { Range: "bytes=5000-6000" } });
    expect(res.status).toBe(416);
    expect(res.headers.get("Content-Range")).toBe("bytes */1000");
    // A range running past the end is cut to the file.
    const tail = await call("/v1/packs/en/index-2.bin", { headers: { Range: "bytes=998-5000" } });
    expect(tail.status).toBe(206);
    expect(tail.headers.get("Content-Range")).toBe("bytes 998-999/1000");
    expect(new Uint8Array(await tail.arrayBuffer())).toEqual(INDEX.slice(998));
  });

  it("answers HEAD with the size and no body", async () => {
    const res = await call("/v1/packs/en/index-2.bin", { method: "HEAD" });
    expect(res.status).toBe(200);
    expect(res.headers.get("Content-Length")).toBe("1000");
    expect(res.headers.get("ETag")).toMatch(/^".+"$/);
  });

  it("returns 404 for missing or invalid files and 405 for writes", async () => {
    expect((await call("/v1/packs/en/index-9.bin")).status).toBe(404);
    expect((await call("/v1/packs/xx/%2e%2e%2fcatalog.json")).status).toBe(404);
    const put = await call("/v1/packs/en/index-2.bin", { method: "PUT", body: "x" });
    expect(put.status).toBe(405);
    expect(put.headers.get("Allow")).toBe("GET, HEAD");
  });
});

describe("parseRange / resolveRange", () => {
  it("handles the single-range forms and ignores the rest", () => {
    expect(parseRange("bytes=0-9")).toEqual({ start: 0, end: 9 });
    expect(parseRange("bytes=10-")).toEqual({ start: 10 });
    expect(parseRange("bytes=-10")).toEqual({ suffix: 10 });
    for (const h of [null, "", "bytes=-", "bytes=5-1", "bytes=0-1,5-9", "items=0-1", "bytes=a-b"]) {
      expect(parseRange(h), String(h)).toBeNull();
    }
    expect(resolveRange({ start: 0, end: 9 }, 5)).toEqual({ offset: 0, length: 5 });
    expect(resolveRange({ start: 5 }, 5)).toBeNull();
    expect(resolveRange({ suffix: 10 }, 5)).toEqual({ offset: 0, length: 5 });
    expect(resolveRange({ suffix: 0 }, 5)).toBeNull();
  });
});
