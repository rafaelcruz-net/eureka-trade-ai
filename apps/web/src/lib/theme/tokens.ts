/**
 * Dark theme color tokens (NFR11.1, CT-UX-7). Every text color must keep a contrast of at least 4.5:1 against the
 * backgrounds it is used on; tokens.test.ts enforces it. Colors are never the only indicator of a state.
 */
export const colors = {
  background: '#0f1115',
  surface: '#171a21',
  surfaceRaised: '#1f232c',
  text: '#e8eaed',
  textMuted: '#b3b9c4',
  accent: '#8ab4ff',
  focus: '#ffd166',
  danger: '#ff8a80',
  warning: '#ffcc66',
  unknown: '#c9b8ff',
  border: '#3a404c',
} as const;

export type ColorToken = keyof typeof colors;

/** Text tokens and the backgrounds they appear on. */
export const textPairs: ReadonlyArray<[ColorToken, ColorToken]> = [
  ['text', 'background'],
  ['text', 'surface'],
  ['text', 'surfaceRaised'],
  ['textMuted', 'background'],
  ['textMuted', 'surface'],
  ['textMuted', 'surfaceRaised'],
  ['accent', 'surface'],
  ['danger', 'surface'],
  ['warning', 'surface'],
  ['unknown', 'surface'],
  ['focus', 'surface'],
];

function channel(value: number): number {
  const c = value / 255;
  return c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4;
}

/** WCAG 2.1 relative luminance of a #rrggbb color. */
export function luminance(hex: string): number {
  const match = /^#([0-9a-f]{2})([0-9a-f]{2})([0-9a-f]{2})$/i.exec(hex);
  if (!match) {
    throw new Error(`Not a #rrggbb color: ${hex}`);
  }
  const [r, g, b] = [match[1], match[2], match[3]].map((h) => channel(parseInt(h as string, 16)));
  return 0.2126 * (r as number) + 0.7152 * (g as number) + 0.0722 * (b as number);
}

/** WCAG 2.1 contrast ratio between two colors. */
export function contrastRatio(foreground: string, background: string): number {
  const [light, dark] = [luminance(foreground), luminance(background)].sort((a, b) => b - a);
  return ((light as number) + 0.05) / ((dark as number) + 0.05);
}
