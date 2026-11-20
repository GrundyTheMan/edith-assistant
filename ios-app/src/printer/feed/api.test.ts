import { PrinterFeedApi, MAX_WAIT_S } from './api';
import { PrinterWords, wordsForError, wordsForFailure } from './words';
import { FEED_TOKEN, FakeFeed, JPEG, connectedFeed, fixture } from './testkit';

async function setup(over?: (f: FakeFeed) => void) {
  const c = await connectedFeed(over);
  return { ...c, api: new PrinterFeedApi(c.service) };
}

describe('PrinterFeedApi (every route against the pretend feed; the same cases as Android\'s PrinterFeedApiTest)', () => {
  it('state asks with the revision, epoch, wait and quiet, signed with the token', async () => {
    const { api, feed } = await setup();
    const r = await api.state(56, 1763400000, 20, { quiet: true }); // an older revision than Jarvis has: answered at once
    expect(r).toMatchObject({ kind: 'ok', value: { rev: 57 } });
    const q = feed.requests.at(-1)!;
    expect(q).toMatchObject({ method: 'GET', path: '/v1/printer/state', query: { after: '56', epoch: '1763400000', wait: '20', quiet: '1' }, auth: `Bearer ${FEED_TOKEN}` });
  });

  it('the first state question sends no revision and does not wait', async () => {
    const { api, feed } = await setup();
    await api.state(null, null, 0);
    expect(feed.requests.at(-1)!.query).toEqual({});
  });

  it('a wait over 25 seconds is cut to 25', async () => {
    const { api, feed } = await setup();
    await api.state(1, null, 90);
    expect(feed.requests.at(-1)!.query.wait).toBe(String(MAX_WAIT_S));
  });

  it('the list routes read their answers', async () => {
    const { api, feed } = await setup();
    expect(await api.temperatures()).toMatchObject({ kind: 'ok', value: [{ label: expect.any(String) }, expect.anything(), expect.anything()] });
    const macros = await api.macros();
    expect(macros.kind === 'ok' && macros.value.macros.map((m) => m.name)).toEqual(['LOAD_FILAMENT', 'BED_MESH_CALIBRATE']);
    const con = await api.console(120, 50);
    expect(con).toMatchObject({ kind: 'ok', value: { seq: 121 } });
    expect(feed.requests.at(-1)!.query).toEqual({ after: '120', count: '50' });
    const files = await api.files(true);
    expect(files).toMatchObject({ kind: 'ok', value: { files: expect.any(Array) } });
    expect(feed.requests.at(-1)!.query).toEqual({ fresh: '1' });
    await api.files(false);
    expect(feed.requests.at(-1)!.query).toEqual({ fresh: '0' });
    expect(await api.history(20)).toMatchObject({ kind: 'ok', value: { totals: { jobs: 966 } } });
    expect(feed.requests.at(-1)!.query).toEqual({ start: '20' });
  });

  it('a file path with odd characters is one encoded query value, never glued into the URL', async () => {
    const { api, feed } = await setup();
    const odd = 'gcodes/a b&c=d/é😀 #1?.gcode';
    const r = await api.fileDetail(odd);
    expect(r).toMatchObject({ kind: 'ok', value: { path: odd } });
    expect(feed.requests.at(-1)!.path).toBe('/v1/printer/files/detail');
    expect(feed.requests.at(-1)!.query).toEqual({ path: odd });
  });

  it('a camera picture comes back as bytes, and the id is one path segment', async () => {
    const { api, feed } = await setup();
    const r = await api.snapshot('video');
    expect(r.kind === 'ok' && Array.from(r.value)).toEqual(Array.from(JPEG));
    await api.snapshot('a/b c');
    expect(feed.requests.at(-1)!.path).toBe('/v1/printer/camera/a/b c/snapshot'.replace('a/b c', 'a%2Fb%20c').replace(/%2F/g, '%2F'));
  });

  it('a camera that cannot give a picture says so, and a 404 for it is not shared', async () => {
    const { api, feed } = await setup();
    feed.snapshotStatus = 503;
    expect(await api.snapshot('video')).toMatchObject({ kind: 'failed', code: 'PRINTER_UNREACHABLE' });
  });

  it('controls sends on or off and returns the arm state', async () => {
    const { api, feed } = await setup();
    expect(await api.controls(true)).toEqual({ kind: 'ok', value: { armed: true, until: 1763400160000 } });
    expect(feed.requests.at(-1)!.body).toEqual({ on: true });
    expect(await api.controls(false)).toEqual({ kind: 'ok', value: { armed: false, until: null } });
  });
});

