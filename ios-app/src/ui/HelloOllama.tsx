import { useEffect, useRef, useState } from 'react';
import { Pressable, ScrollView, StyleSheet, Text, TextInput, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { toAppError } from '../core/errors';
import { kvStore } from '../core/kv';
import { ollamaClient } from '../core/network';
import { runStreamProbe, type ProbeResult } from '../ollama/probe';
import { chooseDefaultModel } from '../settings/modelSelection';
import { normalizeServerUrl, DEFAULT_SERVER_URL } from '../settings/serverUrl';
import { SettingsRepository } from '../settings/settings';
import { ModelCatalog } from '../ollama/catalog';

const settings = new SettingsRepository(kvStore);
const catalog = new ModelCatalog(ollamaClient);

/**
 * C40 Stage 1, step 3: "Hello Ollama on the iPhone". Streams one reply from the owner's Ollama with `expo/fetch` and
 * records the time to the first token, whether chunks arrive incrementally, and the exact error if iOS blocks the
 * connection. The server address is a setting, saved on this phone.
 */
export function HelloOllama() {
  const [server, setServer] = useState(DEFAULT_SERVER_URL);
  const [prompt, setPrompt] = useState('Say hello and tell me a short joke.');
  const [text, setText] = useState('');
  const [result, setResult] = useState<ProbeResult | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const abort = useRef<AbortController | null>(null);

  useEffect(() => {
    void settings.current().then((s) => setServer(s.serverUrl));
  }, []);

  async function run() {
    const url = normalizeServerUrl(server);
    if (!url.valid) {
      setError(url.message);
      return;
    }
    await settings.update((s) => ({ ...s, serverUrl: url.url }));
    setServer(url.url);
    setBusy(true);
    setError(null);
    setResult(null);
    setText('');
    abort.current = new AbortController();
    try {
      const models = await catalog.chatModels(url.url);
      const model = chooseDefaultModel(models);
      if (!model) throw new Error('No chat models on the server.');
      const r = await runStreamProbe(ollamaClient, url.url, model.name, prompt, setText, Date.now, abort.current.signal);
      setResult({ ...r, text: `[${model.name}] ${r.text}` });
    } catch (e) {
      const err = toAppError(e);
      const cause = err.cause instanceof Error ? `${err.cause.name}: ${err.cause.message}` : err.cause ? String(err.cause) : '';
      setError(`${err.message}\n\nExact error: ${cause || `${err.name}: ${err.message}`}`);
    } finally {
      setBusy(false);
    }
  }

  return (
    <SafeAreaView style={styles.screen}>
      <ScrollView contentContainerStyle={styles.content} keyboardShouldPersistTaps="handled">
        <Text style={styles.title}>Hello Ollama</Text>
        <Text style={styles.label}>Server address</Text>
        <TextInput
          style={styles.input}
          value={server}
          onChangeText={setServer}
          autoCapitalize="none"
          autoCorrect={false}
          keyboardType="url"
          accessibilityLabel="Server address"
        />
        <Text style={styles.label}>Prompt</Text>
        <TextInput style={styles.input} value={prompt} onChangeText={setPrompt} multiline accessibilityLabel="Prompt" />
        <Pressable
          style={[styles.button, busy && styles.buttonBusy]}
          onPress={busy ? () => abort.current?.abort() : run}
          accessibilityRole="button"
        >
          <Text style={styles.buttonText}>{busy ? 'Stop' : 'Send'}</Text>
        </Pressable>
        {error ? <Text style={styles.error} selectable>{error}</Text> : null}
        <Text style={styles.reply} selectable>{text}</Text>
        {result ? (
          <View style={styles.metrics}>
            <Text style={styles.metric}>First network chunk: {result.firstChunkMs ?? '—'} ms</Text>
            <Text style={styles.metric}>First token: {result.firstTokenMs ?? '—'} ms</Text>
            <Text style={styles.metric}>Whole reply: {result.totalMs} ms</Text>
            <Text style={styles.metric}>Network chunks: {result.networkChunks} · tokens: {result.tokens}</Text>
            <Text style={styles.metric}>Chunk spread: {result.spreadMs} ms</Text>
            <Text style={styles.metric}>Incremental: {result.incremental ? 'yes' : 'NO — arrived all at once'}</Text>
          </View>
        ) : null}
      </ScrollView>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  screen: { flex: 1, backgroundColor: '#fff' },
  content: { padding: 16, gap: 8 },
  title: { fontSize: 24, fontWeight: '700' },
  label: { fontSize: 13, color: '#555', marginTop: 8 },
  input: { borderWidth: 1, borderColor: '#bbb', borderRadius: 8, padding: 10, fontSize: 16 },
  button: { backgroundColor: '#6b3fa0', padding: 14, borderRadius: 10, alignItems: 'center', marginTop: 12 },
  buttonBusy: { backgroundColor: '#a33' },
  buttonText: { color: '#fff', fontSize: 16, fontWeight: '600' },
  error: { color: '#b00020', marginTop: 12 },
  reply: { fontSize: 16, marginTop: 12 },
  metrics: { marginTop: 16, padding: 12, backgroundColor: '#f2eefa', borderRadius: 8, gap: 4 },
  metric: { fontSize: 14 },
});
