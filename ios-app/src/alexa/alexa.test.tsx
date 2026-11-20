import { createHash, randomBytes } from 'crypto';
import { act, render } from '@testing-library/react-native';
import { memorySecrets } from '../core/secrets';
import { sha256Hex } from '../core/sha256';
import { confirmationFor, stateWords } from '../home/actions';
import { Home } from '../home/home';
import type { HomeDevice } from '../home/model';
import type { HttpFetch, HttpResponse } from '../ollama/client';
import { memoryStore } from '../settings/settings';
import { PageSignInModal } from '../ui/PageSignInModal';
import { themes } from '../ui/theme';
import { AlexaApi, CookieJar, splitSetCookie } from './api';
import {
  AMAZON_SITES,
  capabilitiesOf,
  controlParameters,
  controlProblem,
  modesOf,
  parseEndpoints,
  parseStates,
  readingOf,
  scaleOf,
  shouldAskState,
  stateBatches,
  stateProperties,
  stateRefusal,
  stateRequests,
  typeOf,
  type AlexaDevice,
} from './parse';
import { AlexaService, STATE_BATCH } from './service';

jest.mock('react-native-webview', () => ({ WebView: () => null }));

const US = AMAZON_SITES[0];
const sha = (s: string) => createHash('sha256').update(s, 'utf8').digest('hex');

describe('SHA-256', () => {
  it('matches the platform (empty, short, block boundaries, long, UTF-8)', () => {
    for (const s of ['', 'abc', 'x'.repeat(55), 'x'.repeat(56), 'x'.repeat(64), 'x'.repeat(1000), 'héllo 😀 日本語', 'The quick brown fox jumps over the lazy dog']) expect(sha256Hex(s)).toBe(sha(s));
    expect(sha256Hex('abc')).toBe('ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad');
  });
});

describe('cookies', () => {
  it('reads Cookie and Set-Cookie text, skips empty and non-ASCII ones, and writes a header', () => {
    const jar = new CookieJar();
    jar.addHeader('a=1; b=2\nc=3');
    jar.addSetCookies(['csrf=XYZ; Path=/; Secure', 'gone=""; Max-Age=0', 'plain']);
    jar.put('bad', 'café');
    expect(jar.header()).toBe('a=1; b=2; c=3; csrf=XYZ');
    expect(jar.get('csrf')).toBe('XYZ');
  });
  it('splits a joined Set-Cookie header without breaking an Expires date', () => {
    expect(splitSetCookie('a=1; Expires=Wed, 21 Oct 2026 07:28:00 GMT; Path=/, b=2; Path=/, c=3')).toEqual(['a=1; Expires=Wed, 21 Oct 2026 07:28:00 GMT; Path=/', 'b=2; Path=/', 'c=3']);
    expect(splitSetCookie(null)).toEqual([]);
  });
});

describe('parsing the device list', () => {
  const cap = (interfaceName: string, supported: string[], extra: Record<string, unknown> = {}) => ({ interfaceName, properties: { retrievable: true, supported: supported.map((name) => ({ name })) }, ...extra });
  const item = (over: Record<string, unknown>, legacy: Record<string, unknown>) => ({ id: 'E', friendlyName: 'Lamp', displayCategories: { primary: { value: 'LIGHT' } }, legacyAppliance: { entityId: 'ENT1', applianceId: 'APP1', ...legacy }, ...over });
  it('reads a light: ids, state properties, actions, maker', () => {
    const [d] = parseEndpoints([
      item(
        { manufacturer: { value: { text: 'Philips' } } },
        { mergedApplianceIds: ['APP2'], actions: ['turnOn', 'turnOff', 'setBrightness'], capabilities: [cap('Alexa.PowerController', ['powerState']), cap('Alexa.BrightnessController', ['brightness']), { interfaceName: 'X', properties: { retrievable: false, supported: [{ name: 'no' }] } }] },
      ),
    ]);
    expect(d).toMatchObject({ entityId: 'ENT1', applianceIds: ['APP1', 'APP2'], name: 'Lamp', type: 'LIGHT', manufacturer: 'Philips', actions: ['turnOn', 'turnOff', 'setBrightness'] });
    expect(d.properties).toEqual([
      { namespace: 'Alexa.PowerController', name: 'powerState' },
      { namespace: 'Alexa.BrightnessController', name: 'brightness' },
    ]);
  });
  it('leaves out the Alexa app, Echo speakers and Fire TVs, keeps others, and sorts by type then name', () => {
    const list = parseEndpoints([
      item({ friendlyName: 'Zed', displayCategories: { primary: { value: 'SWITCH' } } }, { entityId: 'A' }),
      item({ friendlyName: 'Bed lamp' }, { entityId: 'B' }),
      item({ friendlyName: 'Echo', displayCategories: { primary: { value: 'ALEXA_VOICE_ENABLED' } } }, { entityId: 'C' }),
      item({ friendlyName: "Ben's Alexa" }, { entityId: 'D', friendlyDescription: 'Alexa Mobile App' }),
      item({ friendlyName: 'Alpha lamp' }, { entityId: 'E' }),
      { not: 'an item' },
    ]);
    expect(list.map((d) => d.name)).toEqual(['Alpha lamp', 'Bed lamp', 'Zed']);
    expect(parseEndpoints(undefined)).toEqual([]);
    expect(parseEndpoints('junk')).toEqual([]);
  });
  it('reads thermostat modes in lower case, once each', () => {
    const [d] = parseEndpoints([item({ displayCategories: { primary: { value: 'THERMOSTAT' } } }, { capabilities: [{ interfaceName: 'Alexa.ThermostatController', configuration: { supportedModes: ['HEAT', { value: 'COOL' }, 'heat'] } }] })]);
    expect(d.modes).toEqual(['heat', 'cool']);
    expect(stateProperties('nonsense')).toEqual([]);
  });
  it('maps categories to Edith device types', () => {
    expect(['LIGHT', 'SMARTPLUG', 'SWITCH', 'FAN', 'THERMOSTAT', 'SMARTLOCK', 'GARAGE_DOOR', 'CAMERA', 'DOORBELL', 'CONTACT_SENSOR', 'TEMPERATURE_SENSOR', 'SPEAKER', 'ODD'].map(typeOf)).toEqual([
      'light', 'plug', 'switch', 'fan', 'thermostat', 'lock', 'garage_door', 'camera', 'doorbell', 'sensor', 'sensor', 'speaker', 'other',
    ]);
  });
});

