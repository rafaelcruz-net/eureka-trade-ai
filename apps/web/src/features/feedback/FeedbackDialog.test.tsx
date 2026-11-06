import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ApiError, createApiClient } from '@/lib/api/client';
import { messages } from '@/lib/i18n/messages';
import { tokenStore } from '@/lib/auth/token-store';
import { Providers, fakeApi } from '@/features/auth/test-support';
import { FeedbackButton } from '@/features/feedback/FeedbackButton';
import { FeedbackDialog } from '@/features/feedback/FeedbackDialog';
import { codePoints, screenOf } from '@/features/feedback/screens';

jest.mock('next/navigation', () => ({
  useRouter: () => ({ replace: jest.fn(), push: jest.fn() }),
  useSearchParams: () => new URLSearchParams(),
}));

const text = messages.feedback;

function renderButton(api = fakeApi(), path = '/scanner') {
  tokenStore.set('access-token-value');
  render(
    <Providers api={api}>
      <FeedbackButton currentPath={() => path} />
    </Providers>,
  );
  return api;
}

async function openDialog(path = '/scanner', api = fakeApi()) {
  renderButton(api, path);
  await userEvent.click(screen.getByTestId('app-feedback-button'));
  return api;
}

describe('screen of origin and characters (BR6.1, BR7.3)', () => {
  it('maps every route of the guest app to its screen', () => {
    expect(screenOf('/convite/abc')).toBe('S1');
    expect(screenOf('/entrar')).toBe('S1');
    expect(screenOf('/')).toBe('S2');
    expect(screenOf('/scanner')).toBe('S3');
    expect(screenOf('/bots/new/123')).toBe('S4');
    expect(screenOf('/bots/0b0e')).toBe('S5');
    expect(screenOf('/pnl')).toBe('S6');
    expect(screenOf('/conta')).toBe('S7');
    expect(screenOf('/bots')).toBe('S8');
    expect(screenOf(null)).toBe('S2');
  });

  it('counts code points like the server, so an emoji is one character', () => {
    expect(codePoints('abc')).toBe(3);
    expect(codePoints('😀😀')).toBe(2);
  });
});

