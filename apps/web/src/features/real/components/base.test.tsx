import { act, fireEvent, render, renderHook, screen } from '@testing-library/react';
import { useState } from 'react';
import { ApiError } from '@/lib/api/client';
import { colors, contrastRatio } from '@/lib/theme/tokens';
import { RealError, toRealError } from '@/features/real/api';
import { useLoad } from '@/features/real/hooks/useLoad';
import { useMoneyAction } from '@/features/real/hooks/useMoneyAction';
import {
  TRACKING_INTERVAL_MS,
  TRACKING_LIMIT_MS,
  useTracking,
} from '@/features/real/hooks/useTracking';
import { realMessages as text } from '@/features/real/messages';
import { MoneyConfirmDialog } from '@/features/real/components/MoneyConfirmDialog';
import { OrderStateBadge } from '@/features/real/components/OrderStateBadge';
import { PauseBanner, pausedText } from '@/features/real/components/PauseBanner';
import { RealBadge } from '@/features/real/components/RealBadge';
import { RealErrorNotice } from '@/features/real/components/RealErrorNotice';
import { ReleaseStatus, releaseText } from '@/features/real/components/ReleaseStatus';
import { SolAmountInput } from '@/features/real/components/SolAmountInput';
import { problem } from '@/features/real/test-support';

/**
 * 261113-trading-real-bot U6, Step 7, written after the code (BR6.1 to BR6.3, BR7.1, BR8.1, BR8.3, BR8.4, BR8.8;
 * U6-NFR3.3, U6-NFR3.4, U6-NFR4.2, U6-NFR4.6, U6-NFR5.1, U6-NFR6.2, U6-NFR6.4, U6-NFR7.4, U6-NFR7.6): the texts, the
 * components and the hooks every REAL screen is built on.
 */
afterEach(() => {
  jest.useRealTimers();
});

describe('BR8.8 texts', () => {
  it.each([
    ['REAL_DISABLED', 'O REAL ainda não está disponível'],
    ['REAL_NOT_RELEASED', 'O REAL ainda não está disponível para sua conta'],
    ['ORDER_AMBIGUOUS', 'Espere a ordem em conferência terminar'],
    ['ORDER_ALREADY_IN_PROGRESS', 'Já há uma venda total em andamento'],
    ['CONSENT_EXPIRED', 'A assinatura de consentimento expirou ou não confere; assine de novo'],
    ['CONSENT_INVALID', 'A assinatura de consentimento expirou ou não confere; assine de novo'],
    ['INSUFFICIENT_FREE_BALANCE', 'Saldo livre insuficiente'],
    ['BOT_WALLET_ALREADY_ACTIVE', 'Sua carteira do bot já está ativa'],
    ['TERMS_V3_MISSING', 'Aceite os termos do REAL para continuar'],
    ['IDEMPOTENCY_CONFLICT', 'Este pedido já foi enviado com outros dados; tente de novo'],
  ])('%s has its own text', (code, message) => {
    expect(toRealError(problem(403, code)).text).toBe(message);
  });

  it('the texts with a value name the maximum', () => {
    expect(toRealError(problem(422, 'CAP_ABOVE_MAXIMUM'), { maxCap: 10_000_000_000n }).text).toBe(
      'O teto máximo é de 10,000000000 SOL',
    );
    const limited = toRealError(
      problem(422, 'WITHDRAWAL_LIMITED_BY_DIVERGENCE', 'Maximum withdrawable: 750000000 lamports.'),
    );
    expect(limited.text).toBe(
      'Agora o saque está limitado a 0,750000000 SOL enquanto a conferência com a rede termina',
    );
    expect(limited.maxLamports).toBe(750_000_000n);
    expect(toRealError(problem(409, 'REAL_PAUSED', 'DAILY_LOSS')).text).toBe(
      'REAL pausado: DAILY_LOSS',
    );
  });

  it('a code without a text shows the detail, and without a detail a generic text, never a stack', () => {
    expect(toRealError(problem(422, 'SOMETHING_NEW', 'Detalhe do servidor')).text).toBe(
      'Detalhe do servidor',
    );
    expect(toRealError(problem(500, 'INTERNAL')).text).toBe(text.genericError);
    expect(toRealError(new TypeError('boom at line 3')).text).toBe(text.genericError);
    const same = new RealError(400, 'X', 'y', null, null);
    expect(toRealError(same)).toBe(same);
  });

  it('BR8.3: every paused reason has a text and an unknown one is "REAL pausado"', () => {
    for (const reason of [
      'USER',
      'DAILY_LOSS',
      'DIVERGENCE',
      'KILL_SWITCH',
      'ADMIN',
      'UPSTREAM',
      'RESTORE',
      'RESTORE_CHECK_PENDING',
    ]) {
      expect(pausedText(reason)).toMatch(/^REAL pausado/);
      expect(pausedText(reason)).not.toBe('REAL pausado');
    }
    expect(pausedText('SOMETHING_NEW')).toBe('REAL pausado');
  });
});

