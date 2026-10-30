/**
 * Single pt-BR text catalog of the guest app (NFR14.1). Market terms stay in English with an explanation shown on
 * hover and focus (CT-UX-1). No screen hard-codes user-facing text outside this file.
 */
export const messages = {
  app: {
    name: 'MemeTrade Analytics',
    desktopOnly:
      'Esta versão é feita para computador. Use uma janela com pelo menos 1280 pixels de largura.',
    paperOnly: 'Ambiente de testes PAPER: nenhuma operação usa dinheiro real.',
    signOut: 'Sair',
    skipToContent: 'Pular para o conteúdo',
  },
  login: {
    title: 'Entrar',
    intro:
      'Conecte sua carteira Solana e assine uma mensagem para entrar. A assinatura só serve para entrar e não autoriza operações.',
    neverAskSecret:
      'Nunca pedimos sua frase secreta nem sua chave privada. Desconfie de qualquer tela que peça.',
    connectWith: (wallet: string) => `Conectar ${wallet}`,
    noWallet: 'Nenhuma carteira compatível encontrada.',
    noWalletHelp: 'Instale a extensão Phantom ou Solflare no navegador e recarregue a página.',
    signing: 'Confirme a assinatura na sua carteira…',
    sessionExpired: 'Sua sessão expirou. Entre de novo para continuar de onde parou.',
    rejectedTitle: 'Não foi possível entrar',
    tryAgain: 'Tentar de novo',
    rejected: {
      EXPIRED: 'O pedido de assinatura expirou. Tente de novo.',
      NONCE_ALREADY_USED: 'Este pedido de assinatura já foi usado. Tente de novo.',
      MESSAGE_MISMATCH: 'A mensagem assinada não confere com a enviada. Tente de novo.',
      WRONG_SIGNER: 'A assinatura não pertence a esta carteira.',
      UNKNOWN_ACCOUNT: 'Esta carteira não tem um convite ativo no beta.',
      ADMIN_WALLET: 'Esta carteira só entra pela área administrativa.',
      MALFORMED_MESSAGE: 'A mensagem assinada é inválida. Tente de novo.',
      UNKNOWN_CHALLENGE: 'O pedido de assinatura não foi encontrado. Tente de novo.',
      USER_CANCELLED: 'Você cancelou a assinatura na carteira.',
      RATE_LIMITED: 'Muitas tentativas. Aguarde um minuto e tente de novo.',
      NETWORK: 'Não conseguimos falar com o servidor. Verifique a conexão e tente de novo.',
      GENERIC: 'Algo deu errado ao entrar. Tente de novo.',
    } as Record<string, string>,
  },
  scanner: {
    title: 'Scanner',
    regionLabel: 'Lista de tokens',
    loading: 'Carregando a lista…',
    errorTitle: 'Não foi possível carregar a lista',
    errorBody: 'O servidor não respondeu. Seus dados não foram perdidos.',
    retry: 'Tentar de novo',
    empty: 'Nenhum token encontrado ainda. A fonte coleta a cada 15 segundos.',
    showing: (from: number, to: number, total: number) => `Exibindo ${from}–${to} de ${total}`,
    nextPage: 'Próxima página',
    previousPage: 'Página anterior',
    firstPage: 'Primeira página',
    updatedAt: (time: string) => `Atualizado às ${time}`,
    partial: (count: number) =>
      `Dados parciais: ${count} ${count === 1 ? 'token tem' : 'tokens têm'} campos unknown.`,
    source: (name: string) => `Fonte: ${name}`,
    simulated: 'simulada — dados de teste, não são de mercado real',
    sourceFailed: 'A fonte falhou na última coleta; exibindo os últimos valores com seus horários.',
    columns: {
      token: 'Token',
      price: 'Price',
      liquidity: 'Liquidity',
      volume24h: 'Volume 24h',
      holders: 'Holders',
      age: 'Idade',
      coverage: 'Cobertura',
    },
    columnHelp: {
      price: 'Preço do token na fonte.',
      liquidity: 'Liquidez disponível no pool.',
      volume24h: 'Volume negociado nas últimas 24 horas.',
      holders: 'Quantidade de carteiras com o token.',
      age: 'Tempo desde a criação do token.',
      coverage: 'Quantos dos 5 campos a fonte informou.',
    },
    unknown: 'unknown',
    unknownReason: {
      NOT_PROVIDED: 'A fonte não informou este campo.',
      SOURCE_UNAVAILABLE: 'A fonte estava indisponível.',
      NOT_YET_OBSERVED: 'Ainda não observado.',
    } as Record<string, string>,
    stale: 'desatualizado',
    fresh: 'atualizado',
    minutes: (value: string) => `${value} min`,
    coverage: (informed: number, total: number) => `${informed} de ${total}`,
    detail: (source: string, time: string, freshness: string) =>
      `Fonte: ${source} · Horário: ${time} · Frescor: ${freshness}`,
  },
} as const;
