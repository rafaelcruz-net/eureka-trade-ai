'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import type { GuestApi } from '@/lib/api/guest-api';
import { abbreviateMint } from '@/lib/format/format';
import { useLiveChannel } from '@/features/live/LiveChannelProvider';
import {
  realCall,
  toRealError,
  toWithdrawal,
  type BotWallet,
  type Position,
  type RealReads,
  type Withdrawal,
} from '@/features/real/api';
import { useMoneyAction } from '@/features/real/hooks/useMoneyAction';
import { useTracking, type Tracking } from '@/features/real/hooks/useTracking';
import { formatSolAmount, formatToken, parseSol } from '@/features/real/money';
import { realMessages as text } from '@/features/real/messages';
import { MoneyConfirmDialog } from '@/features/real/components/MoneyConfirmDialog';
import { PauseBanner } from '@/features/real/components/PauseBanner';
import { RealArea } from '@/features/real/components/RealArea';
import { RealErrorNotice } from '@/features/real/components/RealErrorNotice';
import { SolAmountInput } from '@/features/real/components/SolAmountInput';

const copy = text.depositWithdraw;
const FINAL_WITHDRAWAL = ['CONFIRMED', 'FAILED'];
/** BR6.4: with these pauses the server names the maximum it returns now ("Disponível para saque agora"). */
const LIMITED_PAUSES = ['DIVERGENCE', 'RESTORE', 'RESTORE_CHECK_PENDING'];

/** BR6.5, U6-NFR1.8: the main wallet, abbreviated: 4 first and 4 last characters. */
export function abbreviateAddress(address: string): string {
  return address.length <= 10 ? address : `${address.slice(0, 4)}…${address.slice(-4)}`;
}

/**
 * 261113-trading-real-bot U6, R6 `/real/deposito-saque` (US2.2, US5.1, US5.2; BR1.4, BR6; S21 to S25, S42, S44;
 * U6-NFR4.4): the deposit address with "Copiar", and the withdrawal ALWAYS visible and enabled: with the kill switch, a
 * pause, the REAL off, no release, a divergence and without the new acceptance of the terms. It reads only the bot
 * wallet and the withdrawals, through the API without the handler of the reacceptance lock; nothing else can take it
 * down. The destination is always the registered main wallet, abbreviated, without any field to edit it.
 */
export function DepositWithdrawScreen({ newKey }: { newKey?: () => string }) {
  return (
    <RealArea
      origin="/real/deposito-saque"
      title={copy.title}
      titleId="real-deposit-title"
      withdrawal
    >
      {({ wallet, api, reads, reloadWallet }) => (
        <DepositWithdraw
          wallet={wallet}
          api={api}
          reads={reads}
          reloadWallet={reloadWallet}
          newKey={newKey}
        />
      )}
    </RealArea>
  );
}

type Pending = { kind: 'amount'; lamports: bigint } | { kind: 'all' } | { kind: 'sellAll' };
type LiquidationProgress = { done: boolean; remaining: Position[] };

