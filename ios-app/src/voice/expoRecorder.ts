import AudioModule from 'expo-audio/build/AudioModule';
import { AudioQuality, IOSOutputFormat, setAudioModeAsync, requestRecordingPermissionsAsync } from 'expo-audio';
import type { AudioRecorder } from 'expo-audio';
import * as FileSystem from 'expo-file-system/legacy';
import { levelFromDb, type AudioFiles, type RecordedAudio, type Recorder } from './voiceMemos';

/**
 * 16 kHz mono 16-bit PCM WAV: the format Ollama's audio models accept (03-ollama-reference §5; M4A is refused).
 * Linear PCM in a .wav container is what AVAudioRecorder writes for these settings.
 */
const WAV_OPTIONS = {
  extension: '.wav',
  sampleRate: 16000,
  numberOfChannels: 1,
  bitRate: 256000,
  isMeteringEnabled: true,
  outputFormat: IOSOutputFormat.LINEARPCM,
  audioQuality: AudioQuality.HIGH,
  linearPCMBitDepth: 16,
  linearPCMIsBigEndian: false,
  linearPCMIsFloat: false,
};

/** The microphone through expo-audio. Not covered by unit tests: it needs a device (see C44 "Not verified"). */
export class ExpoRecorder implements Recorder {
  private recorder: AudioRecorder | null = null;

  async requestPermission(): Promise<boolean> {
    try {
      return (await requestRecordingPermissionsAsync()).granted;
    } catch {
      return false;
    }
  }

  async start(): Promise<void> {
    await setAudioModeAsync({ allowsRecording: true, playsInSilentMode: true });
    const recorder = new AudioModule.AudioRecorder(WAV_OPTIONS);
    this.recorder = recorder;
    try {
      await recorder.prepareToRecordAsync();
      recorder.record();
    } catch (e) {
      await this.release();
      throw e;
    }
  }

  async stop(): Promise<RecordedAudio> {
    const recorder = this.recorder;
    if (!recorder) throw new Error('Not recording');
    const durationMs = recorder.getStatus().durationMillis;
    try {
      await recorder.stop();
      const uri = recorder.uri;
      if (!uri) throw new Error('The recording has no file');
      return { uri, durationMs };
    } finally {
      await this.release();
    }
  }

  async cancel(): Promise<void> {
    const recorder = this.recorder;
    if (!recorder) return;
    const uri = recorder.uri;
    try {
      await recorder.stop();
    } catch {
      // already stopped
    }
    await this.release();
    const target = uri ?? recorder.uri;
    if (target) await expoAudioFiles.delete(target).catch(() => {});
  }

  level(): number {
    try {
      return levelFromDb(this.recorder?.getStatus().metering);
    } catch {
      return 0;
    }
  }

  /** Lets go of the microphone and puts the audio session back to playback, so spoken replies use the speaker. */
  private async release() {
    const recorder = this.recorder;
    this.recorder = null;
    try {
      recorder?.release?.();
    } catch {
      // nothing to release
    }
    await setAudioModeAsync({ allowsRecording: false, playsInSilentMode: true }).catch(() => {});
  }
}

export const expoAudioFiles: AudioFiles = {
  readBase64: (uri) => FileSystem.readAsStringAsync(uri, { encoding: FileSystem.EncodingType.Base64 }),
  delete: (uri) => FileSystem.deleteAsync(uri, { idempotent: true }),
};
