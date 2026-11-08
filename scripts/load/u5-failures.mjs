// Pure builder of the failures of the U5 load run of scripts/load/load-test.mjs (NFR2.1 to NFR2.3, NFR3.1 to NFR3.3):
// the names of the limits the run broke, from the report already measured. No I/O here, so the node --test regression
// (scripts/load/load-test-u5-slices.test.mjs) imports it without running the load script. The limits come from the
// caller and never change here.

/**
 * The failure of an empty barrier timer (loadtest_bots_gate_seconds without a sample): no BUY or SELL reached the
 * barrier, so NFR2.3 of U5 (and NFR2.2 of U6) could not be measured. It is a failure, never a pass.
 */
export const NO_ORDER_AT_GATE = 'nenhuma ordem chegou à barreira';

/** The failures of the run, in the order of the report; an empty list means the run passed. */
export function u5Failures(report, limits, memoryLimitMb) {
  const db = report.database;
  return [
    ...[
      ['p95 cycle', report.cycle, limits.cycleS],
      ['p95 barrier plus execution', report.gate, limits.gateS, NO_ORDER_AT_GATE],
      ['p95 S5 screen', report.screen, limits.screenS],
      ['p95 live channel', report.live, limits.liveS],
      ...Object.entries(report.routes).map(([route, p95]) => [`p95 ${route}`, p95, limits.routesS]),
    ]
      .filter(([, value, limit]) => value === null || value > limit)
      .map(([name, value, , whenEmpty]) => (value === null && whenEmpty ? whenEmpty : name)),
    ...(db.checked && db.staleDecisions > 0 ? ['decisions for stale data'] : []),
    ...(db.checked && db.slices < db.slicesExpected ? ['fewer completed slices than expected'] : []),
    ...(report.stopwatch.failed > 0 ? ['failed cycles'] : []),
    ...(report.workerPool.maxActive > limits.maxConnections ? ['worker pool above 14 connections'] : []),
    ...(report.workerPool.maxAcquireS > limits.acquireS ? ['worker pool wait above 1 s'] : []),
    ...(report.pool.longestPendingSeconds > 10 ? ['api pool wait'] : []),
    ...(report.errors > 0 ? ['client errors'] : []),
    ...Object.entries(report.maxMemoryMb)
      .filter(([, mb]) => mb > memoryLimitMb)
      .map(([name]) => `memory ${name}`),
  ];
}

/** The line of the report with the BUY and SELL decisions created during the run (counted by the database check). */
export function u5DecisionsLine(database) {
  if (!database?.checked) return '- Decisões durante a carga: não conferido';
  return `- Decisões durante a carga: BUY ${database.buyDecisions}, SELL ${database.sellDecisions} (sem elas, a barreira não tem o que medir)`;
}
