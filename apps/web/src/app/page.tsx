import { redirect } from 'next/navigation';

/** U1 has no dashboard yet: the home page opens the scanner list (F1 step 9). */
export default function HomePage() {
  redirect('/scanner');
}
