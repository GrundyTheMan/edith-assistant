/**
 * Voice activity detection (C57 §2): decides from the microphone's level, sampled every 100 ms, when someone starts and stops speaking.
 * Pure and crude on purpose: a metering heuristic, not a speech model. Real rooms (a television, a fan) will need tuning; `sensitivity`
 * and the pause length are the owner's knobs in Settings → Conversation. Every number lives here.
 */
export const VAD = {
  /** How often the level is read. */
  sampleMs: 100,
  /** The start of each listening period measures the room's own noise, and nothing is decided meanwhile. */
  calibrateMs: 700,
  /** Speech is a level above `max(minThreshold, floor + margin)`; both shrink as sensitivity rises. */
  minThreshold: 0.15,
  margin: 0.12,
  /** Speech starts after this much unbroken level above the threshold (a 150 ms cough is not speech). */
  startMs: 250,
  /** An utterance needs this much speech in all, or it is dropped as a false start. */
  minSpeechMs: 400,
  /** Silence this long after speech ends the utterance (the owner's "Pause length" setting overrides it). */
  endSilenceMs: 1100,
  /** One utterance never lasts longer than this. */
  maxUtteranceMs: 60_000,
} as const;

export type VadEvent = 'speech_start' | 'speech_end' | 'false_start';

export interface VadOptions {
  /** 0..1; 0.5 is the default. Higher hears quieter speech (a smaller margin over the room's noise). */
  sensitivity?: number;
  endSilenceMs?: number;
}

/** The threshold a level must pass to count as speech, for a room whose noise floor is `floor`. */
export function thresholdFor(floor: number, sensitivity = 0.5): number {
  const k = 1.5 - Math.min(1, Math.max(0, sensitivity)); // 0.5 -> 1.0, 1 -> 0.5, 0 -> 1.5
  return Math.max(VAD.minThreshold * k, floor + VAD.margin * k);
}

export class SpeechDetector {
  private calibratedMs = 0;
  private floorSum = 0;
  private floorCount = 0;
  private threshold: number;
  private speaking = false;
  private aboveMs = 0;
  private belowMs = 0;
  private speechMs = 0;
  private readonly sensitivity: number;
  private readonly endSilenceMs: number;

  constructor(options: VadOptions = {}) {
    this.sensitivity = options.sensitivity ?? 0.5;
    this.endSilenceMs = options.endSilenceMs ?? VAD.endSilenceMs;
    this.threshold = thresholdFor(0, this.sensitivity);
  }

  /** The level that counts as speech right now (for the Settings test's meter). */
  get currentThreshold(): number {
    return this.threshold;
  }
  get isSpeaking(): boolean {
    return this.speaking;
  }
  get calibrating(): boolean {
    return this.calibratedMs < VAD.calibrateMs;
  }

  /** Feeds one level (0..1) covering `dtMs`. Returns what, if anything, just happened. */
  push(level: number, dtMs: number = VAD.sampleMs): VadEvent | null {
    if (this.calibrating) {
      this.calibratedMs += dtMs;
      this.floorSum += level;
      this.floorCount++;
      if (!this.calibrating) this.threshold = thresholdFor(this.floorSum / this.floorCount, this.sensitivity);
      return null;
    }
    const loud = level > this.threshold;
    if (!this.speaking) {
      this.aboveMs = loud ? this.aboveMs + dtMs : 0;
      if (this.aboveMs >= VAD.startMs) {
        this.speaking = true;
        this.speechMs = this.aboveMs;
        this.belowMs = 0;
        return 'speech_start';
      }
      return null;
    }
    if (loud) {
      this.speechMs += dtMs;
      this.belowMs = 0;
      return null;
    }
    this.belowMs += dtMs;
    if (this.belowMs < this.endSilenceMs) return null;
    const enough = this.speechMs >= VAD.minSpeechMs;
    this.reset();
    return enough ? 'speech_end' : 'false_start';
  }

  /** Back to waiting for speech (the room's floor is kept). */
  reset(): void {
    this.speaking = false;
    this.aboveMs = 0;
    this.belowMs = 0;
    this.speechMs = 0;
  }
}
