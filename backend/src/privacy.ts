// Privacy policy pages for the Play listing (#70, #15).
//
//   GET /privacy          German or English, picked by Accept-Language (English when neither is asked for)
//   GET /privacy/de       German
//   GET /privacy/en       English
//
// Static HTML from src/privacy-de.ts and src/privacy-en.ts. The Play listing links to /privacy.

import { privacyDe } from "./privacy-de";
import { privacyEn } from "./privacy-en";

/**
 * The controller's details (Art. 13(1)(a) GDPR), shown on both pages, same as the Karelo imprint.
 * They are inserted HTML-escaped.
 */
export const CONTROLLER = {
  name: "Fabian Dürkop",
  address: "Vor dem Berge 40\n38524 Sassenburg\nGermany",
  email: "fabian@karelo.de",
  /** International format; the imprint shows it (§ 5 DDG), the privacy pages don't. */
  phone: "+49 152 33933821",
};

/** The app's name in the text (final name, #15). */
export const APP_NAME = "Foilark";

/** Shown as "last updated". Change it with every change of the text. */
export const UPDATED = "2026-11-18";

export type PrivacyLang = "de" | "en";

export interface PrivacyText {
  lang: PrivacyLang;
  title: string;
  /** The language's own name ("Deutsch", "English"), the text of the link to this page from the other one. */
  languageName: string;
  /** "Last updated" label. */
  updated: string;
  body: (c: Contact) => string;
}

/** The controller's details, already HTML-escaped. */
export interface Contact {
  app: string;
  name: string;
  address: string;
  email: string;
  /** The email as a mailto link, or the plain placeholder while it is not filled in. */
  emailLink: string;
}

const TEXTS: Record<PrivacyLang, PrivacyText> = { de: privacyDe, en: privacyEn };

/** The look of the legal pages (privacy, imprint): plain, readable, light and dark. */
export const PAGE_STYLE = `:root { color-scheme: light dark; --fg: #1c1b1f; --muted: #5f5c66; --bg: #fdfcff; --line: #d9d6e0; --link: #3a5bc7; }
@media (prefers-color-scheme: dark) { :root { --fg: #e6e1e9; --muted: #aaa6b2; --bg: #141218; --line: #3a3741; --link: #9db4ff; } }
body { margin: 0; background: var(--bg); color: var(--fg); font: 16px/1.6 system-ui, -apple-system, "Segoe UI", Roboto, sans-serif; }
main { max-width: 46rem; margin: 0 auto; padding: 1.5rem 1rem 3rem; }
h1 { font-size: 1.7rem; line-height: 1.25; margin: 0 0 .25rem; }
h2 { font-size: 1.2rem; margin: 2rem 0 .5rem; padding-top: 1rem; border-top: 1px solid var(--line); }
h3 { font-size: 1.02rem; margin: 1.25rem 0 .25rem; }
p, ul { margin: .5rem 0; }
li { margin: .2rem 0; }
a { color: var(--link); overflow-wrap: anywhere; }
.meta { color: var(--muted); font-size: .9rem; margin: 0 0 1rem; }
address { font-style: normal; }
`;

/** Pages change rarely; an hour keeps a corrected text from lingering long. */
const CACHE = "public, max-age=3600";

/** Handles /privacy and below; null for any other path. */
export function handlePrivacy(request: Request, pathname: string): Response | null {
  const m = pathname.match(/^\/privacy(?:\/(de|en))?\/?$/);
  if (!m) return pathname.startsWith("/privacy/") ? notFound() : null;
  if (request.method !== "GET" && request.method !== "HEAD") {
    return new Response("method not allowed", { status: 405, headers: { Allow: "GET, HEAD" } });
  }
  const fixed = m[1] as PrivacyLang | undefined;
  const lang = fixed ?? pickLanguage(request.headers.get("Accept-Language"));
  const headers: Record<string, string> = {
    "Content-Type": "text/html; charset=utf-8",
    "Content-Language": lang,
    "Cache-Control": CACHE,
    "Content-Security-Policy": "default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'",
    "Referrer-Policy": "no-referrer",
    "X-Content-Type-Options": "nosniff",
  };
  // The same URL answers in two languages, so caches must keep them apart.
  if (!fixed) headers["Vary"] = "Accept-Language";
  const html = renderPrivacy(lang);
  return new Response(request.method === "HEAD" ? null : html, { headers });
}

/**
 * German when the browser ranks German above English, English otherwise. Only the primary subtag counts
 * (`de-AT` is German); `q=0` excludes a language.
 */
export function pickLanguage(acceptLanguage: string | null): PrivacyLang {
  let best: { lang: PrivacyLang; q: number } | null = null;
  for (const part of (acceptLanguage ?? "").split(",")) {
    const [tag = "", ...params] = part.trim().split(";");
    const primary = tag.trim().toLowerCase().split("-")[0];
    if (primary !== "de" && primary !== "en") continue;
    const qParam = params.map((p) => p.trim()).find((p) => p.startsWith("q="));
    const q = qParam ? Number(qParam.slice(2)) : 1;
    if (!(q > 0)) continue;
    // Equal weights keep the earlier entry, as the browser listed it first.
    if (!best || q > best.q) best = { lang: primary, q };
  }
  return best?.lang ?? "en";
}

export function renderPrivacy(lang: PrivacyLang): string {
  const t = TEXTS[lang];
  const other: PrivacyLang = lang === "de" ? "en" : "de";
  const email = escapeHtml(CONTROLLER.email);
  const contact: Contact = {
    app: escapeHtml(APP_NAME),
    name: escapeHtml(CONTROLLER.name),
    address: escapeHtml(CONTROLLER.address).replace(/\n/g, "<br>"),
    email,
    emailLink: CONTROLLER.email.includes("@") ? `<a href="mailto:${email}">${email}</a>` : email,
  };
  return `<!doctype html>
<html lang="${lang}">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>${escapeHtml(t.title)} – ${contact.app}</title>
<link rel="alternate" hreflang="${other}" href="/privacy/${other}">
<style>
${PAGE_STYLE}</style>
</head>
<body>
<main>
<p class="meta"><a href="/privacy/${other}" hreflang="${other}" lang="${other}">${escapeHtml(TEXTS[other].languageName)}</a></p>
<h1>${escapeHtml(t.title)}</h1>
<p class="meta">${escapeHtml(t.updated)} ${UPDATED}</p>
${t.body(contact).trim()}
</main>
</body>
</html>
`;
}

export function escapeHtml(s: string): string {
  return s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;").replace(/'/g, "&#39;");
}

function notFound(): Response {
  return Response.json({ error: "not found" }, { status: 404 });
}
