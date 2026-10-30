import type { Metadata } from 'next';
import type { ReactNode } from 'react';
import { DesktopOnlyNotice } from '@/components/DesktopOnlyNotice';
import { ApiProvider } from '@/lib/api/ApiProvider';
import { SessionProvider } from '@/features/auth/SessionProvider';
import { messages } from '@/lib/i18n/messages';
import './globals.css';

export const metadata: Metadata = {
  title: messages.app.name,
};

// Rendered per request so the CSP nonce set by the middleware applies to Next.js scripts.
export const dynamic = 'force-dynamic';

export default function RootLayout({ children }: { children: ReactNode }) {
  // Runtime (server-side) configuration; never a NEXT_PUBLIC_* variable.
  const apiOrigin = process.env.API_ORIGIN ?? 'http://localhost:8080';
  return (
    <html lang="pt-BR">
      <body>
        <a className="skip-link" href="#conteudo" data-testid="skip-to-content-link">
          {messages.app.skipToContent}
        </a>
        <ApiProvider apiOrigin={apiOrigin}>
          <SessionProvider>
            <DesktopOnlyNotice />
            {children}
          </SessionProvider>
        </ApiProvider>
      </body>
    </html>
  );
}
