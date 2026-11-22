/**
 * 261121-paper-real-unico U1 (C17; interaction-spec "SimulatedBadge", BR9.1): the label of the simulated mode: an icon
 * hidden from the screen reader, an outline without fill and the text SIMULADO, so color is never the only signal.
 * Display only: it takes no focus and carries no monetary value. The pair of the RealBadge.
 */
export function SimulatedBadge({ testId = 'simulated-badge' }: { testId?: string }) {
  return (
    <span className="simulated-badge" data-testid={testId}>
      <span aria-hidden="true" className="simulated-badge-icon">
        ◇
      </span>{' '}
      SIMULADO
    </span>
  );
}
