import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ApiError } from '@/lib/api/client';
import type { AccessSummary, DataRequestView } from '@/lib/api/guest-api';
import { tokenStore } from '@/lib/auth/token-store';
import { messages } from '@/lib/i18n/messages';
import { fakeApi, Providers } from '@/features/auth/test-support';
import { AccessSummaryView } from '@/features/data-requests/AccessSummaryView';
import { DataRequestDetail } from '@/features/data-requests/DataRequestDetail';
import { DataRequestForm, codePoints } from '@/features/data-requests/DataRequestForm';
import { DataRequestList } from '@/features/data-requests/DataRequestList';
import { DataRequestsSection } from '@/features/data-requests/DataRequestsSection';

jest.mock('next/navigation', () => ({
  useRouter: () => ({ replace: jest.fn(), push: jest.fn() }),
  useSearchParams: () => new URLSearchParams(),
}));

const text = messages.dataRequests;
const ZONE = 'America/Sao_Paulo';

const SUMMARY: AccessSummary = {
  walletMasked: 'TestW…1111',
  provider: 'PHANTOM',
  accountCreatedAt: '2026-01-01T00:00:00Z',
  lastLoginAt: '2026-11-09T12:00:00Z',
  acceptedVersions: [{ version: 'v1', acceptedAt: '2026-01-01T00:00:00Z' }],
  botCount: 2,
  paperOrderCount: 1500,
  feedbackCount: 1,
  requests: [{ type: 'ACCESS', status: 'DONE', receivedAt: '2026-11-01T12:00:00Z' }],
};

function request(overrides: Partial<DataRequestView> = {}): DataRequestView {
  return {
    requestId: '00000000-0000-4000-8000-0000000000d1',
    type: 'ACCESS',
    status: 'RECEIVED',
    receivedAt: '2026-11-10T12:00:00Z',
    dueAt: '2026-11-25T12:00:00Z',
    response: null,
    refusalReason: null,
    accessSummary: null,
    ...overrides,
  };
}

function renderWith(api: ReturnType<typeof fakeApi>, ui: React.ReactElement) {
  tokenStore.set('access-token-value');
  render(<Providers api={api}>{ui}</Providers>);
  return api;
}

function renderSection(api = fakeApi(), onSessionEnded = jest.fn()) {
  renderWith(
    api,
    <DataRequestsSection timeZone={ZONE} onSessionEnded={onSessionEnded} newKey={() => 'key-1'} />,
  );
  return { api, onSessionEnded };
}

describe('section "Meus dados (LGPD)" (T5)', () => {
  beforeEach(() => tokenStore.clear());

  it('shows the loading state in text and then the empty state', async () => {
    renderSection();

    expect(screen.getByTestId('data-requests-loading')).toHaveTextContent(text.loading);
    expect(await screen.findByTestId('data-requests-empty')).toHaveTextContent(text.none);
    expect(screen.getByRole('heading', { level: 2, name: text.title })).toBeInTheDocument();
  });

  it('shows an error with "Tentar de novo" that loads again', async () => {
    const api = fakeApi({
      listDataRequests: jest
        .fn()
        .mockRejectedValueOnce(new ApiError(503, 'SERVICE_UNAVAILABLE'))
        .mockResolvedValueOnce([request()]),
    });
    renderSection(api);

    expect(await screen.findByTestId('data-requests-error')).toHaveTextContent(text.errorTitle);
    await userEvent.click(screen.getByTestId('data-requests-retry'));
    expect(await screen.findAllByTestId('data-requests-row')).toHaveLength(1);
    expect(api.listDataRequests).toHaveBeenCalledTimes(2);
  });

  it('ends the session on a 401 of the list', async () => {
    const api = fakeApi({
      listDataRequests: jest.fn(async () => {
        throw new ApiError(401, 'UNAUTHENTICATED');
      }),
    });
    const { onSessionEnded } = renderSection(api);

    await waitFor(() => expect(onSessionEnded).toHaveBeenCalled());
  });

  it('adds the created request at the top of the list', async () => {
    const created = request({ requestId: 'new-id', type: 'CORRECTION' });
    const api = fakeApi({
      listDataRequests: jest.fn(async () => [request()]),
      createDataRequest: jest.fn(async () => created),
    });
    renderSection(api);

    await screen.findAllByTestId('data-requests-row');
    await userEvent.selectOptions(screen.getByTestId('data-request-type'), 'CORRECTION');
    await userEvent.type(screen.getByTestId('data-request-details'), 'Corrijam meu nome.');
    await userEvent.click(screen.getByTestId('data-request-submit'));

    const rows = await screen.findAllByTestId('data-requests-row');
    expect(rows).toHaveLength(2);
    expect(rows[0]).toHaveTextContent(text.types.CORRECTION ?? '');
    expect(api.createDataRequest).toHaveBeenCalledWith(
      { type: 'CORRECTION', details: 'Corrijam meu nome.' },
      'key-1',
    );
  });

  it('opens and closes the detail of a row by keyboard', async () => {
    const api = fakeApi({
      listDataRequests: jest.fn(async () => [request({ status: 'IN_PROGRESS' })]),
    });
    renderSection(api);

    const open = await screen.findByTestId('data-requests-row-open');
    open.focus();
    await userEvent.keyboard('{Enter}');
    expect(screen.getByTestId('data-request-detail')).toBeInTheDocument();
    expect(open).toHaveAttribute('aria-expanded', 'true');
    expect(screen.getByTestId('data-request-detail-pending')).toHaveTextContent(text.pending);
    screen.getByTestId('data-request-detail-close').focus();
    await userEvent.keyboard('{Enter}');
    expect(screen.queryByTestId('data-request-detail')).toBeNull();
  });
});

