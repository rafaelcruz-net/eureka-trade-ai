import { AdminShell } from '@/features/layout/AdminShell';
import { RealTokensScreen } from '@/features/real/screens/RealTokensScreen';

/** 261113-trading-real-bot U6, A1 `/real/tokens`: the tokens blocked for every bot wallet. */
export default function RealTokensRoute() {
  return (
    <AdminShell>
      <RealTokensScreen />
    </AdminShell>
  );
}
