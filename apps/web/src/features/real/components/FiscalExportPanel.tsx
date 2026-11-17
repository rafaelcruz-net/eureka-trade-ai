'use client';

import { useState } from 'react';
import type { GuestApi } from '@/lib/api/guest-api';
import { realCall, toRealError } from '@/features/real/api';
import { realMessages as text } from '@/features/real/messages';

const copy = text.fiscal;

/** The dates of the period in the zone of the person: the last 12 months ending today, as `yyyy-mm-dd`. */
export function defaultPeriod(today: Date, timeZone?: string): { from: string; to: string } {
  const day = (date: Date) =>
    new Intl.DateTimeFormat('en-CA', {
      timeZone,
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
    }).format(date);
  const yearAgo = new Date(today.getTime());
  yearAgo.setUTCDate(yearAgo.getUTCDate() - 365);
  return { from: day(yearAgo), to: day(today) };
}

/** The whole days between two `yyyy-mm-dd` dates (the end excluded), in UTC so the change of offset never counts. */
export function daysBetween(from: string, to: string): number {
  return Math.trunc((Date.parse(`${to}T00:00:00Z`) - Date.parse(`${from}T00:00:00Z`)) / 86_400_000);
}

/** The zone of the browser for the column `data_local` (the server keeps UTC). */
function browserZone(): string | undefined {
  return Intl.DateTimeFormat().resolvedOptions().timeZone || undefined;
}

/**
 * 261113-trading-real-bot U7, "Exportar histórico (CSV)" (BR7, BR8.4, S28, S31, S32): the period in two dates of the
 * person (at most 1 year), the CSV generated on demand by the API from the REAL ledger and downloaded with the name the
 * server gives. The file goes straight to the download of the browser: nothing is kept by the app nor in the browser
 * storage, and the object URL is revoked at once.
 */
export function FiscalExportPanel({
  api,
  timeZone,
  now = () => new Date(),
  save = saveFile,
}: {
  api: GuestApi;
  timeZone?: string;
  now?: () => Date;
  save?: (file: Blob, name: string) => void;
}) {
  const initial = defaultPeriod(now(), timeZone);
  const [from, setFrom] = useState(initial.from);
  const [to, setTo] = useState(initial.to);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<{ kind: 'status' | 'alert'; text: string } | null>(null);
  const span = from && to ? daysBetween(from, to) : 0;
  const problem = span < 0 ? copy.inverted : span > 365 ? copy.tooLong : null;

  async function exportFile() {
    if (busy || problem || !from || !to) {
      return;
    }
    setBusy(true);
    setMessage(null);
    try {
      const file = await realCall(() =>
        api.exportRealFiscal({ from, to, tz: timeZone ?? browserZone() }),
      );
      save(file, `historico-real-${from}-${to}.csv`);
      setMessage({ kind: 'status', text: copy.done });
    } catch (failure) {
      setMessage({ kind: 'alert', text: toRealError(failure).text });
    } finally {
      setBusy(false);
    }
  }

  return (
    <section aria-labelledby="real-fiscal-title" data-testid="real-fiscal-export">
      <h2 id="real-fiscal-title">{copy.title}</h2>
      <p className="muted">{copy.hint}</p>
      <p>
        <label htmlFor="real-fiscal-from">{copy.from}</label>{' '}
        <input
          id="real-fiscal-from"
          type="date"
          value={from}
          onChange={(event) => setFrom(event.target.value)}
          data-testid="real-fiscal-from"
        />{' '}
        <label htmlFor="real-fiscal-to">{copy.to}</label>{' '}
        <input
          id="real-fiscal-to"
          type="date"
          value={to}
          onChange={(event) => setTo(event.target.value)}
          data-testid="real-fiscal-to"
        />
      </p>
      {problem && (
        <p role="alert" data-testid="real-fiscal-problem">
          {problem}
        </p>
      )}
      <button
        type="button"
        disabled={busy || problem !== null}
        onClick={() => void exportFile()}
        data-testid="real-fiscal-button"
      >
        {busy ? copy.exporting : copy.export}
      </button>
      {message && (
        <p role={message.kind} data-testid={`real-fiscal-${message.kind}`}>
          {message.text}
        </p>
      )}
    </section>
  );
}

/** Hands the file to the download of the browser and forgets it (no storage, no cache of the app). */
function saveFile(file: Blob, name: string): void {
  const url = URL.createObjectURL(file);
  const link = document.createElement('a');
  link.href = url;
  link.download = name;
  link.rel = 'noopener';
  document.body.appendChild(link);
  link.click();
  link.remove();
  URL.revokeObjectURL(url);
}
