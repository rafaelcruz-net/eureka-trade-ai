/** Health route used by the container health check (node -e fetch …/api/health, exit 0 only on HTTP 200). */
export const dynamic = 'force-dynamic';

export function GET(): Response {
  return Response.json({ status: 'UP' }, { status: 200, headers: { 'Cache-Control': 'no-store' } });
}
