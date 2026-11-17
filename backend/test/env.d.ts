declare global {
  namespace Cloudflare {
    // `wrangler types` only emits mainModule when the Worker has Durable Objects; tests need it for `exports.default`.
    interface GlobalProps {
      mainModule: typeof import("../src/index");
    }
  }
}

export {};
