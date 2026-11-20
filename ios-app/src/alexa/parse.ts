import type { Capability, DeviceCommand, DeviceState, DeviceType } from '../home/model';

/** Amazon sites an Alexa account can sign in through, by the country the account is in (Jarvis's `AMAZON_SITES`). */
export interface AmazonSite {
  site: string;
  label: string;
  language: string;
}
export const AMAZON_SITES: AmazonSite[] = [
  { site: 'amazon.com', label: 'United States (amazon.com)', language: 'en_US' },
  { site: 'amazon.ca', label: 'Canada (amazon.ca)', language: 'en_CA' },
  { site: 'amazon.co.uk', label: 'United Kingdom (amazon.co.uk)', language: 'en_GB' },
  { site: 'amazon.com.au', label: 'Australia (amazon.com.au)', language: 'en_AU' },
  { site: 'amazon.de', label: 'Germany (amazon.de)', language: 'de_DE' },
  { site: 'amazon.fr', label: 'France (amazon.fr)', language: 'fr_FR' },
  { site: 'amazon.it', label: 'Italy (amazon.it)', language: 'it_IT' },
  { site: 'amazon.es', label: 'Spain (amazon.es)', language: 'es_ES' },
];
export const DEFAULT_SITE = AMAZON_SITES[0];
export const siteOf = (site: string): AmazonSite | undefined => AMAZON_SITES.find((s) => s.site === site);

/** The scale Alexa expects for an account on this site (`.com` → °F, as Jarvis's `temperatureScale`). */
export type TempScale = 'FAHRENHEIT' | 'CELSIUS';
export const scaleOf = (site: AmazonSite): TempScale => (site.site === 'amazon.com' ? 'FAHRENHEIT' : 'CELSIUS');

/** One property a state request asks for, e.g. `Alexa.PowerController` / `powerState`. */
export interface StateProperty {
  namespace: string;
  name: string;
  instance?: string;
}

/** One smart-home device on the Alexa account, as the device list gives it (Jarvis's `AlexaDevice`, without the state). */
export interface AlexaDevice {
  /** The id Alexa's control requests take. */
  entityId: string;
  /** The ids its state requests take: its appliance id, and any merged into it. */
  applianceIds: string[];
  /** The state properties Alexa can report for it; none, and it isn't asked. */
  properties: StateProperty[];
  name: string;
  /** Alexa's category code: `LIGHT`, `SMARTLOCK`, … */
  type: string;
  /** The control actions Alexa lists for it (`turnOn`, `setBrightness`, …); empty when it lists none. */
  actions: string[];
  /** A thermostat's modes, lower case, when its capabilities list them. */
  modes: string[];
  manufacturer?: string;
}

/** What one lookup learned about one device. */
export interface AlexaReading {
  state: DeviceState | null;
  unreachable?: boolean;
  noAnswer?: boolean;
}

type Obj = Record<string, unknown>;
const obj = (v: unknown): Obj => (v && typeof v === 'object' && !Array.isArray(v) ? (v as Obj) : {});
const arr = (v: unknown): unknown[] => (Array.isArray(v) ? v : []);
const text = (v: unknown): string | undefined => (typeof v === 'string' && v.trim() ? v.trim() : undefined);
const num = (v: unknown): number | undefined => (typeof v === 'number' && Number.isFinite(v) ? v : undefined);

/** The properties a device's capabilities say Alexa can report: each capability's supported properties where it's marked retrievable. */
export function stateProperties(capabilities: unknown): StateProperty[] {
  const out: StateProperty[] = [];
  for (const raw of arr(capabilities)) {
    const cap = obj(raw);
    const namespace = text(cap.interfaceName);
    if (!namespace) continue;
    const props = obj(cap.properties);
    if (props.retrievable !== true) continue;
    for (const p of arr(props.supported)) {
      const name = text(obj(p).name);
      if (name) out.push({ namespace, name, ...(text(cap.instance) ? { instance: text(cap.instance) } : {}) });
    }
  }
  return out;
}

