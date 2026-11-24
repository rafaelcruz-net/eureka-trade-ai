import { Suspense } from 'react';
import { BotList } from '@/features/bots/BotList';

/** The bot list (U5); 261121-paper-real-unico U3 reads the filter by mode from `?mode=`. */
export default function BotsPage() {
  return (
    <Suspense>
      <BotList />
    </Suspense>
  );
}