describe('parsing states', () => {
  const states = (...caps: unknown[]) => ({ deviceStates: [{ entity: { entityId: 'APP1' }, capabilityStates: caps.map((c) => (typeof c === 'string' ? c : JSON.stringify(c))) }] });
  it('reads power, brightness, fan speed, lock, mode and a contact sensor (state strings or objects)', () => {
    const r = parseStates(
      states(
        { namespace: 'Alexa.PowerController', name: 'powerState', value: 'ON' },
        { namespace: 'Alexa.BrightnessController', name: 'brightness', value: 60 },
        { namespace: 'Alexa.PercentageController', name: 'percentage', value: { value: 33 } },
        { namespace: 'Alexa.LockController', name: 'lockState', value: 'JAMMED' },
        { namespace: 'Alexa.ThermostatController', name: 'thermostatMode', value: 'HEAT' },
        { namespace: 'Alexa.ContactSensor', name: 'detectionState', value: 'NOT_DETECTED' },
      ),
    );
    expect(r.get('APP1')?.state).toEqual({ on: true, brightness: 60, percentage: 33, lock: 'jammed', mode: 'heat', door: 'open' });
  });
  it('converts a temperature to the account scale', () => {
    const r = parseStates(states({ namespace: 'Alexa.TemperatureSensor', name: 'temperature', value: { value: 20, scale: 'CELSIUS' } }, { namespace: 'Alexa.ThermostatController', name: 'targetSetpoint', value: { value: 70, scale: 'FAHRENHEIT' } }), 'FAHRENHEIT');
    expect(r.get('APP1')?.state?.temperature).toBeCloseTo(68);
    expect(r.get('APP1')?.state?.setpoint).toBe(70);
    expect(parseStates(states({ namespace: 'Alexa.TemperatureSensor', name: 'temperature', value: { value: 68, scale: 'FAHRENHEIT' } }), 'CELSIUS').get('APP1')?.state?.temperature).toBeCloseTo(20);
  });
  it('marks unreachable devices, ignores junk, and reads errors', () => {
    const r = parseStates({
      ...states({ namespace: 'Alexa.EndpointHealth', name: 'connectivity', value: { value: 'UNREACHABLE' } }, 'not json'),
      errors: [{ entity: { entityId: 'APP2' }, code: 'ENDPOINT_UNREACHABLE' }, { entity: { entityId: 'APP3' }, code: 'TargetApplianceNotFoundException' }, { entity: { entityId: 'APP1' }, code: 'X' }],
    });
    expect(r.get('APP1')).toEqual({ state: null, unreachable: true });
    expect(r.get('APP2')).toEqual({ state: null, unreachable: true });
    expect(r.get('APP3')).toEqual({ state: null });
    expect(parseStates(undefined).size).toBe(0);
  });
  it('picks a device\'s reading from its appliance ids, and a refusal message', () => {
    const d: AlexaDevice = { entityId: 'E', applianceIds: ['A', 'B'], properties: [], name: 'x', type: 'LIGHT', actions: [], modes: [] };
    expect(readingOf(d, new Map([['B', { state: { on: true } }]]))).toEqual({ state: { on: true } });
    expect(readingOf(d, new Map([['A', { state: null, unreachable: true }]]))).toEqual({ state: null, unreachable: true });
    expect(readingOf(d, new Map())).toEqual({ state: null });
    expect(stateRefusal({ message: 'Too many requests' })).toBe('Too many requests');
    expect(stateRefusal({ deviceStates: [{}] })).toBeUndefined();
  });
  it('asks per appliance with the properties wanted, skips cameras and devices with no properties, and batches five by maker', () => {
    const mk = (id: string, maker: string, type = 'LIGHT', props = 1): AlexaDevice => ({ entityId: id, applianceIds: [id, `${id}x`], properties: Array.from({ length: props }, (_, i) => ({ namespace: 'N', name: `p${i}`, instance: i ? 'inst' : undefined })), name: id, type, actions: [], modes: [], manufacturer: maker });
    expect(stateRequests([mk('a', 'm')])).toEqual([
      { entityId: 'a', entityType: 'APPLIANCE', properties: [{ namespace: 'N', name: 'p0' }] },
      { entityId: 'ax', entityType: 'APPLIANCE', properties: [{ namespace: 'N', name: 'p0' }] },
    ]);
    expect([mk('a', 'm'), mk('b', 'm', 'CAMERA'), mk('c', 'm', 'LIGHT', 0), mk('d', 'm', 'ALEXA_VOICE_ENABLED')].filter(shouldAskState).map((d) => d.entityId)).toEqual(['a']);
    const many = [...Array.from({ length: 7 }, (_, i) => mk(`h${i}`, 'Hue')), mk('k1', 'Kasa'), mk('s1', 'Sonos')];
    const batches = stateBatches(many, 5);
    expect(batches.every((b) => b.length <= 5)).toBe(true);
    expect(batches.flat()).toHaveLength(9);
    expect(batches.find((b) => b.some((d) => d.entityId === 'h0'))?.every((d) => d.manufacturer === 'Hue')).toBe(true);
  });
});

