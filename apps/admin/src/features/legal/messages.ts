/**
 * pt-BR texts of the public legal pages and of the footer of the administrator app (u1-legal-documents,
 * frontend-components.md). No legal text lives here: the documents come from the API; these are only the labels.
 */
export const legalMessages = {
  brand: 'MemeTrade Admin',
  signIn: 'Entrar',
  title: {
    terms: 'Termos de uso',
    privacy: 'Política de privacidade',
  },
  version: (version: string, date: string) => `Versão ${version} · vigente desde ${date}`,
  provisional: '(versão provisória)',
  notCurrent: 'Esta não é a versão vigente.',
  seeCurrent: 'Ver a versão vigente',
  notPublishable: 'Esta versão ainda não foi publicada para convidados.',
  paperNotice:
    'Ambiente de testes PAPER: todas as operações do beta são simuladas e nenhuma usa dinheiro real.',
  summaryHeading: 'Resumo',
  tocLabel: 'Nesta página',
  tocToggleOpen: 'Mostrar o índice',
  tocToggleClose: 'Esconder o índice',
  riskNoticeHeading: 'Aviso de risco',
  privacyMissing: 'A política de privacidade desta versão ainda não foi publicada.',
  retentionHeading: 'Retenção dos dados',
  retentionCaption:
    'Tabela de retenção: cada dado, para que serve, por quanto tempo fica guardado e o que acontece no fim',
  retentionColumns: {
    dataItem: 'Dado',
    purpose: 'Finalidade',
    period: 'Prazo',
    endAction: 'No fim do prazo',
  },
  retentionEmpty: 'A tabela de retenção desta versão ainda não foi publicada.',
  deploymentHeading: 'Local, transferência e encarregado',
  deployment: {
    storageLocation: 'Local de armazenamento',
    internationalTransfer: 'Transferência internacional',
    dpoEmail: 'E-mail do encarregado de dados',
  },
  toBeDefined: 'a definir',
  requestHeading: 'Pedidos de titulares',
  requestButton: 'Fazer um pedido',
  requestGuestApp:
    'Os pedidos são feitos na Conta do app do convidado; o botão leva à entrada desse app.',
  requestByEmail:
    'Se você não consegue entrar (conta anonimizada ou carteira perdida), faça o pedido pelo e-mail do encarregado de dados:',
  historyHeading: 'Histórico de versões',
  historyCurrent: '(vigente)',
  historyProvisional: '(provisória)',
  errorTitle: 'Não foi possível carregar o documento',
  errorBody: 'O servidor não respondeu. Verifique a conexão e tente de novo.',
  retry: 'Tentar de novo',
  notFoundTitle: 'Versão não encontrada',
  notFoundBody: 'A versão pedida não existe ou não foi publicada.',
  loading: 'Carregando o documento…',
  footer: {
    label: 'Documentos legais',
    terms: 'Termos de uso',
    privacy: 'Privacidade',
  },
} as const;

export type LegalKind = 'terms' | 'privacy';

export const LEGAL_PATH: Record<LegalKind, string> = {
  terms: '/termos',
  privacy: '/privacidade',
};

/** The effective date as a date of the calendar in UTC (the catalog dates are UTC instants). */
export function formatEffectiveDate(instant: string): string {
  const date = new Date(instant);
  if (Number.isNaN(date.getTime())) {
    return instant;
  }
  return `${new Intl.DateTimeFormat('pt-BR', { dateStyle: 'short', timeZone: 'UTC' }).format(date)} (UTC)`;
}
