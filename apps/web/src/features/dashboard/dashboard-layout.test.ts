import { readFileSync } from 'node:fs';
import { join } from 'node:path';

/**
 * 261121-paper-real-unico U3 (BR10.1, AC4.5.1; defect of the run 45325572790, smoke "U3 8"): the dashboard "/"
 * scrolled sideways at 360 px. The cards sat in two fixed columns at every width, about 110 px of content each, and a
 * long value or a badge pushed the page. jsdom has no layout, so this guard checks the structure of the stylesheet
 * that prevents it; the end-to-end spec measures the page at 360 px.
 */
const CSS = readFileSync(join(__dirname, '../../app/globals.css'), 'utf8');

/** The bodies of every `@media (max-width: 767px)` block, by brace matching. */
function narrowBlocks(css: string): string {
  const bodies: string[] = [];
  const opener = '@media (max-width: 767px) {';
  let index = css.indexOf(opener);
  while (index >= 0) {
    let depth = 1;
    let cursor = index + opener.length;
    while (depth > 0 && cursor < css.length) {
      if (css[cursor] === '{') depth++;
      if (css[cursor] === '}') depth--;
      cursor++;
    }
    bodies.push(css.slice(index + opener.length, cursor - 1));
    index = css.indexOf(opener, cursor);
  }
  return bodies.join('\n');
}

/** The declarations of a rule whose selector list is exactly `selector`, in the given text. */
function declarationsOf(css: string, selector: string): string {
  const escaped = selector.replace(/[.*+?^${}()|[\]\\>]/g, '\\$&');
  const match = new RegExp(`(?:^|\\})\\s*${escaped}\\s*\\{([^}]*)\\}`, 'm').exec(css);
  return match?.[1] ?? '';
}

/** The stylesheet without the media blocks: the rules that hold at every width. */
function baseRules(css: string): string {
  return css.replace(/@media[^{]*\{(?:[^{}]*\{[^}]*\})*[^{}]*\}/g, '');
}

describe('the layout of the dashboard at 360 px', () => {
  it('stacks the cards in one shrinkable column below 768 px', () => {
    expect(declarationsOf(narrowBlocks(CSS), '.dashboard-cards')).toMatch(
      /grid-template-columns:\s*minmax\(0,\s*1fr\)/,
    );
  });

  it('never lets a card grow past its column, and wraps a long value inside it', () => {
    const card = declarationsOf(baseRules(CSS), '.dashboard-card');
    expect(card).toMatch(/min-width:\s*0/);
    expect(card).toMatch(/overflow-wrap:\s*anywhere/);
    expect(declarationsOf(baseRules(CSS), '.dashboard-cards > *')).toMatch(/min-width:\s*0/);
  });

  it('has no fixed width or nowrap on the classes of the dashboard cards', () => {
    for (const selector of [
      '.dashboard-cards',
      '.dashboard-card',
      '.dashboard-mode-cards',
      '.pnl-modes',
    ]) {
      const rules = declarationsOf(baseRules(CSS), selector);
      expect(rules).not.toMatch(/(^|[^-])width:\s*\d+px/);
      expect(rules).not.toMatch(/white-space:\s*nowrap/);
    }
  });
});
