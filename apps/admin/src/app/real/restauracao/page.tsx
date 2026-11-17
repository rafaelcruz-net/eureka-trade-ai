import { AdminShell } from '@/features/layout/AdminShell';
import { RealRestoreScreen } from '@/features/real/screens/RealRestoreScreen';

/** 261113-trading-real-bot U6, A1 `/real/restauracao`: the confirmation of the restore. */
export default function RealRestoreRoute() {
  return (
    <AdminShell>
      <RealRestoreScreen />
    </AdminShell>
  );
}
