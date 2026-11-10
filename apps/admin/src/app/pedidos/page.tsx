import { AdminShell } from '@/features/layout/AdminShell';
import { AdminDataRequestList } from '@/features/data-requests/AdminDataRequestList';

/** u4-data-requests (T6): the data subject requests (US5.3, US5.9). */
export default function DataRequestsRoute() {
  return (
    <AdminShell>
      <AdminDataRequestList />
    </AdminShell>
  );
}
