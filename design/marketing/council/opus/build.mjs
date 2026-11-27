// Builds the Opus council flyer: node design/marketing/council/opus/build.mjs
// Outputs: out/flyer-a6-bleed.pdf (111 x 154 mm), out/flyer-a6.png (trim, 1240 px wide),
//          out/join-card-1080x1920.png
import { chromium } from '/home/fdu/.claude/skills/marketing-images/node_modules/playwright-core/index.mjs';
import { findChromium } from '/home/fdu/.claude/skills/marketing-images/scripts/chromium.mjs';
import { mkdirSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const url = pathToFileURL(join(here, 'flyer.html')).href;
const out = join(here, 'out');
mkdirSync(out, { recursive: true });
const MM = 96 / 25.4; // CSS px per mm

const browser = await chromium.launch({ executablePath: findChromium() });
try {
  // Print PDF with bleed
  const p = await browser.newPage();
  await p.goto(url, { waitUntil: 'networkidle' });
  await p.evaluate(() => document.fonts.ready);
  await p.pdf({ path: join(out, 'flyer-a6-bleed.pdf'), width: '111mm', height: '154mm', printBackground: true, pageRanges: '1' });
  await p.close();

  // A6 preview, trimmed (105 mm at 3x = 1191 px)
  const scale = 3;
  const a = await browser.newPage({ viewport: { width: Math.ceil(111 * MM), height: Math.ceil(154 * MM) }, deviceScaleFactor: scale });
  await a.goto(url, { waitUntil: 'networkidle' });
  await a.evaluate(() => document.fonts.ready);
  await a.screenshot({ path: join(out, 'flyer-a6.png'), clip: { x: 3 * MM, y: 3 * MM, width: 105 * MM, height: 148 * MM } });
  await a.close();

  // Phone card: same design on a 105 x 186.667 mm canvas
  // 105 mm is zoomed to exactly 540 CSS px, rendered at 2x -> 1080 x 1920
  const c = await browser.newPage({ viewport: { width: 540, height: 960 }, deviceScaleFactor: 2 });
  await c.goto(url, { waitUntil: 'networkidle' });
  await c.evaluate((z) => { document.body.classList.add('card'); document.documentElement.style.zoom = z; return document.fonts.ready; }, 540 / (105 * MM));
  await c.screenshot({ path: join(out, 'join-card-1080x1920.png'), clip: { x: 0, y: 0, width: 540, height: 960 } });
  await c.close();
} finally {
  await browser.close();
}
console.log('built', out);
