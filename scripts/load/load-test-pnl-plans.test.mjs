// 261121-paper-real-unico U2, Etapa 25 (written after the layer; NFR1.18): the plan check of the scenario pnl-by-mode.
// Run with:
//   node --test scripts/load/load-test-pnl-plans.test.mjs
// Synthetic ids only; no database, no network.
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { NETWORK_ORDER_SQL, checkPnlPlan, parsePnlSample, pnlPlanQuery } from './pnl-plans.mjs';

const SAMPLE = {
  tenantId: '11111111-1111-4111-8111-111111111111',
  botWalletId: '22222222-2222-4222-8222-222222222222',
};

test('the EXPLAIN query is the main REAL read with the sample as quoted literals', () => {
  const sql = pnlPlanQuery(SAMPLE);

  assert.ok(sql.includes(`e.tenant_id = '${SAMPLE.tenantId}'`));
  assert.ok(sql.includes(`e.bot_wallet_id in ('${SAMPLE.botWalletId}')`));
  assert.ok(sql.includes("entry_kind in ('SWAP_FILL_IN', 'SWAP_FILL_OUT', 'NETWORK_FEE', 'RENT')"));
  assert.doesNotMatch(sql, /:tenant|:wallets|:kinds/);
  assert.equal(sql.replace(/'[^']*'/g, '?').length > 0, true);
  assert.ok(NETWORK_ORDER_SQL.includes('collate "C"'));
});

test('a sample value outside the UUID format is refused before any query is built', () => {
  for (const botWalletId of ["2222' or '1'='1", '', 'not-a-uuid', undefined, null]) {
    assert.throws(() => pnlPlanQuery({ ...SAMPLE, botWalletId }), /botWalletId is not a UUID/);
  }
  assert.throws(() => pnlPlanQuery(null), /sample/);
});

test('the sample is read from the psql -At line, and an empty ledger gives no sample', () => {
  assert.deepEqual(parsePnlSample(`${SAMPLE.tenantId}|${SAMPLE.botWalletId}\n`), SAMPLE);
  assert.equal(parsePnlSample(''), null);
  assert.throws(() => parsePnlSample('a|b|c'), /unexpected sample/);
});

test('the plan passes only with the index of the 0023 by name and without a Seq Scan on the ledger', () => {
  const indexed = [
    'Sort  (cost=1.00..2.00 rows=10 width=200)',
    '  ->  WindowAgg',
    '        ->  Index Scan using real_ledger_entry_wallet_created_idx on real_ledger_entry e  (cost=0.42..8.44)',
    '        ->  Seq Scan on real_entry_slot s  (cost=0.00..1.00)',
  ].join('\n');
  const bitmap = '  ->  Bitmap Index Scan on real_ledger_entry_wallet_created_idx  (cost=0.00..4.42)';
  const seqScan = '  ->  Seq Scan on real_ledger_entry e  (cost=0.00..100.00 rows=1000 width=200)';
  const otherIndex = '  ->  Index Scan using real_ledger_entry_tenant_created_idx on real_ledger_entry e';

  assert.deepEqual(checkPnlPlan(indexed), { usesIndex: true, seqScan: false, passed: true });
  assert.equal(checkPnlPlan(bitmap).passed, true);
  assert.equal(checkPnlPlan(seqScan).passed, false);
  assert.equal(checkPnlPlan(`${indexed}\n${seqScan}`).passed, false);
  assert.equal(checkPnlPlan(otherIndex).passed, false);
});
