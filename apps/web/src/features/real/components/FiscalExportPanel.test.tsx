import { act, fireEvent, render, screen } from '@testing-library/react';
import type { GuestApi } from '@/lib/api/guest-api';
import { tokenStore } from '@/lib/auth/token-store';
import { settle } from '@/features/dashboard/test-support';
import {
  daysBetween,
  defaultPeriod,
  FiscalExportPanel,
} from '@/features/real/components/FiscalExportPanel';
import { realMessages as text } from '@/features/real/messages';
import { browserStorageText, problem, RealProviders, realApi } from '@/features/real/test-support';

/**
 * 261113-trading-real-bot U7, Step 14, written after the code (BR7.7, BR7.9, BR8.4; S28, S31, S32): the period in two
 * dates (the last 12 months by default, at most 1 year, never inverted), the CSV downloaded from the route with the
 * name of the period, and nothing kept by the browser.
 */
const NOW = new Date('2026-11-17T12:00:00Z');

async function renderPanel(api: jest.Mocked<GuestApi>, save = jest.fn()) {
  tokenStore.set('access-token-value');
  render(
    <RealProviders api={api}>
      <FiscalExportPanel api={api} timeZone="America/Sao_Paulo" now={() => NOW} save={save} />
    </RealProviders>,
  );
  await act(async () => settle(5));
  return save;
}

afterEach(() => tokenStore.clear());

describe('FiscalExportPanel', () => {
  it('the default period is the last 365 days ending today, in the zone of the person', () => {
    expect(defaultPeriod(NOW, 'America/Sao_Paulo')).toEqual({
      from: '2025-11-17',
      to: '2026-11-17',
    });
    expect(daysBetween('2026-01-01', '2027-01-01')).toBe(365);
  });

  it('S28: exports the period and hands the file to the download with its name, keeping nothing', async () => {
    const file = new Blob(['﻿data_utc;...'], { type: 'text/csv' });
    const api = realApi({ exportRealFiscal: jest.fn(async () => file) });
    const save = await renderPanel(api);

    await act(async () => {
      fireEvent.click(screen.getByTestId('real-fiscal-button'));
    });
    await act(async () => settle(5));

    expect(api.exportRealFiscal).toHaveBeenCalledWith({
      from: '2025-11-17',
      to: '2026-11-17',
      tz: 'America/Sao_Paulo',
    });
    expect(save).toHaveBeenCalledWith(file, 'historico-real-2025-11-17-2026-11-17.csv');
    expect(screen.getByTestId('real-fiscal-status')).toHaveTextContent(text.fiscal.done);
    expect(browserStorageText()).not.toContain('data_utc');
  });

  it('S31: more than one year or an inverted period is refused before calling the server', async () => {
    const api = realApi({ exportRealFiscal: jest.fn() });
    await renderPanel(api);

    fireEvent.change(screen.getByTestId('real-fiscal-from'), { target: { value: '2025-01-01' } });
    fireEvent.change(screen.getByTestId('real-fiscal-to'), { target: { value: '2026-01-02' } });
    expect(screen.getByTestId('real-fiscal-problem')).toHaveTextContent(text.fiscal.tooLong);
    fireEvent.change(screen.getByTestId('real-fiscal-to'), { target: { value: '2024-12-31' } });
    expect(screen.getByTestId('real-fiscal-problem')).toHaveTextContent(text.fiscal.inverted);
    expect(screen.getByTestId('real-fiscal-button')).toBeDisabled();
    expect(api.exportRealFiscal).not.toHaveBeenCalled();
  });

  it('S32: the eleventh export in the hour says the limit in text', async () => {
    const api = realApi({
      exportRealFiscal: jest.fn(async () => {
        throw problem(429, 'RATE_LIMITED');
      }),
    });
    const save = await renderPanel(api);

    await act(async () => {
      fireEvent.click(screen.getByTestId('real-fiscal-button'));
    });
    await act(async () => settle(5));

    expect(screen.getByTestId('real-fiscal-alert')).toHaveTextContent(text.errors.RATE_LIMITED);
    expect(save).not.toHaveBeenCalled();
  });

  it('the default download creates an object URL, clicks it and revokes it at once', async () => {
    const created = jest.fn(() => 'blob:fake');
    const revoked = jest.fn();
    Object.assign(URL, { createObjectURL: created, revokeObjectURL: revoked });
    // jsdom does not navigate; the click of the link is the download of a browser.
    const clicked = jest
      .spyOn(HTMLAnchorElement.prototype, 'click')
      .mockImplementation(() => undefined);
    const api = realApi({ exportRealFiscal: jest.fn(async () => new Blob(['x'])) });
    tokenStore.set('access-token-value');
    render(
      <RealProviders api={api}>
        <FiscalExportPanel api={api} timeZone="UTC" now={() => NOW} />
      </RealProviders>,
    );

    await act(async () => {
      fireEvent.click(screen.getByTestId('real-fiscal-button'));
    });
    await act(async () => settle(5));

    expect(created).toHaveBeenCalled();
    expect(clicked).toHaveBeenCalledTimes(1);
    expect(revoked).toHaveBeenCalledWith('blob:fake');
    clicked.mockRestore();
  });
});
