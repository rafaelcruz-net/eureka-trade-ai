import { Suspense } from 'react';
import { PnlScreen } from '@/features/pnl/PnlScreen';

/** S6 - P&L, ledger and reconciliation; 261121-paper-real-unico U3 reads the period and the comparison from the address. */
export default function PnlPage() {
  return (
    <Suspense>
      <PnlScreen />
    </Suspense>
  );
}
