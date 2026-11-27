import { spawnSync } from "node:child_process";
import { mkdir, rm } from "node:fs/promises";
import { homedir } from "node:os";
import { join } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const root = fileURLToPath(new URL(".", import.meta.url));
const marketing = join(homedir(), ".claude/skills/marketing-images");
const playwrightPath = process.env.FOILARK_PLAYWRIGHT
  ?? join(marketing, "node_modules/playwright-core/index.mjs");
const { chromium } = await import(pathToFileURL(playwrightPath));
const chromePath = process.env.CHROME_PATH
  ?? (await import(pathToFileURL(join(marketing, "scripts/chromium.mjs")))).findChromium();
const python = process.env.FOILARK_PYTHON ?? join(homedir(), "VorlagenWerk24/.venv/bin/python");

function raster(step) {
  const job = spawnSync(python, [join(root, "raster.py"), step], { stdio: "inherit" });
  if (job.error) throw job.error;
  if (job.status !== 0) throw new Error(`Raster step failed: ${step}`);
}

async function loadPage(page, format, viewport) {
  await page.setViewportSize(viewport);
  await page.goto(new URL(`flyer.html?format=${format}`, import.meta.url).href);
  await page.evaluate(async () => {
    await document.fonts.ready;
    if (!document.fonts.check("800 40px Schibsted")) throw new Error("Flyer font did not load");
    await Promise.all(Array.from(document.images, (img) => img.decode()));
  });
}

await mkdir(join(root, "out"), { recursive: true });
const browser = await chromium.launch({ executablePath: chromePath, args: ["--no-sandbox"] });
try {
  raster("screen");
  const page = await browser.newPage();
  await loadPage(page, "print", { width: 420, height: 583 });
  await page.pdf({
    path: join(root, ".browser-print.pdf"),
    preferCSSPageSize: true,
    printBackground: true,
  });
  raster("print");

  await loadPage(page, "phone", { width: 1080, height: 1920 });
  await page.screenshot({ path: join(root, "out/join-card-1080x1920.png") });
} finally {
  await browser.close();
  await rm(join(root, ".browser-print.pdf"), { force: true });
}
