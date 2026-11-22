import { formatSignedSolCompact, lamportsSign, type LamportsSign } from '@/features/real/money';

/**
 * 261121-paper-real-unico U1 (BR7.1, BR7.2, BR7.5; C17): the money texts of the blocks by mode and of the bot list.
 * Nothing here computes: the totals arrive ready from the server and this file only writes them. US$ works on the
 * decimal TEXT of the PAPER ledger (never a JavaScript number); SOL goes through features/real/money.ts, the single
 * place where lamports are bigint.
 */

export type MoneySign = LamportsSign;

const DECIMAL = /^(-?)(\d+)(?:\.(\d+))?$/;

function groupThousands(digits: string): string {
  return digits.replace(/\B(?=(\d{3})+(?!\d))/g, '.');
}

/** gain, loss or zero of a decimal text in US$. */
export function usdSign(value: string): MoneySign {
  const match = DECIMAL.exec(value);
  if (!match) {
    throw new Error('Not a decimal string');
  }
  const digits = `${match[2]}${(match[3] ?? '').slice(0, 2)}`;
  if (/^0+$/.test(digits)) {
    return 'zero';
  }
  return match[1] === '-' ? 'loss' : 'gain';
}

/**
 * BR7.2: "US$" with 2 places, comma for decimals and point for thousands, cut toward zero (never rounded), "+" on a
 * gain, the typographic "−" on a loss and no sign on zero. 12.40 → "+US$ 12,40"; -3.1 → "−US$ 3,10"; 0 → "US$ 0,00".
 */
export function formatUsd(value: string): string {
  const match = DECIMAL.exec(value);
  if (!match) {
    throw new Error('Not a decimal string');
  }
  const whole = groupThousands((match[2] ?? '0').replace(/^0+(?=\d)/, ''));
  const cents = (match[3] ?? '').slice(0, 2).padEnd(2, '0');
  const sign = usdSign(value);
  const prefix = sign === 'gain' ? '+' : sign === 'loss' ? '−' : '';
  return `${prefix}US$ ${whole},${cents}`;
}

/** BR7.1: a P&L in lamports (integer text) as signed SOL, by the money module of the REAL. */
export function formatSol(lamports: string): string {
  return formatSignedSolCompact(lamports);
}

/** gain, loss or zero of an integer text of lamports. */
export function solSign(lamports: string): MoneySign {
  return lamportsSign(lamports);
}
