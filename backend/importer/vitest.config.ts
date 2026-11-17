import { defineConfig } from "vitest/config";

// The importer runs on Node (Vulcan), so its tests do too, with an in-memory SQLite database.
export default defineConfig({
  test: { root: "importer", include: ["test/**/*.test.ts"], environment: "node" },
});
