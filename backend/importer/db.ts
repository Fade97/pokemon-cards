// The importer's database: a local SQLite file on Vulcan, opened with Node's built-in `node:sqlite`.
//
// The jobs were written against D1, so this exposes the same small surface (prepare → bind → run/all/first/raw,
// and batch as one transaction). Keeping that shape means the job code stays as it is, and the database could
// move back to D1 without touching it.

import { readFileSync } from "node:fs";
import { DatabaseSync, type SQLInputValue, type StatementSync } from "node:sqlite";

export interface RunResult {
  meta: { changes: number };
}

export interface Stmt {
  bind(...values: unknown[]): Stmt;
  run(): Promise<RunResult>;
  all<T = Record<string, unknown>>(): Promise<{ results: T[] }>;
  first<T = Record<string, unknown>>(): Promise<T | null>;
  raw(): Promise<unknown[][]>;
}

export interface Db {
  prepare(sql: string): Stmt;
  /** Runs the statements in one transaction, like D1's batch. */
  batch(statements: Stmt[]): Promise<RunResult[]>;
}

const SCHEMA = new URL("./schema.sql", import.meta.url);

export class SqliteDb implements Db {
  readonly sqlite: DatabaseSync;
  private readonly cache = new Map<string, StatementSync>();

  /** Opens (or creates) the database at `path` and applies the schema to a new one. */
  constructor(path: string, schemaPath: string | URL = SCHEMA) {
    this.sqlite = new DatabaseSync(path);
    this.sqlite.exec("PRAGMA journal_mode = WAL; PRAGMA foreign_keys = ON; PRAGMA busy_timeout = 5000;");
    const hasSchema = this.sqlite.prepare("SELECT 1 FROM sqlite_master WHERE name = 'cards'").get();
    if (!hasSchema) this.sqlite.exec(readFileSync(schemaPath, "utf8"));
  }

  prepare(sql: string): Stmt {
    return new SqliteStmt(this, sql, []);
  }

  statement(sql: string): StatementSync {
    let s = this.cache.get(sql);
    if (!s) {
      s = this.sqlite.prepare(sql);
      this.cache.set(sql, s);
    }
    return s;
  }

  async batch(statements: Stmt[]): Promise<RunResult[]> {
    this.sqlite.exec("BEGIN");
    try {
      const results = statements.map((s) => (s as SqliteStmt).runSync());
      this.sqlite.exec("COMMIT");
      return results;
    } catch (e) {
      this.sqlite.exec("ROLLBACK");
      throw e;
    }
  }

  close(): void {
    this.sqlite.close();
  }
}

class SqliteStmt implements Stmt {
  constructor(
    private readonly db: SqliteDb,
    private readonly sql: string,
    private readonly values: SQLInputValue[],
  ) {}

  bind(...values: unknown[]): Stmt {
    // D1 binds undefined as NULL; node:sqlite rejects it.
    return new SqliteStmt(this.db, this.sql, values.map((v) => (v === undefined ? null : (v as SQLInputValue))));
  }

  runSync(): RunResult {
    const r = this.db.statement(this.sql).run(...this.values);
    return { meta: { changes: Number(r.changes) } };
  }

  async run(): Promise<RunResult> {
    return this.runSync();
  }

  async all<T>(): Promise<{ results: T[] }> {
    return { results: this.db.statement(this.sql).all(...this.values) as T[] };
  }

  async first<T>(): Promise<T | null> {
    return (this.db.statement(this.sql).get(...this.values) as T | undefined) ?? null;
  }

  async raw(): Promise<unknown[][]> {
    // Rows keep the column order of the SELECT, so their values are the raw row.
    return this.db
      .statement(this.sql)
      .all(...this.values)
      .map((row) => Object.values(row as object));
  }
}
