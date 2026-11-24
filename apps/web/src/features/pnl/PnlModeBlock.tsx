'use client';

import Link from 'next/link';
import { useEffect, useRef, type ReactNode, type RefObject } from 'react';
import type {
  LamportsAmount,
  PaperBotPnlLine,
  PaperModeBlock,
  PnlAmount,
  RealBotPnlLine,
  RealIneligibilityReason,
  RealModeBlock,
} from '@/lib/api/guest-api';
import { formatClock } from '@/lib/format/format';
import { SimulatedBadge } from '@/components/SimulatedBadge';
import { TableScroll } from '@/components/TableScroll';
import { RealBadge } from '@/features/real/components/RealBadge';
import { modeStepMessages } from '@/features/bots/mode-messages';
import { modeMessages as text } from '@/features/pnl/mode-messages';
import {
  formatSol,
  formatUsd,
  isZeroText,
  solSign,
  usdSign,
  type MoneySign,
} from '@/features/pnl/money-format';
import { ProratedLines } from '@/features/pnl/ProratedLines';

/** The load of ONE block of C4: an UNAVAILABLE block or a failed route is an error with the code for the support. */
export type BlockLoad<B> =
  | { kind: 'loading' }
  | { kind: 'error'; code: string }
  | { kind: 'ready'; block: B };

/** 261121-paper-real-unico U3 (BR4.6): the load of the line of ONE bot; `none` when the block has no line for it yet. */
export type BotLineLoad<L> = BlockLoad<L> | { kind: 'none' };

/**
 * 261121-paper-real-unico U3 (BR7.4): why the REAL block is empty, from C2. `available` offers the creation of a REAL
 * bot; a reason other than WALLET_INACTIVE is only written; `unknown` (C2 failed) shows no link.
 */
export type RealEmptyReason =
  | { kind: 'available' }
  | { kind: 'reason'; reason: RealIneligibilityReason }
  | { kind: 'unknown' };

type HeadingLevel = 2 | 3;

type FullProps =
  | {
      market: 'PAPER';
      load: BlockLoad<PaperModeBlock>;
      onRetry: () => void;
      variant?: 'full';
      period?: string;
      timeZone?: string;
      focusHeading?: boolean;
      headingLevel?: HeadingLevel;
      realUnavailable?: undefined;
      /** U3 (BR2.9): content at the end of the block (the table by token of /pnl). */
      children?: ReactNode;
    }
  | {
      market: 'REAL';
      load: BlockLoad<RealModeBlock>;
      onRetry: () => void;
      variant?: 'full';
      period?: string;
      timeZone?: string;
      focusHeading?: boolean;
      headingLevel?: HeadingLevel;
      realUnavailable?: RealEmptyReason;
      children?: ReactNode;
    };

type CompactProps =
  | {
      variant: 'compact';
      market: 'PAPER';
      load: BlockLoad<PaperModeBlock>;
      onRetry: () => void;
      headingLevel?: HeadingLevel;
      headingRef?: RefObject<HTMLHeadingElement | null>;
      /** BR7.5: whether a new total is announced (at most once every 30 s, never on the first load). */
      announce?: boolean;
      timeZone?: string;
    }
  | {
      variant: 'compact';
      market: 'REAL';
      load: BlockLoad<RealModeBlock>;
      onRetry: () => void;
      headingLevel?: HeadingLevel;
      headingRef?: RefObject<HTMLHeadingElement | null>;
      announce?: boolean;
      realUnavailable?: RealEmptyReason;
      timeZone?: string;
    };

type BotProps =
  | {
      variant: 'bot';
      market: 'PAPER';
      load: BotLineLoad<PaperBotPnlLine>;
      heading: ReactNode;
      onRetry?: () => void;
      totalLabel?: string;
      /** The fixed disclaimer of the simulated mode, as the API sent it (C4 or C6). */
      disclaimer?: string;
      headingLevel?: HeadingLevel;
      headingRef?: RefObject<HTMLHeadingElement | null>;
      testId?: string;
      children?: ReactNode;
    }
  | {
      variant: 'bot';
      market: 'REAL';
      load: BotLineLoad<RealBotPnlLine>;
      heading: ReactNode;
      onRetry?: () => void;
      totalLabel?: string;
      disclaimer?: undefined;
      headingLevel?: HeadingLevel;
      headingRef?: RefObject<HTMLHeadingElement | null>;
      testId?: string;
      children?: ReactNode;
    };

