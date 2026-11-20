import type { JarvisClient } from '../../jarvis/service';
import { PrinterFeedApi, type PrinterRoutes } from './api';
import type { SharedPrinter } from './wire';
import { PrinterWords, wordsForFailure } from './words';

/** What the app can do about the printer right now (C39 §3.1). */
export type PrinterAccess =
  /** No Jarvis is paired. */
  | { kind: 'noJarvis' }
  /** Paired, but the printer is not shared with this phone (or this Jarvis is too old to know it). */
  | { kind: 'notShared' }
  /** Shared: `api` asks Jarvis; `shared` is what Jarvis says about the printer (name, control, presets, cameras). */
  | { kind: 'ready'; api: PrinterRoutes; shared: SharedPrinter }
  /** Jarvis could not be asked: `why` is in plain words; `removed` when it does not know this phone any more. */
  | { kind: 'away'; why: string; removed: boolean };

/** Where the page, Home and the chat tools get the printer from. */
export interface PrinterFeedSource {
  /** Reads what Jarvis shares (asks Jarvis once) and says what the app can do. */
  access(): Promise<PrinterAccess>;
}

/** The paired Jarvis's connection, as far as the printer needs it. */
export interface JarvisConnection extends JarvisClient {
  /** `signed_out` is no pairing; `needs_sign_in` is a phone Jarvis removed. */
  readonly state: { get(): { kind: string } };
}

/** Finds the paired Jarvis (C66a) and its printer. It owns no state: the one `PrinterSession` holds that. Network goes through the Jarvis connection's client. */
export class PrinterFeed implements PrinterFeedSource {
  constructor(private readonly jarvis: JarvisConnection) {}

  async access(): Promise<PrinterAccess> {
    const kind = this.jarvis.state.get().kind;
    if (kind === 'signed_out' || kind === 'connecting') return { kind: 'noJarvis' };
    if (kind === 'needs_sign_in') return { kind: 'away', why: PrinterWords.UNAUTHORIZED, removed: true };
    const r = await this.jarvis.readShared();
    switch (r.kind) {
      case 'ok':
        return r.value.printerShare ? { kind: 'ready', api: new PrinterFeedApi(this.jarvis), shared: r.value.printerShare } : { kind: 'notShared' };
      case 'unauthorized':
        return { kind: 'away', why: PrinterWords.UNAUTHORIZED, removed: true };
      default:
        return { kind: 'away', why: wordsForFailure(r) || PrinterWords.UNREACHABLE, removed: false };
    }
  }
}
