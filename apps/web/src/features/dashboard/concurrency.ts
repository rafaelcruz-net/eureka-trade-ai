/**
 * Small schedulers of the dashboard calls (performance-design.md, NFR3.3, NFR3.4).
 */

/** Runs at most {@code limit} tasks at the same time; the others wait in order (NFR3.3: last orders, 3 at a time). */
export function createLimiter(limit: number) {
  if (limit < 1) {
    throw new Error('The limit must be at least 1');
  }
  let running = 0;
  const waiting: Array<() => void> = [];

  function next() {
    if (running >= limit) {
      return;
    }
    const start = waiting.shift();
    if (start) {
      running += 1;
      start();
    }
  }

  return {
    run<T>(task: () => Promise<T>): Promise<T> {
      return new Promise<T>((resolve, reject) => {
        waiting.push(() => {
          task()
            .then(resolve, reject)
            .finally(() => {
              running -= 1;
              next();
            });
        });
        next();
      });
    },
  };
}

export type TimerHandle = ReturnType<typeof setTimeout>;

export interface WindowedRefreshOptions {
  /** The window: at most one run in it (30 s for the cards by mode, BR7.5). */
  windowMs?: number;
  setTimer?: (run: () => void, ms: number) => TimerHandle;
  clearTimer?: (handle: TimerHandle) => void;
  /** The clock in milliseconds; injectable for the tests. */
  now?: () => number;
}

/**
 * 261121-paper-real-unico U3 (BR7.5, NFR1.7, NFR1.14): "at most one run per window, and nothing lost". A trigger with
 * no run in the last `windowMs` runs at once; a trigger inside the window marks ONE pending run at the end of the
 * window (more triggers meanwhile join it); a trigger after that pending run waits for the next window. `dispose`
 * cancels the pending run and ignores every later trigger.
 */
export function windowedRefresh(
  run: () => void | Promise<void>,
  {
    windowMs = 30_000,
    setTimer = (callback, ms) => setTimeout(callback, ms),
    clearTimer = (handle) => clearTimeout(handle),
    now = () => Date.now(),
  }: WindowedRefreshOptions = {},
) {
  let lastRun: number | null = null;
  let timer: TimerHandle | null = null;
  let disposed = false;

  function fire() {
    timer = null;
    lastRun = now();
    void run();
  }

  return {
    trigger(): void {
      if (disposed || timer !== null) {
        return;
      }
      const at = now();
      if (lastRun === null || at - lastRun >= windowMs) {
        fire();
        return;
      }
      timer = setTimer(fire, lastRun + windowMs - at);
    },
    /** Whether a run waits for the end of the window. */
    isPending(): boolean {
      return timer !== null;
    },
    dispose(): void {
      disposed = true;
      if (timer !== null) {
        clearTimer(timer);
        timer = null;
      }
    },
  };
}

/**
 * "One in progress, at most one pending" (BR3.3, NFR3.4, BR1.5): a trigger while the task runs only marks it pending,
 * and every trigger that arrived meanwhile becomes ONE more run after it. Failures are the task's own business: it
 * reports them itself, so a rejected run never stops the next one.
 */
export function singleFlight(task: () => Promise<void>) {
  let running = false;
  let pending = false;

  async function loop() {
    running = true;
    try {
      do {
        pending = false;
        await task();
      } while (pending);
    } finally {
      running = false;
    }
  }

  return {
    trigger(): Promise<void> | undefined {
      if (running) {
        pending = true;
        return undefined;
      }
      return loop();
    },
    isRunning(): boolean {
      return running;
    },
  };
}
