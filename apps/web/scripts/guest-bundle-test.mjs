// GuestBundleTest (U8, security-design.md "Convidado sem código administrativo", ADR-009): after `next build`, the
// output of the guest app must not contain any administrative route nor the administrative specification. Fails with a
// list of files (never their content) when it finds one; fails too when there is no build to check.
import { readdir, readFile, stat } from 'node:fs/promises';
import { dirname, join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const output = join(root, '.next');
const FORBIDDEN = ['/api/v1/admin', 'openapi-admin'];
const SCANNED = /\.(js|mjs|cjs|json|html|txt|map|rsc)$/;

async function* files(directory) {
  for (const entry of await readdir(directory, { withFileTypes: true })) {
    const path = join(directory, entry.name);
    if (entry.isDirectory()) {
      if (entry.name !== 'cache') {
        yield* files(path);
      }
    } else if (SCANNED.test(entry.name)) {
      yield path;
    }
  }
}

try {
  await stat(output);
} catch {
  console.error(
    'GuestBundleTest: apps/web/.next is missing; run "pnpm --dir apps/web build" first.',
  );
  process.exit(1);
}

const offenders = [];
let scanned = 0;
for await (const file of files(output)) {
  scanned += 1;
  const text = await readFile(file, 'utf8');
  for (const needle of FORBIDDEN) {
    if (text.includes(needle)) {
      offenders.push(`${relative(root, file)} contains ${needle}`);
    }
  }
}

if (scanned === 0) {
  console.error('GuestBundleTest: no file of the build was scanned.');
  process.exit(1);
}
if (offenders.length > 0) {
  console.error(
    `GuestBundleTest: the guest build carries administrative code:\n${offenders.join('\n')}`,
  );
  process.exit(1);
}
console.warn(`GuestBundleTest: ${scanned} files of the guest build, no administrative route.`);