/** The thermostat modes a device's capabilities list (`Alexa.ThermostatController`'s `supportedModes`), lower case. */
export function thermostatModes(capabilities: unknown): string[] {
  const out: string[] = [];
  for (const raw of arr(capabilities)) {
    const cap = obj(raw);
    if (text(cap.interfaceName) !== 'Alexa.ThermostatController') continue;
    for (const m of arr(obj(cap.configuration).supportedModes)) {
      const mode = (text(m) ?? text(obj(m).value))?.toLowerCase();
      if (mode && !out.includes(mode)) out.push(mode);
    }
  }
  return out;
}

/**
 * The devices from the endpoints Alexa's device service lists (ported from Jarvis's `shared/alexa.ts` through Android's `AlexaParse`). The Alexa
 * app registers itself as a device and is left out; Echo speakers and Fire TVs (`ALEXA_VOICE_ENABLED`) report no smart-home state and are left out
 * too. Never throws on an answer it doesn't understand.
 */
export function parseEndpoints(items: unknown): AlexaDevice[] {
  const out: AlexaDevice[] = [];
  for (const raw of arr(items)) {
    const item = obj(raw);
    const legacy = obj(item.legacyAppliance);
    const entityId = text(legacy.entityId) ?? text(item.id);
    if (!entityId) continue;
    const types = arr(legacy.applianceTypes).map(text).filter((t): t is string => !!t);
    const primary = text(obj(obj(item.displayCategories).primary).value) ?? types[0];
    if (/alexa (mobile|app)/i.test(text(legacy.friendlyDescription) ?? '')) continue;
    if (primary === 'ALEXA_VOICE_ENABLED') continue;
    const merged = arr(legacy.mergedApplianceIds).map(text).filter((t): t is string => !!t);
    const applianceId = text(legacy.applianceId);
    out.push({
      entityId,
      applianceIds: [...new Set([...(applianceId ? [applianceId] : []), ...merged])],
      properties: stateProperties(legacy.capabilities),
      name: text(item.friendlyName) ?? text(legacy.friendlyName) ?? 'Unnamed device',
      type: primary ?? 'OTHER',
      actions: arr(legacy.actions).map(text).filter((t): t is string => !!t),
      modes: thermostatModes(legacy.capabilities),
      manufacturer: text(obj(obj(item.manufacturer).value).text) ?? text(legacy.manufacturerName),
    });
  }
  return out.sort((a, b) => (a.type < b.type ? -1 : a.type > b.type ? 1 : a.name.toLowerCase() < b.name.toLowerCase() ? -1 : a.name.toLowerCase() > b.name.toLowerCase() ? 1 : 0));
}

/** A temperature Alexa reported (`{value, scale}`), in `want` when given: 18 °C on a °F account reads 64.4. */
function inScale(v: unknown, want?: TempScale): number | undefined {
  const t = obj(v);
  const value = num(t.value);
  if (value === undefined) return undefined;
  const s = text(t.scale);
  const scale: TempScale | undefined = s === 'CELSIUS' ? 'CELSIUS' : s === 'FAHRENHEIT' ? 'FAHRENHEIT' : undefined;
  if (!want || !scale || scale === want) return value;
  return want === 'FAHRENHEIT' ? (value * 9) / 5 + 32 : ((value - 32) * 5) / 9;
}
const percent = (v: unknown): number | undefined => {
  const n = num(v) ?? num(obj(v).value);
  return n === undefined ? undefined : Math.min(100, Math.max(0, Math.trunc(n)));
};

