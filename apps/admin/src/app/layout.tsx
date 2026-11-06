import type { Metadata } from 'next';
import type { ReactNode } from 'react';
import { ApiProvider } from '@/lib/api/ApiProvider';
import { messages } from '@/lib/i18n/messages';
import { AdminSessionProvider } from '@/features/auth/AdminSessionProvider';
import './globals.css';

export const metadata: Metadata = {
  title: messages.app.name,
  robots: { index: false, follow: false },
};

// Rendered per request so the CSP nonce set by the middleware applies to the Next.js scripts (security-design.md).
export const dynamic = 'force-dynamic';

export default function RootLayout({ children }: { children: ReactNode }) {
  // Runtime (server-side) configuration; never a NEXT_PUBLIC_* variable (R-04 of the Infrastructure Design).
  const apiOrigin = process.env.API_ORIGIN ?? 'http://localhost:8080';
  return (
    <html lang="pt-BR">
      <body>
        <a className="skip-link" href="#conteudo" data-testid="admin-skip-to-content">
          {messages.app.skipToContent}
        </a>
        <ApiProvider apiOrigin={apiOrigin}>
          <AdminSessionProvider>{children}</AdminSessionProvider>
        </ApiProvider>
      </body>
    </html>
  );
}
