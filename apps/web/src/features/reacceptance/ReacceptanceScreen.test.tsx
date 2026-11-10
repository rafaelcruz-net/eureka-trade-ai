import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ApiError } from '@/lib/api/client';
import type { CurrentLegalDocuments } from '@/lib/api/guest-api';
import { tokenStore } from '@/lib/auth/token-store';
import { fakeApi, LEGAL_CURRENT, Providers } from '@/features/auth/test-support';
import { clearLegalCache } from '@/features/legal/legal-api';
import { reacceptanceMessages } from '@/features/reacceptance/messages';
import { ReacceptanceScreen } from '@/features/reacceptance/ReacceptanceScreen';
import ReacceptancePage from '@/app/novo-aceite/page';

const replace = jest.fn();

jest.mock('next/navigation', () => ({
  useRouter: () => ({ replace, push: replace }),
  useSearchParams: () => new URLSearchParams(),
}));

const text = reacceptanceMessages;
const NEWER: CurrentLegalDocuments = { ...LEGAL_CURRENT, version: 'v3' };

function renderScreen(
  api = fakeApi(),
  { initial = LEGAL_CURRENT as CurrentLegalDocuments | null, returnTo = '/bots' } = {},
) {
  tokenStore.set('access-token-value');
  const keys = ['key-1', 'key-2', 'key-3'];
  const newKey = jest.fn(() => keys.shift() ?? 'key-x');
  render(
    <Providers api={api}>
      <ReacceptanceScreen returnTo={returnTo} initialDocuments={initial} newKey={newKey} />
    </Providers>,
  );
  return { api, newKey };
}

async function markBoth() {
  await userEvent.click(screen.getByTestId('reacceptance-accept-terms'));
  await userEvent.click(screen.getByTestId('reacceptance-accept-risk'));
}

