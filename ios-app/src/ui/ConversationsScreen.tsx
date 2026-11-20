import { Ionicons } from '@expo/vector-icons';
import { useRouter } from 'expo-router';
import { Alert, FlatList, Pressable, StyleSheet, Text, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useServices, useStore } from '../core/services';
import { useRepoQuery } from '../core/useRepoQuery';
import { stripMarkdown } from './markdown';
import { listTime } from './messageTimes';
import { useTheme } from './theme';
import { useNow } from './useNow';

/** The conversation list (D6): newest first; tap to open, long-press to rename or delete. */
export function ConversationsScreen() {
  const theme = useTheme();
  const router = useRouter();
  const { repo, current, engine } = useServices();
  const list = useRepoQuery(repo, () => repo.conversations(), [], []);
  const openId = useStore(current.id);
  const engineState = useStore(engine.state);
  const replyingIn = engineState.kind === 'working' ? engineState.conversationId : null;
  const now = useNow();

  const open = async (id: number) => {
    await current.select(id);
    router.back();
  };
  const rename = (id: number, title: string) =>
    Alert.prompt('Rename chat', undefined, (t) => void (t?.trim() && repo.rename(id, t)), 'plain-text', title);
  const remove = (id: number) =>
    Alert.alert('Delete this chat?', "This can't be undone.", [
      { text: 'Cancel', style: 'cancel' },
      { text: 'Delete', style: 'destructive', onPress: () => void current.delete(id) },
    ]);
  const menu = (id: number, title: string) =>
    Alert.alert(title || 'New chat', undefined, [
      { text: 'Rename', onPress: () => rename(id, title) },
      { text: 'Delete', style: 'destructive', onPress: () => remove(id) },
      { text: 'Cancel', style: 'cancel' },
    ]);

  return (
    <SafeAreaView style={[styles.screen, { backgroundColor: theme.background }]}>
      <View style={[styles.header, { borderBottomColor: theme.border }]}>
        <Text style={[styles.heading, { color: theme.text }]}>Conversations</Text>
        <Pressable onPress={() => router.back()} accessibilityRole="button" accessibilityLabel="Close" hitSlop={8}>
          <Ionicons name="close" size={26} color={theme.text} />
        </Pressable>
      </View>
      <FlatList
        data={list}
        keyExtractor={(c) => String(c.id)}
        renderItem={({ item }) => (
          <Pressable
            onPress={() => void open(item.id)}
            onLongPress={() => menu(item.id, item.title)}
            accessibilityRole="button"
            style={[styles.row, item.id === openId && { backgroundColor: theme.surface }]}
          >
            <View style={styles.flex}>
              <Text numberOfLines={1} style={[styles.title, { color: theme.text }]}>
                {item.title || 'New chat'}
              </Text>
              <Text numberOfLines={1} style={[styles.preview, { color: theme.textMuted }]}>
                {stripMarkdown(item.preview)}
              </Text>
            </View>
            {replyingIn === item.id ? <Ionicons name="ellipsis-horizontal-circle" size={18} color={theme.primary} /> : null}
            <Text style={[styles.time, { color: theme.textMuted }]}>{listTime(item.updatedAt, now)}</Text>
          </Pressable>
        )}
      />
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  screen: { flex: 1 },
  flex: { flex: 1 },
  header: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', padding: 16, borderBottomWidth: StyleSheet.hairlineWidth },
  heading: { fontSize: 20, fontWeight: '700' },
  row: { flexDirection: 'row', alignItems: 'center', gap: 10, paddingHorizontal: 16, paddingVertical: 12 },
  title: { fontSize: 16, fontWeight: '600' },
  preview: { fontSize: 14, marginTop: 2 },
  time: { fontSize: 13 },
});
