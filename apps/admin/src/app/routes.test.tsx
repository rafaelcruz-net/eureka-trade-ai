/**
 * @jest-environment node
 */
import { GET } from '@/app/api/health/route';

describe('health route of the container (R-07)', () => {
  it('answers 200 with no data and no cache', async () => {
    const response = GET();

    expect(response.status).toBe(200);
    expect(response.headers.get('Cache-Control')).toBe('no-store');
    await expect(response.json()).resolves.toEqual({ status: 'UP' });
  });
});
