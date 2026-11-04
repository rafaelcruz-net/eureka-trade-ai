/**
 * The two acceptance marks of the invitation entry (BR6.1), kept in the BROWSER SESSION (sessionStorage) until the
 * account is created or the tab is closed, so going back to install a wallet never loses them. Only the version of
 * the terms and the two marks are stored: never the invitation code. Storage may be unavailable (private mode,
 * blocked site data); then the marks simply live in memory.
 */
const KEY = 'memetrade.invite.acceptance';

export interface Acceptance {
  terms: boolean;
  risk: boolean;
}

const NONE: Acceptance = { terms: false, risk: false };

function storage(): Storage | null {
  try {
    return typeof window === 'undefined' ? null : window.sessionStorage;
  } catch {
    return null;
  }
}

/** The marks saved for this version of the terms; a mark of another version does not count (BR3.1). */
export function readAcceptance(termsVersion: string): Acceptance {
  try {
    const raw = storage()?.getItem(KEY);
    if (!raw) {
      return NONE;
    }
    const saved = JSON.parse(raw) as { version?: unknown; terms?: unknown; risk?: unknown };
    if (saved.version !== termsVersion) {
      return NONE;
    }
    return { terms: saved.terms === true, risk: saved.risk === true };
  } catch {
    return NONE;
  }
}

export function writeAcceptance(termsVersion: string, acceptance: Acceptance): void {
  try {
    storage()?.setItem(KEY, JSON.stringify({ version: termsVersion, ...acceptance }));
  } catch (failure) {
    console.warn(
      'Acceptance marks could not be kept',
      failure instanceof Error ? failure.name : 'unknown',
    );
  }
}

export function clearAcceptance(): void {
  try {
    storage()?.removeItem(KEY);
  } catch (failure) {
    console.warn(
      'Acceptance marks could not be cleared',
      failure instanceof Error ? failure.name : 'unknown',
    );
  }
}

export const ACCEPTANCE_STORAGE_KEY = KEY;
