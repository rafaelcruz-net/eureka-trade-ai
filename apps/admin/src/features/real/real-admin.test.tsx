import { act, fireEvent, render, screen, within } from '@testing-library/react';
import type { AdminApi, RealAdminDivergence, RealAdminUser } from '@/lib/api/admin-api';
import { ApiError } from '@/lib/api/client';
import { tokenStore } from '@/lib/auth/token-store';
import { colors, contrastRatio } from '@/lib/theme/tokens';
import { fakeAdminApi, Providers } from '@/test-support';
import {
  AdminActionDialog,
  reasonLength,
  reasonProblem,
} from '@/features/real/components/AdminActionDialog';
import { toAdminRealError } from '@/features/real/errors';
import { adminRealMessages as text } from '@/features/real/messages';
import {
  RealDivergencesScreen,
  compensationItems,
} from '@/features/real/screens/RealDivergencesScreen';
import { RealKillSwitchScreen } from '@/features/real/screens/RealKillSwitchScreen';
import { RealRestoreScreen } from '@/features/real/screens/RealRestoreScreen';
import { RealUserDetailScreen } from '@/features/real/screens/RealUserDetailScreen';
import { RealUsersScreen, solText } from '@/features/real/screens/RealUsersScreen';
import RealUsersRoute from '@/app/real/page';
import RealTokensRoute from '@/app/real/tokens/page';
import RealKillSwitchRoute from '@/app/real/kill-switch/page';
import RealDivergencesRoute from '@/app/real/divergencias/page';
import RealRestoreRoute from '@/app/real/restauracao/page';
import RealUserRoute from '@/app/real/usuarios/[id]/page';

/**
 * 261113-trading-real-bot U6, Step 15, written after the code (A1; US6.2, US7.1 to US7.3, US8.1, US8.2; BR9.1 to BR9.6;
 * S33 to S35; U6-NFR1.8, U6-NFR1.10, U6-NFR1.11, U6-NFR3.6, U6-NFR6.2): the REAL section of the administrator app.
 */
jest.mock('next/navigation', () => ({
  useRouter: () => ({ replace: jest.fn(), push: jest.fn() }),
  useSearchParams: () => new URLSearchParams(),
  usePathname: () => '/real',
  useParams: () => ({ id: USER_ID }),
}));

const USER_ID = '0a0a0000-0000-4000-8000-000000000001';
const ZONE = 'America/Sao_Paulo';
const MAIN_FULL = 'MainWa11et111111111111111111111111111111111';
const MINT = 'TestMint111111111111111111111111111111111111';

const USER: RealAdminUser = {
  userId: USER_ID,
  botWalletId: '0b0b0000-0000-4000-8000-000000000001',
  botWalletAddress: 'BotWa11et1111111111111111111111111111111111',
  mainWalletShort: 'Main…1111',
  mode: 'AUTO_FULL',
  capLamports: '3000000000',
  balance: { totalLamports: '8400000000' },
  walletStatus: 'ACTIVE',
  pausedReasons: [],
  openDivergences: [],
  recentOrders: [
    {
      realOrderId: 'o1',
      createdAt: '2026-11-16T13:02:00Z',
      mint: MINT,
      side: 'BUY',
      state: 'CONFIRMED',
    },
  ],
  release: { state: 'ACTIVE', network: 'LOCALNET', effectiveAt: '2026-11-10T12:00:00Z' },
};

const DIVERGENCE: RealAdminDivergence = {
  divergenceId: '0d0d0000-0000-4000-8000-000000000001',
  botWalletId: '0b0b0000-0000-4000-8000-000000000001',
  state: 'OPEN',
  openedAt: '2026-11-16T11:00:00Z',
  lamportsDiff: '0',
  tokenDiffs: [
    { mint: MINT, chainBaseUnits: '1000000', ledgerBaseUnits: '0', diffBaseUnits: '1000000' },
  ],
};

function problem(status: number, code: string, detail?: string) {
  return new ApiError(status, code, undefined, [], undefined, detail, 'corr-admin-1');
}

async function renderIn(node: React.ReactNode, api: jest.Mocked<AdminApi>) {
  tokenStore.set('admin-access-token');
  render(<Providers api={api}>{node}</Providers>);
  await act(async () => {
    for (let index = 0; index < 20; index++) {
      await Promise.resolve();
    }
  });
}

async function click(testId: string) {
  await act(async () => {
    fireEvent.click(screen.getByTestId(testId));
  });
  await act(async () => {
    for (let index = 0; index < 20; index++) {
      await Promise.resolve();
    }
  });
}

