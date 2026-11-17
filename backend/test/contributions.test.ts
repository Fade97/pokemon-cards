import { env, exports } from "cloudflare:workers";
import { describe, expect, it } from "vitest";
import { ImageError, sniffType, stripMetadata } from "../src/image-meta";

let clients = 0;

function call(path: string, init: RequestInit = {}, ip = `192.0.2.${++clients % 250}`) {
  const headers = new Headers(init.headers);
  headers.set("CF-Connecting-IP", ip);
  return exports.default.fetch(`https://api.test${path}`, { ...init, headers });
}

const text = (s: string) => [...new TextEncoder().encode(s)];

// ---------------------------------------------------------------------------------------------------------------
// Fixtures: structurally valid files (the server never decodes pixels)

function seg(marker: number, payload: number[]): number[] {
  const len = payload.length + 2;
  return [0xff, marker, len >> 8, len & 0xff, ...payload];
}

const ENTROPY = [0x12, 0xff, 0x00, 0x34, 0xff, 0xd0, 0x56]; // a stuffed 0xFF and a restart marker

/** Fixed 12-byte Adobe APP14 payload: "Adobe", version, two flag words, transform 1 (YCbCr). */
const ADOBE = [...text("Adobe"), 0, 100, 0, 0, 0, 0, 1];

function jpeg(
  opts: {
    exif?: boolean; xmp?: boolean; comment?: boolean; trailing?: boolean; jfif?: boolean; icc?: boolean;
    adobe?: boolean; adobeLong?: boolean; w?: number; h?: number;
  } = {},
) {
  const { w = 252, h = 352 } = opts;
  return new Uint8Array([
    0xff, 0xd8,
    ...(opts.jfif ? seg(0xe0, [...text("JFIF\0"), 1, 1, 0, 0, 1, 0, 1, 0, 0]) : []),
    ...(opts.exif ? seg(0xe1, [...text("Exif\0\0"), ...text("MM GPS 52.52N 13.40E Pixel 9")]) : []),
    ...(opts.xmp ? seg(0xe1, [...text("http://ns.adobe.com/xap/1.0/\0"), ...text("<x:xmpmeta/>")]) : []),
    ...(opts.comment ? seg(0xfe, text("taken at home")) : []),
    ...(opts.icc ? seg(0xe2, [...text("ICC_PROFILE\0"), 1, 1, ...text("desc: Max Mustermann, Berlin")]) : []),
    ...(opts.adobe ? seg(0xee, ADOBE) : []),
    ...(opts.adobeLong ? seg(0xee, [...ADOBE, ...text("hidden note")]) : []),
    ...seg(0xdb, [0, ...new Array(64).fill(1)]),
    ...seg(0xc0, [8, h >> 8, h & 0xff, w >> 8, w & 0xff, 1, 1, 0x11, 0]),
    ...seg(0xda, [1, 1, 0, 0, 63, 0]),
    ...ENTROPY,
    0xff, 0xd9,
    ...(opts.trailing ? text("appended secret") : []),
  ]);
}

function chunk(fourcc: string, data: number[]): number[] {
  const n = data.length;
  return [...text(fourcc), n & 0xff, (n >> 8) & 0xff, (n >> 16) & 0xff, n >>> 24, ...data, ...(n & 1 ? [0] : [])];
}

function webp(opts: { exif?: boolean; xmp?: boolean; icc?: boolean; anim?: boolean; w?: number; h?: number } = {}) {
  const { w = 252, h = 352 } = opts;
  const flags = 0x10 | (opts.icc ? 0x20 : 0) | (opts.exif ? 0x08 : 0) | (opts.xmp ? 0x04 : 0) | (opts.anim ? 0x02 : 0);
  const le24 = (v: number) => [v & 0xff, (v >> 8) & 0xff, (v >> 16) & 0xff];
  const bits = (w - 1) | ((h - 1) << 14);
  const body = [
    ...text("WEBP"),
    ...chunk("VP8X", [flags, 0, 0, 0, ...le24(w - 1), ...le24(h - 1)]),
    ...(opts.icc ? chunk("ICCP", text("desc: Max Mustermann, Berlin")) : []),
    ...(opts.anim ? chunk("ANIM", [0, 0, 0, 0, 0, 0]) : []),
    ...chunk("VP8L", [0x2f, bits & 0xff, (bits >> 8) & 0xff, (bits >> 16) & 0xff, (bits >>> 24) & 0xff, 7, 7]),
    ...(opts.exif ? chunk("EXIF", text("MM GPS 52.52N")) : []),
    ...(opts.xmp ? chunk("XMP ", text("<x:xmpmeta/>")) : []),
  ];
  const n = body.length;
  return new Uint8Array([...text("RIFF"), n & 0xff, (n >> 8) & 0xff, (n >> 16) & 0xff, n >>> 24, ...body]);
}

