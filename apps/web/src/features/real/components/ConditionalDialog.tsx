'use client';

import { useRef, useState } from 'react';
import type { GuestApi } from '@/lib/api/guest-api';
import { encodeBase58 } from '@/lib/base58';
import { formatInstant } from '@/lib/format/format';
import type { WalletPort } from '@/features/auth/wallets';
import { realCall, toRealError } from '@/features/real/api';
import {
  CONDITIONAL_KINDS,
  checkTriggers,
  parseUsd,
  showUsd,
  toConditional,
  toQuote,
  type Conditional,
  type ConditionalKind,
  type Quote,
} from '@/features/real/conditionals';
import { useLoad } from '@/features/real/hooks/useLoad';
import { isUnknown } from '@/features/real/money';
import { realMessages as text } from '@/features/real/messages';
import { ModalDialog } from '@/features/real/components/ModalDialog';

const copy = text.exits;

/**
 * 261113-trading-real-bot U7, "Definir stop e alvo" (BR8.2, BR3, BR2.6, BR3.8, BR1.2): the current price in USD with
 * its source and time (unknown, never zero: the button stays off), the kind, the prices as DECIMAL TEXT (no floating
 * point), the bounds of the API, the warning that a stop has no guaranteed execution, and the confirmation. The
 * creation goes with an Idempotency-Key per exact body; the consent the server built is signed with `signMessage` by
 * the main wallet, unchanged, and sent back. The browser builds and sends no transaction.
 */
export function ConditionalDialog({
  api,
  mint,
  tokenLabel,
  pickWallet,
  timeZone,
  newKey = () => crypto.randomUUID(),
  onClose,
  onCreated,
  returnFocusTo,
}: {
  api: GuestApi;
  mint: string;
  tokenLabel: string;
  pickWallet: () => Promise<WalletPort | null>;
  timeZone?: string;
  newKey?: () => string;
  onClose: () => void;
  onCreated: (conditional: Conditional) => void;
  returnFocusTo?: HTMLElement | null;
}) {
  const quote = useLoad(() => realCall(() => api.quoteRealConditional(mint)).then(toQuote));
  const cancelRef = useRef<HTMLButtonElement>(null);
  return (
    <ModalDialog
      title={copy.dialogTitle}
      testId="real-exits-dialog"
      onClose={onClose}
      initialFocus={cancelRef}
      returnFocusTo={returnFocusTo}
    >
      <h2 id="real-exits-dialog-title">
        {copy.dialogTitle} · {tokenLabel}
      </h2>
      {quote.load.kind === 'loading' && <p role="status">{text.loading}</p>}
      {quote.load.kind === 'error' && (
        <p role="alert" data-testid="real-exits-quote-error">
          {quote.load.error.text}
        </p>
      )}
      {quote.load.kind === 'ready' && (
        <ConditionalForm
          api={api}
          quote={quote.load.data}
          pickWallet={pickWallet}
          timeZone={timeZone}
          newKey={newKey}
          onCreated={onCreated}
        />
      )}
      <p>
        <button
          ref={cancelRef}
          type="button"
          onClick={onClose}
          data-testid="real-exits-dialog-close"
        >
          {text.cancel}
        </button>
      </p>
    </ModalDialog>
  );
}

