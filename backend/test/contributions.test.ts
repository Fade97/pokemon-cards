import { createExecutionContext, waitOnExecutionContext } from "cloudflare:test";
import { env, exports } from "cloudflare:workers";
import { describe, expect, it } from "vitest";
import { handleContributionReview } from "../src/contribution-review";
import { DEFAULT_DAILY_QUOTA, dailyQuota, handleContributions } from "../src/contributions";
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

function webp(
  opts: {
    exif?: boolean;
    xmp?: boolean;
    icc?: boolean;
    anim?: boolean;
    w?: number;
    h?: number;
    /** VP8X canvas size, when it should differ from the frame. */
    canvas?: [number, number];
    /** Bytes appended to the VP8X payload. */
    vp8xExtra?: number[];
    /** A second VP8L frame after the first. */
    secondFrame?: boolean;
  } = {},
) {
  const { w = 252, h = 352 } = opts;
  const [cw, ch] = opts.canvas ?? [w, h];
  const flags = 0x10 | (opts.icc ? 0x20 : 0) | (opts.exif ? 0x08 : 0) | (opts.xmp ? 0x04 : 0) | (opts.anim ? 0x02 : 0);
  const le24 = (v: number) => [v & 0xff, (v >> 8) & 0xff, (v >> 16) & 0xff];
  const bits = (w - 1) | ((h - 1) << 14);
  const body = [
    ...text("WEBP"),
    ...chunk("VP8X", [flags, 0, 0, 0, ...le24(cw - 1), ...le24(ch - 1), ...(opts.vp8xExtra ?? [])]),
    ...(opts.icc ? chunk("ICCP", text("desc: Max Mustermann, Berlin")) : []),
    ...(opts.anim ? chunk("ANIM", [0, 0, 0, 0, 0, 0]) : []),
    ...chunk("VP8L", [0x2f, bits & 0xff, (bits >> 8) & 0xff, (bits >> 16) & 0xff, (bits >>> 24) & 0xff, 7, 7]),
    ...(opts.secondFrame ? chunk("VP8L", [0x2f, 0, 0, 0, 0, 7, 7]) : []),
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

  it("rejects a WebP whose VP8X canvas hides the real frame size, or that carries extra payload (#128)", () => {
    // A 4096 px frame behind a canvas that passes the size check.
    expect(() => stripMetadata(webp({ w: 4096, h: 4096, canvas: [252, 352] }), "image/webp")).toThrow(/canvas and frame/);
    expect(() => stripMetadata(webp({ vp8xExtra: [...text("hidden note")] }), "image/webp")).toThrow(/VP8X chunk of the wrong size/);
    expect(() => stripMetadata(webp({ secondFrame: true }), "image/webp")).toThrow(/more than one image chunk/);
    // A 9-byte VP8X padded to 10 is as long as a real one; its declared size gives it away.
    const short = webp();
    const at = 12 + 4; // size field of the first chunk (VP8X)
    short[at] = 9;
    expect(() => stripMetadata(short, "image/webp")).toThrow(/VP8X chunk of the wrong size/);
  });

  it("rejects animated or truncated WebPs", () => {
    expect(() => stripMetadata(webp({ anim: true }), "image/webp")).toThrow(/animated/);
    const w = webp();
    expect(() => stripMetadata(w.slice(0, w.length - 3), "image/webp")).toThrow(ImageError);
  });
});

// ---------------------------------------------------------------------------------------------------------------

function upload(body: Uint8Array, opts: { type?: string; query?: string; ip?: string; token?: string } = {}) {
  const query = opts.query ?? "cardId=sv03.5-025&lang=de&consent=cc0-1";
  const headers: Record<string, string> = { "Content-Type": opts.type ?? "image/jpeg" };
  if (opts.token) headers.Authorization = `Bearer ${opts.token}`;
  return call(`/v1/contributions?${query}`, { method: "POST", headers, body }, opts.ip);
}

function withdraw(id: string, token?: string) {
  return call(`/v1/contributions/${id}`, {
    method: "DELETE",
    headers: token ? { Authorization: `Bearer ${token}` } : {},
  });
}

