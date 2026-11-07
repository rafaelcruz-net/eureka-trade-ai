import { DashboardScreen } from '@/features/dashboard/DashboardScreen';

/** U9 (BR1.1, FR12.1): the home page `/` is the dashboard S2, a client page with no server-side data fetching. */
export default function HomePage() {
  return <DashboardScreen />;
}
