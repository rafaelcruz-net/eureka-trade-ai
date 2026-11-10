import { AdminShell } from '@/features/layout/AdminShell';
import { RegisterEmailRequestForm } from '@/features/data-requests/RegisterEmailRequestForm';

/** u4-data-requests (T6b): a request received by the e-mail of the DPO (US5.6). */
export default function RegisterDataRequestRoute() {
  return (
    <AdminShell>
      <RegisterEmailRequestForm />
    </AdminShell>
  );
}
