import { AccountScreen } from '@/features/account/AccountScreen';

/** /conta (US1.5); the middleware answers it with Cache-Control no-store (NFR6.5). */
export default function AccountPage() {
  return <AccountScreen />;
}