export type PnlModeBlockProps = FullProps | CompactProps | BotProps;

function browserTimeZone(): string {
  return Intl.DateTimeFormat().resolvedOptions().timeZone;
}

function signWord(sign: MoneySign): string {
  return sign === 'gain' ? text.gain : sign === 'loss' ? text.loss : text.zero;
}

function Heading({
  level,
  children,
  ...rest
}: {
  level: HeadingLevel;
  children: ReactNode;
  id: string;
  tabIndex: number;
  'data-testid': string;
  innerRef?: RefObject<HTMLHeadingElement | null>;
}) {
  const { innerRef, ...props } = rest;
  return level === 3 ? (
    <h3 ref={innerRef} {...props}>
      {children}
    </h3>
  ) : (
    <h2 ref={innerRef} {...props}>
      {children}
    </h2>
  );
}

/** A US$ amount with sign and word; color is never the only signal. */
function Usd({ value, testId }: { value: string; testId: string }) {
  const sign = usdSign(value);
  return (
    <span className={`money money-${sign}`} data-testid={testId} data-sign={sign}>
      {formatUsd(value)} <span className="muted">({signWord(sign)})</span>
    </span>
  );
}

/** A SOL amount (lamports text) with sign and word. */
function Sol({ value, testId }: { value: string; testId: string }) {
  const sign = solSign(value);
  return (
    <span className={`money money-${sign}`} data-testid={testId} data-sign={sign}>
      {formatSol(value)} <span className="muted">({signWord(sign)})</span>
    </span>
  );
}

function UsdAmount({ amount, testId }: { amount: PnlAmount; testId: string }) {
  if (amount.status === 'UNKNOWN') {
    return (
      <span className="money money-unknown" data-testid={testId} data-status="UNKNOWN">
        {text.unknown(text.unknownReasons[amount.reason] ?? amount.reason)}
      </span>
    );
  }
  return <Usd value={amount.value} testId={testId} />;
}

function SolAmount({ amount, testId }: { amount: LamportsAmount; testId: string }) {
  if (amount.status === 'UNKNOWN') {
    return (
      <span className="money money-unknown" data-testid={testId} data-status="UNKNOWN">
        {text.unknown(text.unknownReasons[amount.reason] ?? amount.reason)}
      </span>
    );
  }
  return <Sol value={amount.value} testId={testId} />;
}

/** A total: unknown is "desconhecido (depende do não realizado)", never zero. */
function TotalValue({
  market,
  amount,
  testId,
}: {
  market: 'PAPER' | 'REAL';
  amount: PnlAmount | LamportsAmount;
  testId: string;
}) {
  if (amount.status === 'UNKNOWN') {
    return (
      <span className="money money-unknown" data-testid={testId} data-status="UNKNOWN">
        {text.totalUnknown}
      </span>
    );
  }
  return market === 'PAPER' ? (
    <Usd value={amount.value} testId={testId} />
  ) : (
    <Sol value={amount.value} testId={testId} />
  );
}

/**
 * 261121-paper-real-unico U1 (C17; BR5.1 to BR5.5, BR6.1, BR7.1, BR7.2, BR9.1 to BR9.3; NFR5.x, NFR6.x): ONE block
 * of the P&L by mode, variant {@code full}. It receives a single market (no prop takes both) and never adds,
 * subtracts nor converts a value: the totals arrive ready from the server; it only writes them (US$ for PAPER, SOL for
 * REAL). Five distinct states: loading (never zero), ready, empty, unknown (total unknown, realized and fees exact)
 * and error ("Tentar de novo" reads the route again and updates only this block). The PAPER block always shows its
 * fixed disclaimer when it shows a value. 261121-paper-real-unico U2 (BR10.4): the REAL block has no fixed note any
 * more and its fees are just "Taxas", as in the PAPER: the realized is gross and the total takes the fees out.
 *
 * 261121-paper-real-unico U3 (BR3.4, BR4.7, BR4.8, BR7.1 to BR7.4, BR9.2; only additions): the variant `compact` of
 * the dashboard (total, realized, fees, the disclaimer in the PAPER, the link to /pnl), the variant `bot` of one bot
 * line (total, realized, unrealized and fees, "Total do bot, em …"), the prorated shares of each REAL bot and the line
 * "Sem bot" in the REAL `full`, and the optional `realUnavailable` and `headingLevel`.
 */
