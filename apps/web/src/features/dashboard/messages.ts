/**
 * pt-BR texts of the dashboard S2 and of the shell of the logged screens (NFR14.1), part of the single catalog of the
 * guest app ({@code messages.dashboard} and {@code messages.nav}). Every state, reason, sign and order status is
 * written, never shown by color alone (BR2.4); values are always called simulated and nothing promises profit
 * (BR8.2, project.md).
 */
export const dashboardMessages = {
  title: 'Painel',
  paperLabel: 'MODO PAPER (simulado)',
  updatedAt: (time: string) => `Atualizado às ${time}`,
  notYetUpdated: 'Aguardando a primeira atualização',
  loading: 'Carregando…',
  retry: 'Tentar de novo',
  bots: {
    title: 'Bots PAPER',
    loading: 'Carregando os bots…',
    error: 'Não foi possível carregar os bots agora. Os outros quadros continuam funcionando.',
    counts: (active: number, limit: number) => `Ativos: ${active} de ${limit}`,
    paused: (count: number) => `Pausados: ${count}`,
    empty: 'Nenhum bot PAPER em uso',
    openScanner: 'Abrir scanner',
    createBot: 'Criar bot PAPER',
    drafts: (count: number) =>
      count === 1 ? 'Você tem 1 rascunho' : `Você tem ${count} rascunhos`,
    continueDraft: 'Continuar rascunho',
    stopped: (count: number) => (count === 1 ? '1 encerrado' : `${count} encerrados`),
    draftsCount: (count: number) => (count === 1 ? '1 rascunho' : `${count} rascunhos`),
    allBots: 'Ver todos os bots',
    state: 'Estado',
    pause: 'Pausar',
    pausing: 'Pausando…',
    pauseLabel: (name: string) => `Pausar o bot ${name}`,
    pauseFailed: 'Não foi possível pausar agora.',
    pauseConflict: 'O bot já não estava ativo.',
    paused_: (name: string) => `Bot ${name} pausado por você`,
    stateChanged: (name: string, state: string, reasons: string) =>
      reasons ? `Bot ${name}: ${state} (${reasons})` : `Bot ${name}: ${state}`,
  },
  lastTrade: {
    label: 'Última operação',
    loading: 'Carregando a última operação…',
    error: 'Não foi possível carregar a última operação agora.',
    none: 'Nenhuma operação ainda',
    line: (time: string, bot: string, side: string, token: string, status: string) =>
      `${time} — ${bot} — ${side} ${token} — ${status}`,
    unknownBot: 'bot',
    sides: { BUY: 'Compra', SELL: 'Venda' } as Record<string, string>,
    statuses: {
      FILLED: 'preenchida',
      PARTIALLY_FILLED: 'parcial',
      NOT_FILLED: 'não preenchida',
      CLOSE_PENDING: 'fechamento pendente',
    } as Record<string, string>,
    notFilledWithReason: (reason: string) => `não preenchida: ${reason}`,
    announced: (bot: string, side: string) => `Nova operação simulada do bot ${bot}: ${side}`,
  },
  // 261121-paper-real-unico U3 (BR7.1): the cards by mode replace the card "P&L PAPER (simulado)" of the UTC day.
  modes: {
    title: 'Resultados por modo · desde o início',
    intro:
      'O resultado simulado e o resultado real ficam em cartões separados e nunca são somados.',
  },
  reconciliation: {
    loading: 'Carregando a reconciliação…',
    reconciled: (time: string) => `Reconciliado às ${time}`,
    reconciledNoTime: 'Reconciliado',
    divergent: (count: number) => (count === 1 ? '1 divergência' : `${count} divergências`),
    pending: 'Ainda não conferido',
    error: 'Reconciliação indisponível agora',
  },
  notices: {
    region: 'Avisos de dados',
    sourcesOk: (time: string) => `Dados: OK ${time}`,
    sourcesLoading: 'Verificando as fontes de dados…',
    sourcesError: 'Não foi possível verificar as fontes de dados agora.',
    sourceStale: (name: string, time: string) => `Fonte ${name} sem atualização desde ${time}`,
    sourceFailed: (name: string, time: string) =>
      `Fonte ${name} com falha; última atualização às ${time}`,
    sourceNever: (name: string) => `Fonte ${name} ainda não coletou`,
    sourceBack: (name: string) => `Fonte ${name} voltou a atualizar`,
    outdated: (time: string) =>
      `Os valores podem estar desatualizados; última atualização às ${time}`,
    outdatedNoTime: 'Os valores podem estar desatualizados.',
    lost: 'Atualização automática interrompida; tentando de novo',
    resumed: 'Atualização automática retomada',
  },
} as const;

/** The shell of the logged screens S2 to S8 (BR6.1 to BR6.4): main navigation in the order of the mockup. */
export const navMessages = {
  label: 'Navegação principal',
  appName: 'MemeTrade Analytics',
  paperMode: 'MODO PAPER (simulado)',
  items: {
    dashboard: 'Painel',
    scanner: 'Scanner',
    bots: 'Bots PAPER',
    pnl: 'P&L PAPER',
    account: 'Conta',
  },
  wallet: 'Carteira',
  walletLabel: (masked: string) => `Carteira ${masked}`,
  notices: 'Avisos',
} as const;
