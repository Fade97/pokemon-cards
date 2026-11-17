import { cloudflareTest } from "@cloudflare/vitest-plugin";
import { defineConfig } from "vitest/config";

// Worker tests run inside workerd with the R2 buckets from wrangler.jsonc. The importer has its own Node config.
export default defineConfig({
  plugins: [cloudflareTest({ wrangler: { configPath: "./wrangler.jsonc" } })],
  test: { include: ["test/**/*.test.ts"] },
});
