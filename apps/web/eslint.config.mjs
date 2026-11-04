import { dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { FlatCompat } from '@eslint/eslintrc';

const compat = new FlatCompat({ baseDirectory: dirname(fileURLToPath(import.meta.url)) });

const config = [
  {
    ignores: [
      '.next/**',
      'node_modules/**',
      'coverage/**',
      'test-results/**',
      'playwright-report/**',
      'next-env.d.ts',
      'src/lib/api/schema.d.ts',
    ],
  },
  ...compat.extends('next/core-web-vitals', 'next/typescript'),
  {
    rules: {
      'no-console': ['error', { allow: ['warn', 'error'] }],
      '@typescript-eslint/no-explicit-any': 'error',
    },
  },
  {
    // U4 (NFR6.6): symbol and name come from untrusted sources; the scanner screens render text only.
    files: ['src/features/scanner/**/*.{ts,tsx}', 'src/app/scanner/**/*.{ts,tsx}'],
    rules: {
      'react/no-danger': 'error',
    },
  },
  {
    // U3 (cicd-pipeline.md): terms, risk notice and account data are plain text, never HTML.
    files: [
      'src/features/auth/**/*.{ts,tsx}',
      'src/features/account/**/*.{ts,tsx}',
      'src/app/convite/**/*.{ts,tsx}',
      'src/app/conta/**/*.{ts,tsx}',
    ],
    rules: {
      'react/no-danger': 'error',
    },
  },
  {
    // U7 (cicd-pipeline.md, project.md): money, quantities, prices and P&L are DecimalString from the API to the screen
    // and are never converted to a JavaScript number (floating point). Calendar parts use parseInt with radix 10.
    files: ['src/features/pnl/**/*.{ts,tsx}', 'src/app/pnl/**/*.{ts,tsx}'],
    rules: {
      'react/no-danger': 'error',
      'no-restricted-globals': [
        'error',
        {
          name: 'parseFloat',
          message: 'DecimalString values stay text (project.md: no floating point).',
        },
      ],
      'no-restricted-properties': [
        'error',
        { object: 'Number', property: 'parseFloat', message: 'DecimalString values stay text.' },
        {
          object: 'Math',
          property: 'round',
          message: 'Monetary values are never rounded in the screen.',
        },
      ],
      'no-restricted-syntax': [
        'error',
        {
          selector: "CallExpression[callee.name='Number']",
          message: 'DecimalString values stay text (project.md: no floating point).',
        },
        {
          selector: "UnaryExpression[operator='+'][argument.type!='Literal']",
          message: 'Unary plus converts a DecimalString to a number.',
        },
        {
          selector: "CallExpression[callee.property.name='toFixed']",
          message: 'toFixed works on floating point numbers.',
        },
      ],
    },
  },
];

export default config;
