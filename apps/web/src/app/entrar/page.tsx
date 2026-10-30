import { Suspense } from 'react';
import { LoginScreen } from '@/features/auth/LoginScreen';

export default function EntrarPage() {
  return (
    <Suspense>
      <LoginScreen />
    </Suspense>
  );
}
