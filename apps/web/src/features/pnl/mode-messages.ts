/**
 * 261121-paper-real-unico U1 (mockups M4, M5; interaction-spec "PnlModeBlock"): the texts of the P&L by mode in
 * Brazilian Portuguese. No text promises a profit (BR6.2, C-16).
 */
export const modeMessages = {
  title: 'Resultado',
  intro:
    'O resultado simulado e o resultado real aparecem em blocos separados e nunca são somados.',
  sinceStart: 'desde o início',
  PAPER: {
    heading: 'Simulado, em dólares',
    total: 'Total simulado, em dólares',
    loading: 'Calculando o resultado simulado…',
    error: 'Não foi possível carregar o resultado simulado.',
    empty: 'Nenhuma operação simulada neste período.',
    emptyLink: '→ Criar bot simulado',
    fees: 'Taxas',
  },
  REAL: {
    heading: 'Real, em SOL',
    total: 'Total real, em SOL',
    loading: 'Calculando o resultado real…',
    error: 'Não foi possível carregar o resultado real.',
    empty: 'Sem bots reais.',
    emptyLink: '→ Ativar a carteira do bot',
    // 261121-paper-real-unico U2 (BR10.4): the realized of the REAL is gross and the total takes the fees out, as in
    // the PAPER; the fixed note of the U1 about the proration is gone with the proration.
    fees: 'Taxas',
  },
  realized: 'Realizado',
  unrealized: 'Não realizado',
  unrealizedNow: (time: string) => `Não realizado: posições abertas agora (marcação às ${time})`,
  totalUnknown: 'desconhecido (depende do não realizado)',
  partial: '◐ Dado parcial: há posição sem preço confiável.',
  unknown: (reason: string) => `desconhecido (${reason})`,
  unknownReasons: {
    PRICE_UNKNOWN: 'sem preço',
    STALE: 'preço desatualizado',
    NO_LIQUIDITY: 'sem liquidez',
  } as Record<string, string>,
  bots: 'Bots do modo',
  botStatus: { ACTIVE: 'Ativo', PAUSED: 'Pausado', REMOVED: 'Removido' } as Record<string, string>,
  columns: { bot: 'Bot', status: 'Estado', total: 'Total' },
  gain: 'ganho',
  loss: 'perda',
  zero: 'zero',
  supportCode: (code: string) => `Código para o suporte: ${code}`,
  retry: 'Tentar de novo',
  fixedNotice: 'Aviso do simulado',
  paperSection: 'Simulado por período',
  // 261121-paper-real-unico U3 (BR4.1): the origin of a line of the P&L REAL, in text; an unknown code is shown as is.
  origins: {
    BOT: 'Bot',
    LIQUIDATION: 'liquidação',
    CONDITIONAL: 'venda condicional',
    SOLD_BY_OTHER_BOT: 'vendido por outro bot',
  } as Record<string, string>,
  // 261121-paper-real-unico U3 (BR4.8): the line "Sem bot" of the REAL block and its reasons.
  noBot: 'Sem bot',
  noBotIntro: 'Resultado real que não pertence a nenhum bot.',
  noBotReason: {
    UNOWNED_EXCESS: 'Motivo: excedente sem dono',
    UNOWNED_NO_COST: 'Motivo: venda sem custo registrado',
    ORDER_RECORD_MISSING: 'Motivo: registro da ordem ausente',
  } as Record<string, string>,
  noBotReasonUnknown: (code: string) => `Motivo: ${code}`,
  // 261121-paper-real-unico U3 (BR4.7): the prorated shares of a REAL bot.
  prorated: {
    includes: (value: string, origin: string) => `inclui ${value} de ${origin}`,
    show: (count: number) => (count === 1 ? 'Ver 1 linha rateada' : `Ver ${count} linhas rateadas`),
    hide: (count: number) =>
      count === 1 ? 'Esconder 1 linha rateada' : `Esconder ${count} linhas rateadas`,
    ofBot: (bot: string) => ` de ${bot}`,
    caption: (bot: string) => `Linhas rateadas de ${bot}`,
    seeAll: 'Ver todas no detalhe do bot',
    columns: {
      date: 'Data (seu fuso)',
      token: 'Token',
      origin: 'Origem',
      received: 'Recebido (SOL)',
      fee: 'Taxa (SOL)',
      realized: 'Resultado (SOL)',
    },
  },
  // 261121-paper-real-unico U3 (BR7.1 to BR7.4): the compact variant of the dashboard.
  compact: {
    PAPER: {
      heading: 'Resultado simulado',
      total: 'Total simulado, em dólares',
      error:
        'Não foi possível carregar o resultado simulado. Os outros quadros continuam funcionando.',
      empty: 'Sem bots simulados.',
      emptyLink: '→ Criar bot simulado',
      open: 'Ver o resultado simulado',
    },
    REAL: {
      heading: 'Resultado real',
      total: 'Total real, em SOL',
      error: 'Não foi possível carregar o resultado real. Os outros quadros continuam funcionando.',
      empty: 'Sem bots reais.',
      createLink: '→ Criar bot real',
      activateLink: '→ Ativar a carteira do bot',
      open: 'Ver o resultado real',
    },
  },
  // 261121-paper-real-unico U3 (BR4.6, BR5.4): the variant of one bot (detail and comparison).
  bot: {
    PAPER: { total: 'Total do bot, em dólares', loading: 'Calculando o resultado do bot…' },
    REAL: { total: 'Total do bot, em SOL', loading: 'Calculando o resultado do bot…' },
    noLine: '—',
    noLineHelp: 'Este bot ainda não tem resultado.',
    error: 'Não foi possível carregar o resultado do bot.',
  },
  // 261121-paper-real-unico U3 (BR2.1 to BR2.3, BR2.7, BR2.9): the period of /pnl.
  period: {
    legend: 'Período',
    options: {
      ALL: 'Desde o início',
      TODAY: 'Hoje',
      LAST_7: 'Últimos 7 dias',
      LAST_30: 'Últimos 30 dias',
      RANGE: 'Intervalo',
    },
    from: 'De',
    to: 'Até (inclusive)',
    apply: 'Aplicar',
    help: (zone: string) => `Datas no seu fuso (${zone}).`,
    errors: {
      format: 'Informe a data no formato dd/mm/aaaa.',
      reversed: 'A data final vem antes da inicial. Corrija o campo "Até".',
      future: 'O período começa depois de hoje. Escolha um dia inicial até hoje.',
      tooLong: 'Escolha um período de até 366 dias.',
    },
    showingAll: (zone: string) => `Mostrando: desde o início · datas no seu fuso (${zone})`,
    showing: (from: string, to: string) =>
      `Mostrando: ${from} a ${to} · realizado no período; não realizado de agora`,
    byToken: 'Ver por token',
    byTokenNote: 'Escolha um período para ver o resultado por token',
  },
  // 261121-paper-real-unico U3 (BR5.1 to BR5.6): the comparison of a PAPER bot and a REAL bot.
  compare: {
    open: 'Comparar um bot simulado com um bot real',
    close: 'Fechar a comparação',
    heading: 'Comparação',
    paperSelect: 'Bot simulado',
    realSelect: 'Bot real',
    choose: 'Escolha um bot',
    choosePaperFirst: 'Escolha primeiro o bot simulado.',
    noPair: 'Para comparar, você precisa de um bot simulado e de um bot real da mesma estratégia.',
    loadingBots: 'Carregando os seus bots…',
    botsError: 'Não foi possível carregar os seus bots.',
    loading: 'Calculando a comparação…',
    paperSide: (name: string) => `${name}, simulado, em dólares`,
    realSide: (name: string) => `${name}, real, em SOL`,
    paperTotal: 'Total do bot simulado, em dólares',
    realTotal: 'Total do bot real, em SOL',
    createdAt: (day: string) => `Criado em ${day}`,
    currencies: 'Os valores ficam cada um na sua moeda e não são comparados por conta.',
    templateMismatch:
      'Esses dois bots usam estratégias diferentes. Escolha um bot real da mesma estratégia do bot simulado.',
    notFound: 'Bot não encontrado.',
    error: 'Não foi possível carregar este lado da comparação.',
  },
  // 261121-paper-real-unico U3 (BR4.1 to BR4.6, BR10.2, A9): the lines of the P&L of a REAL bot.
  lines: {
    heading: 'Linhas do P&L',
    caption: (bot: string) => `Linhas do P&L de ${bot}`,
    loading: 'Carregando as linhas do P&L…',
    empty: 'Nenhuma operação real até agora.',
    error: 'Não foi possível carregar as linhas do P&L.',
    moreError: 'Não foi possível carregar mais linhas.',
    loadMore: 'Carregar mais',
    loadingMore: 'Carregando mais linhas…',
    stale: 'A lista mudou desde a última página.',
    reload: 'Recarregar do início',
    noResult: 'sem resultado',
    sides: { BUY: 'Compra', SELL: 'Venda' } as Record<string, string>,
    columns: {
      date: 'Data (seu fuso)',
      side: 'Lado',
      token: 'Token',
      result: 'Resultado (SOL)',
      fee: 'Taxa (SOL)',
      origin: 'Origem',
    },
  },
};
