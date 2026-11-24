// The app's website on foilark.app (#226): a start page and the imprint. The privacy policy (src/privacy.ts) is
// served on every host, so foilark.app/privacy works too.
//
//   foilark.de, foilark.com, www.*      301 to https://foilark.app, same path
//   GET foilark.app/                     start page
//   GET /imprint, /imprint/de, /imprint/en, /impressum   imprint (§ 5 DDG), German or English like /privacy

import { APP_NAME, CONTROLLER, escapeHtml, PAGE_STYLE, pickLanguage, type PrivacyLang } from "./privacy";

export const SITE = "foilark.app";
const REDIRECT_HOSTS = new Set(["www.foilark.app", "foilark.de", "www.foilark.de", "foilark.com", "www.foilark.com"]);
const SOURCE_CODE = "https://github.com/Fade97/pokemon-cards";

/** Shown as "last updated". Change it with every change of the imprint text. */
export const IMPRINT_UPDATED = "2026-11-24";

const CACHE = "public, max-age=3600";

/** The website routes; null for anything else (the API). */
export function handleSite(request: Request, url: URL): Response | null {
  const host = url.hostname.toLowerCase();
  if (REDIRECT_HOSTS.has(host)) {
    return new Response(null, { status: 301, headers: { Location: `https://${SITE}${url.pathname}${url.search}` } });
  }
  const imprint = url.pathname.match(/^\/(?:imprint(?:\/(de|en))?|(impressum))\/?$/);
  const home = host === SITE && url.pathname === "/";
  if (!imprint && !home) return null;
  if (request.method !== "GET" && request.method !== "HEAD") {
    return new Response("method not allowed", { status: 405, headers: { Allow: "GET, HEAD" } });
  }
  const fixed: PrivacyLang | undefined = imprint ? ((imprint[2] ? "de" : imprint[1]) as PrivacyLang | undefined) : undefined;
  const lang = fixed ?? pickLanguage(request.headers.get("Accept-Language"));
  const headers: Record<string, string> = {
    "Content-Type": "text/html; charset=utf-8",
    "Content-Language": lang,
    "Cache-Control": CACHE,
    "Content-Security-Policy": "default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'",
    "Referrer-Policy": "no-referrer",
    "X-Content-Type-Options": "nosniff",
  };
  if (!fixed) headers["Vary"] = "Accept-Language";
  const html = imprint ? renderImprint(lang) : renderHome(lang);
  return new Response(request.method === "HEAD" ? null : html, { headers });
}

interface ImprintText {
  title: string;
  languageName: string;
  updated: string;
  body: string;
}

function contact() {
  const email = escapeHtml(CONTROLLER.email);
  return {
    app: escapeHtml(APP_NAME),
    name: escapeHtml(CONTROLLER.name),
    address: escapeHtml(CONTROLLER.address).replace(/\n/g, "<br>"),
    emailLink: `<a href="mailto:${email}">${email}</a>`,
    phoneLink: `<a href="tel:${escapeHtml(CONTROLLER.phone.replace(/\s/g, ""))}">${escapeHtml(CONTROLLER.phone)}</a>`,
  };
}

