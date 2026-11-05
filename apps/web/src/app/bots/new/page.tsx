import { Suspense } from 'react';
import { BotCreate } from '@/features/bots/BotCreate';

/** F1 step 1: template and name of a new PAPER bot (U5). */
export default function NewBotPage() {
  return (
    <Suspense>
      <BotCreate />
    </Suspense>
  );
}