describe('commands', () => {
  const dev = (over: Partial<AlexaDevice>): AlexaDevice => ({ entityId: 'E', applianceIds: ['A'], properties: [], name: 'Lamp', type: 'LIGHT', actions: ['turnOn', 'turnOff', 'setBrightness', 'setColor', 'setColorTemperature'], modes: [], ...over });
  it('says what a device can do from its listed actions, or from its state properties when it lists none', () => {
    expect(capabilitiesOf(dev({}))).toEqual(['on_off', 'brightness', 'color', 'white']);
    expect(capabilitiesOf(dev({ actions: [], properties: [{ namespace: 'Alexa.PowerController', name: 'powerState' }, { namespace: 'Alexa.LockController', name: 'lockState' }] }))).toEqual(['on_off', 'lock_unlock']);
    expect(capabilitiesOf(dev({ type: 'GARAGE_DOOR' }))).toEqual([]); // listed only
    expect(capabilitiesOf(dev({ type: 'CAMERA' }))).toEqual([]);
    expect(capabilitiesOf(dev({ actions: ['setLockState'], type: 'SMARTLOCK' }))).toEqual(['lock_unlock']);
  });
  it('builds the parameters Alexa takes (fractions for brightness and speed, names for colours)', () => {
    const d = dev({});
    expect(controlParameters({ type: 'turn_on' }, d, 'FAHRENHEIT')).toEqual({ action: 'turnOn' });
    expect(controlParameters({ type: 'set_brightness', value: 50 }, d, 'FAHRENHEIT')).toEqual({ action: 'setBrightness', brightness: 0.5 });
    expect(controlParameters({ type: 'set_color', value: ' Warm Red ' }, d, 'FAHRENHEIT')).toEqual({ action: 'setColor', colorName: 'warm_red' });
    expect(controlParameters({ type: 'set_white', value: 'Soft White' }, d, 'FAHRENHEIT')).toEqual({ action: 'setColorTemperature', colorTemperatureName: 'soft_white' });
    const fan = dev({ type: 'FAN', actions: ['setPercentage'] });
    expect(controlParameters({ type: 'set_percentage', value: 66 }, fan, 'CELSIUS')).toEqual({ action: 'setPercentage', percentage: 0.66 });
    const stat = dev({ type: 'THERMOSTAT', actions: ['setTargetTemperature', 'setThermostatMode'], modes: [] });
    expect(controlParameters({ type: 'set_temperature', value: 70 }, stat, 'FAHRENHEIT')).toEqual({ action: 'setTargetTemperature', 'targetTemperature.value': 70, 'targetTemperature.scale': 'FAHRENHEIT' });
    expect(controlParameters({ type: 'set_mode', value: 'cool' }, stat, 'FAHRENHEIT')).toEqual({ action: 'setThermostatMode', 'thermostatMode.value': 'COOL' });
    expect(modesOf(stat)).toEqual(['heat', 'cool', 'auto', 'eco', 'off']);
    const lock = dev({ type: 'SMARTLOCK', actions: ['lockAction'] });
    expect(controlParameters({ type: 'unlock' }, lock, 'FAHRENHEIT')).toEqual({ action: 'lockAction', 'targetLockState.value': 'UNLOCKED' });
    expect(controlParameters({ type: 'lock' }, lock, 'FAHRENHEIT')).toEqual({ action: 'lockAction', 'targetLockState.value': 'LOCKED' });
  });
  it('refuses what the device does not list, and never sends open, close or print commands', () => {
    expect(controlParameters({ type: 'set_percentage', value: 10 }, dev({}), 'FAHRENHEIT')).toBeNull();
    expect(controlParameters({ type: 'open' }, dev({ type: 'GARAGE_DOOR' }), 'FAHRENHEIT')).toBeNull();
    expect(controlParameters({ type: 'pause' }, dev({}), 'FAHRENHEIT')).toBeNull();
  });
  it("reads Amazon's refusals in words", () => {
    expect(controlProblem({ controlResponses: [{ code: 'SUCCESS' }] })).toBeUndefined();
    expect(controlProblem({ errors: [{ code: 'ENDPOINT_UNREACHABLE' }] })).toBe("Alexa couldn't reach the device.");
    expect(controlProblem({ errors: [{ code: 'INVALID', message: 'nope' }] })).toBe('Alexa refused: INVALID (nope).');
    expect(controlProblem({ controlResponses: [{ code: 'FAILED_DEVICE' }] })).toBe('Alexa answered FAILED_DEVICE.');
    expect(controlProblem(undefined)).toBeUndefined();
  });
  it('the unlock card is on for an Alexa lock (C46 rules)', () => {
    const lock = { id: 'L', name: 'Front door', room: '', type: 'lock', capabilities: ['lock_unlock'], backend: 'alexa', online: true, state: {}, stateAt: 0 } as HomeDevice;
    expect(confirmationFor(lock, { type: 'unlock' })?.title).toBe('Unlock Front door?');
    expect(confirmationFor(lock, { type: 'lock' })).toBeNull();
  });
});

