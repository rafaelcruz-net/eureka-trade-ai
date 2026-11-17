import { AdminShell } from '@/features/layout/AdminShell';
import { RealDivergencesScreen } from '@/features/real/screens/RealDivergencesScreen';

/** 261113-trading-real-bot U6, A1 `/real/divergencias`: the divergences and their compensation. */
export default function RealDivergencesRoute() {
  return (
    <AdminShell>
      <RealDivergencesScreen />
    </AdminShell>
  );
}
