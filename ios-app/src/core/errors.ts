/** Every failure the owner can see, with a plain-language message (as in the Kotlin app's AppError). */
export type AppErrorKind =
  | 'server_unreachable'
  | 'timeout'
  | 'model_not_found'
  | 'empty_reply'
  | 'no_chat_models'
  | 'http'
  | 'malformed'
  | 'invalid_url'
  | 'model_lacks_capability'
  | 'http_blocked'
  | 'unknown';

export class AppError extends Error {
  readonly kind: AppErrorKind;

  constructor(kind: AppErrorKind, message: string, options?: { cause?: unknown }) {
    super(message, options);
    this.name = 'AppError';
    this.kind = kind;
  }

  static serverUnreachable(url: string, cause?: unknown) {
    return new AppError(
      'server_unreachable',
      `Can't reach your Ollama server at ${url}. Is the PC on and Ollama running?`,
      { cause },
    );
  }
  static timeout() {
    return new AppError('timeout', 'Edith took too long to answer. Try again.');
  }
  static modelNotFound(model: string) {
    return new AppError('model_not_found', `The model "${model}" isn't installed on your server. Pick another in Settings.`);
  }
  static emptyReply() {
    return new AppError('empty_reply', "Edith didn't reply. Try again.");
  }
  static noChatModels() {
    return new AppError(
      'no_chat_models',
      'No chat models are installed on your Ollama server. Install one on the PC, e.g. "ollama pull gemma4".',
    );
  }
  static http(code: number, body: string) {
    return new AppError('http', `Your Ollama server reported an error (HTTP ${code}): ${body}`);
  }
  static malformed() {
    return new AppError('malformed', "Your Ollama server sent a reply Edith couldn't read. Try again.");
  }
  static invalidUrl(message: string) {
    return new AppError('invalid_url', message);
  }
  static lacksCapability(model: string, what: 'images' | 'audio') {
    const verb = what === 'images' ? 'see images' : 'hear audio';
    const badge = what === 'images' ? 'Sees images' : 'Hears audio';
    return new AppError('model_lacks_capability', `${model} can't ${verb} — pick a model marked "${badge}" in Settings.`);
  }
  /** A request was refused because it wasn't HTTPS, or went (or was sent on) to an address it may not use. Names the host only. */
  static insecureUrl(host: string) {
    return new AppError('http_blocked', `Edith only talks to ${host} over a secure connection to the right address, so she stopped.`);
  }
  static unknown(cause: unknown) {
    const text = cause instanceof Error ? cause.message || cause.name : String(cause);
    return new AppError('unknown', `Something went wrong: ${text}`, { cause });
  }
}

export function isAbortError(e: unknown): boolean {
  return e instanceof Error && (e.name === 'AbortError' || /\babort(ed)?\b/i.test(e.message));
}

/** Maps anything thrown into an AppError (an AppError stays as it is). */
export function toAppError(e: unknown): AppError {
  return e instanceof AppError ? e : AppError.unknown(e);
}