describe("consent versions (ADR 0016)", () => {
  it("accepts the training-only consent and the older CC0 one, and records which", async () => {
    for (const consent of ["train-2", "train-1", "cc0-1"]) {
      const res = await upload(jpeg(), { query: `cardId=sv03.5-025&lang=de&consent=${consent}` });
      expect(res.status).toBe(201);
      const { id } = (await res.json()) as { id: string };
      const row = await env.DB.prepare("SELECT consent_version FROM contributions WHERE id = ?").bind(id).first();
      expect(row).toEqual({ consent_version: consent });
    }
  });
});

describe("false positives (#244)", () => {
  const row = (id: string) =>
    env.DB.prepare("SELECT kind, card_id, lang, predicted_card_id, predicted_lang, consent_version FROM contributions WHERE id = ?")
      .bind(id)
      .first();

  it("records the scanner's wrong top match next to the confirmed card", async () => {
    const res = await upload(jpeg(), {
      query: "cardId=sv03.5-025&lang=de&consent=train-1&predicted=xy9-110&predictedLang=it",
    });
    expect(res.status).toBe(201);
    const { id } = await res.json<{ id: string }>();
    expect(await row(id)).toEqual({
      kind: "card", card_id: "sv03.5-025", lang: "de", predicted_card_id: "xy9-110", predicted_lang: "it",
      consent_version: "train-1",
    });
  });

  it("stores a not-a-card scan without a label, only under the debug consent", async () => {
    const res = await upload(jpeg(), { query: "kind=not_card&consent=debug-1&predicted=xy9-110&predictedLang=de" });
    expect(res.status).toBe(201);
    const { id } = await res.json<{ id: string }>();
    expect(await row(id)).toEqual({
      kind: "not_card", card_id: "", lang: "", predicted_card_id: "xy9-110", predicted_lang: "de",
      consent_version: "debug-1",
    });
    // Without a prediction too.
    expect((await upload(jpeg(), { query: "kind=not_card&consent=debug-1" })).status).toBe(201);

    const { contributions } = await (await admin("?kind=not_card&limit=200")).json<{ contributions: Record<string, unknown>[] }>();
    expect(contributions.find((c) => c.id === id)).toMatchObject({
      kind: "not_card", cardId: "", predictedCardId: "xy9-110", predictedLang: "de", consent: "debug-1",
    });
    expect(contributions.every((c) => c.kind === "not_card")).toBe(true);
    const cards = await (await admin("?kind=card&limit=200")).json<{ contributions: { id: string }[] }>();
    expect(cards.contributions.some((c) => c.id === id)).toBe(false);
  });

  it("refuses mismatched kinds, consents and predictions", async () => {
    for (const query of [
      "kind=not_card&consent=train-1", // release consent doesn't cover non-card crops
      "kind=not_card&consent=cc0-1",
      "kind=not_card&cardId=sv03.5-025&lang=de&consent=debug-1",
      "cardId=sv03.5-025&lang=de&consent=debug-1", // the debug consent is for not_card only
      "kind=blank&consent=debug-1",
      "cardId=sv03.5-025&lang=de&consent=train-1&predicted=xy9-110", // without predictedLang
      "cardId=sv03.5-025&lang=de&consent=train-1&predictedLang=de",
      "cardId=sv03.5-025&lang=de&consent=train-1&predicted=../x&predictedLang=de",
      "cardId=sv03.5-025&lang=de&consent=train-1&predicted=xy9-110&predictedLang=DEU",
    ]) {
      expect((await upload(jpeg(), { query })).status, query).toBe(400);
    }
    expect((await admin("?kind=blank")).status).toBe(400);
  });
});

