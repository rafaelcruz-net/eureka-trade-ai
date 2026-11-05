'use client';

import Link from 'next/link';
import { useParams } from 'next/navigation';
import { useCallback, useEffect, useRef, useState } from 'react';
import { ApiError } from '@/lib/api/client';
import { useApi } from '@/lib/api/ApiProvider';
import type {
  BotCommand,
  BotDetail as BotDetailData,
  BotLimits,
  BotState,
  DayUsage,
  Decision,
  OrderRejection,
  PauseReason,
  PnlTokenLine,
} from '@/lib/api/guest-api';
import { abbreviateMint, formatDecimal, formatInstant } from '@/lib/format/format';
import { messages } from '@/lib/i18n/messages';
import { useSession } from '@/features/auth/SessionProvider';
import { useRequireSession } from '@/features/auth/useRequireSession';
import { BotTradesTable } from '@/features/pnl/BotTradesTable';
import { BotsShell } from '@/features/bots/BotsShell';
import { compareDecimal } from '@/features/bots/decimal';
import { Dialog } from '@/features/bots/Dialog';
import { commandErrorText, fieldErrorsOf, isUnauthenticated } from '@/features/bots/errors';
import { fromForm, LimitFields, toForm, type LimitsForm } from '@/features/bots/LimitFields';
import { botMessages } from '@/features/bots/messages';
import { reasonsText, resumesAtText, stateText, validCommands } from '@/features/bots/states';
import { validateLimits, type FieldErrors } from '@/features/bots/validation';

const text = botMessages.detail;

type Load =
  | { kind: 'loading' }
  | { kind: 'error' }
  | { kind: 'not-found' }
  | { kind: 'ready'; bot: BotDetailData };

function browserTimeZone(): string {
  return Intl.DateTimeFormat().resolvedOptions().timeZone;
}

/**
 * S5, the bot screen (US4.4, US4.5, US4.10; BR6.1 to BR6.4): state and every pause reason in text, the restart time in
 * the person's time zone, only the valid commands, the limits in use with their version, the use of the UTC day, the
 * operations or "Nenhuma operação simulada até agora", the refused orders and the explained decisions. A command
 * answer is the confirmed state; on failure the screen keeps the last confirmed state and says so.
 */
