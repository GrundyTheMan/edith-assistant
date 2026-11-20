/** The few SQLite operations the app needs, so the real database (expo-sqlite) and the tests' (node:sqlite) can share code. */
export type SqlParam = string | number | null;

export interface SqlAdapter {
  exec(sql: string): Promise<void>;
  run(sql: string, params?: SqlParam[]): Promise<{ lastInsertRowId: number; changes: number }>;
  all<T>(sql: string, params?: SqlParam[]): Promise<T[]>;
}

export class Db {
  private chain: Promise<unknown> = Promise.resolve();

  constructor(private readonly adapter: SqlAdapter) {}

  exec(sql: string) {
    return this.adapter.exec(sql);
  }
  run(sql: string, params?: SqlParam[]) {
    return this.adapter.run(sql, params);
  }
  all<T>(sql: string, params?: SqlParam[]) {
    return this.adapter.all<T>(sql, params);
  }
  async first<T>(sql: string, params?: SqlParam[]): Promise<T | null> {
    return (await this.adapter.all<T>(sql, params))[0] ?? null;
  }

  /** Transactions run one at a time, on the one connection: BEGIN … COMMIT, or ROLLBACK if `fn` throws. */
  transaction<T>(fn: () => Promise<T>): Promise<T> {
    const result = this.chain.then(async () => {
      await this.adapter.exec('BEGIN IMMEDIATE');
      try {
        const value = await fn();
        await this.adapter.exec('COMMIT');
        return value;
      } catch (e) {
        await this.adapter.exec('ROLLBACK');
        throw e;
      }
    });
    this.chain = result.catch(() => undefined);
    return result;
  }
}
