import type { LegalDocuments, LegalVersionRef } from '@/features/legal/legal-api';

/**
 * Test fixtures of L1, typed with the generated contract types (a change of the contract breaks them). Fictitious
 * texts only: never the real v2 text, a real wallet or personal data.
 */
export const CURRENT: LegalDocuments = {
  version: 'v2',
  effectiveAt: '2026-10-15T00:00:00Z',
  provisional: false,
  publishable: false,
  terms: {
    summary: ['Tópico 1', 'Tópico 2', 'Tópico 3', 'Tópico 4', 'Tópico 5'],
    sections: [
      {
        id: 'uso-do-beta',
        title: 'Uso do beta',
        paragraphs: ['Primeiro parágrafo.', 'Texto com <script>alert(1)</script> literal.'],
      },
      { id: 'contas', title: 'Contas', paragraphs: ['Texto sobre contas.'] },
    ],
  },
  privacy: {
    summary: ['Política 1', 'Política 2', 'Política 3', 'Política 4', 'Política 5', 'Política 6'],
    sections: [
      { id: 'dados', title: 'Dados que tratamos', paragraphs: ['Texto sobre dados.'] },
      { id: 'direitos', title: 'Seus direitos', paragraphs: ['Texto sobre direitos.'] },
    ],
  },
  riskNotice: {
    summary: null,
    sections: [{ id: 'riscos', title: 'Riscos', paragraphs: ['Tokens são voláteis.'] }],
  },
  deployment: { storageLocation: null, internationalTransfer: null, dpoEmail: null },
  retention: [
    {
      dataItem: 'Trilha de auditoria',
      purpose: 'Segurança',
      period: '12 meses',
      endAction: 'Apagar',
    },
    {
      dataItem: 'Feedback',
      purpose: 'Melhorar o produto',
      period: '180 dias',
      endAction: 'Apagar',
    },
  ],
};

/** A legacy version: no policy, no summaries, one implicit section, no retention table (BR1.15). */
export const LEGACY: LegalDocuments = {
  version: 'v1',
  effectiveAt: '2026-10-01T00:00:00Z',
  provisional: true,
  publishable: false,
  terms: {
    summary: null,
    sections: [{ id: 'termos-de-uso', title: 'Termos de uso', paragraphs: ['Termos antigos.'] }],
  },
  privacy: null,
  riskNotice: {
    summary: null,
    sections: [{ id: 'aviso-de-risco', title: 'Aviso de risco', paragraphs: ['Aviso antigo.'] }],
  },
  deployment: { storageLocation: null, internationalTransfer: null, dpoEmail: null },
  retention: [],
};

export const HISTORY: LegalVersionRef[] = [
  { version: 'v2', effectiveAt: '2026-10-15T00:00:00Z', provisional: false },
  { version: 'v1', effectiveAt: '2026-10-01T00:00:00Z', provisional: true },
];

export const CONFIGURED: LegalDocuments = {
  ...CURRENT,
  publishable: true,
  deployment: {
    storageLocation: 'Brasil (região de teste)',
    internationalTransfer: 'Não há transferência internacional',
    dpoEmail: 'encarregado@example.test',
  },
};

/** A fetch double answering L1 by path; anything else is a 500. */
export function legalFetch(answers: Record<string, { status: number; body?: unknown }>) {
  return jest.fn(async (input: string | URL | Request) => {
    const url = String(input);
    const path = url.slice(url.indexOf('/api/v1/legal') + '/api/v1/legal'.length);
    const answer = answers[path] ?? { status: 500 };
    return new Response(answer.body === undefined ? null : JSON.stringify(answer.body), {
      status: answer.status,
      headers: { 'Content-Type': 'application/json' },
    });
  }) as jest.Mock & typeof fetch;
}
