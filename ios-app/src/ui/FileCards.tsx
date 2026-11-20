import { Ionicons } from '@expo/vector-icons';
import { Modal, Pressable, ScrollView, StyleSheet, Text, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import type { Theme } from './theme';

/** A file Edith wrote, as the bubble shows it: its text while it is kept, else null ("name — no longer kept"). */
export interface BubbleFile {
  id: number;
  name: string;
  text: string | null;
}

const preview = (text: string) => text.split('\n').slice(0, 3).join('\n');

/** File cards in Edith's bubble (C16 §4, C56): an icon, the name, the first 3 lines, and View / Copy / Share. */
export function FileCards({
  items,
  theme,
  onView,
  onCopy,
  onShare,
}: {
  items: BubbleFile[];
  theme: Theme;
  onView: (f: BubbleFile) => void;
  onCopy: (f: BubbleFile) => void;
  onShare: (f: BubbleFile) => void;
}) {
  if (items.length === 0) return null;
  return (
    <View style={{ gap: 8, marginBottom: 6 }}>
      {items.map((f) =>
        f.text === null ? (
          <Text key={f.id} style={{ color: theme.textMuted, fontSize: 13 }}>
            {f.name} — no longer kept
          </Text>
        ) : (
          <View key={f.id} style={[styles.card, { borderColor: theme.border, backgroundColor: theme.background }]} accessibilityLabel={`File ${f.name}`}>
            <View style={styles.title}>
              <Ionicons name="document-text-outline" size={18} color={theme.text} />
              <Text numberOfLines={1} style={{ color: theme.text, fontWeight: '600', flex: 1 }}>
                {f.name}
              </Text>
            </View>
            <Text numberOfLines={3} style={[styles.preview, { color: theme.textMuted }]}>
              {preview(f.text)}
            </Text>
            <View style={styles.buttons}>
              {(
                [
                  ['View', () => onView(f)],
                  ['Copy', () => onCopy(f)],
                  ['Share', () => onShare(f)],
                ] as const
              ).map(([label, run]) => (
                <Pressable key={label} onPress={run} accessibilityRole="button" accessibilityLabel={`${label} ${f.name}`} hitSlop={6}>
                  <Text style={{ color: theme.primary, fontWeight: '600' }}>{label}</Text>
                </Pressable>
              ))}
            </View>
          </View>
        ),
      )}
    </View>
  );
}

/** The whole file: selectable and scrollable. */
export function FileViewer({ file, theme, onClose }: { file: BubbleFile | null; theme: Theme; onClose: () => void }) {
  return (
    <Modal visible={file !== null} animationType="slide" presentationStyle="pageSheet" onRequestClose={onClose}>
      <SafeAreaView style={[styles.screen, { backgroundColor: theme.background }]}>
        <View style={styles.bar}>
          <Text style={{ color: theme.text, fontSize: 17, fontWeight: '600', flex: 1 }} numberOfLines={1}>
            {file?.name}
          </Text>
          <Pressable onPress={onClose} accessibilityRole="button" accessibilityLabel="Close file" hitSlop={8}>
            <Text style={{ color: theme.primary, fontWeight: '600' }}>Done</Text>
          </Pressable>
        </View>
        <ScrollView contentContainerStyle={{ padding: 16 }}>
          <Text selectable style={[styles.full, { color: theme.text }]}>
            {file?.text ?? ''}
          </Text>
        </ScrollView>
      </SafeAreaView>
    </Modal>
  );
}

const styles = StyleSheet.create({
  card: { borderWidth: 1, borderRadius: 12, padding: 10, gap: 6 },
  title: { flexDirection: 'row', alignItems: 'center', gap: 6 },
  preview: { fontFamily: 'Menlo', fontSize: 12 },
  buttons: { flexDirection: 'row', gap: 16 },
  screen: { flex: 1 },
  bar: { flexDirection: 'row', alignItems: 'center', padding: 14, gap: 12 },
  full: { fontFamily: 'Menlo', fontSize: 13, lineHeight: 19 },
});