describe('components', () => {
  it('RealErrorNotice shows the text and the correlationId, and "Tentar de novo" calls back (U6-NFR5.1)', () => {
    const retry = jest.fn();
    render(
      <RealErrorNotice
        error={toRealError(problem(503, 'SERVICE_UNAVAILABLE'))}
        onRetry={retry}
        testId="e"
        message="Falhou"
      />,
    );

    expect(screen.getByRole('alert')).toHaveTextContent('Falhou');
    expect(screen.getByTestId('e-correlation')).toHaveTextContent(
      'corr-0000-4000-8000-000000000001',
    );
    fireEvent.click(screen.getByTestId('e-retry'));
    expect(retry).toHaveBeenCalled();
  });

  it('RealBadge has text and icon, and the amber keeps 4.5:1 on its uses (BR7.1, U6-NFR6.4)', () => {
    render(<RealBadge />);

    expect(screen.getByTestId('real-badge')).toHaveTextContent('◆ REAL');
    for (const background of [colors.background, colors.surface, colors.surfaceRaised]) {
      expect(contrastRatio(colors.real, background)).toBeGreaterThanOrEqual(4.5);
    }
    expect(contrastRatio(colors.background, colors.real)).toBeGreaterThanOrEqual(4.5);
  });

  it('PauseBanner shows the reason and the withdrawal reminder, and nothing when not paused', () => {
    const { rerender } = render(<PauseBanner reason="KILL_SWITCH" />);
    expect(screen.getByTestId('real-pause-banner')).toHaveTextContent('kill switch');
    expect(screen.getByTestId('real-pause-banner')).toHaveTextContent(text.paused.withdrawStill);

    rerender(<PauseBanner reason="NONE" />);
    expect(screen.queryByTestId('real-pause-banner')).toBeNull();
  });

  it('OrderStateBadge has text and icon for every state, and "Em conferência" with the reserved amount (BR8.4)', () => {
    for (const state of [
      'PROPOSED',
      'QUOTED',
      'SIGNED',
      'SENT',
      'CONFIRMED',
      'FAILED',
      'AMBIGUOUS',
      'CANCELLED',
    ]) {
      const { unmount } = render(<OrderStateBadge state={state} testId="s" />);
      const badge = screen.getByTestId('s');
      expect(badge.textContent?.length ?? 0).toBeGreaterThan(2);
      expect(badge.querySelector('[aria-hidden="true"]')?.textContent?.trim()).not.toBe('');
      unmount();
    }
    render(<OrderStateBadge state="AMBIGUOUS" reservedLamports={200_000_000n} testId="a" />);
    expect(screen.getByTestId('a')).toHaveTextContent(
      'Em conferência · Valor reservado: 0,200000000 SOL',
    );
  });

  it('ReleaseStatus writes each state of a release that is not in force (BR2.5)', () => {
    expect(releaseText(null)).toBe(text.notReleased);
    expect(releaseText({ state: 'NONE', effectiveAt: null, blockedReason: null })).toBe(
      text.notReleased,
    );
    expect(
      releaseText(
        { state: 'QUARANTINE', effectiveAt: '2026-11-17T15:00:00Z', blockedReason: null },
        'America/Sao_Paulo',
      ),
    ).toBe('Liberação em quarentena até 17/11/2026, 12:00:00');
    expect(
      releaseText({ state: 'BLOCKED', effectiveAt: null, blockedReason: 'KILL_SWITCH_ON' }),
    ).toBe('Liberação bloqueada: o kill switch do REAL está ligado');
    expect(releaseText({ state: 'REVOKED', effectiveAt: null, blockedReason: null })).toBe(
      text.revoked,
    );
    render(<ReleaseStatus release={null} />);
    expect(screen.getByRole('status')).toHaveTextContent(text.notReleased);
  });

  it('SolAmountInput has a visible label and links the hint and the error', () => {
    function Field() {
      const [value, setValue] = useState('');
      return (
        <SolAmountInput
          id="cap"
          label="Teto"
          value={value}
          onChange={setValue}
          hint="Até 10"
          error={value === 'x' ? 'Inválido' : null}
          testId="cap"
        />
      );
    }
    render(<Field />);
    const input = screen.getByLabelText('Teto');
    fireEvent.change(input, { target: { value: 'x' } });

    expect(input).toHaveAttribute('aria-invalid', 'true');
    expect(input).toHaveAttribute('aria-describedby', 'cap-hint cap-error');
    expect(screen.getByTestId('cap-error')).toHaveTextContent('Inválido');
    expect(input).toHaveAttribute('inputmode', 'decimal');
  });
});

