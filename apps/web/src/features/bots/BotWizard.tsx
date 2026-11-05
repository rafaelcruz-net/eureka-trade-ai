'use client';

import { useParams, useRouter, useSearchParams } from 'next/navigation';
import { useCallback, useEffect, useState } from 'react';
import { useApi } from '@/lib/api/ApiProvider';
import type {
  BotDetail,
  BotTokenSelection,
  StrategyParameter,
  StrategyTemplate,
} from '@/lib/api/guest-api';
import { formatDecimal } from '@/lib/format/format';
import { useSession } from '@/features/auth/SessionProvider';
import { useRequireSession } from '@/features/auth/useRequireSession';
import { BotsShell } from '@/features/bots/BotsShell';
import { Term } from '@/features/bots/Dialog';
import { commandErrorText, fieldErrorsOf, isUnauthenticated } from '@/features/bots/errors';
import { fromForm, LimitFields, toForm, type LimitsForm } from '@/features/bots/LimitFields';
import { botMessages } from '@/features/bots/messages';
import {
  defaultLimits,
  validateBalance,
  validateLimits,
  validateParameters,
  validateSelection,
  type FieldErrors,
  type SelectionDraft,
} from '@/features/bots/validation';

const text = botMessages.wizard;
const FIELDS = ['LIQUIDITY', 'VOLUME_24H', 'HOLDERS', 'AGE_MINUTES', 'RISK_SCORE'];
const GLOSSARY: Record<string, string> = {
  takeProfitPct: botMessages.glossary.takeProfit,
  stopLossPct: botMessages.glossary.stopLoss,
};

type Load =
  | { kind: 'loading' }
  | { kind: 'error' }
  | { kind: 'ready'; bot: BotDetail; template: StrategyTemplate };

/**
 * S4, the wizard in 4 steps (F1; US4.1 to US4.3; BR1.1 to BR1.7, BR6.4): each accepted step is saved on the server
 * and the draft reopens at the last saved step on another computer; "Voltar" sends nothing and keeps the data; step 1
 * without a token nor a rule cannot continue and says why; the limits default to the proportions of the balance until
 * edited; the start shows "Iniciando bot PAPER" and accepts no second click. Keyboard only, visible focus.
 */