function reason(dialog: string, value = 'motivo da ação') {
  fireEvent.change(screen.getByTestId(`${dialog}-reason`), { target: { value } });
}

afterEach(() => tokenStore.clear());

describe('AdminActionDialog', () => {
  function Harness({ onSubmit }: { onSubmit: (reason: string) => void }) {
    return (
      <AdminActionDialog
        title="Ação"
        effect="Efeito"
        testId="d"
        state={{ kind: 'idle' }}
        onSubmit={onSubmit}
        onClose={() => undefined}
      />
    );
  }

  it('counts the reason in characters and accepts 1 and 500, refusing 0 and 501 before sending (U6-NFR1.11)', () => {
    expect(reasonLength('😀😀')).toBe(2);
    expect(reasonProblem('')).toBe(text.reason.empty);
    expect(reasonProblem('   ')).toBe(text.reason.empty);
    expect(reasonProblem('a')).toBeNull();
    expect(reasonProblem('😀'.repeat(500))).toBeNull();
    expect(reasonProblem('a'.repeat(501))).toBe(text.reason.tooLong);
    const onSubmit = jest.fn();
    render(<Harness onSubmit={onSubmit} />);

    fireEvent.click(screen.getByTestId('d-submit'));
    expect(screen.getByTestId('d-reason-error')).toHaveTextContent(text.reason.empty);
    fireEvent.change(screen.getByTestId('d-reason'), { target: { value: 'a'.repeat(501) } });
    fireEvent.click(screen.getByTestId('d-submit'));
    expect(onSubmit).not.toHaveBeenCalled();
    fireEvent.change(screen.getByTestId('d-reason'), { target: { value: ' certo ' } });
    fireEvent.click(screen.getByTestId('d-submit'));
    expect(onSubmit).toHaveBeenCalledWith('certo');
  });

  it('is a modal with the first focus on "Cancelar" (U6-NFR6.2)', () => {
    render(<Harness onSubmit={jest.fn()} />);

    expect(screen.getByRole('alertdialog')).toHaveAttribute('open');
    expect(screen.getByTestId('d-cancel')).toHaveFocus();
  });

  it('a refusal without its own text shows the detail and the id for support (BR9.3)', () => {
    const error = toAdminRealError(problem(422, 'SOMETHING', 'Detalhe do servidor'));

    expect(error.text).toBe('Detalhe do servidor');
    expect(toAdminRealError(new Error('x')).text).toBe(text.genericError);
    expect(toAdminRealError(problem(409, 'PILOT_FULL')).text).toBe(
      'Limite de 20 convidados atingido',
    );
  });
});

describe('A1 users', () => {
  it('the route lists the people 20 per page, without the main wallet (U6-NFR3.6, U6-NFR1.8)', async () => {
    const api = fakeAdminApi({
      listRealUsers: jest.fn(async () => ({ items: [USER], nextCursor: 'c1' })),
    });
    await renderIn(<RealUsersRoute />, api);

    const row = await screen.findByTestId(`admin-real-user-${USER_ID}`);
    expect(api.listRealUsers).toHaveBeenCalledWith(null);
    expect(row).toHaveTextContent('Automática');
    expect(row).toHaveTextContent('3,000000000 SOL');
    expect(row).toHaveTextContent('8,400000000 SOL');
    expect(within(row).getByTestId(`admin-real-user-release-${USER_ID}`)).toHaveTextContent(
      'Ativa',
    );
    expect(document.body.innerHTML).not.toContain(MAIN_FULL);
    expect(screen.getByTestId('admin-real-badge')).toHaveTextContent('◆ REAL');
  });

  it('"Carregar mais" reads the next page by cursor', async () => {
    const listRealUsers = jest
      .fn()
      .mockResolvedValueOnce({ items: [USER], nextCursor: 'c1' })
      .mockResolvedValueOnce({ items: [{ ...USER, userId: 'u2' }] });
    await renderIn(<RealUsersScreen />, fakeAdminApi({ listRealUsers }));
    await click('admin-real-users-more');

    expect(listRealUsers).toHaveBeenLastCalledWith('c1');
    expect(screen.getByTestId('admin-real-user-u2')).toBeInTheDocument();
  });

  it('an empty list and an error with "Tentar de novo" are states in text (BR8.1)', async () => {
    const listRealUsers = jest
      .fn()
      .mockRejectedValueOnce(problem(503, 'SERVICE_UNAVAILABLE'))
      .mockResolvedValue({ items: [] });
    await renderIn(<RealUsersScreen />, fakeAdminApi({ listRealUsers }));

    expect(screen.getByTestId('admin-real-users-error')).toHaveTextContent(text.users.error);
    await click('admin-real-users-error-retry');
    expect(screen.getByTestId('admin-real-users-empty')).toHaveTextContent(text.users.empty);
  });

  it('a missing amount is "sem dado", never zero', () => {
    expect(solText(undefined)).toBe('sem dado');
    expect(solText('1')).toBe('0,000000001 SOL');
  });
});

