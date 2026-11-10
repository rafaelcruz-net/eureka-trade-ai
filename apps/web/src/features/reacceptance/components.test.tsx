import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { TermsStatus } from '@/lib/api/guest-api';
import { CURRENT, LEGACY } from '@/features/legal/fixtures';
import { LegalSummary } from '@/features/reacceptance/LegalSummary';
import { reacceptanceMessages } from '@/features/reacceptance/messages';
import {
  hasFullConsent,
  NO_CONSENT,
  TermsConsentBoxes,
  type Consent,
} from '@/features/reacceptance/TermsConsentBoxes';
import { TermsStatusPanel } from '@/features/reacceptance/TermsStatusPanel';

/**
 * Passo 10 of u3-reacceptance (BR3.7 to BR3.10; NFR4.1, NFR4.2; US2.3, US4.1, US4.2): the two boxes, the summary with
 * its links in another tab and the status panel of the Account, each alone.
 */
describe('TermsConsentBoxes', () => {
  function renderBoxes(
    consent: Consent = NO_CONSENT,
    { disabled = false, help }: { disabled?: boolean; help?: string } = {},
  ) {
    const onChange = jest.fn();
    render(
      <TermsConsentBoxes
        idPrefix="sample"
        consent={consent}
        disabled={disabled}
        onChange={onChange}
        help={help}
      />,
    );
    return onChange;
  }

  it('shows two boxes with visible labels and the prefix of the screen in the ids', () => {
    renderBoxes();

    expect(screen.getByLabelText(reacceptanceMessages.consent.terms)).toHaveAttribute(
      'id',
      'sample-accept-terms',
    );
    expect(screen.getByLabelText(reacceptanceMessages.consent.risk)).toHaveAttribute(
      'data-testid',
      'sample-accept-risk',
    );
    expect(screen.getByRole('group')).toHaveAccessibleName(reacceptanceMessages.consent.legend);
  });

  it('reports each mark without losing the other one', async () => {
    const onChange = renderBoxes({ terms: false, risk: true });

    await userEvent.click(screen.getByTestId('sample-accept-terms'));

    expect(onChange).toHaveBeenCalledWith({ terms: true, risk: true });
  });

  it('unmarks a box', async () => {
    const onChange = renderBoxes({ terms: true, risk: true });

    await userEvent.click(screen.getByTestId('sample-accept-risk'));

    expect(onChange).toHaveBeenCalledWith({ terms: true, risk: false });
  });

  it('disables both boxes and ignores clicks while disabled', async () => {
    const onChange = renderBoxes(NO_CONSENT, { disabled: true });

    expect(screen.getByTestId('sample-accept-terms')).toBeDisabled();
    expect(screen.getByTestId('sample-accept-risk')).toBeDisabled();
    await userEvent.click(screen.getByTestId('sample-accept-terms'));
    expect(onChange).not.toHaveBeenCalled();
  });

  it('shows the help text and links it to the group', () => {
    renderBoxes(NO_CONSENT, { help: 'Ajuda' });

    expect(screen.getByRole('group')).toHaveAttribute('aria-describedby', 'sample-consent-help');
    expect(screen.getByText('Ajuda')).toHaveAttribute('id', 'sample-consent-help');
  });

  it('accepts custom labels and is reachable by keyboard in order', async () => {
    const onChange = jest.fn();
    render(
      <TermsConsentBoxes
        idPrefix="other"
        consent={NO_CONSENT}
        disabled={false}
        labels={{ terms: 'Aceito A', risk: 'Aceito B' }}
        onChange={onChange}
      />,
    );

    await userEvent.tab();
    expect(screen.getByLabelText('Aceito A')).toHaveFocus();
    await userEvent.keyboard(' ');
    expect(onChange).toHaveBeenCalledWith({ terms: true, risk: false });
    await userEvent.tab();
    expect(screen.getByLabelText('Aceito B')).toHaveFocus();
  });

  it('full consent needs both marks', () => {
    expect(hasFullConsent({ terms: true, risk: true })).toBe(true);
    expect(hasFullConsent({ terms: true, risk: false })).toBe(false);
    expect(hasFullConsent(NO_CONSENT)).toBe(false);
  });
});

