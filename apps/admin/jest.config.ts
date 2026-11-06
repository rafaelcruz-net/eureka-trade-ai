import type { Config } from 'jest';
import nextJest from 'next/jest.js';

const createJestConfig = nextJest({ dir: './' });

const config: Config = {
  testEnvironment: 'jsdom',
  setupFilesAfterEnv: ['<rootDir>/jest.setup.ts'],
  moduleNameMapper: {
    '^@/(.*)$': '<rootDir>/src/$1',
  },
  testPathIgnorePatterns: ['/node_modules/', '/.next/'],
  collectCoverageFrom: [
    'src/**/*.{ts,tsx}',
    // Fixed exclusions (unit-test-instructions.md of U8), the same as apps/web; never decided ad hoc.
    '!src/lib/api/schema.d.ts',
    '!src/**/*.config.*',
    '!src/app/layout.tsx',
    '!src/**/*.d.ts',
  ],
  coverageReporters: ['text-summary', 'lcov', 'json-summary'],
  // 80% line floor measured for the administrator app alone (team.md). Never lowered to pass a step.
  coverageThreshold: {
    global: {
      lines: 80,
    },
  },
};

export default createJestConfig(config);
