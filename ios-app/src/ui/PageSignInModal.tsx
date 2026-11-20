import { useMemo, useRef, useState } from 'react';
import { ActivityIndicator, Modal, Pressable, StyleSheet, Text, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { WebView } from 'react-native-webview';
import type { HomeService } from '../home/service';
import type { Theme } from './theme';

/** A browser-like user agent: some sign-in pages (Amazon's) treat a bare WebView differently. */
export const SAFARI_USER_AGENT =
  'Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1';

/**
 * Sign-in on a service's own web page (C48 §5, used by Alexa in C54): Edith never sees the password, only where the page
 * ends up. Fresh cookies every time (`incognito`), and the finish is caught once: the load is stopped and the code handed on.
 */
export function PageSignInModal({ service, theme, onClose }: { service: HomeService; theme: Theme; onClose: () => void }) {
  const page = service.pageSignIn!;
  const handled = useRef(false);
  // Once: every call starts a new sign-in (a new device id and PKCE pair).
  const startUrl = useMemo(() => page.startUrl(), [page]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const finish = async (code: string) => {
    setBusy(true);
    try {
      const result = await page.complete(code);
      if (result.kind === 'ok') onClose();
      else setError(result.kind === 'failed' ? result.message : 'That sign-in needs another step Edith can’t do here.');
    } catch {
      setError('Something went wrong signing in. Try again.');
    } finally {
      setBusy(false);
    }
  };
  /** Returns false to stop the page from loading the finish address. */
  const check = (url: string): boolean => {
    const end = page.isFinish(url);
    if (!end) return true;
    if (!handled.current) {
      handled.current = true;
      void finish(end.code);
    }
    return false;
  };

  return (
    <Modal visible animationType="slide" presentationStyle="pageSheet" onRequestClose={onClose}>
      <SafeAreaView style={[styles.screen, { backgroundColor: theme.background }]}>
        <View style={styles.bar}>
          <Text style={{ color: theme.text, fontSize: 17, fontWeight: '600' }}>{`Sign in to ${service.name}`}</Text>
          <Pressable onPress={onClose} accessibilityRole="button" accessibilityLabel="Cancel sign-in" hitSlop={8}>
            <Text style={{ color: theme.primary }}>Cancel</Text>
          </Pressable>
        </View>
        {error ? <Text style={{ color: theme.error, padding: 12 }}>{error}</Text> : null}
        {busy ? <ActivityIndicator style={{ padding: 12 }} /> : null}
        <WebView
          source={{ uri: startUrl }}
          incognito
          javaScriptEnabled
          userAgent={SAFARI_USER_AGENT}
          onShouldStartLoadWithRequest={(req) => check(req.url)}
          onNavigationStateChange={(nav) => void check(nav.url)}
          style={styles.web}
          accessibilityLabel={`${service.name} sign-in page`}
        />
      </SafeAreaView>
    </Modal>
  );
}

const styles = StyleSheet.create({
  screen: { flex: 1 },
  bar: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', padding: 14 },
  web: { flex: 1 },
});
