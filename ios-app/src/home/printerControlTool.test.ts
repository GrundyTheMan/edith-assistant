import { PrinterFeed, type PrinterAccess } from '../printer/feed/feed';
import { connectedFeed, fixture, type FakeFeed } from '../printer/feed/testkit';
import { fakeFetch, jsonResponse, ndjson, streamResponse } from '../testing/fakeHttp';
import { OllamaClient } from '../ollama/client';
import { FakeClock } from '../testing/nodeSqlite';
import { ToolArgs } from '../tools/framework/toolArgs';
import { ToolRegistry } from '../tools/framework/toolRegistry';
import { runToolTurn } from '../tools/framework/toolRunner';
import type { ConfirmationRequest, ConfirmationSpec, Tool, ToolResult } from '../tools/framework/tool';
import { printerControlTool } from './printerControlTool';

const ctx = { clock: new FakeClock() };

async function setup(over: (f: FakeFeed) => void = () => {}, mayControl = true) {
  const c = await connectedFeed(over);
  let n = 0;
  const tool = printerControlTool({ feed: new PrinterFeed(c.service), mayControl: () => mayControl, uuid: () => `chat-${++n}` });
  const args = (a: Record<string, unknown>) => new ToolArgs(a as never, tool.spec);
  const commands = () => c.feed.requests.filter((r) => r.path === '/v1/printer/command');
  /** The runner's order: ask for a card; the owner says yes; run. */
  const approved = async (a: Record<string, unknown>): Promise<{ card: ConfirmationSpec | null; result: ToolResult }> => {
    const card = (await tool.confirmation!(args(a), ctx)) ?? null;
    return { card, result: await tool.execute(args(a), ctx) };
  };
  return { ...c, tool, args, commands, approved };
}

