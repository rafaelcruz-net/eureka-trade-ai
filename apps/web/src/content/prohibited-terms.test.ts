import catalog from '@/content/prohibited-terms.json';
import {
  catalogTexts,
  findProhibited,
  normalize,
  prohibitedTerms,
} from '@/content/prohibited-terms';
import { messages } from '@/lib/i18n/messages';

/** BR5.1, NFR6.8, AC3.2.3: no text of the guest app contains a prohibited term. */
describe('prohibited terms', () => {
  it('the versioned catalog names its version, its reviewer and the four terms of BR5.1', () => {
    expect(catalog.version).toBeGreaterThanOrEqual(1);
    expect(catalog.reviewedBy).toBeTruthy();
    expect(catalog.terms).toEqual(
      expect.arrayContaining(['lucro garantido', 'sem risco', '100% seguro', 'detecta golpes']),
    );
  });

  it('no text of the guest app catalog contains a prohibited term, whatever the case and accents', () => {
    const texts = catalogTexts(messages);

    expect(texts.length).toBeGreaterThan(100);
    expect(findProhibited(texts)).toEqual([]);
  });

  it('a planted text with a term in another case and with accents is found, naming the text and the term', () => {
    const planted = [{ path: 'messages.planted', text: 'Indicador SEM RÍSCO para você' }];

    expect(findProhibited(planted)).toEqual([
      { path: 'messages.planted', text: 'Indicador SEM RÍSCO para você', term: 'sem risco' },
    ]);
  });

  // U3 (BR6.2, NFR6.13): the texts of the invitation entry and of the Account screen, and the versioned terms and
  // risk notice served by the api (apps/api/src/main/resources/terms), contain no prohibited term.
  it('the invitation entry, the Account screen and the versioned terms contain no prohibited term', () => {
    const fs = jest.requireActual<typeof import('node:fs')>('node:fs');
    const path = jest.requireActual<typeof import('node:path')>('node:path');
    const termsRoot = path.join(
      __dirname,
      '..',
      '..',
      '..',
      'api',
      'src',
      'main',
      'resources',
      'terms',
    );
    const versions = fs.readdirSync(termsRoot);
    // u1-legal-documents: every text file of every version (privacy policy, summaries and retention table too).
    const names = [
      'terms.txt',
      'risk-notice.txt',
      'privacy.txt',
      'terms-summary.txt',
      'privacy-summary.txt',
      'retention.tsv',
    ];
    const files = versions.flatMap((version) =>
      names
        .filter((name) => fs.existsSync(path.join(termsRoot, version, name)))
        .map((name) => ({
          path: `terms/${version}/${name}`,
          text: fs.readFileSync(path.join(termsRoot, version, name), 'utf8'),
        })),
    );
    const u3Texts = [
      ...catalogTexts(messages.invite, 'messages.invite'),
      ...catalogTexts(messages.account, 'messages.account'),
    ];

    expect(versions).toEqual(expect.arrayContaining(['v1', 'v2', 'v3']));
    // 261113-trading-real-bot U6, Step 19: v3 (the REAL text, in effect) has all six text files, none of them a draft.
    const v3Files = files.filter(({ path: file }) => file.startsWith('terms/v3/'));
    expect(v3Files.map(({ path: file }) => file).sort()).toEqual(
      names.map((name) => `terms/v3/${name}`).sort(),
    );
    expect(v3Files.every(({ text }) => !text.startsWith('## Versão em rascunho'))).toBe(true);
    // The legacy v1 is provisional; the draft v2 opens with its "Versão em rascunho" section until the legal review.
    expect(
      files
        .filter(({ path: file }) => file.startsWith('terms/v1/'))
        .every(({ text }) => text.startsWith('Versão provisória')),
    ).toBe(true);
    expect(
      files
        .filter(({ path: file }) => /^terms\/v2\/(terms|privacy|risk-notice)\.txt$/.test(file))
        .every(({ text }) => text.startsWith('## Versão em rascunho')),
    ).toBe(true);
    expect(u3Texts.length).toBeGreaterThan(40);
    expect(findProhibited([...files, ...u3Texts])).toEqual([]);
  });

  it('the level of risk never uses the word "seguro"', () => {
    const levels = catalogTexts(messages.scanner.riskBand, 'riskBand');

    expect(levels.every(({ text }) => !normalize(text).includes('seguro'))).toBe(true);
    expect(prohibitedTerms().length).toBeGreaterThanOrEqual(4);
  });
});
