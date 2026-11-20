import { fireEvent, render, screen } from '@testing-library/react-native';
import { ChatEngine, type EngineState } from '../chat/engine';
import { PromptBuilder, dateTimeSection } from '../chat/promptBuilder';
import { ChatRepository } from '../data/chatRepository';
import { ModelCatalog } from '../ollama/catalog';
import { OllamaClient } from '../ollama/client';
import { ModelSelection } from '../settings/modelSelection';
import { SettingsRepository, memoryStore } from '../settings/settings';
import { fakeFetch, jsonResponse, ndjson, streamResponse } from '../testing/fakeHttp';
import { FakeClock, migratedDb } from '../testing/nodeSqlite';
import { InFlightBubble } from '../ui/ChatComponents';
import { themes } from '../ui/theme';
import { ok, type ConfirmationRequest, type Tool } from './framework/tool';
import { ToolRegistry } from './framework/toolRegistry';
import { runToolTurn } from './framework/toolRunner';

const BASE = 'http://192.168.4.82:11434';
const clock = new FakeClock();
const MODEL = { name: 'm', capabilities: ['completion', 'tools'] };

const call = (name: string, args: Record<string, unknown>) =>
  streamResponse([
    JSON.stringify({ model: 'm', message: { role: 'assistant', content: '', tool_calls: [{ function: { name, arguments: args } }] }, done: false }) + '\n',
    ndjson.done(),
  ]);
const text = (t: string) => streamResponse([ndjson.line(t), ndjson.done()]);

function script(responses: ReturnType<typeof text>[]) {
  let i = 0;
  const { fetch, requests } = fakeFetch(() => responses[Math.min(i++, responses.length - 1)]);
  return { client: new OllamaClient({ fetch }), requests };
}
const bodies = (requests: { body?: string }[]) => requests.map((r) => JSON.parse(r.body!) as { messages: { role: string; content: string }[] });

function addToCalendar(executed: string[]): Tool {
  return {
    spec: { name: 'add_event', description: 'Adds an event', parameters: [{ name: 'title', type: 'string', description: 't', required: true }] },
    statusLabel: 'Adding…',
    displayName: 'Calendar',
    confirmation: (args) => ({ title: 'Add to your calendar?', details: [['What', args.string('title') ?? '']] }),
    execute: async (args) => {
      executed.push(args.string('title') ?? '');
      return ok('Added.');
    },
  };
}
const plainTool = (executed: string[]): Tool => ({ ...addToCalendar(executed), confirmation: undefined });

function turn(client: OllamaClient, tool: Tool, confirm?: (r: ConfirmationRequest) => Promise<boolean>, signal?: AbortSignal) {
  return runToolTurn({
    client,
    baseUrl: BASE,
    model: MODEL,
    messages: [{ role: 'user', content: 'go' }],
    registry: new ToolRegistry([tool]),
    toolContext: { clock, signal },
    hooks: { onDelta: () => {}, onTextReset: () => {}, onStatus: () => {} },
    signal,
    confirm,
  });
}

describe('confirmations in the tool loop', () => {
  it('asks first, then runs the tool when approved', async () => {
    const done: string[] = [];
    const { client, requests } = script([call('add_event', { title: 'Dentist' }), text('Added it.')]);
    const asked: ConfirmationRequest[] = [];
    const out = await turn(client, addToCalendar(done), async (r) => {
      asked.push(r);
      return true;
    });
    expect(asked).toEqual([
      {
        id: expect.any(String),
        toolName: 'add_event',
        title: 'Add to your calendar?',
        details: [['What', 'Dentist']],
        confirmLabel: 'Confirm',
        denyLabel: 'Cancel',
      },
    ]);
    expect(done).toEqual(['Dentist']);
    expect(out.toolsUsed).toEqual(['add_event']);
    expect(bodies(requests)[1].messages[2].content).toBe('Added.');
  });

  it('a declined action is not run and the model is told not to retry', async () => {
    const done: string[] = [];
    const { client, requests } = script([call('add_event', { title: 'Dentist' }), text('OK, skipped.')]);
    const out = await turn(client, addToCalendar(done), async () => false);
    expect(done).toEqual([]);
    expect(out.toolsUsed).toEqual([]);
    expect(bodies(requests)[1].messages[2].content).toBe("The user declined: Add to your calendar? Don't retry unless they ask.");
  });

  it('a tool with nothing to confirm never asks', async () => {
    const done: string[] = [];
    const { client } = script([call('add_event', { title: 'x' }), text('ok')]);
    const confirm = jest.fn(async () => true);
    await turn(client, plainTool(done), confirm);
    expect(confirm).not.toHaveBeenCalled();
    expect(done).toEqual(['x']);
  });

  it('without a way to ask, an action that needs approval is not run', async () => {
    const done: string[] = [];
    const { client, requests } = script([call('add_event', { title: 'x' }), text('ok')]);
    await turn(client, addToCalendar(done));
    expect(done).toEqual([]);
    expect(bodies(requests)[1].messages[2].content).toContain('needs the owner');
  });

  it('a missing argument is reported before anything is asked', async () => {
    const done: string[] = [];
    const { client, requests } = script([call('add_event', {}), text('ok')]);
    const confirm = jest.fn(async () => true);
    await turn(client, addToCalendar(done), confirm);
    expect(confirm).not.toHaveBeenCalled();
    expect(bodies(requests)[1].messages[2].content).toBe('Error: missing title for add_event.');
  });

  it('stopping the turn while the card is up runs nothing', async () => {
    const done: string[] = [];
    const ctl = new AbortController();
    const { client } = script([call('add_event', { title: 'x' }), text('ok')]);
    const waiting = () =>
      new Promise<boolean>((_, reject) => {
        ctl.signal.addEventListener('abort', () => reject(new Error('Aborted')));
      });
    const pending = turn(client, addToCalendar(done), waiting, ctl.signal);
    await new Promise((r) => setTimeout(r, 20));
    ctl.abort();
    await expect(pending).rejects.toThrow();
    expect(done).toEqual([]);
  });
});

