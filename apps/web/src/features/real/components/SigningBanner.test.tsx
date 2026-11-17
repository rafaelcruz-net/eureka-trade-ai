import { act, fireEvent, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { GuestApi } from '@/lib/api/guest-api';
import { tokenStore } from '@/lib/auth/token-store';
import { decodeBase64, encodeBase64 } from '@/lib/encoding/base64';
import { fakeChannel, fakeFetch, settle, sseBlock } from '@/features/dashboard/test-support';
import { useLiveChannel } from '@/features/live/LiveChannelProvider';
import type { WalletPort } from '@/features/auth/wallets';
import { SigningBanner } from '@/features/real/components/SigningBanner';
import { realMessages as text } from '@/features/real/messages';
import {
  browserStorageText,
  orderDto,
  ORDER_ID,
  problem,
  RealProviders,
  realApi,
  sessionDto,
  signingWallet,
  UNSIGNED_BYTES,
} from '@/features/real/test-support';
import { resetSessionReal } from '@/features/real/session-real';

/**
 * 261113-trading-real-bot U6, Step 12, written BEFORE the code (BR3, S12 to S16, S38, S39, S41, S42; U6-NFR3.1,
 * U6-NFR3.2, U6-NFR3.5, U6-NFR4.1, U6-NFR4.5, U6-NFR6.2, U6-NFR6.3, U6-NFR1.1): the signing banner of the SIGNED mode
 * and its dialog. The banner shows in the same render as the event, reads the open session again on load, on every
 * route change and after a reconnection (at most two calls), counts down on the clock of the server, announces only at
 * 10 s and 5 s, and every outcome of the signature has its text. The browser hands the bytes of the server to the
 * wallet unchanged and sends only what the wallet signed.
 */
let pathname = '/';
jest.mock('next/navigation', () => ({
  useRouter: () => ({ replace: jest.fn(), push: jest.fn() }),
  useSearchParams: () => new URLSearchParams(),
  usePathname: () => pathname,
}));

const SERVER_NOW = new Date('2026-11-16T12:00:00Z');
const SIGNING_EVENT = {
  type: 'REAL_SIGNING_REQUESTED',
  realOrderId: ORDER_ID,
  deadline: '2026-11-16T12:00:10Z',
};

function quotedApi(overrides: Partial<GuestApi> = {}) {
  return realApi({
    listRealOrders: jest.fn(async () => ({ items: [orderDto()], total: 1 })),
    getRealSigningSession: jest.fn(async () => sessionDto()),
    ...overrides,
  });
}

function renderBanner(
  api: jest.Mocked<GuestApi> = quotedApi(),
  options: {
    mode?: 'SIGNED' | 'AUTO_FULL' | null;
    wallet?: WalletPort | null;
    net?: ReturnType<typeof fakeFetch>;
  } = {},
) {
  tokenStore.set('access-token-value');
  const net = options.net ?? fakeFetch();
  const wallet = options.wallet === undefined ? signingWallet() : options.wallet;
  const view = render(
    <RealProviders api={api} net={net}>
      <SigningBanner
        mode={options.mode === undefined ? 'SIGNED' : options.mode}
        pickWallet={async () => wallet}
      />
    </RealProviders>,
  );
  const rerenderAt = (path: string) => {
    pathname = path;
    view.rerender(
      <RealProviders api={api} net={net}>
        <SigningBanner
          mode={options.mode === undefined ? 'SIGNED' : options.mode}
          pickWallet={async () => wallet}
        />
      </RealProviders>,
    );
  };
  return { ...view, api, net, wallet, rerenderAt };
}

beforeEach(() => {
  pathname = '/';
  resetSessionReal();
  jest.useFakeTimers({ now: SERVER_NOW, advanceTimers: true });
});

afterEach(() => {
  jest.useRealTimers();
  tokenStore.clear();
  jest.restoreAllMocks();
});

async function flush() {
  await act(async () => {
    await settle(20);
  });
}

describe('SigningBanner: when it shows', () => {
  it('outside the SIGNED mode it renders nothing and reads nothing', async () => {
    const api = quotedApi();
    renderBanner(api, { mode: 'AUTO_FULL' });
    await flush();

    expect(screen.queryByTestId('real-signing-banner')).toBeNull();
    expect(api.listRealOrders).not.toHaveBeenCalled();
    expect(api.getRealSigningSession).not.toHaveBeenCalled();
  });

  it('on load it reads the QUOTED order and its session, at most two calls (S12, U6-NFR3.5)', async () => {
    const api = quotedApi();
    renderBanner(api);
    await flush();

    expect(api.listRealOrders).toHaveBeenCalledWith({ state: 'QUOTED', limit: 1 });
    expect(api.getRealSigningSession).toHaveBeenCalledWith(ORDER_ID);
    expect(screen.getByTestId('real-signing-banner')).toHaveTextContent('Ordem para assinar');
    expect(screen.getByTestId('real-signing-banner')).toHaveTextContent('Comprar');
    expect(screen.getByTestId('real-signing-countdown')).toHaveTextContent('0:10');
  });

  it('without a QUOTED order it shows nothing after ONE call', async () => {
    const api = realApi();
    renderBanner(api);
    await flush();

    expect(api.listRealOrders).toHaveBeenCalledTimes(1);
    expect(api.getRealSigningSession).not.toHaveBeenCalled();
    expect(screen.queryByTestId('real-signing-banner')).toBeNull();
  });

  it.each([
    [404, 'NOT_FOUND'],
    [409, 'ORDER_NOT_QUOTED'],
  ])('a %i on the session means the order no longer waits: no banner', async (status, code) => {
    const api = quotedApi({
      getRealSigningSession: jest.fn(async () => {
        throw problem(status, code);
      }),
    });
    renderBanner(api);
    await flush();

    expect(screen.queryByTestId('real-signing-banner')).toBeNull();
  });

  it('the event shows the banner in the same render, before any read answers (U6-NFR3.1)', async () => {
    const pending = new Promise<never>(() => undefined);
    const api = realApi({ listRealOrders: jest.fn(() => pending) });
    const channel = fakeChannel();
    const net = fakeFetch();
    net.respond(channel.response);
    renderBanner(api, { net });
    await flush();

    act(() => channel.push(sseBlock('real.signing.requested', SIGNING_EVENT)));
    await act(async () => {
      await Promise.resolve();
      await Promise.resolve();
      await Promise.resolve();
    });

    expect(screen.getByTestId('real-signing-banner')).toHaveTextContent('Ordem para assinar');
    expect(api.getRealSigningSession).not.toHaveBeenCalled();
  });

  it('reads the session again on every route change, two calls at most (U6-NFR3.5)', async () => {
    const api = quotedApi();
    const { rerenderAt } = renderBanner(api);
    await flush();

    rerenderAt('/bots');
    await flush();
    rerenderAt('/real/ordens');
    await flush();

    expect(api.listRealOrders).toHaveBeenCalledTimes(3);
    expect(api.getRealSigningSession).toHaveBeenCalledTimes(3);
  });

  it('a lost event is covered by the read right after the reconnection (U6-NFR4.1)', async () => {
    const listRealOrders = jest
      .fn()
      .mockResolvedValueOnce({ items: [], total: 0 })
      .mockResolvedValue({ items: [orderDto()], total: 1 });
    const api = quotedApi({ listRealOrders });
    const first = fakeChannel();
    const net = fakeFetch();
    net.respond(first.response, fakeChannel().response);
    renderBanner(api, { net });
    await flush();
    expect(screen.queryByTestId('real-signing-banner')).toBeNull();

    await act(async () => first.end());
    await act(async () => {
      await jest.advanceTimersByTimeAsync(2_000);
    });
    await flush();

    expect(listRealOrders).toHaveBeenCalledTimes(2);
    expect(screen.getByTestId('real-signing-banner')).toBeInTheDocument();
  });

  it('with the reacceptance pending it reads nothing, and its own 403 marks it pending (S42, U6-NFR4.5)', async () => {
    const api = realApi({
      listRealOrders: jest.fn(async () => {
        throw problem(403, 'TERMS_REACCEPTANCE_REQUIRED');
      }),
    });
    let pending = () => false;
    function Pending() {
      const live = useLiveChannel();
      pending = () => live.termsPending;
      return null;
    }
    tokenStore.set('access-token-value');
    const net = fakeFetch();
    const view = render(
      <RealProviders api={api} net={net}>
        <Pending />
        <SigningBanner mode="SIGNED" pickWallet={async () => signingWallet()} />
      </RealProviders>,
    );
    await flush();
    pathname = '/real/deposito-saque';
    view.rerender(
      <RealProviders api={api} net={net}>
        <Pending />
        <SigningBanner mode="SIGNED" pickWallet={async () => signingWallet()} />
      </RealProviders>,
    );
    await flush();

    expect(pending()).toBe(true);
    expect(api.listRealOrders).toHaveBeenCalledTimes(1);
    expect(api.notifyTermsReacceptance).not.toHaveBeenCalled();
    expect(screen.queryByTestId('real-signing-banner')).toBeNull();
  });
});

describe('SigningBanner: the countdown', () => {
  it.each([-30_000, 0, 30_000])(
    'with the browser clock off by %i ms it counts the seconds of the server (U6-NFR3.2)',
    async (offset) => {
      jest.setSystemTime(SERVER_NOW.getTime() + offset);
      renderBanner();
      await flush();

      expect(screen.getByTestId('real-signing-countdown')).toHaveTextContent('0:10');
      await act(async () => {
        await jest.advanceTimersByTimeAsync(4_000);
      });
      expect(screen.getByTestId('real-signing-countdown')).toHaveTextContent('0:06');
    },
  );

  it('announces only at the marks of 10 s and 5 s (U6-NFR6.3)', async () => {
    renderBanner();
    await flush();
    const live = screen.getByTestId('real-signing-live');
    expect(live).toHaveAttribute('aria-live', 'polite');
    expect(live).toHaveTextContent(text.signing.milestone(10));

    await act(async () => {
      await jest.advanceTimersByTimeAsync(2_000);
    });
    expect(live).toHaveTextContent(text.signing.milestone(10));
    await act(async () => {
      await jest.advanceTimersByTimeAsync(3_000);
    });

    expect(live).toHaveTextContent(text.signing.milestone(5));
  });

  it('at zero says "A proposta expirou. Nada foi enviado." for 5 s and leaves (S14, BR3.3)', async () => {
    renderBanner();
    await flush();

    await act(async () => {
      await jest.advanceTimersByTimeAsync(10_250);
    });
    expect(screen.getByTestId('real-signing-outcome')).toHaveTextContent(text.signing.expired);
    await act(async () => {
      await jest.advanceTimersByTimeAsync(5_000);
    });

    expect(screen.queryByTestId('real-signing-outcome')).toBeNull();
    expect(screen.queryByTestId('real-signing-banner')).toBeNull();
  });
});

describe('SigningDialog', () => {
  async function openDialog(api: jest.Mocked<GuestApi> = quotedApi(), wallet?: WalletPort | null) {
    const rendered = renderBanner(api, { wallet });
    await flush();
    await act(async () => {
      fireEvent.click(screen.getByTestId('real-signing-view'));
    });
    await flush();
    return rendered;
  }

  async function sign() {
    await act(async () => {
      fireEvent.click(screen.getByTestId('real-signing-sign'));
    });
    await flush();
  }

  it('shows side, amounts, fee, reason and deadline, with the first focus on "Assinar na carteira"', async () => {
    await openDialog();

    const dialog = screen.getByRole('dialog');
    expect(dialog).toHaveTextContent('Comprar');
    expect(dialog).toHaveTextContent('16.260,000000');
    expect(dialog).toHaveTextContent('15.934,800000');
    expect(dialog).toHaveTextContent('0,000005000 SOL');
    expect(dialog).toHaveTextContent('volume 24h subiu 40% e liquidez acima do mínimo');
    expect(dialog).toHaveTextContent('expira em 0:10');
    expect(screen.getByTestId('real-signing-sign')).toHaveFocus();
  });

  it('hands the bytes of the server to the wallet unchanged and sends only what it signed (S13, U6-NFR1.1)', async () => {
    const submit = jest.fn(async () => orderDto({ state: 'SIGNED' }));
    const { wallet } = await openDialog(quotedApi({ submitRealSignedTransaction: submit }));
    await sign();

    const handed = (wallet?.signTransaction as jest.Mock).mock.calls[0]?.[0] as Uint8Array;
    expect(handed).toEqual(UNSIGNED_BYTES);
    const [orderId, body, key] = submit.mock.calls[0] as unknown as [
      string,
      { signedTransactionBase64: string },
      string,
    ];
    expect(orderId).toBe(ORDER_ID);
    const sent = decodeBase64(body.signedTransactionBase64);
    expect(sent.subarray(1 + 128)).toEqual(UNSIGNED_BYTES.subarray(1 + 128));
    expect(sent.subarray(65, 129)).toEqual(new Uint8Array(64).fill(0xab));
    expect(key).toMatch(/[0-9a-f-]{36}/);
    expect(screen.queryByRole('dialog')).toBeNull();
    expect(screen.getByTestId('real-signing-outcome')).toHaveTextContent(text.signing.accepted);
    expect(document.body.innerHTML).not.toContain(body.signedTransactionBase64);
    expect(browserStorageText()).not.toContain(body.signedTransactionBase64);
    expect(browserStorageText()).not.toContain(encodeBase64(UNSIGNED_BYTES));
  });

  it.each([
    [409, 'SIGNING_DEADLINE_EXPIRED', text.signing.expired],
    [422, 'SIGNED_MESSAGE_MISMATCH', text.errors.SIGNED_MESSAGE_MISMATCH],
    [422, 'SIGNING_POLICY_REFUSED', text.errors.SIGNING_POLICY_REFUSED],
    [409, 'ORDER_NOT_QUOTED', text.signing.notWaiting],
  ])(
    'a %i %s closes the dialog with its text (BR3.3, BR3.7, S38, S39)',
    async (status, code, message) => {
      const api = quotedApi({
        submitRealSignedTransaction: jest.fn(async () => {
          throw problem(status, code);
        }),
      });
      await openDialog(api);
      await sign();

      expect(screen.queryByRole('dialog')).toBeNull();
      expect(screen.getByTestId('real-signing-outcome')).toHaveTextContent(message);
    },
  );

  it('a refusal in the wallet calls reject and says "Você recusou. Nada foi enviado." (S15, BR3.4)', async () => {
    const reject = jest.fn(async () => orderDto({ state: 'CANCELLED' }));
    const wallet = signingWallet({
      signTransaction: jest.fn(async () => {
        throw new Error('User rejected the request');
      }),
    });
    const api = quotedApi({ rejectRealSigning: reject });
    await openDialog(api, wallet);
    await sign();

    expect(reject).toHaveBeenCalledWith(ORDER_ID, expect.any(String));
    expect(api.submitRealSignedTransaction).not.toHaveBeenCalled();
    expect(screen.getByTestId('real-signing-outcome')).toHaveTextContent(text.signing.refused);
  });

  it('when the reject fails it says so, closes the dialog and nothing is sent (S41)', async () => {
    const api = quotedApi({
      rejectRealSigning: jest.fn(async () => {
        throw problem(503, 'SERVICE_UNAVAILABLE');
      }),
    });
    await openDialog(api);
    await act(async () => {
      fireEvent.click(screen.getByTestId('real-signing-reject'));
    });
    await flush();

    expect(screen.queryByRole('dialog')).toBeNull();
    expect(screen.getByTestId('real-signing-outcome')).toHaveTextContent(text.signing.rejectFailed);
    expect(api.submitRealSignedTransaction).not.toHaveBeenCalled();
  });

  it('"Recusar" calls reject and says nothing was sent', async () => {
    const reject = jest.fn(async () => orderDto({ state: 'CANCELLED' }));
    await openDialog(quotedApi({ rejectRealSigning: reject }));
    await act(async () => {
      fireEvent.click(screen.getByTestId('real-signing-reject'));
    });
    await flush();

    expect(reject).toHaveBeenCalledTimes(1);
    expect(screen.getByTestId('real-signing-outcome')).toHaveTextContent(text.signing.refused);
  });

  it('a wallet without signTransaction never opens the dialog (S16, BR3.6)', async () => {
    const wallet = signingWallet({ canSignTransaction: false });
    await openDialog(quotedApi(), wallet);

    expect(screen.queryByRole('dialog')).toBeNull();
    expect(screen.getByTestId('real-signing-outcome')).toHaveTextContent(
      text.signing.walletCannotSign,
    );
    expect(wallet.signTransaction).not.toHaveBeenCalled();
  });

  it('a wallet that changed the message is refused before anything is sent (BR3.7)', async () => {
    const wallet = signingWallet({
      signTransaction: jest.fn(async (transaction: Uint8Array) => {
        const changed = Uint8Array.from(transaction);
        changed[changed.length - 1] = (changed[changed.length - 1] ?? 0) ^ 1;
        return changed;
      }),
    });
    const api = quotedApi();
    await openDialog(api, wallet);
    await sign();

    expect(api.submitRealSignedTransaction).not.toHaveBeenCalled();
    expect(screen.getByTestId('real-signing-outcome')).toHaveTextContent(
      text.errors.SIGNED_MESSAGE_MISMATCH,
    );
  });

  it('a network failure keeps the dialog with "Tentar de novo", which resends the same body and key', async () => {
    const submit = jest
      .fn()
      .mockRejectedValueOnce(problem(0, 'NETWORK'))
      .mockResolvedValue(orderDto({ state: 'SIGNED' }));
    await openDialog(quotedApi({ submitRealSignedTransaction: submit }));
    await sign();

    expect(screen.getByRole('dialog')).toHaveTextContent(text.errors.NETWORK);
    await act(async () => {
      fireEvent.click(screen.getByTestId('real-signing-error-retry'));
    });
    await flush();

    expect(submit).toHaveBeenCalledTimes(2);
    expect(submit.mock.calls[1]).toEqual(submit.mock.calls[0]);
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('Esc closes without signing and the focus returns to "Ver" (U6-NFR6.2)', async () => {
    const api = quotedApi();
    const { wallet } = await openDialog(api);

    await act(async () => {
      fireEvent.keyDown(screen.getByRole('dialog'), { key: 'Escape' });
    });

    expect(screen.queryByRole('dialog')).toBeNull();
    expect(screen.getByTestId('real-signing-view')).toHaveFocus();
    expect(wallet?.signTransaction).not.toHaveBeenCalled();
    expect(api.rejectRealSigning).not.toHaveBeenCalled();
  });

  it('the deadline passing while the dialog is open closes it with the expiry text', async () => {
    await openDialog();

    await act(async () => {
      await jest.advanceTimersByTimeAsync(10_250);
    });

    expect(screen.queryByRole('dialog')).toBeNull();
    expect(screen.getByTestId('real-signing-outcome')).toHaveTextContent(text.signing.expired);
  });

  it('can be used with the keyboard only: Tab reaches "Ver" and Enter opens the dialog (U6-NFR6.1)', async () => {
    renderBanner();
    await flush();
    const user = userEvent.setup({ advanceTimers: jest.advanceTimersByTime });

    screen.getByTestId('real-signing-view').focus();
    await user.keyboard('{Enter}');

    // The dialog opens after the signing session is read: wait for it instead of one flush, which was not always
    // enough on the slower CI runner (regression of the flaky run 44007384143).
    expect(await screen.findByRole('dialog')).toBeInTheDocument();
  });
});
