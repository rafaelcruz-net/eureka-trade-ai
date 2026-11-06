'use client';

import { useCallback, useEffect, useState, type ReactNode } from 'react';
import { useApi } from '@/lib/api/ApiProvider';
import type {
  CountMapPart,
  CountPart,
  KillSwitchPart,
  SourcesPart,
  SystemStatus,
} from '@/lib/api/admin-api';
import { browserTimeZone, count, time } from '@/lib/format';
import { messages } from '@/lib/i18n/messages';
import { useFailure } from '@/features/auth/use-failure';

const text = messages.status;

type Part = CountPart | CountMapPart | KillSwitchPart | SourcesPart;

/**
 * A1, the system status (F5; BR3.1 to BR3.3; AC7.4.1): counts and the state of the sources only. Loading, error and
 * UNKNOWN are three distinct visible states: a part that did not answer says "desconhecido" with its reason, never
 * zero. The time of the reading is shown in the time zone of the browser.
 */
export function SystemStatusPage({ timeZone = browserTimeZone() }: { timeZone?: string }) {
  const api = useApi();
  const handled = useFailure();
  const [status, setStatus] = useState<SystemStatus | null>(null);
  const [failed, setFailed] = useState(false);

  const load = useCallback(async () => {
    setFailed(false);
    try {
      setStatus(await api.getSystemStatus());
    } catch (failure) {
      if (!handled(failure)) {
        setFailed(true);
      }
    }
  }, [api, handled]);

  useEffect(() => {
    void load();
  }, [load]);

  return (
    <section aria-labelledby="status-title" data-testid="system-status">
      <h1 id="status-title">{text.title}</h1>
      <button
        type="button"
        className="secondary"
        data-testid="system-status-refresh"
        onClick={() => void load()}
      >
        {text.refresh}
      </button>
      {failed ? (
        <p role="alert" className="field-error" data-testid="system-status-error">
          {text.error}
        </p>
      ) : null}
      {!status && !failed ? (
        <p role="status" data-testid="system-status-loading">
          {text.loading}
        </p>
      ) : null}
      {status ? (
        <>
          <p data-testid="system-status-updated">
            {text.updated(time(status.generatedAt, timeZone))}
          </p>
          <dl className="status-grid">
            <Item label={text.guests} part={status.guestCount} testId="status-guests">
              {(value: number) => count(value)}
            </Item>
            <Item label={text.invites} part={status.inviteCounts} testId="status-invites">
              {(value: Record<string, number>) => countList(value, text.inviteStatus)}
            </Item>
            <Item label={text.bots} part={status.botCountsByState} testId="status-bots">
              {(value: Record<string, number>) => countList(value, text.botState)}
            </Item>
            <Item label={text.killSwitch} part={status.killSwitch} testId="status-kill-switch">
              {(value: NonNullable<KillSwitchPart['value']>) =>
                value.state === 'ON' ? text.switchOn : text.switchOff
              }
            </Item>
            <Item label={text.sources} part={status.sources} testId="status-sources">
              {(value: NonNullable<SourcesPart['value']>) => (
                <ul>
                  {value.map((source) => (
                    <li key={source.sourceId} data-testid={`status-source-${source.sourceId}`}>
                      {source.name}
                      {source.simulated ? ` (${text.simulated})` : ''}:{' '}
                      {text.sourceStatus[source.status]}
                      {', '}
                      {source.lastUpdatedAt
                        ? text.sourceUpdated(time(source.lastUpdatedAt, timeZone))
                        : text.sourceNever}
                    </li>
                  ))}
                </ul>
              )}
            </Item>
            <Item
              label={text.auditOutbox}
              part={status.auditOutboxPending}
              testId="status-audit-outbox"
            >
              {(value: number) => count(value)}
            </Item>
            <Item
              label={text.rejectedToday}
              part={status.rejectedOrdersToday}
              testId="status-rejected"
            >
              {(value: number) => count(value)}
            </Item>
          </dl>
        </>
      ) : null}
    </section>
  );
}

function countList(values: Record<string, number>, labels: Record<string, string>): string {
  return Object.entries(values)
    .map(([key, value]) => `${labels[key] ?? key}: ${count(value)}`)
    .join(' · ');
}

/** One part: its value when OK, "desconhecido (motivo)" when UNKNOWN, never a zero in its place (BR3.3). */
function Item<T>({
  label,
  part,
  testId,
  children,
}: {
  label: string;
  part: Part;
  testId: string;
  children: (value: T) => ReactNode;
}) {
  const known = part.status === 'OK' && part.value !== undefined && part.value !== null;
  return (
    <div data-testid={testId} data-state={known ? 'known' : 'unknown'}>
      <dt>{label}</dt>
      <dd>
        {known ? (
          children(part.value as T)
        ) : (
          <span className="unknown">
            <span aria-hidden="true">? </span>
            {text.unknown} ({text.unknownReason[part.reason ?? 'UNAVAILABLE']})
          </span>
        )}
      </dd>
    </div>
  );
}