export function BotWizard() {
  const params = useParams<{ botId: string }>();
  const botId = params.botId;
  const status = useRequireSession(`/bots/new/${botId}`);
  const api = useApi();
  const router = useRouter();
  const scanQuery = useSearchParams().toString();
  const { expire } = useSession();
  const [load, setLoad] = useState<Load>({ kind: 'loading' });
  const [step, setStep] = useState(1);
  const [parameters, setParameters] = useState<Record<string, string>>({});
  const [selection, setSelection] = useState<SelectionDraft>({ tokens: [], rules: [] });
  const [balance, setBalance] = useState('1000.000000');
  const [limits, setLimits] = useState<LimitsForm>(toForm(defaultLimits('1000.000000')));
  const [touched, setTouched] = useState<Set<string>>(new Set());
  const [errors, setErrors] = useState<FieldErrors>({});
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);

  const fetchDraft = useCallback(async () => {
    setLoad({ kind: 'loading' });
    try {
      const [bot, templates] = await Promise.all([api.getBot(botId), api.listStrategyTemplates()]);
      const template = templates.find((candidate) => candidate.templateId === bot.templateId);
      if (!template) {
        setLoad({ kind: 'error' });
        return;
      }
      setParameters({ ...bot.parameters });
      const scanned = scannerToken(new URLSearchParams(scanQuery));
      const tokens = bot.tokenSelection.tokens ?? [];
      setSelection({
        tokens:
          scanned && !tokens.some((t) => t.mint === scanned.mint) ? [...tokens, scanned] : tokens,
        rules: bot.tokenSelection.rules ?? [],
      });
      if (scanned) {
        setNotice(text.fromScanner);
      }
      if (bot.virtualBalance) {
        setBalance(bot.virtualBalance);
      }
      if (bot.limits) {
        setLimits(toForm(bot.limits));
        setTouched(new Set(Object.keys(bot.limits)));
      }
      setStep(
        bot.state === 'VALIDATED'
          ? Math.max(bot.draftStep, 4)
          : Math.min(Math.max(bot.draftStep, 1), 3),
      );
      setLoad({ kind: 'ready', bot, template });
    } catch (failure) {
      if (isUnauthenticated(failure)) {
        expire();
        return;
      }
      setLoad({ kind: 'error' });
    }
  }, [api, botId, expire, scanQuery]);

  useEffect(() => {
    if (status === 'authenticated') {
      void fetchDraft();
    }
  }, [status, fetchDraft]);

  function changeBalance(value: string) {
    setBalance(value);
    if (Object.keys(validateBalance(value)).length === 0) {
      const proportional = toForm(defaultLimits(value));
      setLimits((current) => {
        const next = { ...current };
        for (const field of Object.keys(proportional) as (keyof LimitsForm)[]) {
          if (!touched.has(field)) {
            next[field] = proportional[field];
          }
        }
        return next;
      });
    }
  }

  async function save(
    nextStep: number,
    body: Parameters<typeof api.saveBotDraft>[1],
    local: FieldErrors,
  ) {
    if (Object.keys(local).length > 0) {
      setErrors(local);
      return;
    }
    setBusy(true);
    setErrors({});
    setNotice(null);
    try {
      await api.saveBotDraft(botId, body);
      setStep(nextStep);
    } catch (failure) {
      if (isUnauthenticated(failure)) {
        expire();
        return;
      }
      const fields = fieldErrorsOf(failure);
      setErrors(fields);
      if (Object.keys(fields).length === 0) {
        setNotice(text.saveError);
      }
    } finally {
      setBusy(false);
    }
  }

  async function start() {
    setBusy(true);
    setNotice(null);
    try {
      await api.runBotCommand(botId, 'start');
      router.push(`/bots/${botId}`);
    } catch (failure) {
      if (isUnauthenticated(failure)) {
        expire();
        return;
      }
      const sentence = commandErrorText(failure);
      setNotice(
        sentence === botMessages.detail.commandError ? text.startError : startText(sentence),
      );
      setBusy(false);
    }
  }

  if (load.kind !== 'ready') {
    return (
      <BotsShell title={text.title} testId="wizard">
        {load.kind === 'loading' ? (
          <p role="status" data-testid="wizard-loading">
            {text.loading}
          </p>
        ) : (
          <div role="alert" className="notice notice-error" data-testid="wizard-error">
            <p>{text.loadError}</p>
            <button
              type="button"
              className="secondary"
              data-testid="wizard-retry"
              onClick={() => void fetchDraft()}
            >
              {botMessages.retry}
            </button>
          </div>
        )}
      </BotsShell>
    );
  }

  const { template } = load;
  const selectionEmpty = selection.tokens.length === 0 && selection.rules.length === 0;
  return (
    <BotsShell title={text.title} testId="wizard">
      <p data-testid="wizard-step">{text.stepIndicator(step, text.steps[step - 1] as string)}</p>
      <ol className="wizard-steps" aria-label={text.title}>
        {text.steps.map((name, index) => (
          <li key={name} aria-current={index + 1 === step ? 'step' : undefined}>
            {index + 1}. {name}
          </li>
        ))}
      </ol>
      {notice && (
        <p role="alert" className="notice" data-testid="wizard-notice">
          {notice}
        </p>
      )}
      {step === 1 && (
        <section data-testid="wizard-step-1">
          <h2>{template.name}</h2>
          <p className="muted">{template.description}</p>
          <fieldset>
            <legend>{text.parameters}</legend>
            {template.parameters.map((spec) => (
              <ParameterField
                key={spec.key}
                spec={spec}
                value={parameters[spec.key] ?? ''}
                error={errors[`parameters.${spec.key}`]}
                onChange={(value) => setParameters({ ...parameters, [spec.key]: value })}
              />
            ))}
          </fieldset>
          <SelectionEditor selection={selection} errors={errors} onChange={setSelection} />
          {selectionEmpty && (
            <p className="muted" data-testid="wizard-next-reason">
              {text.nextUnavailable}
            </p>
          )}
          <button
            type="button"
            disabled={busy || selectionEmpty}
            data-testid="wizard-next-1"
            onClick={() =>
              void save(
                2,
                { step: 1, parameters, tokenSelection: selection as BotTokenSelection },
                {
                  ...validateParameters(template.parameters, parameters),
                  ...validateSelection(selection),
                },
              )
            }
          >
            {busy ? text.saving : text.next}
          </button>
        </section>
      )}
      {step === 2 && (
        <section data-testid="wizard-step-2">
          <label htmlFor="wizard-balance">{text.balance} (USDC)</label>
          <input
            id="wizard-balance"
            inputMode="decimal"
            value={balance}
            aria-describedby={`wizard-balance-help${errors.virtualBalance ? ' wizard-balance-error' : ''}`}
            data-testid="wizard-balance"
            onChange={(event) => changeBalance(event.target.value)}
          />
          <p id="wizard-balance-help" className="muted">
            {text.balanceHelp('10.000')}
          </p>
          {errors.virtualBalance && (
            <p id="wizard-balance-error" className="field-error" data-testid="wizard-balance-error">
              {errors.virtualBalance}
            </p>
          )}
          <LimitFields
            form={limits}
            defaults={
              Object.keys(validateBalance(balance)).length === 0
                ? toForm(defaultLimits(balance))
                : undefined
            }
            errors={errors}
            prefix="limits."
            testId="wizard-limits"
            onChange={(field, value) => {
              setLimits({ ...limits, [field]: value });
              setTouched(new Set(touched).add(field));
            }}
          />
          <button
            type="button"
            className="secondary"
            data-testid="wizard-back-2"
            onClick={() => setStep(1)}
          >
            {text.back}
          </button>
          <button
            type="button"
            disabled={busy}
            data-testid="wizard-next-2"
            onClick={() =>
              void save(
                3,
                { step: 2, virtualBalance: balance, limits: fromForm(limits) },
                { ...validateBalance(balance), ...prefixed(validateLimits(fromForm(limits))) },
              )
            }
          >
            {busy ? text.saving : text.next}
          </button>
        </section>
      )}
      {step === 3 && (
        <section data-testid="wizard-step-3">
          <h2>{text.review}</h2>
          <dl className="hypotheses">
            <dt>{botMessages.create_.strategy}</dt>
            <dd>{template.name}</dd>
            {template.parameters.map((spec) => (
              <ReviewLine
                key={spec.key}
                label={`${spec.label} (${spec.unit})`}
                value={parameters[spec.key] ?? ''}
              />
            ))}
            <ReviewLine label={`${text.balance} (USDC)`} value={balance} />
            {(Object.keys(limits) as (keyof LimitsForm)[]).map((field) => (
              <ReviewLine
                key={field}
                label={`${(botMessages.limitFields[field] as { label: string }).label}`}
                value={limits[field]}
              />
            ))}
          </dl>
          <p data-testid="wizard-hypotheses">{text.hypotheses}</p>
          <button
            type="button"
            className="secondary"
            data-testid="wizard-back-3"
            onClick={() => setStep(2)}
          >
            {text.back}
          </button>
          <button
            type="button"
            disabled={busy}
            data-testid="wizard-next-3"
            onClick={() => void save(4, { step: 3 }, {})}
          >
            {busy ? text.saving : text.next}
          </button>
        </section>
      )}
      {step === 4 && (
        <section data-testid="wizard-step-4">
          <p>{text.hypotheses}</p>
          <button
            type="button"
            className="secondary"
            data-testid="wizard-back-4"
            onClick={() => setStep(3)}
          >
            {text.back}
          </button>
          <button
            type="button"
            disabled={busy}
            data-testid="wizard-start"
            onClick={() => void start()}
          >
            {busy ? text.starting : text.start}
          </button>
        </section>
      )}
    </BotsShell>
  );
}

