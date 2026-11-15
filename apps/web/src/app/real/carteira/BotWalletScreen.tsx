'use client';

import { useCallback, useEffect, useState } from 'react';
import { ApiError } from '@/lib/api/client';
import { useApi } from '@/lib/api/ApiProvider';
import type { RealBotWallet, RealWithdrawal } from '@/lib/api/guest-api';
import { encodeBase58 } from '@/lib/base58';
import { AppShell } from '@/components/shell/AppShell';
import { useSession } from '@/features/auth/SessionProvider';
import { useRequireSession } from '@/features/auth/useRequireSession';
import { watchWallets, type WalletPort } from '@/features/auth/wallets';
import { formatSolAmount, parseSol } from './lamports';

/**
 * Minimal screen of the bot wallet for the walking skeleton (US1.2, US2.2, US5.1; NFR7.1): activation signed by the
 * main wallet, the deposit address, the balance and the withdrawal to the main wallet. Loading, error and unavailable
 * are states in text. The browser only signs the consent message; it never builds nor sends a transaction (ADR-008).
 */
const text = {
  title: 'Carteira do bot',
  loading: 'Carregando a carteira do bot…',
  error: 'Não foi possível carregar a carteira do bot. Tente de novo.',
  retry: 'Tentar de novo',
  none: 'Você ainda não tem uma carteira do bot.',
  capLabel: 'Teto de operação em SOL (até 10)',
  activate: 'Ativar a carteira do bot',
  activating: 'Assine a mensagem na sua carteira…',
  noWallet: 'Nenhuma carteira compatível encontrada no navegador.',
  invalidAmount: 'Informe um valor em SOL, com até 9 casas decimais.',
  address: 'Endereço para depósito',
  capNotice: 'O bot só opera até o teto. O que passar do teto fica disponível apenas para saque.',
  total: 'Saldo total',
  free: 'Livre',
  reserved: 'Reservado em saques e ordens',
  feeReserve: 'Reserva para taxas',
  aboveCap: 'Acima do teto (só para saque)',
  cap: 'Teto',
  withdrawTitle: 'Sacar para a sua carteira principal',
  withdrawTo: 'O saque vai sempre para a carteira principal',
  amountLabel: 'Valor do saque em SOL',
  withdraw: 'Sacar',
  withdrawAll: 'Sacar tudo',
  requested: 'Saque pedido. Estado:',
  errors: {
    REAL_DISABLED: 'O REAL está desligado neste ambiente.',
    REAL_NOT_RELEASED: 'O REAL ainda não está liberado para a sua conta.',
    TERMS_REACCEPTANCE_REQUIRED: 'Aceite a versão dos termos do REAL antes de ativar.',
    CONSENT_INVALID: 'A assinatura não confere com a mensagem. Peça uma nova ativação.',
    CONSENT_EXPIRED: 'A mensagem venceu. Peça uma nova ativação.',
    CAP_ABOVE_MAXIMUM: 'O teto passa do máximo permitido.',
    UPSTREAM_UNAVAILABLE: 'O serviço de assinatura está indisponível. Tente de novo em instantes.',
    INSUFFICIENT_FREE_BALANCE: 'Saldo livre insuficiente para esse saque.',
    ORDER_AMBIGUOUS: 'Há um saque ou ordem em conferência. Aguarde para sacar tudo.',
    BOT_WALLET_ALREADY_ACTIVE: 'Você já tem uma carteira do bot ativa.',
  } as Record<string, string>,
  genericError: 'Não foi possível concluir. Tente de novo.',
};

type Load =
  | { kind: 'loading' }
  | { kind: 'error' }
  | { kind: 'none' }
  | { kind: 'ready'; wallet: RealBotWallet };

function firstWallet(): Promise<WalletPort | null> {
  return new Promise((resolve) => {
    const stop = watchWallets((wallets) => {
      resolve(wallets[0] ?? null);
    });
    stop();
  });
}

function messageOf(failure: unknown): string {
  if (failure instanceof ApiError) {
    return text.errors[failure.code] ?? text.genericError;
  }
  return text.genericError;
}

function newKey(): string {
  return crypto.randomUUID();
}

