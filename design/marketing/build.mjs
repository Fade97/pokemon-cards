// Renders the tester flyer (flyer.html + flyer.css) into print and share files in out/.
//   node design/marketing/build.mjs
// Needs playwright-core and a Chromium; by default both come from the marketing-images skill
// (MARKETING_IMAGES=<skill dir> to point elsewhere, CHROME_PATH for another Chromium).
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { homedir } from 'node:os';
import { dirname, join } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const skill = process.env.MARKETING_IMAGES || join(homedir(), '.claude/skills/marketing-images');
const { chromium } = await import(pathToFileURL(join(skill, 'node_modules/playwright-core/index.mjs')).href);
const { findChromium } = await import(pathToFileURL(join(skill, 'scripts/chromium.mjs')).href);

const flyer = readFileSync(join(here, 'flyer.html'), 'utf8').replace(/<!--[\s\S]*?-->\s*/, '');
const page = (bodyClass, body, size) => `<!doctype html><html lang="de"><head><meta charset="utf-8">
<link rel="stylesheet" href="flyer.css"><style>@page { size: ${size}; margin: 0 }</style></head>
<body class="${bodyClass}">${body}</body></html>`;

const docs = {
  'flyer-a6': page('', `<div class="sheet" style="width:105mm;height:148mm">${flyer}</div>`, '105mm 148mm'),
  'flyer-a4-4up': page('', `<div class="a4">${`<div class="sheet">${flyer}</div>`.repeat(4)}
    <i class="cut v"></i><i class="cut h"></i></div>`, 'A4'),
  'join-card': page('story', `<div class="sheet" style="width:540px;height:960px">${flyer}</div>`, '540px 960px'),
};

const out = join(here, 'out');
mkdirSync(out, { recursive: true });
const browser = await chromium.launch({ executablePath: findChromium() });
const ctx = await browser.newContext({ deviceScaleFactor: 2, viewport: { width: 540, height: 960 } });
const tab = await ctx.newPage();

for (const [name, html] of Object.entries(docs)) {
  const file = join(here, `.${name}.html`);
  writeFileSync(file, html);
  await tab.goto(pathToFileURL(file).href);
  await tab.evaluate(() => document.fonts.ready);
  await tab.waitForLoadState('networkidle');
  if (name === 'join-card') {
    await tab.locator('.sheet').screenshot({ path: join(out, 'join-card-1080x1920.png') });
  } else {
    await tab.pdf({ path: join(out, `${name}.pdf`), preferCSSPageSize: true, printBackground: true });
    if (name === 'flyer-a6') {
      await tab.setViewportSize({ width: 397, height: 560 });
      await tab.locator('.sheet').screenshot({ path: join(out, 'flyer-a6-preview.png') });
      await tab.setViewportSize({ width: 540, height: 960 });
    }
  }
  console.log('wrote', name);
}
await browser.close();
