import { Client } from 'pg';

/**
 * Polls the audit trail of the TEST database (every second, at most 10 s, F7 step 5) for an action with the given
 * correlation id. DATABASE_URL is set by scripts/verify.sh for the ephemeral environment only.
 */
export async function waitForAuditAction(
  correlationId: string,
  action: string,
  timeoutMs = 10_000,
): Promise<boolean> {
  const url = process.env.DATABASE_URL;
  if (!url) {
    throw new Error('DATABASE_URL is not set (scripts/verify.sh sets it)');
  }
  const client = new Client({ connectionString: url });
  await client.connect();
  try {
    const deadline = Date.now() + timeoutMs;
    while (Date.now() <= deadline) {
      const result = await client.query(
        'select count(*)::int as n from audit_event where correlation_id = $1 and action = $2',
        [correlationId, action],
      );
      if ((result.rows[0] as { n: number }).n > 0) {
        return true;
      }
      await new Promise((resolve) => setTimeout(resolve, 1_000));
    }
    return false;
  } finally {
    await client.end();
  }
}
