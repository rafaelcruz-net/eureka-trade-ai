import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ApiError } from '@/lib/api/client';
import type { AdminDataRequest, AnonymizationResult } from '@/lib/api/admin-api';
import { messages } from '@/lib/i18n/messages';
import { tokenStore } from '@/lib/auth/token-store';
import { Providers, fakeAdminApi } from '@/test-support';
import {
  AnonymizeAccountButton,
  canAnonymize,
} from '@/features/data-requests/AnonymizeAccountButton';
import {
  AnonymizeAccountDialog,
  CONFIRMATION,
} from '@/features/data-requests/AnonymizeAccountDialog';
import { AdminDataRequestDetail } from '@/features/data-requests/AdminDataRequestDetail';

jest.mock('next/navigation', () => ({
  useRouter: () => ({ push: jest.fn(), replace: jest.fn() }),
  useParams: () => ({ id: 'req-1' }),
  useSearchParams: () => new URLSearchParams(),
}));

const text = messages.dataRequests;
const t8 = text.anonymization;

function deletion(overrides: Partial<AdminDataRequest> = {}): AdminDataRequest {
  return {
    requestId: 'req-1',
    type: 'DELETION',
    status: 'IN_PROGRESS',
    origin: 'ACCOUNT',
    receivedAt: '2026-11-01T12:00:00Z',
    dueAt: '2026-11-16T12:00:00Z',
    details: 'Quero excluir meus dados',
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

const RESULT: AnonymizationResult = {
  requestId: 'req-1',
  userId: 'user-1',
  anonymizedAt: '2026-11-10T12:00:00Z',
  sessionsRevoked: 2,
  botsStopped: 1,
  feedbackDeleted: 3,
  alreadyAnonymized: false,
};

function renderButton(api: ReturnType<typeof fakeAdminApi>, request = deletion()) {
  const onAnonymized = jest.fn();
  render(
    <Providers api={api}>
      <AnonymizeAccountButton
        request={request}
        onAnonymized={onAnonymized}
        newKey={() => 'key-t8'}
      />
    </Providers>,
  );
  return onAnonymized;
}

async function openDialog() {
  await userEvent.click(screen.getByTestId('anonymize-open'));
  return screen.getByRole('dialog');
}

describe('AnonymizeAccountButton (T6a, BR5.1)', () => {
  beforeEach(() => tokenStore.clear());

  it('is offered only for a DELETION in progress with an account not anonymized yet', () => {
    expect(canAnonymize(deletion())).toBe(true);
    expect(canAnonymize(deletion({ type: 'ACCESS' }))).toBe(false);
    expect(canAnonymize(deletion({ status: 'RECEIVED' }))).toBe(false);
    expect(canAnonymize(deletion({ status: 'DONE' }))).toBe(false);
    expect(canAnonymize(deletion({ userId: null }))).toBe(false);
    expect(canAnonymize(deletion({ accountAnonymized: true }))).toBe(false);
  });

  it('is hidden in any other case', () => {
    renderButton(fakeAdminApi(), deletion({ status: 'AWAITING_VERIFICATION' }));

    expect(screen.queryByTestId('anonymize-open')).toBeNull();
  });

  it('opens the dialog that says what leaves and what stays', async () => {
    renderButton(fakeAdminApi());

    const dialog = await openDialog();

    expect(dialog).toHaveAttribute('aria-modal', 'true');
    expect(dialog).toHaveAccessibleName(t8.title);
    const removed = within(screen.getByTestId('anonymize-dialog-removed')).getAllByRole('listitem');
    expect(removed.map((item) => item.textContent)).toEqual([...t8.removed]);
    const kept = within(screen.getByTestId('anonymize-dialog-kept')).getAllByRole('listitem');
    expect(kept.map((item) => item.textContent)).toEqual([...t8.kept]);
  });
});

describe('AnonymizeAccountDialog (T8, BR5.9, NFR4.1)', () => {
  beforeEach(() => tokenStore.clear());

  it('keeps "Anonimizar" disabled with its reason in text until ANONIMIZAR is typed exactly', async () => {
    renderButton(fakeAdminApi());
    await openDialog();
    const submit = screen.getByTestId('anonymize-dialog-submit');
    const field = screen.getByLabelText(t8.confirmLabel);

    expect(submit).toBeDisabled();
    expect(submit).toHaveAttribute('aria-describedby', 'anonymize-dialog-reason');
    expect(screen.getByTestId('anonymize-dialog-reason')).toHaveTextContent(t8.disabledReason);
    await userEvent.type(field, 'anonimizar');
    expect(submit).toBeDisabled();
    await userEvent.clear(field);
    await userEvent.type(field, `${CONFIRMATION} `);
    expect(submit).toBeDisabled();
    await userEvent.clear(field);
    await userEvent.type(field, CONFIRMATION);
    expect(submit).toBeEnabled();
    expect(screen.queryByTestId('anonymize-dialog-reason')).toBeNull();
  });

  it('sends the confirmation with the key of the dialog, closes and reports the result', async () => {
    const api = fakeAdminApi({ anonymizeAccount: jest.fn(async () => RESULT) });
    const onAnonymized = renderButton(api);
    await openDialog();

    await userEvent.type(screen.getByLabelText(t8.confirmLabel), CONFIRMATION);
    await userEvent.click(screen.getByTestId('anonymize-dialog-submit'));

    await waitFor(() => expect(onAnonymized).toHaveBeenCalledWith(RESULT));
    expect(api.anonymizeAccount).toHaveBeenCalledWith('req-1', CONFIRMATION, 'key-t8');
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('submits with Enter from the field, only by the keyboard', async () => {
    const api = fakeAdminApi({ anonymizeAccount: jest.fn(async () => RESULT) });
    const onAnonymized = renderButton(api);
    screen.getByTestId('anonymize-open').focus();
    await userEvent.keyboard('{Enter}');

    await userEvent.type(screen.getByLabelText(t8.confirmLabel), `${CONFIRMATION}{Enter}`);

    await waitFor(() => expect(onAnonymized).toHaveBeenCalledTimes(1));
  });

  it.each([
    ['IDENTITY_NOT_VERIFIED', 409],
    ['REQUEST_NOT_ELIGIBLE', 409],
    ['NO_LINKED_ACCOUNT', 409],
    ['ADMIN_ACCOUNT_NOT_ANONYMIZABLE', 409],
    ['CONFIRMATION_REQUIRED', 422],
    ['LOCK_TIMEOUT', 503],
  ])(
    'says %s in text with an icon, keeps the typed text and the same key',
    async (code, status) => {
      const api = fakeAdminApi({
        anonymizeAccount: jest
          .fn()
          .mockRejectedValueOnce(new ApiError(status, code))
          .mockResolvedValueOnce(RESULT),
      });
      const onAnonymized = renderButton(api);
      await openDialog();
      await userEvent.type(screen.getByLabelText(t8.confirmLabel), CONFIRMATION);

      await userEvent.click(screen.getByTestId('anonymize-dialog-submit'));

      const error = await screen.findByTestId('anonymize-dialog-error');
      expect(error).toHaveTextContent(text.errors[code] as string);
      expect(error.querySelector('[aria-hidden="true"]')).not.toBeNull();
      expect(screen.getByTestId('anonymize-dialog-messages')).toHaveAttribute(
        'aria-live',
        'polite',
      );
      expect(screen.getByLabelText(t8.confirmLabel)).toHaveValue(CONFIRMATION);
      await userEvent.click(screen.getByTestId('anonymize-dialog-submit'));
      await waitFor(() => expect(onAnonymized).toHaveBeenCalledWith(RESULT));
      expect(api.anonymizeAccount).toHaveBeenNthCalledWith(2, 'req-1', CONFIRMATION, 'key-t8');
    },
  );

  it('says a generic message for an unknown failure', async () => {
    const api = fakeAdminApi({
      anonymizeAccount: jest.fn().mockRejectedValue(new Error('offline')),
    });
    renderButton(api);
    await openDialog();
    await userEvent.type(screen.getByLabelText(t8.confirmLabel), CONFIRMATION);

    await userEvent.click(screen.getByTestId('anonymize-dialog-submit'));

    expect(await screen.findByTestId('anonymize-dialog-error')).toHaveTextContent(
      text.errors.GENERIC as string,
    );
  });

  it('keeps the focus inside with Tab and Shift+Tab', async () => {
    renderButton(fakeAdminApi());
    await openDialog();
    const field = screen.getByLabelText(t8.confirmLabel);
    const cancel = screen.getByTestId('anonymize-dialog-cancel');

    expect(field).toHaveFocus();
    await userEvent.tab();
    expect(cancel).toHaveFocus();
    await userEvent.tab();
    expect(field).toHaveFocus();
    await userEvent.tab({ shift: true });
    expect(cancel).toHaveFocus();
  });

  it('closes with Esc without any call and returns the focus to the button that opened it', async () => {
    const api = fakeAdminApi();
    renderButton(api);
    const opener = screen.getByTestId('anonymize-open');
    await openDialog();

    await userEvent.keyboard('{Escape}');

    expect(screen.queryByRole('dialog')).toBeNull();
    expect(opener).toHaveFocus();
    expect(api.anonymizeAccount).not.toHaveBeenCalled();
  });

  it('closes with Cancel without any call', async () => {
    const api = fakeAdminApi();
    renderButton(api);
    await openDialog();

    await userEvent.click(screen.getByTestId('anonymize-dialog-cancel'));

    expect(screen.queryByRole('dialog')).toBeNull();
    expect(api.anonymizeAccount).not.toHaveBeenCalled();
  });

  it('uses a new key of the api when no key source is given', async () => {
    const api = fakeAdminApi({ anonymizeAccount: jest.fn(async () => RESULT) });
    const onAnonymized = jest.fn();
    render(
      <Providers api={api}>
        <AnonymizeAccountDialog
          request={deletion()}
          onClose={jest.fn()}
          onAnonymized={onAnonymized}
        />
      </Providers>,
    );

    await userEvent.type(screen.getByLabelText(t8.confirmLabel), CONFIRMATION);
    await userEvent.click(screen.getByTestId('anonymize-dialog-submit'));

    await waitFor(() => expect(onAnonymized).toHaveBeenCalled());
    expect(api.anonymizeAccount).toHaveBeenCalledWith('req-1', CONFIRMATION, 'key-1');
  });
});

describe('the detail with the anonymization (T6a, US6.1)', () => {
  beforeEach(() => tokenStore.clear());

  it('anonymizes, announces it and shows the request again, done and with "Conta anonimizada"', async () => {
    let anonymized = false;
    const api = fakeAdminApi({
      getDataRequest: jest.fn(async () =>
        anonymized
          ? deletion({
              status: 'DONE',
              accountAnonymized: true,
              response: text.templateText.DELETION,
            })
          : deletion(),
      ),
      anonymizeAccount: jest.fn(async () => {
        anonymized = true;
        return RESULT;
      }),
    });
    render(
      <Providers api={api}>
        <AdminDataRequestDetail requestId="req-1" timeZone="UTC" now={() => 0} newKey={() => 'k'} />
      </Providers>,
    );
    await userEvent.click(await screen.findByTestId('anonymize-open'));

    await userEvent.type(screen.getByLabelText(t8.confirmLabel), CONFIRMATION);
    await userEvent.click(screen.getByTestId('anonymize-dialog-submit'));

    expect(await screen.findByTestId('account-state-anonymized')).toHaveTextContent(
      text.accountAnonymized,
    );
    expect(screen.getByTestId('data-request-note')).toHaveTextContent(t8.done);
    expect(screen.getByTestId('data-request-final-response')).toHaveTextContent(
      text.templateText.DELETION,
    );
    expect(screen.queryByTestId('anonymize-open')).toBeNull();
  });

  it('says a repetition when the account was already anonymized', async () => {
    const api = fakeAdminApi({
      getDataRequest: jest.fn(async () => deletion()),
      anonymizeAccount: jest.fn(async () => ({ ...RESULT, alreadyAnonymized: true })),
    });
    render(
      <Providers api={api}>
        <AdminDataRequestDetail requestId="req-1" timeZone="UTC" now={() => 0} newKey={() => 'k'} />
      </Providers>,
    );
    await userEvent.click(await screen.findByTestId('anonymize-open'));
    await userEvent.type(screen.getByLabelText(t8.confirmLabel), CONFIRMATION);
    await userEvent.click(screen.getByTestId('anonymize-dialog-submit'));

    expect(await screen.findByTestId('data-request-note')).toHaveTextContent(t8.already);
  });
});
