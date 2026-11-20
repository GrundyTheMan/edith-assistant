import type { Clock } from '../core/clock';
import type { ModelInfo } from '../ollama/types';
import { PERSONA } from './persona';

export interface PromptContext {
  /** Epoch millis, the zone id and the UTC offset at that time. */
  now: number;
  zone: string;
  offsetMinutes: number;
  model: ModelInfo;
}
/** One capability paragraph of the system prompt (01-architecture §6). Return null to leave it out. */
export interface PromptSection {
  text(ctx: PromptContext): string | null;
}

const DAYS = ['Sunday', 'Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday'];
const MONTHS = [
  'January', 'February', 'March', 'April', 'May', 'June',
  'July', 'August', 'September', 'October', 'November', 'December',
];

export function formatOffset(offsetMinutes: number): string {
  const sign = offsetMinutes < 0 ? '-' : '+';
  const abs = Math.abs(offsetMinutes);
  const hh = String(Math.floor(abs / 60)).padStart(2, '0');
  const mm = String(abs % 60).padStart(2, '0');
  return `UTC${sign}${hh}:${mm}`;
}

/**
 * "Current date and time: Thursday, October 29, 2026, 2:30 PM (America/Detroit, UTC-04:00)." (C06, with C18's offset:
 * without it gemma4 guessed -04:00 the day after daylight saving ended, 04-lessons L29.)
 */
export function describeMoment(now: number, zone: string, offsetMinutes: number): string {
  const local = new Date(now + offsetMinutes * 60_000);
  const h24 = local.getUTCHours();
  const h12 = h24 % 12 === 0 ? 12 : h24 % 12;
  const minutes = String(local.getUTCMinutes()).padStart(2, '0');
  const date = `${DAYS[local.getUTCDay()]}, ${MONTHS[local.getUTCMonth()]} ${local.getUTCDate()}, ${local.getUTCFullYear()}`;
  return `${date}, ${h12}:${minutes} ${h24 < 12 ? 'AM' : 'PM'} (${zone}, ${formatOffset(offsetMinutes)})`;
}

export const dateTimeSection: PromptSection = {
  text: (ctx) => `Current date and time: ${describeMoment(ctx.now, ctx.zone, ctx.offsetMinutes)}.`,
};

/** This chunk has no tools, so the model is told so, rather than left to pretend. */
export const noToolsSection: PromptSection = {
  text: () =>
    "You have no tools in this chat: you can't look things up, check the weather, read calendars or mail, or control anything. If asked for something that needs them, say so plainly and help with what you know.",
};

/** Persona, a blank line, then every non-null section on its own line. */
export class PromptBuilder {
  constructor(
    private readonly clock: Clock,
    private readonly sections: () => PromptSection[],
  ) {}

  system(model: ModelInfo): string {
    const now = this.clock.now();
    const ctx: PromptContext = { now, zone: this.clock.zone(), offsetMinutes: this.clock.offsetMinutes(now), model };
    const active = this.sections()
      .map((s) => s.text(ctx))
      .filter((t): t is string => !!t && t.trim().length > 0);
    return active.length === 0 ? PERSONA : `${PERSONA}\n\n${active.join('\n')}`;
  }
}

/** C15: for a model that can see, say how the owner's pictures reach it. */
export const attachmentsSection: PromptSection = {
  text: (ctx) =>
    ctx.model.capabilities.includes('vision')
      ? 'The user can attach images; they appear as labels like [Attachment 3: receipt.jpg (image)]. Pictures you showed the user (a camera snapshot) appear as labels in your own messages.'
      : null,
};