function imprintText(lang: PrivacyLang): ImprintText {
  const c = contact();
  if (lang === "de") {
    return {
      title: "Impressum",
      languageName: "Deutsch",
      updated: "Stand:",
      body: `
<h2>Angaben gemäß § 5 DDG</h2>
<address>${c.name}<br>${c.address}</address>
<h2>Kontakt</h2>
<p>Telefon: ${c.phoneLink}<br>E-Mail: ${c.emailLink}</p>
<h2>Umsatzsteuer</h2>
<p>Kleinunternehmer gemäß § 19 UStG; ich weise keine Umsatzsteuer aus. Käufe in der App verkauft Google Play an dich und berechnet dabei die Umsatzsteuer, die in deinem Land gilt.</p>
<h2>Verbraucherstreitbeilegung</h2>
<p>Ich bin nicht bereit oder verpflichtet, an Streitbeilegungsverfahren vor einer Verbraucherschlichtungsstelle teilzunehmen.</p>
<h2>Hinweis</h2>
<p>${c.app} ist eine inoffizielle Fan-App und steht in keiner Verbindung zu Nintendo, Creatures Inc., GAME FREAK inc. oder The Pokémon Company und wird von diesen weder unterstützt noch gesponsert. Pokémon und alle zugehörigen Namen sind Marken ihrer Inhaber.</p>
<p><a href="/privacy/de">Datenschutzerklärung</a> · <a href="${SOURCE_CODE}">Quellcode</a></p>`,
    };
  }
  return {
    title: "Imprint",
    languageName: "English",
    updated: "Last updated:",
    body: `
<h2>Information under § 5 DDG (German Digital Services Act)</h2>
<address>${c.name}<br>${c.address}</address>
<h2>Contact</h2>
<p>Phone: ${c.phoneLink}<br>Email: ${c.emailLink}</p>
<h2>VAT</h2>
<p>Small business under § 19 UStG (German VAT Act); I do not charge VAT myself. Purchases in the app are sold to you by Google Play, which charges the VAT of your country.</p>
<h2>Consumer dispute resolution</h2>
<p>I am neither willing nor obliged to take part in dispute resolution proceedings before a consumer arbitration board.</p>
<h2>Notice</h2>
<p>${c.app} is an unofficial fan-made app. It is not affiliated with, endorsed or sponsored by Nintendo, Creatures Inc., GAME FREAK inc. or The Pokémon Company. Pokémon and all related names are trademarks of their respective owners.</p>
<p><a href="/privacy/en">Privacy policy</a> · <a href="${SOURCE_CODE}">Source code</a></p>`,
  };
}

export function renderImprint(lang: PrivacyLang): string {
  const t = imprintText(lang);
  const other: PrivacyLang = lang === "de" ? "en" : "de";
  return page(
    lang,
    `${escapeHtml(t.title)} – ${escapeHtml(APP_NAME)}`,
    `<link rel="alternate" hreflang="${other}" href="/imprint/${other}">`,
    `<p class="meta"><a href="/imprint/${other}" hreflang="${other}" lang="${other}">${imprintText(other).languageName}</a></p>
<h1>${escapeHtml(t.title)}</h1>
<p class="meta">${t.updated} ${IMPRINT_UPDATED}</p>
${t.body.trim()}`,
  );
}

export function renderHome(lang: PrivacyLang): string {
  const app = escapeHtml(APP_NAME);
  const de = lang === "de";
  const intro = de
    ? `Eine kostenlose Open-Source-App für Android, die Pokémon-TCG-Karten direkt auf dem Handy erkennt, Cardmarket- und TCGplayer-Preise zeigt und deine Sammlung ordnet. Bald bei Google Play.`
    : `A free, open-source Android app that recognizes Pokémon TCG cards on the phone itself, shows Cardmarket and TCGplayer prices and sorts your collection. Coming to Google Play.`;
  const links = de
    ? `<a href="/privacy/de">Datenschutz</a> · <a href="/imprint/de">Impressum</a> · <a href="${SOURCE_CODE}">Quellcode</a>`
    : `<a href="/privacy/en">Privacy</a> · <a href="/imprint/en">Imprint</a> · <a href="${SOURCE_CODE}">Source code</a>`;
  return page(lang, app, "", `<h1>${app}</h1>\n<p>${intro}</p>\n<p>${links}</p>`);
}

function page(lang: PrivacyLang, title: string, head: string, main: string): string {
  return `<!doctype html>
<html lang="${lang}">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>${title}</title>
${head}
<style>
${PAGE_STYLE}</style>
</head>
<body>
<main>
${main}
</main>
</body>
</html>
`;
}
