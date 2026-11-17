'use client';

import { useMemo, useRef, useState } from 'react';
import Link from 'next/link';
import type { GuestApi } from '@/lib/api/guest-api';
import { encodeBase58 } from '@/lib/base58';
import { formatInstant } from '@/lib/format/format';
import type { WalletPort } from '@/features/auth/wallets';
import { realCall, realReads, toBotWallet, toRealError, type BotWallet } from '@/features/real/api';
import { useLoad } from '@/features/real/hooks/useLoad';
import { useMoneyAction } from '@/features/real/hooks/useMoneyAction';
import { formatSolAmount, MAX_CAP_LAMPORTS, parseSol } from '@/features/real/money';
import { realMessages as text } from '@/features/real/messages';
import { MoneyConfirmDialog } from '@/features/real/components/MoneyConfirmDialog';
import { PauseBanner } from '@/features/real/components/PauseBanner';
import { RealArea } from '@/features/real/components/RealArea';
import { RealErrorNotice } from '@/features/real/components/RealErrorNotice';
import { RealPnlCard } from '@/features/real/components/RealPnlCard';
import { ReleaseStatus } from '@/features/real/components/ReleaseStatus';
import { SolAmountInput } from '@/features/real/components/SolAmountInput';
import { firstWallet } from '@/features/real/components/SigningBanner';

const copy = text.overview;

/**
 * 261113-trading-real-bot U6, R2 `/real` (US2.1, US2.2, US4.2, US8.2; BR5, BR7.2, BR8.2, BR8.3; S8 to S11, S30 to S32,
 * S36): the balance in its parts, the pause with its reason, the release when it is not in force, the mode, the cap
 * and the P&L REAL in its own card. Each part has its own state: an error of the P&L never takes the balance down.
 */
export function OverviewScreen({
  pickWallet = firstWallet,
  timeZone,
  newKey,
}: {
  pickWallet?: () => Promise<WalletPort | null>;
  timeZone?: string;
  newKey?: () => string;
}) {
  return (
    <RealArea origin="/real" title={copy.title} titleId="real-overview-title">
      {({ wallet, release, api, reads, setWallet }) => (
        <>
          <PauseBanner reason={wallet.pausedReason} />
          {release && release.release.state !== 'ACTIVE' && (
            <ReleaseStatus release={release.release} timeZone={timeZone} />
          )}
          <BalanceGrid wallet={wallet} timeZone={timeZone} />
          <p data-testid="real-overview-mode">
            {copy.mode}: {text.mode.options[wallet.mode]?.name ?? wallet.mode}{' '}
            <Link href="/real/modo" data-testid="real-overview-mode-link">
              {copy.changeMode}
            </Link>
          </p>
          <CapSection
            wallet={wallet}
            api={api}
            pickWallet={pickWallet}
            newKey={newKey}
            onSaved={setWallet}
          />
          <PnlPart reads={reads} />
          <p>
            <Link href="/real/deposito-saque" data-testid="real-overview-deposit-link">
              {copy.deposit}
            </Link>{' '}
            ·{' '}
            <Link href="/real/ordens" data-testid="real-overview-orders-link">
              {copy.orders}
            </Link>
          </p>
        </>
      )}
    </RealArea>
  );
}

function BalanceGrid({ wallet, timeZone }: { wallet: BotWallet; timeZone?: string }) {
  const balance = wallet.balance;
  const cells: Array<[string, string, bigint | undefined]> = [
    ['free', copy.free, balance?.free],
    ['reserved', copy.reserved, balance?.reserved],
    ['fee-reserve', copy.feeReserve, balance?.feeReserve],
    ['above-cap', copy.aboveCap, balance?.aboveCap],
  ];
  return (
    <section aria-label={copy.total} data-testid="real-overview-balance">
      <dl className="real-grid">
        {cells.map(([id, label, value]) => (
          <div key={id} className="real-card">
            <dt>{label}</dt>
            <dd data-testid={`real-overview-${id}`}>
              {value === undefined ? text.noData : formatSolAmount(value)}
            </dd>
          </div>
        ))}
      </dl>
      {balance?.asOf && (
        <p className="muted" data-testid="real-overview-as-of">
          {copy.lastReconciliation(formatInstant(balance.asOf, timeZone))}
        </p>
      )}
    </section>
  );
}

function PnlPart({ reads }: { reads: ReturnType<typeof realReads> }) {
  const pnl = useLoad(reads.pnl);
  return <RealPnlCard load={pnl.load} onRetry={pnl.reload} />;
}

type CapBody = { capLamports: string; challengeId?: string; signature?: string };

/**
 * BR5.1 to BR5.4, S8 to S11, S36: the cap field. The text becomes lamports only in money.ts; empty, negative, above
 * 10 SOL or with more than 9 decimals is refused before sending. Lowering goes straight with PATCH; raising asks the
 * challenge RAISE_CAP with the new value and the signature first. The screen says which path it will take.
 */
