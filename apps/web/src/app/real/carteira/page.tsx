import { redirect } from 'next/navigation';

/**
 * /real/carteira: the minimal screen of the U1 became the REAL area of the U6. 261113-trading-real-bot U6 (BR1.3): the
 * old links keep working and land on the overview, which itself sends a person without a bot wallet to the activation.
 */
export default function BotWalletPage(): never {
  redirect('/real');
}
