import { ChatEngine } from '../chat/engine';
import { PromptBuilder, dateTimeSection } from '../chat/promptBuilder';
import { ChatRepository } from '../data/chatRepository';
import { ModelCatalog } from '../ollama/catalog';
import { OllamaClient, type HttpResponse } from '../ollama/client';
import { canUseTools } from '../ollama/types';
import { ModelSelection } from '../settings/modelSelection';
import { SettingsRepository, memoryStore } from '../settings/settings';
import { fakeFetch, jsonResponse, ndjson, streamResponse, type RecordedRequest } from '../testing/fakeHttp';
import { FakeClock, migratedDb } from '../testing/nodeSqlite';
import { currentTimeTool, zoneOffsetMinutes } from './context/currentTimeTool';
import { failed, ok, toOllama, type Tool } from './framework/tool';
import { ArgumentError, ToolArgs } from './framework/toolArgs';
import { ToolRegistry, toolGuidanceSection } from './framework/toolRegistry';
import { MAX_ROUNDS, runToolTurn, type TurnHooks } from './framework/toolRunner';
import type { LocationProvider, LocationResult } from './location/deviceLocation';
import { OpenMeteoClient } from './weather/openMeteo';
import { IMPERIAL, METRIC, describeWeatherCode, resolvePlace, splitPlaceQuery, unitsForCountry } from './weather/weather';
import { weatherTool } from './weather/weatherTool';
import forecastImperial from './weather/fixtures/forecast-imperial.json';
import geocodeFowlerville from './weather/fixtures/geocode-fowlerville.json';
import geocodeNone from './weather/fixtures/geocode-none.json';
import geocodeSpringfield from './weather/fixtures/geocode-springfield.json';

const BASE = 'http://192.168.4.82:11434';
const clock = new FakeClock(Date.UTC(2026, 9, 29, 18, 30), 'America/Detroit', -240);

const echoTool = (over: Partial<Tool> = {}): Tool => ({
  spec: {
    name: 'echo_text',
    description: 'repeats text',
    parameters: [
      { name: 'text', type: 'string', description: 'what to repeat', required: true, aliases: ['message'] },
      { name: 'times', type: 'integer', description: 'how often' },
      { name: 'loud', type: 'boolean', description: 'shout' },
    ],
  },
  statusLabel: 'Echoing…',
  displayName: 'echo',
  execute: async (args) => ok(`${args.string('text')} x${args.int('times') ?? 1}`),
  ...over,
});

describe('toOllama and ToolArgs', () => {
  it('builds the JSON Ollama expects', () => {
    const t = toOllama({
      name: 'get_x',
      description: 'does x',
      parameters: [
        { name: 'when', type: 'datetime', description: 'a time', required: true },
        { name: 'kind', type: 'string', description: 'k', enumValues: ['a', 'b'] },
      ],
    });
    expect(t.type).toBe('function');
    expect(t.function.name).toBe('get_x');
    expect(t.function.parameters).toEqual({
      type: 'object',
      properties: {
        when: { type: 'string', description: 'a time (ISO-8601, e.g. 2026-10-30T12:00:00-04:00)' },
        kind: { type: 'string', description: 'k', enum: ['a', 'b'] },
      },
      required: ['when'],
    });
  });

  it('coerces strings to numbers and booleans and repairs keys from declared aliases only', () => {
    const spec = echoTool().spec;
    const args = new ToolArgs({ Message: 'hi', times: '5', loud: 'yes' }, spec);
    expect(args.string('text')).toBe('hi'); // alias, case-insensitive
    expect(args.int('times')).toBe(5);
    expect(args.bool('loud')).toBe(true);
    expect(new ToolArgs({ text: 'a', times: 2.9 }, spec).int('times')).toBe(2);
    expect(new ToolArgs({ text: 'a' }, spec).int('times')).toBeNull(); // missing optional
    expect(() => new ToolArgs({ other: 'x' }, spec).string('text')).toThrow(ArgumentError); // nothing else is guessed
  });
});

