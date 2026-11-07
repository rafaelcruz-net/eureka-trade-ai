// Regression of the U6 plan check (Build and Test loop 1, failure F4; NFR2.6). Run with:
//   node --test scripts/load/load-test-u6-plans.test.mjs
// The defect: u6Plans() put the sample subquery "(select ... from order_rejection r limit 1)" inside the EXPLAIN, and
// that subquery alone produced the "Seq Scan on order_rejection" the rule rejects, so the plans of the real queries
// were never checked. Synthetic ids only; no database, no network.
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';
import { parseU6Sample, u6PlanQueries } from './u6-plans.mjs';

const SAMPLE = {
  tenantId: '11111111-1111-4111-8111-111111111111',
  ownerUserId: '22222222-2222-4222-8222-222222222222',
  botId: '33333333-3333-4333-8333-333333333333',
};
/** The correlated anti-join of the real purge selection (RiskEvaluationPurgeJob); it is part of the query, not a sample. */
const PURGE_ANTI_JOIN = 'not exists (select 1 from order_rejection r where r.evaluation_id = e.evaluation_id)';
const SAMPLE_SUBQUERY = /\(\s*select\b[^()]*\bfrom\s+(order_rejection|order_evaluation)\b[^()]*\blimit\s+1\s*\)/i;

function nestedSelects(sql) {
  return (sql.replace(PURGE_ANTI_JOIN, '').match(/\bselect\b/gi) ?? []).length - 1;
}

test('the EXPLAIN queries carry no sample subquery and take the sample as quoted literals', () => {
  const queries = u6PlanQueries(SAMPLE);

  assert.deepEqual(Object.keys(queries).sort(), ['decision', 'page', 'purge']);
  for (const [name, sql] of Object.entries(queries)) {
    assert.doesNotMatch(sql, SAMPLE_SUBQUERY, `${name} still reads its sample inside the EXPLAIN`);
    assert.equal(nestedSelects(sql), 0, `${name} has a subquery other than the purge anti-join`);
  }
  assert.equal(
    queries.page,
    `select * from order_rejection where tenant_id = '${SAMPLE.tenantId}' and owner_user_id = '${SAMPLE.ownerUserId}' ` +
      `and mode = 'PAPER' and bot_id = '${SAMPLE.botId}' order by evaluated_at desc, rejection_id desc limit 51`,
  );
  assert.equal(
    queries.decision,
    `select * from order_evaluation where tenant_id = '${SAMPLE.tenantId}' and mode = 'PAPER' ` +
      `and bot_id = '${SAMPLE.botId}' and decision_id = '00000000-0000-0000-0000-000000000000'`,
  );
  assert.ok(queries.purge.includes(PURGE_ANTI_JOIN), 'the purge keeps its own anti-join');
});

test('a sample value outside the UUID format is refused before any query is built', () => {
  for (const botId of ["33333333-3333-4333-8333-333333333333' or '1'='1", '', 'not-a-uuid', undefined, null]) {
    assert.throws(() => u6PlanQueries({ ...SAMPLE, botId }), /botId is not a UUID/);
  }
  assert.throws(() => u6PlanQueries(null), /sample/);
});

test('the sample is read from the psql -At line, and an empty table gives no sample', () => {
  assert.deepEqual(parseU6Sample(`${SAMPLE.tenantId}|${SAMPLE.ownerUserId}|${SAMPLE.botId}\n`), SAMPLE);
  assert.equal(parseU6Sample(''), null);
  assert.equal(parseU6Sample('\n'), null);
  assert.throws(() => parseU6Sample('a|b'), /unexpected sample/);
});

test('the plan rule is unchanged: a Seq Scan on the U6 tables fails, an Index Scan passes', () => {
  const runner = readFileSync(new URL('./load-test.mjs', import.meta.url), 'utf8');
  const literal = runner.match(/\/(Seq Scan on \(order_evaluation\|order_rejection\)\\b)\//);
  assert.ok(literal, 'scripts/load/load-test.mjs no longer holds the Seq Scan rule');
  const rule = new RegExp(literal[1]);

  const seqScan = [
    'Limit  (cost=12.10..12.23 rows=51 width=210)',
    '  ->  Sort  (cost=12.10..12.23 rows=51 width=210)',
    '        ->  Seq Scan on order_rejection  (cost=0.00..11.40 rows=51 width=210)',
  ].join('\n');
  const indexScan = [
    'Limit  (cost=0.56..8.60 rows=51 width=210)',
    '  ->  Index Scan using order_rejection_page_idx on order_rejection  (cost=0.56..8.60 rows=51 width=210)',
    "        Index Cond: ((tenant_id = '11111111-1111-4111-8111-111111111111'::uuid))",
  ].join('\n');
  const evaluationSeqScan = '  ->  Seq Scan on order_evaluation e  (cost=0.00..1.00 rows=1 width=16)';

  assert.match(seqScan, rule);
  assert.match(evaluationSeqScan, rule);
  assert.doesNotMatch(indexScan, rule);
});
