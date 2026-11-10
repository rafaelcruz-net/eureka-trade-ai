/**
 * pt-BR texts of the reacceptance of the terms (u3-reacceptance, frontend-components.md): the screen /novo-aceite, the
 * summary and the two boxes shared with the invitation, and the status panel of the Account. No legal text lives here:
 * the documents come from the API (L1); these are only the labels around them.
 */
export const reacceptanceMessages = {
  title: 'Novo aceite dos termos',
  intro:
    'Os termos de uso ou a política de privacidade mudaram. Leia o resumo da versão nova e aceite para continuar usando o MemeTrade.',
  botsKeepRunning:
    'Enquanto você não aceita, seus bots PAPER ativos continuam rodando, mas as outras telas ficam bloqueadas.',
  version: (version: string) => `Versão ${version}`,
  loading: 'Carregando os documentos…',
  errorTitle: 'Não foi possível carregar os documentos',
  errorBody: 'O servidor não respondeu. Verifique a conexão e tente de novo.',
  retry: 'Tentar de novo',
  accept: 'Aceitar e continuar',
  submitting: 'Registrando o seu aceite…',
  bothRequired: 'Marque as duas caixas para continuar.',
  waitDocuments: 'Aguarde os documentos carregarem para marcar as caixas.',
  outdated: 'Os termos mudaram enquanto você lia. Leia a versão nova.',
  failed: 'Não foi possível registrar o seu aceite. Tente de novo.',
  sessionExpired: 'Sua sessão terminou. Entre de novo para aceitar os termos.',
  summary: {
    label: 'Resumo da versão vigente',
    termsHeading: 'Resumo dos termos de uso',
    privacyHeading: 'Resumo da política de privacidade',
    unavailable: 'Esta versão não tem resumo. Leia o documento completo.',
    privacyMissing: 'A política de privacidade desta versão ainda não foi publicada.',
    linksLabel: 'Documentos completos',
    readTerms: 'Ler os termos de uso completos (abre em outra aba)',
    readPrivacy: 'Ler a política de privacidade completa (abre em outra aba)',
  },
  consent: {
    legend: 'Seu aceite',
    terms: 'Li e aceito os termos de uso e a política de privacidade',
    risk: 'Li e entendi o aviso de risco',
  },
  invite: {
    loading: 'Carregando os documentos legais…',
    error:
      'Não foi possível carregar os documentos legais. As caixas ficam desabilitadas até eles carregarem.',
    retry: 'Tentar de novo',
  },
  status: {
    heading: 'Situação dos termos',
    accepted: (version: string, date: string) => `Você aceitou a versão ${version} em ${date}.`,
    upToDate: 'Em dia com a versão vigente.',
    outdated: (current: string) =>
      `Há uma versão nova dos termos (${current}) esperando o seu aceite.`,
    none: 'Nenhum aceite da versão vigente está registrado para esta conta.',
    acceptNew: 'Aceitar a nova versão',
  },
} as const;
