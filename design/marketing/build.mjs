// Renders every flyer variant (variants/<name>.html + variants/<name>.css on top of base.css) into print and
// share files in out/<name>/.
//   node design/marketing/build.mjs [<name>]
// Needs playwright-core and a Chromium; by default both come from the marketing-images skill
// (MARKETING_IMAGES=<skill dir> to point elsewhere, CHROME_PATH for another Chromium).
import { mkdirSync, readdirSync, readFileSync, writeFileSync } from 'node:fs';
import { homedir } from 'node:os';
import { dirname, join } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const skill = process.env.MARKETING_IMAGES || join(homedir(), '.claude/skills/marketing-images');
const { chromium } = await import(pathToFileURL(join(skill, 'node_modules/playwright-core/index.mjs')).href);
const { findChromium } = await import(pathToFileURL(join(skill, 'scripts/chromium.mjs')).href);

const variants = readdirSync(join(here, 'variants')).filter((f) => f.endsWith('.html')).sort()
  .map((f) => [f.replace(/\.html$/, ''), readFileSync(join(here, 'variants', f), 'utf8').replace(/<!--[\s\S]*?-->\s*/, '')]);
const page = (variant, bodyClass, body, size) => `<!doctype html><html lang="de"><head><meta charset="utf-8">
<link rel="stylesheet" href="base.css"><link rel="stylesheet" href="variants/${variant}.css"><style>@page { size: ${size}; margin: 0 }</style></head>
<body class="${bodyClass}">${body}</body></html>`;

const docsFor = (variant, flyer) => ({
  // Print shop: A6 with 3 mm bleed on every side (111 × 154 mm page, trim to 105 × 148 mm).
  'flyer-a6-bleed': page(variant, 'bleed', `<div class="page"><div class="sheet">${flyer}</div></div>`, '111mm 154mm'),
  // Preview of the trimmed A6, PNG only.
  'flyer-a6': page(variant, '', `<div class="sheet" style="width:105mm;height:148mm;overflow:hidden">${flyer}</div>`, '105mm 148mm'),
  'flyer-a4-4up': page(variant, '', `<div class="a4">${`<div class="sheet">${flyer}</div>`.repeat(4)}</div>`, 'A4'),
  'join-card': page(variant, 'story', `<div class="sheet" style="width:540px;height:960px;overflow:hidden">${flyer}</div>`, '540px 960px'),
});

const only = process.argv[2]; // optional: build one variant, e.g. `node build.mjs b-scan`
const browser = await chromium.launch({ executablePath: findChromium() });
const ctx = await browser.newContext({ deviceScaleFactor: 2, viewport: { width: 540, height: 960 } });
const tab = await ctx.newPage();

for (const [variant, flyer] of variants) {
  if (only && variant !== only) continue;
  const out = join(here, 'out', variant);
  mkdirSync(out, { recursive: true });
  for (const [name, html] of Object.entries(docsFor(variant, flyer))) {
    const file = join(here, `.${name}.html`);
    writeFileSync(file, html);
    await tab.goto(pathToFileURL(file).href);
    await tab.evaluate(() => document.fonts.ready);
    await tab.waitForLoadState('networkidle');
    if (name === 'join-card') {
      await tab.locator('.sheet').screenshot({ path: join(out, 'join-card-1080x1920.png') });
    } else if (name === 'flyer-a6') {
      await tab.setViewportSize({ width: 397, height: 560 });
      await tab.locator('.sheet').screenshot({ path: join(out, 'flyer-a6-preview.png') });
      await tab.setViewportSize({ width: 540, height: 960 });
    } else {
      await tab.pdf({ path: join(out, `${name}.pdf`), preferCSSPageSize: true, printBackground: true });
    }
  }
  console.log('wrote', variant);
}
await browser.close();
