'use client';

import { reacceptanceMessages } from '@/features/reacceptance/messages';

export interface Consent {
  /** "Li e aceito os termos de uso (e a política de privacidade)". */
  terms: boolean;
  /** "Li e entendi o aviso de risco". */
  risk: boolean;
}

export const NO_CONSENT: Consent = { terms: false, risk: false };

export function hasFullConsent(consent: Consent): boolean {
  return consent.terms && consent.risk;
}

/**
 * u3-reacceptance (BR3.7 to BR3.9; NFR4.1, NFR4.2): the two boxes of the invitation and of the new acceptance, each
 * with its VISIBLE label. `disabled` while the documents load or failed, and while the acceptance is sent. The ids and
 * the test ids carry the prefix of the screen (`invite-accept-terms`, `reacceptance-accept-risk`).
 */
export function TermsConsentBoxes({
  idPrefix,
  consent,
  disabled,
  onChange,
  labels = reacceptanceMessages.consent,
  help,
}: {
  idPrefix: string;
  consent: Consent;
  disabled: boolean;
  onChange: (next: Consent) => void;
  labels?: { terms: string; risk: string };
  help?: string;
}) {
  const termsId = `${idPrefix}-accept-terms`;
  const riskId = `${idPrefix}-accept-risk`;
  return (
    <fieldset
      data-testid={`${idPrefix}-consent`}
      aria-describedby={help ? `${idPrefix}-consent-help` : undefined}
    >
      <legend className="visually-hidden">{reacceptanceMessages.consent.legend}</legend>
      <div>
        <input
          id={termsId}
          type="checkbox"
          data-testid={termsId}
          checked={consent.terms}
          disabled={disabled}
          onChange={(event) => onChange({ ...consent, terms: event.target.checked })}
        />
        <label htmlFor={termsId}>{labels.terms}</label>
      </div>
      <div>
        <input
          id={riskId}
          type="checkbox"
          data-testid={riskId}
          checked={consent.risk}
          disabled={disabled}
          onChange={(event) => onChange({ ...consent, risk: event.target.checked })}
        />
        <label htmlFor={riskId}>{labels.risk}</label>
      </div>
      {help ? (
        <p className="muted" id={`${idPrefix}-consent-help`}>
          {help}
        </p>
      ) : null}
    </fieldset>
  );
}