function CapSection({
  wallet,
  api,
  pickWallet,
  newKey,
  onSaved,
}: {
  wallet: BotWallet;
  api: GuestApi;
  pickWallet: () => Promise<WalletPort | null>;
  newKey?: () => string;
  onSaved: (wallet: BotWallet) => void;
}) {
  const [value, setValue] = useState('');
  const [touched, setTouched] = useState(false);
  const [confirming, setConfirming] = useState(false);
  const [signing, setSigning] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);
  const [problem, setProblem] = useState<string | null>(null);
  const opener = useRef<HTMLButtonElement>(null);
  const max = wallet.maxCap < MAX_CAP_LAMPORTS ? wallet.maxCap : MAX_CAP_LAMPORTS;
  const parsed = useMemo(() => parseSol(value, { max: MAX_CAP_LAMPORTS }), [value]);
  const error = touched && !parsed.ok ? (text.cap.invalid[parsed.reason] ?? null) : null;
  const raising = parsed.ok && parsed.lamports > wallet.cap;
  const save = useMoneyAction(
    (body: CapBody, key: string) =>
      realCall(() => api.changeRealPreferences(body, key), { maxCap: max }).then(toBotWallet),
    { newKey, maxCap: max },
  );

  function saved(next: BotWallet, lowered: boolean) {
    onSaved(next);
    setNotice(lowered ? `${text.cap.saved} ${text.cap.belowExposure}` : text.cap.saved);
    setValue('');
    setTouched(false);
  }

  async function lower() {
    if (!parsed.ok) {
      return;
    }
    const result = await save.submit({ capLamports: parsed.lamports.toString() });
    if (result) {
      saved(result, true);
    }
  }

  async function raise() {
    if (!parsed.ok || signing || save.sending) {
      return;
    }
    setProblem(null);
    const main = await pickWallet();
    if (!main) {
      setProblem(text.activation.noWallet);
      return;
    }
    setSigning(true);
    let body: CapBody;
    try {
      await main.connect();
      const challenge = await realCall(() =>
        api.requestRealChallenge({ kind: 'RAISE_CAP', capLamports: parsed.lamports.toString() }),
      );
      const signature = await main.signMessage(new TextEncoder().encode(challenge.message ?? ''));
      body = {
        capLamports: parsed.lamports.toString(),
        challengeId: challenge.challengeId ?? '',
        signature: encodeBase58(signature),
      };
    } catch (failure) {
      setSigning(false);
      setProblem(
        failure instanceof Error && failure.name === 'RealError'
          ? toRealError(failure).text
          : text.walletRefused,
      );
      return;
    }
    setSigning(false);
    raised(await save.submit(body));
  }

  function raised(result: BotWallet | null) {
    if (result) {
      setConfirming(false);
      saved(result, false);
    }
  }

  function onSubmit() {
    setTouched(true);
    setNotice(null);
    if (!parsed.ok) {
      return;
    }
    if (raising) {
      setConfirming(true);
    } else {
      void lower();
    }
  }

  return (
    <section aria-labelledby="real-cap-title" className="real-card" data-testid="real-cap">
      <h2 id="real-cap-title">{text.cap.title}</h2>
      <p data-testid="real-cap-current">
        {text.cap.current(formatSolAmount(wallet.cap), formatSolAmount(max))}
        {wallet.capSetBy === 'ADMIN' && (
          <>
            {' '}
            <strong data-testid="real-cap-admin">· {text.cap.setByAdmin}</strong>
          </>
        )}
      </p>
      <SolAmountInput
        id="real-cap-input"
        label={text.cap.label}
        value={value}
        onChange={(next) => {
          setValue(next);
          setNotice(null);
        }}
        hint={parsed.ok ? (raising ? text.cap.raisePath : text.cap.lowerPath) : undefined}
        error={error}
        testId="real-cap-input"
      />
      <button
        ref={opener}
        type="button"
        disabled={save.sending}
        onClick={onSubmit}
        data-testid="real-cap-save"
      >
        {save.sending && !confirming ? text.sending : text.cap.save}
      </button>
      {notice && (
        <p role="status" data-testid="real-cap-notice">
          {notice}
        </p>
      )}
      {problem && !confirming && (
        <p role="alert" data-testid="real-cap-problem">
          {problem}
        </p>
      )}
      {!confirming && save.state.kind === 'error' && (
        <RealErrorNotice
          error={save.state.error}
          onRetry={() => void save.retry().then((result) => result && saved(result, true))}
          testId="real-cap-error"
        />
      )}
      {confirming && parsed.ok && (
        <MoneyConfirmDialog
          title={text.cap.confirmRaiseTitle}
          amount={formatSolAmount(parsed.lamports)}
          effect={text.cap.confirmRaise(formatSolAmount(parsed.lamports))}
          action={{ ...save, sending: save.sending || signing }}
          onConfirm={() => void raise()}
          onClose={() => {
            save.reset();
            setProblem(null);
            setConfirming(false);
          }}
          returnFocusTo={opener.current}
          notice={problem}
          onRetry={() => void save.retry().then(raised)}
          testId="real-cap-confirm"
        />
      )}
    </section>
  );
}
