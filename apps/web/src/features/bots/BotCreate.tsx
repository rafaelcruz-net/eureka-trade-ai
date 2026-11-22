'use client';

import { useRouter, useSearchParams } from 'next/navigation';
import { useCallback, useEffect, useRef, useState, type FormEvent } from 'react';
import { useApi } from '@/lib/api/ApiProvider';
import { ApiError } from '@/lib/api/client';
import type { BotMarket, RealEligibility, StrategyTemplate } from '@/lib/api/guest-api';
import { useSession } from '@/features/auth/SessionProvider';
import { useRequireSession } from '@/features/auth/useRequireSession';
import { BotsShell } from '@/features/bots/BotsShell';
import { isUnauthenticated } from '@/features/bots/errors';
import { botMessages } from '@/features/bots/messages';
import { modeStepMessages } from '@/features/bots/mode-messages';
import { ModeStep, reasonText, type EligibilityLoad } from '@/features/bots/ModeStep';
import { RealConfirmDialog, type RealCreateOutcome } from '@/features/bots/RealConfirmDialog';

const text = botMessages.create_;

function browserTimeZone(): string {
  return Intl.DateTimeFormat().resolvedOptions().timeZone;
}

/**
 * F1 step 1 (BR1.1): the person chooses the template strategy and the name; the draft is created on the server and
 * the wizard opens on it. A token coming from the scanner travels in the address to step 1.
 *
 * <p>261121-paper-real-unico U1 (US1.1 to US1.3; BR1.1, BR1.5, BR1.7, BR2.3, BR2.7, BR2.8): step 1 of 5 is now "Modo e
 * nome". "Simulado" is chosen by default; the REAL eligibility is read once when the screen opens. A PAPER draft is
 * created at once; a REAL bot goes through the confirmation, and the eligibility is checked again by the server in
 * the command. A domain refusal (409) closes the dialog, reads the eligibility again, goes back to "Simulado" and says
 * why, with the focus on the message; name and strategy stay filled.
 */
