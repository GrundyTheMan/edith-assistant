import type { JsonValue } from '../../ollama/types';
import type { ToolSpec } from './tool';

/** A required argument is missing; the message goes back to the model. */
export class ArgumentError extends Error {
  constructor(readonly argName: string) {
    super(`missing ${argName}`);
  }
}

const normalise = (s: string) => s.toLowerCase().replace(/[_\-\s]/g, '');

/**
 * Tolerant argument reading (C08 §2): numbers given as strings, "true"/"yes", a key repaired from a declared alias or by
 * case/underscore, a missing optional value is null, a missing required one is an ArgumentError.
 */
export class ToolArgs {
  private readonly values = new Map<string, JsonValue>();

  constructor(
    raw: Record<string, JsonValue> | undefined,
    private readonly spec: ToolSpec,
  ) {
    const given = raw ?? {};
    for (const p of spec.parameters) {
      const candidates = [p.name, ...(p.aliases ?? [])];
      let found: JsonValue | undefined = undefined;
      for (const c of candidates) {
        if (c in given && given[c] !== null && given[c] !== '') {
          found = given[c];
          break;
        }
      }
      if (found === undefined) {
        const wanted = new Set(candidates.map(normalise));
        const key = Object.keys(given).find((k) => wanted.has(normalise(k)) && given[k] !== null && given[k] !== '');
        if (key !== undefined) found = given[key];
      }
      if (found !== undefined) this.values.set(p.name, found);
    }
  }

  private raw(name: string): JsonValue | undefined {
    const v = this.values.get(name);
    if (v === undefined && this.spec.parameters.find((p) => p.name === name)?.required) throw new ArgumentError(name);
    return v;
  }

  string(name: string): string | null {
    const v = this.raw(name);
    if (v === undefined) return null;
    if (typeof v === 'string') return v;
    if (typeof v === 'number' || typeof v === 'boolean') return String(v);
    return null;
  }
  number(name: string): number | null {
    const v = this.raw(name);
    if (typeof v === 'number') return Number.isFinite(v) ? v : null;
    if (typeof v === 'string' && v.trim() !== '') {
      const n = Number(v.trim());
      return Number.isFinite(n) ? n : null;
    }
    return null;
  }
  int(name: string): number | null {
    const n = this.number(name);
    return n === null ? null : Math.trunc(n);
  }
  bool(name: string): boolean | null {
    const v = this.raw(name);
    if (typeof v === 'boolean') return v;
    if (typeof v === 'string') {
      const s = v.trim().toLowerCase();
      if (s === 'true' || s === 'yes') return true;
      if (s === 'false' || s === 'no') return false;
    }
    return null;
  }
}
