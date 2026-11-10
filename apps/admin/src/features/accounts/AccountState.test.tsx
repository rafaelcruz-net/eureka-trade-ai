import { render, screen, within } from '@testing-library/react';
import type { AdminDataRequest } from '@/lib/api/admin-api';
import { messages } from '@/lib/i18n/messages';
import { tokenStore } from '@/lib/auth/token-store';
import { Providers, fakeAdminApi } from '@/test-support';
import { AccountState } from '@/features/accounts/AccountState';
import { AdminDataRequestList } from '@/features/data-requests/AdminDataRequestList';

jest.mock('next/navigation', () => ({
  useRouter: () => ({ push: jest.fn(), replace: jest.fn() }),
  useParams: () => ({}),
  useSearchParams: () => new URLSearchParams(),
}));

const text = messages.dataRequests;

function item(overrides: Partial<AdminDataRequest>): AdminDataRequest {
  return {
    requestId: 'req-1',
    type: 'DELETION',
    status: 'DONE',
    origin: 'ACCOUNT',
    receivedAt: '2026-11-01T12:00:00Z',
    dueAt: '2026-11-16T12:00:00Z',
    details: 'Texto',
    userId: 'user-1',
    contact: null,
    response: null,
    refusalReason: null,
    identityVerified: false,
    verification: null,
    accessSummary: null,
    accessSummaryPreview: null,
    replySentAt: null,
    overdue: false,
    dueSoon: false,
    accountAnonymized: false,
    ...overrides,
  };
}

describe('AccountState (u5-anonymization, "Conta anonimizada")', () => {
  it('shows "Conta anonimizada" with an icon and text and never the identifier', () => {
    render(<AccountState userId="user-1" anonymized />);

    const badge = screen.getByTestId('account-state-anonymized');
    expect(badge).toHaveTextContent(text.accountAnonymized);
    expect(badge.querySelector('[aria-hidden="true"]')).not.toBeNull();
    expect(badge).not.toHaveTextContent('user-1');
  });

  it('shows the internal identifier of an account that is not anonymized', () => {
    render(<AccountState userId="user-2" anonymized={false} />);

    expect(screen.getByTestId('account-state')).toHaveTextContent('user-2');
  });

  it('says that there is no account when the request has none', () => {
    render(<AccountState userId={null} anonymized={false} />);

    expect(screen.getByTestId('account-state')).toHaveTextContent(text.noAccount);
  });

  it('marks only the anonymized accounts of the administrator list', async () => {
    tokenStore.clear();
    const api = fakeAdminApi({
      listDataRequests: jest.fn(async () => ({
        items: [
          item({ requestId: 'req-a', userId: 'user-a', accountAnonymized: true }),
          item({ requestId: 'req-b', userId: 'user-b' }),
          item({ requestId: 'req-c', userId: null, origin: 'EMAIL', contact: 'x@example.org' }),
        ],
      })),
    });
    render(
      <Providers api={api}>
        <AdminDataRequestList timeZone="UTC" now={() => Date.parse('2026-11-10T12:00:00Z')} />
      </Providers>,
    );

    const anonymized = await screen.findByTestId('data-request-account-req-a-anonymized');
    expect(anonymized).toHaveTextContent(text.accountAnonymized);
    expect(screen.getByTestId('data-request-account-req-b')).toHaveTextContent('user-b');
    expect(screen.getByTestId('data-request-account-req-c')).toHaveTextContent(text.noAccount);
    const table = screen.getByRole('table');
    expect(within(table).queryByText('user-a')).toBeNull();
  });
});
