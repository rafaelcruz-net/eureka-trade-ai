import { AdminShell } from '@/features/layout/AdminShell';
import { RealKillSwitchScreen } from '@/features/real/screens/RealKillSwitchScreen';

/** 261113-trading-real-bot U6, A1 `/real/kill-switch`: the kill switch REAL, apart from the one of the PAPER. */
export default function RealKillSwitchRoute() {
  return (
    <AdminShell>
      <RealKillSwitchScreen />
    </AdminShell>
  );
}
