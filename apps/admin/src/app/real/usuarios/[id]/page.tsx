'use client';

import { useParams } from 'next/navigation';
import { AdminShell } from '@/features/layout/AdminShell';
import { RealUserDetailScreen } from '@/features/real/screens/RealUserDetailScreen';

/** 261113-trading-real-bot U6, A1 `/real/usuarios/[id]`: one person of the REAL and its actions. */
export default function RealUserRoute() {
  const params = useParams<{ id: string }>();
  return (
    <AdminShell>
      <RealUserDetailScreen userId={params.id} />
    </AdminShell>
  );
}