describe('printer_control (C39 §10)', () => {
  it('is only offered when Jarvis lets this phone control the printer, and says what it is for', async () => {
    const on = await setup();
    expect(on.tool.enabled!()).toBe(true);
    expect(on.tool.spec.name).toBe('printer_control');
    expect(on.tool.spec.description).toBe("Pause, resume or cancel the owner's 3D print, or set the printer's light, through Jarvis (works away from home). Every call shows the owner a card first.");
    expect(on.tool.spec.parameters.find((p) => p.name === 'action')).toMatchObject({ required: true, enumValues: ['pause', 'resume', 'cancel', 'light'] });
    expect((await setup(() => {}, false)).tool.enabled!()).toBe(false);
  });

  it("pause: a card in Jarvis's own words (or Edith's when it has none), then it is sent as chat with a request id and no Controls", async () => {
    const t = await setup();
    const { card, result } = await t.approved({ action: 'pause' });
    expect(card).toEqual({ title: 'Pause the print?', details: [['Printer', 'FlashForge AD5X']], confirmLabel: 'Pause', denyLabel: 'Cancel', screenOnly: false });
    expect(result).toEqual({ ok: true, text: 'Done: paused the print. Jarvis sent PAUSE.' });
    const [dry, real] = t.commands();
    expect(dry.body).toMatchObject({ command: { kind: 'pause' }, via: 'chat', dryRun: true });
    expect(dry.body).not.toHaveProperty('requestId');
    expect(real.body).toMatchObject({ command: { kind: 'pause' }, via: 'chat', confirmed: true, requestId: 'chat-1' });
    expect(t.feed.armed).toBe(false); // it never arms Controls
  });

  it('resume and light, with the level read as a person says it', async () => {
    const t = await setup();
    expect((await t.approved({ action: 'resume' })).result).toMatchObject({ ok: true, text: 'Done: resumed the print. Jarvis sent RESUME.' });
    expect((await t.approved({ action: 'light' })).card).toMatchObject({ title: "Set the printer's light to 100%?", confirmLabel: 'Set light' });
    const sent = () => t.commands().filter((r) => r.body?.confirmed && (r.body.command as { kind: string }).kind === 'light').map((r) => (r.body!.command as { level?: number }).level);
    await t.approved({ action: 'light', level: '50%' });
    await t.approved({ action: 'light', level: 0 });
    await t.approved({ action: 'light', level: '250' });
    await t.approved({ action: 'light', level: '-5' });
    await t.approved({ action: 'light', level: ' 12,5 ' });
    expect(sent()).toEqual([1, 0.5, 0, 1, 0, 0.125]);
    expect((await t.approved({ action: 'light', level: 0 })).card!.title).toBe("Turn the printer's light off?");
    expect((await t.approved({ action: 'light', level: 'bright' })).result).toEqual({ ok: false, text: 'The light level must be a number from 0 to 100.' });
  });

  it("cancel: Jarvis's question and words, 'Keep printing' as the other button, and a tap on the screen only", async () => {
    const t = await setup();
    const { card, result } = await t.approved({ action: 'cancel' });
    expect(card).toEqual({ title: 'Really cancel?', details: [['Printer', 'FlashForge AD5X']], confirmLabel: 'cancel', denyLabel: 'Keep printing', screenOnly: true });
    expect(result).toMatchObject({ ok: true, text: 'Done: cancelled the print. Jarvis sent CANCEL.' });
  });

  it('every other action and a wrong one are explained, and nothing is sent', async () => {
    const t = await setup();
    for (const action of ['estop', 'home', 'PAUSE it']) {
      const { card, result } = await t.approved({ action });
      expect(card).toBeNull();
      expect(result).toEqual({ ok: false, text: 'The action must be pause, resume, cancel or light.' });
    }
    expect(t.commands()).toHaveLength(0);
    expect((await t.approved({ action: ' Pause ' })).result.ok).toBe(true);
    expect((await t.approved({ command: 'resume' })).result.ok).toBe(true); // a model's other name for it
  });

  it('nothing is sent unless a card was shown for this very call', async () => {
    const t = await setup();
    const r = await t.tool.execute(t.args({ action: 'cancel' }), ctx);
    expect(r).toEqual({ ok: false, text: "The owner wasn't asked, so nothing was sent." });
    expect(t.commands()).toHaveLength(0);
    // one card is good for one run only
    await t.tool.confirmation!(t.args({ action: 'pause' }), ctx);
    await t.tool.execute(t.args({ action: 'pause' }), ctx);
    const again = await t.tool.execute(t.args({ action: 'pause' }), ctx);
    expect(again.ok).toBe(false);
    expect(t.commands().filter((c) => c.body?.confirmed)).toHaveLength(1);
  });

  it("when Jarvis's check could not be had the owner still decides, on a card in Edith's own words", async () => {
    const t = await setup((f) => (f.onCommand = (b) => (b.dryRun ? jsonResponse({ error: { code: 'X', message: 'boom' } }, 500) : undefined)));
    const { card, result } = await t.approved({ action: 'cancel' });
    expect(card).toMatchObject({ title: "Cancel the print? It can't be continued.", confirmLabel: 'Cancel print', denyLabel: 'Keep printing', screenOnly: true });
    expect(result.ok).toBe(true);
  });

  it("when Jarvis's check says no there is no card, and the reason is told; nothing is sent", async () => {
    const t = await setup((f) => (f.onCommand = (b) => (b.dryRun ? jsonResponse({ allowed: false, risk: 'none', reason: 'It is already paused.' }) : undefined)));
    const { card, result } = await t.approved({ action: 'pause' });
    expect(card).toBeNull();
    expect(result).toEqual({ ok: false, text: 'It is already paused.' });
    expect(t.commands().filter((c) => !c.body?.dryRun)).toHaveLength(0);
    const blank = await setup((f) => (f.onCommand = (b) => (b.dryRun ? jsonResponse({ allowed: false, risk: 'none' }) : undefined)));
    expect((await blank.approved({ action: 'pause' })).result).toEqual({ ok: false, text: 'Jarvis says no to that right now.' });
  });

  it('a refusal later is told in Jarvis\'s words; Controls off and "asked again" are told too', async () => {
    const refused = await setup((f) => (f.onCommand = (b) => (b.dryRun ? undefined : jsonResponse(fixture('error-refused'), 409))));
    expect((await refused.approved({ action: 'pause' })).result).toEqual({ ok: false, text: 'Home the printer first.' });
    const off = await setup((f) => (f.onCommand = (b) => (b.dryRun ? undefined : jsonResponse({ error: { code: 'CONTROLS_OFF', message: 'x' } }, 403))));
    expect((await off.approved({ action: 'pause' })).result.text).toBe('Controls turned off. Turn them on to continue.');
    const again = await setup((f) => (f.onCommand = (b) => (b.dryRun ? undefined : jsonResponse({ error: { code: 'CONFIRM_REQUIRED', message: 'x' }, check: { allowed: true, risk: 'danger', question: 'Really?', confirm: 'go' } }, 409))));
    expect((await again.approved({ action: 'pause' })).result).toEqual({ ok: false, text: 'Jarvis asked again; nothing was done. Ask the owner to try again.' });
    const down = await setup((f) => (f.onCommand = (b) => (b.dryRun ? undefined : jsonResponse({ error: { code: 'X', message: 'nope' } }, 500))));
    expect((await down.approved({ action: 'pause' })).result.ok).toBe(false);
  });

  it("a card the owner turned down is not good for the next call", async () => {
    const t = await setup();
    const real = new PrinterFeed(t.service);
    let up = true;
    const tool = printerControlTool({ feed: { access: async () => (up ? real.access() : { kind: 'away' as const, why: 'x', removed: false }) }, mayControl: () => true, uuid: () => 'z' });
    const args = new ToolArgs({ action: 'cancel' } as never, tool.spec);
    expect(await tool.confirmation!(args, ctx)).not.toBeNull(); // shown, answered no: execute never ran
    // the next call finds nothing to show a card for (Jarvis away at that moment), then Jarvis is back when it runs
    up = false;
    expect(await tool.confirmation!(args, ctx)).toBeNull();
    up = true;
    expect(await tool.execute(args, ctx)).toEqual({ ok: false, text: "The owner wasn't asked, so nothing was sent." });
    expect(t.commands().filter((c) => c.body?.confirmed)).toHaveLength(0);
  });

  it('with no Jarvis, no sharing, no control, or Jarvis away it says why, shows no card, and sends nothing', async () => {
    const access = async (a: PrinterAccess) => {
      const c = await connectedFeed();
      const tool = printerControlTool({ feed: { access: async () => a }, mayControl: () => true, uuid: () => 'x' });
      const args = new ToolArgs({ action: 'pause' } as never, tool.spec);
      const card = await tool.confirmation!(args, ctx);
      const result = await tool.execute(args, ctx);
      return { card, result, requests: c.feed.requests.length };
    };
    const noJarvis = await access({ kind: 'noJarvis' });
    expect(noJarvis.card).toBeNull();
    expect(noJarvis.result.text).toContain("hasn't paired Edith with Jarvis");
    expect((await access({ kind: 'notShared' })).result.text).toBe("Jarvis isn't sharing the printer with this phone.");
    expect((await access({ kind: 'away', why: 'Is the PC on?', removed: false })).result.text).toBe('Is the PC on?');
    const lookOnly = await access({ kind: 'ready', api: {} as never, shared: { name: 'P', control: false, alerts: true, armMinutes: 5, presets: [], cameras: [], features: [], schema: 1 } });
    expect(lookOnly.card).toBeNull();
    expect(lookOnly.result.text).toBe('Jarvis lets this phone look at the printer, not control it.');
    expect(noJarvis.requests).toBe(0);
  });
});