describe('form of a new request (T5, BR4.1, BR4.2)', () => {
  beforeEach(() => tokenStore.clear());

  function renderForm(api = fakeApi(), onCreated = jest.fn(), onSessionEnded = jest.fn()) {
    let key = 0;
    renderWith(
      api,
      <DataRequestForm
        timeZone={ZONE}
        onCreated={onCreated}
        onSessionEnded={onSessionEnded}
        newKey={() => `key-${++key}`}
      />,
    );
    return { api, onCreated, onSessionEnded };
  }

  it('labels the four types and the text, and the counter is described by the field', () => {
    renderForm();

    const type = screen.getByLabelText(text.typeLabel);
    expect(
      within(type)
        .getAllByRole('option')
        .map((option) => option.textContent),
    ).toEqual([text.types.ACCESS, text.types.CORRECTION, text.types.DELETION, text.types.OTHER]);
    const details = screen.getByLabelText(text.detailsLabel);
    expect(details).toHaveAttribute(
      'aria-describedby',
      expect.stringContaining('data-request-counter'),
    );
    expect(screen.getByTestId('data-request-counter')).toHaveTextContent(text.counter('0'));
  });

  it('counts characters as code points up to 1.000 and refuses more without calling the api', async () => {
    const { api } = renderForm();
    const details = screen.getByTestId('data-request-details');

    await userEvent.click(details);
    await userEvent.paste('a'.repeat(1000));
    expect(screen.getByTestId('data-request-counter')).toHaveTextContent(text.counter('1.000'));
    await userEvent.paste('😀');
    expect(screen.getByTestId('data-request-counter')).toHaveTextContent(text.counter('1.001'));
    expect(details).toHaveAttribute('aria-invalid', 'true');
    await userEvent.click(screen.getByTestId('data-request-submit'));

    expect(screen.getByTestId('data-request-error')).toHaveTextContent(text.tooLong);
    expect(api.createDataRequest).not.toHaveBeenCalled();
    expect(codePoints('😀a')).toBe(2);
  });

  it('refuses an empty text', async () => {
    const { api } = renderForm();

    await userEvent.click(screen.getByTestId('data-request-submit'));

    expect(screen.getByTestId('data-request-error')).toHaveTextContent(text.empty);
    expect(api.createDataRequest).not.toHaveBeenCalled();
  });

  it('announces the 409 of an open request of the type and keeps the text and the key', async () => {
    const api = fakeApi({
      createDataRequest: jest.fn(async () => {
        throw new ApiError(409, 'DATA_REQUEST_ALREADY_OPEN');
      }),
    });
    renderForm(api);

    await userEvent.type(screen.getByTestId('data-request-details'), 'De novo.');
    await userEvent.click(screen.getByTestId('data-request-submit'));
    expect(await screen.findByTestId('data-request-error')).toHaveTextContent(text.alreadyOpen);
    await waitFor(() => expect(screen.getByTestId('data-request-submit')).toBeEnabled());
    await userEvent.click(screen.getByTestId('data-request-submit'));
    await waitFor(() => expect(api.createDataRequest).toHaveBeenCalledTimes(2));

    expect(screen.getByTestId('data-request-error')).toHaveTextContent(text.alreadyOpen);
    expect(screen.getByTestId('data-request-messages')).toHaveAttribute('aria-live', 'polite');
    expect(screen.getByTestId('data-request-details')).toHaveValue('De novo.');
    expect(api.createDataRequest.mock.calls.map((call) => call[1])).toEqual(['key-1', 'key-1']);
  });

  it('announces the 429 with the minutes of Retry-After, or without them', async () => {
    const api = fakeApi({
      createDataRequest: jest
        .fn()
        .mockRejectedValueOnce(new ApiError(429, 'RATE_LIMITED', undefined, undefined, [], [], 125))
        .mockRejectedValueOnce(new ApiError(429, 'RATE_LIMITED')),
    });
    renderForm(api);

    await userEvent.type(screen.getByTestId('data-request-details'), 'Mais um.');
    await userEvent.click(screen.getByTestId('data-request-submit'));
    expect(await screen.findByTestId('data-request-error')).toHaveTextContent(text.rateLimited(3));
    await waitFor(() => expect(screen.getByTestId('data-request-submit')).toBeEnabled());
    await userEvent.click(screen.getByTestId('data-request-submit'));
    await waitFor(() =>
      expect(screen.getByTestId('data-request-error')).toHaveTextContent(text.rateLimitedNoWait),
    );
  });

  it('marks the field of a 400 and shows a failure for anything else', async () => {
    const api = fakeApi({
      createDataRequest: jest
        .fn()
        .mockRejectedValueOnce(
          new ApiError(400, 'VALIDATION_FAILED', undefined, undefined, [
            { field: 'type', message: 'x' },
          ]),
        )
        .mockRejectedValueOnce(new Error('network')),
    });
    renderForm(api);

    await userEvent.type(screen.getByTestId('data-request-details'), 'Texto.');
    await userEvent.click(screen.getByTestId('data-request-submit'));
    expect(await screen.findByTestId('data-request-error')).toHaveTextContent(text.invalid);
    expect(screen.getByTestId('data-request-type')).toHaveAttribute('aria-invalid', 'true');
    await waitFor(() => expect(screen.getByTestId('data-request-submit')).toBeEnabled());
    await userEvent.click(screen.getByTestId('data-request-submit'));
    await waitFor(() =>
      expect(screen.getByTestId('data-request-error')).toHaveTextContent(text.failed),
    );
  });

  it('after a 201 clears the text, announces the due date and renews the key', async () => {
    const created = request();
    const api = fakeApi({ createDataRequest: jest.fn(async () => created) });
    const { onCreated } = renderForm(api);

    await userEvent.type(screen.getByTestId('data-request-details'), 'Primeiro.');
    await userEvent.click(screen.getByTestId('data-request-submit'));
    expect(await screen.findByTestId('data-request-created')).toHaveTextContent(
      'Pedido registrado. O prazo de resposta é',
    );
    expect(screen.getByTestId('data-request-details')).toHaveValue('');
    await waitFor(() => expect(screen.getByTestId('data-request-submit')).toBeEnabled());
    await userEvent.type(screen.getByTestId('data-request-details'), 'Segundo.');
    await userEvent.click(screen.getByTestId('data-request-submit'));

    expect(onCreated).toHaveBeenCalledWith(created);
    expect(api.createDataRequest.mock.calls.map((call) => call[1])).toEqual(['key-1', 'key-2']);
  });

  it('ends the session on a 401', async () => {
    const api = fakeApi({
      createDataRequest: jest.fn(async () => {
        throw new ApiError(401, 'UNAUTHENTICATED');
      }),
    });
    const { onSessionEnded } = renderForm(api);

    await userEvent.type(screen.getByTestId('data-request-details'), 'Texto.');
    await userEvent.click(screen.getByTestId('data-request-submit'));

    await waitFor(() => expect(onSessionEnded).toHaveBeenCalled());
  });

  it('is reachable in reading order by keyboard', async () => {
    renderForm();

    await userEvent.tab();
    expect(screen.getByTestId('data-request-type')).toHaveFocus();
    await userEvent.tab();
    expect(screen.getByTestId('data-request-details')).toHaveFocus();
    await userEvent.tab();
    expect(screen.getByTestId('data-request-submit')).toHaveFocus();
  });
});