describe('new acceptance of the terms /novo-aceite', () => {
  beforeEach(() => {
    replace.mockReset();
    tokenStore.clear();
    clearLegalCache();
  });

  it('shows the h1, the summaries as h2, the version and the links in another tab', () => {
    renderScreen();

    expect(screen.getByRole('heading', { level: 1, name: text.title })).toHaveFocus();
    expect(
      screen.getByRole('heading', { level: 2, name: text.summary.termsHeading }),
    ).toBeInTheDocument();
    expect(
      screen.getByRole('heading', { level: 2, name: text.summary.privacyHeading }),
    ).toBeInTheDocument();
    expect(screen.getByTestId('reacceptance-version')).toHaveTextContent('Versão v2');
    expect(
      within(screen.getByTestId('reacceptance-terms-summary')).getAllByRole('listitem'),
    ).toHaveLength(5);
    for (const link of [
      screen.getByTestId('reacceptance-terms-link'),
      screen.getByTestId('reacceptance-privacy-link'),
    ]) {
      expect(link).toHaveAttribute('target', '_blank');
      expect(link).toHaveAttribute('rel', 'noopener noreferrer');
    }
    expect(screen.getByRole('main')).toBeInTheDocument();
    expect(screen.getByTestId('site-footer')).toBeInTheDocument();
    expect(screen.getByTestId('reacceptance-bots-note')).toHaveTextContent(text.botsKeepRunning);
  });

  it('keeps "Aceitar e continuar" disabled with the reason in text until both boxes are marked', async () => {
    renderScreen();
    const button = screen.getByTestId('reacceptance-accept');

    expect(button).toBeDisabled();
    expect(button).toHaveAttribute('aria-describedby', 'reacceptance-accept-reason');
    expect(screen.getByTestId('reacceptance-accept-reason')).toHaveTextContent(text.bothRequired);
    await userEvent.click(screen.getByTestId('reacceptance-accept-terms'));
    expect(button).toBeDisabled();
    await userEvent.click(screen.getByTestId('reacceptance-accept-risk'));
    await waitFor(() => expect(button).toBeEnabled());
    expect(screen.queryByTestId('reacceptance-accept-reason')).toBeNull();
  });

  it('accepts the version shown with a key of the submission and goes back to the screen of origin', async () => {
    const { api, newKey } = renderScreen();
    await markBoth();

    await userEvent.click(screen.getByTestId('reacceptance-accept'));

    await waitFor(() => expect(replace).toHaveBeenCalledWith('/bots'));
    expect(api.acceptTerms).toHaveBeenCalledWith('v2', 'key-1');
    expect(newKey).toHaveBeenCalledTimes(1);
  });

  it('shows "registrando" and disables the boxes while the acceptance is sent', async () => {
    let finish!: () => void;
    const api = fakeApi({
      acceptTerms: jest.fn(
        () =>
          new Promise((resolve) => {
            finish = () =>
              resolve({
                acceptedVersion: 'v2',
                currentVersion: 'v2',
                acceptedAt: 'x',
                upToDate: true,
              });
          }),
      ),
    });
    renderScreen(api);
    await markBoth();

    await userEvent.click(screen.getByTestId('reacceptance-accept'));

    expect(await screen.findByTestId('reacceptance-submitting')).toHaveTextContent(text.submitting);
    expect(screen.getByTestId('reacceptance-accept-terms')).toBeDisabled();
    expect(screen.getByTestId('reacceptance-accept')).toBeDisabled();
    finish();
    await waitFor(() => expect(replace).toHaveBeenCalledWith('/bots'));
  });

  it('on 409 reloads the documents, unmarks the boxes and explains that the version changed', async () => {
    const api = fakeApi({
      acceptTerms: jest.fn(async () => {
        throw new ApiError(
          409,
          'TERMS_VERSION_OUTDATED',
          undefined,
          undefined,
          [],
          [],
          undefined,
          'v3',
        );
      }),
      getCurrentLegalDocuments: jest.fn(async () => NEWER),
    });
    renderScreen(api);
    await markBoth();

    await userEvent.click(screen.getByTestId('reacceptance-accept'));

    expect(await screen.findByTestId('reacceptance-outdated')).toHaveTextContent(text.outdated);
    await waitFor(() =>
      expect(screen.getByTestId('reacceptance-version')).toHaveTextContent('Versão v3'),
    );
    expect(api.getCurrentLegalDocuments).toHaveBeenCalledTimes(1);
    expect(screen.getByTestId('reacceptance-accept-terms')).not.toBeChecked();
    expect(screen.getByTestId('reacceptance-accept-risk')).not.toBeChecked();
    expect(screen.getByTestId('reacceptance-accept')).toBeDisabled();
    expect(replace).not.toHaveBeenCalled();
  });

  it('any other failure shows the error in text and keeps the marks for a new attempt with a new key', async () => {
    const api = fakeApi({
      acceptTerms: jest
        .fn()
        .mockRejectedValueOnce(new ApiError(503, 'SERVICE_UNAVAILABLE'))
        .mockResolvedValueOnce({
          acceptedVersion: 'v2',
          currentVersion: 'v2',
          acceptedAt: 'x',
          upToDate: true,
        }),
    });
    renderScreen(api);
    await markBoth();

    await userEvent.click(screen.getByTestId('reacceptance-accept'));

    expect(await screen.findByTestId('reacceptance-failed')).toHaveTextContent(text.failed);
    expect(screen.getByTestId('reacceptance-accept-terms')).toBeChecked();
    await userEvent.click(screen.getByTestId('reacceptance-accept'));
    await waitFor(() => expect(replace).toHaveBeenCalledWith('/bots'));
    expect(api.acceptTerms).toHaveBeenNthCalledWith(2, 'v2', 'key-2');
  });

  it('a 401 ends the session and sends the person to the entry with this screen as origin', async () => {
    const api = fakeApi({
      acceptTerms: jest.fn(async () => {
        throw new ApiError(401, 'UNAUTHENTICATED');
      }),
    });
    renderScreen(api);
    await markBoth();

    await userEvent.click(screen.getByTestId('reacceptance-accept'));

    await waitFor(() => expect(replace).toHaveBeenCalledWith(expect.stringContaining('/entrar?')));
    const target = String(replace.mock.calls.at(-1)?.[0]);
    expect(new URLSearchParams(target.split('?')[1]).get('origem')).toBe(
      '/novo-aceite?voltar=%2Fbots',
    );
    expect(new URLSearchParams(target.split('?')[1]).get('expirada')).toBe('1');
  });

  it('without the documents of the server it loads them, with the boxes disabled meanwhile', async () => {
    let deliver!: (documents: CurrentLegalDocuments) => void;
    const api = fakeApi({
      getCurrentLegalDocuments: jest.fn(
        () =>
          new Promise<CurrentLegalDocuments>((resolve) => {
            deliver = resolve;
          }),
      ),
    });
    renderScreen(api, { initial: null });

    expect(screen.getByTestId('reacceptance-loading')).toHaveTextContent(text.loading);
    expect(screen.getByTestId('reacceptance-accept-terms')).toBeDisabled();
    expect(screen.getByTestId('reacceptance-accept-reason')).toHaveTextContent(text.waitDocuments);
    deliver(LEGAL_CURRENT);
    await waitFor(() => expect(screen.getByTestId('reacceptance-accept-terms')).toBeEnabled());
  });

  it('a failure to load shows the error and "Tentar de novo" loads again', async () => {
    const api = fakeApi({
      getCurrentLegalDocuments: jest
        .fn()
        .mockRejectedValueOnce(new ApiError(0, 'NETWORK'))
        .mockResolvedValueOnce(LEGAL_CURRENT),
    });
    jest.spyOn(console, 'warn').mockImplementation(() => undefined);
    renderScreen(api, { initial: null });

    expect(await screen.findByTestId('reacceptance-error')).toHaveTextContent(text.errorTitle);
    expect(screen.getByTestId('reacceptance-accept-risk')).toBeDisabled();
    await userEvent.click(screen.getByTestId('reacceptance-retry'));

    await waitFor(() =>
      expect(screen.getByTestId('reacceptance-version')).toHaveTextContent('Versão v2'),
    );
    expect(screen.getByTestId('reacceptance-accept-risk')).toBeEnabled();
  });

  it('announces the states in a polite live region', async () => {
    renderScreen(fakeApi(), { initial: null });

    const live = screen.getByTestId('reacceptance-live');
    expect(live).toHaveAttribute('aria-live', 'polite');
    expect(within(live).getByText(text.loading)).toBeInTheDocument();
    await waitFor(() => expect(within(live).queryByText(text.loading)).toBeNull());
  });

  it('works by keyboard alone: heading, links, boxes and the button, in that order', async () => {
    const { api } = renderScreen();

    expect(screen.getByRole('heading', { level: 1 })).toHaveFocus();
    await userEvent.tab();
    expect(screen.getByTestId('reacceptance-terms-link')).toHaveFocus();
    await userEvent.tab();
    expect(screen.getByTestId('reacceptance-privacy-link')).toHaveFocus();
    await userEvent.tab();
    expect(screen.getByTestId('reacceptance-accept-terms')).toHaveFocus();
    await userEvent.keyboard(' ');
    await userEvent.tab();
    expect(screen.getByTestId('reacceptance-accept-risk')).toHaveFocus();
    await userEvent.keyboard(' ');
    await userEvent.tab();
    expect(screen.getByTestId('reacceptance-accept')).toHaveFocus();
    await userEvent.keyboard('{Enter}');

    await waitFor(() => expect(api.acceptTerms).toHaveBeenCalledTimes(1));
  });

  it('labels both boxes with visible text', () => {
    renderScreen();

    expect(screen.getByLabelText(text.consent.terms)).toBe(
      screen.getByTestId('reacceptance-accept-terms'),
    );
    expect(screen.getByLabelText(text.consent.risk)).toBe(
      screen.getByTestId('reacceptance-accept-risk'),
    );
  });

  it('the page reads the documents on the server and refuses an external return path', async () => {
    const fetchSpy = serverFetch({ ok: true, status: 200, json: async () => LEGAL_CURRENT });
    tokenStore.set('access-token-value');
    const api = fakeApi();
    render(
      <Providers api={api}>
        {await ReacceptancePage({
          searchParams: Promise.resolve({ voltar: 'https://evil.example/roubo' }),
        })}
      </Providers>,
    );
    await markBoth();

    await userEvent.click(screen.getByTestId('reacceptance-accept'));

    await waitFor(() => expect(replace).toHaveBeenCalledWith('/'));
    expect(fetchSpy).toHaveBeenCalledWith(
      expect.stringContaining('/api/v1/legal/current'),
      expect.objectContaining({ cache: 'no-store' }),
    );
    expect(api.getCurrentLegalDocuments).not.toHaveBeenCalled();
  });

  it('when the server read fails, the page lets the screen load the documents itself', async () => {
    serverFetch({ ok: false, status: 500, json: async () => ({}) });
    jest.spyOn(console, 'warn').mockImplementation(() => undefined);
    tokenStore.set('access-token-value');
    const api = fakeApi();
    render(
      <Providers api={api}>
        {await ReacceptancePage({ searchParams: Promise.resolve({ voltar: ['/pnl', '/x'] }) })}
      </Providers>,
    );

    await waitFor(() => expect(api.getCurrentLegalDocuments).toHaveBeenCalledTimes(1));
    expect(await screen.findByTestId('reacceptance-version')).toHaveTextContent('Versão v2');
  });
});

const originalFetch = globalThis.fetch;

afterEach(() => {
  globalThis.fetch = originalFetch;
});

/** The `fetch` of the Next.js server, answering every L1 read with `answer`. */
function serverFetch(answer: { ok: boolean; status: number; json: () => Promise<unknown> }) {
  const fake = jest.fn(async () => answer as unknown as Response);
  globalThis.fetch = fake as unknown as typeof fetch;
  return fake;
}
