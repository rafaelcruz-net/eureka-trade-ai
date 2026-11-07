import { dashboardWarn } from '@/features/dashboard/warn';
import type { GuestApi } from '@/lib/api/guest-api';
import { tokenStore } from '@/lib/auth/token-store';

/**
 * The `renew` of the live channel (BR5.5, NFR6.6). It goes through `api.refreshSession()`, the single-flight renewal of
 * the shared API layer, so a renewal of the channel and the 401 renewals of the REST calls of the dashboard share ONE
 * `POST /auth/refresh` (U9 review R-01). The channel client still asks for at most one renewal per attempt; this only
 * answers whether that renewal worked.
 */
export function channelRenewal(api: Pick<GuestApi, 'refreshSession'>): () => Promise<boolean> {
  return async () => {
    try {
      const session = await api.refreshSession();
      tokenStore.set(session.accessToken);
      return true;
    } catch {
      // The refresh cookie is gone or the server refused it: the client starts the session-expired flow.
      dashboardWarn('stream', 'NETWORK');
      return false;
    }
  };
}
