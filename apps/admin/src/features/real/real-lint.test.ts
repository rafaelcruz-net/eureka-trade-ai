/**
 * @jest-environment node
 */
import { execFileSync } from 'node:child_process';
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join, relative, resolve } from 'node:path';

/**
 * 261113-trading-real-bot U6, Step 1 for apps/admin, written after the configuration (U6-NFR1.1b, U6-NFR1.2, U6-NFR1.3, U6-NFR1.5,
 * U6-NFR1.9, U6-NFR7.3, U6-NFR8.6): the lint block of the REAL folders refuses each forbidden sample, placed (by name
 * only, through stdin) in the folders it guards; the bans that already existed stay; money.ts is the only named
 * exception; the dependencies of the app are an allowed list with no Solana SDK nor transaction library; and no new
 * NEXT_PUBLIC_* variable exists.
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
      {
        cwd: ROOT,
        input: code,
        encoding: 'utf8',
      },
    );
  } catch (failure) {
    output = (failure as { stdout?: string }).stdout ?? '';
    if (!output) {
      throw failure;
    }
  }
  const [report] = JSON.parse(output) as Array<{ messages: LintMessage[] }>;
  return report?.messages ?? [];
}

function rules(messages: LintMessage[]): string[] {
  return messages.map((entry) => entry.ruleId ?? '').filter((id) => id !== '');
}

const SAMPLES: Record<string, { code: string; rule: string }> = {
  'transaction library': {
    code: "import { Transaction } from '@solana/web3.js';\nexport const t = Transaction;\n",
    rule: 'no-restricted-imports',
  },
  'kit library': {
    code: "import * as kit from '@solana/kit';\nexport const k = kit;\n",
    rule: 'no-restricted-imports',
  },
  sendTransaction: {
    code: 'export async function go(c: { sendTransaction(): void }) {\n  c.sendTransaction();\n}\n',
    rule: 'no-restricted-syntax',
  },
  'Number(': { code: "export const n = Number('1.5');\n", rule: 'no-restricted-syntax' },
  parseInt: { code: "export const n = parseInt('15', 10);\n", rule: 'no-restricted-globals' },
  'Math.round': { code: 'export const n = Math.round(1.5);\n', rule: 'no-restricted-properties' },
  localStorage: {
    code: "localStorage.setItem('k', 'v');\nexport {};\n",
    rule: 'no-restricted-globals',
  },
  'document.cookie': {
    code: "document.cookie = 'k=v';\nexport {};\n",
    rule: 'no-restricted-properties',
  },
  console: { code: "console.warn('x');\nexport {};\n", rule: 'no-restricted-globals' },
  dangerouslySetInnerHTML: {
    code: 'export const C = ({ t }: { t: string }) => <p dangerouslySetInnerHTML={{ __html: t }} />;\n',
    rule: 'react/no-danger',
  },
};

describe('lint of the REAL folders', () => {
  jest.setTimeout(180_000);

  it.each(Object.entries(SAMPLES))('refuses %s in src/features/real', (_name, sample) => {
    const file = sample.code.includes('<p')
      ? 'src/features/real/sample.tsx'
      : 'src/features/real/sample.ts';

    expect(rules(lint(file, sample.code))).toContain(sample.rule);
  });

  it('refuses the same in src/app/real and leaves the other folders of the app alone', () => {
    const conversion = "export const a = Number('1');\n";

    expect(rules(lint('src/app/real/sample.ts', conversion))).toContain('no-restricted-syntax');
    expect(rules(lint('src/features/killswitch/sample.ts', conversion))).toHaveLength(0);
  });

  it('money.ts is the named exception for the conversion only; the storage ban still applies to it', () => {
    const conversion = lint('src/features/real/money.ts', "export const n = Number('1');\n");
    const storage = lint(
      'src/features/real/money.ts',
      "localStorage.setItem('k', 'v');\nexport {};\n",
    );

    expect(rules(conversion)).not.toContain('no-restricted-syntax');
    expect(rules(storage)).toContain('no-restricted-globals');
  });

  it('the dependencies of the app are the allowed list: no Solana SDK nor transaction library (U6-NFR1.3)', () => {
    const manifest = JSON.parse(readFileSync(join(ROOT, 'package.json'), 'utf8')) as {
      dependencies: Record<string, string>;
    };

    expect(Object.keys(manifest.dependencies).sort()).toEqual([
      '@solana/wallet-standard-features',
      '@wallet-standard/app',
      '@wallet-standard/base',
      '@wallet-standard/features',
      'next',
      'react',
      'react-dom',
    ]);
  });

  it('no source of the app reads a NEXT_PUBLIC_* variable, and the REAL code sends no transaction (U6-NFR1.5)', () => {
    const files = (function walk(dir: string): string[] {
      return readdirSync(dir).flatMap((name) => {
        const path = join(dir, name);
        return statSync(path).isDirectory() ? walk(path) : [path];
      });
    })(join(ROOT, 'src')).filter((file) => /\.(ts|tsx)$/.test(file) && !/\.test\.tsx?$/.test(file));
    const publicUse = /process\.env(\.|\[['"])NEXT_PUBLIC_/;
    const sends = /sendTransaction|sendRawTransaction|signAndSendTransaction/;

    expect(
      files
        .filter((file) => publicUse.test(readFileSync(file, 'utf8')))
        .map((file) => relative(ROOT, file)),
    ).toEqual([]);
    expect(
      files
        .filter((file) => sends.test(readFileSync(file, 'utf8')))
        .map((file) => relative(ROOT, file)),
    ).toEqual([]);
  });
});
