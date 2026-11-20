import type { PromptSection } from '../../chat/promptBuilder';
import { canUseTools, type ModelInfo } from '../../ollama/types';
import type { Tool } from './tool';

function editDistance(a: string, b: string): number {
  const prev = Array.from({ length: b.length + 1 }, (_, j) => j);
  for (let i = 1; i <= a.length; i++) {
    let diag = prev[0];
    prev[0] = i;
    for (let j = 1; j <= b.length; j++) {
      const up = prev[j];
      prev[j] = Math.min(prev[j] + 1, prev[j - 1] + 1, diag + (a[i - 1] === b[j - 1] ? 0 : 1));
      diag = up;
    }
  }
  return prev[b.length];
}

const normaliseName = (name: string) => {
  let n = name.trim().toLowerCase();
  if (n.startsWith('functions.')) n = n.slice('functions.'.length);
  return n.split('-').join('_').split('.').join('_').split(' ').join('_');
};

/** The tools Edith has. Only a model that can use tools is offered any (L6, L7: nothing broken, nothing desktop). */
export class ToolRegistry {
  constructor(private readonly tools: Tool[]) {}

  all(): Tool[] {
    return this.tools;
  }
  available(model: ModelInfo): Tool[] {
    return canUseTools(model) ? this.tools.filter((t) => t.enabled?.() ?? true) : [];
  }
  displayName(toolName: string): string {
    return this.tools.find((t) => t.spec.name === toolName)?.displayName ?? toolName;
  }

  /** Exact, then normalised ("functions.get_weather", "Get-Weather"), then one typo for names of 8+ characters. */
  resolve(name: string, offered: Tool[]): Tool | null {
    const exact = offered.find((t) => t.spec.name === name);
    if (exact) return exact;
    const n = normaliseName(name);
    const normalised = offered.find((t) => t.spec.name === n);
    if (normalised) return normalised;
    return offered.find((t) => t.spec.name.length >= 8 && editDistance(t.spec.name, n) <= 1) ?? null;
  }
}

/** C08 §4: what the model may do, or a plain "you can't look things up" for a model without tools. */
export function toolGuidanceSection(registry: ToolRegistry): PromptSection {
  return {
    text(ctx) {
      if (registry.all().length === 0) return null;
      const offered = registry.available(ctx.model);
      if (offered.length === 0) {
        return "You can't look things up right now; if asked for current information, say so briefly.";
      }
      const list = offered.map((t) => `${t.spec.name} (${t.spec.description})`).join(', ');
      const web = offered.some((t) => t.spec.name === 'search_web')
        ? ' For questions about current events, sports, prices or anything that changes, search first; read a page if the snippets aren\'t enough; mention the source site.'
        : '';
      return (
        `You can use these tools when they help: ${list}.\n` +
        'Use a tool instead of guessing whenever the answer depends on current or personal information. ' +
        `After using tools, answer in your own words and mention where information came from when it's from the web.${web}`
      );
    },
  };
}
