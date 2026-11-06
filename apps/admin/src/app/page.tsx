import { AdminShell } from '@/features/layout/AdminShell';
import { SystemStatusPage } from '@/features/status/SystemStatusPage';

/** A1: the system status is the home of the administrator app. */
export default function StatusRoute() {
  return (
    <AdminShell>
      <SystemStatusPage />
    </AdminShell>
  );
}
