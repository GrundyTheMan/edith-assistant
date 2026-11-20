import Slider from '@react-native-community/slider';
import Constants from 'expo-constants';
import { useCallback, useEffect, useRef, useState } from 'react';
import { ActivityIndicator, Pressable, ScrollView, StyleSheet, Switch, Text, TextInput, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { toAppError } from '../core/errors';
import { ollamaClient } from '../core/network';
import { useServices } from '../core/services';
import type { ModelInfo } from '../ollama/types';
import { capabilityLine } from '../ollama/catalog';
import { normalizeServerUrl } from '../settings/serverUrl';
import type { Settings } from '../settings/settings';
import { useTheme } from './theme';

type Connection = { state: 'idle' } | { state: 'testing' } | { state: 'ok'; text: string } | { state: 'failed'; text: string };
type Models = { state: 'loading' } | { state: 'loaded'; list: ModelInfo[] } | { state: 'failed'; text: string };

/** Settings (C04 §3, C13 §2): server address and Test connection, the model, speak replies, speech speed. */
export function SettingsScreen() {
  const theme = useTheme();
  const { settings, catalog, speech } = useServices();
  const [saved, setSaved] = useState<Settings>(settings.snapshot());
  const [field, setField] = useState(saved.serverUrl);
  const [connection, setConnection] = useState<Connection>({ state: 'idle' });
  const [models, setModels] = useState<Models>({ state: 'loading' });
  const [version, setVersion] = useState<string | null>(null);
  const [rate, setRate] = useState(saved.speechRate);
  const seq = useRef(0);

  useEffect(() => settings.subscribe(setSaved), [settings]);

  const normalized = normalizeServerUrl(field);
  const unsaved = normalized.valid ? normalized.url !== saved.serverUrl : field.trim() !== saved.serverUrl;

  const loadModels = useCallback(
    async (url: string) => {
      const mine = ++seq.current;
      setModels({ state: 'loading' });
      try {
        catalog.invalidate();
        const list = await catalog.chatModels(url);
        if (mine !== seq.current) return;
        setModels({ state: 'loaded', list });
        void ollamaClient.version(url).then((v) => mine === seq.current && setVersion(v), () => undefined);
      } catch (e) {
        if (mine === seq.current) setModels({ state: 'failed', text: toAppError(e).message });
      }
    },
    [catalog],
  );

  // Reload 600 ms after the address changes, and on entry.
  useEffect(() => {
    if (!normalized.valid) return;
    const url = normalized.url;
    const t = setTimeout(() => void loadModels(url), 600);
    return () => clearTimeout(t);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [normalized.valid && normalized.url, loadModels]);

  const save = async () => {
    if (!normalized.valid) return;
    await settings.update((s) => ({ ...s, serverUrl: normalized.url }));
    setField(normalized.url);
  };

  const test = async () => {
    if (!normalized.valid) {
      setConnection({ state: 'failed', text: normalized.message });
      return;
    }
    setConnection({ state: 'testing' });
    try {
      const v = await ollamaClient.version(normalized.url);
      setConnection({ state: 'ok', text: `Connected — Ollama ${v}` });
    } catch (e) {
      setConnection({ state: 'failed', text: toAppError(e).message });
    }
  };

  const pick = (name: string) => void settings.update((s) => ({ ...s, model: name }));
  const savedMissing = models.state === 'loaded' && saved.model !== null && !models.list.some((m) => m.name === saved.model);

  return (
    <SafeAreaView edges={['top']} style={[styles.screen, { backgroundColor: theme.background }]}>
      <ScrollView contentContainerStyle={styles.content} keyboardShouldPersistTaps="handled" contentInsetAdjustmentBehavior="automatic">
        <Text style={[styles.heading, { color: theme.text }]}>Settings</Text>

        <Section title="Server" theme={theme}>
          <TextInput
            style={[styles.input, { color: theme.text, borderColor: theme.border, backgroundColor: theme.surface }]}
            value={field}
            onChangeText={setField}
            autoCapitalize="none"
            autoCorrect={false}
            keyboardType="url"
            accessibilityLabel="Server address"
          />
          <Text style={[styles.note, { color: theme.textMuted }]}>
            Your PC&apos;s address on the home Wi-Fi, e.g. http://192.168.4.82:11434
          </Text>
          {!normalized.valid && field.trim() ? <Text style={[styles.note, { color: theme.error }]}>{normalized.message}</Text> : null}
          <View style={styles.buttons}>
            <Button label="Save" onPress={save} disabled={!unsaved || !normalized.valid} theme={theme} />
            <Button label="Test connection" onPress={test} theme={theme} />
          </View>
          {unsaved ? <Text style={[styles.note, { color: theme.textMuted }]}>Unsaved changes</Text> : null}
          {connection.state === 'testing' ? <ActivityIndicator /> : null}
          {connection.state === 'ok' ? <Text style={{ color: theme.primary }}>{connection.text}</Text> : null}
          {connection.state === 'failed' ? <Text style={{ color: theme.error }}>{connection.text}</Text> : null}
        </Section>

        <Section title="Model" theme={theme}>
          {models.state === 'loading' ? <Text style={{ color: theme.textMuted }}>Loading models…</Text> : null}
          {models.state === 'failed' ? (
            <View style={{ gap: 8 }}>
              <Text style={{ color: theme.error }}>{models.text}</Text>
              <Button label="Retry" onPress={() => normalized.valid && void loadModels(normalized.url)} theme={theme} />
            </View>
          ) : null}
          {models.state === 'loaded' && models.list.length === 0 ? (
            <Text style={{ color: theme.textMuted }}>No chat models installed on this server</Text>
          ) : null}
          {savedMissing ? <Text style={{ color: theme.error }}>Your saved model {saved.model} isn&apos;t installed on this server.</Text> : null}
          {models.state === 'loaded'
            ? models.list.map((m) => (
                <Pressable
                  key={m.name}
                  onPress={() => pick(m.name)}
                  accessibilityRole="radio"
                  accessibilityState={{ selected: saved.model === m.name }}
                  style={[styles.modelRow, { borderColor: saved.model === m.name ? theme.primary : theme.border }]}
                >
                  <Text style={{ color: theme.text, fontSize: 16, fontWeight: saved.model === m.name ? '700' : '400' }}>{m.name}</Text>
                  {capabilityLine(m) ? <Text style={[styles.note, { color: theme.textMuted }]}>{capabilityLine(m)}</Text> : null}
                </Pressable>
              ))
            : null}
        </Section>

        <Section title="Voice" theme={theme}>
          <View style={styles.switchRow}>
            <View style={styles.flex}>
              <Text style={{ color: theme.text, fontSize: 16 }}>Speak Edith&apos;s replies</Text>
              <Text style={[styles.note, { color: theme.textMuted }]}>Reads each new reply aloud with the iPhone&apos;s voice</Text>
            </View>
            <Switch value={saved.speakReplies} onValueChange={(v) => void settings.update((s) => ({ ...s, speakReplies: v }))} />
          </View>
          <Text style={{ color: theme.text }}>Speech speed: {rate.toFixed(1)}×</Text>
          <Slider
            minimumValue={0.6}
            maximumValue={1.6}
            step={0.1}
            value={rate}
            onValueChange={setRate}
            onSlidingComplete={(v) => void settings.update((s) => ({ ...s, speechRate: Math.round(v * 10) / 10 }))}
            accessibilityLabel="Speech speed"
          />
          <Button label="Test voice" onPress={() => speech.test()} theme={theme} />
        </Section>

        <Section title="About" theme={theme}>
          <Text style={{ color: theme.text }}>Edith {Constants.expoConfig?.version ?? ''}</Text>
          {version ? <Text style={{ color: theme.textMuted }}>Ollama {version}</Text> : null}
        </Section>
      </ScrollView>
    </SafeAreaView>
  );
}

function Section({ title, children, theme }: { title: string; children: React.ReactNode; theme: ReturnType<typeof useTheme> }) {
  return (
    <View style={styles.section}>
      <Text style={[styles.sectionTitle, { color: theme.textMuted }]}>{title.toUpperCase()}</Text>
      <View style={[styles.card, { backgroundColor: theme.surface }]}>{children}</View>
    </View>
  );
}

function Button({ label, onPress, disabled, theme }: { label: string; onPress: () => void; disabled?: boolean; theme: ReturnType<typeof useTheme> }) {
  return (
    <Pressable
      onPress={onPress}
      disabled={disabled}
      accessibilityRole="button"
      style={[styles.button, { backgroundColor: theme.primary }, disabled && { opacity: 0.4 }]}
    >
      <Text style={{ color: theme.onPrimary, fontWeight: '600' }}>{label}</Text>
    </Pressable>
  );
}

const styles = StyleSheet.create({
  screen: { flex: 1 },
  flex: { flex: 1 },
  content: { padding: 16, gap: 16 },
  heading: { fontSize: 28, fontWeight: '700' },
  section: { gap: 6 },
  sectionTitle: { fontSize: 12, fontWeight: '600', marginLeft: 4 },
  card: { borderRadius: 12, padding: 12, gap: 10 },
  input: { borderWidth: 1, borderRadius: 10, padding: 10, fontSize: 16 },
  note: { fontSize: 13 },
  buttons: { flexDirection: 'row', gap: 10 },
  button: { paddingHorizontal: 16, paddingVertical: 10, borderRadius: 10, alignSelf: 'flex-start' },
  modelRow: { borderWidth: 1.5, borderRadius: 10, padding: 10, gap: 2 },
  switchRow: { flexDirection: 'row', alignItems: 'center', gap: 12 },
});
