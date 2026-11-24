import { ApiError } from '@/lib/api/client';
import { failureCode, paperLoad, realLoad } from '@/features/pnl/by-mode';
import { BY_MODE } from '@/features/pnl/fixtures';

/**
 * 261121-paper-real-unico U3, Etapa 16 (BR3.1, BR3.3): one block of C4 as the screen loads it. The key of the block not
 * asked (C4 with `market`) is absent and is not an error; UNAVAILABLE is an error with its correlation id; a failure of
 * the route gives its correlation id or its code.
 */
describe('the blocks of C4 (U3)', () => {
  it('an absent key is no load at all, never an error', () => {
    expect(paperLoad(undefined)).toBeUndefined();
    expect(realLoad(undefined)).toBeUndefined();
  });

  it('an UNAVAILABLE block is an error with its correlation id', () => {
    expect(
      realLoad({
        status: 'UNAVAILABLE',
        market: 'REAL',
        currency: 'SOL',
        code: 'SERVICE_UNAVAILABLE',
        correlationId: 'corr-real',
      }),
    ).toEqual({ kind: 'error', code: 'corr-real' });
  });

  it('an AVAILABLE block is ready with the block of the API', () => {
    const paper = BY_MODE.paper;
    if (paper === undefined) {
      throw new Error('the fixture has a PAPER block');
    }
    expect(paperLoad(paper)).toEqual({ kind: 'ready', block: paper });
  });

  it('a failure of the route gives its correlation id, its code or NETWORK', () => {
    expect(failureCode(new ApiError(503, 'SERVICE_UNAVAILABLE', undefined, 'corr-1'))).toBe(
      'corr-1',
    );
    expect(failureCode(new ApiError(0, 'TIMEOUT'))).toBe('TIMEOUT');
    expect(failureCode(new Error('offline'))).toBe('NETWORK');
  });
});