describe('ToolRegistry', () => {
  const weather = echoTool({ spec: { name: 'get_weather', description: 'weather', parameters: [] } });
  const registry = new ToolRegistry([weather]);

  it('resolves exact, normalised and one-typo names (8+ characters)', () => {
    const offered = registry.all();
    expect(registry.resolve('get_weather', offered)).toBe(weather);
    expect(registry.resolve('functions.get_weather', offered)).toBe(weather);
    expect(registry.resolve('Get-Weather', offered)).toBe(weather);
    expect(registry.resolve('get_wether', offered)).toBe(weather);
    expect(registry.resolve('get_time', offered)).toBeNull();
  });

  it('offers nothing to a model without the tools capability', () => {
    expect(registry.available({ name: 'm', capabilities: ['completion'] })).toEqual([]);
    expect(registry.available({ name: 'm', capabilities: ['tools'] })).toHaveLength(1);
    expect(canUseTools({ name: 'm', capabilities: ['tools'] })).toBe(true);
  });

  it('writes the guidance, or the "can\'t look things up" line, or nothing', () => {
    const section = toolGuidanceSection(registry);
    const ctx = (capabilities: string[]) => ({ now: 0, zone: 'UTC', offsetMinutes: 0, model: { name: 'm', capabilities } });
    expect(section.text(ctx(['tools']))).toContain('get_weather (weather)');
    expect(section.text(ctx(['completion']))).toContain("can't look things up");
    expect(toolGuidanceSection(new ToolRegistry([])).text(ctx(['tools']))).toBeNull();
  });
});

// --- the loop, against a scripted Ollama --------------------------------------------------------------------------

const callLine = (name: string, args: Record<string, unknown>, extra: string[] = []) =>
  JSON.stringify({
    model: 'm',
    message: { role: 'assistant', content: '', tool_calls: [{ function: { name, arguments: args } }] },
    done: false,
  }) + '\n' + extra.join('');
const twoCalls = () =>
  JSON.stringify({
    model: 'm',
    message: {
      role: 'assistant',
      content: '',
      tool_calls: [{ function: { name: 'echo_text', arguments: { text: 'one' } } }, { function: { name: 'echo_text', arguments: { text: 'two' } } }],
    },
    done: false,
  }) + '\n';
const textReply = (text: string) => streamResponse([ndjson.line(text), ndjson.done()]);
const toolReply = (line: string) => streamResponse([line, ndjson.done()]);

function script(responses: (HttpResponse | ((req: RecordedRequest) => HttpResponse))[]) {
  let i = 0;
  const { fetch, requests } = fakeFetch((req) => {
    const r = responses[Math.min(i++, responses.length - 1)];
    return typeof r === 'function' ? r(req) : r;
  });
  return { client: new OllamaClient({ fetch }), requests };
}
const bodies = (requests: RecordedRequest[]) => requests.map((r) => JSON.parse(r.body!) as { messages: { role: string; content: string; tool_name?: string; tool_calls?: unknown }[]; tools?: unknown[] });

function hooks() {
  const events: string[] = [];
  const h: TurnHooks = {
    onDelta: (t) => events.push(`delta:${t}`),
    onTextReset: () => events.push('reset'),
    onStatus: (s) => events.push(`status:${s}`),
  };
  return { h, events };
}
const MODEL = { name: 'm', capabilities: ['completion', 'tools'] };
function run(client: OllamaClient, tools: Tool[], extra: { h?: TurnHooks; signal?: AbortSignal; timeoutMs?: number; model?: typeof MODEL } = {}) {
  return runToolTurn({
    client,
    baseUrl: BASE,
    model: extra.model ?? MODEL,
    messages: [{ role: 'user', content: 'go' }],
    registry: new ToolRegistry(tools),
    toolContext: { clock, signal: extra.signal },
    hooks: extra.h ?? hooks().h,
    signal: extra.signal,
    timeoutMs: extra.timeoutMs,
  });
}

