/**
 * 261121-paper-real-unico U1 (mockups M1 to M3b; BR2.7, BR1.5, BR3.2, BR3.4): the texts of the choice of the mode, the
 * confirmation of a REAL bot and the REAL limits of the account, in Brazilian Portuguese. No text promises a profit
 * (BR6.2, C-16).
 */
export const modeStepMessages = {
  stepIndicator: 'Etapa 1 de 5: Modo e nome',
  legend: 'Modo do bot',
  paper: 'Simulado',
  paperHelp: 'Opera com saldo virtual. Nenhum dinheiro real é usado.',
  real: 'Real',
  realHelp: 'Opera com o SOL da carteira do bot, dentro dos limites REAL da conta.',
  loading: 'Conferindo se o modo real está disponível…',
  error: 'Não foi possível conferir o modo real agora.',
  retry: 'Tentar de novo',
  unavailable: (reason: string) => `Real indisponível: ${reason}`,
  reasons: {
    KILL_SWITCH: 'As operações reais estão suspensas pelo sistema no momento.',
    REAL_DISABLED: 'O modo real está desligado no momento.',
    WALLET_INACTIVE: 'A carteira do bot não está ativa.',
    NOT_RELEASED: 'Sua conta ainda não foi liberada para o modo real.',
    QUARANTINE: 'Sua liberação para o modo real está em quarentena e ainda não vale.',
  } as Record<string, string>,
  quarantineFrom: (when: string) => `Vale a partir de ${when}.`,
  activateWallet: 'Ativar a carteira do bot',
  supportCode: (code: string) => `Código para o suporte: ${code}`,
  createPaper: 'Criar rascunho simulado',
  continueReal: 'Continuar para a confirmação',
  refused: 'O bot real não foi criado.',
  modeLine: (mode: string) => `Modo: ${mode} · não pode ser trocado depois da criação`,
};

export const realConfirmMessages = {
  title: 'Criar um bot real',
  summary: (name: string, template: string) => `${name} · ${template}`,
  capLine: (cap: string) => `Teto da carteira do bot: ${cap}`,
  checkbox: 'Este bot opera com dinheiro real',
  checkboxHelp: 'Marque a confirmação para criar o bot real.',
  cancel: 'Cancelar',
  create: 'Criar bot real',
  creating: 'Criando o bot real…',
  technicalError: 'Não foi possível criar o bot real. Tente de novo.',
  retry: 'Tentar de novo',
  supportCode: (code: string) => `Código para o suporte: ${code}`,
};

export const realLimitsMessages = {
  title: 'Limites REAL da conta',
  intro:
    'Um bot real usa os limites REAL da sua conta, conferidos antes de cada ordem. Eles valem para todos os seus bots reais.',
  cap: 'Teto da carteira do bot',
  orderMax: 'Tamanho máximo por ordem',
  dailyBuys: 'Compras por bot, por dia UTC',
  dailyLoss: 'Perda máxima do dia',
  ofCap: (percent: string) => `(${percent.replace('.', ',')}% do teto)`,
  upTo: (count: number) => `até ${count}`,
  notDefined: 'não definido',
  dependsOnCap: 'não definido (depende do teto)',
  change: 'Alterar limites',
  defineCap: 'Definir o teto',
  loading: 'Espere os limites carregarem.',
  error: 'Não foi possível carregar os limites agora.',
  retry: 'Tentar de novo',
  back: 'Voltar',
  continue: 'Continuar',
  supportCode: (code: string) => `Código para o suporte: ${code}`,
};

export const botListModeMessages = {
  title: 'Meus bots',
  modeColumn: 'Modo',
  pnlColumn: 'P&L',
  draftPnl: '—',
  pnlUnavailable: 'P&L indisponível agora',
  disclaimer: 'O resultado simulado não garante o resultado real.',
  // 261121-paper-real-unico U3 (BR1.1 to BR1.5): the filter by mode of /bots.
  filter: {
    legend: 'Modo',
    ALL: 'Todos',
    PAPER: 'Simulado',
    REAL: 'Real',
  },
  shown: (matching: number, total: number) =>
    total === 1 ? `Mostrando ${matching} de 1 bot.` : `Mostrando ${matching} de ${total} bots.`,
  emptyNone: 'Você ainda não tem bots.',
  emptyNoneLink: '+ Novo bot',
  emptyPaper: 'Você não tem bots simulados.',
  emptyReal: 'Você não tem bots reais.',
  emptyState: 'Nenhum bot neste estado.',
  showAll: 'Ver todos os bots',
  // 261121-paper-real-unico U4 (FR6.2, BR6.1; Q4): the fixed notice of the own quantity, word for word.
  ownQuantityNotice:
    'Cada bot real vende só a quantidade que ele mesmo comprou. Posições herdadas ou de outros bots continuam na carteira e podem ser vendidas em Vender tudo e sacar.',
};

/** 261121-paper-real-unico U3 (BR4.5, BR4.6, BR8.2, BR9.3): the head and the P&L of the bot screen. */
export const botDetailModeMessages = {
  modeLine: (mode: string) => `Modo: ${mode} · não pode ser trocado`,
  modes: { PAPER: 'Simulado', REAL: 'Real' } as Record<string, string>,
  pnlHeading: 'P&L do bot',
  sinceStart: 'Desde o início',
  notFound: 'Bot não encontrado.',
  back: 'Voltar para Meus bots',
};
