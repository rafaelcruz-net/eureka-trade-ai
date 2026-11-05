'use client';

import { useRouter, useSearchParams } from 'next/navigation';
import { useEffect, useState, type FormEvent } from 'react';
import { useApi } from '@/lib/api/ApiProvider';
import type { StrategyTemplate } from '@/lib/api/guest-api';
import { useSession } from '@/features/auth/SessionProvider';
import { useRequireSession } from '@/features/auth/useRequireSession';
import { BotsShell } from '@/features/bots/BotsShell';
import { isUnauthenticated } from '@/features/bots/errors';
import { botMessages } from '@/features/bots/messages';

const text = botMessages.create_;

/**
 * F1 step 1 (BR1.1): the person chooses the template strategy and the name; the draft is created on the server and
 * the wizard opens on it. A token coming from the scanner ("Usar em bot PAPER") travels in the address to step 1.
 */
export function BotCreate() {
  const status = useRequireSession('/bots/new');
  const api = useApi();
  const router = useRouter();
  const search = useSearchParams();
  const { expire } = useSession();
  const [templates, setTemplates] = useState<StrategyTemplate[]>([]);
  const [templateId, setTemplateId] = useState('MOMENTUM_VOLUME');
  const [name, setName] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [sending, setSending] = useState(false);

  useEffect(() => {
    if (status !== 'authenticated') {
      return;
    }
    api
      .listStrategyTemplates()
      .then(setTemplates)
      .catch((failure: unknown) => (isUnauthenticated(failure) ? expire() : setError(text.error)));
  }, [api, expire, status]);

  async function submit(event: FormEvent) {
    event.preventDefault();
    const trimmed = name.trim();
    if (trimmed.length === 0 || trimmed.length > 60) {
      setError(text.nameRequired);
      return;
    }
    setSending(true);
    setError(null);
    try {
      const created = await api.createBot({ templateId, name: trimmed });
      const query = search.toString();
      router.push(`/bots/new/${created.botId}${query ? `?${query}` : ''}`);
    } catch (failure) {
      if (isUnauthenticated(failure)) {
        expire();
        return;
      }
      setError(text.error);
      setSending(false);
    }
  }

  return (
    <BotsShell title={text.title} testId="bot-create">
      <form onSubmit={submit} data-testid="bot-create-form">
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
        <button type="submit" disabled={sending} data-testid="bot-create-submit">
          {sending ? text.creating : text.submit}
        </button>
      </form>
    </BotsShell>
  );
}
