import catalog from '@/content/prohibited-terms.json';

/** One text of the interface that contains a prohibited term. */
export interface Violation {
  path: string;
  text: string;
  term: string;
}

/** Lower case without accents (NFD without combining marks), so "Sem Risco" and "sem risco" match the same term. */
export function normalize(value: string): string {
  return value.normalize('NFD').replace(/[̀-ͯ]/g, '').toLowerCase();
}

/** Every term checked: the four of BR5.1 and the proposed ones, waiting for the review of the project owner. */
export function prohibitedTerms(): string[] {
  return [...catalog.terms, ...catalog.proposedTerms];
}

const SAMPLE_ARGUMENTS = [1, 2, 3];

/**
 * Every text of a catalog of messages with its path: strings as they are, functions called with sample arguments
 * (numbers, which every text function of the catalog accepts as numbers or as strings).
 */
export function catalogTexts(
  node: unknown,
  path = 'messages',
): Array<{ path: string; text: string }> {
  if (typeof node === 'string') {
    return [{ path, text: node }];
  }
  if (typeof node === 'function') {
    const produced = (node as (...args: unknown[]) => unknown)(...SAMPLE_ARGUMENTS);
    return typeof produced === 'string' ? [{ path, text: produced }] : [];
  }
  if (node && typeof node === 'object') {
    return Object.entries(node as Record<string, unknown>).flatMap(([key, value]) =>
      catalogTexts(value, `${path}.${key}`),
    );
  }
  return [];
}

/** BR5.1: every text that contains a prohibited term, with the term; empty when the catalog is clean. */
export function findProhibited(
  texts: Array<{ path: string; text: string }>,
  terms: string[] = prohibitedTerms(),
): Violation[] {
  const normalizedTerms = terms.map((term) => ({ term, normalized: normalize(term) }));
  return texts.flatMap(({ path, text }) =>
    normalizedTerms
      .filter(({ normalized }) => normalize(text).includes(normalized))
      .map(({ term }) => ({ path, text, term })),
  );
}
