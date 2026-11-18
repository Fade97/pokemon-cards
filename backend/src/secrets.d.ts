// Worker secrets, set with `wrangler secret put` (or `.dev.vars` locally). `wrangler types` only sees bindings
// from wrangler.jsonc, so secrets are declared here.

interface Env {
  /** SHA-256 hex digests of Pro test tokens, separated by commas or whitespace (src/entitlements.ts, #17). */
  PRO_TEST_TOKENS?: string;
  /** SHA-256 hex digests of admin tokens for contribution review (src/contribution-review.ts, #135). */
  CONTRIBUTION_ADMIN_TOKENS?: string;
}

declare namespace Cloudflare {
  interface Env {
    PRO_TEST_TOKENS?: string;
    CONTRIBUTION_ADMIN_TOKENS?: string;
  }
}
