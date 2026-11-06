/** Health route of the container health check (Q1 and R-07 of the Infrastructure Design): no data, outside the CSP. */
export const dynamic = 'force-dynamic';

export function GET(): Response {
  return Response.json({ status: 'UP' }, { status: 200, headers: { 'Cache-Control': 'no-store' } });
}
