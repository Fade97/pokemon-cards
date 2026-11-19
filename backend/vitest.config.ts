import { cloudflareTest, readD1Migrations } from "@cloudflare/vitest-plugin";
import { createHash } from "node:crypto";
import { defineConfig } from "vitest/config";

/** The Pro test token test/history.test.ts sends; the Worker only knows its SHA-256 (src/entitlements.ts). */
const PRO_TEST_TOKEN = "test-pro-token-0123456789-abcdefghijklmnop";
/** The admin token test/contributions.test.ts sends for contribution review (src/contribution-review.ts). */
const ADMIN_TEST_TOKEN = "test-admin-token-0123456789-abcdefghijkl";

// Worker tests run inside workerd with the D1 database (contributions) and R2 buckets from wrangler.jsonc.
// The importer has its own Node config (importer/vitest.config.ts).
export default defineConfig(async () => ({
  plugins: [
    cloudflareTest({
      wrangler: { configPath: "./wrangler.jsonc" },
      miniflare: {
        bindings: {
          TEST_MIGRATIONS: await readD1Migrations("./migrations"),
          PRO_TEST_TOKENS: createHash("sha256").update(PRO_TEST_TOKEN).digest("hex"),
          CONTRIBUTION_ADMIN_TOKENS: createHash("sha256").update(ADMIN_TEST_TOKEN).digest("hex"),
        },
      },
    }),
  ],
  test: { include: ["test/**/*.test.ts"], setupFiles: ["./test/apply-migrations.ts"] },
}));
