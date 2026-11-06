import { colors, contrastRatio, luminance, textPairs } from '@/lib/theme/tokens';

describe('dark theme contrast (NFR11.1, R-05 of the NFR Design)', () => {
  it.each(textPairs)('%s on %s keeps a contrast of at least 4.5:1', (text, background) => {
    expect(contrastRatio(colors[text], colors[background])).toBeGreaterThanOrEqual(4.5);
  });

  it('computes the WCAG ratio of black on white as 21:1', () => {
    expect(contrastRatio('#000000', '#ffffff')).toBeCloseTo(21, 5);
    expect(luminance('#ffffff')).toBeCloseTo(1, 5);
  });

  it('refuses a color that is not #rrggbb', () => {
    expect(() => luminance('red')).toThrow('Not a #rrggbb color');
  });
});