function read(s: Obj, into: DeviceState, scale?: TempScale) {
  const v = s.value;
  const t = text(v);
  switch (`${text(s.namespace)}.${text(s.name)}`) {
    case 'Alexa.PowerController.powerState':
      if (t === 'ON' || t === 'OFF') into.on = t === 'ON';
      break;
    case 'Alexa.BrightnessController.brightness':
      into.brightness = percent(v);
      break;
    case 'Alexa.PercentageController.percentage':
      into.percentage = percent(v);
      break;
    case 'Alexa.LockController.lockState':
      into.lock = t === 'LOCKED' ? 'locked' : t === 'UNLOCKED' ? 'unlocked' : t === 'JAMMED' ? 'jammed' : undefined;
      break;
    case 'Alexa.ThermostatController.targetSetpoint':
      into.setpoint = num(v) ?? inScale(v, scale);
      break;
    case 'Alexa.TemperatureSensor.temperature':
      into.temperature = num(v) ?? inScale(v, scale);
      break;
    case 'Alexa.ThermostatController.thermostatMode':
      if (t) into.mode = t.toLowerCase();
      break;
    // A contact sensor reads as a door: detected = closed.
    case 'Alexa.ContactSensor.detectionState':
      into.door = t === 'DETECTED' ? 'closed' : t === 'NOT_DETECTED' ? 'open' : undefined;
      break;
  }
}

const cleaned = (s: DeviceState): DeviceState | null => {
  const out: DeviceState = {};
  for (const [k, v] of Object.entries(s)) if (v !== undefined) (out as Record<string, unknown>)[k] = v;
  return Object.keys(out).length > 0 ? out : null;
};

/**
 * Each device's reading from a `phoenix/state` answer: `{deviceStates: [{entity, capabilityStates}], errors: [{entity, code}]}`. `capabilityStates`
 * are JSON strings. Keyed by the id the answer names (an appliance id or the entity id).
 */
export function parseStates(body: unknown, scale?: TempScale): Map<string, AlexaReading> {
  const out = new Map<string, AlexaReading>();
  const b = obj(body);
  for (const raw of arr(b.deviceStates)) {
    const d = obj(raw);
    const id = text(obj(d.entity).entityId);
    if (!id) continue;
    const values: DeviceState = {};
    let unreachable = false;
    for (const c of arr(d.capabilityStates)) {
      let s: Obj;
      if (typeof c === 'string') {
        try {
          s = obj(JSON.parse(c));
        } catch {
          s = {};
        }
      } else s = obj(c);
      if (text(s.namespace) === 'Alexa.EndpointHealth' && text(obj(s.value).value) === 'UNREACHABLE') unreachable = true;
      read(s, values, scale);
    }
    out.set(id, { state: cleaned(values), unreachable });
  }
  for (const raw of arr(b.errors)) {
    const e = obj(raw);
    const id = text(obj(e.entity).entityId);
    if (!id || out.has(id)) continue;
    // Echo speakers, Fire TVs and the like have no smart-home state to report.
    out.set(id, text(e.code) === 'ENDPOINT_UNREACHABLE' ? { state: null, unreachable: true } : { state: null });
  }
  return out;
}

/** One request per appliance id (state is asked by appliance, not entity), naming the properties wanted. */
export function stateRequests(devices: AlexaDevice[]): unknown[] {
  return devices
    .filter((d) => d.properties.length > 0)
    .flatMap((d) =>
      d.applianceIds.map((id) => ({
        entityId: id,
        entityType: 'APPLIANCE',
        properties: d.properties.map((p) => ({ namespace: p.namespace, name: p.name, ...(p.instance ? { instance: p.instance } : {}) })),
      })),
    );
}

/** The reason Amazon gives when it refuses a state request as a whole (`{message}`), or undefined. */
export function stateRefusal(body: unknown): string | undefined {
  const b = obj(body);
  if (arr(b.deviceStates).length > 0) return undefined;
  return text(b.message)?.slice(0, 300);
}

/** A device's reading: the first of its appliance ids (or its entity id) that reports a state. */
export function readingOf(device: AlexaDevice, readings: Map<string, AlexaReading>): AlexaReading {
  const found = [...device.applianceIds, device.entityId].map((id) => readings.get(id)).filter((r): r is AlexaReading => !!r);
  const withState = found.find((r) => r.state !== null);
  if (withState) return withState;
  return found.length > 0 && found.every((r) => r.unreachable) ? { state: null, unreachable: true } : { state: null };
}

