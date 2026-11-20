import * as Speech from 'expo-speech';
import type { TtsEngine } from './speechOutput';

/** The iPhone's own voices through expo-speech (so C36's own voice isn't needed on iOS). Utterances queue. */
export const expoTts: TtsEngine = {
  speak(text, rate, handlers) {
    Speech.speak(text, {
      rate,
      onDone: handlers.onDone,
      onStopped: handlers.onStopped,
      onError: handlers.onError,
    });
  },
  stop() {
    void Speech.stop();
  },
};
