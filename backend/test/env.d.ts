import type { D1Migration } from "@cloudflare/vitest-plugin";

declare global {
  namespace Cloudflare {
    // `wrangler types` only emits mainModule when the Worker has Durable Objects; tests need it for `exports.default`.
    interface GlobalProps {
      mainModule: typeof import("../src/index");
    }
    interface Env {
      TEST_MIGRATIONS: D1Migration[];
    }
  }
}
