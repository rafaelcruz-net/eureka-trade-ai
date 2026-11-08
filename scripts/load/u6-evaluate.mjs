// The p95 of OrderGate.evaluate in the two windows of the scenario u6 (NFR2.1) and the rule "with the purge at most
// 10% above" (Build and Test loop 3, F12, choice A1). Until loop 3 the p95 was the upper bound of the Micrometer
// percentile-histogram bucket (..., 16.78, 22.37, 27.96, 33.55 ms, ...): between 10 and 60 ms a bucket is 20% to 33%
// wide, so one bucket more failed the 10% rule by noise and one bucket less hid a real difference. The p95 now comes
// from the samples the load driver keeps (loadtest profile only, microseconds, one per call, health component
// loadtestRisk): the nearest rank, ceil(0.95 * n), of the sorted samples, exact to 1 microsecond. The limits are the
// approved ones and do not change. Pure functions; no database, no network.

export const U6_EVALUATE_LIMITS = Object.freeze({ evaluateS: 0.25, withPurgeRatio: 1.1 });

export const U6_EVALUATE_METHOD =
  'percentil 95 por posição mais próxima (ceil(0,95 × n)) das amostras de cada chamada, em microssegundos, guardadas pelo perfil loadtest';

/** The nearest-rank p95 of samples in microseconds, in seconds; null without samples. */
export function evaluateP95(samplesMicros) {
  if (samplesMicros.length === 0) return null;
  const sorted = [...samplesMicros].sort((a, b) => a - b);
  return sorted[Math.ceil(0.95 * sorted.length) - 1] / 1e6;
}

/** The failures of the evaluate rules over the two windows: 250 ms in each, the second at most 10% above the first. */
export function evaluateWindowFailures(beforePurge, withPurge, limits = U6_EVALUATE_LIMITS) {
  const before = evaluateP95(beforePurge);
  const during = evaluateP95(withPurge);
  return [
    ...(before === null || before > limits.evaluateS ? ['p95 evaluate before the purge'] : []),
    ...(during === null || during > limits.evaluateS ? ['p95 evaluate with the purge'] : []),
    ...(before !== null && during !== null && during > before * limits.withPurgeRatio
      ? ['evaluate with the purge more than 10% above']
      : []),
  ];
}

function samples(values, window) {
  const list = values ?? [];
  if (!Array.isArray(list)) throw new Error(`the samples of ${window} are not a list`);
  for (const value of list) {
    if (!Number.isSafeInteger(value) || value < 0) {
      throw new Error(`a sample of ${window} is not a whole number of microseconds: ${value}`);
    }
  }
  return list;
}

/** The samples of each window from the details of the health component loadtestRisk. */
export function parseEvaluateSamples(details) {
  const windows = details?.evaluateMicros ?? {};
  return {
    beforePurge: samples(windows['before-purge'], 'before-purge'),
    withPurge: samples(windows['with-purge'], 'with-purge'),
  };
}