describe('MoneyConfirmDialog', () => {
  function Harness({ send }: { send: (body: { v: string }, key: string) => Promise<string> }) {
    const action = useMoneyAction(send, { newKey: () => `key-${Math.random()}` });
    const [open, setOpen] = useState(false);
    return (
      <>
        <button type="button" data-testid="opener" onClick={() => setOpen(true)}>
          Sacar
        </button>
        {open && (
          <MoneyConfirmDialog
            title="Confirmar saque"
            amount="0,500000000 SOL"
            destination="Main…1111"
            effect="Saque para a carteira principal"
            action={action}
            returnFocusTo={screen.queryByTestId('opener')}
            onConfirm={() => void action.submit({ v: '1' })}
            onClose={() => {
              action.reset();
              setOpen(false);
            }}
          />
        )}
      </>
    );
  }

  it('names amount, destination and effect, starts on "Cancelar" and gives the focus back (BR6.1, U6-NFR6.2)', () => {
    render(<Harness send={async () => 'ok'} />);
    screen.getByTestId('opener').focus();
    fireEvent.click(screen.getByTestId('opener'));

    expect(screen.getByRole('alertdialog')).toHaveAttribute('open');
    expect(screen.getByTestId('real-confirm-amount')).toHaveTextContent('0,500000000 SOL');
    expect(screen.getByTestId('real-confirm-destination')).toHaveTextContent('Main…1111');
    expect(screen.getByTestId('real-confirm-cancel')).toHaveFocus();
    fireEvent.keyDown(screen.getByRole('alertdialog'), { key: 'Escape' });
    expect(screen.queryByRole('alertdialog')).toBeNull();
    expect(screen.getByTestId('opener')).toHaveFocus();
  });

  it('disables "Confirmar" and says "Enviando…" before the request answers, and a second click sends nothing (U6-NFR3.3, U6-NFR7.6)', async () => {
    let resolve: (value: string) => void = () => undefined;
    const send = jest.fn(() => new Promise<string>((done) => (resolve = done)));
    render(<Harness send={send} />);
    fireEvent.click(screen.getByTestId('opener'));

    fireEvent.click(screen.getByTestId('real-confirm-submit'));
    fireEvent.click(screen.getByTestId('real-confirm-submit'));

    expect(screen.getByTestId('real-confirm-submit')).toBeDisabled();
    expect(screen.getByTestId('real-confirm-submit')).toHaveTextContent(text.sending);
    expect(send).toHaveBeenCalledTimes(1);
    await act(async () => resolve('ok'));
  });

  it('an error shows its text and "Tentar de novo" resends the same body with the same key (S25, U6-NFR4.2)', async () => {
    const send = jest
      .fn()
      .mockRejectedValueOnce(new ApiError(0, 'NETWORK'))
      .mockResolvedValue('ok');
    render(<Harness send={send} />);
    fireEvent.click(screen.getByTestId('opener'));

    await act(async () => fireEvent.click(screen.getByTestId('real-confirm-submit')));
    expect(screen.getByTestId('real-confirm-error')).toHaveTextContent(text.errors.NETWORK);
    await act(async () => fireEvent.click(screen.getByTestId('real-confirm-error-retry')));

    expect(send).toHaveBeenCalledTimes(2);
    expect(send.mock.calls[1]).toEqual(send.mock.calls[0]);
  });
});

describe('useMoneyAction', () => {
  it('keeps the key for the same body, creates a new one for another body, and never resends on its own (BR6.2)', async () => {
    let counter = 0;
    const send = jest.fn(async () => {
      throw new ApiError(503, 'SERVICE_UNAVAILABLE');
    });
    const { result } = renderHook(() => useMoneyAction(send, { newKey: () => `k${++counter}` }));

    await act(async () => void (await result.current.submit({ amount: '1' })));
    await act(async () => void (await result.current.submit({ amount: '1' })));
    await act(async () => void (await result.current.submit({ amount: '2' })));

    expect(send.mock.calls.map((call) => (call as unknown[])[1])).toEqual(['k1', 'k1', 'k2']);
    await new Promise((done) => setTimeout(done, 20));
    expect(send).toHaveBeenCalledTimes(3);
  });

  it('a success, a reset and an IDEMPOTENCY_CONFLICT discard the key', async () => {
    let counter = 0;
    const send = jest
      .fn()
      .mockResolvedValueOnce('ok')
      .mockRejectedValueOnce(new ApiError(409, 'IDEMPOTENCY_CONFLICT'))
      .mockRejectedValueOnce(new ApiError(503, 'SERVICE_UNAVAILABLE'))
      .mockResolvedValue('ok');
    const { result } = renderHook(() => useMoneyAction(send, { newKey: () => `k${++counter}` }));

    await act(async () => void (await result.current.submit({ a: 1 })));
    await act(async () => void (await result.current.submit({ a: 1 })));
    expect(result.current.state).toMatchObject({
      kind: 'error',
      error: { code: 'IDEMPOTENCY_CONFLICT' },
    });
    await act(async () => void (await result.current.submit({ a: 1 })));
    act(() => result.current.reset());
    await act(async () => void (await result.current.submit({ a: 1 })));

    expect(send.mock.calls.map((call) => (call as unknown[])[1])).toEqual(['k1', 'k2', 'k3', 'k4']);
    expect(result.current.state).toEqual({ kind: 'done', result: 'ok' });
  });

  it('retry without a previous body does nothing', async () => {
    const send = jest.fn(async () => 'ok');
    const { result } = renderHook(() => useMoneyAction(send));

    await act(async () => void (await result.current.retry()));

    expect(send).not.toHaveBeenCalled();
  });
});

