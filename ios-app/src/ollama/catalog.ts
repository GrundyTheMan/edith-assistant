import type { OllamaClient } from './client';
import type { ModelInfo } from './types';

/** Chat-capable models with their capabilities (C03 §6). Capabilities are cached per (server, model). */
export class ModelCatalog {
  private readonly cache = new Map<string, string[]>();

  constructor(private readonly client: OllamaClient) {}

  /** Hides models that lack "completion" (embedding models). A model whose `show` fails stays, with no capabilities. */
  async chatModels(baseUrl: string): Promise<ModelInfo[]> {
    const tags = await this.client.listModels(baseUrl);
    const models = await Promise.all(
      tags.map(async (t): Promise<ModelInfo> => ({ name: t.name, capabilities: await this.capabilities(baseUrl, t.name) })),
    );
    return models.filter((m) => m.capabilities.length === 0 || m.capabilities.includes('completion'));
  }

  async capabilities(baseUrl: string, model: string): Promise<string[]> {
    const key = `${baseUrl}\n${model}`;
    const cached = this.cache.get(key);
    if (cached) return cached;
    try {
      const caps = (await this.client.show(baseUrl, model)).capabilities ?? [];
      this.cache.set(key, caps);
      return caps;
    } catch {
      return [];
    }
  }

  invalidate() {
    this.cache.clear();
  }
}

/** "Sees images · Hears audio · Uses tools" (only the ones present). */
export function capabilityLine(m: ModelInfo): string {
  const parts: string[] = [];
  if (m.capabilities.includes('vision')) parts.push('Sees images');
  if (m.capabilities.includes('audio')) parts.push('Hears audio');
  if (m.capabilities.includes('tools')) parts.push('Uses tools');
  return parts.join(' · ');
}
