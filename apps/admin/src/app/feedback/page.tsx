import { AdminShell } from '@/features/layout/AdminShell';
import { FeedbackListPage } from '@/features/feedback/FeedbackListPage';

/** The comments of the guests (US6.1). */
export default function FeedbackRoute() {
  return (
    <AdminShell>
      <FeedbackListPage />
    </AdminShell>
  );
}