function startText(sentence: string): string {
  if (sentence === botMessages.detail.resumeLimit) {
    return text.startLimit;
  }
  if (sentence === botMessages.detail.resumeSystem) {
    return text.startSystem;
  }
  return sentence;
}

function prefixed(errors: FieldErrors): FieldErrors {
  const result: FieldErrors = {};
  for (const [field, message] of Object.entries(errors)) {
    result[`limits.${field}`] = message;
  }
  return result;
}

function scannerToken(search: URLSearchParams): { chain: string; mint: string } | null {
  const mint = search.get('mint');
  return mint ? { chain: search.get('chain') ?? 'solana', mint } : null;
}

function ReviewLine({ label, value }: { label: string; value: string }) {
  return (
    <>
      <dt>{label}</dt>
      <dd>{/^-?\d+(\.\d+)?$/.test(value) ? formatDecimal(value) : value}</dd>
    </>
  );
}

/** A parameter with its label, unit, default, range and step; the error in text next to it (AC4.1.2). */
function ParameterField({
  spec,
  value,
  error,
  onChange,
}: {
  spec: StrategyParameter;
  value: string;
  error?: string;
  onChange: (value: string) => void;
}) {
  const id = `wizard-param-${spec.key}`;
  return (
    <div className="field">
      <label htmlFor={id}>
        {GLOSSARY[spec.key] ? (
          <Term
            label={spec.label}
            explanation={GLOSSARY[spec.key] as string}
            testId={`${id}-term`}
          />
        ) : (
          spec.label
        )}{' '}
        ({spec.unit})
      </label>
      <input
        id={id}
        inputMode="decimal"
        value={value}
        aria-invalid={error ? true : undefined}
        aria-describedby={`${id}-help${error ? ` ${id}-error` : ''}`}
        data-testid={id}
        onChange={(event) => onChange(event.target.value)}
      />
      <p id={`${id}-help`} className="muted">
        {text.range(spec.min, spec.max, spec.step, spec.unit)}. {text.defaultValue(spec.default)}
      </p>
      {error && (
        <p id={`${id}-error`} className="field-error" data-testid={`${id}-error`}>
          {error}
        </p>
      )}
    </div>
  );
}

