// Who may use Pro features (ADR 0007, #17). Pro is enforced here, on the server, so the open-source app can't
// unlock it by itself.
//
// Sign-in and Play Billing (#16) don't exist yet. Until then the only implementation accepts test tokens: bearer
// tokens whose SHA-256 is listed in the Worker secret PRO_TEST_TOKENS. #16 adds an implementation that checks a
// Google ID token and the account's Play purchase, behind the same interface.

export type Entitlement =
  /** No usable `Authorization: Bearer` header. */
  | "anonymous"
  /** A caller we know, without Pro. */
  | "free"
  | "pro";

export interface Entitlements {
  check(request: Request): Promise<Entitlement>;
}

/** Tokens the app sends: URL-safe, long enough not to be guessed. */
const TOKEN = /^Bearer ([A-Za-z0-9_-]{32,128})$/;

/**
 * Test tokens from a secret: SHA-256 hex digests separated by commas or whitespace. Unset or empty means nobody is
 * Pro, so a deployment without the secret fails closed. Unknown tokens count as "free": without accounts there is no
 * way to tell a mistyped tester token from anyone else.
 */
export class TestTokenEntitlements implements Entitlements {
  private readonly digests: Set<string>;

  constructor(secret: string | undefined) {
    this.digests = tokenDigests(secret);
  }

  async check(request: Request): Promise<Entitlement> {
    const token = request.headers.get("Authorization")?.match(TOKEN)?.[1];
    if (!token) return "anonymous";
    return this.digests.has(await sha256Hex(token)) ? "pro" : "free";
  }
}

/** SHA-256 hex digests from a secret, separated by commas or whitespace; anything else is ignored. */
export function tokenDigests(secret: string | undefined): Set<string> {
  return new Set(
    (secret ?? "")
      .split(/[\s,]+/)
      .map((d) => d.toLowerCase())
      .filter((d) => /^[0-9a-f]{64}$/.test(d)),
  );
}

export function entitlements(env: Env): Entitlements {
  return new TestTokenEntitlements(env.PRO_TEST_TOKENS);
}

export async function sha256Hex(text: string): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(text));
  return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, "0")).join("");
}