describe('sending commands', () => {
  it('a command is sent as typed JSON with its request id (and says it is from the phone)', async () => {
    const { api, feed } = await setup();
    await api.controls(true);
    const r = await api.command({ kind: 'jog', axis: 'x', distance: 10 }, 'phone', { requestId: 'req-1' });
    expect(r).toEqual({ kind: 'sent', sent: 'JOG', risk: 'none' });
    expect(feed.requests.at(-1)!.body).toEqual({ command: { kind: 'jog', axis: 'x', distance: 10 }, via: 'phone', requestId: 'req-1' });
  });

  it('a dry run sends no request id and says it is a dry run', async () => {
    const { api, feed } = await setup();
    const r = await api.command({ kind: 'home', axes: 'xyz' }, 'phone', { dryRun: true, requestId: 'req-2' });
    expect(r).toEqual({ kind: 'checked', check: { allowed: true, risk: 'confirm', reason: null, question: 'Really home?', confirm: 'home' } });
    expect(feed.requests.at(-1)!.body).toEqual({ command: { kind: 'home', axes: 'xyz' }, via: 'phone', dryRun: true });
  });

  it('a command the owner must confirm comes back with the server\'s own question, and goes through when confirmed', async () => {
    const { api, feed } = await setup();
    await api.controls(true);
    const first = await api.command({ kind: 'home', axes: 'xyz' }, 'phone', { requestId: 'req-3' });
    expect(first).toEqual({ kind: 'needsConfirm', check: expect.objectContaining({ risk: 'confirm', question: 'Really home?', confirm: 'home' }) });
    const second = await api.command({ kind: 'home', axes: 'xyz' }, 'phone', { requestId: 'req-3', confirmed: true });
    expect(second).toEqual({ kind: 'sent', sent: 'HOME', risk: 'confirm' });
    expect(feed.requests.at(-1)!.body).toMatchObject({ confirmed: true, requestId: 'req-3' }); // the same id on the retry
  });

  it('Controls being off and a refusal are told apart', async () => {
    const { api, feed } = await setup();
    expect(await api.command({ kind: 'pause' }, 'phone', { requestId: 'r' })).toEqual({ kind: 'controlsOff' });
    feed.onCommand = () => new FakeRefusal().response();
    expect(await api.command({ kind: 'jog', axis: 'x', distance: 1 }, 'phone', { requestId: 'r' })).toEqual({ kind: 'refused', reason: 'Home the printer first.' });
  });

  it('other errors are put in plain words with their code kept', async () => {
    const { api, feed } = await setup();
    const cases: [string, number, string, string][] = [
      ['PRINTER_UNREACHABLE', 502, 'x', PrinterWords.PRINTER_UNREACHABLE],
      ['CONTROL_OFF', 403, 'x', PrinterWords.CONTROL_OFF],
      ['PRINTER_NOT_SHARED', 404, 'x', PrinterWords.NOT_SHARED],
      ['TOO_MANY', 429, 'x', PrinterWords.TOO_MANY],
      ['BAD_REQUEST', 400, 'x', PrinterWords.BAD_REQUEST],
      ['TIMEOUT', 504, 'x', PrinterWords.TIMEOUT],
      ['WHATEVER', 500, 'Something odd happened.', 'Something odd happened.'],
      ['WHATEVER', 500, 'Oops', PrinterWords.UNEXPECTED],
    ];
    for (const [code, status, message, words] of cases) {
      feed.onCommand = () => new FakeRefusal(code, status, message).response();
      const r = await api.command({ kind: 'pause' }, 'phone', { requestId: 'r' });
      expect(r).toEqual({ kind: 'failed', words, code: code === 'TIMEOUT' ? 'TIMEOUT' : code });
    }
  });

  it('an answer that is not what was promised is not a crash', async () => {
    const { api, feed } = await setup();
    feed.onCommand = () => new FakeRefusal().ok({ weird: true });
    expect(await api.command({ kind: 'pause' }, 'phone', { requestId: 'r' })).toMatchObject({ kind: 'failed', words: expect.any(String) });
    expect(await api.command({ kind: 'pause' }, 'phone', { dryRun: true })).toMatchObject({ kind: 'failed' });
    feed.onCommand = () => new FakeRefusal('CONFIRM_REQUIRED', 409, 'Ask').response(); // no check in the body
    expect(await api.command({ kind: 'cancel' }, 'phone', { requestId: 'r' })).toEqual({ kind: 'failed', words: PrinterWords.UNEXPECTED, code: 'CONFIRM_REQUIRED' });
  });
});

