// Pure builders of the U6 plan check of scripts/load/load-test.mjs (NFR2.6): the queries sent to EXPLAIN for the page
// of C21, the lookup of the decision and the selection of the purge. No I/O here, so the node --test regression
// (scripts/load/load-test-u6-plans.test.mjs) imports it without running the load script.

/** The query that reads the sample, run on its own before the EXPLAIN (psql -At prints "tenant|owner|bot"). */
export const U6_SAMPLE_SQL = 'select r.tenant_id, r.owner_user_id, r.bot_id from order_rejection r limit 1';

/** The sample from the psql -At output of U6_SAMPLE_SQL, or null when the table has no row. */
export function parseU6Sample(stdout) {
  const line = String(stdout ?? '').trim();
  if (line === '') return null;
  const fields = line.split('|');
  if (fields.length !== 3) throw new Error(`unexpected sample line from order_rejection: ${fields.length} fields`);
  const [tenantId, ownerUserId, botId] = fields;
  return { tenantId, ownerUserId, botId };
}

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/** A quoted SQL literal for a sample id: anything but a UUID is refused, and single quotes are doubled as well. */
function uuidLiteral(value, name) {
  if (typeof value !== 'string' || !UUID.test(value)) throw new Error(`sample ${name} is not a UUID`);
  return `'${value.replaceAll("'", "''")}'`;
}

/**
 * The queries sent to EXPLAIN, built from the sample (tenantId, ownerUserId, botId) already read. They have the shape
 * of the real ones (JdbcRejectionRepository.page, JdbcEvaluationRepository.find, RiskEvaluationPurgeJob) with the
 * sample as literals, so the plan checked is the plan of the real query and never the plan of a sample subquery.
 */
export function u6PlanQueries(sample) {
  if (sample === null || typeof sample !== 'object') throw new Error('no sample to build the U6 plans from');
  const tenant = uuidLiteral(sample.tenantId, 'tenantId');
  const owner = uuidLiteral(sample.ownerUserId, 'ownerUserId');
  const bot = uuidLiteral(sample.botId, 'botId');
  return {
    page:
      `select * from order_rejection where tenant_id = ${tenant} and owner_user_id = ${owner} ` +
      `and mode = 'PAPER' and bot_id = ${bot} order by evaluated_at desc, rejection_id desc limit 51`,
    decision:
      `select * from order_evaluation where tenant_id = ${tenant} and mode = 'PAPER' ` +
      `and bot_id = ${bot} and decision_id = '00000000-0000-0000-0000-000000000000'`,
    purge:
      "select e.evaluation_id from order_evaluation e where e.outcome = 'APPROVED' " +
      "and e.evaluated_at < now() - interval '31 days' " +
      'and not exists (select 1 from order_rejection r where r.evaluation_id = e.evaluation_id) ' +
      'order by e.evaluated_at limit 1000',
  };
}