function DepositWithdraw({
  wallet,
  api,
  reads,
  reloadWallet,
  newKey,
}: {
  wallet: BotWallet;
  api: GuestApi;
  reads: RealReads;
  reloadWallet: () => void;
  newKey?: () => string;
}) {
  const { markTermsPending } = useLiveChannel();
  const [termsPending, setTermsPending] = useState(false);
  const [amount, setAmount] = useState('');
  const [touched, setTouched] = useState(false);
  const [pending, setPending] = useState<Pending | null>(null);
  const [copied, setCopied] = useState<string | null>(null);
  const [withdrawalId, setWithdrawalId] = useState<string | null>(null);
  // null when the withdrawals before the liquidation could not be read: an older TOTAL withdrawal would then look new,
  // so the tracking never calls the liquidation done and ends with the "still processing" text.
  const knownWithdrawals = useRef<Set<string> | null>(new Set());
  const opener = useRef<HTMLElement | null>(null);
  const main = abbreviateAddress(wallet.mainWalletAddress);
  const free = wallet.balance?.free;
  const parsed = parseSol(amount, { positive: true, max: free });
  const amountError = touched && !parsed.ok ? (copy.invalid[parsed.reason] ?? null) : null;
  const limited = LIMITED_PAUSES.includes(wallet.pausedReason);

  // BR1.4, S42: a read that needs the new acceptance tells whether the band "Aceite os termos novos" shows; nothing
  // here depends on it, and its 403 never sends anyone to the new acceptance (the API without the handler).
  useEffect(() => {
    let active = true;
    api.getRealRelease().catch((failure: unknown) => {
      if (active && toRealError(failure).code === 'TERMS_REACCEPTANCE_REQUIRED') {
        setTermsPending(true);
        markTermsPending();
      }
    });
    return () => {
      active = false;
    };
  }, [api, markTermsPending]);

  const withdraw = useMoneyAction(
    (body: { amount: { lamports: string } | { all: true } }, key: string) =>
      realCall(() => api.requestRealWithdrawal(body, key)).then(toWithdrawal),
    { newKey },
  );
  const liquidate = useMoneyAction(
    (_body: { liquidation: true }, key: string) => realCall(() => api.startRealLiquidation(key)),
    { newKey },
  );

  const withdrawalTracking = useTracking<Withdrawal>(
    useCallback(() => reads.withdrawal(withdrawalId ?? ''), [reads, withdrawalId]),
    (current) => FINAL_WITHDRAWAL.includes(current.state),
  );
  const liquidationTracking = useTracking<LiquidationProgress>(
    useCallback(async () => {
      // There is no read of a liquidation: it ends when its withdrawal of everything free appears (LiquidationJob);
      // the positions left then say whether it was PARTIAL.
      const known = knownWithdrawals.current;
      if (known === null) {
        return { done: false, remaining: [] };
      }
      const withdrawals = await reads.withdrawals();
      const fresh = withdrawals.some((item) => item.kind === 'TOTAL' && !known.has(item.id));
      if (!fresh) {
        return { done: false, remaining: [] };
      }
      const pnl = await reads.pnl();
      return { done: true, remaining: pnl.positions };
    }, [reads]),
    (progress) => progress.done,
  );

  const trackingDone =
    withdrawalTracking.tracking.kind === 'done' || liquidationTracking.tracking.kind === 'done';
  useEffect(() => {
    if (trackingDone) {
      reloadWallet();
    }
  }, [trackingDone, reloadWallet]);

  async function confirm() {
    if (!pending) {
      return;
    }
    if (pending.kind === 'sellAll') {
      knownWithdrawals.current = await reads
        .withdrawals()
        .then((items) => new Set(items.map((item) => item.id)))
        .catch(() => null);
      if (await liquidate.submit({ liquidation: true })) {
        setPending(null);
        liquidationTracking.start();
      }
      return;
    }
    const body =
      pending.kind === 'all'
        ? { amount: { all: true as const } }
        : { amount: { lamports: pending.lamports.toString() } };
    const result = await withdraw.submit(body);
    if (result) {
      setPending(null);
      setAmount('');
      setTouched(false);
      setWithdrawalId(result.id);
    }
  }

  // The tracking starts once the id of the new withdrawal is known.
  const startWithdrawalTracking = withdrawalTracking.start;
  useEffect(() => {
    if (withdrawalId) {
      startWithdrawalTracking();
    }
  }, [withdrawalId, startWithdrawalTracking]);

  async function copyAddress() {
    try {
      await navigator.clipboard.writeText(wallet.address);
      setCopied(text.copied);
    } catch {
      setCopied(text.copyFailed);
    }
  }

  function open(next: Pending, event: React.MouseEvent<HTMLElement>) {
    opener.current = event.currentTarget;
    setPending(next);
  }

  const action = pending?.kind === 'sellAll' ? liquidate : withdraw;
  return (
    <>
      {termsPending && (
        <p role="status" className="notice notice-warning" data-testid="real-terms-pending">
          {copy.termsPending}
        </p>
      )}
      {wallet.pausedReason === 'KILL_SWITCH' ? (
        <p role="status" className="notice notice-warning" data-testid="real-kill-switch">
          <span aria-hidden="true">⛔ </span>
          {copy.killSwitch}
        </p>
      ) : (
        <PauseBanner reason={wallet.pausedReason} />
      )}
      <section
        aria-labelledby="real-deposit-heading"
        className="real-card"
        data-testid="real-deposit"
      >
        <h2 id="real-deposit-heading">{copy.depositTitle}</h2>
        <p>
          {copy.depositText('')}
          <span className="mono" data-testid="real-deposit-address">
            {wallet.address}
          </span>{' '}
          <button
            type="button"
            className="secondary"
            onClick={() => void copyAddress()}
            data-testid="real-deposit-copy"
          >
            {text.copy}
          </button>
        </p>
        {copied && <p role="status">{copied}</p>}
        <p className="muted">{copy.depositNote}</p>
      </section>
      <section
        aria-labelledby="real-withdraw-heading"
        className="real-card"
        data-testid="real-withdraw"
      >
        <h2 id="real-withdraw-heading">{copy.withdrawTitle}</h2>
        <p data-testid="real-withdraw-destination">{copy.withdrawTo(main)}</p>
        <dl>
          <dt>{limited ? copy.availableNow : copy.available}</dt>
          <dd data-testid="real-withdraw-available">
            {free === undefined ? text.noData : formatSolAmount(free)}
          </dd>
          <dt>{copy.reserved}</dt>
          <dd data-testid="real-withdraw-reserved">
            {wallet.balance ? formatSolAmount(wallet.balance.reserved) : text.noData}
          </dd>
        </dl>
        <SolAmountInput
          id="real-withdraw-amount"
          label={copy.amountLabel}
          value={amount}
          onChange={setAmount}
          error={amountError}
          testId="real-withdraw-amount"
        />
        <p className="dialog-actions">
          <button
            type="button"
            onClick={(event) => {
              setTouched(true);
              if (parsed.ok) {
                open({ kind: 'amount', lamports: parsed.lamports }, event);
              }
            }}
            data-testid="real-withdraw-submit"
          >
            {copy.withdraw}
          </button>{' '}
          <button
            type="button"
            className="secondary"
            onClick={(event) => open({ kind: 'all' }, event)}
            data-testid="real-withdraw-all"
          >
            {copy.withdrawAll}
          </button>{' '}
          <button
            type="button"
            className="secondary"
            onClick={(event) => open({ kind: 'sellAll' }, event)}
            data-testid="real-sell-all"
          >
            {copy.sellAll}
          </button>
        </p>
        <Progress
          withdrawal={withdrawalTracking.tracking}
          liquidation={liquidationTracking.tracking}
        />
        {!pending && withdraw.state.kind === 'error' && (
          <RealErrorNotice error={withdraw.state.error} testId="real-withdraw-error" />
        )}
        {!pending && liquidate.state.kind === 'error' && (
          <RealErrorNotice error={liquidate.state.error} testId="real-sell-all-error" />
        )}
      </section>
      {pending && (
        <MoneyConfirmDialog
          title={pending.kind === 'sellAll' ? copy.confirmSellAllTitle : copy.confirmWithdrawTitle}
          amount={pending.kind === 'amount' ? formatSolAmount(pending.lamports) : undefined}
          destination={main}
          effect={
            pending.kind === 'sellAll'
              ? copy.confirmSellAll(main)
              : pending.kind === 'all'
                ? copy.confirmWithdrawAll(main)
                : copy.confirmWithdraw(formatSolAmount(pending.lamports), main)
          }
          action={action}
          onConfirm={() => void confirm()}
          onRetry={() => void confirm()}
          onClose={() => {
            action.reset();
            setPending(null);
          }}
          returnFocusTo={opener.current}
          testId="real-withdraw-confirm"
        />
      )}
    </>
  );
}

