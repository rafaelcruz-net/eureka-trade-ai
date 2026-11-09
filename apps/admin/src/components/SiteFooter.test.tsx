import { render, screen } from '@testing-library/react';
import { tokenStore } from '@/lib/auth/token-store';
import { AdminShell } from '@/features/layout/AdminShell';
import { Providers, fakeAdminApi } from '@/test-support';
import { SiteFooter } from '@/components/SiteFooter';

/** Passo 16 / 17 (FR2.2, BR1.11): the footer is on every administrative screen and on its own. */
describe('SiteFooter of the administrator app', () => {
  beforeEach(() => tokenStore.clear());

  it('is part of the layout of every signed-in screen', async () => {
    render(
      <Providers api={fakeAdminApi()}>
        <AdminShell>
          <p>conteúdo</p>
        </AdminShell>
      </Providers>,
    );

    const footer = await screen.findByTestId('site-footer');
    expect(footer).toContainElement(screen.getByTestId('site-footer-privacy-link'));
  });

  it('labels its navigation for assistive technologies', () => {
    render(<SiteFooter />);

    expect(screen.getByRole('navigation', { name: 'Documentos legais' })).toBeInTheDocument();
  });
});
