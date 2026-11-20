import { ok, failed, type ConfirmationSpec, type Tool } from '../tools/framework/tool';
import { ACTIONS, buildCommand, confirmationFor, stateWords } from './actions';
import type { Home } from './home';
import type { HomeDevice } from './model';
import { matchDevice, type Match } from './names';

const MAX_LISTED = 80;

const ambiguous = (m: Extract<Match, { kind: 'ambiguous' }>) =>
  `More than one device matches. Ask which: ${m.candidates
    .slice(0, 10)
    .map((d) => (d.room ? `${d.name} (${d.room})` : d.name))
    .join('; ')}.`;

const wanted = (d: HomeDevice, filter: string) => {
  const f = filter.trim().toLowerCase();
  return !f || d.name.toLowerCase().includes(f) || d.room.toLowerCase().includes(f) || d.type.replace('_', ' ').includes(f) || d.type.includes(f);
};

/** `home_devices`: what's in the home and its state, grouped by room (C25 §4). */
export function homeDevicesTool(home: Home): Tool {
  return {
    spec: {
      name: 'home_devices',
      description: 'Lists the smart-home devices (lights, plugs, fans, thermostats, locks, doors) with their current state, by room. Use it to see what is on or locked.',
      parameters: [{ name: 'filter', type: 'string', description: 'Optional: part of a device name, a room, or a type such as "light" or "lock"' }],
    },
    statusLabel: 'Checking your home…',
    enabled: () => home.connected,
    displayName: 'Home',
    maxResultChars: 3500,
    async execute(args) {
      const snapshot = home.connected ? await home.refresh() : { devices: [], problems: [] };
      const visible = snapshot.devices.filter((d) => !home.isHidden(d.id));
      const filter = args.string('filter') ?? '';
      const shown = visible.filter((d) => wanted(d, filter));
      const lines: string[] = [];
      if (!home.connected) return ok('No smart-home service is connected. The owner can connect one under Settings → Home.');
      if (shown.length === 0) lines.push(filter ? `No device matches "${filter}".` : 'No devices.');
      const rooms = [...new Set(shown.slice(0, MAX_LISTED).map((d) => d.room || 'No room'))].sort();
      for (const room of rooms) {
        lines.push(`${room}:`);
        for (const d of shown.slice(0, MAX_LISTED).filter((x) => (x.room || 'No room') === room)) lines.push(`- ${d.name} (${d.type.replace('_', ' ')}): ${stateWords(d)}`);
      }
      if (shown.length > MAX_LISTED) lines.push(`(${shown.length - MAX_LISTED} more not listed; ask with a filter.)`);
      for (const p of snapshot.problems) lines.push(`Didn't answer: ${p}`);
      return ok(lines.join('\n'));
    },
  };
}

const hasSnapshot = (d: HomeDevice) => d.capabilities.includes('snapshot');
const ageText = (ms: number) => (ms < 45_000 ? 'just now' : `${Math.round(ms / 60_000)} min ago`);

/** `camera_snapshot`: a current picture from a camera or doorbell, shown in Edith's bubble (C49). Read-only: no card. */
export function cameraSnapshotTool(home: Home, now: () => number = Date.now): Tool {
  return {
    spec: {
      name: 'camera_snapshot',
      description: 'Shows a current picture from a camera or doorbell. Use it for "show me the front door" or "is anyone at the door?".',
      parameters: [{ name: 'device', type: 'string', description: 'The camera or doorbell name, e.g. "front door"', required: true, aliases: ['name', 'camera', 'target'] }],
    },
    statusLabel: 'Getting a picture…',
    displayName: 'Camera',
    enabled: () => home.visible().some(hasSnapshot),
    async execute(args, ctx) {
      const cameras = home.visible().filter(hasSnapshot);
      const m = matchDevice(cameras, args.string('device') ?? '');
      if (m.kind === 'none') return failed(`No camera called "${args.string('device')}". Cameras: ${cameras.map((d) => d.name).join(', ') || 'none'}.`);
      if (m.kind === 'ambiguous') return failed(ambiguous(m));
      try {
        const shot = await home.snapshot(m.device, ctx.signal);
        const age = ageText(Math.max(0, now() - shot.takenAt));
        const stamp = new Date(shot.takenAt);
        const name = `${m.device.name.replace(/[^A-Za-z0-9._-]+/g, '-').toLowerCase()}-${stamp.getHours()}${String(stamp.getMinutes()).padStart(2, '0')}.jpg`;
        return ok(`Showing ${m.device.name}, taken ${age}.`, [{ fileName: name, mimeType: 'image/jpeg', base64: shot.jpegBase64 }]);
      } catch (e) {
        return failed(e instanceof Error && e.message ? e.message : `Couldn't get a picture from ${m.device.name}.`);
      }
    },
  };
}

/** `home_control`: do something to one device. Unlock, open, close and mode changes ask first (C25 §4). */
export function homeControlTool(home: Home): Tool {
  const resolve = async (device: string): Promise<Match> => {
    if (home.cached().length === 0 && home.connected) await home.refresh();
    return matchDevice(home.visible(), device);
  };
  return {
    spec: {
      name: 'home_control',
      description:
        'Controls one smart-home device: turn it on or off, set brightness (0-100), fan speed (low 33, medium 66, high 100), colour, a white tone, a thermostat temperature or mode, lock or unlock, open or close. Use home_devices first if unsure of the name.',
      parameters: [
        { name: 'device', type: 'string', description: 'The device name, e.g. "porch light" or "front door"', required: true, aliases: ['name', 'target'] },
        { name: 'action', type: 'string', description: 'What to do', required: true, enumValues: [...ACTIONS] },
        { name: 'value', type: 'string', description: 'For set_* actions: the number, or the colour, white tone or mode', aliases: ['level', 'amount'] },
      ],
    },
    statusLabel: 'Controlling your home…',
    enabled: () => home.connected,
    displayName: 'Home',
    async confirmation(args): Promise<ConfirmationSpec | null> {
      const m = await resolve(args.string('device') ?? '');
      if (m.kind !== 'found') return null; // execute reports it; nothing is done
      const built = buildCommand(m.device, args.string('action') ?? '', args.string('value'));
      return built.ok ? confirmationFor(m.device, built.command) : null;
    },
    async execute(args) {
      if (!home.connected) return failed('No smart-home service is connected. The owner can connect one under Settings → Home.');
      const m = await resolve(args.string('device') ?? '');
      if (m.kind === 'none') return failed(`No device called "${args.string('device')}". Use home_devices to see the names.`);
      if (m.kind === 'ambiguous') return failed(ambiguous(m));
      const built = buildCommand(m.device, args.string('action') ?? '', args.string('value'));
      if (!built.ok) return failed(built.error);
      const result = await home.command(m.device, built.command, 'chat');
      return result.ok ? ok(result.text) : failed(result.text);
    },
  };
}