function contains(hay: Uint8Array, needle: string): boolean {
  return new TextDecoder("latin1").decode(hay).includes(needle);
}

// ---------------------------------------------------------------------------------------------------------------

describe("stripMetadata", () => {
  it("drops EXIF, XMP, comments and trailing data from a JPEG and keeps the image", () => {
    const src = jpeg({ exif: true, xmp: true, comment: true, trailing: true });
    expect(sniffType(src)).toBe("image/jpeg");
    const out = stripMetadata(src, "image/jpeg");
    expect(out.stripped).toEqual(["APP1", "APP1", "COM", "trailing data"]);
    expect([out.width, out.height]).toEqual([252, 352]);
    for (const s of ["Exif", "GPS", "xmpmeta", "taken at home", "appended secret"]) expect(contains(out.bytes, s)).toBe(false);
    expect(out.bytes).toEqual(jpeg()); // exactly the clean file
  });

  it("drops JFIF and ICC segments and keeps only the fixed-size Adobe segment", () => {
    const out = stripMetadata(jpeg({ jfif: true, icc: true, adobe: true }), "image/jpeg");
    expect(out.stripped).toEqual(["APP0", "APP2"]);
    expect(contains(out.bytes, "Mustermann")).toBe(false);
    expect(out.bytes).toEqual(jpeg({ adobe: true }));

    const long = stripMetadata(jpeg({ adobeLong: true }), "image/jpeg");
    expect(long.stripped).toEqual(["APP14"]);
    expect(contains(long.bytes, "hidden note")).toBe(false);
  });

  it("leaves a clean JPEG untouched", () => {
    const out = stripMetadata(jpeg(), "image/jpeg");
    expect(out.stripped).toEqual([]);
    expect(out.bytes).toEqual(jpeg());
  });

  it("rejects broken JPEGs", () => {
    const clean = jpeg();
    expect(() => stripMetadata(clean.slice(0, clean.length - 2), "image/jpeg")).toThrow(ImageError); // no EOI
    expect(() => stripMetadata(clean.slice(0, 30), "image/jpeg")).toThrow(ImageError);
    const sneaky = new Uint8Array([...clean.slice(0, -2), ...seg(0xe1, text("Exif\0\0")), 0xff, 0xd9]);
    expect(() => stripMetadata(sneaky, "image/jpeg")).toThrow(/between JPEG scans/);
  });

  it("drops ICC, EXIF and XMP chunks from a WebP and clears their flags", () => {
    const out = stripMetadata(webp({ icc: true, exif: true, xmp: true }), "image/webp");
    expect(out.stripped).toEqual(["ICCP", "EXIF", "XMP"]);
    expect(contains(out.bytes, "Mustermann")).toBe(false);
    expect([out.width, out.height]).toEqual([252, 352]);
    expect(contains(out.bytes, "GPS")).toBe(false);
    expect(out.bytes).toEqual(webp()); // flags and RIFF size match a file that never had them
  });

  it("rejects animated or truncated WebPs", () => {
    expect(() => stripMetadata(webp({ anim: true }), "image/webp")).toThrow(/animated/);
    const w = webp();
    expect(() => stripMetadata(w.slice(0, w.length - 3), "image/webp")).toThrow(ImageError);
  });
});

// ---------------------------------------------------------------------------------------------------------------

function upload(body: Uint8Array, opts: { type?: string; query?: string; ip?: string } = {}) {
  const query = opts.query ?? "cardId=sv03.5-025&lang=de&consent=cc0-1";
  return call(
    `/v1/contributions?${query}`,
    { method: "POST", headers: { "Content-Type": opts.type ?? "image/jpeg" }, body },
    opts.ip,
  );
}

function withdraw(id: string, token?: string) {
  return call(`/v1/contributions/${id}`, {
    method: "DELETE",
    headers: token ? { Authorization: `Bearer ${token}` } : {},
  });
}