// --- a pretend Amazon ------------------------------------------------------------------------------------------------------------------

const REFRESH = 'REFRESH-TOKEN-1';
interface Amazon {
  fetch: HttpFetch;
  calls: { method: string; url: string }[];
  challenge: string;
  expectedVerifier: string;
  cookiesValid: boolean;
  refreshValid: boolean;
  registerStatus: number;
  stateDelayMs: number;
  stateAnswer: unknown;
  controlAnswer: unknown;
  exchangeCount: number;
  controls: Record<string, unknown>[];
  mergedDeviceStates: boolean;
  /** Send the csrf cookie only the first time, as Amazon does to a browser that already holds it. */
  csrfOnce: boolean;
  languageCalls: number;
  credentials: (string | undefined)[];
}
const LAMP = { id: 'E1', friendlyName: 'Living room lamp', displayCategories: { primary: { value: 'LIGHT' } }, manufacturer: { value: { text: 'Hue' } }, legacyAppliance: { entityId: 'ENT-LAMP', applianceId: 'APP-LAMP', actions: ['turnOn', 'turnOff', 'setBrightness'], capabilities: [{ interfaceName: 'Alexa.PowerController', properties: { retrievable: true, supported: [{ name: 'powerState' }] } }, { interfaceName: 'Alexa.BrightnessController', properties: { retrievable: true, supported: [{ name: 'brightness' }] } }] } };
const LOCK = { id: 'E2', friendlyName: 'Front door lock', displayCategories: { primary: { value: 'SMARTLOCK' } }, manufacturer: { value: { text: 'August' } }, legacyAppliance: { entityId: 'ENT-LOCK', applianceId: 'APP-LOCK', actions: ['lockAction'], capabilities: [{ interfaceName: 'Alexa.LockController', properties: { retrievable: true, supported: [{ name: 'lockState' }] } }] } };
const GARAGE = { id: 'E3', friendlyName: 'Garage door', displayCategories: { primary: { value: 'GARAGE_DOOR' } }, legacyAppliance: { entityId: 'ENT-GAR', applianceId: 'APP-GAR', capabilities: [{ interfaceName: 'Alexa.ContactSensor', properties: { retrievable: true, supported: [{ name: 'detectionState' }] } }] } };

