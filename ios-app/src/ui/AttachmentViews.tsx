import { Ionicons } from '@expo/vector-icons';
import { Image, Modal, Pressable, ScrollView, StyleSheet, Text, View } from 'react-native';
import type { PendingAttachment } from '../attachments/attachments';
import type { Theme } from './theme';

const dataUri = (base64: string) => `data:image/jpeg;base64,${base64}`;

/** An attachment of a stored message as the bubble shows it: the picture if it is still held, else its name. */
export interface BubbleAttachment {
  id: number;
  fileName: string;
  /** Base64 JPEG, or null after a restart ("no longer available"). */
  base64: string | null;
}

/** Pending photos above the input: a thumbnail with a Remove button each. */
export function PendingChips({ items, onRemove, theme }: { items: PendingAttachment[]; onRemove: (localId: string) => void; theme: Theme }) {
  if (items.length === 0) return null;
  return (
    <ScrollView horizontal showsHorizontalScrollIndicator={false} contentContainerStyle={styles.chips} keyboardShouldPersistTaps="handled">
      {items.map((a) => (
        <View key={a.localId} style={styles.chip}>
          <Image source={{ uri: dataUri(a.base64) }} style={[styles.chipImage, { backgroundColor: theme.surface }]} accessibilityLabel={a.fileName} />
          <Pressable
            onPress={() => onRemove(a.localId)}
            accessibilityRole="button"
            accessibilityLabel={`Remove ${a.fileName}`}
            hitSlop={8}
            style={[styles.remove, { backgroundColor: theme.text }]}
          >
            <Ionicons name="close" size={14} color={theme.background} />
          </Pressable>
        </View>
      ))}
    </ScrollView>
  );
}

/** Thumbnails in the owner's bubble, above the text; a tap opens the picture full screen. */
export function BubbleAttachments({ items, onOpen, theme, tone = 'user' }: { items: BubbleAttachment[]; onOpen: (base64: string) => void; theme: Theme; tone?: 'user' | 'edith' }) {
  const ink = tone === 'edith' ? theme.onEdithBubble : theme.onPrimary;
  if (items.length === 0) return null;
  return (
    <View style={styles.bubbleRow}>
      {items.map((a) =>
        a.base64 ? (
          <Pressable key={a.id} onPress={() => onOpen(a.base64!)} accessibilityRole="imagebutton" accessibilityLabel={`Open ${a.fileName}`}>
            <Image source={{ uri: dataUri(a.base64) }} style={styles.thumb} />
          </Pressable>
        ) : (
          <View key={a.id} style={[styles.missing, { borderColor: ink }]}>
            <Ionicons name="image-outline" size={18} color={ink} />
            <Text style={{ color: ink, fontSize: 13 }} numberOfLines={1}>
              {a.fileName} — no longer available
            </Text>
          </View>
        ),
      )}
    </View>
  );
}

/** The picture full screen on black; Close or a tap on the picture dismisses it. */
export function ImageViewer({ base64, onClose }: { base64: string | null; onClose: () => void }) {
  return (
    <Modal visible={base64 !== null} transparent animationType="fade" onRequestClose={onClose} statusBarTranslucent>
      <View style={styles.viewer}>
        {base64 ? (
          <Pressable style={styles.viewerPress} onPress={onClose} accessibilityRole="button" accessibilityLabel="Close picture">
            <Image source={{ uri: dataUri(base64) }} style={styles.viewerImage} resizeMode="contain" />
          </Pressable>
        ) : null}
        <Pressable onPress={onClose} accessibilityRole="button" accessibilityLabel="Close" style={styles.viewerClose} hitSlop={12}>
          <Ionicons name="close" size={28} color="#fff" />
        </Pressable>
      </View>
    </Modal>
  );
}

const styles = StyleSheet.create({
  chips: { gap: 8, paddingBottom: 8, paddingHorizontal: 4 },
  chip: { width: 64, height: 64 },
  chipImage: { width: 64, height: 64, borderRadius: 12 },
  remove: { position: 'absolute', top: -6, right: -6, width: 22, height: 22, borderRadius: 11, alignItems: 'center', justifyContent: 'center' },
  bubbleRow: { flexDirection: 'row', flexWrap: 'wrap', gap: 6, marginBottom: 6 },
  thumb: { width: 160, height: 160, borderRadius: 12 },
  missing: { flexDirection: 'row', alignItems: 'center', gap: 6, borderWidth: 1, borderRadius: 12, paddingHorizontal: 10, paddingVertical: 8, opacity: 0.8 },
  viewer: { flex: 1, backgroundColor: '#000' },
  viewerPress: { flex: 1, justifyContent: 'center' },
  viewerImage: { width: '100%', height: '100%' },
  viewerClose: { position: 'absolute', top: 56, right: 20, width: 40, height: 40, borderRadius: 20, backgroundColor: 'rgba(255,255,255,0.2)', alignItems: 'center', justifyContent: 'center' },
});