export function BotCreate({ timeZone }: { timeZone?: string } = {}) {
  const zone = timeZone ?? browserTimeZone();
  const status = useRequireSession('/bots/new');
  const api = useApi();
  const router = useRouter();
  const search = useSearchParams();
  const { expire } = useSession();
  const [templates, setTemplates] = useState<StrategyTemplate[]>([]);
  const [templateId, setTemplateId] = useState('MOMENTUM_VOLUME');
  const [name, setName] = useState('');
  const [market, setMarket] = useState<BotMarket>('PAPER');
  const [eligibility, setEligibility] = useState<EligibilityLoad>({ kind: 'loading' });
  const [confirming, setConfirming] = useState(false);
  const [refusal, setRefusal] = useState<{ message: string; code: string } | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [sending, setSending] = useState(false);
  const submitButton = useRef<HTMLButtonElement>(null);
  const refusalMessage = useRef<HTMLDivElement>(null);

  const readEligibility = useCallback(async (): Promise<RealEligibility | null> => {
    setEligibility({ kind: 'loading' });
    try {
      const read = await api.getRealEligibility();
      setEligibility({ kind: 'ready', eligibility: read });
      return read;
    } catch (failure) {
      if (isUnauthenticated(failure)) {
        expire();
        return null;
      }
      const code =
        failure instanceof ApiError ? (failure.correlationId ?? failure.code) : 'NETWORK';
      setEligibility({ kind: 'error', code });
      return null;
    }
  }, [api, expire]);

  useEffect(() => {
    if (status !== 'authenticated') {
      return;
    }
    api
      .listStrategyTemplates()
      .then(setTemplates)
      .catch((failure: unknown) => (isUnauthenticated(failure) ? expire() : setError(text.error)));
    void readEligibility();
  }, [api, expire, status, readEligibility]);

  useEffect(() => {
    if (refusal) {
      refusalMessage.current?.focus();
    }
  }, [refusal]);

  function validName(): string | null {
    const trimmed = name.trim();
    if (trimmed.length === 0 || trimmed.length > 60) {
      setError(text.nameRequired);
      return null;
    }
    return trimmed;
  }

  function openWizard(botId: string) {
    const query = search.toString();
    router.push(`/bots/new/${botId}${query ? `?${query}` : ''}`);
  }

  async function submit(event: FormEvent) {
    event.preventDefault();
    const trimmed = validName();
    if (trimmed === null) {
      return;
    }
    setError(null);
    setRefusal(null);
    if (market === 'REAL') {
      setConfirming(true);
      return;
    }
    setSending(true);
    try {
      const created = await api.createBot({ templateId, name: trimmed, market: 'PAPER' });
      openWizard(created.botId);
    } catch (failure) {
      if (isUnauthenticated(failure)) {
        expire();
        return;
      }
      setError(text.error);
      setSending(false);
    }
  }

  async function createReal(idempotencyKey: string) {
    const created = await api.createBot(
      { templateId, name: name.trim(), market: 'REAL' },
      idempotencyKey,
    );
    setConfirming(false);
    openWizard(created.botId);
    return created;
  }

  async function refused(outcome: Extract<RealCreateOutcome, { kind: 'refused' }>) {
    setConfirming(false);
    setMarket('PAPER');
    const read = await readEligibility();
    const reason = read && !read.available ? reasonText(read, zone) : '';
    setRefusal({ message: `${modeStepMessages.refused} ${reason}`.trim(), code: outcome.code });
  }

  const templateName =
    templates.find((template) => template.templateId === templateId)?.name ?? templateId;

  return (
    <BotsShell title={text.title} testId="bot-create" tag={null}>
      <p data-testid="bot-create-step">{modeStepMessages.stepIndicator}</p>
      <form onSubmit={submit} data-testid="bot-create-form">
        <ModeStep
          value={market}
          onChange={(next) => {
            setMarket(next);
            setRefusal(null);
          }}
          eligibility={eligibility}
          onRetry={() => void readEligibility()}
          timeZone={zone}
        />
        {refusal && (
          <div
            ref={refusalMessage}
            tabIndex={-1}
            role="alert"
            className="notice notice-error"
            data-testid="bot-create-refused"
          >
            <p>{refusal.message}</p>
            <p className="muted">{modeStepMessages.supportCode(refusal.code)}</p>
          </div>
        )}
        <fieldset>
          <legend>{text.strategy}</legend>
          {templates.map((template) => (
            <div key={template.templateId}>
              <input
                type="radio"
                id={`bot-create-${template.templateId}`}
                name="template"
                value={template.templateId}
                checked={templateId === template.templateId}
                data-testid={`bot-create-${template.templateId}`}
                onChange={() => setTemplateId(template.templateId)}
              />
              <label htmlFor={`bot-create-${template.templateId}`}>{template.name}</label>
              <p className="muted">{template.description}</p>
            </div>
          ))}
        </fieldset>
        <label htmlFor="bot-create-name">{text.name}</label>
        <input
          id="bot-create-name"
          value={name}
          maxLength={60}
          aria-describedby="bot-create-name-help"
          data-testid="bot-create-name"
          onChange={(event) => setName(event.target.value)}
        />
        <p id="bot-create-name-help" className="muted">
          {text.nameHelp}
        </p>
        {error && (
          <p role="alert" className="field-error" data-testid="bot-create-error">
            {error}
          </p>
        )}
        <button ref={submitButton} type="submit" disabled={sending} data-testid="bot-create-submit">
          {sending
            ? text.creating
            : market === 'REAL'
              ? modeStepMessages.continueReal
              : modeStepMessages.createPaper}
        </button>
      </form>
      {confirming && (
        <RealConfirmDialog
          draft={{ name: name.trim(), templateName }}
          onConfirm={createReal}
          onRefused={(outcome) => void refused(outcome)}
          onClose={() => setConfirming(false)}
          returnFocusTo={submitButton.current}
        />
      )}
    </BotsShell>
  );
}