export function BotDetail({ timeZone, now }: { timeZone?: string; now?: () => Date } = {}) {
  const zone = timeZone ?? browserTimeZone();
  const clock = now ?? (() => new Date());
  const params = useParams<{ botId: string }>();
  const botId = params.botId;
  const status = useRequireSession(`/bots/${botId}`);
  const api = useApi();
  const { expire } = useSession();
  const [load, setLoad] = useState<Load>({ kind: 'loading' });
  const [positions, setPositions] = useState<PnlTokenLine[]>([]);
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);
  const [dialog, setDialog] = useState<'stop' | 'limits' | null>(null);
  const [revision, setRevision] = useState(0);
  const stopButton = useRef<HTMLButtonElement>(null);
  const limitsButton = useRef<HTMLButtonElement>(null);

  const fetchBot = useCallback(async () => {
    try {
      const bot = await api.getBot(botId);
      setLoad({ kind: 'ready', bot });
      try {
        const report = await api.getPnl({
          from: bot.activatedAt ?? bot.updatedAt,
          to: new Date(clock().getTime() + 60_000).toISOString(),
          botId,
        });
        setPositions(report.byToken.filter(isOpen));
      } catch {
        setPositions([]);
      }
    } catch (failure) {
      if (isUnauthenticated(failure)) {
        expire();
        return;
      }
      setLoad(
        failure instanceof ApiError && failure.status === 404
          ? { kind: 'not-found' }
          : { kind: 'error' },
      );
    }
    // The clock is a test seam; the screen reloads on the bot and the session only.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [api, botId, expire]);

  useEffect(() => {
    if (status === 'authenticated') {
      void fetchBot();
    }
  }, [status, fetchBot]);

  async function run(command: BotCommand) {
    setBusy(true);
    setNotice(null);
    setDialog(null);
    try {
      await api.runBotCommand(botId, command);
    } catch (failure) {
      if (isUnauthenticated(failure)) {
        expire();
        return;
      }
      setNotice(commandErrorText(failure));
    }
    await fetchBot();
    setRevision((value) => value + 1);
    setBusy(false);
  }

  if (load.kind !== 'ready') {
    return (
      <BotsShell title={botMessages.listTitle} testId="bot">
        {load.kind === 'loading' && (
          <p role="status" data-testid="bot-loading">
            {text.loading}
          </p>
        )}
        {load.kind === 'not-found' && (
          <p role="alert" className="notice" data-testid="bot-not-found">
            {text.notFound} <Link href="/bots">{botMessages.listTitle}</Link>
          </p>
        )}
        {load.kind === 'error' && (
          <div role="alert" className="notice notice-error" data-testid="bot-error">
            <p>{text.error}</p>
            <button
              type="button"
              className="secondary"
              data-testid="bot-retry"
              onClick={() => void fetchBot()}
            >
              {botMessages.retry}
            </button>
          </div>
        )}
      </BotsShell>
    );
  }

  const { bot } = load;
  const state = bot.state as BotState;
  const reasons = bot.pauseReasons as PauseReason[];
  const restart = resumesAtText(bot.resumesAt, zone);
  const commands = validCommands(state).filter((command) => command !== 'start');
  const limitsEditable = state === 'ACTIVE' || state === 'PAUSED';
  return (
    <BotsShell title={bot.name} testId="bot">
      <section aria-label={text.state} data-testid="bot-header">
        <p>
          {text.state}: <strong data-testid="bot-state">{stateText(state)}</strong>
          {reasons.length > 0 && (
            <span data-testid="bot-reasons">: {reasonsText(reasons).join('; ')}</span>
          )}
          {restart && <span data-testid="bot-restart"> ({restart})</span>}
        </p>
        {state === 'VALIDATED' && (
          <p>
            <Link href={`/bots/new/${bot.botId}`} data-testid="bot-open-wizard">
              {botMessages.resumeDraft}
            </Link>
          </p>
        )}
      </section>
      {notice && (
        <p role="alert" className="notice notice-error" data-testid="bot-notice">
          {notice}
        </p>
      )}
      {state === 'PAUSED' && positions.length > 0 && (
        <p className="notice" data-testid="bot-paused-positions">
          {text.pausedPositions}
        </p>
      )}
      <div data-testid="bot-controls">
        {commands.map((command) => (
          <button
            key={command}
            ref={command === 'stop' ? stopButton : undefined}
            type="button"
            className={command === 'stop' ? 'secondary' : undefined}
            disabled={busy}
            data-testid={`bot-${command}`}
            onClick={() => (command === 'stop' ? setDialog('stop') : void run(command))}
          >
            {busy ? text.working : commandLabel(command)}
          </button>
        ))}
        {limitsEditable && (
          <button
            ref={limitsButton}
            type="button"
            className="secondary"
            disabled={busy}
            data-testid="bot-edit-limits"
            onClick={() => setDialog('limits')}
          >
            {text.editLimits}
          </button>
        )}
      </div>
      {bot.limits && <LimitsInUse limits={bot.limits} version={bot.limitsVersion} />}
      {state !== 'DRAFT' && state !== 'VALIDATED' && (
        <DayUsagePanel key={`usage-${revision}`} botId={botId} limits={bot.limits} />
      )}
      <StaleDataNotice key={`stale-${revision}`} botId={botId} timeZone={zone} />
      <TradesPanel key={`trades-${revision}`} bot={bot} timeZone={zone} />
      <RejectionsPanel botId={botId} timeZone={zone} />
      <DecisionsList key={`decisions-${revision}`} botId={botId} timeZone={zone} />
      {dialog === 'stop' && (
        <StopConfirmDialog
          positions={positions}
          returnFocusTo={stopButton.current}
          onCancel={() => setDialog(null)}
          onConfirm={() => void run('stop')}
        />
      )}
      {dialog === 'limits' && bot.limits && (
        <LimitsEditDialog
          botId={botId}
          limits={bot.limits}
          returnFocusTo={limitsButton.current}
          onCancel={() => setDialog(null)}
          onSaved={() => {
            setDialog(null);
            void fetchBot().then(() => setRevision((value) => value + 1));
          }}
        />
      )}
    </BotsShell>
  );
}

function commandLabel(command: BotCommand): string {
  switch (command) {
    case 'pause':
      return text.pause;
    case 'resume':
      return text.resume;
    case 'stop':
      return text.stop;
    default:
      return botMessages.wizard.start;
  }
}

/** A token line of the P&L that still has a position: an unknown or a non-zero unrealized result. */
function isOpen(line: PnlTokenLine): boolean {
  return line.unrealized.status === 'UNKNOWN' || compareDecimal(line.unrealized.value, '0') !== 0;
}

function LimitsInUse({ limits, version }: { limits: BotLimits; version?: number }) {
  return (
    <section aria-labelledby="bot-limits-title" data-testid="bot-limits">
      <h2 id="bot-limits-title">{text.limits}</h2>
      {version !== undefined && <p className="muted">{text.limitsVersion(version)}</p>}
      <dl>
        {(Object.keys(limits) as (keyof BotLimits)[]).map((field) => {
          const spec = botMessages.limitFields[field] as { label: string; unit: string };
          const value = limits[field];
          return (
            <div key={field}>
              <dt>{spec.label}</dt>
              <dd data-testid={`bot-limit-${field}`}>
                {typeof value === 'string' ? formatDecimal(value) : value} {spec.unit}
              </dd>
            </div>
          );
        })}
      </dl>
    </section>
  );
}

/** Exposure and loss of the UTC day against the limits (AC4.4.2); before the start there is no use to show. */
function DayUsagePanel({ botId, limits }: { botId: string; limits?: BotLimits }) {
  const api = useApi();
  const [usage, setUsage] = useState<DayUsage | null | 'unavailable'>(null);

  useEffect(() => {
    api
      .getDayUsage(botId)
      .then(setUsage)
      .catch(() => setUsage('unavailable'));
  }, [api, botId]);

  return (
    <section aria-labelledby="bot-usage-title" data-testid="bot-day-usage">
      <h2 id="bot-usage-title">{text.dayUsage}</h2>
      {usage === 'unavailable' && (
        <p data-testid="bot-day-usage-unavailable">{text.dayUsageUnavailable}</p>
      )}
      {usage && usage !== 'unavailable' && limits && (
        <>
          <p data-testid="bot-day-exposure">
            {text.exposure(formatDecimal(usage.exposure), formatDecimal(limits.maxTotalExposure))}
          </p>
          <p data-testid="bot-day-loss">
            {text.dayLoss(formatDecimal(usage.dayLoss), formatDecimal(limits.maxDailyLoss))}
          </p>
        </>
      )}
    </section>
  );
}

/** The last decision did not trade because the market data is stale: the screen says so, with the time (BR9.1). */
function StaleDataNotice({ botId, timeZone }: { botId: string; timeZone: string }) {
  const api = useApi();
  const [stale, setStale] = useState<Decision | null>(null);

  useEffect(() => {
    api
      .listBotDecisions(botId, { limit: 1 })
      .then((page) => {
        const last = page.items[0];
        setStale(last && last.noTradeReason === 'STALE_DATA' ? last : null);
      })
      .catch(() => setStale(null));
  }, [api, botId]);

  if (!stale) {
    return null;
  }
  const observed = stale.inputs
    .map((input) => input.observedAt)
    .filter((value): value is string => typeof value === 'string')
    .sort()
    .pop();
  return (
    <p role="status" className="notice" data-testid="bot-stale">
      {observed ? text.stale(formatInstant(observed, timeZone)) : text.staleUnknownTime}
    </p>
  );
}

/** The operations of the bot, or the empty sentence with the rules in use (AC4.10.4). */
function TradesPanel({ bot, timeZone }: { bot: BotDetailData; timeZone: string }) {
  const api = useApi();
  const [any, setAny] = useState<boolean | null>(null);

  useEffect(() => {
    api
      .listBotTrades(bot.botId, { limit: 1 })
      .then((page) => setAny(page.items.length > 0))
      .catch(() => setAny(true));
  }, [api, bot.botId]);

  if (any === null) {
    return null;
  }
  if (any) {
    return <BotTradesTable botId={bot.botId} timeZone={timeZone} />;
  }
  const tokens = bot.tokenSelection.tokens ?? [];
  const rules = bot.tokenSelection.rules ?? [];
  return (
    <section data-testid="bot-no-trades">
      <p>{text.noTrades}</p>
      <h2>{text.rulesInUse}</h2>
      <ul data-testid="bot-rules">
        {tokens.map((token) => (
          <li key={token.mint}>{abbreviateMint(token.mint)}</li>
        ))}
        {rules.map((rule, index) => (
          <li key={index}>
            {botMessages.wizard.ruleFields[rule.field]}{' '}
            {botMessages.wizard.operators[rule.operator]} {formatDecimal(rule.value)}
          </li>
        ))}
        {Object.entries(bot.parameters).map(([key, value]) => (
          <li key={key}>
            {key}: {formatDecimal(value)}
          </li>
        ))}
      </ul>
    </section>
  );
}

/**
 * The refused orders of the bot (C21 of U6), opened on demand: a 404 means no rejection of this person and shows
 * "Nenhuma recusa."; DUPLICATE shows "decisão já avaliada".
 */
function RejectionsPanel({ botId, timeZone }: { botId: string; timeZone: string }) {
  const api = useApi();
  const [state, setState] = useState<
    | { kind: 'closed' }
    | { kind: 'loading' }
    | { kind: 'error' }
    | { kind: 'ready'; items: OrderRejection[] }
  >({ kind: 'closed' });

  async function open() {
    setState({ kind: 'loading' });
    try {
      const page = await api.listBotRejections(botId, { limit: 20 });
      setState({ kind: 'ready', items: page.items });
    } catch (failure) {
      setState(
        failure instanceof ApiError && failure.status === 404
          ? { kind: 'ready', items: [] }
          : { kind: 'error' },
      );
    }
  }

  return (
    <section aria-labelledby="bot-rejections-title" data-testid="bot-rejections">
      <h2 id="bot-rejections-title">{text.rejections}</h2>
      {state.kind === 'closed' && (
        <button
          type="button"
          className="secondary"
          data-testid="bot-rejections-open"
          onClick={() => void open()}
        >
          {text.showRejections}
        </button>
      )}
      {state.kind === 'loading' && <p role="status">{botMessages.loading}</p>}
      {state.kind === 'error' && (
        <p role="alert" className="notice notice-error" data-testid="bot-rejections-error">
          {text.rejectionsError}
        </p>
      )}
      {state.kind === 'ready' && state.items.length === 0 && (
        <p data-testid="bot-rejections-empty">{text.noRejections}</p>
      )}
      {state.kind === 'ready' && state.items.length > 0 && (
        <ul data-testid="bot-rejections-list">
          {state.items.map((rejection) => (
            <li key={rejection.decisionId} data-testid={`bot-rejection-${rejection.decisionId}`}>
              {formatInstant(rejection.evaluatedAt, timeZone)}:{' '}
              {rejection.reasons
                .map((reason) => text.rejectionReasons[reason] ?? reason)
                .join('; ')}
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}

/** The explained decisions of the bot, newest first, with "Carregar mais" (US4.5). */
function DecisionsList({ botId, timeZone }: { botId: string; timeZone: string }) {
  const api = useApi();
  const [items, setItems] = useState<Decision[]>([]);
  const [cursor, setCursor] = useState<string | null>(null);
  const [state, setState] = useState<'loading' | 'error' | 'ready'>('loading');

  const fetchPage = useCallback(
    async (after: string | null) => {
      setState('loading');
      try {
        const page = await api.listBotDecisions(botId, { cursor: after, limit: 20 });
        setItems((current) => (after ? [...current, ...page.items] : page.items));
        setCursor(page.nextCursor);
        setState('ready');
      } catch {
        setState('error');
      }
    },
    [api, botId],
  );

  useEffect(() => {
    void fetchPage(null);
  }, [fetchPage]);

  return (
    <section aria-labelledby="bot-decisions-title" data-testid="bot-decisions">
      <h2 id="bot-decisions-title">{text.decisions}</h2>
      {state === 'error' && (
        <p role="alert" className="notice notice-error" data-testid="bot-decisions-error">
          {text.decisionsError}
        </p>
      )}
      {state === 'ready' && items.length === 0 && (
        <p data-testid="bot-decisions-empty">{text.noDecisions}</p>
      )}
      <ol data-testid="bot-decisions-list">
        {items.map((decision) => (
          <li key={decision.decisionId} data-testid={`bot-decision-${decision.decisionId}`}>
            <p>
              <strong>{text.actions[decision.action] ?? decision.action}</strong> —{' '}
              {formatInstant(decision.createdAt, timeZone)}
              {decision.tokenMint && <> — {abbreviateMint(decision.tokenMint)}</>}
            </p>
            <p>{decision.summary}</p>
            {decision.factors.length > 0 && (
              <ul>
                {decision.factors.map((factor, index) => (
                  <li key={`${factor.name}-${index}`}>
                    {factor.label ?? factor.name}:{' '}
                    {factor.value.status === 'KNOWN' && factor.value.value !== undefined
                      ? formatDecimal(factor.value.value)
                      : messages.pnl.unknown}
                    {factor.threshold !== undefined && ` (${formatDecimal(factor.threshold)})`}{' '}
                    <span data-testid={`bot-factor-${decision.decisionId}-${index}`}>
                      {factor.satisfied ? `✓ ${text.satisfied}` : `✗ ${text.notSatisfied}`}
                    </span>
                  </li>
                ))}
              </ul>
            )}
          </li>
        ))}
      </ol>
      {state === 'ready' && cursor && (
        <button
          type="button"
          className="secondary"
          data-testid="bot-decisions-more"
          onClick={() => void fetchPage(cursor)}
        >
          {text.loadMore}
        </button>
      )}
    </section>
  );
}

/** The confirmation of "Encerrar" with the open positions (AC4.3.4); Esc or "Cancelar" changes nothing. */
function StopConfirmDialog({
  positions,
  returnFocusTo,
  onCancel,
  onConfirm,
}: {
  positions: PnlTokenLine[];
  returnFocusTo: HTMLElement | null;
  onCancel: () => void;
  onConfirm: () => void;
}) {
  const dialogText = botMessages.stopDialog;
  return (
    <Dialog
      title={dialogText.title}
      testId="bot-stop-dialog"
      onClose={onCancel}
      returnFocusTo={returnFocusTo}
    >
      <p>{dialogText.intro}</p>
      {positions.length === 0 ? (
        <p data-testid="bot-stop-no-positions">{dialogText.noPositions}</p>
      ) : (
        <ul data-testid="bot-stop-positions">
          {positions.map((line) => (
            <li key={line.mint}>{abbreviateMint(line.mint)}</li>
          ))}
        </ul>
      )}
      <button type="button" className="secondary" data-testid="bot-stop-cancel" onClick={onCancel}>
        {dialogText.cancel}
      </button>
      <button type="button" data-testid="bot-stop-confirm" onClick={onConfirm}>
        {dialogText.confirm}
      </button>
    </Dialog>
  );
}

/** A new version of the limits (BR5.1): validated here, repeated by the server; the errors stay next to the fields. */
function LimitsEditDialog({
  botId,
  limits,
  returnFocusTo,
  onCancel,
  onSaved,
}: {
  botId: string;
  limits: BotLimits;
  returnFocusTo: HTMLElement | null;
  onCancel: () => void;
  onSaved: () => void;
}) {
  const api = useApi();
  const dialogText = botMessages.limitsDialog;
  const [form, setForm] = useState<LimitsForm>(toForm(limits));
  const [errors, setErrors] = useState<FieldErrors>({});
  const [notice, setNotice] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  async function save() {
    const body = fromForm(form);
    const local = validateLimits(body);
    if (Object.keys(local).length > 0) {
      setErrors(local);
      return;
    }
    setSaving(true);
    setErrors({});
    setNotice(null);
    try {
      await api.changeBotLimits(botId, body);
      onSaved();
    } catch (failure) {
      const fields = fieldErrorsOf(failure);
      setErrors(fields);
      if (Object.keys(fields).length === 0) {
        setNotice(commandErrorText(failure));
      }
      setSaving(false);
    }
  }

  return (
    <Dialog
      title={dialogText.title}
      testId="bot-limits-dialog"
      onClose={onCancel}
      returnFocusTo={returnFocusTo}
    >
      <p>{dialogText.intro}</p>
      {notice && (
        <p role="alert" className="notice notice-error" data-testid="bot-limits-notice">
          {notice}
        </p>
      )}
      <LimitFields
        form={form}
        errors={errors}
        testId="bot-limits-form"
        onChange={(field, value) => setForm({ ...form, [field]: value })}
      />
      <button
        type="button"
        className="secondary"
        data-testid="bot-limits-cancel"
        onClick={onCancel}
      >
        {dialogText.cancel}
      </button>
      <button
        type="button"
        disabled={saving}
        data-testid="bot-limits-save"
        onClick={() => void save()}
      >
        {saving ? botMessages.wizard.saving : dialogText.confirm}
      </button>
    </Dialog>
  );
}
