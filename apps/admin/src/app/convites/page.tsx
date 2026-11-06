import { AdminShell } from '@/features/layout/AdminShell';
import { InvitesPage } from '@/features/invites/InvitesPage';

/** A2: the invitations. The guest app origin builds the link of a new invitation (read on the server). */
export default function InvitesRoute() {
  const guestAppOrigin = process.env.GUEST_APP_ORIGIN ?? 'http://localhost:3000';
  return (
    <AdminShell>
      <InvitesPage guestAppOrigin={guestAppOrigin} />
    </AdminShell>
  );
}
