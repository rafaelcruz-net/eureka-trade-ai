import { render } from '@testing-library/react';
import { ApiProvider } from '@/lib/api/ApiProvider';
import { fakeApi } from '@/features/auth/test-support';
import { currentScreen, ReacceptanceRedirect } from '@/features/reacceptance/ReacceptanceRedirect';
import {
  DEFAULT_RETURN,
  firstParam,
  reacceptanceHref,
  REACCEPTANCE_PATH,
  safeReturnPath,
} from '@/features/reacceptance/return-path';

const replace = jest.fn();

jest.mock('next/navigation', () => ({
  useRouter: () => ({ replace, push: replace }),
  useSearchParams: () => new URLSearchParams(),
}));

/**
 * Passo 8 of u3-reacceptance (NFR2.4, BR3.7): the return path of `/novo-aceite` is only an internal path of the app,
 * and the handler registered by the ApiProvider sends the person there with `router.replace`.
 */
describe('safeReturnPath', () => {
  it.each(['/', '/bots', '/bots/123?aba=decisoes', '/conta', '/scanner?ordem=AGE#topo'])(
    'keeps the internal path %s',
    (path) => {
      expect(safeReturnPath(path)).toBe(path);
    },
  );

  it.each([
    'https://evil.example/x',
    '//evil.example/x',
    '/\\evil.example',
    '/bots\\..\\x',
    'javascript:alert(1)',
    'bots',
    '/bots com espaço',
    '',
    ' /bots',
  ])('refuses %s and goes back to the dashboard', (value) => {
    expect(safeReturnPath(value)).toBe(DEFAULT_RETURN);
  });

  it('refuses a missing value and the screen of the new acceptance itself', () => {
    expect(safeReturnPath(undefined)).toBe(DEFAULT_RETURN);
    expect(safeReturnPath(null)).toBe(DEFAULT_RETURN);
    expect(safeReturnPath(REACCEPTANCE_PATH)).toBe(DEFAULT_RETURN);
    expect(safeReturnPath(`${REACCEPTANCE_PATH}?voltar=/bots`)).toBe(DEFAULT_RETURN);
  });

  it('builds the address of the new acceptance with the encoded screen of origin', () => {
    expect(reacceptanceHref('/bots/1?aba=2')).toBe('/novo-aceite?voltar=%2Fbots%2F1%3Faba%3D2');
    expect(reacceptanceHref('https://evil.example')).toBe('/novo-aceite?voltar=%2F');
  });

  it('reads the first value of a search parameter', () => {
    expect(firstParam(['/a', '/b'])).toBe('/a');
    expect(firstParam('/c')).toBe('/c');
    expect(firstParam(undefined)).toBeUndefined();
  });
});

describe('ReacceptanceRedirect', () => {
  beforeEach(() => replace.mockReset());

  it('registers the handler that replaces the route with /novo-aceite and the screen of origin', () => {
    const api = fakeApi();
    render(<ReacceptanceRedirect api={api} screen={() => '/pnl?periodo=7d'} />);

    const handler = api.setTermsReacceptanceHandler.mock.calls[0]?.[0];
    handler?.();

    expect(replace).toHaveBeenCalledWith('/novo-aceite?voltar=%2Fpnl%3Fperiodo%3D7d');
  });

  it('does nothing on the screen of the new acceptance itself', () => {
    const api = fakeApi();
    render(<ReacceptanceRedirect api={api} screen={() => '/novo-aceite?voltar=%2F'} />);

    api.setTermsReacceptanceHandler.mock.calls[0]?.[0]?.();

    expect(replace).not.toHaveBeenCalled();
  });

  it('unregisters the handler when it leaves the page', () => {
    const unregister = jest.fn();
    const api = fakeApi({ setTermsReacceptanceHandler: jest.fn(() => unregister) });
    const view = render(<ReacceptanceRedirect api={api} />);

    view.unmount();

    expect(unregister).toHaveBeenCalledTimes(1);
  });

  it('is rendered by the ApiProvider only when the app asks for it', () => {
    const withRedirect = fakeApi();
    render(
      <ApiProvider apiOrigin="http://api" api={withRedirect} redirectOnTermsReacceptance>
        <p>conteúdo</p>
      </ApiProvider>,
    );
    const without = fakeApi();
    render(
      <ApiProvider apiOrigin="http://api" api={without}>
        <p>conteúdo</p>
      </ApiProvider>,
    );

    expect(withRedirect.setTermsReacceptanceHandler).toHaveBeenCalledTimes(1);
    expect(without.setTermsReacceptanceHandler).not.toHaveBeenCalled();
  });

  it('reads the screen of origin from the path and the query, never the hash', () => {
    expect(currentScreen({ pathname: '/bots', search: '?estado=ACTIVE' })).toBe(
      '/bots?estado=ACTIVE',
    );
    window.history.replaceState(null, '', '/scanner?ordem=AGE#x');
    expect(currentScreen()).toBe('/scanner?ordem=AGE');
  });
});