describe("POST /v1/contributions with a client id (idempotent)", () => {
  const token = "A".repeat(43);
  const query = (id: string) => `cardId=sv03.5-025&lang=de&consent=cc0-1&id=${id}`;

  it("stores once and answers a retry with the same id and token", async () => {
    const id = crypto.randomUUID();
    const first = await upload(jpeg(), { query: query(id), token });
    expect(first.status).toBe(201);
    expect(await first.json()).toEqual({ id, withdrawToken: token });

    // The response got lost; the app sends the same upload again.
    const retry = await upload(jpeg(), { query: query(id), token });
    expect(retry.status).toBe(200);
    expect(await retry.json()).toEqual({ id, withdrawToken: token });
    const rows = await env.DB.prepare("SELECT COUNT(*) AS n FROM contributions WHERE id = ?").bind(id).first();
    expect(rows).toEqual({ n: 1 });

    // The client's token withdraws it.
    expect((await withdraw(id, token)).status).toBe(200);
    expect(await env.CONTRIBUTIONS.head(`scans/${id}`)).toBeNull();
  });

  it("a withdrawal that overtakes its upload leaves a tombstone, so the late upload stores nothing", async () => {
    const id = crypto.randomUUID();
    expect((await withdraw(id, token)).status).toBe(404); // unknown yet: still 404, ids can't be probed
    const late = await upload(jpeg(), { query: query(id), token });
    expect(late.status).toBe(200);
    expect(((await late.json()) as any).withdrawnAt).toBeTruthy();
    expect(await env.CONTRIBUTIONS.head(`scans/${id}`)).toBeNull();
    const row = await env.DB.prepare("SELECT withdrawn_at FROM contributions WHERE id = ?").bind(id).first<any>();
    expect(row.withdrawn_at).toBeTruthy();
    // Someone else's token can't take the id either.
    expect((await upload(jpeg(), { query: query(id), token: "C".repeat(43) })).status).toBe(409);
  });

  async function claimOnly(id: string, createdAt: string) {
    const hash = [...new Uint8Array(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(token)))]
      .map((b) => b.toString(16).padStart(2, "0"))
      .join("");
    await env.DB.prepare(
      `INSERT INTO contributions (id, token_hash, card_id, lang, consent_version, object_key, content_type, size,
         width, height, sha256, created_at)
       VALUES (?, ?, 'sv03.5-025', 'de', 'cc0-1', ?, 'image/jpeg', 1, 252, 352, 'x', ?)`,
    )
      .bind(id, hash, `scans/${id}`, createdAt)
      .run();
  }

  it("marks a finished upload as stored", async () => {
    const id = crypto.randomUUID();
    expect((await upload(jpeg(), { query: query(id), token })).status).toBe(201);
    const row = await env.DB.prepare("SELECT stored_at FROM contributions WHERE id = ?").bind(id).first<any>();
    expect(row.stored_at).toBeTruthy();
  });

  it("tells a retry to come back while the first upload is still writing", async () => {
    const id = crypto.randomUUID();
    await claimOnly(id, new Date().toISOString());
    const retry = await upload(jpeg(), { query: query(id), token });
    expect(retry.status).toBe(503);
    expect(retry.headers.get("Retry-After")).toBe("30");
    expect((await upload(jpeg(), { query: query(id), token: "E".repeat(43) })).status).toBe(409);
  });

  it("lets a retry take over a claim whose upload never finished", async () => {
    const id = crypto.randomUUID();
    await claimOnly(id, new Date(Date.now() - 10 * 60 * 1000).toISOString());
    const retry = await upload(jpeg(), { query: query(id), token });
    expect(retry.status).toBe(201);
    expect(await env.CONTRIBUTIONS.head(`scans/${id}`)).not.toBeNull();
    const row = await env.DB.prepare("SELECT stored_at FROM contributions WHERE id = ?").bind(id).first<any>();
    expect(row.stored_at).toBeTruthy();
  });

  it("withdraws a claim whose image was never written, and its late retry stores nothing", async () => {
    const id = crypto.randomUUID();
    await claimOnly(id, new Date(Date.now() - 10 * 60 * 1000).toISOString());
    expect((await withdraw(id, token)).status).toBe(200);
    const retry = await upload(jpeg(), { query: query(id), token });
    expect(retry.status).toBe(200);
    expect(((await retry.json()) as any).withdrawnAt).toBeTruthy();
    expect(await env.CONTRIBUTIONS.head(`scans/${id}`)).toBeNull();
  });

  it("deletes the image again on a repeated withdrawal", async () => {
    const id = crypto.randomUUID();
    expect((await upload(jpeg(), { query: query(id), token })).status).toBe(201);
    expect((await withdraw(id, token)).status).toBe(200);
    // An image that came back after the first withdrawal (e.g. a write that raced it) goes on the next one.
    await env.CONTRIBUTIONS.put(`scans/${id}`, jpeg());
    expect((await withdraw(id, token)).status).toBe(200);
    expect(await env.CONTRIBUTIONS.head(`scans/${id}`)).toBeNull();
  });

  it("leaves no tombstone for a token that isn't a client token", async () => {
    const id = crypto.randomUUID();
    expect((await withdraw(id, "short")).status).toBe(404);
    expect(await env.DB.prepare("SELECT 1 FROM contributions WHERE id = ?").bind(id).first()).toBeNull();
  });

  it("lets exactly one of two racing uploads with one id write the image", async () => {
    const id = crypto.randomUUID();
    const [a, b] = await Promise.all([
      upload(jpeg({ w: 252 }), { query: query(id), token, ip: "192.0.2.10" }),
      upload(jpeg({ w: 250 }), { query: query(id), token: "D".repeat(43), ip: "192.0.2.11" }),
    ]);
    const statuses = [a.status, b.status].sort();
    expect(statuses).toEqual([201, 409]);
    const winner = a.status === 201 ? 252 : 250;
    const row = await env.DB.prepare("SELECT width FROM contributions WHERE id = ?").bind(id).first<any>();
    expect(row.width).toBe(winner);
    const stored = await env.CONTRIBUTIONS.get(`scans/${id}`);
    expect(new Uint8Array(await stored!.arrayBuffer())).toEqual(jpeg({ w: winner }));
  });

  it("refuses an id taken with another token, and malformed ids or tokens", async () => {
    const id = crypto.randomUUID();
    expect((await upload(jpeg(), { query: query(id), token })).status).toBe(201);
    expect((await upload(jpeg(), { query: query(id), token: "B".repeat(43) })).status).toBe(409);
    expect((await upload(jpeg(), { query: query("not-a-uuid"), token })).status).toBe(400);
    expect((await upload(jpeg(), { query: query(crypto.randomUUID()), token: "short" })).status).toBe(400);
    expect((await upload(jpeg(), { query: query(crypto.randomUUID()) })).status).toBe(400);
  });
});