function Progress({
  withdrawal,
  liquidation,
}: {
  withdrawal: Tracking<Withdrawal>;
  liquidation: Tracking<LiquidationProgress>;
}) {
  if (liquidation.kind === 'tracking') {
    return (
      <p role="status" data-testid="real-sell-all-progress">
        {copy.trackingLiquidation}
      </p>
    );
  }
  if (liquidation.kind === 'timeout' || withdrawal.kind === 'timeout') {
    return (
      <p role="status" data-testid="real-tracking-timeout">
        {copy.stillProcessing}
      </p>
    );
  }
  if (liquidation.kind === 'done') {
    const { remaining } = liquidation.latest;
    if (remaining.length === 0) {
      return (
        <p role="status" data-testid="real-sell-all-done">
          {copy.liquidationDone}
        </p>
      );
    }
    return (
      <div role="status" data-testid="real-sell-all-partial">
        <p>{copy.liquidationPartial}</p>
        <ul>
          {remaining.map((position) => (
            <li key={position.mint}>
              {abbreviateMint(position.mint)}: {formatToken(position.baseUnits, undefined)}
            </li>
          ))}
        </ul>
      </div>
    );
  }
  const latest =
    withdrawal.kind === 'tracking' || withdrawal.kind === 'done' ? withdrawal.latest : null;
  if (withdrawal.kind === 'idle') {
    return null;
  }
  const state = latest?.state ?? 'REQUESTED';
  return (
    <p role="status" data-testid="real-withdraw-state">
      {copy.tracking(copy.withdrawalStates[state] ?? state)}
    </p>
  );
}
