import { exports } from "cloudflare:workers";
import { describe, expect, it } from "vitest";
import { CONTROLLER, pickLanguage, renderPrivacy } from "../src/privacy";

let clients = 0;

function call(path: string, init: RequestInit = {}) {
  const headers = new Headers(init.headers);
  headers.set("CF-Connecting-IP", `203.0.113.${++clients}`);
  return exports.default.fetch(`https://api.test${path}`, { ...init, headers });
}

describe("privacy policy pages", () => {
  it.each([
    ["/privacy/de", "de", "Datenschutzerklärung"],
    ["/privacy/en", "en", "Privacy policy"],
    ["/privacy/de/", "de", "Datenschutzerklärung"],
  ])("%s is the %s page", async (path, lang, title) => {
    const res = await call(path);
    expect(res.status).toBe(200);
    expect(res.headers.get("Content-Type")).toBe("text/html; charset=utf-8");
    expect(res.headers.get("Content-Language")).toBe(lang);
    expect(res.headers.get("Vary")).toBeNull();
    expect(res.headers.get("Cache-Control")).toBe("public, max-age=3600");
    expect(res.headers.get("Content-Security-Policy")).toContain("default-src 'none'");
    expect(res.headers.get("X-Content-Type-Options")).toBe("nosniff");
    const html = await res.text();
    expect(html).toMatch(new RegExp(`^<!doctype html>\\n<html lang="${lang}">`));
    expect(html).toContain(`<h1>${title}</h1>`);
    // Links to the other language.
    expect(html).toContain(`href="/privacy/${lang === "de" ? "en" : "de"}"`);
  });

  it.each([
    [null, "en"],
    ["de-DE,de;q=0.9,en;q=0.8", "de"],
    ["en-US,en;q=0.9,de;q=0.8", "en"],
    ["fr-FR,fr;q=0.9", "en"],
    ["fr;q=1, de;q=0.5", "de"],
  ])("/privacy with Accept-Language %s answers %s", async (accept, lang) => {
    const res = await call("/privacy", accept ? { headers: { "Accept-Language": accept } } : {});
    expect(res.status).toBe(200);
    expect(res.headers.get("Content-Language")).toBe(lang);
    expect(res.headers.get("Vary")).toBe("Accept-Language");
    expect(await res.text()).toContain(`<html lang="${lang}">`);
  });

  it("answers HEAD without a body", async () => {
    const res = await call("/privacy/en", { method: "HEAD" });
    expect(res.status).toBe(200);
    expect(res.headers.get("Content-Type")).toBe("text/html; charset=utf-8");
    expect(await res.text()).toBe("");
  });

  it("refuses other methods", async () => {
    const res = await call("/privacy", { method: "POST", body: "x" });
    expect(res.status).toBe(405);
    expect(res.headers.get("Allow")).toBe("GET, HEAD");
  });

  it.each(["/privacy/fr", "/privacy/de/x", "/privacyx"])("%s is not found", async (path) => {
    expect((await call(path)).status).toBe(404);
  });

  it("names the data flows the app has, in both languages", () => {
    // A guard against the two texts drifting apart: each names every recipient and legal basis.
    for (const lang of ["de", "en"] as const) {
      const html = renderPrivacy(lang);
      for (const term of ["Cloudflare", "TCGdex", "ML Kit", "CC0", "Art. 6", "Art. 7", "Art. 11", "Art. 21", "Art. 77"]) {
        expect(html, `${lang}: ${term}`).toContain(term);
      }
      expect(html).toContain(CONTROLLER.name);
    }
  });
});

describe("pickLanguage", () => {
  it.each([
    ["", "en"],
    ["de", "de"],
    ["de-AT", "de"],
    ["DE-de", "de"],
    ["en, de", "en"],
    ["de, en", "de"],
    ["en;q=0.5, de;q=0.6", "de"],
    ["de;q=0, en;q=0.1", "en"],
    ["de;q=0", "en"],
    ["*", "en"],
  ])("%j → %s", (header, lang) => {
    expect(pickLanguage(header)).toBe(lang);
  });
});
