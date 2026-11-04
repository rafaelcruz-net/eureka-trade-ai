'use client';

import { useId, useState } from 'react';
import type { RiskSummary } from '@/lib/api/guest-api';
import { messages } from '@/lib/i18n/messages';

const text = messages.scanner;

/**
 * Column "Risco" (BR5.4): an AVAILABLE indicator with its level in words; an UNAVAILABLE one as "--" with the reason
 * in text on hover and on focus. Never a provisional number and never the word "seguro".
 */
export function RiskCell({ risk, testId }: { risk: RiskSummary; testId: string }) {
  const [open, setOpen] = useState(false);
  const tooltipId = useId();

  if (risk.status === 'AVAILABLE') {
    return (
      <span data-testid={testId} data-status="AVAILABLE" data-band={risk.band}>
        {text.riskCell(risk.score, text.riskBand[risk.band] ?? risk.band)}
      </span>
    );
  }
  const reason = text.riskReason[risk.reason] ?? risk.reason;
  return (
    <span
      className="value-cell"
      tabIndex={0}
      data-testid={testId}
      data-status="UNAVAILABLE"
      aria-label={`${text.riskUnavailable} ${reason}`}
      aria-describedby={tooltipId}
      onMouseEnter={() => setOpen(true)}
      onMouseLeave={() => setOpen(false)}
      onFocus={() => setOpen(true)}
      onBlur={() => setOpen(false)}
    >
      <span aria-hidden="true">{text.riskUnavailable}</span>
      <span
        id={tooltipId}
        role="tooltip"
        className="value-tooltip"
        hidden={!open}
        data-testid={`${testId}-reason`}
      >
        {reason}
      </span>
    </span>
  );
}