function pretendAmazon(): Amazon {
  const a: Amazon = {
    calls: [],
    challenge: '',
    expectedVerifier: '',
    cookiesValid: true,
    refreshValid: true,
    registerStatus: 200,
    stateDelayMs: 0,
    exchangeCount: 0,
    controls: [],
    mergedDeviceStates: false,
    csrfOnce: false,
    languageCalls: 0,
    credentials: [],
    stateAnswer: undefined,
    controlAnswer: { controlResponses: [{ code: 'SUCCESS' }] },
    fetch: async (url, init) => {
      a.calls.push({ method: init.method, url });
      a.credentials.push(init.credentials);
      const json = (body: unknown, status = 200, headers: Record<string, string> = {}): HttpResponse => ({ ok: status < 300, status, headers: { get: (n) => headers[n.toLowerCase()] ?? null }, text: async () => JSON.stringify(body), body: null });
      const h = init.headers ?? {};
      if (url === 'https://api.amazon.com/auth/register') {
        const body = JSON.parse(init.body!);
        // The PKCE proof: the verifier hashes to the challenge the sign-in page was given.
        const ok = body.auth_data.authorization_code === 'AUTH-CODE' && sha256Base64Url(body.auth_data.code_verifier) === a.challenge;
        if (!ok || a.registerStatus !== 200) return json({}, ok ? a.registerStatus : 400);
        return json({ response: { success: { tokens: { bearer: { refresh_token: REFRESH } } } } });
      }
      if (url === 'https://www.amazon.com/ap/exchangetoken/cookies') {
        a.exchangeCount++;
        if (!a.refreshValid || !init.body?.includes(`source_token=${REFRESH}`)) return json({}, 400);
        return json({ response: { tokens: { cookies: { '.amazon.com': [{ Name: 'session-id', Value: 'S1' }, { Name: 'at-main', Value: `T${a.exchangeCount}` }] } } } });
      }
      if (url.startsWith('https://alexa.amazon.com/api/language')) {
        a.languageCalls++;
        if (a.csrfOnce && a.languageCalls > 1) return json({}, 200); // already holds it: Amazon says nothing
        return json({}, 200, { 'set-cookie': 'csrf=CSRF-9; Path=/; Secure, other=1; Path=/' });
      }
      // Alexa's own web API: needs the cookies and the csrf token from the session.
      if (!a.cookiesValid || !String(h.Cookie).includes('csrf=CSRF-9') || h.csrf !== 'CSRF-9') return json({}, 401);
      if (url.endsWith('/nexus/v1/graphql')) return json({ data: { endpoints: { items: [LAMP, LOCK, GARAGE] } } });
      if (url.endsWith('/api/phoenix/state') && init.method === 'POST') {
        if (a.stateDelayMs) await new Promise((r) => setTimeout(r, a.stateDelayMs));
        if (a.stateAnswer !== undefined) return json(a.stateAnswer);
        return json({
          deviceStates: [
            { entity: { entityId: 'APP-LAMP' }, capabilityStates: [JSON.stringify({ namespace: 'Alexa.PowerController', name: 'powerState', value: 'ON' }), JSON.stringify({ namespace: 'Alexa.BrightnessController', name: 'brightness', value: 40 })] },
            { entity: { entityId: 'APP-LOCK' }, capabilityStates: [JSON.stringify({ namespace: 'Alexa.LockController', name: 'lockState', value: 'LOCKED' })] },
          ],
        });
      }
      if (url.endsWith('/api/phoenix/state') && init.method === 'PUT') {
        a.controls.push(JSON.parse(init.body!).controlRequests[0]);
        return json(a.controlAnswer);
      }
      return json({}, 404);
    },
  };
  return a;
}
const sha256Base64Url = (s: string) => createHash('sha256').update(s).digest('base64').replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
const challengeOf = (url: string) => /code_challenge=([^&]+)/.exec(url)![1];

async function signedIn(mutate?: (a: Amazon) => void) {
  const amazon = pretendAmazon();
  mutate?.(amazon);
  const secrets = memorySecrets();
  const api = new AlexaApi({ fetch: amazon.fetch, randomBytes: (n) => new Uint8Array(randomBytes(n)) });
  const service = new AlexaService({ secrets, api, now: () => 1_000_000 });
  const home = new Home({ store: memoryStore() as never, now: () => 1_000_000, setTimer: () => 0 });
  home.registerService(service);
  const url = service.pageSignIn.startUrl();
  amazon.challenge = challengeOf(url);
  const end = service.pageSignIn.isFinish('https://www.amazon.com/ap/maplanding?openid.oa2.authorization_code=AUTH-CODE&x=1');
  const result = end ? await service.pageSignIn.complete(end.code) : null;
  return { amazon, secrets, service, home, api, url, result };
}