describe('printer_control in the tool loop', () => {
  const call = (name: string, args: Record<string, unknown>) =>
    streamResponse([JSON.stringify({ model: 'm', message: { role: 'assistant', content: '', tool_calls: [{ function: { name, arguments: args } }] }, done: false }) + '\n', ndjson.done()]);
  const text = (t: string) => streamResponse([ndjson.line(t), ndjson.done()]);
  async function run(action: string, answer: boolean) {
    const t = await setup();
    let i = 0;
    const responses = [call('printer_control', { action }), text('OK.')];
    const { fetch } = fakeFetch(() => responses[Math.min(i++, responses.length - 1)]);
    const asked: ConfirmationRequest[] = [];
    const out = await runToolTurn({
      client: new OllamaClient({ fetch }),
      baseUrl: 'http://192.168.4.82:11434',
      model: { name: 'm', capabilities: ['completion', 'tools'] },
      messages: [{ role: 'user', content: 'go' }],
      registry: new ToolRegistry([t.tool as Tool]),
      toolContext: ctx,
      hooks: { onDelta: () => {}, onTextReset: () => {}, onStatus: () => {} },
      confirm: async (r) => {
        asked.push(r);
        return answer;
      },
    });
    return { t, asked, out };
  }

  it('"cancel the print" shows a card that only a tap can answer, and waits for it', async () => {
    const { t, asked, out } = await run('cancel', true);
    expect(asked).toHaveLength(1);
    expect(asked[0]).toMatchObject({ toolName: 'printer_control', title: 'Really cancel?', confirmLabel: 'cancel', denyLabel: 'Keep printing', screenOnly: true });
    expect(t.commands().filter((c) => c.body?.confirmed)).toHaveLength(1);
    expect(out.toolsUsed).toEqual(['printer_control']);
  });

  it('a "no" on the card sends nothing', async () => {
    const { t, asked } = await run('cancel', false);
    expect(asked).toHaveLength(1);
    expect(t.commands().filter((c) => !c.body?.dryRun)).toHaveLength(0);
  });

  it('"pause the print" shows its card too, and pauses it on a yes', async () => {
    const { t, asked } = await run('pause', true);
    expect(asked[0]).toMatchObject({ title: 'Pause the print?', screenOnly: false });
    expect(t.commands().filter((c) => c.body?.confirmed)).toHaveLength(1);
  });
});
