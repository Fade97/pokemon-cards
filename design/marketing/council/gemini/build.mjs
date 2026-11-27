#!/usr/bin/env node
// Build script for Gemini 4 Neon's Foilark tester flyer (A6 print + 1080x1920 phone card).
// Re-creates:
//   out/flyer-a6-bleed.pdf     (111 x 154 mm = A6 105 x 148 mm + 3 mm bleed)
//   out/flyer-a6.png           (trimmed A6 preview at 1240 x 1748 px, 300 DPI)
//   out/join-card-1080x1920.png (1080 x 1920 px WhatsApp/Signal card)

import { mkdirSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { homedir } from 'node:os';

const here = dirname(fileURLToPath(import.meta.url));
const outDir = join(here, 'out');
mkdirSync(outDir, { recursive: true });

const pwPath = join(homedir(), '.claude', 'skills', 'marketing-images', 'node_modules', 'playwright-core', 'index.mjs');
const chromiumHelperPath = join(homedir(), '.claude', 'skills', 'marketing-images', 'scripts', 'chromium.mjs');

const { chromium } = await import(pathToFileURL(pwPath).href);
const { findChromium, glArgs } = await import(pathToFileURL(chromiumHelperPath).href);

const executablePath = findChromium();
const browser = await chromium.launch({
  executablePath,
  args: ['--no-sandbox', '--disable-dev-shm-usage', '--font-render-hinting=none', ...glArgs()],
});

try {
  // 1. A6 print PDF with 3 mm bleed (111 x 154 mm)
  const pdfContext = await browser.newContext({
    viewport: { width: 1110, height: 1540 },
    deviceScaleFactor: 2,
  });
  const pdfPage = await pdfContext.newPage();
  const a6Url = pathToFileURL(resolve(here, 'flyer-a6.html')).href;
  await pdfPage.goto(a6Url, { waitUntil: 'networkidle' });
  await pdfPage.evaluate(() => document.fonts.ready);

  const pdfOut = join(outDir, 'flyer-a6-bleed.pdf');
  await pdfPage.pdf({
    path: pdfOut,
    width: '111mm',
    height: '154mm',
    margin: { top: '0mm', right: '0mm', bottom: '0mm', left: '0mm' },
    printBackground: true,
    preferCSSPageSize: true,
  });
  console.log(`✓ ${pdfOut}`);
  await pdfContext.close();

  // 2. Trimmed A6 PNG preview (105 x 148 mm @ 300 DPI = 1240 x 1748 px)
  // CSS 1mm = 96/25.4 px = 3.7795275591 px
  // 111mm = 419.5276 px, 154mm = 582.0472 px; 105mm = 396.8504 px, 148mm = 559.3701 px
  // To get exact 1240 x 1748 px trimmed PNG cleanly, we open flyer-a6.html?trimmed=1
  // which renders the exact 105 x 148 mm trim box filling a 620 x 874 viewport at 2x scale = 1240 x 1748 px.
  const a6PngContext = await browser.newContext({
    viewport: { width: 620, height: 874 },
    deviceScaleFactor: 2,
  });
  const a6PngPage = await a6PngContext.newPage();
  await a6PngPage.goto(`${a6Url}?trimmed=1`, { waitUntil: 'networkidle' });
  await a6PngPage.evaluate(() => document.fonts.ready);
  const a6PngOut = join(outDir, 'flyer-a6.png');
  await a6PngPage.screenshot({ path: a6PngOut, type: 'png' });
  console.log(`✓ ${a6PngOut}`);
  await a6PngContext.close();

  // 3. Phone-size WhatsApp / Signal card (1080 x 1920 px)
  const phoneContext = await browser.newContext({
    viewport: { width: 1080, height: 1920 },
    deviceScaleFactor: 1,
  });
  const phonePage = await phoneContext.newPage();
  const phoneUrl = pathToFileURL(resolve(here, 'join-card.html')).href;
  await phonePage.goto(phoneUrl, { waitUntil: 'networkidle' });
  await phonePage.evaluate(() => document.fonts.ready);
  const phonePngOut = join(outDir, 'join-card-1080x1920.png');
  await phonePage.screenshot({ path: phonePngOut, type: 'png' });
  console.log(`✓ ${phonePngOut}`);
  await phoneContext.close();
} finally {
  await browser.close();
}
