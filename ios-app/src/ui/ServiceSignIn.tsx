import { useState } from 'react';
import { ActivityIndicator, KeyboardAvoidingView, Modal, Pressable, ScrollView, StyleSheet, Text, TextInput, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import type { HomeService, SignInField, SignInForm } from '../home/service';
import type { Theme } from './theme';

const contentType = (f: SignInField): 'username' | 'password' | 'oneTimeCode' | 'none' =>
  f.secret ? 'password' : f.keyboard === 'number' ? 'oneTimeCode' : f.keyboard === 'email' ? 'username' : 'none';

/**
 * The shared sign-in form (C48 §5): the service's fields, a spinner while it works, the service's own words when it
 * fails, and a second step for a code. The answers live in this component's state only: never written to settings, logs or
 * anywhere else, and cleared when the form closes.
 */
export function ServiceSignIn({ service, again, theme, onClose }: { service: HomeService; again: boolean; theme: Theme; onClose: () => void }) {
  const [form, setForm] = useState<SignInForm>(service.signInForm());
  const [values, setValues] = useState<Record<string, string>>({});
  const [first, setFirst] = useState<Record<string, string> | null>(null); // the first step's answers, kept for the code step
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const missing = form.fields.some((f) => !f.optional && !(values[f.key] ?? '').trim());
  const submit = async () => {
    setBusy(true);
    setError(null);
    try {
      const all = { ...(first ?? {}), ...values };
      const result = await service.signIn(all);
      if (result.kind === 'ok') {
        setValues({});
        onClose();
      } else if (result.kind === 'needs_code') {
        setFirst(all);
        setForm(result.form);
        setValues({});
      } else {
        setError(result.message);
      }
    } catch {
      setError("Something went wrong signing in. Try again."); // never the thrown text: it could hold a secret
    } finally {
      setBusy(false);
    }
  };
  const close = () => {
    setValues({});
    setFirst(null);
    onClose();
  };

  return (
    <Modal visible animationType="slide" presentationStyle="pageSheet" onRequestClose={close}>
      <SafeAreaView style={[styles.screen, { backgroundColor: theme.background }]}>
        <KeyboardAvoidingView behavior="padding" style={styles.flex}>
          <ScrollView contentContainerStyle={styles.content} keyboardShouldPersistTaps="handled">
            <Text style={[styles.title, { color: theme.text }]}>{again && !first ? `${form.title} again` : form.title}</Text>
            {form.intro ? <Text style={{ color: theme.textMuted }}>{form.intro}</Text> : null}
            {form.fields.map((f) => (
              <View key={f.key} style={{ gap: 4 }}>
                <Text style={{ color: theme.text }}>{f.label}</Text>
                <TextInput
                  value={values[f.key] ?? ''}
                  onChangeText={(t) => setValues((v) => ({ ...v, [f.key]: t }))}
                  secureTextEntry={f.secret}
                  autoCapitalize="none"
                  autoCorrect={false}
                  keyboardType={f.keyboard === 'email' ? 'email-address' : f.keyboard === 'number' ? 'number-pad' : f.keyboard === 'url' ? 'url' : 'default'}
                  textContentType={contentType(f)}
                  accessibilityLabel={f.label}
                  style={[styles.input, { color: theme.text, borderColor: theme.border, backgroundColor: theme.surface }]}
                />
                {f.hint ? <Text style={[styles.hint, { color: theme.textMuted }]}>{f.hint}</Text> : null}
              </View>
            ))}
            {error ? <Text style={{ color: theme.error }}>{error}</Text> : null}
            {busy ? <ActivityIndicator /> : null}
            <View style={styles.buttons}>
              <Pressable onPress={close} accessibilityRole="button" accessibilityLabel="Cancel sign-in" style={[styles.button, { borderColor: theme.border, borderWidth: 1 }]}>
                <Text style={{ color: theme.text }}>Cancel</Text>
              </Pressable>
              <Pressable
                onPress={() => void submit()}
                disabled={busy || missing}
                accessibilityRole="button"
                accessibilityLabel={form.submitLabel ?? 'Sign in'}
                style={[styles.button, { backgroundColor: theme.primary }, (busy || missing) && { opacity: 0.4 }]}
              >
                <Text style={{ color: theme.onPrimary, fontWeight: '600' }}>{form.submitLabel ?? 'Sign in'}</Text>
              </Pressable>
            </View>
          </ScrollView>
        </KeyboardAvoidingView>
      </SafeAreaView>
    </Modal>
  );
}

const styles = StyleSheet.create({
  screen: { flex: 1 },
  flex: { flex: 1 },
  content: { padding: 20, gap: 14 },
  title: { fontSize: 22, fontWeight: '700' },
  input: { borderWidth: 1, borderRadius: 10, padding: 12, fontSize: 16 },
  hint: { fontSize: 13 },
  buttons: { flexDirection: 'row', gap: 12, justifyContent: 'flex-end' },
  button: { paddingHorizontal: 18, paddingVertical: 12, borderRadius: 12 },
});
