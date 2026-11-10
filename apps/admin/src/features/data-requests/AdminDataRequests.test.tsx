import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ApiError } from '@/lib/api/client';
import type { AccessSummary, AdminDataRequest, VerificationView } from '@/lib/api/admin-api';
import { messages } from '@/lib/i18n/messages';
import { tokenStore } from '@/lib/auth/token-store';
import { Providers, fakeAdminApi } from '@/test-support';
import { AdminDataRequestList } from '@/features/data-requests/AdminDataRequestList';
import { DeadlineAlert, daysUntil } from '@/features/data-requests/DeadlineAlert';
import { replyText, ReplyToContact } from '@/features/data-requests/ReplyToContact';
import {
  RegisterEmailRequestForm,
  toInstant,
} from '@/features/data-requests/RegisterEmailRequestForm';
import { VerificationPanel } from '@/features/data-requests/VerificationPanel';
import { AccessSummaryView } from '@/features/data-requests/AccessSummaryView';
import {
  AdminDataRequestDetail,
  needsVerification,
} from '@/features/data-requests/AdminDataRequestDetail';
import { copyText, reasonOf } from '@/features/data-requests/errors';
import DataRequestsRoute from '@/app/pedidos/page';
import RegisterDataRequestRoute from '@/app/pedidos/novo/page';
import DataRequestRoute from '@/app/pedidos/[id]/page';

const push = jest.fn();

jest.mock('next/navigation', () => ({
  useRouter: () => ({ push, replace: push }),
  useParams: () => ({ id: 'req-1' }),
  useSearchParams: () => new URLSearchParams(),
}));

const text = messages.dataRequests;
const NOW = Date.parse('2026-11-10T12:00:00Z');

const SUMMARY: AccessSummary = {
  walletMasked: 'TestW…1111',
  provider: 'PHANTOM',
  accountCreatedAt: '2026-01-01T00:00:00Z',
  lastLoginAt: null,
  acceptedVersions: [],
  botCount: 2,
  paperOrderCount: 5,
  feedbackCount: 1,
  requests: [{ type: 'ACCESS', status: 'RECEIVED', receivedAt: '2026-11-10T12:00:00Z' }],
};

