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
    // BR5.5 (U1): the fees of the REAL are already inside the realized and the cost.
    fees: 'Taxas (já incluídas no realizado e no custo)',
    unownedNote: 'Algumas operações ainda não têm bot dono; o rateio chega na próxima etapa.',
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
};
