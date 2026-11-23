// 261121-paper-real-unico U2 (NFR1.6, NFR1.18; performance-design.md "Conferência do plano"): pure builders of the
// plan check of the scenario pnl-by-mode of scripts/load/load-test.mjs. The main REAL read of a person (the rows of her
// bot wallets in network order, JdbcRealLedger.NETWORK_ORDER_SQL, kept identical here by PnlPlanSqlParityTest) is sent
// to EXPLAIN with the sample as literals, and the plan must use real_ledger_entry_wallet_created_idx by name and never a
// Seq Scan on real_ledger_entry. No I/O here, so the node --test regression imports it without running the load.

/** The wallet with the most rows, read on its own before the EXPLAIN (psql -At prints "tenant|wallet"). */
export const PNL_SAMPLE_SQL =
  'select tenant_id, bot_wallet_id from real_ledger_entry group by tenant_id, bot_wallet_id ' +
  'order by count(*) desc limit 1';

/** The trading kinds the P&L REAL streams (BR6.5). */
export const TRADING_KINDS = ['SWAP_FILL_IN', 'SWAP_FILL_OUT', 'NETWORK_FEE', 'RENT'];

/** The exact text of JdbcRealLedger.NETWORK_ORDER_SQL (PnlPlanSqlParityTest compares them). */
export const NETWORK_ORDER_SQL = `with entries as (
  select e.entry_id, e.bot_wallet_id, e.entry_kind, e.lamports, e.mint, e.token_base_units,
    e.tx_signature, e.reference, e.idempotency_key, e.created_at, s.slot as recorded_slot,
    case e.entry_kind when 'SWAP_FILL_IN' then 0 when 'SWAP_FILL_OUT' then 0 when 'NETWORK_FEE' then 1
      when 'RENT' then 2 else 3 end as kind_rank
  from real_ledger_entry e
  left join real_entry_slot s on s.tx_signature = e.tx_signature
  where e.tenant_id = :tenant and e.bot_wallet_id in (:wallets) and e.mode = 'REAL'
), grouped as (
  select entries.*, count(recorded_slot) over (partition by bot_wallet_id
    order by created_at, reference collate "C", kind_rank, idempotency_key collate "C"
    rows between unbounded preceding and current row) as slot_group
  from entries
), keyed as (
  select grouped.*, coalesce(recorded_slot,
    max(recorded_slot) over (partition by bot_wallet_id, slot_group), 0) as effective_slot
  from grouped
)
select entry_id, bot_wallet_id, entry_kind, lamports, mint, token_base_units, tx_signature, reference,
  idempotency_key, created_at, recorded_slot, kind_rank, effective_slot
from keyed
where entry_kind in (:kinds)
order by bot_wallet_id, effective_slot, recorded_slot is null,
  (case when recorded_slot is not null then tx_signature end) collate "C",
  created_at, reference collate "C", kind_rank, idempotency_key collate "C"`;

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/** A quoted SQL literal for a sample id: anything but a UUID is refused. */
function uuidLiteral(value, name) {
  if (typeof value !== 'string' || !UUID.test(value)) throw new Error(`sample ${name} is not a UUID`);
  return `'${value.replaceAll("'", "''")}'`;
}

/** The sample from the psql -At output of PNL_SAMPLE_SQL, or null when the ledger has no row. */
export function parsePnlSample(stdout) {
  const line = String(stdout ?? '').trim();
  if (line === '') return null;
  const fields = line.split('|');
  if (fields.length !== 2) throw new Error(`unexpected sample line from real_ledger_entry: ${fields.length} fields`);
  const [tenantId, botWalletId] = fields;
  return { tenantId, botWalletId };
}

/** The main read with the sample as literals: the plan checked is the plan of the real query. */
export function pnlPlanQuery(sample) {
  if (sample === null || typeof sample !== 'object') throw new Error('no sample to build the P&L plan from');
  return NETWORK_ORDER_SQL.replace(':tenant', uuidLiteral(sample.tenantId, 'tenantId'))
    .replace(':wallets', uuidLiteral(sample.botWalletId, 'botWalletId'))
    .replace(':kinds', TRADING_KINDS.map((kind) => `'${kind}'`).join(', '));
}

const INDEX = /(Index Scan using|Index Only Scan using|Bitmap Index Scan on) real_ledger_entry_wallet_created_idx\b/;
const SEQ_SCAN = /Seq Scan on real_ledger_entry\b/;

/** NFR1.18: the plan uses the index of the 0023 by name and never a Seq Scan on real_ledger_entry. */
export function checkPnlPlan(plan) {
  const text = String(plan ?? '');
  const usesIndex = INDEX.test(text);
  const seqScan = SEQ_SCAN.test(text);
  return { usesIndex, seqScan, passed: usesIndex && !seqScan };
}
