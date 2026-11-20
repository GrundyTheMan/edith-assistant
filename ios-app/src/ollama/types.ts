/** DTOs mirroring 03-ollama-reference exactly. `arguments` is an object, never a string (04-lessons L2). */
export const CONTEXT_LENGTH = 16384;

export type JsonValue = string | number | boolean | null | JsonValue[] | { [key: string]: JsonValue };

export interface OllamaFunctionCall {
  name: string;
  arguments: Record<string, JsonValue>;
  index?: number;
}
export interface OllamaToolCall {
  id?: string;
  function: OllamaFunctionCall;
}
export interface OllamaMessage {
  role: 'system' | 'user' | 'assistant' | 'tool';
  content: string;
  images?: string[];
  tool_calls?: OllamaToolCall[];
  tool_name?: string;
}
export interface OllamaTool {
  type: 'function';
  function: { name: string; description: string; parameters: Record<string, JsonValue> };
}
export interface OllamaChatChunk {
  model?: string;
  message?: OllamaMessage;
  done?: boolean;
  done_reason?: string;
  total_duration?: number;
  eval_count?: number;
  prompt_eval_count?: number;
  error?: string;
}
export interface OllamaModelTag {
  name: string;
  size?: number;
  modified_at?: string;
}
export interface OllamaShow {
  capabilities?: string[];
}

export interface ChatCall {
  model: string;
  messages: OllamaMessage[];
  tools?: OllamaTool[];
}
export interface ReplyStats {
  totalMs?: number;
  evalCount?: number;
  promptEvalCount?: number;
}
export type ChatEvent =
  | { type: 'delta'; text: string }
  | { type: 'tool_calls'; message: OllamaMessage }
  | { type: 'done'; stats: ReplyStats };

export interface ModelInfo {
  name: string;
  capabilities: string[];
}
export const canUseTools = (m: ModelInfo) => m.capabilities.includes('tools');
export const canSee = (m: ModelInfo) => m.capabilities.includes('vision');
export const canHear = (m: ModelInfo) => m.capabilities.includes('audio');
