import { runStreamProbe } from '../ollama/probe';
import { OllamaClient } from '../ollama/client';
import { fakeFetch, ndjson, streamResponse } from '../testing/fakeHttp';
import { normalizeServerUrl } from './serverUrl';
import { DEFAULT_SETTINGS, SettingsRepository, memoryStore } from './settings';

describe('normalizeServerUrl', () => {
  it('adds the scheme and the default port, strips trailing slashes', () => {
    expect(normalizeServerUrl('192.168.4.82')).toEqual({ valid: true, url: 'http://192.168.4.82:11434' });
    expect(normalizeServerUrl('http://x:1234/')).toEqual({ valid: true, url: 'http://x:1234' });
    expect(normalizeServerUrl('  https://pc.local  ')).toEqual({ valid: true, url: 'https://pc.local:11434' });
    expect(normalizeServerUrl('MyPC:8080')).toEqual({ valid: true, url: 'http://MyPC:8080' });
  });

  it('rejects empty input, spaces, bad hosts and bad ports with a plain message', () => {
    for (const bad of ['', '   ', 'my pc', 'http://', 'http://exa mple.com', 'ftp://x', 'http://x:99999', 'http://-bad-:1']) {
      const r = normalizeServerUrl(bad);
      expect(r.valid).toBe(false);
      expect(r.valid === false && r.message.length).toBeGreaterThan(5);
    }
  });
});

describe('SettingsRepository', () => {
  it('has defaults, persists updates, and round-trips every key', async () => {
    const store = memoryStore();
    const repo = new SettingsRepository(store);
    expect(await repo.current()).toEqual(DEFAULT_SETTINGS);
    await repo.update(() => ({ serverUrl: 'http://x:1', model: 'm', speakReplies: false, speechRate: 1.4, currentConversationId: 7 }));
    const again = new SettingsRepository(store);
    expect(await again.current()).toEqual({ serverUrl: 'http://x:1', model: 'm', speakReplies: false, speechRate: 1.4, currentConversationId: 7 });
    await again.update((s) => ({ ...s, model: null }));
    expect(await store.getItem('model')).toBeNull();
    expect((await new SettingsRepository(store).current()).model).toBeNull();
  });

  it('uses the Kotlin key names, tells subscribers, and the snapshot is sync', async () => {
    const store = memoryStore();
    const repo = new SettingsRepository(store);
    const seen = jest.fn();
    repo.subscribe(seen);
    expect(repo.snapshot()).toEqual(DEFAULT_SETTINGS);
    await repo.update((s) => ({ ...s, speakReplies: false, currentConversationId: 3 }));
    expect(seen).toHaveBeenCalledTimes(1);
    expect(await store.getItem('speak_replies')).toBe('false');
    expect(await store.getItem('current_conversation_id')).toBe('3');
    expect(repo.snapshot().speakReplies).toBe(false);
  });

  it('ignores unreadable stored numbers', async () => {
    const repo = new SettingsRepository(memoryStore({ speech_rate: 'fast', current_conversation_id: 'x' }));
    expect(await repo.current()).toMatchObject({ speechRate: 1, currentConversationId: null });
  });
});

describe('runStreamProbe (the Hello Ollama measurements)', () => {
  it('records the first chunk, the first token, the chunk count and whether the reply was incremental', async () => {
    let t = 1000;
    const clock = () => (t += 50);
    const { fetch } = fakeFetch(() => streamResponse([ndjson.line('Hel'), ndjson.line('lo'), ndjson.line('!'), ndjson.done()]));
    const seen: string[] = [];
    const r = await runStreamProbe(new OllamaClient({ fetch }), 'http://x:11434', 'm', 'hi', (s) => seen.push(s), clock);
    expect(r.text).toBe('Hello!');
    expect(seen).toEqual(['Hel', 'Hello', 'Hello!']);
    expect(r.networkChunks).toBe(4);
    expect(r.tokens).toBe(3);
    expect(r.firstTokenMs).toBeGreaterThan(0);
    expect(r.firstChunkMs).toBeLessThanOrEqual(r.firstTokenMs!);
    expect(r.spreadMs).toBeGreaterThanOrEqual(100);
    expect(r.incremental).toBe(true);
  });

  it('says "not incremental" when everything arrives in one chunk', async () => {
    const { fetch } = fakeFetch(() => streamResponse([ndjson.line('a') + ndjson.line('b') + ndjson.done()]));
    const r = await runStreamProbe(new OllamaClient({ fetch }), 'http://x:11434', 'm', 'hi', () => undefined);
    expect(r.networkChunks).toBe(1);
    expect(r.incremental).toBe(false);
  });
});