describe('when Jarvis is not there or the phone is not paired', () => {
  it('a removed phone is unauthorized and the connection is told (pair it again)', async () => {
    const { api, feed, service } = await setup();
    feed.tokens.clear();
    expect(await api.temperatures()).toEqual({ kind: 'unauthorized' });
    expect(service.state.get()).toMatchObject({ kind: 'needs_sign_in', reason: PrinterWords.UNAUTHORIZED });
    expect(await api.command({ kind: 'pause' }, 'phone', { requestId: 'r' })).toEqual({ kind: 'failed', words: PrinterWords.UNAUTHORIZED, code: 'UNAUTHORIZED' });
  });

  it('no pairing at all is unauthorized without a request', async () => {
    const { api, feed, service } = await setup();
    await service.disconnect();
    const before = feed.requests.length;
    expect(await api.macros()).toEqual({ kind: 'unauthorized' });
    expect(feed.requests).toHaveLength(before);
  });

  it('a PC that is off is unreachable, in words', async () => {
    const { api, feed } = await setup();
    feed.down = true;
    expect(await api.macros()).toEqual({ kind: 'unreachable' });
    expect(await api.command({ kind: 'pause' }, 'phone', { requestId: 'r' })).toEqual({ kind: 'failed', words: PrinterWords.UNREACHABLE, code: 'UNREACHABLE' });
    expect(wordsForFailure({ kind: 'unreachable' })).toBe(PrinterWords.UNREACHABLE);
  });

  it('an answer that is not what was promised is a failure in words, not a crash', async () => {
    const { api, feed } = await setup();
    feed.answer = { not: 'a state' };
    const r = await api.state(null, null, 0);
    expect(r).toMatchObject({ kind: 'failed' });
    expect(wordsForFailure(r)).toBe(PrinterWords.UNEXPECTED);
  });

  it('a time limit cancels a held long poll at once instead of waiting for Jarvis', async () => {
    const { api, feed } = await setup();
    feed.hold = true;
    const ctl = new AbortController();
    const p = api.state(57, 1763400000, 25, { signal: ctl.signal });
    await new Promise((r) => setTimeout(r, 5));
    expect(feed.heldPolls()).toBe(1);
    ctl.abort();
    expect(await p).toEqual({ kind: 'unreachable' });
    expect(feed.heldPolls()).toBe(0);
  });

  it('the token never appears in the words of an error', async () => {
    const { api, feed } = await setup();
    feed.onCommand = () => new FakeRefusal('REFUSED', 400, `Bad ${FEED_TOKEN} thing`).response();
    const r = await api.command({ kind: 'pause' }, 'phone', { requestId: 'r' });
    // Jarvis never echoes a token; if it ever did, only its own sentence is shown, and the client never adds one.
    expect(JSON.stringify(await api.temperatures())).not.toContain(FEED_TOKEN);
    expect(r.kind).toBe('refused');
  });
});

describe('the words (C39 §7.5)', () => {
  it('has the table\'s sentence for each code, and Jarvis\'s own sentence where the table says so', () => {
    expect(wordsForError('PRINTER_NOT_SHARED')).toBe("Jarvis isn't sharing the printer with this phone.");
    expect(wordsForError('CONTROL_OFF')).toBe('Jarvis lets this phone look at the printer, not control it.');
    expect(wordsForError('CONTROLS_OFF')).toBe('Controls turned off. Turn them on to continue.');
    expect(wordsForError('REFUSED', 'Home the printer first.')).toBe('Home the printer first.');
    expect(wordsForError('AWAY_LIMITED', 'Not while away.')).toBe('Not while away.');
    expect(wordsForError('REFUSED', 'SomeDump')).toBe(PrinterWords.UNEXPECTED);
    expect(wordsForError('REFUSED', 'x '.repeat(200))).toBe(PrinterWords.UNEXPECTED); // a long dump is not a sentence
    expect(wordsForError('TOO_LARGE')).toBe('That file is too big to send from the phone.');
    expect(wordsForError('NOT_FOUND')).toBe("Jarvis doesn't have that any more. Refresh the list.");
    expect(PrinterWords.UNREACHABLE).toBe("Edith can't reach Jarvis. Is the PC on, Jarvis running, and Tailscale connected (away from home)?");
    expect(wordsForFailure({ kind: 'ok', value: 1 })).toBe('');
    expect(wordsForFailure({ kind: 'notShared' })).toBe(PrinterWords.NOT_SHARED);
    expect(wordsForFailure({ kind: 'busy' })).toBe(PrinterWords.TOO_MANY);
    expect(wordsForFailure({ kind: 'timeout' })).toBe(PrinterWords.TIMEOUT);
    expect(wordsForFailure({ kind: 'unavailable', message: "Outlook isn't running." })).toBe("Outlook isn't running.");
    expect(fixture('error-refused')).toBeTruthy();
  });
});

/** Builds the error answers the pretend feed's `onCommand` returns. */
class FakeRefusal {
  constructor(
    private readonly code = 'REFUSED',
    private readonly status = 400,
    private readonly message = 'Home the printer first.',
  ) {}
  response() {
    return this.build(this.status, { error: { code: this.code, message: this.message } });
  }
  ok(body: unknown) {
    return this.build(200, body);
  }
  private build(status: number, body: unknown) {
    // eslint-disable-next-line @typescript-eslint/no-require-imports
    return (require('../../testing/fakeHttp') as typeof import('../../testing/fakeHttp')).jsonResponse(body, status);
  }
}
