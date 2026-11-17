import { AdminShell } from '@/features/layout/AdminShell';
import { RealUsersScreen } from '@/features/real/screens/RealUsersScreen';

/** 261113-trading-real-bot U6, A1 `/real`: the people with a bot wallet. */
export default function RealUsersRoute() {
  return (
    <AdminShell>
      <RealUsersScreen />
    </AdminShell>
  );
}
