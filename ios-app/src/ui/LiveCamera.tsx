import { Ionicons } from '@expo/vector-icons';
import { useEffect, useRef, useState } from 'react';
import { ActivityIndicator, AppState, Image, Modal, Pressable, StyleSheet, Text, View } from 'react-native';
/** One picture from a camera: a JPEG and when it was taken. */
export interface Frame {
  jpegBase64: string;
  takenAt: number;
}

/** How long to wait between one picture arriving and asking for the next (the printer's camera answers in well under a second). */
export const FRAME_GAP_MS = 400;
/** Failures in a row before the view stops and offers Retry. */
export const MAX_FAILURES = 3;
const RETRY_GAP_MS = 2_000;

type Phase = { kind: 'running' } | { kind: 'stopped'; message: string };

/**
 * A camera that keeps updating (C55): the Camera button on the printer card used to show one picture and never change. This asks for a new picture as soon as
 * the last one is on screen, so it moves about once a second, for as long as the window is open and Edith is in front. It only exists while open (closing
 * unmounts it, which stops the asking), and a hiccup keeps the last picture on screen and tries again; three in a row stop it with a Retry button.
 * Only for cameras that cost nothing to ask (the printer's, on the home Wi-Fi): a battery camera such as Ring's would be woken for every frame.
 */
export function LiveFrames({
  name,
  next,
  onClose,
  delay = (ms) => new Promise<void>((r) => setTimeout(r, ms)),
  now = Date.now,
}: {
  name: string;
  /** Asks the camera for a picture now. Throws words for the owner. */
  next: (signal: AbortSignal) => Promise<Frame>;
  onClose: () => void;
  delay?: (ms: number) => Promise<void>;
  now?: () => number;
}) {
  const [frame, setFrame] = useState<Frame | null>(null);
  const [phase, setPhase] = useState<Phase>({ kind: 'running' });
  const [problem, setProblem] = useState<string | null>(null);
  const [tick, setTick] = useState(0);
  const [attempt, setAttempt] = useState(0);
  const [active, setActive] = useState(AppState.currentState !== 'background' && AppState.currentState !== 'inactive');
  const nextRef = useRef(next);
  useEffect(() => {
    nextRef.current = next;
  });

  useEffect(() => {
    const sub = AppState.addEventListener('change', (s) => setActive(s === 'active'));
    return () => sub.remove();
  }, []);

  // The loop. `attempt` restarts it (Retry); leaving the screen or the app stops it.
  useEffect(() => {
    if (!active) return undefined;
    const ctl = new AbortController();
    let failures = 0;
    void (async () => {
      while (!ctl.signal.aborted) {
        try {
          const f = await nextRef.current(ctl.signal);
          if (ctl.signal.aborted) return;
          failures = 0;
          setProblem(null);
          setFrame(f);
          await delay(FRAME_GAP_MS);
        } catch (e) {
          if (ctl.signal.aborted) return;
          const why = e instanceof Error && e.message ? e.message : "Couldn't get a picture.";
          failures++;
          if (failures >= MAX_FAILURES) {
            setPhase({ kind: 'stopped', message: why });
            return;
          }
          setProblem(why);
          await delay(RETRY_GAP_MS);
        }
      }
    })();
    return () => ctl.abort();
  }, [active, attempt, delay]);

  // "Updated 2 s ago" moves every second.
  useEffect(() => {
    const t = setInterval(() => setTick((n) => n + 1), 1_000);
    return () => clearInterval(t);
  }, []);
  void tick;

  const age = frame ? Math.max(0, Math.round((now() - frame.takenAt) / 1000)) : null;
  const stopped = phase.kind === 'stopped';
  return (
    <View style={styles.screen}>
      {frame ? <Image source={{ uri: `data:image/jpeg;base64,${frame.jpegBase64}` }} style={styles.image} resizeMode="contain" accessibilityLabel={`${name} camera`} /> : null}
      {!frame && !stopped ? <ActivityIndicator color="#fff" style={styles.center} /> : null}
      <Pressable onPress={onClose} accessibilityRole="button" accessibilityLabel="Close" style={styles.close} hitSlop={12}>
        <Ionicons name="close" size={28} color="#fff" />
      </Pressable>
      <View style={styles.bar}>
        <Text style={styles.title}>{name}</Text>
        {stopped ? (
          <>
            <Text style={styles.note}>{phase.message}</Text>
            <Pressable
              onPress={() => {
                setPhase({ kind: 'running' });
                setProblem(null);
                setAttempt((n) => n + 1);
              }}
              accessibilityRole="button"
              accessibilityLabel="Retry"
              style={styles.retry}
            >
              <Text style={styles.retryText}>Retry</Text>
            </Pressable>
          </>
        ) : (
          <Text style={styles.note} accessibilityLiveRegion="polite">
            {!active ? 'Paused while Edith is in the background' : problem ? `${problem} Trying again…` : age === null ? 'Getting a picture…' : `Live · updated ${age < 2 ? 'just now' : `${age} s ago`}`}
          </Text>
        )}
      </View>
    </View>
  );
}

/** The window around it: mounted only while a camera is chosen. */
export function LiveCamera({ name, next, onClose, visible }: { name: string; next: (signal: AbortSignal) => Promise<Frame>; onClose: () => void; visible: boolean }) {
  return (
    <Modal visible={visible} animationType="fade" onRequestClose={onClose} statusBarTranslucent>
      {visible ? <LiveFrames name={name} next={next} onClose={onClose} /> : null}
    </Modal>
  );
}

const styles = StyleSheet.create({
  screen: { flex: 1, backgroundColor: '#000', justifyContent: 'center' },
  image: { width: '100%', height: '80%' },
  center: { alignSelf: 'center' },
  close: { position: 'absolute', top: 54, right: 16, zIndex: 2 },
  bar: { position: 'absolute', left: 16, right: 16, bottom: 48, gap: 6 },
  title: { color: '#fff', fontSize: 18, fontWeight: '600' },
  note: { color: '#ddd', fontSize: 14 },
  retry: { alignSelf: 'flex-start', backgroundColor: '#fff', borderRadius: 16, paddingHorizontal: 16, paddingVertical: 8 },
  retryText: { color: '#000', fontWeight: '600' },
});
