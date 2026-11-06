// Fails when src/lib/api/schema.d.ts differs from what openapi-typescript generates from the versioned
// apps/api/openapi/openapi-admin.yaml (team.md: frontend types are generated, never hand-written). Runs the same CLI as
// "gen:api", reading its output from stdout, so nothing is written to disk.
import { execFile } from 'node:child_process';
import { readFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { promisify } from 'node:util';
import prettier from 'prettier';

const here = dirname(fileURLToPath(import.meta.url));
const root = resolve(here, '..');
const typesPath = resolve(root, 'src/lib/api/schema.d.ts');
const cli = resolve(root, 'node_modules/openapi-typescript/bin/cli.js');

const { stdout } = await promisify(execFile)(
  process.execPath,
  [cli, '../api/openapi/openapi-admin.yaml'],
  {
    cwd: root,
    maxBuffer: 10 * 1024 * 1024,
  },
);
const options = (await prettier.resolveConfig(typesPath)) ?? {};
const expected = await prettier.format(stdout, { ...options, filepath: typesPath });
const actual = (await readFile(typesPath, 'utf8')).replace(/\r\n/g, '\n');

if (actual !== expected) {
  console.error(
    'schema.d.ts is out of date: run "pnpm --dir apps/admin gen:api" and commit the result.',
  );
  process.exit(1);
}
console.warn('schema.d.ts matches openapi-admin.yaml');
