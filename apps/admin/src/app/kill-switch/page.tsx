import { AdminShell } from '@/features/layout/AdminShell';
import { KillSwitchPage } from '@/features/killswitch/KillSwitchPage';

/** The kill switch screen (US7.2). */
export default function KillSwitchRoute() {
  return (
    <AdminShell>
      <KillSwitchPage />
    </AdminShell>
  );
}