describe('A1 detail', () => {
  it('the route shows the main wallet abbreviated and no key field (BR9.5, U6-NFR1.8)', async () => {
    await renderIn(<RealUserRoute />, fakeAdminApi({ getRealUser: jest.fn(async () => USER) }));

    expect(await screen.findByTestId('admin-real-detail-main')).toHaveTextContent('Main…1111');
    expect(screen.getByTestId('admin-real-detail-orders')).toHaveTextContent('CONFIRMED');
    expect(screen.queryByLabelText(/chave|seed|privada/i)).toBeNull();
  });

  it('"Liberar" a 21st guest shows "Limite de 20 convidados atingido" (S33, BR9.3)', async () => {
    const releaseRealUser = jest.fn(async () => {
      throw problem(409, 'PILOT_FULL');
    });
    await renderIn(
      <RealUserDetailScreen userId={USER_ID} timeZone={ZONE} />,
      fakeAdminApi({ getRealUser: jest.fn(async () => USER), releaseRealUser }),
    );
    await click('admin-real-release');
    fireEvent.change(screen.getByTestId('admin-real-network'), { target: { value: 'MAINNET' } });
    reason('admin-real-release-dialog');
    await click('admin-real-release-dialog-submit');

    expect(releaseRealUser).toHaveBeenCalledWith(
      USER_ID,
      { network: 'MAINNET', reason: 'motivo da ação' },
      'key-1',
    );
    expect(screen.getByTestId('admin-real-release-dialog-error')).toHaveTextContent(
      'Limite de 20 convidados atingido',
    );
    expect(screen.getByTestId('admin-real-release-dialog-error')).toHaveTextContent('corr-admin-1');
  });

  it('a retry of the same action reuses the key; the success closes and reads the person again (BR9.2)', async () => {
    const revokeRealUser = jest
      .fn()
      .mockRejectedValueOnce(problem(503, 'SERVICE_UNAVAILABLE'))
      .mockResolvedValue({ userId: USER_ID, revoked: true, cancelledOrders: 0 });
    const getRealUser = jest.fn(async () => USER);
    await renderIn(
      <RealUserDetailScreen userId={USER_ID} timeZone={ZONE} />,
      fakeAdminApi({ getRealUser, revokeRealUser }),
    );
    await click('admin-real-revoke');
    reason('admin-real-revoke-dialog');
    await click('admin-real-revoke-dialog-submit');
    await click('admin-real-revoke-dialog-submit');

    expect(revokeRealUser.mock.calls[0]?.[2]).toBe(revokeRealUser.mock.calls[1]?.[2]);
    expect(screen.queryByRole('alertdialog')).toBeNull();
    expect(getRealUser).toHaveBeenCalledTimes(2);
  });

  it('"Baixar teto" only lowers: a higher cap cannot be confirmed; a lower one is sent in lamports', async () => {
    const lowerRealCap = jest.fn(async () => ({ userId: USER_ID, capLamports: '500000000' }));
    await renderIn(
      <RealUserDetailScreen userId={USER_ID} timeZone={ZONE} />,
      fakeAdminApi({ getRealUser: jest.fn(async () => USER), lowerRealCap }),
    );
    await click('admin-real-cap');
    fireEvent.change(screen.getByTestId('admin-real-cap-input'), { target: { value: '5' } });
    expect(screen.getByTestId('admin-real-cap-dialog-submit')).toBeDisabled();
    fireEvent.change(screen.getByTestId('admin-real-cap-input'), { target: { value: '0,5' } });
    reason('admin-real-cap-dialog');
    await click('admin-real-cap-dialog-submit');

    expect(lowerRealCap).toHaveBeenCalledWith(
      USER_ID,
      { capLamports: '500000000', reason: 'motivo da ação' },
      'key-1',
    );
  });
});