function ConditionalForm({
  api,
  quote,
  pickWallet,
  timeZone,
  newKey,
  onCreated,
}: {
  api: GuestApi;
  quote: Quote;
  pickWallet: () => Promise<WalletPort | null>;
  timeZone?: string;
  newKey: () => string;
  onCreated: (conditional: Conditional) => void;
}) {
  const [kind, setKind] = useState<ConditionalKind>('OCO');
  const [stopText, setStopText] = useState('');
  const [takeProfitText, setTakeProfitText] = useState('');
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);
  const lastKey = useRef<{ body: string; key: string } | null>(null);
  const known = !isUnknown(quote.price);
  const stop = kind === 'TAKE_PROFIT' ? null : parseUsd(stopText);
  const takeProfit = kind === 'STOP' ? null : parseUsd(takeProfitText);
  const pricesValid = (stop === null || stop.ok) && (takeProfit === null || takeProfit.ok);
  const triggerProblem = pricesValid
    ? checkTriggers(
        kind,
        stop && stop.ok ? stop.value : null,
        takeProfit && takeProfit.ok ? takeProfit.value : null,
        quote,
      )
    : null;
  const ready = known && quote.position > 0n && pricesValid && triggerProblem === null && !busy;

  async function confirm() {
    if (!ready) {
      return;
    }
    setProblem(null);
    const body = {
      positionMint: quote.mint,
      kind,
      ...(stop && stop.ok ? { stopPriceUsd: stop.value } : {}),
      ...(takeProfit && takeProfit.ok ? { takeProfitPriceUsd: takeProfit.value } : {}),
    };
    const serialized = JSON.stringify(body);
    const key =
      lastKey.current && lastKey.current.body === serialized ? lastKey.current.key : newKey();
    lastKey.current = { body: serialized, key };
    const wallet = await pickWallet();
    if (!wallet) {
      setProblem(text.activation.noWallet);
      return;
    }
    setBusy(true);
    try {
      await wallet.connect();
      const created = await realCall(() => api.createRealConditional(body, key));
      const session = created.signingSession;
      if (session.kind !== 'MESSAGE' || !session.message) {
        setProblem(copy.transactionNotYet);
        setBusy(false);
        return;
      }
      let signature: Uint8Array;
      try {
        signature = await wallet.signMessage(new TextEncoder().encode(session.message));
      } catch {
        setProblem(text.walletRefused);
        setBusy(false);
        return;
      }
      const signed = await realCall(() =>
        api.signRealConditional(
          created.conditionalOrderId,
          { signature: encodeBase58(signature) },
          newKey(),
        ),
      );
      lastKey.current = null;
      setBusy(false);
      onCreated(toConditional(signed));
    } catch (failure) {
      setBusy(false);
      setProblem(toRealError(failure).text);
    }
  }

  return (
    <form
      data-testid="real-exits-form"
      onSubmit={(event) => {
        event.preventDefault();
        void confirm();
      }}
    >
      <p data-testid="real-exits-price">
        {copy.currentPrice}:{' '}
        {isUnknown(quote.price) ? copy.noPrice : <strong>{showUsd(quote.price)}</strong>}
      </p>
      {!isUnknown(quote.price) && quote.source && quote.observedAt && (
        <p className="muted" data-testid="real-exits-price-source">
          {copy.priceFrom(quote.source, formatInstant(quote.observedAt, timeZone))}
        </p>
      )}
      <p data-testid="real-exits-position">{copy.position(quote.position.toString())}</p>
      <fieldset data-testid="real-exits-kind">
        <legend>{copy.kind}</legend>
        {CONDITIONAL_KINDS.map((option) => (
          <p key={option}>
            <input
              id={`real-exits-kind-${option.toLowerCase()}`}
              type="radio"
              name="real-exits-kind"
              value={option}
              checked={kind === option}
              onChange={() => setKind(option)}
              data-testid={`real-exits-kind-${option}`}
            />{' '}
            <label htmlFor={`real-exits-kind-${option.toLowerCase()}`}>{copy.kinds[option]}</label>
          </p>
        ))}
      </fieldset>
      {kind !== 'TAKE_PROFIT' && (
        <PriceField
          id="real-exits-stop"
          label={copy.stop}
          value={stopText}
          onChange={setStopText}
          error={stop && !stop.ok && stopText !== '' ? (copy.invalid[stop.reason] ?? null) : null}
        />
      )}
      {kind !== 'STOP' && (
        <PriceField
          id="real-exits-take-profit"
          label={copy.takeProfit}
          value={takeProfitText}
          onChange={setTakeProfitText}
          error={
            takeProfit && !takeProfit.ok && takeProfitText !== ''
              ? (copy.invalid[takeProfit.reason] ?? null)
              : null
          }
        />
      )}
      {quote.minStop && quote.maxTakeProfit && (
        <p className="muted" data-testid="real-exits-bounds">
          {copy.bounds(showUsd(quote.minStop), showUsd(quote.maxTakeProfit))}
        </p>
      )}
      {kind !== 'TAKE_PROFIT' && (
        <p role="note" data-testid="real-exits-stop-warning">
          {copy.stopWarning}
        </p>
      )}
      {triggerProblem && triggerProblem !== 'PRICE_UNKNOWN' && (
        <p role="alert" data-testid="real-exits-trigger-problem">
          {text.errors[triggerProblem]}
        </p>
      )}
      <p className="muted">{copy.consent}</p>
      {problem && (
        <p role="alert" data-testid="real-exits-problem">
          {problem}
        </p>
      )}
      <button type="submit" disabled={!ready} data-testid="real-exits-confirm">
        {busy ? copy.walletSigning : copy.confirm}
      </button>
    </form>
  );
}

function PriceField({
  id,
  label,
  value,
  onChange,
  error,
}: {
  id: string;
  label: string;
  value: string;
  onChange: (value: string) => void;
  error: string | null;
}) {
  return (
    <p>
      <label htmlFor={id}>{label}</label>{' '}
      <input
        id={id}
        type="text"
        inputMode="decimal"
        autoComplete="off"
        value={value}
        aria-invalid={error ? 'true' : undefined}
        aria-describedby={error ? `${id}-error` : undefined}
        onChange={(event) => onChange(event.target.value)}
        data-testid={id}
      />
      {error && (
        <span id={`${id}-error`} role="alert" data-testid={`${id}-error`}>
          {' '}
          {error}
        </span>
      )}
    </p>
  );
}
