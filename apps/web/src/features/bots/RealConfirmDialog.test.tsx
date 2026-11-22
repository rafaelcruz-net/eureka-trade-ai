import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ApiError } from '@/lib/api/client';
import { RealConfirmDialog } from '@/features/bots/RealConfirmDialog';

const DRAFT = { name: 'Meu bot real', templateName: 'Momentum com volume' };

function renderDialog(overrides: Partial<Parameters<typeof RealConfirmDialog>[0]> = {}) {
  const props = {
    draft: DRAFT,
    onConfirm: jest.fn(async () => ({ botId: 'b1' })),
    onRefused: jest.fn(),
    onClose: jest.fn(),
    returnFocusTo: null,
    newKey: () => 'key-1',
    ...overrides,
  };
  render(<RealConfirmDialog {...props} />);
  return props;
}

/**
 * 261121-paper-real-unico U1, Step 18 (US1.3; BR1.4, BR1.5, BR2.8; AC1.3.x): the confirmation of a REAL bot. The box
 * frees the button; a domain refusal closes and hands the reason back; a technical error offers "Tentar de novo" with
 * the SAME key.
 */
describe('RealConfirmDialog', () => {
  it('opens with the focus on Cancelar and "Criar bot real" disabled until the box is checked', async () => {
    const props = renderDialog();

    expect(screen.getByTestId('real-confirm-cancel')).toHaveFocus();
    expect(screen.getByTestId('real-confirm-create')).toBeDisabled();
    expect(screen.getByTestId('real-confirm-summary')).toHaveTextContent(
      'Meu bot real · Momentum com volume',
    );
    await userEvent.click(screen.getByTestId('real-confirm-check'));
    expect(screen.getByTestId('real-confirm-create')).toBeEnabled();
    expect(props.onConfirm).not.toHaveBeenCalled();
  });

  it('creates with the key of the dialog after the confirmation', async () => {
    const props = renderDialog();

    await userEvent.click(screen.getByTestId('real-confirm-check'));
    await userEvent.click(screen.getByTestId('real-confirm-create'));

    expect(props.onConfirm).toHaveBeenCalledWith('key-1');
  });

  it('while sending both buttons are disabled and Esc does not close', async () => {
    let finish: (value: { botId: string }) => void = () => undefined;
    const props = renderDialog({
      onConfirm: jest.fn(() => new Promise<{ botId: string }>((resolve) => (finish = resolve))),
    });
    await userEvent.click(screen.getByTestId('real-confirm-check'));
    await userEvent.click(screen.getByTestId('real-confirm-create'));

    expect(screen.getByTestId('real-confirm-create')).toHaveTextContent('Criando o bot real…');
    expect(screen.getByTestId('real-confirm-cancel')).toBeDisabled();
    await userEvent.keyboard('{Escape}');
    expect(props.onClose).not.toHaveBeenCalled();
    finish({ botId: 'b1' });
  });

  it('a domain refusal (409 REAL_NOT_ELIGIBLE) hands the reason and the code back, without "Tentar de novo"', async () => {
    const props = renderDialog({
      onConfirm: jest.fn(async () => {
        throw new ApiError(409, 'REAL_NOT_ELIGIBLE', 'QUARANTINE', 'corr-409');
      }),
    });
    await userEvent.click(screen.getByTestId('real-confirm-check'));
    await userEvent.click(screen.getByTestId('real-confirm-create'));

    await waitFor(() =>
      expect(props.onRefused).toHaveBeenCalledWith({
        kind: 'refused',
        reason: 'QUARANTINE',
        code: 'corr-409',
      }),
    );
    expect(screen.queryByTestId('real-confirm-error')).toBeNull();
  });

  it('a technical error keeps the dialog open and "Tentar de novo" repeats with the same key', async () => {
    const onConfirm = jest
      .fn()
      .mockRejectedValueOnce(new ApiError(503, 'SERVICE_UNAVAILABLE', undefined, 'corr-503'))
      .mockResolvedValueOnce({ botId: 'b1' });
    renderDialog({ onConfirm });
    await userEvent.click(screen.getByTestId('real-confirm-check'));
    await userEvent.click(screen.getByTestId('real-confirm-create'));

    expect(await screen.findByTestId('real-confirm-error')).toHaveTextContent(
      'Não foi possível criar o bot real. Tente de novo.',
    );
    expect(screen.getByTestId('real-confirm-error')).toHaveTextContent('corr-503');
    expect(screen.getByTestId('real-confirm-create')).toHaveTextContent('Tentar de novo');
    await userEvent.click(screen.getByTestId('real-confirm-create'));
    expect(onConfirm).toHaveBeenNthCalledWith(1, 'key-1');
    expect(onConfirm).toHaveBeenNthCalledWith(2, 'key-1');
  });

  it('a lost connection is a technical error too, and Cancelar or Esc close without effect', async () => {
    const props = renderDialog({
      onConfirm: jest.fn(async () => {
        throw new TypeError('Failed to fetch');
      }),
    });
    await userEvent.click(screen.getByTestId('real-confirm-check'));
    await userEvent.click(screen.getByTestId('real-confirm-create'));

    expect(await screen.findByTestId('real-confirm-error')).toHaveTextContent('NETWORK');
    await userEvent.keyboard('{Escape}');
    await userEvent.click(screen.getByTestId('real-confirm-cancel'));
    expect(props.onClose).toHaveBeenCalledTimes(2);
  });

  it('shows the cap of the account only when the screen already read it', () => {
    renderDialog({ cap: '5,00 SOL' });

    expect(screen.getByTestId('real-confirm-cap')).toHaveTextContent(
      'Teto da carteira do bot: 5,00 SOL',
    );
  });
});