describe('useTracking', () => {
  it('reads every 3 s, one read at a time, and stops when done (U6-NFR3.4)', async () => {
    jest.useFakeTimers();
    let release: () => void = () => undefined;
    let reads = 0;
    const read = jest.fn(
      () =>
        new Promise<string>((done) => {
          reads += 1;
          release = () => done(reads >= 3 ? 'CONFIRMED' : 'SENT');
        }),
    );
    const { result } = renderHook(() => useTracking(read, (state) => state === 'CONFIRMED'));

    act(() => result.current.start());
    await act(async () => jest.advanceTimersByTimeAsync(TRACKING_INTERVAL_MS));
    expect(read).toHaveBeenCalledTimes(1);
    await act(async () => jest.advanceTimersByTimeAsync(TRACKING_INTERVAL_MS * 3));
    expect(read).toHaveBeenCalledTimes(1);
    await act(async () => release());
    await act(async () => jest.advanceTimersByTimeAsync(TRACKING_INTERVAL_MS));
    await act(async () => release());
    await act(async () => jest.advanceTimersByTimeAsync(TRACKING_INTERVAL_MS));
    await act(async () => release());

    expect(result.current.tracking).toEqual({ kind: 'done', latest: 'CONFIRMED' });
    expect(read).toHaveBeenCalledTimes(3);
  });

  it('ends after 2 minutes with at most 40 reads, and a failed read waits for the next tick (U6-NFR4.6, U6-NFR3.5b)', async () => {
    jest.useFakeTimers();
    const read = jest.fn().mockRejectedValueOnce(new Error('offline')).mockResolvedValue('SENT');
    const { result } = renderHook(() => useTracking(read, () => false));

    act(() => result.current.start());
    await act(async () => jest.advanceTimersByTimeAsync(TRACKING_LIMIT_MS + 10_000));

    expect(read.mock.calls.length).toBeLessThanOrEqual(40);
    expect(read.mock.calls.length).toBeGreaterThanOrEqual(39);
    expect(result.current.tracking).toEqual({ kind: 'timeout', latest: 'SENT' });
  });

  it('stop cancels the next read', async () => {
    jest.useFakeTimers();
    const read = jest.fn(async () => 'SENT');
    const { result } = renderHook(() => useTracking(read, () => false));

    act(() => result.current.start());
    act(() => result.current.stop());
    await act(async () => jest.advanceTimersByTimeAsync(TRACKING_INTERVAL_MS * 3));

    expect(read).not.toHaveBeenCalled();
  });
});

describe('useLoad', () => {
  it('goes loading, then ready or error, and reload reads again (BR8.1)', async () => {
    const read = jest
      .fn()
      .mockRejectedValueOnce(new ApiError(503, 'SERVICE_UNAVAILABLE'))
      .mockResolvedValue('ok');
    const { result } = renderHook(() => useLoad(read));
    expect(result.current.load).toEqual({ kind: 'loading' });

    await act(async () => Promise.resolve());
    expect(result.current.load.kind).toBe('error');
    act(() => result.current.reload());
    await act(async () => Promise.resolve());

    expect(result.current.load).toEqual({ kind: 'ready', data: 'ok' });
  });

  it('does not read while disabled, and set replaces the data', async () => {
    const read = jest.fn(async () => 'a');
    const { result } = renderHook(() => useLoad(read, false));
    await act(async () => Promise.resolve());

    expect(read).not.toHaveBeenCalled();
    act(() => result.current.set('b'));
    expect(result.current.load).toEqual({ kind: 'ready', data: 'b' });
  });
});