describe("POST /v1/contributions", () => {
  it("stores the stripped crop and returns an id and a withdraw token", async () => {
    const res = await upload(jpeg({ exif: true, xmp: true }));
    expect(res.status).toBe(201);
    const { id, withdrawToken } = await res.json<{ id: string; withdrawToken: string }>();
    expect(id).toMatch(/^[0-9a-f-]{36}$/);
    expect(withdrawToken).toMatch(/^[A-Za-z0-9_-]{43}$/);

    const row = await env.DB.prepare("SELECT * FROM contributions WHERE id = ?").bind(id).first<Record<string, unknown>>();
    expect(row).toMatchObject({
      card_id: "sv03.5-025", lang: "de", consent_version: "cc0-1", object_key: `scans/${id}.jpg`,
      content_type: "image/jpeg", width: 252, height: 352, withdrawn_at: null,
    });
    expect(row!.token_hash).not.toBe(withdrawToken);
    expect(row!.token_hash).toMatch(/^[0-9a-f]{64}$/);
    // Nothing about the client: no column could hold an IP address or device data.
    expect(Object.keys(row!).sort()).toEqual([
      "card_id", "consent_version", "content_type", "created_at", "height", "id", "lang", "object_key", "sha256",
      "size", "token_hash", "width", "withdrawn_at",
    ]);

    const obj = await env.CONTRIBUTIONS.get(`scans/${id}.jpg`);
    const stored = new Uint8Array(await obj!.arrayBuffer());
    expect(stored).toEqual(jpeg());
    expect(obj!.httpMetadata?.contentType).toBe("image/jpeg");
    expect(obj!.customMetadata).toEqual({});
  });

  it("accepts WebP", async () => {
    const res = await upload(webp({ exif: true }), { type: "image/webp" });
    expect(res.status).toBe(201);
    const { id } = await res.json<{ id: string }>();
    const obj = await env.CONTRIBUTIONS.get(`scans/${id}.webp`);
    expect(new Uint8Array(await obj!.arrayBuffer())).toEqual(webp());
  });

  it("validates the input", async () => {
    const cases: [Promise<Response>, number][] = [
      [upload(jpeg(), { query: "cardId=../x&lang=de&consent=cc0-1" }), 400],
      [upload(jpeg(), { query: "cardId=sv03.5-025&lang=german&consent=cc0-1" }), 400],
      [upload(jpeg(), { query: "cardId=sv03.5-025&lang=de&consent=cc0-99" }), 400],
      [upload(jpeg(), { query: "cardId=sv03.5-025&lang=de" }), 400],
      [upload(jpeg(), { type: "image/png" }), 415],
      [upload(webp(), { type: "image/jpeg" }), 415],
      [upload(new Uint8Array(1024 * 1024 + 1)), 413],
      [upload(jpeg().slice(0, 40)), 422],
      [upload(jpeg({ w: 32, h: 44 })), 422],
      [upload(jpeg({ w: 3000, h: 4000 })), 422],
      [call("/v1/contributions", { method: "GET" }), 405],
    ];
    for (const [res, status] of cases) {
      const r = await res;
      expect(r.status).toBe(status);
      await r.arrayBuffer();
    }
  });

  it("limits uploads per client much tighter than the API", async () => {
    const ip = "192.0.2.251";
    for (let i = 0; i < 10; i++) {
      const r = await upload(jpeg(), { ip });
      expect(r.status).toBe(201);
      await r.arrayBuffer();
    }
    const over = await upload(jpeg(), { ip });
    expect(over.status).toBe(429);
    expect(over.headers.get("Retry-After")).toBe("60");
    // Other routes still work for this client: an unknown card is a 404, not a 429.
    expect((await call("/v1/prices/sv99-001", {}, ip)).status).toBe(404);
  });
});

describe("DELETE /v1/contributions/:id", () => {
  it("withdraws with the token: image deleted, row marked, repeat is idempotent", async () => {
    const { id, withdrawToken } = await (await upload(jpeg())).json<{ id: string; withdrawToken: string }>();

    expect((await withdraw(id)).status).toBe(401);
    expect((await withdraw(id, "wrong-token")).status).toBe(404);
    expect(await env.CONTRIBUTIONS.head(`scans/${id}.jpg`)).not.toBeNull();

    const res = await withdraw(id, withdrawToken);
    expect(res.status).toBe(200);
    const body = await res.json<{ id: string; withdrawnAt: string }>();
    expect(body.id).toBe(id);
    expect(await env.CONTRIBUTIONS.head(`scans/${id}.jpg`)).toBeNull();
    const row = await env.DB.prepare("SELECT withdrawn_at FROM contributions WHERE id = ?").bind(id).first();
    expect(row).toEqual({ withdrawn_at: body.withdrawnAt });

    const again = await withdraw(id, withdrawToken);
    expect(again.status).toBe(200);
    expect(await again.json()).toEqual(body);

    // Dataset releases take only rows that are not withdrawn.
    const live = await env.DB.prepare("SELECT COUNT(*) AS n FROM contributions WHERE id = ? AND withdrawn_at IS NULL")
      .bind(id)
      .first<{ n: number }>();
    expect(live!.n).toBe(0);
  });

  it("answers unknown ids like wrong tokens", async () => {
    expect((await withdraw("00000000-0000-4000-8000-000000000000", "x")).status).toBe(404);
    expect((await withdraw("not-a-uuid", "x")).status).toBe(404);
    const put = await call("/v1/contributions/00000000-0000-4000-8000-000000000000", { method: "PUT" });
    expect(put.status).toBe(405);
  });
});