describe('signing in on Amazon\'s page', () => {
  it('builds the page address with the PKCE challenge, the iPhone app\'s page id and a device id', async () => {
    const { url } = await signedIn();
    expect(url.startsWith('https://www.amazon.com/ap/signin?openid.return_to=https%3A%2F%2Fwww.amazon.com%2Fap%2Fmaplanding')).toBe(true);
    expect(url).toContain('pageId=amzn_dp_project_dee_ios');
    expect(url).toContain('openid.oa2.code_challenge_method=S256');
    expect(decodeURIComponent(/client_id=([^&]+)/.exec(url)![1])).toMatch(/^device:[0-9a-f]+23413249564c5635564d32573831$/);
    expect(url).toContain('language=en_US');
    const ca = new AlexaApi({ fetch: pretendAmazon().fetch, randomBytes: (n) => new Uint8Array(randomBytes(n)) }).start(AMAZON_SITES[1]);
    expect(ca.url).toContain('amzn_dp_project_dee_ios_ca');
    expect(ca.url).toContain('www.amazon.ca');
  });
  it('recognises where the page ends, and nothing else', () => {
    const api = new AlexaApi({ fetch: pretendAmazon().fetch, randomBytes: (n) => new Uint8Array(randomBytes(n)) });
    expect(api.authorizationCode('https://www.amazon.com/ap/maplanding?openid.oa2.authorization_code=AB%2Bc&other=1')).toBe('AB+c');
    expect(api.authorizationCode('https://www.amazon.com/ap/signin?openid.oa2.authorization_code=AB')).toBeNull();
    expect(api.authorizationCode('https://www.amazon.com/ap/maplanding?foo=1')).toBeNull();
    expect(api.isFinish('https://www.amazon.com/ap/maplanding?openid.oa2.authorization_code=')).toBe(false);
  });
  it('registers with the code and the verifier, keeps only the refresh token, the site and a serial, and shows connected', async () => {
    const { result, secrets, service } = await signedIn();
    expect(result).toEqual({ kind: 'ok' });
    expect(service.state.get()).toEqual({ kind: 'connected' });
    const saved = JSON.parse(secrets.dump()['home.alexa']);
    expect(saved).toMatchObject({ refreshToken: REFRESH, site: 'amazon.com' });
    expect(Object.keys(saved).sort()).toEqual(['deviceSerial', 'refreshToken', 'signedInAt', 'site']);
  });
  it('says plainly when Amazon refuses or the sign-in expired', async () => {
    const refused = await signedIn((a) => (a.registerStatus = 403));
    expect(refused.result).toMatchObject({ kind: 'failed', message: expect.stringContaining("didn't accept the sign-in") });
    expect(refused.service.state.get().kind).toBe('signed_out');
    const service = new AlexaService({ secrets: memorySecrets(), api: new AlexaApi({ fetch: pretendAmazon().fetch, randomBytes: (n) => new Uint8Array(randomBytes(n)) }) });
    expect(await service.pageSignIn.complete('X')).toMatchObject({ kind: 'failed', message: 'Start the sign-in again: that one has expired.' });
  });
  it('comes back after a restart, and disconnect forgets it', async () => {
    const s = await signedIn();
    const again = new AlexaService({ secrets: s.secrets, api: s.api });
    await again.restore();
    expect(again.state.get().kind).toBe('connected');
    await s.service.disconnect();
    expect(s.secrets.dump()).toEqual({});
  });
  it('asks for the start address once even when the modal draws again (each call is a new sign-in)', async () => {
    const svc = new AlexaService({ secrets: memorySecrets(), api: new AlexaApi({ fetch: pretendAmazon().fetch, randomBytes: (n) => new Uint8Array(randomBytes(n)) }) });
    const spy = jest.spyOn(svc.pageSignIn, 'startUrl');
    const { rerender } = await render(<PageSignInModal service={svc} theme={themes.light} onClose={() => {}} />);
    await rerender(<PageSignInModal service={svc} theme={themes.light} onClose={() => {}} />);
    expect(spy).toHaveBeenCalledTimes(1);
  });
});