describe('list and detail of the requests (T5, BR4.4)', () => {
  it('shows type, situation and dates in text for each request', () => {
    render(
      <DataRequestList
        requests={[request(), request({ requestId: 'b', status: 'REFUSED', type: 'OTHER' })]}
        selectedId={null}
        timeZone={ZONE}
        onOpen={jest.fn()}
      />,
    );

    const rows = screen.getAllByTestId('data-requests-row');
    expect(rows[0]).toHaveTextContent(text.types.ACCESS ?? '');
    expect(
      within(rows[1] as HTMLElement).getByTestId('data-requests-row-status'),
    ).toHaveTextContent(text.status.REFUSED ?? '');
    expect(screen.getAllByRole('columnheader').map((cell) => cell.textContent)).toContain(
      text.columns.dueAt,
    );
  });

  it('renders a response with a script tag literally, never as HTML', () => {
    const hostile = '<script>alert(1)</script>\nlinha 2';
    render(
      <DataRequestDetail
        request={request({ status: 'DONE', response: hostile })}
        timeZone={ZONE}
        onClose={jest.fn()}
      />,
    );

    const shown = screen.getByTestId('data-request-detail-response');
    expect(shown.textContent).toBe(hostile);
    expect(shown.querySelector('script')).toBeNull();
    expect(shown).toHaveClass('pre-wrap');
  });

  it('shows the reason of a refusal and no summary', () => {
    render(
      <DataRequestDetail
        request={request({ status: 'REFUSED', refusalReason: 'identidade não confirmada' })}
        timeZone={ZONE}
        onClose={jest.fn()}
      />,
    );

    expect(screen.getByTestId('data-request-detail-reason')).toHaveTextContent(
      'identidade não confirmada',
    );
    expect(screen.queryByTestId('access-summary')).toBeNull();
  });

  it('shows the released summary of a done Access and "Sem resposta escrita" without a response', () => {
    render(
      <DataRequestDetail
        request={request({ status: 'DONE', accessSummary: SUMMARY })}
        timeZone={ZONE}
        onClose={jest.fn()}
      />,
    );

    expect(screen.getByTestId('data-request-detail-response')).toHaveTextContent(text.noResponse);
    expect(screen.getByTestId('access-summary-bots')).toHaveTextContent('2');
  });

  it('the summary shows the masked wallet, the counts in pt-BR and the requests in text', () => {
    render(<AccessSummaryView summary={SUMMARY} timeZone={ZONE} />);

    expect(screen.getByTestId('access-summary-wallet')).toHaveTextContent('TestW…1111');
    expect(screen.getByTestId('access-summary-provider')).toHaveTextContent('Phantom');
    expect(screen.getByTestId('access-summary-orders')).toHaveTextContent('1.500');
    expect(screen.getByTestId('access-summary-versions')).toHaveTextContent('Versão v1');
    expect(screen.getByTestId('access-summary-requests')).toHaveTextContent(
      `${text.types.ACCESS}: ${text.status.DONE}`,
    );
  });

  it('the summary says when there was no login and no acceptance yet', () => {
    render(
      <AccessSummaryView
        summary={{ ...SUMMARY, lastLoginAt: null, acceptedVersions: [] }}
        timeZone={ZONE}
      />,
    );

    expect(screen.getByTestId('access-summary-last-login')).toHaveTextContent(
      text.summary.neverLoggedIn,
    );
    expect(screen.getByTestId('access-summary-no-versions')).toHaveTextContent(
      text.summary.noVersions,
    );
  });
});
