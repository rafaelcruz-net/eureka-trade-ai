import { windowedRefresh, type TimerHandle } from '@/features/dashboard/concurrency';

/** A hand clock and a hand timer, so every window is exact. */
function harness() {
  let at = 0;
  const timers = new Map<number, { due: number; run: () => void }>();
  let next = 1;
  const runs: number[] = [];
  const refresh = windowedRefresh(
    () => {
      runs.push(at);
    },
    {
      windowMs: 30_000,
      now: () => at,
      setTimer: (run, ms) => {
        const id = next++;
        timers.set(id, { due: at + ms, run });
        return id as unknown as TimerHandle;
      },
      clearTimer: (handle) => {
        timers.delete(handle as unknown as number);
      },
    },
  );
  function advance(ms: number) {
    at += ms;
    for (const [id, timer] of [...timers]) {
      if (timer.due <= at) {
        timers.delete(id);
        timer.run();
      }
    }
  }
  return { refresh, runs, advance, timers };
}

/** 261121-paper-real-unico U3, Step 23 (BR7.5, NFR1.14). */
describe('windowedRefresh', () => {
  it('runs at once the first time', () => {
    const { refresh, runs } = harness();

    refresh.trigger();

    expect(runs).toEqual([0]);
    expect(refresh.isPending()).toBe(false);
  });

  it('five triggers within 30 s make two runs: one at once and one at the end of the window', () => {
    const { refresh, runs, advance } = harness();

    refresh.trigger();
    for (let i = 0; i < 4; i++) {
      advance(5_000);
      refresh.trigger();
    }
    expect(runs).toEqual([0]);
    expect(refresh.isPending()).toBe(true);

    advance(10_000);

    expect(runs).toEqual([0, 30_000]);
  });

  it('a trigger after the pending run waits for the next window', () => {
    const { refresh, runs, advance } = harness();
    refresh.trigger();
    advance(1_000);
    refresh.trigger();
    advance(29_000);
    expect(runs).toEqual([0, 30_000]);

    advance(1_000);
    refresh.trigger();

    expect(runs).toEqual([0, 30_000]);
    advance(29_000);
    expect(runs).toEqual([0, 30_000, 60_000]);
  });

  it('a trigger after a quiet window runs at once', () => {
    const { refresh, runs, advance } = harness();
    refresh.trigger();
    advance(45_000);

    refresh.trigger();

    expect(runs).toEqual([0, 45_000]);
  });

  it('dispose clears the pending run and ignores the next triggers', () => {
    const { refresh, runs, advance, timers } = harness();
    refresh.trigger();
    advance(1_000);
    refresh.trigger();

    refresh.dispose();
    advance(60_000);
    refresh.trigger();

    expect(runs).toEqual([0]);
    expect(timers.size).toBe(0);
  });
});
