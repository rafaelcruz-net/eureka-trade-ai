/**
 * The administrator access token lives ONLY in this module's memory (contract-summary.md): never localStorage,
 * sessionStorage or a cookie readable by scripts. A reload loses it and the app renews it through the HttpOnly cookie.
 */
type Listener = (token: string | null) => void;

let accessToken: string | null = null;
const listeners = new Set<Listener>();

export const tokenStore = {
  get(): string | null {
    return accessToken;
  },
  set(token: string | null): void {
    accessToken = token;
    listeners.forEach((listener) => listener(token));
  },
  clear(): void {
    tokenStore.set(null);
  },
  subscribe(listener: Listener): () => void {
    listeners.add(listener);
    return () => listeners.delete(listener);
  },
};
