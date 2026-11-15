import type { ScannerPage } from '@/lib/api/guest-api';
import { messages } from '@/lib/i18n/messages';

const text = messages.scanner.dataSource;

type DataSource = ScannerPage['dataSource'];

/** Only an HTTPS link of the attribution becomes a link; anything else stays plain text. */
function safeLink(url: string): string | null {
  try {
    const parsed = new URL(url);
    return parsed.protocol === 'https:' ? parsed.toString() : null;
  } catch {
    return null;
  }
}

/**
 * 261113-trading-real-bot U2 (BR1.8, NFR7.1): where the market data comes from. With the real source, the attribution
 * its provider requires ("Dados: CoinGecko") with its link; with a simulated source, a visible warning in text and icon,
 * never only color. Each state has its own test id.
 */
export function DataSourceNotice({
  dataSource,
  testIdPrefix,
}: {
  dataSource: DataSource | undefined;
  testIdPrefix: string;
}) {
  if (!dataSource) {
    return null;
  }
  if (dataSource.kind === 'SIMULATED') {
    return (
      <p className="notice notice-warning" data-testid={`${testIdPrefix}-simulated-active`}>
        <span aria-hidden="true">⚠ </span>
        {text.simulatedActive}
      </p>
    );
  }
  const attribution = dataSource.attribution;
  if (!attribution) {
    return null;
  }
  const href = safeLink(attribution.url);
  return (
    <p className="muted" data-testid={`${testIdPrefix}-attribution`}>
      {href ? (
        <a href={href} target="_blank" rel="noopener noreferrer">
          {attribution.text}
          <span className="visually-hidden"> {text.opensInNewTab}</span>
        </a>
      ) : (
        attribution.text
      )}
    </p>
  );
}