describe('Feedback dialog S9 (US6.1)', () => {
  beforeEach(() => tokenStore.clear());

  it('opens over the screen with the screen of the route, the notice and an empty counter', async () => {
    await openDialog('/pnl');

    expect(screen.getByTestId('feedback-dialog')).toHaveAttribute('aria-modal', 'true');
    expect(screen.getByTestId('feedback-dialog-screen')).toHaveTextContent(text.screen('S6'));
    expect(screen.getByTestId('feedback-dialog-visibility')).toHaveTextContent(text.visibility);
    expect(screen.getByTestId('feedback-dialog-counter')).toHaveTextContent('0 / 2.000');
    expect(screen.getByTestId('feedback-dialog-text')).toHaveFocus();
  });

  it('updates the counter with the characters used', async () => {
    await openDialog();

    await userEvent.type(screen.getByTestId('feedback-dialog-text'), 'Olá \u{1F600}');

    expect(screen.getByTestId('feedback-dialog-counter')).toHaveTextContent('5 / 2.000');
  });

  it('refuses a blank text without calling the API', async () => {
    const api = await openDialog();

    await userEvent.type(screen.getByTestId('feedback-dialog-text'), '   ');
    await userEvent.click(screen.getByTestId('feedback-dialog-send'));

    expect(screen.getByTestId('feedback-dialog-error')).toHaveTextContent(text.empty);
    expect(api.sendFeedback).not.toHaveBeenCalled();
  });

  it('refuses more than 2000 characters without calling the API and keeps the text', async () => {
    const api = await openDialog();
    const long = 'a'.repeat(2001);

    fireEvent.change(screen.getByTestId('feedback-dialog-text'), { target: { value: long } });
    await userEvent.click(screen.getByTestId('feedback-dialog-send'));

    expect(screen.getByTestId('feedback-dialog-counter')).toHaveTextContent('2.001 / 2.000');
    expect(screen.getByTestId('feedback-dialog-error')).toHaveTextContent(text.tooLong);
    expect(screen.getByTestId('feedback-dialog-text')).toHaveValue(long);
    expect(api.sendFeedback).not.toHaveBeenCalled();
  });

  it('sends screen and text, closes, announces and returns the focus to the header item', async () => {
    const api = await openDialog('/scanner');

    await userEvent.type(screen.getByTestId('feedback-dialog-text'), 'A lista demorou.');
    await userEvent.click(screen.getByTestId('feedback-dialog-send'));

    await waitFor(() => expect(screen.queryByTestId('feedback-dialog')).toBeNull());
    expect(api.sendFeedback).toHaveBeenCalledWith(
      { screen: 'S3', text: 'A lista demorou.' },
      expect.any(String),
    );
    expect(screen.getByTestId('app-feedback-status')).toHaveTextContent(text.sent);
    await waitFor(() => expect(screen.getByTestId('app-feedback-button')).toHaveFocus());
  });

  it('shows the reason of a 400 and keeps the text', async () => {
    const api = fakeApi({
      sendFeedback: jest.fn(async () => {
        throw new ApiError(400, 'VALIDATION_FAILED', undefined, undefined, [
          { field: 'screen', message: 'Must be one of S1 to S9.' },
        ]);
      }),
    });
    await openDialog('/scanner', api);

    await userEvent.type(screen.getByTestId('feedback-dialog-text'), 'Texto mantido');
    await userEvent.click(screen.getByTestId('feedback-dialog-send'));

    expect(await screen.findByTestId('feedback-dialog-error')).toHaveTextContent(text.invalid);
    expect(screen.getByTestId('feedback-dialog-text')).toHaveValue('Texto mantido');
  });

  it('shows the wait of a 429 in minutes and keeps the text', async () => {
    const api = fakeApi({
      sendFeedback: jest.fn(async () => {
        throw new ApiError(429, 'RATE_LIMITED', undefined, undefined, [], [], 600);
      }),
    });
    await openDialog('/scanner', api);

    await userEvent.type(screen.getByTestId('feedback-dialog-text'), 'Mais um');
    await userEvent.click(screen.getByTestId('feedback-dialog-send'));

    expect(await screen.findByTestId('feedback-dialog-error')).toHaveTextContent(
      text.rateLimited(10),
    );
    expect(screen.getByTestId('feedback-dialog-text')).toHaveValue('Mais um');
  });

  it('shows the unavailable reason of a 503 and the network reason without connection', async () => {
    const failures = [new ApiError(503, 'SERVICE_UNAVAILABLE'), new ApiError(0, 'NETWORK')];
    const api = fakeApi({
      sendFeedback: jest.fn(async () => {
        throw failures.shift();
      }),
    });
    await openDialog('/scanner', api);
    await userEvent.type(screen.getByTestId('feedback-dialog-text'), 'Sem servidor');

    await userEvent.click(screen.getByTestId('feedback-dialog-send'));
    expect(await screen.findByTestId('feedback-dialog-error')).toHaveTextContent(text.unavailable);
    await userEvent.click(screen.getByTestId('feedback-dialog-send'));

    await waitFor(() =>
      expect(screen.getByTestId('feedback-dialog-error')).toHaveTextContent(text.network),
    );
    expect(screen.getByTestId('feedback-dialog-text')).toHaveValue('Sem servidor');
  });

  it('reuses the Idempotency-Key of the dialog on a new attempt', async () => {
    const keys: string[] = [];
    let first = true;
    const api = fakeApi({
      sendFeedback: jest.fn(async (_body, key: string) => {
        keys.push(key);
        if (first) {
          first = false;
          throw new ApiError(503, 'SERVICE_UNAVAILABLE');
        }
        return { feedbackId: 'f', createdAt: '2026-10-28T12:00:00Z' };
      }),
    });
    tokenStore.set('access-token-value');
    render(
      <Providers api={api}>
        <FeedbackDialog
          screen="S2"
          onClose={jest.fn()}
          onSent={jest.fn()}
          newKey={() => 'key-of-the-dialog'}
        />
      </Providers>,
    );

    await userEvent.type(screen.getByTestId('feedback-dialog-text'), 'Repetir');
    await userEvent.click(screen.getByTestId('feedback-dialog-send'));
    await screen.findByTestId('feedback-dialog-error');
    await userEvent.click(screen.getByTestId('feedback-dialog-send'));

    await waitFor(() => expect(keys).toEqual(['key-of-the-dialog', 'key-of-the-dialog']));
  });

  it('closes with Esc and with Cancelar, returning the focus to the header item', async () => {
    await openDialog();
    await userEvent.keyboard('{Escape}');
    await waitFor(() => expect(screen.queryByTestId('feedback-dialog')).toBeNull());
    await waitFor(() => expect(screen.getByTestId('app-feedback-button')).toHaveFocus());

    await userEvent.click(screen.getByTestId('app-feedback-button'));
    await userEvent.click(screen.getByTestId('feedback-dialog-cancel'));

    await waitFor(() => expect(screen.queryByTestId('feedback-dialog')).toBeNull());
  });

  it('keeps the focus inside the dialog with Tab and Shift+Tab', async () => {
    await openDialog();
    const field = screen.getByTestId('feedback-dialog-text');
    const cancel = screen.getByTestId('feedback-dialog-cancel');

    await userEvent.tab({ shift: true });
    expect(cancel).toHaveFocus();
    await userEvent.tab();
    expect(field).toHaveFocus();
  });

  it('a session that ended keeps the text and says to sign in again', async () => {
    const api = fakeApi({
      sendFeedback: jest.fn(async () => {
        throw new ApiError(401, 'UNAUTHENTICATED');
      }),
    });
    await openDialog('/scanner', api);

    await userEvent.type(screen.getByTestId('feedback-dialog-text'), 'Sessão');
    await userEvent.click(screen.getByTestId('feedback-dialog-send'));

    expect(await screen.findByTestId('feedback-dialog-error')).toHaveTextContent(text.sessionEnded);
    expect(screen.getByTestId('feedback-dialog-text')).toHaveValue('Sessão');
  });
});

describe('client with a fixed Idempotency-Key (U8)', () => {
  it('sends the key of the screen and retries a 503 with the same key', async () => {
    const seen: string[] = [];
    const responses = [
      { ok: false, status: 503, json: async () => ({}) },
      {
        ok: true,
        status: 201,
        json: async () => ({ feedbackId: 'f', createdAt: '2026-10-28T12:00:00Z' }),
      },
    ];
    const client = createApiClient({
      baseUrl: 'http://api',
      sleep: async () => undefined,
      fetchImpl: async (_url, init) => {
        seen.push((init?.headers as Record<string, string>)['Idempotency-Key'] ?? '');
        return responses.shift() as unknown as Response;
      },
    });

    await client.request('/api/v1/feedback', {
      method: 'POST',
      body: { screen: 'S1', text: 'x' },
      idempotencyKey: 'fixed-key',
    });

    expect(seen).toEqual(['fixed-key', 'fixed-key']);
  });
});
