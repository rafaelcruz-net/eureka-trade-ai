// Pure builders of the U5 slice check of scripts/load/load-test.mjs (NFR2.2): which 5-minute slices of the run must be
// complete and the query that counts the completed ones. No I/O here, so the node --test regression
// (scripts/load/load-test-u5-slices.test.mjs) imports it without running the load script.

/** The slices of the worker: 5 minutes aligned to the UTC clock (SlotDispatcher.slotOf). */
export const SLICE_MS = 300_000;

function instant(value, name) {
  if (typeof value !== 'number' || !Number.isFinite(value)) throw new Error(`${name} is not an instant in ms`);
  return value;
}

/** The cycles of a slice run from slotStart + 60 s (SlotDispatcher), so a slice must end 60 s before the run ends. */
export const LAST_CYCLE_MARGIN_MS = 60_000;

/**
 * The starts (epoch ms) of the slices the run must have completed, from the start and the end of the run (epoch ms):
 * only the slices that fit whole in the run, that is, that start at or after its start and end at or before its end
 * minus 60 s. Every one of them must be complete (NFR2.2); the slice running when the run starts is never expected.
 */
export function expectedU5Slices(startedAtMs, endedAtMs) {
  const start = instant(startedAtMs, 'startedAtMs');
  const end = instant(endedAtMs, 'endedAtMs');
  if (end < start) throw new Error('the run ends before it starts');
  const slices = [];
  for (let slice = Math.ceil(start / SLICE_MS) * SLICE_MS; slice + SLICE_MS <= end - LAST_CYCLE_MARGIN_MS; ) {
    slices.push(slice);
    slice += SLICE_MS;
  }
  return slices;
}

/** The query that counts the distinct expected slices with a COMPLETED cycle; ISO literals built from numbers only. */
export function u5SlicesSql(slices) {
  if (!Array.isArray(slices) || slices.length === 0) return 'select 0';
  const literals = slices.map((slice) => `'${new Date(instant(slice, 'slice')).toISOString()}'`).join(', ');
  return `select count(distinct slot_start) from bot_cycle where status = 'COMPLETED' and slot_start in (${literals})`;
}
