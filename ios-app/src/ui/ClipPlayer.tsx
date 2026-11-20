import { Ionicons } from '@expo/vector-icons';
import { useVideoPlayer, VideoView } from 'expo-video';
import { useCallback, useEffect, useState } from 'react';
import { ActivityIndicator, Modal, Pressable, StyleSheet, Text, View } from 'react-native';
import { clipKindWords, clipTime } from '../home/clips';
import type { Home } from '../home/home';
import type { ClipSource, DeviceClip } from '../home/model';

/** The player itself. It exists only while a clip is open: closing unmounts it, which releases the player, so a clip never plays on behind the list (C53 §2). */
export function ClipSurface({ source, onFailed }: { source: ClipSource; onFailed: (why: string) => void }) {
  const player = useVideoPlayer({ uri: source.uri, headers: source.headers }, (p) => {
    p.play();
  });
  useEffect(() => {
    const sub = player.addListener('statusChange', (e: { status: string; error?: { message?: string } }) => {
      if (e.status === 'error') onFailed(e.error?.message ?? 'no more detail');
    });
    return () => {
      sub.remove();
      try {
        player.pause();
      } catch {
        // already released
      }
    };
  }, [player, onFailed]);
  return <VideoView player={player} style={styles.video} nativeControls contentFit="contain" />;
}

type Loaded = { kind: 'loading' } | { kind: 'ready'; source: ClipSource } | { kind: 'error'; message: string };

/** What is inside the modal for one clip. It is keyed by the clip, so each clip starts from "loading" with a fresh player. */
function ClipPlaying({ clip, home, now, onClose }: { clip: DeviceClip; home: Home; now: number; onClose: () => void }) {
  const [loaded, setLoaded] = useState<Loaded>({ kind: 'loading' });
  useEffect(() => {
    let live = true;
    home.clipSource(clip).then(
      (source) => live && setLoaded({ kind: 'ready', source }),
      (e) => live && setLoaded({ kind: 'error', message: e instanceof Error && e.message ? e.message : "Couldn't get the clip." }),
    );
    return () => {
      live = false;
    };
  }, [clip, home]);
  const failed = useCallback((why: string) => setLoaded({ kind: 'error', message: `This phone couldn't play the clip (${why}).` }), []);
  return (
    <View style={styles.screen}>
      <Pressable onPress={onClose} accessibilityRole="button" accessibilityLabel="Close" style={styles.close} hitSlop={12}>
        <Ionicons name="close" size={28} color="#fff" />
      </Pressable>
      <Text style={styles.title} accessibilityRole="header">
        {clip.deviceName} · {clipKindWords(clip)} · {clipTime(clip.at, now)}
      </Text>
      {loaded.kind === 'loading' ? <ActivityIndicator color="#fff" style={styles.center} /> : null}
      {loaded.kind === 'error' ? <Text style={styles.error}>{loaded.message}</Text> : null}
      {loaded.kind === 'ready' ? <ClipSurface source={loaded.source} onFailed={failed} /> : null}
    </View>
  );
}

/** A clip in a full-screen modal: the address (and header) is asked for now, because the services' addresses expire. */
export function ClipPlayerModal({ clip, home, now, onClose }: { clip: DeviceClip | null; home: Home; now: number; onClose: () => void }) {
  return (
    <Modal visible={clip !== null} animationType="slide" onRequestClose={onClose} statusBarTranslucent>
      {clip ? <ClipPlaying key={`${clip.backend}:${clip.id}`} clip={clip} home={home} now={now} onClose={onClose} /> : null}
    </Modal>
  );
}

const styles = StyleSheet.create({
  screen: { flex: 1, backgroundColor: '#000', justifyContent: 'center' },
  close: { position: 'absolute', top: 54, right: 16, zIndex: 2 },
  title: { color: '#fff', position: 'absolute', top: 58, left: 16, right: 64, fontSize: 15 },
  video: { width: '100%', height: '70%' },
  center: { alignSelf: 'center' },
  error: { color: '#fff', textAlign: 'center', paddingHorizontal: 24, fontSize: 16 },
});
