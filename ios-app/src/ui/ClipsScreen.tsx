import { Ionicons } from '@expo/vector-icons';
import { useCallback, useEffect, useRef, useState } from 'react';
import { ActivityIndicator, FlatList, Modal, Pressable, RefreshControl, StyleSheet, Text, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { clipKindWords, clipLength, clipTime, emptyClips, loadOlder, noPlanText, type ClipsState } from '../home/clips';
import type { Home } from '../home/home';
import type { DeviceClip } from '../home/model';
import { ClipPlayerModal } from './ClipPlayer';
import type { Theme } from './theme';

/** The Clips list (C53): newest first, a quiet window loads the next older one, pull to refresh; a tap plays the clip. */
export function ClipsScreen({ visible, onClose, home, theme, nowFn = Date.now }: { visible: boolean; onClose: () => void; home: Home; theme: Theme; nowFn?: () => number }) {
  const [state, setState] = useState<ClipsState>(() => emptyClips(nowFn()));
  const [loading, setLoading] = useState(false);
  const [playing, setPlaying] = useState<DeviceClip | null>(null);
  const busy = useRef(false);

  const load = useCallback(
    async (from: ClipsState) => {
      if (busy.current) return;
      busy.current = true;
      setLoading(true);
      try {
        setState(await loadOlder(home, from, nowFn()));
      } finally {
        busy.current = false;
        setLoading(false);
      }
    },
    [home, nowFn],
  );
  const fresh = useCallback(() => void load(emptyClips(nowFn())), [load, nowFn]);
  useEffect(() => {
    if (visible) fresh();
  }, [visible, fresh]);

  const now = nowFn();
  return (
    <Modal visible={visible} animationType="slide" onRequestClose={onClose}>
      <SafeAreaView style={[styles.screen, { backgroundColor: theme.background }]}>
        <View style={styles.header}>
          <Text style={[styles.title, { color: theme.text }]} accessibilityRole="header">
            Clips
          </Text>
          <Pressable onPress={onClose} accessibilityRole="button" accessibilityLabel="Close" hitSlop={12}>
            <Ionicons name="close" size={26} color={theme.text} />
          </Pressable>
        </View>
        <FlatList
          data={state.clips}
          keyExtractor={(c) => `${c.backend}:${c.id}`}
          refreshControl={<RefreshControl refreshing={loading && state.clips.length === 0} onRefresh={fresh} />}
          onEndReached={() => void load(state)}
          onEndReachedThreshold={0.4}
          ListHeaderComponent={
            <View>
              {state.noPlan.map((n) => (
                <Text key={n} style={[styles.note, { color: theme.textMuted }]}>
                  {noPlanText(n)}
                </Text>
              ))}
              {state.problems.map((p) => (
                <Text key={p} style={[styles.note, { color: theme.error }]}>
                  {p}
                </Text>
              ))}
            </View>
          }
          ListEmptyComponent={loading ? undefined : <Text style={[styles.empty, { color: theme.textMuted }]}>{state.done ? 'No clips in the last 60 days.' : 'No clips yet.'}</Text>}
          ListFooterComponent={loading ? <ActivityIndicator style={styles.spinner} color={theme.textMuted} /> : undefined}
          renderItem={({ item }) => (
            <Pressable
              onPress={() => setPlaying(item)}
              accessibilityRole="button"
              accessibilityLabel={`${item.deviceName}, ${clipKindWords(item)}, ${clipTime(item.at, now)}`}
              style={[styles.row, { borderColor: theme.border }]}
            >
              <Ionicons name="play-circle-outline" size={28} color={theme.primary} />
              <View style={styles.rowText}>
                <Text style={{ color: theme.text, fontSize: 16 }}>{item.deviceName}</Text>
                <Text style={{ color: theme.textMuted }}>
                  {clipKindWords(item)} · {clipTime(item.at, now)}
                  {clipLength(item) ? ` · ${clipLength(item)}` : ''}
                </Text>
              </View>
            </Pressable>
          )}
        />
        <ClipPlayerModal clip={playing} home={home} now={now} onClose={() => setPlaying(null)} />
      </SafeAreaView>
    </Modal>
  );
}

const styles = StyleSheet.create({
  screen: { flex: 1 },
  header: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', paddingHorizontal: 16, paddingVertical: 12 },
  title: { fontSize: 24, fontWeight: '700' },
  row: { flexDirection: 'row', alignItems: 'center', gap: 12, paddingHorizontal: 16, paddingVertical: 12, borderBottomWidth: StyleSheet.hairlineWidth },
  rowText: { flex: 1 },
  note: { paddingHorizontal: 16, paddingVertical: 8 },
  empty: { textAlign: 'center', padding: 32 },
  spinner: { padding: 16 },
});
