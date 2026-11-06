/** Instants travel in UTC and are shown in the time zone of the browser (project.md). */
export function browserTimeZone(): string {
  return Intl.DateTimeFormat().resolvedOptions().timeZone;
}

/** dd/mm/aaaa hh:mm in the given time zone. */
export function dateTime(instant: string, timeZone: string): string {
  return new Intl.DateTimeFormat('pt-BR', {
    timeZone,
    day: '2-digit',
    month: '2-digit',
    year: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  }).format(new Date(instant));
}

/** hh:mm in the given time zone. */
export function time(instant: string, timeZone: string): string {
  return new Intl.DateTimeFormat('pt-BR', {
    timeZone,
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  }).format(new Date(instant));
}

const INTEGER = new Intl.NumberFormat('pt-BR');

/** Counts are integers (no money in the administrator app). */
export function count(value: number): string {
  return INTEGER.format(value);
}

/** Characters as the server counts them: code points. */
export function codePoints(text: string): number {
  return Array.from(text).length;
}
