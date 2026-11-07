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