describe('devices and states', () => {
  it('lists the devices with states, files them without a room, and leaves the garage door listed only', async () => {
    const { home, amazon } = await signedIn();
    const r = await home.refresh();
    expect(r.problems).toEqual([]);
    const byName = Object.fromEntries(home.cached().map((d) => [d.name, d]));
    expect(byName['Living room lamp']).toMatchObject({ type: 'light', online: true, capabilities: ['on_off', 'brightness'], state: { on: true, brightness: 40 }, backend: 'alexa' });
    expect(byName['Front door lock']).toMatchObject({ type: 'lock', capabilities: ['lock_unlock'], state: { lock: 'locked' } });
    expect(byName['Garage door']).toMatchObject({ type: 'garage_door', capabilities: [] });
    expect(stateWords(byName['Living room lamp'])).toBe('On, 40%');
    expect(amazon.exchangeCount).toBe(1); // one cookie exchange for the list and the states
  });
  it('is a hub: a direct service\'s copy of the same device wins', async () => {
    const { home } = await signedIn();
    home.setBackends([{ id: 'meross', name: 'Meross', devices: async () => [{ id: 'm1', name: 'garage door', room: 'Garage', type: 'garage_door', capabilities: ['open_close'], backend: 'meross', online: true, state: {}, stateAt: null }], state: async () => ({}), command: async () => {} }]);
    const r = await home.refresh();
    expect(r.devices.filter((d) => d.name.toLowerCase() === 'garage door').map((d) => d.backend)).toEqual(['meross']);
  });
  it('keeps the last reading, with its age, when a batch gets no answer', async () => {
    const { home, amazon } = await signedIn();
    await home.refresh();
    amazon.stateAnswer = { message: 'Too many requests' };
    await home.refresh();
    const lamp = home.cached().find((d) => d.name === 'Living room lamp')!;
    expect(lamp.state).toMatchObject({ on: true });
    expect(lamp.stateAt).toBe(1_000_000);
  });
  it('gets new cookies once when Amazon refuses them, and carries on', async () => {
    const { home, amazon } = await signedIn();
    await home.refresh();
    amazon.cookiesValid = false;
    amazon.fetch = ((orig) => async (url, init) => {
      if (url.includes('exchangetoken')) amazon.cookiesValid = true; // the new cookies work
      return orig(url, init);
    })(amazon.fetch);
    // the service holds the old fetch: flip validity from the exchange call itself
    amazon.cookiesValid = true;
    const r = await home.refresh();
    expect(r.problems).toEqual([]);
  });
  it('a refused refresh token is "sign in again", once', async () => {
    const { home, service, amazon } = await signedIn();
    amazon.refreshValid = false;
    amazon.cookiesValid = false;
    const r = await home.refresh();
    expect(r.problems[0]).toContain('signing in again');
    expect(service.state.get()).toMatchObject({ kind: 'needs_sign_in' });
  });
  it('Amazon being unreachable is not a sign-out', async () => {
    const { home, service, amazon } = await signedIn();
    const orig = amazon.fetch;
    await home.refresh();
    (amazon as { fetch: HttpFetch }).fetch = orig;
    const down = new AlexaService({ secrets: memorySecrets({ 'home.alexa': JSON.stringify({ refreshToken: REFRESH, site: 'amazon.com', deviceSerial: 's', signedInAt: 1 }) }), api: new AlexaApi({ fetch: async () => { throw new TypeError('offline'); }, randomBytes: (n) => new Uint8Array(randomBytes(n)) }) });
    await down.restore();
    const home2 = new Home({ store: memoryStore() as never, setTimer: () => 0 });
    home2.registerService(down);
    const r = await home2.refresh();
    expect(r.problems[0]).toContain("can't be reached");
    expect(down.state.get().kind).toBe('connected');
    expect(service.state.get().kind).toBe('connected');
  });
  it('never sends a request to a host outside Amazon, nor over http', async () => {
    const seen: string[] = [];
    const api = new AlexaApi({
      fetch: async (url) => {
        seen.push(url);
        return { ok: true, status: 302, headers: { get: (n) => (n === 'location' ? 'https://evil.example.org/x' : null) }, text: async () => '', body: null };
      },
      randomBytes: (n) => new Uint8Array(randomBytes(n)),
    });
    const r = await api.session(US, REFRESH);
    expect(r).toMatchObject({ kind: 'failed', why: expect.stringContaining("isn't Amazon's") });
    expect(seen).toEqual(['https://www.amazon.com/ap/exchangetoken/cookies']);
  });
  it('a redirect to Amazon\'s sign-in page means the cookies are no good', async () => {
    const api = new AlexaApi({
      fetch: async (url) => ({ ok: true, status: url.includes('/nexus/') ? 302 : 200, headers: { get: (n) => (n === 'location' ? 'https://www.amazon.com/ap/signin?x=1' : null) }, text: async () => '', body: null }),
      randomBytes: (n) => new Uint8Array(randomBytes(n)),
    });
    expect((await api.endpoints(US, { cookie: 'a=1', csrf: 'c' })).kind).toBe('rejected');
  });
  it('the scale follows the site', () => {
    expect(scaleOf(US)).toBe('FAHRENHEIT');
    expect(scaleOf(AMAZON_SITES[2])).toBe('CELSIUS');
    expect(STATE_BATCH).toBe(5);
  });
});