async function engineFixture(tool: Tool) {
  const { fetch } = fakeFetch((req) => {
    if (req.url.endsWith('/api/tags')) return jsonResponse({ models: [{ name: 'm' }] });
    if (req.url.endsWith('/api/show')) return jsonResponse({ capabilities: ['completion', 'tools'] });
    const sent = (JSON.parse(req.body!) as { messages: { role: string }[] }).messages;
    return sent.some((m) => m.role === 'tool') ? text('All done.') : call('add_event', { title: 'Dentist' });
  });
  const { db } = await migratedDb();
  const repo = new ChatRepository(db, clock);
  const settings = new SettingsRepository(memoryStore());
  await settings.update((s) => ({ ...s, serverUrl: BASE, model: 'm' }));
  const client = new OllamaClient({ fetch });
  const engine = new ChatEngine({
    repo,
    settings,
    selection: new ModelSelection(settings, new ModelCatalog(client)),
    client,
    prompts: new PromptBuilder(clock, () => [dateTimeSection]),
    listeners: [],
    tools: new ToolRegistry([tool]),
    throttleMs: 0,
  });
  const states: EngineState[] = [];
  engine.state.subscribe(() => states.push(engine.state.get()));
  return { engine, repo, states };
}
function waitingFor(states: EngineState[]): ConfirmationRequest | undefined {
  for (const s of states) if (s.kind === 'working' && s.confirmation) return s.confirmation;
  return undefined;
}
const tick = () => new Promise((r) => setTimeout(r, 50));

describe('the engine and the card', () => {
  it('shows the request while waiting, runs the tool after Confirm, and stores the reply', async () => {
    const done: string[] = [];
    const f = await engineFixture(addToCalendar(done));
    const id = await f.repo.createConversation();
    const turnDone = f.engine.send(id, 'add a dentist visit');
    await tick();
    const cur = f.engine.state.get();
    expect(cur.kind === 'working' && cur.confirmation?.title).toBe('Add to your calendar?');
    expect(done).toEqual([]);
    f.engine.resolveConfirmation('wrong-id', true); // not the waiting request: ignored
    expect(done).toEqual([]);
    f.engine.resolveConfirmation(waitingFor(f.states)!.id, true);
    await turnDone;
    expect(done).toEqual(['Dentist']);
    const msgs = await f.repo.messages(id);
    expect(msgs.map((m) => m.content)).toEqual(['add a dentist visit', 'All done.']);
    expect(msgs[1].toolsUsed).toEqual(['add_event']);
    expect(f.engine.state.get().kind).toBe('idle');
  });

  it('Cancel on the card: the tool is not run and Edith still answers', async () => {
    const done: string[] = [];
    const f = await engineFixture(addToCalendar(done));
    const id = await f.repo.createConversation();
    const turnDone = f.engine.send(id, 'add it');
    await tick();
    f.engine.resolveConfirmation(waitingFor(f.states)!.id, false);
    await turnDone;
    expect(done).toEqual([]);
    expect((await f.repo.messages(id)).at(-1)?.content).toBe('All done.');
  });

  it('Stop while the card is up: nothing runs and the engine goes idle', async () => {
    const done: string[] = [];
    const f = await engineFixture(addToCalendar(done));
    const id = await f.repo.createConversation();
    const turnDone = f.engine.send(id, 'add it');
    await tick();
    const request = waitingFor(f.states)!;
    f.engine.cancel();
    await turnDone;
    expect(done).toEqual([]);
    expect(f.engine.state.get().kind).toBe('idle');
    f.engine.resolveConfirmation(request.id, true); // a late answer from the screen changes nothing
    expect(done).toEqual([]);
  });
});

describe('ConfirmationCard in the reply bubble', () => {
  const request: ConfirmationRequest = {
    id: 'c1',
    toolName: 'add_event',
    title: 'Add to your calendar?',
    details: [['When', 'Fri 12:00']],
    confirmLabel: 'Add',
    denyLabel: 'Not now',
  };
  it('shows the title, details and answers with Confirm and Cancel', async () => {
    const onAnswer = jest.fn();
    await render(<InFlightBubble text="" label="Waiting" canStop onStop={() => {}} theme={themes.light} confirmation={request} onAnswer={onAnswer} />);
    expect(screen.getByText('Add to your calendar?')).toBeTruthy();
    expect(screen.getByText('Fri 12:00')).toBeTruthy();
    expect(screen.getByText('Add')).toBeTruthy();
    await fireEvent.press(screen.getByLabelText('Confirm action'));
    await fireEvent.press(screen.getByLabelText('Cancel action'));
    expect(onAnswer.mock.calls).toEqual([[true], [false]]);
  });
});
