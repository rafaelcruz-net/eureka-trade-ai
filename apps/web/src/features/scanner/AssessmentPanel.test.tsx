import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ApiError } from '@/lib/api/client';
import type { Assessment } from '@/lib/api/guest-api';
import { messages } from '@/lib/i18n/messages';
import { fakeApi, Providers } from '@/features/auth/test-support';
import { AssessmentPanel } from '@/features/scanner/AssessmentPanel';
import { assessment, MINT_A } from '@/features/scanner/fixtures';

jest.mock('next/navigation', () => ({
  useRouter: () => ({ replace: jest.fn(), push: jest.fn() }),
  useSearchParams: () => new URLSearchParams(),
}));

const text = messages.assessment;

function renderPanel(getAssessment: jest.Mock, onClose = jest.fn(), onUnauthenticated = jest.fn()) {
  render(
    <Providers api={fakeApi({ getAssessment })}>
      <AssessmentPanel
        chain="solana"
        mint={MINT_A}
        symbol="PEPE"
        timeZone="UTC"
        onClose={onClose}
        onUnauthenticated={onUnauthenticated}
      />
    </Providers>,
  );
  return { onClose, onUnauthenticated };
}

function deferred<T>() {
  let resolve: (value: T) => void = () => undefined;
  const promise = new Promise<T>((r) => {
    resolve = r;
  });
  return { promise, resolve };
}

describe('assessment side panel', () => {
  it('while loading shows only "Carregando avaliação" and never a provisional number', async () => {
    const pending = deferred<Assessment>();
    renderPanel(jest.fn(() => pending.promise));

    expect(screen.getByTestId('assessment-loading')).toHaveTextContent(text.loading);
    expect(screen.getByTestId('assessment-panel')).not.toHaveTextContent(/\d/);
    pending.resolve(assessment());
    expect(await screen.findByTestId('assessment-score')).toHaveTextContent(
      'Indicador: 61 de 100 — Risco médio',
    );
  });

  it('shows how the indicator was made: validation, scale, version, coverage, time and every factor', async () => {
    const getAssessment = jest.fn(async () => assessment());
    renderPanel(getAssessment);

    await screen.findByTestId('assessment-detail');
    expect(getAssessment).toHaveBeenCalledWith('solana', MINT_A);
    expect(screen.getByTestId('assessment-in-validation')).toHaveTextContent(text.inValidation);
    expect(screen.getByTestId('assessment-scale')).toHaveTextContent('maior significa mais risco');
    expect(screen.getByTestId('assessment-version')).toHaveTextContent('Versão dos pesos: 1');
    expect(screen.getByTestId('assessment-coverage')).toHaveTextContent(
      'Cobertura: 100,0000% (mínima 80,0000%)',
    );
    expect(screen.getByTestId('assessment-computed-at')).toHaveTextContent('Calculado às 12:00');
    expect(screen.getByTestId('assessment-decision')).toHaveTextContent('Avaliação: ELIGIBLE');
    expect(screen.getByTestId('assessment-factor-LIQUIDITY')).toHaveTextContent('35,0000%');
    expect(screen.getByTestId('assessment-factor-LIQUIDITY')).toHaveTextContent('Fonte simulada A');
    expect(screen.getByTestId('assessment-factor-LIQUIDITY-contribution')).toHaveTextContent(
      '28,0000',
    );
    expect(screen.getByTestId('assessment-factor-HOLDERS-stale')).toHaveTextContent(
      messages.scanner.stale,
    );
    expect(screen.getByTestId('assessment-factor-PRICE_CHANGE_1H-contribution')).toHaveTextContent(
      text.contributionUnknown,
    );
    expect(screen.getByTestId('assessment-factor-PRICE_CHANGE_1H')).toHaveTextContent('unknown');
  });

  it('explains a level raised by an unknown factor', async () => {
    renderPanel(jest.fn(async () => assessment({ bandRaisedByUnknown: true })));

    expect(await screen.findByTestId('assessment-raised')).toHaveTextContent(text.raisedByUnknown);
  });

  it('an unavailable indicator shows its reason and NO_TRADE, without a score', async () => {
    renderPanel(
      jest.fn(async () =>
        assessment({
          risk: { status: 'UNAVAILABLE', reason: 'SOURCE_UNAVAILABLE' },
          decisionHint: 'NO_TRADE',
          coverage: '0.0000',
        }),
      ),
    );

    expect(await screen.findByTestId('assessment-unavailable')).toHaveTextContent(text.unavailable);
    expect(screen.getByTestId('assessment-unavailable-reason')).toHaveTextContent(
      'Fontes indisponíveis',
    );
    expect(screen.getByTestId('assessment-decision')).toHaveTextContent('Avaliação: NO_TRADE');
    expect(screen.queryByTestId('assessment-score')).toBeNull();
  });

  it('closes with "Fechar" and with Esc', async () => {
    const { onClose } = renderPanel(jest.fn(async () => assessment()));

    await userEvent.click(await screen.findByTestId('assessment-close'));
    await userEvent.keyboard('{Escape}');

    expect(onClose).toHaveBeenCalledTimes(2);
  });

  it('a token without recent data and a failure are distinct messages', async () => {
    renderPanel(jest.fn().mockRejectedValue(new ApiError(404, 'NOT_FOUND')));
    expect(await screen.findByTestId('assessment-not-found')).toHaveTextContent(text.notFound);
  });

  it('a technical failure shows an error, never an indicator', async () => {
    renderPanel(jest.fn().mockRejectedValue(new ApiError(500, 'INTERNAL_ERROR')));
    expect(await screen.findByTestId('assessment-error')).toHaveTextContent(text.error);
    expect(screen.queryByTestId('assessment-score')).toBeNull();
  });

  it('an expired session is handed to the screen', async () => {
    const { onUnauthenticated } = renderPanel(
      jest.fn().mockRejectedValue(new ApiError(401, 'UNAUTHENTICATED')),
    );
    await screen.findByTestId('assessment-loading');
    await new Promise((resolve) => setTimeout(resolve, 0));
    expect(onUnauthenticated).toHaveBeenCalled();
  });

  it('is a labelled non-modal dialog next to the list', async () => {
    renderPanel(jest.fn(async () => assessment()));

    const dialog = screen.getByRole('dialog', { name: text.title('PEPE') });
    expect(dialog).toHaveAttribute('aria-modal', 'false');
    await screen.findByTestId('assessment-detail');
  });
});
