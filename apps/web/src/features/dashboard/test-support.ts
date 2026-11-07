import type {
  BotPage,
  BotSummary,
  PaperTrade,
  PnlReport,
  ReconciliationResult,
  SourceStatus,
} from '@/lib/api/guest-api';
import type { TradeNotice } from '@/features/dashboard/envelopes';

/**
 * Test utilities of the dashboard (Passo 2 of U9): typed factories built from the generated contract types (a test of
 * an error starts from a valid value and changes only the field under test), a controllable live channel (a body that
 * delivers chunks when the test says so, split anywhere, even in the middle of a character), a fake `fetch` that
 * records every request, and the fake clock of Jest. Ids and mints are synthetic, never real ones.
 */
export const START = new Date('2026-10-28T12:00:00Z');
export const ZONE = 'America/Sao_Paulo';

export const BOT_A = '0b0e0000-0000-4000-8000-00000000000a';
export const BOT_B = '0b0e0000-0000-4000-8000-00000000000b';
export const BOT_C = '0b0e0000-0000-4000-8000-00000000000c';
export const MINT = 'TestMint111111111111111111111111111111111111';

export function botSummary(overrides: Partial<BotSummary> = {}): BotSummary {
  return {
    botId: BOT_A,
    name: 'Alfa',
    mode: 'PAPER',
    state: 'ACTIVE',
    pauseReasons: [],
    templateId: 'MOMENTUM_VOLUME',
    updatedAt: '2026-10-28T11:00:00Z',
    ...overrides,
  };
}

export function botPage(items: BotSummary[], overrides: Partial<BotPage> = {}): BotPage {
  return {
    items,
    nextCursor: null,
    activeCount: items.filter((bot) => bot.state === 'ACTIVE').length,
    activeLimit: 3,
    ...overrides,
  };
}

export function paperTrade(overrides: Partial<PaperTrade> = {}): PaperTrade {
  return {
    orderId: '0dde0000-0000-4000-8000-000000000001',
    idempotencyKey: '0dde0000-0000-4000-8000-0000000000ff',
    kind: 'TRADE',
    mode: 'PAPER',
    side: 'BUY',
    chain: 'solana',
    tokenMint: MINT,
    status: 'FILLED',
    requestedQuantity: '10.000000',
    limitsVersion: 1,
    createdAt: '2026-10-28T11:30:00Z',
    ...overrides,
  };
}

export function tradeNotice(overrides: Partial<TradeNotice> = {}): TradeNotice {
  return {
    orderId: '0dde0000-0000-4000-8000-000000000002',
    botId: BOT_A,
    side: 'SELL',
    chain: 'solana',
    tokenMint: MINT,
    status: 'NOT_FILLED',
    createdAt: '2026-10-28T11:45:00Z',
    ...overrides,
  };
}

export function pnlReport(overrides: Partial<PnlReport> = {}): PnlReport {
  return {
    from: '2026-10-28T00:00:00Z',
    to: '2026-10-28T12:00:00Z',
    realized: '12.500000',
    unrealized: { status: 'KNOWN', value: '-3.250000' },
    fees: '0.750000',
    partial: false,
    empty: false,
    unrealizedAsOf: '2026-10-28T12:00:00Z',
    byToken: [],
    ...overrides,
  };
}

export function reconciliation(
  overrides: Partial<ReconciliationResult> = {},
): ReconciliationResult {
  return {
    status: 'RECONCILED',
    trigger: 'SCHEDULED',
    checkedAt: '2026-10-28T11:50:00Z',
    divergenceCount: 0,
    divergences: [],
    ...overrides,
  };
}

export function sourceStatus(overrides: Partial<SourceStatus> = {}): SourceStatus {
  return {
    source: { sourceId: 'source-a', name: 'Fonte A', simulated: true },
    status: 'OK',
    lastUpdatedAt: '2026-10-28T11:59:30Z',
    ...overrides,
  };
}