describe("POST /v1/contributions", () => {
  it("stores the stripped crop and returns an id and a withdraw token", async () => {
    const res = await upload(jpeg({ exif: true, xmp: true }));
    expect(res.status).toBe(201);
    const { id, withdrawToken } = await res.json<{ id: string; withdrawToken: string }>();
    expect(id).toMatch(/^[0-9a-f-]{36}$/);
    expect(withdrawToken).toMatch(/^[A-Za-z0-9_-]{43}$/);

    const row = await env.DB.prepare("SELECT * FROM contributions WHERE id = ?").bind(id).first<Record<string, unknown>>();
    expect(row).toMatchObject({
      card_id: "sv03.5-025", lang: "de", consent_version: "cc0-1", object_key: `scans/${id}`,
      content_type: "image/jpeg", width: 252, height: 352, withdrawn_at: null,
    });
    expect(row!.token_hash).not.toBe(withdrawToken);
    expect(row!.token_hash).toMatch(/^[0-9a-f]{64}$/);
    // Nothing about the client: no column could hold an IP address or device data.
    expect(Object.keys(row!).sort()).toEqual([
      "card_id", "consent_version", "content_type", "created_at", "height", "id", "kind", "lang", "object_key",
      "predicted_card_id", "predicted_lang", "review", "reviewed_at", "sha256", "size", "stored_at", "token_hash", "width",
      "withdrawn_at",
    ]);
    expect(row).toMatchObject({ kind: "card", predicted_card_id: null, predicted_lang: null });
    // Quarantined until an admin approves it (#135).
    expect(row).toMatchObject({ review: "pending", reviewed_at: null });

    const obj = await env.CONTRIBUTIONS.get(`scans/${id}`);
    const stored = new Uint8Array(await obj!.arrayBuffer());
    expect(stored).toEqual(jpeg());
    expect(obj!.httpMetadata?.contentType).toBe("image/jpeg");
    expect(obj!.customMetadata).toEqual({});
  });

  it("accepts WebP", async () => {
    const res = await upload(webp({ exif: true }), { type: "image/webp" });
    expect(res.status).toBe(201);
    const { id } = await res.json<{ id: string }>();
    const obj = await env.CONTRIBUTIONS.get(`scans/${id}`);
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
      [upload(new Uint8Array(256 * 1024 + 1)), 413], // the app's crops are ~50 KB (#135)
      [upload(jpeg({ w: 352, h: 352 })), 422], // square: not a card
      [upload(jpeg({ w: 252, h: 600 })), 422], // too long for a card
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

  it("accepts a card crop in either orientation and slightly off the exact ratio", async () => {
    for (const [w, h] of [[352, 252], [240, 352], [270, 352]]) {
      const r = await upload(jpeg({ w, h }));
      expect(r.status).toBe(201);
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
    expect(await env.CONTRIBUTIONS.head(`scans/${id}`)).not.toBeNull();

    const res = await withdraw(id, withdrawToken);
    expect(res.status).toBe(200);
    const body = await res.json<{ id: string; withdrawnAt: string }>();
    expect(body.id).toBe(id);
    expect(await env.CONTRIBUTIONS.head(`scans/${id}`)).toBeNull();
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

// ---------------------------------------------------------------------------------------------------------------
// Daily quota (#135)

const DAY_START = () => `${new Date().toISOString().slice(0, 10)}T00:00:00.000Z`;

async function uploadsToday(): Promise<number> {
  const row = await env.DB.prepare("SELECT COUNT(*) AS n FROM contributions WHERE created_at >= ? AND object_key <> ''")
    .bind(DAY_START())
    .first<{ n: number }>();
  return row!.n;
}

/** An upload straight through the handler with a given quota (the deployed default is in wrangler.jsonc). */
async function uploadWithQuota(quota: number | string, opts: { query?: string; token?: string } = {}) {
  const query = opts.query ?? "cardId=sv03.5-025&lang=de&consent=cc0-1";
  const headers: Record<string, string> = { "Content-Type": "image/jpeg" };
  if (opts.token) headers.Authorization = `Bearer ${opts.token}`;
  const request = new Request(`https://api.test/v1/contributions?${query}`, { method: "POST", headers, body: jpeg() });
  const ctx = createExecutionContext();
  const quotaEnv = { ...env, CONTRIBUTION_DAILY_QUOTA: String(quota) } as unknown as Env;
  const res = await handleContributions(request, quotaEnv, ctx, "/v1/contributions", `quota-client-${++clients}`);
  await waitOnExecutionContext(ctx);
  return res;
}

describe("daily upload quota", () => {
  it("reads the quota from the environment, with a default", () => {
    const q = (v: string | undefined) => dailyQuota({ ...env, CONTRIBUTION_DAILY_QUOTA: v } as unknown as Env);
    expect(q(undefined)).toBe(DEFAULT_DAILY_QUOTA);
    expect(q("")).toBe(DEFAULT_DAILY_QUOTA);
    expect(q("lots")).toBe(DEFAULT_DAILY_QUOTA);
    expect(q("-5")).toBe(DEFAULT_DAILY_QUOTA);
    expect(q(" 42 ")).toBe(42);
    expect(q("0")).toBe(0);
    expect(dailyQuota(env)).toBe(500); // wrangler.jsonc
  });

  it("stops uploads for everyone once the day's quota is used, and answers retries of stored ones", async () => {
    const id = crypto.randomUUID();
    const token = "Q".repeat(43);
    const query = `cardId=sv03.5-025&lang=de&consent=cc0-1&id=${id}`;
    const quota = (await uploadsToday()) + 1;
    expect((await uploadWithQuota(quota, { query, token })).status).toBe(201);

    const full = await uploadWithQuota(quota);
    expect(full.status).toBe(503); // the app ends its run and tries again later
    expect(await full.json()).toEqual({ error: "daily upload quota reached" });
    const wait = Number(full.headers.get("Retry-After"));
    expect(wait).toBeGreaterThan(0);
    expect(wait).toBeLessThanOrEqual(24 * 60 * 60);

    // The upload that made it is still answered when its response got lost.
    expect((await uploadWithQuota(quota, { query, token })).status).toBe(200);
    // Withdrawing frees nothing (it took the writes), and a tombstone doesn't count.
    expect((await withdraw(id, token)).status).toBe(200);
    expect((await withdraw(crypto.randomUUID(), token)).status).toBe(404);
    expect(await uploadsToday()).toBe(quota);
    expect((await uploadWithQuota(quota)).status).toBe(503);
    expect((await uploadWithQuota(quota + 1)).status).toBe(201);
  });

  it("holds under concurrent uploads: the claim checks the quota itself", async () => {
    const quota = (await uploadsToday()) + 2;
    const results = await Promise.all(Array.from({ length: 6 }, () => uploadWithQuota(quota)));
    const statuses = results.map((r) => r.status).sort();
    expect(statuses).toEqual([201, 201, 503, 503, 503, 503]);
    expect(await uploadsToday()).toBe(quota);
  });

  it("0 pauses uploads", async () => {
    expect((await uploadWithQuota(0)).status).toBe(503);
  });
});

// ---------------------------------------------------------------------------------------------------------------
// Quarantine and admin review (#135)

const ADMIN = "test-admin-token-0123456789-abcdefghijkl"; // its SHA-256 is in vitest.config.ts

function admin(path: string, init: RequestInit = {}, token: string | null = ADMIN) {
  const headers = new Headers(init.headers);
  if (token) headers.set("Authorization", `Bearer ${token}`);
  return call(`/v1/admin/contributions${path}`, { ...init, headers });
}

const approve = (id: string) => admin(`/${id}/approve`, { method: "POST" });
const reject = (id: string) => admin(`/${id}/reject`, { method: "POST" });

async function stored(): Promise<{ id: string; withdrawToken: string }> {
  const res = await upload(jpeg());
  expect(res.status).toBe(201);
  return res.json();
}

describe("admin review of contributions", () => {
  it("is invisible without the admin token, or when no admin token is set up", async () => {
    const { id } = await stored();
    for (const token of [null, "wrong-token-0123456789-abcdefghijklmn", "short"]) {
      expect((await admin("", {}, token)).status).toBe(404);
      expect((await admin(`/${id}/approve`, { method: "POST" }, token)).status).toBe(404);
      expect((await admin(`/${id}/image`, {}, token)).status).toBe(404);
    }
    // The withdraw token of a contribution is no admin token either.
    const own = await stored();
    expect((await admin(`/${own.id}/approve`, { method: "POST" }, own.withdrawToken)).status).toBe(404);

    const request = new Request("https://api.test/v1/admin/contributions", { headers: { Authorization: `Bearer ${ADMIN}` } });
    const unset = { ...env, CONTRIBUTION_ADMIN_TOKENS: undefined } as unknown as Env;
    expect((await handleContributionReview(request, unset, "/v1/admin/contributions")).status).toBe(404);
    const row = await env.DB.prepare("SELECT review FROM contributions WHERE id = ?").bind(id).first();
    expect(row).toEqual({ review: "pending" });
  });

  it("lists pending scans with their labels, serves the image, and approves", async () => {
    const { id } = await stored();
    const list = await admin("?limit=200");
    expect(list.status).toBe(200);
    const { contributions } = await list.json<{ contributions: Record<string, unknown>[] }>();
    expect(contributions.find((c) => c.id === id)).toMatchObject({
      cardId: "sv03.5-025", lang: "de", contentType: "image/jpeg", width: 252, height: 352, review: "pending",
      reviewedAt: null,
    });
    // No withdraw token hash or R2 key in the listing.
    expect(Object.keys(contributions[0]!).sort()).toEqual([
      "cardId", "consent", "contentType", "createdAt", "height", "id", "kind", "lang", "predictedCardId", "predictedLang",
      "review", "reviewedAt", "size", "width",
    ]);

    const image = await admin(`/${id}/image`);
    expect(image.status).toBe(200);
    expect(image.headers.get("Content-Type")).toBe("image/jpeg");
    expect(image.headers.get("Cache-Control")).toBe("private, no-store");
    expect(new Uint8Array(await image.arrayBuffer())).toEqual(jpeg());

    const ok = await approve(id);
    expect(ok.status).toBe(200);
    const body = await ok.json<{ id: string; review: string; reviewedAt: string }>();
    expect(body).toMatchObject({ id, review: "approved" });
    expect(await (await approve(id)).json()).toEqual(body); // repeat: same answer, same time
    const row = await env.DB.prepare("SELECT review, reviewed_at FROM contributions WHERE id = ?").bind(id).first();
    expect(row).toEqual({ review: "approved", reviewed_at: body.reviewedAt });

    const approved = await (await admin("?review=approved&limit=200")).json<{ contributions: { id: string }[] }>();
    expect(approved.contributions.map((c) => c.id)).toContain(id);
    const pending = await (await admin("?limit=200")).json<{ contributions: { id: string }[] }>();
    expect(pending.contributions.map((c) => c.id)).not.toContain(id);
  });

  it("rejects: image deleted, row kept, never approved again; also after an approval", async () => {
    const a = await stored();
    const res = await reject(a.id);
    expect(res.status).toBe(200);
    expect(((await res.json()) as any).review).toBe("rejected");
    expect(await env.CONTRIBUTIONS.head(`scans/${a.id}`)).toBeNull();
    expect((await admin(`/${a.id}/image`)).status).toBe(404);
    expect((await approve(a.id)).status).toBe(409);
    // A repeated rejection deletes again (an earlier delete may have failed).
    await env.CONTRIBUTIONS.put(`scans/${a.id}`, jpeg());
    expect((await reject(a.id)).status).toBe(200);
    expect(await env.CONTRIBUTIONS.head(`scans/${a.id}`)).toBeNull();
    // The contributor can still withdraw it.
    expect((await withdraw(a.id, a.withdrawToken)).status).toBe(200);

    const b = await stored();
    expect((await approve(b.id)).status).toBe(200);
    expect((await reject(b.id)).status).toBe(200);
    expect(await env.DB.prepare("SELECT review FROM contributions WHERE id = ?").bind(b.id).first()).toEqual({
      review: "rejected",
    });
  });

  it("leaves withdrawn, unfinished and unknown contributions alone", async () => {
    const w = await stored();
    expect((await withdraw(w.id, w.withdrawToken)).status).toBe(200);
    expect((await approve(w.id)).status).toBe(409);
    expect((await reject(w.id)).status).toBe(409);
    expect((await admin(`/${w.id}/image`)).status).toBe(404);

    // A claim whose image is still being written.
    const id = crypto.randomUUID();
    await env.DB.prepare(
      `INSERT INTO contributions (id, token_hash, card_id, lang, consent_version, object_key, content_type, size,
         width, height, sha256, created_at)
       VALUES (?, 'x', 'sv03.5-025', 'de', 'cc0-1', ?, 'image/jpeg', 1, 252, 352, 'x', ?)`,
    )
      .bind(id, `scans/${id}`, new Date().toISOString())
      .run();
    expect((await approve(id)).status).toBe(409);
    expect((await reject(id)).status).toBe(409);

    // A tombstone, an unknown id, a malformed one.
    const t = crypto.randomUUID();
    expect((await withdraw(t, "T".repeat(43))).status).toBe(404);
    expect((await approve(t)).status).toBe(404);
    expect((await approve(crypto.randomUUID())).status).toBe(404);
    expect((await approve("not-a-uuid")).status).toBe(404);
  });

  it("validates the listing and the methods", async () => {
    for (const q of ["?review=maybe", "?limit=0", "?limit=201", "?limit=x", "?offset=-1"]) {
      expect((await admin(q)).status).toBe(400);
    }
    expect((await admin("", { method: "POST" })).status).toBe(405);
    expect((await admin(`/${crypto.randomUUID()}/approve`)).status).toBe(405);
    expect((await admin(`/${crypto.randomUUID()}/image`, { method: "DELETE" })).status).toBe(405);
    expect((await admin(`/${crypto.randomUUID()}/publish`, { method: "POST" })).status).toBe(404);
  });
});
