import { GUEST_ROUTES, HOME, safeOrigin } from '@/features/auth/origin';
import { safeOrigin as reExported } from '@/features/auth/login-flow';

/** U3 Passo 19 (BR4.2, NFR6.11): the return after the entry accepts only internal guest routes. */
describe('safeOrigin', () => {
  it.each([
    ['//exemplo.com'],
    ['https://exemplo.com'],
    ['/\\exemplo.com'],
    ['/%2F%2Fexemplo.com'],
    ['/%2f%2fexemplo.com'],
    ['\t//exemplo.com'],
    ['\n//exemplo.com'],
    ['/scanner\n'],
    ['javascript:alert(1)'],
    ['/admin'],
    ['/admin/convites'],
    ['/rota-inexistente'],
    ['/entrar'],
    ['/convite/AAAA'],
    ['/%E0%A4%A'],
    [''],
    [null],
    [undefined],
  ])('turns %j into the home page', (value) => {
    expect(safeOrigin(value as string | null | undefined)).toBe(HOME);
  });

  it.each([
    ['/scanner'],
    ['/conta'],
    ['/scanner?pagina=2'],
    ['/scanner#lista'],
    ['/scanner?minHolders=10&sort=AGE'],
  ])('keeps the guest route %s', (value) => {
    expect(safeOrigin(value)).toBe(value);
  });

  it('the home page is the dashboard and the guest routes are listed (U9)', () => {
    expect(HOME).toBe('/');
    expect(GUEST_ROUTES).toEqual(['/', '/scanner', '/conta']);
    expect(reExported('/conta')).toBe('/conta');
    expect(safeOrigin('/')).toBe('/');
  });
});
