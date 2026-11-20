import { describeMoment } from '../../chat/promptBuilder';
import { failed, ok, type Tool } from '../framework/tool';

/** Minutes east of UTC at `at` in an IANA zone, from Intl (works for any zone, daylight saving included). */
export function zoneOffsetMinutes(zone: string, at: number): number {
  const parts = new Intl.DateTimeFormat('en-US', {
    timeZone: zone,
    hourCycle: 'h23',
    year: 'numeric',
    month: 'numeric',
    day: 'numeric',
    hour: 'numeric',
    minute: 'numeric',
    second: 'numeric',
  }).formatToParts(new Date(at));
  const n = (type: string) => Number(parts.find((p) => p.type === type)?.value);
  const wall = Date.UTC(n('year'), n('month') - 1, n('day'), n('hour'), n('minute'), n('second'));
  return Math.round((wall - Math.floor(at / 1000) * 1000) / 60_000);
}

/** `get_current_time(timezone?)` (C08 §7a): a real, deterministic tool. */
export const currentTimeTool: Tool = {
  spec: {
    name: 'get_current_time',
    description: 'the current date and time, here or in another time zone',
    parameters: [{ name: 'timezone', type: 'string', description: 'An IANA zone like "Asia/Tokyo"; leave out for the local time', aliases: ['time_zone', 'zone'] }],
  },
  statusLabel: 'Checking the time…',
  displayName: 'time',
  async execute(args, ctx) {
    const requested = args.string('timezone');
    const now = ctx.clock.now();
    const zone = requested?.trim() || ctx.clock.zone();
    try {
      const offset = requested?.trim() ? zoneOffsetMinutes(zone, now) : ctx.clock.offsetMinutes(now);
      return ok(describeMoment(now, zone, offset));
    } catch {
      return failed(`"${requested}" isn't a time zone I know. Use a name like America/New_York or Asia/Tokyo.`);
    }
  },
};