describe('A1 kill switch REAL', () => {
  it('has its own title "Kill switch REAL", apart from the PAPER one (BR9.4)', async () => {
    await renderIn(<RealKillSwitchRoute />, fakeAdminApi());

    expect(await screen.findByRole('heading', { level: 1 })).toHaveTextContent('Kill switch REAL');
    expect(screen.getByText(text.killSwitch.separate)).toBeInTheDocument();
    expect(screen.getByTestId('admin-real-kill-switch-state')).toHaveTextContent('Desligado');
  });

  it('switching it on with a reason says "Novas ordens REAL bloqueadas para todos; saques liberados" (S35)', async () => {
    const api = fakeAdminApi();
    await renderIn(<RealKillSwitchScreen timeZone={ZONE} />, api);
    await click('admin-real-kill-switch-toggle');
    reason('admin-real-kill-switch-dialog', 'teste');
    await click('admin-real-kill-switch-dialog-submit');

    expect(api.activateRealKillSwitch).toHaveBeenCalledWith({ reason: 'teste' }, 'key-1');
    expect(screen.getByTestId('admin-real-kill-switch-state')).toHaveTextContent(
      'Novas ordens REAL bloqueadas para todos; saques liberados',
    );
  });

  it('switching it off sends the DELETE with the reason', async () => {
    const api = fakeAdminApi({
      getRealKillSwitch: jest.fn(async () => ({
        active: true,
        version: 3,
        activatedAt: '2026-11-16T12:00:00Z',
        reason: 'incidente',
      })),
    });
    await renderIn(<RealKillSwitchScreen timeZone={ZONE} />, api);
    expect(screen.getByTestId('admin-real-kill-switch-state')).toHaveTextContent('Ligado');
    await click('admin-real-kill-switch-toggle');
    reason('admin-real-kill-switch-dialog', 'fim');
    await click('admin-real-kill-switch-dialog-submit');

    expect(api.deactivateRealKillSwitch).toHaveBeenCalledWith({ reason: 'fim' }, 'key-1');
    expect(screen.getByTestId('admin-real-kill-switch-state')).toHaveTextContent('Desligado');
  });
});

describe('A1 divergences', () => {
  it('fills the compensation with the measure; the person confirms and types no value (S34, BR9.6)', async () => {
    const compensateRealDivergence = jest.fn(async () => ({ ...DIVERGENCE, state: 'COMPENSATED' }));
    await renderIn(
      <RealDivergencesRoute />,
      fakeAdminApi({
        listRealDivergences: jest.fn(async () => ({ items: [DIVERGENCE] })),
        compensateRealDivergence,
      }),
    );
    await click(`admin-real-divergence-compensate-${DIVERGENCE.divergenceId}`);

    expect(screen.getByTestId('admin-real-compensation-items')).toHaveTextContent(
      '1000000 Test…1111',
    );
    expect(within(screen.getByRole('alertdialog')).getAllByRole('textbox')).toHaveLength(1);
    reason('admin-real-compensation-dialog', 'token recebido sem ordem');
    await click('admin-real-compensation-dialog-submit');
    expect(compensateRealDivergence).toHaveBeenCalledWith(
      DIVERGENCE.divergenceId,
      { items: [{ asset: MINT, amount: '1000000' }], reason: 'token recebido sem ordem' },
      'key-1',
    );
  });

  it('a wrong measure is refused in text (COMPENSATION_MISMATCH)', async () => {
    await renderIn(
      <RealDivergencesScreen timeZone={ZONE} />,
      fakeAdminApi({
        listRealDivergences: jest.fn(async () => ({ items: [DIVERGENCE] })),
        compensateRealDivergence: jest.fn(async () => {
          throw problem(422, 'COMPENSATION_MISMATCH');
        }),
      }),
    );
    await click(`admin-real-divergence-compensate-${DIVERGENCE.divergenceId}`);
    reason('admin-real-compensation-dialog');
    await click('admin-real-compensation-dialog-submit');

    expect(screen.getByTestId('admin-real-compensation-dialog-error')).toHaveTextContent(
      text.errors.COMPENSATION_MISMATCH as string,
    );
  });

  it('the items keep SOL in lamports and skip zero measures', () => {
    expect(compensationItems({ ...DIVERGENCE, lamportsDiff: '-5000', tokenDiffs: [] })).toEqual([
      { asset: 'SOL', amount: '-5000' },
    ]);
    expect(
      compensationItems({
        ...DIVERGENCE,
        lamportsDiff: '0',
        tokenDiffs: [{ mint: MINT, diffBaseUnits: '0' }],
      }),
    ).toEqual([]);
  });

  it('a resolved divergence shows its state and no action', async () => {
    await renderIn(
      <RealDivergencesScreen timeZone={ZONE} />,
      fakeAdminApi({
        listRealDivergences: jest.fn(async () => ({
          items: [{ ...DIVERGENCE, state: 'RESOLVED' }],
        })),
      }),
    );

    expect(
      screen.getByTestId(`admin-real-divergence-${DIVERGENCE.divergenceId}`),
    ).toHaveTextContent('Resolvida');
    expect(
      screen.queryByTestId(`admin-real-divergence-compensate-${DIVERGENCE.divergenceId}`),
    ).toBeNull();
  });
});