/** The explicit tokens (up to 10) and the scanner rules (up to 5) of the selection (BR1.3). */
function SelectionEditor({
  selection,
  errors,
  onChange,
}: {
  selection: SelectionDraft;
  errors: FieldErrors;
  onChange: (selection: SelectionDraft) => void;
}) {
  const [mint, setMint] = useState('');
  return (
    <fieldset data-testid="wizard-selection">
      <legend>{text.selection}</legend>
      <p className="muted">{text.selectionHelp}</p>
      {errors.tokenSelection && (
        <p className="field-error" data-testid="wizard-selection-error">
          {errors.tokenSelection}
        </p>
      )}
      <ul>
        {selection.tokens.map((token, index) => (
          <li key={token.mint}>
            <span data-testid={`wizard-token-${index}`}>{token.mint}</span>{' '}
            <button
              type="button"
              className="secondary"
              data-testid={`wizard-token-remove-${index}`}
              onClick={() =>
                onChange({ ...selection, tokens: selection.tokens.filter((_, i) => i !== index) })
              }
            >
              {text.removeToken}
            </button>
            {errors[`tokenSelection.tokens[${index}].mint`] && (
              <span className="field-error"> {errors[`tokenSelection.tokens[${index}].mint`]}</span>
            )}
          </li>
        ))}
      </ul>
      <label htmlFor="wizard-mint">{text.tokenMint}</label>
      <input
        id="wizard-mint"
        value={mint}
        data-testid="wizard-mint"
        onChange={(event) => setMint(event.target.value)}
      />
      <button
        type="button"
        className="secondary"
        data-testid="wizard-add-token"
        disabled={mint.trim().length === 0 || selection.tokens.length >= 10}
        onClick={() => {
          onChange({
            ...selection,
            tokens: [...selection.tokens, { chain: 'solana', mint: mint.trim() }],
          });
          setMint('');
        }}
      >
        {text.addToken}
      </button>
      {selection.rules.map((rule, index) => (
        <div key={index} className="field" data-testid={`wizard-rule-${index}`}>
          <label htmlFor={`wizard-rule-field-${index}`}>{text.ruleField}</label>
          <select
            id={`wizard-rule-field-${index}`}
            value={rule.field}
            data-testid={`wizard-rule-field-${index}`}
            onChange={(event) =>
              onChange(updateRule(selection, index, { field: event.target.value }))
            }
          >
            {FIELDS.map((field) => (
              <option key={field} value={field}>
                {text.ruleFields[field]}
              </option>
            ))}
          </select>
          <label htmlFor={`wizard-rule-operator-${index}`}>{text.ruleOperator}</label>
          <select
            id={`wizard-rule-operator-${index}`}
            value={rule.operator}
            data-testid={`wizard-rule-operator-${index}`}
            onChange={(event) =>
              onChange(updateRule(selection, index, { operator: event.target.value }))
            }
          >
            <option value="GTE">{text.operators.GTE}</option>
            <option value="LTE">{text.operators.LTE}</option>
          </select>
          <label htmlFor={`wizard-rule-value-${index}`}>{text.ruleValue}</label>
          <input
            id={`wizard-rule-value-${index}`}
            inputMode="decimal"
            value={rule.value}
            data-testid={`wizard-rule-value-${index}`}
            onChange={(event) =>
              onChange(updateRule(selection, index, { value: event.target.value }))
            }
          />
          {errors[`tokenSelection.rules[${index}].value`] && (
            <span className="field-error">{errors[`tokenSelection.rules[${index}].value`]}</span>
          )}
          <button
            type="button"
            className="secondary"
            data-testid={`wizard-rule-remove-${index}`}
            onClick={() =>
              onChange({ ...selection, rules: selection.rules.filter((_, i) => i !== index) })
            }
          >
            {text.removeRule}
          </button>
        </div>
      ))}
      <button
        type="button"
        className="secondary"
        data-testid="wizard-add-rule"
        disabled={selection.rules.length >= 5}
        onClick={() =>
          onChange({
            ...selection,
            rules: [...selection.rules, { field: 'LIQUIDITY', operator: 'GTE', value: '10000' }],
          })
        }
      >
        {text.addRule}
      </button>
    </fieldset>
  );
}

function updateRule(
  selection: SelectionDraft,
  index: number,
  change: Partial<SelectionDraft['rules'][number]>,
): SelectionDraft {
  return {
    ...selection,
    rules: selection.rules.map((rule, i) => (i === index ? { ...rule, ...change } : rule)),
  };
}