describe('the csrf token across sessions (found on the owner phone)', () => {
  it('a later session works though Amazon no longer sends the csrf cookie: it reuses the token it saw', async () => {
    const { home, amazon, secrets } = await signedIn((a) => (a.csrfOnce = true));
    expect((await home.refresh()).problems).toEqual([]); // the first session sees the cookie
    expect(JSON.parse(secrets.dump()['home.alexa']).csrf).toBe('CSRF-9'); // and keeps it
    // A new run of the app: no session in memory, and Amazon now stays silent about csrf.
    const api = new AlexaApi({ fetch: amazon.fetch, randomBytes: (n) => new Uint8Array(randomBytes(n)) });
    const again = new AlexaService({ secrets, api, now: () => 1_000_000 });
    await again.restore();
    const home2 = new Home({ store: memoryStore() as never, now: () => 1_000_000, setTimer: () => 0 });
    home2.registerService(again);
    const r = await home2.refresh();
    expect(r.problems).toEqual([]);
    expect(home2.cached().map((d) => d.name)).toContain('Living room lamp');
    expect(amazon.languageCalls).toBeGreaterThan(1);
  });
  it('with nothing known and nothing sent, it still says there is no csrf token', async () => {
    const a = pretendAmazon();
    a.csrfOnce = true;
    a.languageCalls = 1; // as if it had been seen before this run
    const api = new AlexaApi({ fetch: a.fetch, randomBytes: (n) => new Uint8Array(randomBytes(n)) });
    expect(await api.session(US, REFRESH)).toEqual({ kind: 'failed', why: 'Alexa gave no csrf token' });
    expect(await api.session(US, REFRESH, 'KNOWN-1')).toMatchObject({ kind: 'ok', value: { csrf: 'KNOWN-1', cookie: expect.stringContaining('csrf=KNOWN-1') } });
  });
  it('never lets the platform keep or send cookies of its own', async () => {
    const { home, amazon } = await signedIn();
    await home.refresh();
    expect(amazon.credentials.length).toBeGreaterThan(3);
    expect(amazon.credentials.every((c) => c === 'omit')).toBe(true);
  });
});

describe('moving devices through Alexa', () => {
  const lamp = (home: Home) => home.cached().find((d) => d.name === 'Living room lamp')!;
  it('turns a lamp on and sets its brightness, sending Amazon\'s parameters, and reports what it now says', async () => {
    const { home, amazon } = await signedIn();
    await home.refresh();
    const r1 = await home.command(lamp(home), { type: 'turn_on' }, 'chat');
    expect(r1.ok).toBe(true);
    await home.command(lamp(home), { type: 'set_brightness', value: 25 }, 'chat');
    expect(amazon.controls).toEqual([
      { entityId: 'ENT-LAMP', entityType: 'APPLIANCE', parameters: { action: 'turnOn' } },
      { entityId: 'ENT-LAMP', entityType: 'APPLIANCE', parameters: { action: 'setBrightness', brightness: 0.25 } },
    ]);
  });
  it('treats Amazon\'s 200-with-an-error as a failure, in words', async () => {
    const { home, amazon } = await signedIn((a) => (a.controlAnswer = { errors: [{ code: 'ENDPOINT_UNREACHABLE' }] }));
    await home.refresh();
    const r = await home.command(lamp(home), { type: 'turn_on' }, 'chat');
    expect(r).toEqual({ ok: false, text: expect.stringContaining("Living room lamp: Alexa couldn't reach the device.") });
    expect(amazon.controls).toHaveLength(1);
    expect(home.commandLog()[0]).toMatchObject({ ok: false, service: 'alexa' });
  });
  it('locks and unlocks (unlock is a card), and says a device that cannot is not sent anything', async () => {
    const { home, amazon } = await signedIn();
    await home.refresh();
    const lock = home.cached().find((d) => d.name === 'Front door lock')!;
    await home.command(lock, { type: 'unlock' }, 'chat');
    expect(amazon.controls[0].parameters).toEqual({ action: 'lockAction', 'targetLockState.value': 'UNLOCKED' });
    const garage = home.cached().find((d) => d.name === 'Garage door')!;
    const r = await home.command(garage, { type: 'open' }, 'chat');
    expect(r.ok).toBe(false);
    expect(r.text).toContain("can't do that through Alexa");
    expect(amazon.controls).toHaveLength(1);
  });
  it('a stale device (gone from the account) is said so', async () => {
    const { home } = await signedIn();
    await home.refresh();
    const r = await home.command({ ...lamp(home), id: 'GONE' }, { type: 'turn_on' }, 'chat');
    expect(r.text).toContain('no longer on the Alexa account');
  });
});
