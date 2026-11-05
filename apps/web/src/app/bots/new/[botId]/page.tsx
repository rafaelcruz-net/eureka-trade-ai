import { Suspense } from 'react';
import { BotWizard } from '@/features/bots/BotWizard';

/** S4, the wizard of a PAPER bot draft (U5). */
export default function BotWizardPage() {
  return (
    <Suspense>
      <BotWizard />
    </Suspense>
  );
}