describe('A1 tokens and restore', () => {
  it('blocks a token with mint and reason, and unblocks it with the confirmation', async () => {
    const blockRealToken = jest.fn(async () => ({ mint: MINT, reason: 'golpe' }));
    const unblockRealToken = jest.fn(async () => ({ mint: MINT, unblocked: true }));
    await renderIn(
      <RealTokensRoute />,
      fakeAdminApi({
        listRealTokenBlocks: jest.fn(async () => ({
          items: [{ mint: MINT, reason: 'golpe', blockedAt: '2026-11-16T12:00:00Z' }],
        })),
        blockRealToken,
        unblockRealToken,
      }),
    );
    await click('admin-real-token-block');
    fireEvent.change(screen.getByTestId('admin-real-token-mint'), {
      target: { value: 'not a mint' },
    });
    expect(screen.getByTestId('admin-real-token-block-dialog-submit')).toBeDisabled();
    fireEvent.change(screen.getByTestId('admin-real-token-mint'), { target: { value: MINT } });
    reason('admin-real-token-block-dialog', 'golpe');
    await click('admin-real-token-block-dialog-submit');
    expect(blockRealToken).toHaveBeenCalledWith({ mint: MINT, reason: 'golpe' }, 'key-1');

    await click(`admin-real-token-unblock-${MINT}`);
    await click('admin-real-token-unblock-dialog-submit');
    expect(unblockRealToken).toHaveBeenCalledWith(MINT, 'key-2');
  });

  it('a restore not clean yet is refused in text; a confirmation with a reason is recorded', async () => {
    const confirmRealRestore = jest
      .fn()
      .mockRejectedValueOnce(problem(409, 'RESTORE_NOT_CLEAN'))
      .mockResolvedValue({ markerId: 'm1' });
    await renderIn(<RealRestoreRoute />, fakeAdminApi({ confirmRealRestore }));
    await click('admin-real-restore-confirm');
    reason('admin-real-restore-dialog', 'banco restaurado');
    await click('admin-real-restore-dialog-submit');
    expect(screen.getByTestId('admin-real-restore-dialog-error')).toHaveTextContent(
      text.errors.RESTORE_NOT_CLEAN as string,
    );
    await click('admin-real-restore-dialog-submit');

    expect(screen.getByTestId('admin-real-restore-notice')).toHaveTextContent(text.restore.done);
  });

  it('without a restore pending it says so', async () => {
    await renderIn(
      <RealRestoreScreen />,
      fakeAdminApi({
        confirmRealRestore: jest.fn(async () => {
          throw problem(404, 'NOT_FOUND');
        }),
      }),
    );
    await click('admin-real-restore-confirm');
    reason('admin-real-restore-dialog', 'x');
    await click('admin-real-restore-dialog-submit');

    expect(screen.getByTestId('admin-real-restore-dialog-effect')).toHaveTextContent(
      text.restore.none,
    );
  });
});

describe('A1 safety', () => {
  it('nothing of the REAL section goes to the browser storage (U6-NFR1.10)', async () => {
    await renderIn(
      <RealUserDetailScreen userId={USER_ID} timeZone={ZONE} />,
      fakeAdminApi({ getRealUser: jest.fn(async () => USER) }),
    );
    await click('admin-real-revoke');

    expect(window.localStorage.length).toBe(0);
    expect(window.sessionStorage.length).toBe(0);
    expect(document.cookie).toBe('');
  });

  it('the REAL label keeps 4.5:1 on its amber (U6-NFR6.4)', () => {
    expect(contrastRatio(colors.background, colors.real)).toBeGreaterThanOrEqual(4.5);
  });

  it('Esc closes the dialog without any call and the focus goes back', async () => {
    const api = fakeAdminApi();
    await renderIn(<RealKillSwitchScreen timeZone={ZONE} />, api);
    screen.getByTestId('admin-real-kill-switch-toggle').focus();
    await click('admin-real-kill-switch-toggle');

    fireEvent.keyDown(screen.getByRole('alertdialog'), { key: 'Escape' });

    expect(screen.queryByRole('alertdialog')).toBeNull();
    expect(screen.getByTestId('admin-real-kill-switch-toggle')).toHaveFocus();
    expect(api.activateRealKillSwitch).not.toHaveBeenCalled();
  });
});
