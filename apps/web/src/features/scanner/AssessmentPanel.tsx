'use client';

import { useEffect, useState } from 'react';
import { ApiError } from '@/lib/api/client';
import { useApi } from '@/lib/api/ApiProvider';
import type { Assessment, AssessmentFactor } from '@/lib/api/guest-api';
import { formatClock, formatDecimal, formatInstant } from '@/lib/format/format';
import { messages } from '@/lib/i18n/messages';

const text = messages.assessment;
const scanner = messages.scanner;

type Load =
  | { kind: 'loading' }
  | { kind: 'error' }
  | { kind: 'notFound' }
  | { kind: 'ready'; assessment: Assessment };

/**
 * Side panel of the assessment (US3.1, US3.2, BR3.9, BR5.2, BR5.4). A non-modal dialog next to the list: while it
 * loads only "Carregando avaliação" shows, never a provisional number; then the indicator with its level in words,
 * "maior significa mais risco", "Indicador em validação", weight version, coverage and computation time, the reason of
 * a raised level, and every factor with weight, input, source, time, stale mark and contribution; an unavailable
 * indicator shows its reason and "Avaliação: NO_TRADE". Esc or "Fechar" closes it (the screen gives the focus back to
 * the row).
 */
export function AssessmentPanel({
  chain,
  mint,
  symbol,
  timeZone,
  onClose,
  onUnauthenticated,
}: {
  chain: string;
  mint: string;
  symbol: string;
  timeZone?: string;
  onClose: () => void;
  onUnauthenticated: () => void;
}) {
  const api = useApi();
  const [load, setLoad] = useState<Load>({ kind: 'loading' });

  useEffect(() => {
    let current = true;
    setLoad({ kind: 'loading' });
    api
      .getAssessment(chain, mint)
      .then((assessment) => {
        if (current) {
          setLoad({ kind: 'ready', assessment });
        }
      })
      .catch((failure: unknown) => {
        if (!current) {
          return;
        }
        if (failure instanceof ApiError && failure.status === 401) {
          onUnauthenticated();
          return;
        }
        setLoad(
          failure instanceof ApiError && failure.status === 404
            ? { kind: 'notFound' }
            : { kind: 'error' },
        );
      });
    return () => {
      current = false;
    };
  }, [api, chain, mint, onUnauthenticated]);

  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        onClose();
      }
    };
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, [onClose]);

  return (
    <aside
      role="dialog"
      aria-modal="false"
      aria-labelledby="assessment-title"
      className="assessment-panel"
      data-testid="assessment-panel"
    >
      <header className="assessment-header">
        <h2 id="assessment-title">{text.title(symbol)}</h2>
        <button
          type="button"
          className="secondary"
          data-testid="assessment-close"
          onClick={onClose}
        >
          {text.close}
        </button>
      </header>
      <div aria-live="polite" aria-busy={load.kind === 'loading'}>
        {load.kind === 'loading' && (
          <p role="status" data-testid="assessment-loading">
            {text.loading}
          </p>
        )}
        {load.kind === 'error' && (
          <p role="alert" className="notice notice-error" data-testid="assessment-error">
            {text.error}
          </p>
        )}
        {load.kind === 'notFound' && <p data-testid="assessment-not-found">{text.notFound}</p>}
        {load.kind === 'ready' && <Detail assessment={load.assessment} timeZone={timeZone} />}
      </div>
    </aside>
  );
}

function Detail({ assessment, timeZone }: { assessment: Assessment; timeZone?: string }) {
  const risk = assessment.risk;
  return (
    <div data-testid="assessment-detail">
      <p className="badge-validation" data-testid="assessment-in-validation">
        {text.inValidation}
      </p>
      {risk.status === 'AVAILABLE' ? (
        <>
          <p className="assessment-score" data-testid="assessment-score">
            {text.score(risk.score)} — {scanner.riskBand[risk.band] ?? risk.band}
          </p>
          <p data-testid="assessment-scale">{text.scale}</p>
          {assessment.bandRaisedByUnknown && (
            <p className="notice notice-warning" data-testid="assessment-raised">
              ⚠ {text.raisedByUnknown}
            </p>
          )}
        </>
      ) : (
        <div data-testid="assessment-unavailable">
          <p className="assessment-score">{text.unavailable}</p>
          <p data-testid="assessment-unavailable-reason">
            {scanner.riskReason[risk.reason] ?? risk.reason}
          </p>
        </div>
      )}
      <p data-testid="assessment-decision">{text.decision(assessment.decisionHint)}</p>
      <p data-testid="assessment-version">{text.version(assessment.weightSetVersion)}</p>
      <p data-testid="assessment-coverage">
        {text.coverage(formatDecimal(assessment.coverage), formatDecimal(assessment.minCoverage))}
      </p>
      <p data-testid="assessment-computed-at">
        {text.computedAt(formatClock(new Date(assessment.computedAt), timeZone))}
      </p>
      <table className="factors" data-testid="assessment-factors">
        <caption>{text.factorsCaption}</caption>
        <thead>
          <tr>
            <th scope="col">{text.factorColumns.factor}</th>
            <th scope="col">{text.factorColumns.weight}</th>
            <th scope="col">{text.factorColumns.input}</th>
            <th scope="col">{text.factorColumns.source}</th>
            <th scope="col">{text.factorColumns.contribution}</th>
          </tr>
        </thead>
        <tbody>
          {assessment.factors.map((factor) => (
            <FactorRow key={factor.factorId} factor={factor} timeZone={timeZone} />
          ))}
        </tbody>
      </table>
    </div>
  );
}

function FactorRow({ factor, timeZone }: { factor: AssessmentFactor; timeZone?: string }) {
  const input = factor.input;
  const contribution = factor.contribution;
  return (
    <tr data-testid={`assessment-factor-${factor.factorId}`}>
      <th scope="row">{text.factorLabel[factor.factorId] ?? factor.label}</th>
      <td>{text.weight(formatDecimal(factor.weight))}</td>
      <td data-status={input.status}>
        {input.status === 'KNOWN' ? (
          <>
            {formatDecimal(input.value)}
            {input.stale && (
              <span
                className="badge-stale"
                data-testid={`assessment-factor-${factor.factorId}-stale`}
              >
                {' '}
                ⚠ {scanner.stale}
              </span>
            )}
          </>
        ) : (
          <span className="value-unknown">
            ? {scanner.unknown} — {scanner.unknownReason[input.reason] ?? input.reason}
          </span>
        )}
      </td>
      <td>
        {input.status === 'KNOWN'
          ? `${input.source.name} · ${formatInstant(input.observedAt, timeZone)}`
          : (input.source?.name ?? text.noSource)}
      </td>
      <td data-testid={`assessment-factor-${factor.factorId}-contribution`}>
        {typeof contribution === 'string' ? formatDecimal(contribution) : text.contributionUnknown}
      </td>
    </tr>
  );
}