/** One `text/event-stream` block. */
export function sseBlock(event: string, data: unknown, id?: string): string {
  const payload = typeof data === 'string' ? data : JSON.stringify(data);
  return `${id === undefined ? '' : `id: ${id}\n`}event: ${event}\ndata: ${payload}\n\n`;
}

const encoder = new TextEncoder();

/** A live channel the test drives: `push` text or bytes, `end` the body, or `fail` the reading. */
export function fakeChannel() {
  const chunks: Array<{ value?: Uint8Array; done: boolean; error?: Error }> = [];
  const waiting: Array<{
    resolve: (result: { value?: Uint8Array; done: boolean }) => void;
    reject: (error: Error) => void;
  }> = [];
  let cancelled = false;

  function deliver(item: { value?: Uint8Array; done: boolean; error?: Error }) {
    const reader = waiting.shift();
    if (!reader) {
      chunks.push(item);
      return;
    }
    if (item.error) {
      reader.reject(item.error);
    } else {
      reader.resolve({ value: item.value, done: item.done });
    }
  }

  const body = {
    getReader() {
      return {
        read(): Promise<{ value?: Uint8Array; done: boolean }> {
          const next = chunks.shift();
          if (next) {
            return next.error ? Promise.reject(next.error) : Promise.resolve(next);
          }
          return new Promise((resolve, reject) => waiting.push({ resolve, reject }));
        },
        cancel(): Promise<void> {
          cancelled = true;
          return Promise.resolve();
        },
      };
    },
  };

  return {
    response: response(200, { 'Content-Type': 'text/event-stream' }, body),
    push(text: string) {
      deliver({ value: encoder.encode(text), done: false });
    },
    pushBytes(bytes: Uint8Array) {
      deliver({ value: bytes, done: false });
    },
    end() {
      deliver({ done: true });
    },
    fail(error: Error = new TypeError('network error')) {
      deliver({ done: false, error });
    },
    get cancelled() {
      return cancelled;
    },
  };
}

export type FakeChannel = ReturnType<typeof fakeChannel>;

/** A Response-like answer with only what the client reads. */
export function response(
  status: number,
  headers: Record<string, string> = {},
  body: unknown = null,
) {
  const lower = Object.fromEntries(
    Object.entries(headers).map(([name, value]) => [name.toLowerCase(), value]),
  );
  return {
    status,
    ok: status >= 200 && status < 300,
    headers: { get: (name: string) => lower[name.toLowerCase()] ?? null },
    body,
  } as unknown as Response;
}

export interface RecordedRequest {
  url: string;
  headers: Record<string, string>;
  init: RequestInit;
}

/**
 * A fake `fetch`: each call takes the next queued answer (a Response, an error to throw, or a pending one the test
 * resolves later); every request is recorded with its headers.
 */
export function fakeFetch() {
  const queue: Array<Response | Error | Promise<Response>> = [];
  const requests: RecordedRequest[] = [];
  const impl = jest.fn(async (input: RequestInfo | URL, init: RequestInit = {}) => {
    requests.push({
      url: String(input),
      headers: { ...(init.headers as Record<string, string>) },
      init,
    });
    const next = queue.shift();
    if (next === undefined) {
      return new Promise<Response>(() => undefined);
    }
    if (next instanceof Error) {
      throw next;
    }
    return next;
  });
  return {
    impl: impl as unknown as typeof fetch,
    requests,
    respond(...answers: Array<Response | Error | Promise<Response>>) {
      queue.push(...answers);
    },
  };
}

/** Lets pending promises (fetch answers, chunk reads) settle, with the fake clock frozen. */
export async function settle(rounds = 10): Promise<void> {
  for (let i = 0; i < rounds; i++) {
    await Promise.resolve();
  }
}

/** Jest's fake clock starting at {@link START}. */
export function startFakeClock(start: Date = START) {
  jest.useFakeTimers({ now: start });
  return {
    async advance(ms: number) {
      await jest.advanceTimersByTimeAsync(ms);
    },
    now: () => new Date(Date.now()),
  };
}