export function PnlModeBlock(props: PnlModeBlockProps) {
  if (props.variant === 'compact') {
    return <CompactBlock {...props} />;
  }
  if (props.variant === 'bot') {
    return <BotBlock {...props} />;
  }
  return <FullBlock {...props} />;
}

function FullBlock(props: FullProps) {
  const { market, load, onRetry, period, focusHeading } = props;
  const zone = props.timeZone ?? browserTimeZone();
  const id = `pnl-block-${market}`;
  const messages = text[market];
  const heading = useRef<HTMLHeadingElement>(null);

  useEffect(() => {
    if (focusHeading) {
      heading.current?.focus();
    }
  }, [focusHeading]);

  return (
    <section aria-labelledby={`${id}-title`} className="pnl-mode-block" data-testid={id}>
      <Heading
        level={props.headingLevel ?? 2}
        id={`${id}-title`}
        innerRef={heading}
        tabIndex={-1}
        data-testid={`${id}-title`}
      >
        {messages.heading}{' '}
        {market === 'PAPER' ? (
          <SimulatedBadge testId={`${id}-badge`} />
        ) : (
          <RealBadge testId={`${id}-badge`} />
        )}
      </Heading>
      {period && <p className="muted">{period}</p>}
      {load.kind === 'loading' && (
        <p role="status" aria-busy="true" data-testid={`${id}-loading`}>
          {messages.loading}
        </p>
      )}
      {load.kind === 'error' && (
        <div role="alert" className="notice notice-error" data-testid={`${id}-error`}>
          <p>{messages.error}</p>
          <p className="muted" data-testid={`${id}-code`}>
            {text.supportCode(load.code)}
          </p>
          <button type="button" className="secondary" data-testid={`${id}-retry`} onClick={onRetry}>
            {text.retry}
          </button>
        </div>
      )}
      {load.kind === 'ready' && props.market === 'PAPER' && (
        <PaperContent block={(props.load as { block: PaperModeBlock }).block} zone={zone} />
      )}
      {load.kind === 'ready' && props.market === 'REAL' && (
        <RealContent
          block={(props.load as { block: RealModeBlock }).block}
          zone={zone}
          realUnavailable={props.realUnavailable}
        />
      )}
      {props.children}
    </section>
  );
}