/** Whether to ask a device for its state. Cameras only report motion (not shown), and asking them is what times out, blanking the devices batched with them. */
export const shouldAskState = (d: AlexaDevice): boolean => d.type !== 'ALEXA_VOICE_ENABLED' && d.type !== 'CAMERA' && d.properties.length > 0;

/** Batches of at most `size`, each from one maker where it can be, so a slow brand holds up only its own devices. */
export function stateBatches(devices: AlexaDevice[], size: number): AlexaDevice[][] {
  const sorted = [...devices].sort((a, b) => (a.manufacturer ?? '').localeCompare(b.manufacturer ?? ''));
  const out: AlexaDevice[][] = [];
  for (const d of sorted) {
    const last = out[out.length - 1];
    if (last && last.length < size && (last[0].manufacturer ?? '') === (d.manufacturer ?? '')) last.push(d);
    else out.push([d]);
  }
  // Small leftovers of different makers share batches, so a home of many brands isn't one request each.
  const full = out.filter((b) => b.length === size);
  const rest = out.filter((b) => b.length < size).flat();
  for (let i = 0; i < rest.length; i += size) full.push(rest.slice(i, i + size));
  return full;
}

// ---- Commands ----

/** The control action Alexa lists for each command (Jarvis's `NEEDS`); a device must support it. Garage doors, cameras and the printer can't be sent. */
function needed(c: DeviceCommand): string | null {
  switch (c.type) {
    case 'turn_on':
      return 'turnOn';
    case 'turn_off':
      return 'turnOff';
    case 'set_brightness':
      return 'setBrightness';
    case 'set_percentage':
      return 'setPercentage';
    case 'set_temperature':
      return 'setTargetTemperature';
    case 'set_mode':
      return 'setThermostatMode';
    case 'set_color':
      return 'setColor';
    case 'set_white':
      return 'setColorTemperature';
    case 'lock':
    case 'unlock':
      return 'lockAction';
    default:
      return null; // Jarvis can't open or close a garage door through Alexa, and neither can Edith
  }
}

/** What the state properties a device reports say it can be told, for a device whose list of control actions is empty. */
const ACTIONS_BY_NAMESPACE: Record<string, string[]> = {
  'Alexa.PowerController': ['turnOn', 'turnOff'],
  'Alexa.BrightnessController': ['setBrightness'],
  'Alexa.PercentageController': ['setPercentage'],
  'Alexa.ColorController': ['setColor'],
  'Alexa.ColorTemperatureController': ['setColorTemperature'],
  'Alexa.ThermostatController': ['setTargetTemperature', 'setThermostatMode'],
  'Alexa.LockController': ['lockAction'],
};
/** Kinds Edith lists but never controls through Alexa: Jarvis can't either. */
const NOT_CONTROLLED = new Set(['GARAGE_DOOR', 'CAMERA', 'SECURITY_PANEL', 'ALEXA_VOICE_ENABLED']);

/** The control actions `d` supports: the ones Alexa lists, else (when it lists none) what its state properties imply. */
function supportedActions(d: AlexaDevice): Set<string> {
  if (NOT_CONTROLLED.has(d.type)) return new Set();
  const listed = new Set(d.actions.map((a) => (a === 'setLockState' ? 'lockAction' : a)));
  if (listed.size > 0) return listed;
  return new Set(d.properties.flatMap((p) => ACTIONS_BY_NAMESPACE[p.namespace] ?? []));
}

/** The thermostat modes Alexa takes when a device lists none (Jarvis's `THERMOSTAT_MODES`). */
export const DEFAULT_MODES = ['heat', 'cool', 'auto', 'eco', 'off'];

