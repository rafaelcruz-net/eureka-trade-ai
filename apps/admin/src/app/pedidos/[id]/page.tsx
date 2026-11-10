'use client';

import { useParams } from 'next/navigation';
import { AdminShell } from '@/features/layout/AdminShell';
import { AdminDataRequestDetail } from '@/features/data-requests/AdminDataRequestDetail';

/** u4-data-requests (T6a, T6c): one data subject request (US5.3 to US5.6). */
export default function DataRequestRoute() {
  const params = useParams<{ id: string }>();
  return (
    <AdminShell>
      <AdminDataRequestDetail requestId={params.id} />
    </AdminShell>
  );
}
