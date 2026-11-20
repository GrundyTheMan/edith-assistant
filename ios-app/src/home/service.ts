import { Store } from '../core/store';
import type { HomeBackend } from './model';

/** Where a smart-home service stands, in words the Settings row can show (C48). */
export type ServiceState =
  | { kind: 'signed_out' }
  | { kind: 'connecting' }
  | { kind: 'connected'; detail?: string }
  /** A token was refused: "Sign in again". Reported once, never retried in a loop (C26, C27, C29 traps). */
  | { kind: 'needs_sign_in'; reason: string }
  /** The service can't be reached or answered something odd: words for the owner; the sign-in is kept. */
  | { kind: 'error'; message: string };

export interface SignInField {
  key: string;
  label: string;
  secret?: boolean;
  optional?: boolean;
  keyboard?: 'default' | 'email' | 'number' | 'url';
  hint?: string;
}
export interface SignInForm {
  title: string;
  intro?: string;
  fields: SignInField[];
  submitLabel?: string;
}
export type SignInResult =
  | { kind: 'ok' }
  /** A second step (2FA): Edith shows this form next and keeps the first answers in memory only. */
  | { kind: 'needs_code'; form: SignInForm }
  | { kind: 'failed'; message: string };

/** A button a connected service adds to its Settings row ("Find on my Wi-Fi"); `run` answers with the words to show. */
export interface ServiceAction {
  id: string;
  label: string;
  run(): Promise<string>;
}

/** A service that signs in on a web page (Alexa): the WebView shows `startUrl()` until `isFinish` recognises the end. */
export interface PageSignIn {
  startUrl(): string;
  /** `{code}` when `url` is where the page flow ends; null otherwise. */
  isFinish(url: string): { code: string } | null;
  complete(code: string): Promise<SignInResult>;
}

/**
 * One smart-home service (Meross, Ring, Blink, Alexa, the printer, ...). C48 builds this shape; each service chunk
 * supplies the protocol. `Home` reads `backend()` while the service is connected.
 */
export interface HomeService {
  id: string;
  name: string;
  state: Store<ServiceState>;
  /** The form for "Sign in" / "Sign in again". Values are the answers; never logged or stored by the UI. */
  signInForm(): SignInForm;
  /** Extra buttons for the connected service's row (C50). */
  actions?(): ServiceAction[];
  /** When present the Settings row opens the page flow instead of the form. */
  pageSignIn?: PageSignIn;
  signIn(values: Record<string, string>): Promise<SignInResult>;
  /** Returns the token to the service when it asks for that, then deletes the secrets. */
  disconnect(): Promise<void>;
  /** What `Home` reads while the service is connected. */
  backend(): HomeBackend;
  /** At startup: load secrets and set `state`. Must not throw. */
  restore(): Promise<void>;
}

export const isConnected = (s: HomeService) => s.state.get().kind === 'connected';

/** One line for the Settings row. */
export function stateText(state: ServiceState): string {
  switch (state.kind) {
    case 'signed_out':
      return 'Not connected';
    case 'connecting':
      return 'Connecting…';
    case 'connected':
      return state.detail ? `Connected · ${state.detail}` : 'Connected';
    case 'needs_sign_in':
      return `Sign in again: ${state.reason}`;
    case 'error':
      return state.message;
  }
}