function adminRequest(overrides: Partial<AdminDataRequest> = {}): AdminDataRequest {
  return {
    requestId: 'req-1',
    type: 'OTHER',
    status: 'RECEIVED',
    origin: 'ACCOUNT',
    receivedAt: '2026-11-01T12:00:00Z',
    dueAt: '2026-11-16T12:00:00Z',
    details: 'Texto do pedido',
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

const VERIFICATION: VerificationView = {
  message: 'MemeTrade - verificação de pedido de titular\nDomínio: localhost:3000',
  expiresAt: '2026-11-11T12:00:00Z',
  state: 'PENDING',
};

function renderIn(api: ReturnType<typeof fakeAdminApi>, ui: React.ReactElement) {
  render(<Providers api={api}>{ui}</Providers>);
  return api;
}

describe('list of the requests (T6, BR4.16, US5.9)', () => {
  beforeEach(() => {
    tokenStore.clear();
    push.mockReset();
  });

  it('shows loading and then the empty state in text', async () => {
    renderIn(fakeAdminApi(), <AdminDataRequestList timeZone="UTC" now={() => NOW} />);

    expect(screen.getByTestId('data-requests-loading')).toHaveTextContent(text.loading);
    expect(await screen.findByTestId('data-requests-empty')).toHaveTextContent(text.empty);
  });

  it('shows type, origin, account, dates and situation, with the alert as icon and text', async () => {
    const api = fakeAdminApi({
      listDataRequests: jest.fn(async () => ({
        items: [
          adminRequest({ requestId: 'a', overdue: true }),
          adminRequest({
            requestId: 'b',
            origin: 'EMAIL',
            userId: null,
            dueSoon: true,
            dueAt: '2026-11-12T13:00:00Z',
          }),
          adminRequest({ requestId: 'c' }),
        ],
      })),
    });
    renderIn(api, <AdminDataRequestList timeZone="UTC" now={() => NOW} />);

    const rows = await screen.findAllByTestId('data-requests-row');
    expect(within(rows[0] as HTMLElement).getByTestId('deadline-alert')).toHaveTextContent(
      text.overdue,
    );
    expect(within(rows[1] as HTMLElement).getByTestId('deadline-alert')).toHaveTextContent(
      'Vence em 3 dias',
    );
    expect(rows[1]).toHaveTextContent(text.noAccount);
    expect(rows[1]).toHaveTextContent(text.origins.EMAIL ?? '');
    expect(within(rows[2] as HTMLElement).queryByTestId('deadline-alert')).toBeNull();
    expect(
      within(rows[0] as HTMLElement)
        .getByTestId('deadline-alert')
        .querySelector('[aria-hidden]'),
    ).not.toBeNull();
  });

  it('filters by situation with a labelled select and pages with "Carregar mais"', async () => {
    const api = fakeAdminApi({
      listDataRequests: jest
        .fn()
        .mockResolvedValueOnce({ items: [adminRequest({ requestId: 'a' })], nextCursor: 'c1' })
        .mockResolvedValueOnce({ items: [adminRequest({ requestId: 'b' })], nextCursor: null })
        .mockResolvedValueOnce({ items: [], nextCursor: null }),
    });
    renderIn(api, <AdminDataRequestList timeZone="UTC" now={() => NOW} />);

    await userEvent.click(await screen.findByTestId('data-requests-more'));
    expect(await screen.findAllByTestId('data-requests-row')).toHaveLength(2);
    await userEvent.selectOptions(screen.getByLabelText(text.filterLabel), 'DONE');

    await waitFor(() => expect(api.listDataRequests).toHaveBeenLastCalledWith('DONE', null));
    expect(api.listDataRequests).toHaveBeenNthCalledWith(2, null, 'c1');
  });

  it('shows an error with "Tentar de novo"', async () => {
    const api = fakeAdminApi({
      listDataRequests: jest
        .fn()
        .mockRejectedValueOnce(new ApiError(503, 'SERVICE_UNAVAILABLE'))
        .mockResolvedValueOnce({ items: [], nextCursor: null }),
    });
    renderIn(api, <AdminDataRequestList timeZone="UTC" now={() => NOW} />);

    expect(await screen.findByTestId('data-requests-error')).toHaveTextContent(text.error);
    await userEvent.click(screen.getByTestId('data-requests-retry'));
    expect(await screen.findByTestId('data-requests-empty')).toBeInTheDocument();
  });

  it('days until the due date are at least one', () => {
    expect(daysUntil('2026-11-10T13:00:00Z', NOW)).toBe(1);
    expect(daysUntil('2026-11-13T11:00:00Z', NOW)).toBe(3);
    expect(text.dueIn(1)).toBe('Vence em 1 dia');
  });

  it('no alert for a request still far from its due date', () => {
    render(<DeadlineAlert request={adminRequest()} now={NOW} />);

    expect(screen.queryByTestId('deadline-alert')).toBeNull();
  });

  it('the routes render the list, the form and the detail inside the shell', async () => {
    const api = fakeAdminApi({ getDataRequest: jest.fn(async () => adminRequest()) });
    const { unmount } = render(
      <Providers api={api}>
        <DataRequestsRoute />
      </Providers>,
    );
    expect(await screen.findByTestId('data-requests-page')).toBeInTheDocument();
    unmount();
    const second = render(
      <Providers api={api}>
        <RegisterDataRequestRoute />
      </Providers>,
    );
    expect(await screen.findByTestId('register-form')).toBeInTheDocument();
    second.unmount();
    render(
      <Providers api={api}>
        <DataRequestRoute />
      </Providers>,
    );
    expect(await screen.findByTestId('data-request-detail')).toBeInTheDocument();
    expect(api.getDataRequest).toHaveBeenCalledWith('req-1');
  });
});

describe('registration of a request received by e-mail (T6b, BR4.10)', () => {
  beforeEach(() => push.mockReset());

  function renderForm(api = fakeAdminApi()) {
    let key = 0;
    return renderIn(api, <RegisterEmailRequestForm newKey={() => `key-${++key}`} />);
  }

  it('labels every field and refuses empty values without calling the api', async () => {
    const api = renderForm();

    expect(screen.getByLabelText(text.form.type)).toBeInTheDocument();
    expect(screen.getByLabelText(text.form.contact)).toBeInTheDocument();
    expect(screen.getByLabelText(text.form.receivedAt)).toBeInTheDocument();
    expect(screen.getByLabelText(text.form.userId)).toBeInTheDocument();
    await userEvent.click(screen.getByTestId('register-submit'));

    expect(screen.getByTestId('register-details-error')).toHaveTextContent(text.form.required);
    expect(screen.getByTestId('register-contact-error')).toHaveTextContent(text.form.invalidEmail);
    expect(screen.getByTestId('register-receivedAt-error')).toHaveTextContent(text.form.required);
    expect(api.registerEmailRequest).not.toHaveBeenCalled();
  });

  it('sends the values with the key and opens the registered request', async () => {
    const api = fakeAdminApi({
      registerEmailRequest: jest.fn(async () =>
        adminRequest({ requestId: 'new-1', origin: 'EMAIL' }),
      ),
    });
    renderForm(api);

    await userEvent.selectOptions(screen.getByTestId('register-type'), 'DELETION');
    await userEvent.type(screen.getByTestId('register-details'), 'Apaguem meus dados.');
    await userEvent.type(screen.getByTestId('register-contact'), 'titular@example.org');
    await userEvent.type(screen.getByTestId('register-received-at'), '2026-11-09T10:30');
    await userEvent.type(screen.getByTestId('register-user-id'), ' user-1 ');
    await userEvent.click(screen.getByTestId('register-submit'));

    await waitFor(() => expect(push).toHaveBeenCalledWith('/pedidos/new-1'));
    const [body, key] = api.registerEmailRequest.mock.calls[0] ?? [];
    expect(key).toBe('key-1');
    expect(body).toMatchObject({
      type: 'DELETION',
      details: 'Apaguem meus dados.',
      contact: 'titular@example.org',
      userId: 'user-1',
    });
    expect(screen.getByTestId('register-counter')).toHaveTextContent(text.form.counter('19'));
  });

  it('marks the fields the server refused and announces a 409', async () => {
    const api = fakeAdminApi({
      registerEmailRequest: jest
        .fn()
        .mockRejectedValueOnce(
          new ApiError(400, 'VALIDATION_FAILED', undefined, [
            { field: 'userId', message: 'x' },
            { field: 'receivedAt', message: 'y' },
          ]),
        )
        .mockRejectedValueOnce(new ApiError(409, 'DATA_REQUEST_ALREADY_OPEN')),
    });
    renderForm(api);

    await userEvent.type(screen.getByTestId('register-details'), 'Texto.');
    await userEvent.type(screen.getByTestId('register-contact'), 'titular@example.org');
    await userEvent.type(screen.getByTestId('register-received-at'), '2026-11-09T10:30');
    await userEvent.click(screen.getByTestId('register-submit'));
    expect(await screen.findByTestId('register-userId-error')).toHaveTextContent(
      text.form.fieldErrors.userId ?? '',
    );
    expect(screen.getByTestId('register-receivedAt-error')).toBeInTheDocument();
    await waitFor(() => expect(screen.getByTestId('register-submit')).toBeEnabled());
    await userEvent.click(screen.getByTestId('register-submit'));

    expect(await screen.findByTestId('register-error')).toHaveTextContent(
      text.errors.DATA_REQUEST_ALREADY_OPEN ?? '',
    );
    expect(api.registerEmailRequest.mock.calls.map((call) => call[1])).toEqual(['key-1', 'key-1']);
  });

  it('refuses more than 1.000 characters and turns a local date into UTC', async () => {
    const api = renderForm();

    await userEvent.click(screen.getByTestId('register-details'));
    await userEvent.paste('x'.repeat(1001));
    await userEvent.click(screen.getByTestId('register-submit'));

    expect(screen.getByTestId('register-details-error')).toHaveTextContent(text.form.tooLong);
    expect(api.registerEmailRequest).not.toHaveBeenCalled();
    expect(toInstant('')).toBeNull();
    expect(toInstant('not a date')).toBeNull();
    expect(toInstant('2026-11-09T10:30')).toMatch(/^2026-11-09T\d\d:30:00\.000Z$/);
  });
});

describe('verification of identity (T6c, BR4.11, BR4.12)', () => {
  function renderPanel(request: AdminDataRequest, api = fakeAdminApi(), onChanged = jest.fn()) {
    let key = 0;
    renderIn(
      api,
      <VerificationPanel
        request={request}
        timeZone="UTC"
        onChanged={onChanged}
        newKey={() => `key-${++key}`}
      />,
    );
    return { api, onChanged };
  }

  it('starts the verification and reloads the request', async () => {
    const reloaded = adminRequest({ status: 'AWAITING_VERIFICATION', verification: VERIFICATION });
    const api = fakeAdminApi({
      startVerification: jest.fn(async () => VERIFICATION),
      getDataRequest: jest.fn(async () => reloaded),
    });
    const { onChanged } = renderPanel(
      adminRequest({ origin: 'EMAIL', type: 'ACCESS', contact: 'a@b.org' }),
      api,
    );

    await userEvent.click(screen.getByTestId('verification-start'));

    await waitFor(() => expect(onChanged).toHaveBeenCalledWith(reloaded));
    expect(api.startVerification).toHaveBeenCalledWith('req-1', 'key-1');
  });

  it('shows the message as text, its validity and copies it', async () => {
    const writeText = jest.fn(async () => undefined);
    Object.assign(navigator, { clipboard: { writeText } });
    renderPanel(adminRequest({ status: 'AWAITING_VERIFICATION', verification: VERIFICATION }));

    expect(screen.getByTestId('verification-message').textContent).toBe(VERIFICATION.message);
    expect(screen.getByTestId('verification-state')).toHaveTextContent(
      text.verification.states.PENDING ?? '',
    );
    await userEvent.click(screen.getByTestId('verification-copy'));

    expect(writeText).toHaveBeenCalledWith(VERIFICATION.message);
    expect(await screen.findByTestId('verification-note')).toHaveTextContent(
      text.verification.copied,
    );
  });

  it('a refused signature keeps the request waiting and says why; a valid one confirms', async () => {
    const confirmed = adminRequest({ status: 'IN_PROGRESS', identityVerified: true });
    const api = fakeAdminApi({
      confirmVerification: jest
        .fn()
        .mockRejectedValueOnce(new ApiError(422, 'VERIFICATION_INVALID'))
        .mockResolvedValueOnce(confirmed),
    });
    const { onChanged } = renderPanel(
      adminRequest({ status: 'AWAITING_VERIFICATION', verification: VERIFICATION }),
      api,
    );

    await userEvent.click(screen.getByTestId('verification-check'));
    expect(screen.getByTestId('verification-error')).toHaveTextContent(text.form.required);
    await userEvent.type(screen.getByLabelText(text.verification.signature), 'assinatura-errada');
    await userEvent.click(screen.getByTestId('verification-check'));
    expect(await screen.findByTestId('verification-error')).toHaveTextContent(
      text.errors.VERIFICATION_INVALID ?? '',
    );
    await waitFor(() => expect(screen.getByTestId('verification-check')).toBeEnabled());
    await userEvent.clear(screen.getByTestId('verification-signature'));
    await userEvent.type(screen.getByTestId('verification-signature'), 'assinatura-boa');
    await userEvent.click(screen.getByTestId('verification-check'));

    await waitFor(() => expect(onChanged).toHaveBeenCalledWith(confirmed));
    expect(screen.getByTestId('verification-note')).toHaveTextContent(text.verification.confirmed);
    expect(screen.getByTestId('verification-signature')).toHaveValue('');
    expect(api.confirmVerification.mock.calls.map((call) => call[2])).toEqual(['key-2', 'key-3']);
  });

  it('an expired verification shows its state and no signature field', () => {
    renderPanel(
      adminRequest({ status: 'REFUSED', verification: { ...VERIFICATION, state: 'EXPIRED' } }),
    );

    expect(screen.getByTestId('verification-state')).toHaveTextContent(
      text.verification.states.EXPIRED ?? '',
    );
    expect(screen.queryByTestId('verification-signature')).toBeNull();
  });

  it('says so when the clipboard refuses', async () => {
    Object.assign(navigator, {
      clipboard: { writeText: jest.fn(async () => Promise.reject(new Error('denied'))) },
    });
    const warn = jest.spyOn(console, 'warn').mockImplementation(() => undefined);

    expect(await copyText('x')).toBe(false);
    Object.assign(navigator, { clipboard: undefined });
    expect(await copyText('x')).toBe(false);
    expect(reasonOf(new Error('x'))).toBe(text.errors.GENERIC);
    expect(reasonOf(new ApiError(409, 'SOMETHING_ELSE'))).toBe(text.errors.GENERIC);
    warn.mockRestore();
  });
});

describe('reply to the contact (BR4.17)', () => {
  it('shows the ready text of a refusal, copies it and marks it sent once', async () => {
    const writeText = jest.fn(async () => undefined);
    Object.assign(navigator, { clipboard: { writeText } });
    const closed = adminRequest({
      origin: 'EMAIL',
      status: 'REFUSED',
      refusalReason: 'identidade não confirmada',
      contact: 'a@b.org',
    });
    const sent = { ...closed, replySentAt: '2026-11-10T13:00:00Z' };
    const onChanged = jest.fn();
    const api = fakeAdminApi({ markReplySent: jest.fn(async () => sent) });
    renderIn(
      api,
      <ReplyToContact request={closed} timeZone="UTC" onChanged={onChanged} newKey={() => 'k'} />,
    );

    expect(screen.getByTestId('reply-text')).toHaveTextContent('identidade não confirmada');
    await userEvent.click(screen.getByTestId('reply-copy'));
    expect(writeText).toHaveBeenCalledWith(replyText(closed, 'UTC'));
    await userEvent.click(screen.getByTestId('reply-mark-sent'));

    await waitFor(() => expect(onChanged).toHaveBeenCalledWith(sent));
    expect(api.markReplySent).toHaveBeenCalledWith('req-1', 'k');
  });

  it('shows the instant when the reply was already sent and the error of a refused mark', async () => {
    const api = fakeAdminApi({
      markReplySent: jest.fn(async () => {
        throw new ApiError(409, 'INVALID_TRANSITION');
      }),
    });
    const { unmount } = render(
      <Providers api={api}>
        <ReplyToContact
          request={adminRequest({
            origin: 'EMAIL',
            status: 'DONE',
            response: 'Feito',
            replySentAt: '2026-11-10T13:00:00Z',
          })}
          timeZone="UTC"
          onChanged={jest.fn()}
          newKey={() => 'k'}
        />
      </Providers>,
    );
    expect(screen.getByTestId('reply-sent-at')).toHaveTextContent('10/11/2026');
    unmount();
    renderIn(
      api,
      <ReplyToContact
        request={adminRequest({ origin: 'EMAIL', status: 'DONE', response: 'Feito' })}
        timeZone="UTC"
        onChanged={jest.fn()}
        newKey={() => 'k'}
      />,
    );
    await userEvent.click(screen.getByTestId('reply-mark-sent'));

    expect(await screen.findByTestId('reply-error')).toHaveTextContent(
      text.errors.INVALID_TRANSITION ?? '',
    );
  });
});

describe('detail of a request (T6a, BR4.5, BR4.8, BR4.9, BR4.17)', () => {
  function renderDetail(api: ReturnType<typeof fakeAdminApi>) {
    let key = 0;
    renderIn(
      api,
      <AdminDataRequestDetail
        requestId="req-1"
        timeZone="UTC"
        now={() => NOW}
        newKey={() => `key-${++key}`}
      />,
    );
    return api;
  }

  it('shows loading, then the text of the request literally even with a script tag', async () => {
    const hostile = '<script>alert(1)</script>';
    renderDetail(
      fakeAdminApi({ getDataRequest: jest.fn(async () => adminRequest({ details: hostile })) }),
    );

    expect(screen.getByTestId('data-request-loading')).toHaveTextContent(text.detailLoading);
    const details = await screen.findByTestId('data-request-details');
    expect(details.textContent).toBe(hostile);
    expect(details.querySelector('script')).toBeNull();
    expect(screen.getByRole('heading', { level: 1 })).toHaveFocus();
  });

  it('says "Pedido não encontrado" on a 404 and retries on demand', async () => {
    const api = fakeAdminApi({
      getDataRequest: jest
        .fn()
        .mockRejectedValueOnce(new ApiError(404, 'NOT_FOUND'))
        .mockResolvedValueOnce(adminRequest()),
    });
    renderDetail(api);

    expect(await screen.findByTestId('data-request-error')).toHaveTextContent(text.notFound);
    await userEvent.click(screen.getByTestId('data-request-retry'));
    expect(await screen.findByTestId('data-request-details')).toBeInTheDocument();
  });

  it('prefills the model of a Correction and walks RECEIVED to IN_PROGRESS to DONE', async () => {
    const received = adminRequest({ type: 'CORRECTION' });
    const started = { ...received, status: 'IN_PROGRESS' as const };
    const done = { ...started, status: 'DONE' as const, response: text.templateText.CORRECTION };
    const api = fakeAdminApi({
      getDataRequest: jest.fn(async () => received),
      transitionDataRequest: jest.fn().mockResolvedValueOnce(started).mockResolvedValueOnce(done),
    });
    renderDetail(api);

    expect(await screen.findByTestId('data-request-response')).toHaveValue(
      text.templateText.CORRECTION,
    );
    await userEvent.click(screen.getByTestId('data-request-start'));
    expect(await screen.findByTestId('data-request-note')).toHaveTextContent(
      text.transitioned(text.status.IN_PROGRESS ?? ''),
    );
    await userEvent.click(screen.getByTestId('data-request-done'));

    expect(await screen.findByTestId('data-request-final-response')).toHaveTextContent(
      'O endereço da carteira não pode ser corrigido',
    );
    expect(api.transitionDataRequest).toHaveBeenLastCalledWith(
      'req-1',
      {
        to: 'DONE',
        response: text.templateText.CORRECTION,
        refusalReason: null,
        releaseAccessSummary: false,
      },
      'key-2',
    );
  });

  it('refuses with a model reason and shows a 422 when the reason is missing', async () => {
    const progressing = adminRequest({ status: 'IN_PROGRESS' });
    const api = fakeAdminApi({
      getDataRequest: jest.fn(async () => progressing),
      transitionDataRequest: jest
        .fn()
        .mockRejectedValueOnce(new ApiError(422, 'VALIDATION_FAILED'))
        .mockResolvedValueOnce({ ...progressing, status: 'REFUSED', refusalReason: 'x' }),
    });
    renderDetail(api);

    await userEvent.click(await screen.findByTestId('data-request-refuse'));
    expect(await screen.findByTestId('data-request-action-error')).toHaveTextContent(
      text.errors.VALIDATION_FAILED ?? '',
    );
    await userEvent.click(screen.getByTestId('data-request-template-wallet'));
    expect(screen.getByTestId('data-request-reason')).toHaveValue(text.templateText.WALLET);
    await userEvent.click(screen.getByTestId('data-request-template-identity'));
    expect(screen.getByTestId('data-request-reason')).toHaveValue(text.templateText.IDENTITY);
    await waitFor(() => expect(screen.getByTestId('data-request-refuse')).toBeEnabled());
    await userEvent.click(screen.getByTestId('data-request-refuse'));

    expect(await screen.findByTestId('data-request-final-reason')).toHaveTextContent('x');
  });

  it('shows the preview of an Access and releases the summary; a 409 without identity is announced', async () => {
    const access = adminRequest({
      type: 'ACCESS',
      status: 'IN_PROGRESS',
      origin: 'EMAIL',
      contact: 'a@b.org',
      userId: null,
      accessSummaryPreview: SUMMARY,
    });
    const api = fakeAdminApi({
      getDataRequest: jest.fn(async () => access),
      transitionDataRequest: jest.fn(async () => {
        throw new ApiError(409, 'IDENTITY_NOT_VERIFIED');
      }),
    });
    renderDetail(api);

    expect(await screen.findByTestId('data-request-preview-bots')).toHaveTextContent('2');
    await userEvent.click(screen.getByLabelText(text.releaseSummary));
    await userEvent.click(screen.getByTestId('data-request-done'));

    expect(await screen.findByTestId('data-request-action-error')).toHaveTextContent(
      text.errors.IDENTITY_NOT_VERIFIED ?? '',
    );
    expect(api.transitionDataRequest).toHaveBeenCalledWith(
      'req-1',
      expect.objectContaining({ to: 'DONE', releaseAccessSummary: true }),
      'key-1',
    );
    expect(screen.getByTestId('data-request-contact')).toHaveTextContent('a@b.org');
  });

  it('an EMAIL Access of an account goes through the verification and cannot be started directly', async () => {
    renderDetail(
      fakeAdminApi({
        getDataRequest: jest.fn(async () =>
          adminRequest({ type: 'ACCESS', origin: 'EMAIL', contact: 'a@b.org', overdue: true }),
        ),
      }),
    );

    expect(await screen.findByTestId('verification-panel')).toBeInTheDocument();
    expect(screen.queryByTestId('data-request-start')).toBeNull();
    expect(screen.getByTestId('data-request-status')).toHaveTextContent(text.overdue);
    expect(
      needsVerification(adminRequest({ type: 'DELETION', origin: 'EMAIL', userId: 'u' })),
    ).toBe(true);
    expect(needsVerification(adminRequest({ type: 'OTHER', origin: 'EMAIL' }))).toBe(false);
  });

  it('a closed EMAIL request shows the released summary and the reply to the contact', async () => {
    renderDetail(
      fakeAdminApi({
        getDataRequest: jest.fn(async () =>
          adminRequest({
            type: 'ACCESS',
            origin: 'EMAIL',
            status: 'DONE',
            contact: 'a@b.org',
            response: 'Segue.',
            identityVerified: true,
            accessSummary: SUMMARY,
          }),
        ),
      }),
    );

    expect(await screen.findByTestId('reply-to-contact')).toBeInTheDocument();
    expect(screen.getByTestId('data-request-released-wallet')).toHaveTextContent('TestW…1111');
    expect(screen.getByTestId('data-request-verified')).toHaveTextContent(text.identityVerified);
    expect(screen.queryByTestId('data-request-actions')).toBeNull();
  });

  it('the actions are reachable by keyboard in reading order', async () => {
    renderDetail(fakeAdminApi({ getDataRequest: jest.fn(async () => adminRequest()) }));

    await screen.findByTestId('data-request-start');
    screen.getByTestId('data-request-template-correction').focus();
    await userEvent.tab();
    expect(screen.getByTestId('data-request-template-identity')).toHaveFocus();
    await userEvent.tab();
    expect(screen.getByTestId('data-request-template-wallet')).toHaveFocus();
    await userEvent.tab();
    expect(screen.getByTestId('data-request-response')).toHaveFocus();
    await userEvent.tab();
    expect(screen.getByTestId('data-request-reason')).toHaveFocus();
    await userEvent.tab();
    expect(screen.getByTestId('data-request-start')).toHaveFocus();
  });
});

describe('Access summary of the administrator (BR4.8)', () => {
  it('shows the masked wallet, the counts and the requests as text', () => {
    render(<AccessSummaryView summary={SUMMARY} title={text.preview} timeZone="UTC" testId="p" />);

    expect(screen.getByRole('heading', { name: text.preview })).toBeInTheDocument();
    expect(screen.getByTestId('p-wallet')).toHaveTextContent('TestW…1111');
    expect(screen.getByTestId('p-orders')).toHaveTextContent('5');
    expect(screen.getByText(text.summary.neverLoggedIn)).toBeInTheDocument();
    expect(screen.getByText(text.summary.noVersions)).toBeInTheDocument();
  });
});
