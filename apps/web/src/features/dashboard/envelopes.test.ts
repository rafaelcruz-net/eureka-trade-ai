import { parseEnvelope } from '@/features/dashboard/envelopes';
import { botSummary, sourceStatus, tradeNotice } from '@/features/dashboard/test-support';
import { dashboardWarn, isEventKind } from '@/features/dashboard/warn';

/** Passo 6 of U9 (BR5.2, NFR6.5): every envelope of C7 is checked field by field against the generated types. */
describe('parseEnvelope', () => {
  it('accepts each of the six envelopes of the contract', () => {
    expect(
      parseEnvelope('bot.state', JSON.stringify({ items: [botSummary()], activeCount: 1 })),
    ).toEqual({ kind: 'bot.state', data: { items: [botSummary()], activeCount: 1 } });
    expect(parseEnvelope('trade.recorded', JSON.stringify({ items: [tradeNotice()] }))).toEqual({
      kind: 'trade.recorded',
      data: { items: [tradeNotice()] },
    });
    expect(parseEnvelope('pnl.updated', '{"ledgerMark":42}')).toEqual({
      kind: 'pnl.updated',
      data: { ledgerMark: 42 },
    });
    expect(parseEnvelope('data.status', JSON.stringify({ items: [sourceStatus()] }))).toEqual({
      kind: 'data.status',
      data: { items: [sourceStatus()] },
    });
    expect(parseEnvelope('heartbeat', '{"at":"2026-10-28T12:00:15Z"}')?.kind).toBe('heartbeat');
    expect(parseEnvelope('auth.expired', '{"expiredAt":"2026-10-28T12:15:00Z"}')?.kind).toBe(
      'auth.expired',
    );
  });

  it('drops the fields the contract does not declare and turns a null time into an absent one', () => {
    const parsed = parseEnvelope(
      'data.status',
      JSON.stringify({
        items: [{ ...sourceStatus(), lastUpdatedAt: null, html: '<b>x</b>' }],
        extra: true,
      }),
    );

    expect(parsed).toEqual({
      kind: 'data.status',
      data: { items: [{ source: sourceStatus().source, status: 'OK' }] },
    });
  });

  it.each([
    ['bot.state', '{"items":[],"activeCount":"1"}'],
    [
      'bot.state',
      JSON.stringify({ items: [botSummary({ state: 'GONE' as never })], activeCount: 0 }),
    ],
    [
      'bot.state',
      JSON.stringify({ items: [botSummary({ pauseReasons: ['NOPE' as never] })], activeCount: 0 }),
    ],
    ['bot.state', JSON.stringify({ items: {}, activeCount: 0 })],
    ['trade.recorded', JSON.stringify({ items: [tradeNotice({ side: 'HOLD' as never })] })],
    ['pnl.updated', '{"ledgerMark":1.5}'],
    [
      'data.status',
      JSON.stringify({ items: [{ ...sourceStatus(), source: { sourceId: 'a', name: 'A' } }] }),
    ],
    ['heartbeat', '{"at":15}'],
    ['auth.expired', '[]'],
    ['heartbeat', 'null'],
    ['heartbeat', '{broken'],
  ] as const)('discards an invalid %s envelope', (kind, data) => {
    expect(parseEnvelope(kind, data)).toBeNull();
  });
});

describe('dashboardWarn', () => {
  afterEach(() => jest.restoreAllMocks());

  it('prints only the kind and the reason of the closed list', () => {
    const warn = jest.spyOn(console, 'warn').mockImplementation(() => undefined);

    dashboardWarn('pnl.updated', 'INVALID_ENVELOPE');

    expect(warn).toHaveBeenCalledWith('Dashboard live channel: pnl.updated INVALID_ENVELOPE');
    expect(isEventKind('heartbeat')).toBe(true);
    expect(isEventKind('message')).toBe(false);
  });
});