describe('runToolTurn', () => {
  it('runs a tool and sends the assistant message unchanged, then a tool message, then answers', async () => {
    const { client, requests } = script([toolReply(callLine('echo_text', { text: 'hi', times: '3' })), textReply('Done.')]);
    const { h, events } = hooks();
    const out = await run(client, [echoTool()], { h });
    expect(out).toEqual({ text: 'Done.', toolsUsed: ['echo_text'], images: [] });
    const [first, second] = bodies(requests);
    expect(first.tools).toHaveLength(1);
    expect(second.messages.map((m) => m.role)).toEqual(['user', 'assistant', 'tool']);
    expect(second.messages[1].tool_calls).toEqual([{ function: { name: 'echo_text', arguments: { text: 'hi', times: '3' } } }]);
    expect(second.messages[2]).toMatchObject({ role: 'tool', tool_name: 'echo_text', content: 'hi x3' });
    expect(events).toContain('reset');
    expect(events).toContain('status:Echoing…');
  });

  it('runs two tools of one round in order', async () => {
    const { client, requests } = script([toolReply(twoCalls()), textReply('ok')]);
    await run(client, [echoTool()]);
    expect(bodies(requests)[1].messages.filter((m) => m.role === 'tool').map((m) => m.content)).toEqual(['one x1', 'two x1']);
  });

  it('follows a second round of tool calls', async () => {
    const { client, requests } = script([toolReply(callLine('echo_text', { text: 'a' })), toolReply(callLine('echo_text', { text: 'b' })), textReply('end')]);
    const out = await run(client, [echoTool()]);
    expect(out.text).toBe('end');
    expect(requests).toHaveLength(3);
    expect(out.toolsUsed).toEqual(['echo_text']); // once
  });

  it('stops after the round cap and asks once more without tools', async () => {
    const { client, requests } = script([toolReply(callLine('echo_text', { text: 'x' })), toolReply(callLine('echo_text', { text: 'x' })), toolReply(callLine('echo_text', { text: 'x' })), toolReply(callLine('echo_text', { text: 'x' })), toolReply(callLine('echo_text', { text: 'x' })), textReply('final')]);
    const out = await run(client, [echoTool()]);
    expect(out.text).toBe('final');
    expect(requests).toHaveLength(MAX_ROUNDS + 1);
    const all = bodies(requests);
    expect(all[MAX_ROUNDS].tools).toBeUndefined();
    expect(all[0].tools).toBeDefined();
  });

  it('answers an unknown tool with an error and goes on', async () => {
    const { client, requests } = script([toolReply(callLine('launch_rocket', {})), textReply('Sorry.')]);
    const out = await run(client, [echoTool()]);
    expect(out.text).toBe('Sorry.');
    expect(out.toolsUsed).toEqual([]);
    expect(bodies(requests)[1].messages[2].content).toBe("Error: there's no tool called 'launch_rocket'. Available: echo_text.");
  });

  it('turns a missing argument, a thrown error and a failed result into text for the model', async () => {
    const { client, requests } = script([toolReply(callLine('echo_text', {})), textReply('a')]);
    await run(client, [echoTool()]);
    expect(bodies(requests)[1].messages[2].content).toBe('Error: missing text for echo_text.');

    const boom = script([toolReply(callLine('echo_text', { text: 'x' })), textReply('b')]);
    await run(boom.client, [echoTool({ execute: async () => Promise.reject(new Error('secret detail')) })]);
    const boomText = bodies(boom.requests)[1].messages[2].content;
    expect(boomText).toBe('The echo tool failed unexpectedly.');
    expect(boomText).not.toContain('secret');

    const bad = script([toolReply(callLine('echo_text', { text: 'x' })), textReply('c')]);
    await run(bad.client, [echoTool({ execute: async () => failed('Nothing found.') })]);
    expect(bodies(bad.requests)[1].messages[2].content).toBe('Nothing found.');
  });

  it('gives a tool that never answers a timeout message', async () => {
    const { client, requests } = script([toolReply(callLine('echo_text', { text: 'x' })), textReply('late')]);
    const slow = echoTool({ execute: () => new Promise(() => {}) });
    const out = await run(client, [slow], { timeoutMs: 20 });
    expect(out.text).toBe('late');
    expect(bodies(requests)[1].messages[2].content).toBe('The echo tool took too long. Try again shortly.');
  });

  it('cuts a long result at the tool\'s own limit', async () => {
    const long = echoTool({ maxResultChars: 50, execute: async () => ok('y'.repeat(500)) });
    const { client, requests } = script([toolReply(callLine('echo_text', { text: 'x' })), textReply('k')]);
    await run(client, [long]);
    expect(bodies(requests)[1].messages[2].content).toHaveLength(50);
  });

  it('offers no tools to a model without the capability', async () => {
    const { client, requests } = script([textReply('hello')]);
    await run(client, [echoTool()], { model: { name: 'm', capabilities: ['completion'] } });
    expect(bodies(requests)[0].tools).toBeUndefined();
  });

  it('stops running tools when the turn is cancelled', async () => {
    const controller = new AbortController();
    const execute = jest.fn(async () => {
      controller.abort();
      return new Promise<never>(() => {});
    });
    const { client } = script([toolReply(callLine('echo_text', { text: 'x' })), textReply('never')]);
    await expect(run(client, [echoTool({ execute })], { signal: controller.signal })).rejects.toBeDefined();
    expect(execute).toHaveBeenCalledTimes(1);
  });
});