export function BotWalletScreen({
  pickWallet = firstWallet,
}: {
  pickWallet?: () => Promise<WalletPort | null>;
}) {
  const status = useRequireSession('/real/carteira');
  const api = useApi();
  const { expire } = useSession();
  const [load, setLoad] = useState<Load>({ kind: 'loading' });
  const [cap, setCap] = useState('1');
  const [amount, setAmount] = useState('');
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);
  const [withdrawal, setWithdrawal] = useState<RealWithdrawal | null>(null);
  const [withdrawKey, setWithdrawKey] = useState(newKey);

  const fetchWallet = useCallback(async () => {
    setLoad({ kind: 'loading' });
    try {
      setLoad({ kind: 'ready', wallet: await api.getBotWallet() });
    } catch (failure) {
      if (failure instanceof ApiError && failure.status === 401) {
        expire();
        return;
      }
      setLoad(
        failure instanceof ApiError && failure.status === 404
          ? { kind: 'none' }
          : { kind: 'error' },
      );
    }
  }, [api, expire]);

  useEffect(() => {
    if (status === 'authenticated') {
      void fetchWallet();
    }
  }, [status, fetchWallet]);

  async function activate() {
    setProblem(null);
    const capLamports = parseSol(cap);
    if (capLamports === null) {
      setProblem(text.invalidAmount);
      return;
    }
    const wallet = await pickWallet();
    if (wallet === null) {
      setProblem(text.noWallet);
      return;
    }
    setBusy(true);
    try {
      await wallet.connect();
      const challenge = await api.requestRealChallenge({ kind: 'ACTIVATE', capLamports });
      const signature = await wallet.signMessage(new TextEncoder().encode(challenge.message));
      const created = await api.activateBotWallet({
        challengeId: challenge.challengeId,
        signature: encodeBase58(signature),
      });
      setLoad({ kind: 'ready', wallet: created });
    } catch (failure) {
      setProblem(messageOf(failure));
    } finally {
      setBusy(false);
    }
  }

  async function withdraw(all: boolean) {
    setProblem(null);
    const lamports = all ? null : parseSol(amount);
    if (!all && lamports === null) {
      setProblem(text.invalidAmount);
      return;
    }
    setBusy(true);
    try {
      const requested = await api.requestRealWithdrawal(
        { amount: all ? { all: true } : { lamports: lamports as string } },
        withdrawKey,
      );
      setWithdrawal(requested);
      setWithdrawKey(newKey());
      await fetchWallet();
    } catch (failure) {
      setProblem(messageOf(failure));
    } finally {
      setBusy(false);
    }
  }

  return (
    <AppShell current="account" titleId="real-wallet-title">
      <h1 id="real-wallet-title">{text.title}</h1>
      {load.kind === 'loading' && (
        <p role="status" data-testid="real-wallet-loading">
          {text.loading}
        </p>
      )}
      {load.kind === 'error' && (
        <div role="alert" data-testid="real-wallet-error">
          <p>{text.error}</p>
          <button type="button" data-testid="real-wallet-retry" onClick={() => void fetchWallet()}>
            {text.retry}
          </button>
        </div>
      )}
      {problem !== null && (
        <p role="alert" data-testid="real-wallet-problem">
          {problem}
        </p>
      )}
      {load.kind === 'none' && (
        <section aria-labelledby="real-activate-title" data-testid="real-wallet-activation">
          <h2 id="real-activate-title">{text.activate}</h2>
          <p>{text.none}</p>
          <label htmlFor="real-cap">{text.capLabel}</label>
          <input
            id="real-cap"
            data-testid="real-wallet-cap-input"
            inputMode="decimal"
            value={cap}
            onChange={(event) => setCap(event.target.value)}
          />
          <button
            type="button"
            data-testid="real-wallet-activate-button"
            disabled={busy}
            onClick={() => void activate()}
          >
            {busy ? text.activating : text.activate}
          </button>
        </section>
      )}
      {load.kind === 'ready' && (
        <>
          <section aria-labelledby="real-address-title" data-testid="real-wallet-summary">
            <h2 id="real-address-title">{text.address}</h2>
            <p data-testid="real-wallet-address" className="mono">
              {load.wallet.address}
            </p>
            <p>{text.capNotice}</p>
            <dl>
              <dt>{text.total}</dt>
              <dd data-testid="real-wallet-total">
                {formatSolAmount(load.wallet.balance?.totalLamports)}
              </dd>
              <dt>{text.free}</dt>
              <dd data-testid="real-wallet-free">
                {formatSolAmount(load.wallet.balance?.freeLamports)}
              </dd>
              <dt>{text.reserved}</dt>
              <dd data-testid="real-wallet-reserved">
                {formatSolAmount(load.wallet.balance?.reservedLamports)}
              </dd>
              <dt>{text.feeReserve}</dt>
              <dd data-testid="real-wallet-fee-reserve">
                {formatSolAmount(load.wallet.balance?.feeReserveLamports)}
              </dd>
              <dt>{text.aboveCap}</dt>
              <dd data-testid="real-wallet-above-cap">
                {formatSolAmount(load.wallet.balance?.aboveCapLamports)}
              </dd>
              <dt>{text.cap}</dt>
              <dd data-testid="real-wallet-cap">{formatSolAmount(load.wallet.capLamports)}</dd>
            </dl>
          </section>
          <section aria-labelledby="real-withdraw-title" data-testid="real-wallet-withdrawal">
            <h2 id="real-withdraw-title">{text.withdrawTitle}</h2>
            <p>
              {text.withdrawTo}{' '}
              <span data-testid="real-wallet-main" className="mono">
                {load.wallet.mainWalletAddress}
              </span>
            </p>
            <label htmlFor="real-amount">{text.amountLabel}</label>
            <input
              id="real-amount"
              data-testid="real-wallet-amount-input"
              inputMode="decimal"
              value={amount}
              onChange={(event) => setAmount(event.target.value)}
            />
            <button
              type="button"
              data-testid="real-wallet-withdraw-button"
              disabled={busy}
              onClick={() => void withdraw(false)}
            >
              {text.withdraw}
            </button>
            <button
              type="button"
              data-testid="real-wallet-withdraw-all-button"
              disabled={busy}
              onClick={() => void withdraw(true)}
            >
              {text.withdrawAll}
            </button>
            {withdrawal !== null && (
              <p role="status" data-testid="real-wallet-withdrawal-state">
                {text.requested} {withdrawal.state}
              </p>
            )}
          </section>
        </>
      )}
    </AppShell>
  );
}
