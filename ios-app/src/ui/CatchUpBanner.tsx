import { Ionicons } from '@expo/vector-icons';
import { Pressable, StyleSheet, Text, View } from 'react-native';
import { useStore } from '../core/services';
import type { Store } from '../core/store';
import type { CatchUpSummary } from '../alerts/loop';
import type { Theme } from './theme';

/** "While you were away" on the Home tab (C72 §3): one line, no pictures (a snapshot taken now would not be of the event); a tap opens the Clips list. */
export function CatchUpBanner({ store, onOpen, onDismiss, theme }: { store: Store<CatchUpSummary | null>; onOpen: () => void; onDismiss: () => void; theme: Theme }) {
  const summary = useStore(store);
  if (!summary) return null;
  return (
    <View style={[styles.banner, { backgroundColor: theme.surface, borderColor: theme.border }]} accessibilityLabel="While you were away">
      <Pressable onPress={onOpen} accessibilityRole="button" accessibilityLabel={`${summary.text}. Open clips`} style={styles.text}>
        <Text style={{ color: theme.text, fontWeight: '600' }}>While you were away</Text>
        <Text style={{ color: theme.text }}>{summary.text}</Text>
      </Pressable>
      <Pressable onPress={onDismiss} accessibilityRole="button" accessibilityLabel="Dismiss" hitSlop={8}>
        <Ionicons name="close" size={20} color={theme.textMuted} />
      </Pressable>
    </View>
  );
}

const styles = StyleSheet.create({
  banner: { flexDirection: 'row', alignItems: 'center', gap: 10, borderWidth: StyleSheet.hairlineWidth, borderRadius: 12, padding: 12, marginHorizontal: 16, marginBottom: 8 },
  text: { flex: 1, gap: 2 },
});