function PaperContent({ block, zone }: { block: PaperModeBlock; zone: string }) {
  const id = 'pnl-block-PAPER';
  if (block.empty) {
    return (
      <div data-testid={`${id}-empty`}>
        <p>{text.PAPER.empty}</p>
        <Link href="/bots/new">{text.PAPER.emptyLink}</Link>
      </div>
    );
  }
  const unknown = block.total.status === 'UNKNOWN';
  return (
    <>
      <p role="note" className="notice" data-testid={`${id}-disclaimer`}>
        {block.disclaimer}
      </p>
      {unknown && (
        <p role="note" className="notice notice-warning" data-testid={`${id}-partial`}>
          {text.partial}
        </p>
      )}
      <dl className="totals">
        <dt>{text.PAPER.total}</dt>
        <dd aria-live="polite" aria-atomic="true" aria-label={text.PAPER.total}>
          {unknown ? (
            <span className="money money-unknown" data-testid={`${id}-total`} data-status="UNKNOWN">
              {text.totalUnknown}
            </span>
          ) : (
            <UsdAmount amount={block.total} testId={`${id}-total`} />
          )}
        </dd>
        <dt>{text.realized}</dt>
        <dd>
          <Usd value={block.realized} testId={`${id}-realized`} />
        </dd>
        <dt>{text.unrealized}</dt>
        <dd>
          <UsdAmount amount={block.unrealized} testId={`${id}-unrealized`} />
          <p className="muted">
            {text.unrealizedNow(formatClock(new Date(block.unrealizedAsOf), zone))}
          </p>
        </dd>
        <dt>{text.PAPER.fees}</dt>
        <dd>
          <Usd value={block.fees} testId={`${id}-fees`} />
        </dd>
      </dl>
      <TableScroll label={`${text.bots}: ${text.PAPER.heading}`} testId={`${id}-bots-scroll`}>
        <table data-testid={`${id}-bots`}>
          <caption>{text.bots}</caption>
          <thead>
            <tr>
              <th scope="col">{text.columns.bot}</th>
              <th scope="col">{text.columns.status}</th>
              <th scope="col">{text.columns.total}</th>
            </tr>
          </thead>
          <tbody>
            {block.bots.map((bot) => (
              <tr key={bot.botId} data-testid={`${id}-bot-${bot.botId}`}>
                <th scope="row">{bot.name}</th>
                <td>{text.botStatus[bot.botStatus] ?? bot.botStatus}</td>
                <td>
                  <UsdAmount amount={bot.total} testId={`${id}-bot-total-${bot.botId}`} />
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </TableScroll>
    </>
  );
}

/** BR7.4: the empty REAL with the link of its reason; without C2 (the U1 /pnl) the activation link, as before. */
function RealEmpty({
  testId,
  sentence,
  realUnavailable,
}: {
  testId: string;
  sentence: string;
  realUnavailable?: RealEmptyReason;
}) {
  if (realUnavailable === undefined) {
    return (
      <div data-testid={testId}>
        <p>{sentence}</p>
        <Link href="/real">{text.REAL.emptyLink}</Link>
      </div>
    );
  }
  return (
    <div
      data-testid={testId}
      data-reason={
        realUnavailable.kind === 'reason' ? realUnavailable.reason : realUnavailable.kind
      }
    >
      <p>{sentence}</p>
      {realUnavailable.kind === 'available' && (
        <Link href="/bots/new" data-testid={`${testId}-create`}>
          {text.compact.REAL.createLink}
        </Link>
      )}
      {realUnavailable.kind === 'reason' && realUnavailable.reason === 'WALLET_INACTIVE' && (
        <Link href="/real" data-testid={`${testId}-activate`}>
          {text.compact.REAL.activateLink}
        </Link>
      )}
      {realUnavailable.kind === 'reason' && realUnavailable.reason !== 'WALLET_INACTIVE' && (
        <p className="muted" data-testid={`${testId}-reason`}>
          {modeStepMessages.reasons[realUnavailable.reason] ?? realUnavailable.reason}
        </p>
      )}
    </div>
  );
}

function RealContent({
  block,
  zone,
  realUnavailable,
}: {
  block: RealModeBlock;
  zone: string;
  realUnavailable?: RealEmptyReason;
}) {
  const id = 'pnl-block-REAL';
  if (block.empty) {
    return (
      <RealEmpty
        testId={`${id}-empty`}
        sentence={text.REAL.empty}
        realUnavailable={realUnavailable}
      />
    );
  }
  const unknown = block.totalLamports.status === 'UNKNOWN';
  return (
    <>
      {unknown && (
        <p role="note" className="notice notice-warning" data-testid={`${id}-partial`}>
          {text.partial}
        </p>
      )}
      <dl className="totals">
        <dt>{text.REAL.total}</dt>
        <dd aria-live="polite" aria-atomic="true" aria-label={text.REAL.total}>
          {unknown ? (
            <span className="money money-unknown" data-testid={`${id}-total`} data-status="UNKNOWN">
              {text.totalUnknown}
            </span>
          ) : (
            <SolAmount amount={block.totalLamports} testId={`${id}-total`} />
          )}
        </dd>
        <dt>{text.realized}</dt>
        <dd>
          <Sol value={block.realizedLamports} testId={`${id}-realized`} />
        </dd>
        <dt>{text.unrealized}</dt>
        <dd>
          <SolAmount amount={block.unrealizedLamports} testId={`${id}-unrealized`} />
          <p className="muted">
            {text.unrealizedNow(formatClock(new Date(block.unrealizedAsOf), zone))}
          </p>
        </dd>
        <dt>{text.REAL.fees}</dt>
        <dd>
          <Sol value={block.feesLamports} testId={`${id}-fees`} />
        </dd>
      </dl>
      <TableScroll label={`${text.bots}: ${text.REAL.heading}`} testId={`${id}-bots-scroll`}>
        <table data-testid={`${id}-bots`}>
          <caption>{text.bots}</caption>
          <thead>
            <tr>
              <th scope="col">{text.columns.bot}</th>
              <th scope="col">{text.columns.status}</th>
              <th scope="col">{text.columns.total}</th>
            </tr>
          </thead>
          <tbody>
            {block.bots.map((bot) => (
              <tr key={bot.botId} data-testid={`${id}-bot-${bot.botId}`}>
                <th scope="row">{bot.name}</th>
                <td>{text.botStatus[bot.botStatus] ?? bot.botStatus}</td>
                <td>
                  <SolAmount amount={bot.totalLamports} testId={`${id}-bot-total-${bot.botId}`} />
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </TableScroll>
      {block.bots.map((bot) =>
        bot.prorated && bot.prorated.count > 0 ? (
          <section
            key={`prorated-${bot.botId}`}
            aria-label={text.prorated.caption(bot.name)}
            className="pnl-prorated"
          >
            <p>
              <strong>{bot.name}</strong>
            </p>
            <ProratedLines
              botId={bot.botId}
              botName={bot.name}
              prorated={bot.prorated}
              timeZone={zone}
            />
          </section>
        ) : null,
      )}
      <UnownedLine unowned={block.unowned} />
    </>
  );
}

/** BR4.8: whether the line "Sem bot" has a value. A text comparison with "0", never arithmetic. */
export function unownedVisible(unowned: RealModeBlock['unowned']): boolean {
  if (unowned === null || unowned === undefined) {
    return false;
  }
  return (
    !isZeroText(unowned.realizedLamports) ||
    !isZeroText(unowned.feesLamports) ||
    unowned.unrealizedLamports.status === 'UNKNOWN' ||
    !isZeroText(unowned.unrealizedLamports.value)
  );
}

/**
 * BR4.8 (FR3.4, FR5.3): the line "Sem bot" only when it has a value, always with each reason in text; not a link. Its
 * values come from the API as they are, in SOL.
 */
function UnownedLine({ unowned }: { unowned: RealModeBlock['unowned'] }) {
  if (!unownedVisible(unowned) || !unowned) {
    return null;
  }
  const id = 'pnl-block-REAL-nobot';
  return (
    <section aria-labelledby={`${id}-title`} className="pnl-nobot" data-testid={id}>
      <p id={`${id}-title`}>
        <strong>{text.noBot}</strong> <span className="muted">{text.noBotIntro}</span>
      </p>
      <dl className="totals">
        <dt>{text.realized}</dt>
        <dd>
          <Sol value={unowned.realizedLamports} testId={`${id}-realized`} />
        </dd>
        <dt>{text.unrealized}</dt>
        <dd>
          <SolAmount amount={unowned.unrealizedLamports} testId={`${id}-unrealized`} />
        </dd>
        <dt>{text.REAL.fees}</dt>
        <dd>
          <Sol value={unowned.feesLamports} testId={`${id}-fees`} />
        </dd>
      </dl>
      <ul data-testid={`${id}-reasons`}>
        {unowned.reasons.map((reason) => (
          <li key={reason}>{text.noBotReason[reason] ?? text.noBotReasonUnknown(reason)}</li>
        ))}
      </ul>
    </section>
  );
}

/**
 * U3 (BR7.1 to BR7.5, BR3.4, BR9.2): the card of the dashboard. Total (in a polite atomic region; `announce` false
 * turns the announcement off), realized and fees; the fixed disclaimer only in the PAPER with a value; the link to
 * /pnl. Five states: loading, ready, unknown, empty and unavailable with the code and "Tentar de novo".
 */
function CompactBlock(props: CompactProps) {
  const { market, load, onRetry } = props;
  const id = `pnl-compact-${market}`;
  const messages = text.compact[market];
  return (
    <section
      aria-labelledby={`${id}-title`}
      className="pnl-mode-block pnl-compact"
      data-testid={id}
    >
      <Heading
        level={props.headingLevel ?? 3}
        id={`${id}-title`}
        innerRef={props.headingRef}
        tabIndex={-1}
        data-testid={`${id}-title`}
      >
        {messages.heading}{' '}
        {market === 'PAPER' ? (
          <SimulatedBadge testId={`${id}-badge`} />
        ) : (
          <RealBadge testId={`${id}-badge`} />
        )}
      </Heading>
      {load.kind === 'loading' && (
        <p role="status" aria-busy="true" data-testid={`${id}-loading`}>
          {text[market].loading}
        </p>
      )}
      {load.kind === 'error' && (
        <div role="alert" className="notice notice-error" data-testid={`${id}-error`}>
          <p>{messages.error}</p>
          <p className="muted" data-testid={`${id}-code`}>
            {text.supportCode(load.code)}
          </p>
          <button type="button" className="secondary" data-testid={`${id}-retry`} onClick={onRetry}>
            {text.retry}
          </button>
        </div>
      )}
      {load.kind === 'ready' && props.market === 'PAPER' && (
        <CompactPaper
          block={(props.load as { block: PaperModeBlock }).block}
          announce={props.announce !== false}
        />
      )}
      {load.kind === 'ready' && props.market === 'REAL' && (
        <CompactReal
          block={(props.load as { block: RealModeBlock }).block}
          announce={props.announce !== false}
          realUnavailable={props.realUnavailable}
        />
      )}
      <Link href="/pnl" data-testid={`${id}-open`}>
        {messages.open}
      </Link>
    </section>
  );
}

function CompactPaper({ block, announce }: { block: PaperModeBlock; announce: boolean }) {
  const id = 'pnl-compact-PAPER';
  if (block.empty) {
    return (
      <div data-testid={`${id}-empty`}>
        <p>{text.compact.PAPER.empty}</p>
        <Link href="/bots/new">{text.compact.PAPER.emptyLink}</Link>
      </div>
    );
  }
  return (
    <>
      <p role="note" className="notice" data-testid={`${id}-disclaimer`}>
        {block.disclaimer}
      </p>
      {block.total.status === 'UNKNOWN' && (
        <p role="note" className="notice notice-warning" data-testid={`${id}-partial`}>
          {text.partial}
        </p>
      )}
      <dl className="totals">
        <dt>{text.compact.PAPER.total}</dt>
        <dd
          aria-live={announce ? 'polite' : 'off'}
          aria-atomic="true"
          aria-label={text.compact.PAPER.total}
          data-testid={`${id}-total-region`}
        >
          <TotalValue market="PAPER" amount={block.total} testId={`${id}-total`} />
        </dd>
        <dt>{text.realized}</dt>
        <dd>
          <Usd value={block.realized} testId={`${id}-realized`} />
        </dd>
        <dt>{text.PAPER.fees}</dt>
        <dd>
          <Usd value={block.fees} testId={`${id}-fees`} />
        </dd>
      </dl>
    </>
  );
}

function CompactReal({
  block,
  announce,
  realUnavailable,
}: {
  block: RealModeBlock;
  announce: boolean;
  realUnavailable?: RealEmptyReason;
}) {
  const id = 'pnl-compact-REAL';
  if (block.empty) {
    return (
      <RealEmpty
        testId={`${id}-empty`}
        sentence={text.compact.REAL.empty}
        realUnavailable={realUnavailable ?? { kind: 'unknown' }}
      />
    );
  }
  return (
    <>
      {block.totalLamports.status === 'UNKNOWN' && (
        <p role="note" className="notice notice-warning" data-testid={`${id}-partial`}>
          {text.partial}
        </p>
      )}
      <dl className="totals">
        <dt>{text.compact.REAL.total}</dt>
        <dd
          aria-live={announce ? 'polite' : 'off'}
          aria-atomic="true"
          aria-label={text.compact.REAL.total}
          data-testid={`${id}-total-region`}
        >
          <TotalValue market="REAL" amount={block.totalLamports} testId={`${id}-total`} />
        </dd>
        <dt>{text.realized}</dt>
        <dd>
          <Sol value={block.realizedLamports} testId={`${id}-realized`} />
        </dd>
        <dt>{text.REAL.fees}</dt>
        <dd>
          <Sol value={block.feesLamports} testId={`${id}-fees`} />
        </dd>
      </dl>
    </>
  );
}

/**
 * U3 (BR4.6, BR5.4, BR9.2): the result of ONE bot, as C4 or C6 sends its line: total ("Total do bot, em …", in a
 * polite atomic region), realized, unrealized and fees. `none` is "—": the bot has no line yet. Never a sum of lines.
 */
function BotBlock(props: BotProps) {
  const { market, load } = props;
  const id = props.testId ?? `pnl-bot-${market}`;
  const messages = text.bot[market];
  const totalLabel = props.totalLabel ?? messages.total;
  return (
    <section aria-labelledby={`${id}-title`} className="pnl-mode-block pnl-bot" data-testid={id}>
      <Heading
        level={props.headingLevel ?? 2}
        id={`${id}-title`}
        innerRef={props.headingRef}
        tabIndex={-1}
        data-testid={`${id}-title`}
      >
        {props.heading}{' '}
        {market === 'PAPER' ? (
          <SimulatedBadge testId={`${id}-badge`} />
        ) : (
          <RealBadge testId={`${id}-badge`} />
        )}
      </Heading>
      {props.children}
      {load.kind === 'loading' && (
        <p role="status" aria-busy="true" data-testid={`${id}-loading`}>
          {messages.loading}
        </p>
      )}
      {load.kind === 'error' && (
        <div role="alert" className="notice notice-error" data-testid={`${id}-error`}>
          <p>{text.bot.error}</p>
          <p className="muted" data-testid={`${id}-code`}>
            {text.supportCode(load.code)}
          </p>
          {props.onRetry && (
            <button
              type="button"
              className="secondary"
              data-testid={`${id}-retry`}
              onClick={props.onRetry}
            >
              {text.retry}
            </button>
          )}
        </div>
      )}
      {load.kind === 'none' && (
        <p data-testid={`${id}-none`}>
          <span aria-hidden="true">{text.bot.noLine}</span>{' '}
          <span className="muted">{text.bot.noLineHelp}</span>
        </p>
      )}
      {load.kind === 'ready' && props.market === 'PAPER' && (
        <>
          {props.disclaimer && (
            <p role="note" className="notice" data-testid={`${id}-disclaimer`}>
              {props.disclaimer}
            </p>
          )}
          <BotPaperTotals
            line={(props.load as { block: PaperBotPnlLine }).block}
            id={id}
            totalLabel={totalLabel}
          />
        </>
      )}
      {load.kind === 'ready' && props.market === 'REAL' && (
        <BotRealTotals
          line={(props.load as { block: RealBotPnlLine }).block}
          id={id}
          totalLabel={totalLabel}
        />
      )}
    </section>
  );
}

function BotPaperTotals({
  line,
  id,
  totalLabel,
}: {
  line: PaperBotPnlLine;
  id: string;
  totalLabel: string;
}) {
  return (
    <dl className="totals">
      <dt>{totalLabel}</dt>
      <dd aria-live="polite" aria-atomic="true" aria-label={totalLabel}>
        <TotalValue market="PAPER" amount={line.total} testId={`${id}-total`} />
      </dd>
      <dt>{text.realized}</dt>
      <dd>
        <Usd value={line.realized} testId={`${id}-realized`} />
      </dd>
      <dt>{text.unrealized}</dt>
      <dd>
        <UsdAmount amount={line.unrealized} testId={`${id}-unrealized`} />
      </dd>
      <dt>{text.PAPER.fees}</dt>
      <dd>
        <Usd value={line.fees} testId={`${id}-fees`} />
      </dd>
    </dl>
  );
}

function BotRealTotals({
  line,
  id,
  totalLabel,
}: {
  line: RealBotPnlLine;
  id: string;
  totalLabel: string;
}) {
  return (
    <dl className="totals">
      <dt>{totalLabel}</dt>
      <dd aria-live="polite" aria-atomic="true" aria-label={totalLabel}>
        <TotalValue market="REAL" amount={line.totalLamports} testId={`${id}-total`} />
      </dd>
      <dt>{text.realized}</dt>
      <dd>
        <Sol value={line.realizedLamports} testId={`${id}-realized`} />
      </dd>
      <dt>{text.unrealized}</dt>
      <dd>
        <SolAmount amount={line.unrealizedLamports} testId={`${id}-unrealized`} />
      </dd>
      <dt>{text.REAL.fees}</dt>
      <dd>
        <Sol value={line.feesLamports} testId={`${id}-fees`} />
      </dd>
    </dl>
  );
}
