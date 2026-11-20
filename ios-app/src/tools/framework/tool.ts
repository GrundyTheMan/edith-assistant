import type { Clock } from '../../core/clock';
import type { JsonValue, OllamaTool } from '../../ollama/types';
import type { ToolArgs } from './toolArgs';

export type ParamType = 'string' | 'integer' | 'number' | 'boolean' | 'datetime';

export interface ToolParam {
  name: string;
  type: ParamType;
  description: string;
  required?: boolean;
  enumValues?: string[];
  /** Other keys a model is known to use for this parameter (`place` for `location`). Nothing else is guessed. */
  aliases?: string[];
}
export interface ToolSpec {
  name: string;
  description: string;
  parameters: ToolParam[];
}

export type ToolResult = { ok: true; text: string } | { ok: false; text: string };
export const ok = (text: string): ToolResult => ({ ok: true, text });
export const failed = (text: string): ToolResult => ({ ok: false, text });

/** What the owner is asked to approve before a tool changes something outside the app (C08 §6, D11). */
export interface ConfirmationSpec {
  /** "Add to your calendar?" */
  title: string;
  /** Label and value rows, e.g. ["When", "Fri Oct 30, 12:00–1:00 PM"]. */
  details: [string, string][];
  confirmLabel?: string;
  denyLabel?: string;
}
export interface ConfirmationRequest extends ConfirmationSpec {
  id: string;
  toolName: string;
  confirmLabel: string;
  denyLabel: string;
}

export interface ToolContext {
  clock: Clock;
  signal?: AbortSignal;
}

/** C08 (Android) on iOS: one thing Edith can do. Tools only offered when implemented, enabled and usable (L6). */
export interface Tool {
  spec: ToolSpec;
  /** Short present-tense label for the thinking bubble. */
  statusLabel: string;
  /** Names the tool in the "Used: …" line. */
  displayName: string;
  /** Longest result text sent to the model (default 2,000). */
  maxResultChars?: number;
  /** Something to approve before `execute` runs, or null when this call needs none (reads, small changes). */
  confirmation?(args: ToolArgs, ctx: ToolContext): ConfirmationSpec | null;
  execute(args: ToolArgs, ctx: ToolContext): Promise<ToolResult>;
}
export const DEFAULT_MAX_RESULT_CHARS = 2000;

const JSON_TYPES: Record<ParamType, string> = {
  string: 'string',
  integer: 'integer',
  number: 'number',
  boolean: 'boolean',
  datetime: 'string',
};

/** `{"type":"function","function":{name,description,parameters:{type:"object",properties,required}}}` */
export function toOllama(spec: ToolSpec): OllamaTool {
  const properties: Record<string, JsonValue> = {};
  for (const p of spec.parameters) {
    const description = p.type === 'datetime' ? `${p.description} (ISO-8601, e.g. 2026-10-30T12:00:00-04:00)` : p.description;
    properties[p.name] = {
      type: JSON_TYPES[p.type],
      description,
      ...(p.enumValues ? { enum: p.enumValues } : {}),
    };
  }
  const required = spec.parameters.filter((p) => p.required).map((p) => p.name);
  return {
    type: 'function',
    function: {
      name: spec.name,
      description: spec.description,
      parameters: { type: 'object', properties, required },
    },
  };
}
