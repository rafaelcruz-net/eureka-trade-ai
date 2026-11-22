/**
 * @jest-environment node
 */
import { execFileSync } from 'node:child_process';
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join, relative, resolve } from 'node:path';

/**
 * Passo 10 of U9 (NFR6.2, NFR4.2; BR3.5, BR5.1): the lint rules of `eslint.config.mjs` run on sample snippets placed
 * (by name only, through stdin) in the folders they guard: EventSource is refused in every folder of `src`, also in
 * the blocks that redefine the same rules (P&L and bots), and the dashboard refuses `Number(`, `parseFloat(` and unary
 * plus. Plus a static search that no source file of the app uses EventSource.
 */
const ROOT = resolve(__dirname, '..', '..', '..');
const ESLINT = join(ROOT, 'node_modules', 'eslint', 'bin', 'eslint.js');

interface LintMessage {
  ruleId: string | null;
  message: string;
}

function lint(filePath: string, code: string): LintMessage[] {
  let output: string;
  try {
    output = execFileSync(
      process.execPath,
      [ESLINT, '--stdin', '--stdin-filename', filePath, '--format', 'json'],
      { cwd: ROOT, input: code, encoding: 'utf8' },
    );
  } catch (failure) {
    // ESLint exits with 1 when it finds problems; the report is still on stdout.
    output = (failure as { stdout?: string }).stdout ?? '';
    if (!output) {
      throw failure;
    }
  }
  const [report] = JSON.parse(output) as Array<{ messages: LintMessage[] }>;
  return report?.messages ?? [];
}

const EVENT_SOURCE = "export const channel = new EventSource('/api/v1/stream/dashboard');\n";
const WINDOW_EVENT_SOURCE = "export const other = new window.EventSource('/x');\n";
const FLOATS = [
  "export const a = Number('1.5');",
  "export const b = parseFloat('1.5');",
  "const text = '2.5';",
  'export const c = +text;',
  '',
].join('\n');

function eventSourceMessages(messages: LintMessage[]): LintMessage[] {
  return messages.filter((entry) => entry.message.includes('never EventSource'));
}

function floatMessages(messages: LintMessage[]): LintMessage[] {
  return messages.filter(
    (entry) =>
      entry.message.includes('DecimalString values stay text') ||
      entry.message.includes('Unary plus'),
  );
}

describe('lint rules of the dashboard', () => {
  jest.setTimeout(120_000);

  it.each([
    'src/features/dashboard/sample.ts',
    'src/features/pnl/sample.ts',
    'src/features/bots/sample.ts',
    'src/app/pnl/sample.ts',
    'src/lib/sample.ts',
  ])('refuses EventSource in %s', (file) => {
    const messages = lint(file, EVENT_SOURCE + WINDOW_EVENT_SOURCE);

    expect(eventSourceMessages(messages).length).toBeGreaterThanOrEqual(2);
  });

  it('refuses Number(, parseFloat( and unary plus in the dashboard', () => {
    const messages = floatMessages(lint('src/features/dashboard/format-sample.ts', FLOATS));

    expect(messages.map((entry) => entry.ruleId).sort()).toEqual([
      'no-restricted-globals',
      'no-restricted-syntax',
      'no-restricted-syntax',
    ]);
  });

  it('keeps the decimal ban of the P&L and bots folders and leaves other folders alone', () => {
    expect(floatMessages(lint('src/features/pnl/format-sample.ts', FLOATS))).toHaveLength(3);
    expect(floatMessages(lint('src/features/bots/format-sample.ts', FLOATS))).toHaveLength(3);
    expect(floatMessages(lint('src/lib/format-sample.ts', FLOATS))).toHaveLength(0);
  });

  // 261121-paper-real-unico U1 (BR7.5, ADR-010, C17): the P&L folder refuses Number(, toFixed, parseFloat and any
  // bigint; lamports are formatted only by features/real/money.ts.
  it('refuses Number(, toFixed, parseFloat and bigint in the P&L folder', () => {
    const code = [
      "export const a = Number('1.5');",
      "export const b = parseFloat('1.5');",
      'export const c = (1.5).toFixed(2);',
      "export const d = BigInt('31200000');",
      'export const e = 10n;',
      '',
    ].join('\n');

    const messages = lint('src/features/pnl/money-sample.ts', code);

    expect(messages.filter((entry) => entry.message.includes('stay text'))).toHaveLength(2);
    expect(messages.filter((entry) => entry.message.includes('toFixed'))).toHaveLength(1);
    expect(
      messages.filter((entry) => entry.message.includes('features/real/money.ts')),
    ).toHaveLength(2);
    expect(
      lint('src/features/real/money.ts', "export const d = BigInt('1');\n").filter((entry) =>
        entry.message.includes('features/real/money.ts'),
      ),
    ).toHaveLength(0);
  });

  it('no source file of the app uses EventSource', () => {
    const files = (function walk(dir: string): string[] {
      return readdirSync(dir).flatMap((name) => {
        const path = join(dir, name);
        return statSync(path).isDirectory() ? walk(path) : [path];
      });
    })(join(ROOT, 'src')).filter((file) => /\.(ts|tsx)$/.test(file) && !/\.test\.tsx?$/.test(file));

    // A use (construction or reference to the global), not the word in a comment that forbids it.
    const use =
      /new\s+(?:window\.|globalThis\.)?EventSource\b|(?:window|globalThis)\.EventSource\b/;
    const offenders = files.filter((file) => use.test(readFileSync(file, 'utf8')));

    expect(files.length).toBeGreaterThan(50);
    expect(offenders.map((file) => relative(ROOT, file))).toEqual([]);
  });
});
