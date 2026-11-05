import type { BotCommand, BotState, PauseReason } from '@/lib/api/guest-api';
import { botMessages } from '@/features/bots/messages';

/**
 * What each state of a bot shows and allows (annex "Transições do bot", BR6.1, BR6.4): the state and every pause
 * reason in text, the restart time in the person's time zone, and only the actions the server accepts.
 */
export function stateText(state: BotState): string {
  return botMessages.states[state] ?? state;
}

export function reasonsText(reasons: PauseReason[]): string[] {
  return reasons.map((reason) => botMessages.reasons[reason] ?? reason);
}

/** "volta a operar às HH:MM, <fuso>", from the UTC instant of the next midnight (AC4.7.6). */
export function resumesAtText(resumesAt: string | undefined, timeZone: string): string | null {
  if (!resumesAt) {
    return null;
  }
  const time = new Intl.DateTimeFormat('pt-BR', {
    hour: '2-digit',
    minute: '2-digit',
    timeZone,
  }).format(new Date(resumesAt));
  return botMessages.resumesAt(time, zoneName(timeZone));
}

function zoneName(timeZone: string): string {
  return timeZone === 'America/Sao_Paulo' ? 'horário de Brasília' : timeZone;
}

/** The commands the annex accepts from {@code state}; the screen shows only these. */
export function validCommands(state: BotState): BotCommand[] {
  switch (state) {
    case 'VALIDATED':
      return ['start'];
    case 'ACTIVE':
      return ['pause', 'stop'];
    case 'PAUSED':
      return ['resume', 'stop'];
    default:
      return [];
  }
}

/** Whether the wizard is the screen of a bot in this state (a draft or a validated bot not yet started). */
export function isWizardState(state: BotState): boolean {
  return state === 'DRAFT' || state === 'VALIDATED';
}