// --- the engine stores only the answer and the tool names ---------------------------------------------------------

describe('ChatEngine with tools', () => {
  it('stores the user row and one assistant row with tools_used, never the tool exchange (L3)', async () => {
    let n = 0;
    const { fetch } = fakeFetch((req) => {
      if (req.url.endsWith('/api/tags')) return jsonResponse({ models: [{ name: 'gemma4:12b-it-q8_0' }] });
      if (req.url.endsWith('/api/show')) return jsonResponse({ capabilities: ['completion', 'tools'] });
      return n++ === 0 ? toolReply(callLine('echo_text', { text: 'hi' })) : textReply('It is 2:30 PM.');
    });
    const { db } = await migratedDb();
    const repo = new ChatRepository(db, clock);
    const settings = new SettingsRepository(memoryStore());
    await settings.update((s) => ({ ...s, serverUrl: BASE }));
    const client = new OllamaClient({ fetch });
    const tools = new ToolRegistry([echoTool()]);
    const reset = jest.fn();
    const engine = new ChatEngine({
      repo,
      settings,
      selection: new ModelSelection(settings, new ModelCatalog(client)),
      client,
      prompts: new PromptBuilder(clock, () => [dateTimeSection, toolGuidanceSection(tools)]),
      listeners: [{ onTextReset: reset }],
      tools,
      clock,
      throttleMs: 0,
    });
    const chatId = await repo.createConversation();
    await engine.send(chatId, 'What time is it?');
    const rows = await repo.messages(chatId);
    expect(rows.map((r) => r.role)).toEqual(['user', 'assistant']);
    expect(rows[1]).toMatchObject({ content: 'It is 2:30 PM.', toolsUsed: ['echo_text'], status: 'complete' });
    expect(reset).toHaveBeenCalledTimes(1);
  });
});

// --- the tools ---------------------------------------------------------------------------------------------------

describe('get_current_time', () => {
  it('answers in the local zone with the offset, and in another zone on request', async () => {
    const ctx = { clock };
    const local = await currentTimeTool.execute(new ToolArgs({}, currentTimeTool.spec), ctx);
    expect(local).toEqual({ ok: true, text: 'Thursday, October 29, 2026, 2:30 PM (America/Detroit, UTC-04:00)' });
    const tokyo = await currentTimeTool.execute(new ToolArgs({ timezone: 'Asia/Tokyo' }, currentTimeTool.spec), ctx);
    expect(tokyo).toEqual({ ok: true, text: 'Friday, October 30, 2026, 3:30 AM (Asia/Tokyo, UTC+09:00)' });
    const bad = await currentTimeTool.execute(new ToolArgs({ timezone: 'Mars/Olympus' }, currentTimeTool.spec), ctx);
    expect(bad.ok).toBe(false);
  });

  it('works out the offset of a zone, daylight saving included', () => {
    expect(zoneOffsetMinutes('America/Detroit', Date.UTC(2026, 6, 1))).toBe(-240);
    expect(zoneOffsetMinutes('America/Detroit', Date.UTC(2026, 11, 1))).toBe(-300);
    expect(zoneOffsetMinutes('Asia/Kolkata', Date.UTC(2026, 6, 1))).toBe(330);
  });
});