describe('LegalSummary', () => {
  it('lists the summaries of the terms and of the privacy policy under h2 headings', () => {
    render(<LegalSummary documents={CURRENT} idPrefix="s" />);

    expect(
      screen.getByRole('heading', { level: 2, name: reacceptanceMessages.summary.termsHeading }),
    ).toBeInTheDocument();
    expect(within(screen.getByTestId('s-terms-summary')).getAllByRole('listitem')).toHaveLength(5);
    expect(within(screen.getByTestId('s-privacy-summary')).getAllByRole('listitem')).toHaveLength(
      6,
    );
  });

  it('opens the full documents in another tab without access to this page', () => {
    render(<LegalSummary documents={CURRENT} idPrefix="s" />);

    const terms = screen.getByRole('link', { name: reacceptanceMessages.summary.readTerms });
    const privacy = screen.getByRole('link', { name: reacceptanceMessages.summary.readPrivacy });
    expect(terms).toHaveAttribute('href', '/termos');
    expect(privacy).toHaveAttribute('href', '/privacidade');
    for (const link of [terms, privacy]) {
      expect(link).toHaveAttribute('target', '_blank');
      expect(link.getAttribute('rel')).toContain('noopener');
    }
    expect(screen.getByRole('navigation')).toHaveAccessibleName(
      reacceptanceMessages.summary.linksLabel,
    );
  });

  it('says when a legacy version has no summary nor privacy policy', () => {
    render(<LegalSummary documents={LEGACY} idPrefix="s" />);

    expect(screen.getByTestId('s-terms-summary')).toHaveTextContent(
      reacceptanceMessages.summary.unavailable,
    );
    expect(screen.getByTestId('s-privacy-summary')).toHaveTextContent(
      reacceptanceMessages.summary.privacyMissing,
    );
  });

  it('says when a privacy policy has no summary', () => {
    render(
      <LegalSummary
        documents={{ ...CURRENT, privacy: { summary: null, sections: [] } }}
        idPrefix="s"
      />,
    );

    expect(screen.getByTestId('s-privacy-summary')).toHaveTextContent(
      reacceptanceMessages.summary.unavailable,
    );
  });

  it('never renders the summary as HTML', () => {
    render(
      <LegalSummary
        documents={{ ...CURRENT, terms: { summary: ['<b>negrito</b>'], sections: [] } }}
        idPrefix="s"
      />,
    );

    const summary = screen.getByTestId('s-terms-summary');
    expect(summary).toHaveTextContent('<b>negrito</b>');
    expect(summary.querySelector('b')).toBeNull();
  });
});

describe('TermsStatusPanel', () => {
  const UP_TO_DATE: TermsStatus = {
    acceptedVersion: 'v2',
    currentVersion: 'v2',
    acceptedAt: '2026-10-28T12:00:00Z',
    upToDate: true,
  };

  it('up to date: the accepted version, its date in the time zone of the person and no link', () => {
    render(<TermsStatusPanel terms={UP_TO_DATE} timeZone="America/Sao_Paulo" />);

    expect(screen.getByTestId('terms-status-accepted')).toHaveTextContent(
      'Você aceitou a versão v2 em 28/10/2026, 09:00:00.',
    );
    expect(screen.getByTestId('terms-status-up-to-date')).toHaveTextContent(
      reacceptanceMessages.status.upToDate,
    );
    expect(screen.queryByTestId('terms-status-accept-link')).toBeNull();
    expect(
      screen.getByRole('heading', { level: 2, name: reacceptanceMessages.status.heading }),
    ).toBeInTheDocument();
  });

  it('outdated: the new version in text and "Aceitar a nova versão" back to the Account', () => {
    render(
      <TermsStatusPanel
        terms={{ ...UP_TO_DATE, acceptedVersion: 'v1', currentVersion: 'v2', upToDate: false }}
        timeZone="UTC"
      />,
    );

    expect(screen.getByTestId('terms-status-outdated')).toHaveTextContent('(v2)');
    const link = screen.getByRole('link', { name: reacceptanceMessages.status.acceptNew });
    expect(link).toHaveAttribute('href', '/novo-aceite?voltar=%2Fconta');
    expect(screen.getByTestId('terms-status-accepted')).toHaveTextContent('versão v1');
  });

  it('without any acceptance: the text says so and offers the new acceptance', () => {
    render(<TermsStatusPanel terms={undefined} />);

    expect(screen.queryByTestId('terms-status-accepted')).toBeNull();
    expect(screen.getByTestId('terms-status-outdated')).toHaveTextContent(
      reacceptanceMessages.status.none,
    );
    expect(screen.getByTestId('terms-status-accept-link')).toBeInTheDocument();
  });
});
