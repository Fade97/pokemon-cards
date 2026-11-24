import { exports } from "cloudflare:workers";
import { describe, expect, it } from "vitest";
import { CONTROLLER } from "../src/privacy";
import { renderImprint } from "../src/site";

let clients = 0;

function call(url: string, init: RequestInit = {}) {
  const headers = new Headers(init.headers);
  headers.set("CF-Connecting-IP", `198.51.100.${++clients}`);
  return exports.default.fetch(url, { ...init, headers, redirect: "manual" });
}

describe("foilark.app website (#226)", () => {
  it.each([
    ["https://foilark.de/privacy?x=1", "https://foilark.app/privacy?x=1"],
    ["https://www.foilark.com/", "https://foilark.app/"],
    ["https://www.foilark.app/imprint", "https://foilark.app/imprint"],
  ])("%s redirects to %s", async (from, to) => {
    const res = await call(from);
    expect(res.status).toBe(301);
    expect(res.headers.get("Location")).toBe(to);
  });

  it("serves the start page on foilark.app only", async () => {
    const home = await call("https://foilark.app/", { headers: { "Accept-Language": "de-DE,de;q=0.9" } });
    expect(home.status).toBe(200);
    expect(home.headers.get("Content-Language")).toBe("de");
    const html = await home.text();
    expect(html).toContain('<a href="/imprint/de">Impressum</a>');
    expect(html).toContain('<a href="/privacy/de">Datenschutz</a>');
    // The API host keeps its JSON 404 at the root.
    expect((await call("https://cards-api.fdu.gg/")).status).toBe(404);
  });

  it.each([
    ["https://foilark.app/imprint/de", "de", "Impressum"],
    ["https://foilark.app/impressum", "de", "Impressum"],
    ["https://foilark.app/imprint/en", "en", "Imprint"],
    ["https://cards-api.fdu.gg/imprint/en/", "en", "Imprint"],
  ])("%s is the %s imprint", async (url, lang, title) => {
    const res = await call(url);
    expect(res.status).toBe(200);
    expect(res.headers.get("Content-Language")).toBe(lang);
    expect(res.headers.get("Content-Security-Policy")).toContain("default-src 'none'");
    expect(await res.text()).toContain(`<h1>${title}</h1>`);
  });

  it("names the provider, address and two ways to reach him (§ 5 DDG)", () => {
    for (const lang of ["de", "en"] as const) {
      const html = renderImprint(lang);
      expect(html).toContain(CONTROLLER.name);
      expect(html).toContain("Vor dem Berge 40");
      expect(html).toContain(`mailto:${CONTROLLER.email}`);
      expect(html).toContain('href="tel:+4915233933821"');
      expect(html).toContain("§ 19 UStG");
      expect(html).toContain("Google Play"); // Play sales carry VAT; Google charges it (#253)
    }
  });

  it("refuses other methods", async () => {
    const res = await call("https://foilark.app/imprint", { method: "POST" });
    expect(res.status).toBe(405);
  });
});