describe('weather', () => {
  const geo = async (fixture: unknown) => new OpenMeteoClient({ fetch: fakeFetch(() => jsonResponse(fixture)).fetch }).geocode('x');
  it('maps WMO codes and chooses units by country (D7)', async () => {
    for (const code of [0, 1, 2, 3, 45, 48, 51, 55, 61, 65, 71, 77, 80, 82, 85, 86, 95, 99]) expect(describeWeatherCode(code)).not.toBe('unsettled');
    expect(describeWeatherCode(2)).toBe('partly cloudy');
    expect(unitsForCountry('US')).toEqual(IMPERIAL);
    expect(unitsForCountry('GB')).toEqual(METRIC);
    expect(unitsForCountry(null)).toEqual(METRIC);
    expect((await geo(geocodeFowlerville))[0]).toMatchObject({ name: 'Fowlerville', admin1: 'Michigan', countryCode: 'US' });
    expect(await geo(geocodeNone)).toEqual([]);
  });

  it('splits "Fowlerville, MI" and picks the matching state; an unhinted tie names the most populous', async () => {
    expect(splitPlaceQuery('Fowlerville, MI')).toEqual({ name: 'Fowlerville', hint: 'MI' });
    const spr = await geo(geocodeSpringfield);
    const withHint = resolvePlace(spr, 'IL');
    expect(withHint?.place.admin1).toBe('Illinois');
    expect(withHint?.ambiguous).toBe(false);
    const bare = resolvePlace(spr, '');
    expect(bare?.ambiguous).toBe(true);
    expect(bare?.place.population).toBe(Math.max(...spr.map((p) => p.population ?? 0)));
    expect(resolvePlace([], '')).toBeNull();
  });

  function weatherSetup(over: { location?: LocationResult; geocode?: unknown; forecastStatus?: number } = {}) {
    const urls: string[] = [];
    const { fetch } = fakeFetch((req) => {
      urls.push(req.url);
      if (req.url.includes('geocoding-api')) return jsonResponse(over.geocode ?? geocodeFowlerville);
      if (over.forecastStatus) return jsonResponse({}, over.forecastStatus);
      return jsonResponse(forecastImperial);
    });
    const location: LocationProvider = { current: async () => over.location ?? { kind: 'ok', latitude: 42.65, longitude: -84.06 } };
    const tool = weatherTool({ client: new OpenMeteoClient({ fetch }), location, units: () => IMPERIAL });
    const call = (a: Record<string, unknown>) => tool.execute(new ToolArgs(a as never, tool.spec), { clock });
    return { call, urls };
  }

  it('answers with real fixture data, URL-encoding the name, and adds the forecast on request', async () => {
    const { call, urls } = weatherSetup();
    const now = await call({ location: 'Fowlerville, MI' });
    expect(now).toEqual({
      ok: true,
      text: 'Fowlerville, Michigan (US) — now 39°F (feels like 34°F), clear, wind 6 mph, humidity 85%.',
    });
    expect(urls[0]).toContain('name=Fowlerville&');
    expect(urls[1]).toContain('temperature_unit=fahrenheit');
    const week = await call({ location: 'Fowlerville', days: '3' });
    expect(week.ok && week.text).toContain('Tomorrow: mainly clear, high 65°F, low');
    expect(week.ok && week.text).toContain('Sunday: drizzle');
    expect(week.ok && week.text.length).toBeLessThanOrEqual(400);
  });

  it('says so when no place matches, and when the service fails', async () => {
    expect(await weatherSetup({ geocode: geocodeNone }).call({ location: 'Zzzqxv' })).toEqual({ ok: false, text: "Couldn't find a place called Zzzqxv." });
    expect(await weatherSetup({ forecastStatus: 503 }).call({ location: 'Fowlerville' })).toEqual({
      ok: false,
      text: "The weather service didn't respond — try again shortly.",
    });
  });

  it('uses the phone for "here", an empty place and "near me"; a denial asks for a city', async () => {
    const ok1 = weatherSetup();
    for (const q of ['here', 'near me', '']) {
      const r = await ok1.call({ location: q });
      expect(r.ok && r.text.startsWith('Your current location — now 39°F')).toBe(true);
    }
    expect(ok1.urls.every((u) => !u.includes('geocoding-api'))).toBe(true);
    expect(await weatherSetup({ location: { kind: 'denied' } }).call({ location: 'here' })).toEqual({
      ok: false,
      text: "The user hasn't shared their location — ask for a city.",
    });
    expect((await weatherSetup({ location: { kind: 'unavailable' } }).call({})).ok).toBe(false);
  });
});