/** What `d` can be told to do, as Edith's capabilities. */
export function capabilitiesOf(d: AlexaDevice): Capability[] {
  const a = supportedActions(d);
  const out: Capability[] = [];
  if (a.has('turnOn') || a.has('turnOff')) out.push('on_off');
  if (a.has('setBrightness')) out.push('brightness');
  if (a.has('setPercentage')) out.push('percentage');
  if (a.has('setColor')) out.push('color');
  if (a.has('setColorTemperature')) out.push('white');
  if (a.has('setTargetTemperature') || a.has('setThermostatMode')) out.push('thermostat');
  if (a.has('lockAction')) out.push('lock_unlock');
  return out;
}

/** The modes to offer a thermostat that can change mode. */
export const modesOf = (d: AlexaDevice): string[] => (supportedActions(d).has('setThermostatMode') ? (d.modes.length ? d.modes : DEFAULT_MODES) : []);

/** Alexa's category code as Edith's device type. */
export function typeOf(code: string): DeviceType {
  switch (code) {
    case 'LIGHT':
      return 'light';
    case 'SMARTPLUG':
      return 'plug';
    case 'SWITCH':
      return 'switch';
    case 'FAN':
      return 'fan';
    case 'THERMOSTAT':
      return 'thermostat';
    case 'SMARTLOCK':
      return 'lock';
    case 'GARAGE_DOOR':
      return 'garage_door';
    case 'CAMERA':
      return 'camera';
    case 'DOORBELL':
      return 'doorbell';
    case 'CONTACT_SENSOR':
    case 'MOTION_SENSOR':
    case 'TEMPERATURE_SENSOR':
      return 'sensor';
    case 'SPEAKER':
      return 'speaker';
    default:
      return 'other';
  }
}

/**
 * The parameters Alexa's control request takes for `command` (as the ioBroker Alexa adapter sends them, and Jarvis's `controlParameters`), or null when
 * `d` can't be sent it. Brightness and speed go as fractions: 50 % is 0.5.
 */
export function controlParameters(command: DeviceCommand, d: AlexaDevice, scale: TempScale): Record<string, unknown> | null {
  const action = needed(command);
  if (!action || !supportedActions(d).has(action)) return null;
  const name = (s: string) => s.trim().toLowerCase().replace(/\s+/g, '_');
  switch (command.type) {
    case 'turn_on':
      return { action: 'turnOn' };
    case 'turn_off':
      return { action: 'turnOff' };
    case 'set_brightness':
      return { action: 'setBrightness', brightness: command.value / 100 };
    case 'set_percentage':
      return { action: 'setPercentage', percentage: command.value / 100 };
    case 'set_temperature':
      return { action: 'setTargetTemperature', 'targetTemperature.value': command.value, 'targetTemperature.scale': scale };
    case 'set_mode':
      return { action: 'setThermostatMode', 'thermostatMode.value': command.value.trim().toUpperCase() };
    case 'set_color':
      return { action: 'setColor', colorName: name(command.value) };
    case 'set_white':
      return { action: 'setColorTemperature', colorTemperatureName: name(command.value) };
    case 'lock':
      return { action: 'lockAction', 'targetLockState.value': 'LOCKED' };
    case 'unlock':
      return { action: 'lockAction', 'targetLockState.value': 'UNLOCKED' };
    default:
      return null;
  }
}

/**
 * Why Alexa's answer to a control request says it didn't work, in words, or undefined when it did (Jarvis's `controlProblem`):
 * `{controlResponses: [{code}], errors: [{code, message}]}`. Amazon answers 200 for a refusal, so this is the only way to know.
 */
export function controlProblem(body: unknown): string | undefined {
  const b = obj(body);
  const errors = arr(b.errors).map(obj);
  if (errors.length > 0) {
    const code = text(errors[0].code) ?? 'ERROR';
    if (code === 'ENDPOINT_UNREACHABLE') return "Alexa couldn't reach the device.";
    const message = text(errors[0].message);
    return `Alexa refused: ${code}${message ? ` (${message})` : ''}.`;
  }
  const failed = arr(b.controlResponses)
    .map(obj)
    .find((r) => {
      const c = text(r.code);
      return c !== undefined && c !== 'SUCCESS';
    });
  return failed ? `Alexa answered ${text(failed.code)}.` : undefined;
}
