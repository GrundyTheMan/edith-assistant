import { AppError } from '../core/errors';
import type { ModelCatalog } from '../ollama/catalog';
import { canUseTools, type ModelInfo } from '../ollama/types';
import type { SettingsRepository } from './settings';

/** Default pick (C04 §2): first `gemma4:12b*`, else any `gemma4`, else the first with tools, else the first. */
export function chooseDefaultModel(models: ModelInfo[]): ModelInfo | undefined {
  return (
    models.find((m) => m.name.startsWith('gemma4:12b')) ??
    models.find((m) => m.name.startsWith('gemma4')) ??
    models.find(canUseTools) ??
    models[0]
  );
}

/**
 * Keeps an installed saved model, otherwise picks a default, saves it and leaves a one-time notice for `takeNotice`
 * (04-lessons L21). A server that's down stays a server error, not "no models".
 */
export class ModelSelection {
  private notice: string | null = null;

  constructor(
    private readonly settings: SettingsRepository,
    private readonly catalog: ModelCatalog,
  ) {}

  async ensureModel(): Promise<ModelInfo> {
    const { serverUrl, model } = await this.settings.current();
    const models = await this.catalog.chatModels(serverUrl);
    if (models.length === 0) throw AppError.noChatModels();
    const saved = model ? models.find((m) => m.name === model) : undefined;
    if (saved) return saved;
    const pick = chooseDefaultModel(models)!;
    await this.settings.update((s) => ({ ...s, model: pick.name }));
    if (model) this.notice = `Your model ${model} is no longer installed; switched to ${pick.name}.`;
    return pick;
  }

  takeNotice(): string | null {
    const n = this.notice;
    this.notice = null;
    return n;
  }
}
