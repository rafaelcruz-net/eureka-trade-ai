/**
 * Reader of the `text/event-stream` format of the live channel C7 (BR5.1, NFR6.5, performance-design.md "Leitor do
 * text/event-stream"). Own code, no dependency (Q4 of the NFR requirements): it receives the body in chunks of bytes,
 * decodes UTF-8 in streaming mode (a character split between two chunks is kept until it is complete), splits blocks
 * on a blank line (`\n\n` or `\r\n\r\n`, also a lone `\r`), joins several `data:` lines with `\n`, ignores comment lines
 * starting with `:` and fields it does not know. It never interprets the text: the data is handed over as a string and
 * only `JSON.parse` + the envelope check of the client touch it.
 *
 * A buffer above {@link MAX_EVENT_BYTES} without a separator stops the reading with `OVERSIZED_EVENT`: a malformed or
 * hostile stream cannot exhaust the memory of the tab.
 */

/** 1 MiB: far above the largest expected event (`bot.state` with 100 bots, a few KiB). */
export const MAX_EVENT_BYTES = 1024 * 1024;

/** One event of the stream. `id` is undefined when the block has no `id` field; it may be the empty string. */
export interface SseEvent {
  id?: string;
  event: string;
  data: string;
}

/** Thrown by {@link SseParser.push} when one event grows past {@link MAX_EVENT_BYTES}. */
export class OversizedEventError extends Error {
  readonly reason = 'OVERSIZED_EVENT' as const;

  constructor() {
    super('OVERSIZED_EVENT');
    this.name = 'OversizedEventError';
  }
}

const BLANK_LINE = /\r\n\r\n|\n\n|\r\r/;

/** Incremental parser: push chunks as they arrive; each call returns the complete events found so far. */
export class SseParser {
  private readonly decoder = new TextDecoder('utf-8');
  private buffer = '';
  private readonly maxBytes: number;

  constructor(maxBytes: number = MAX_EVENT_BYTES) {
    this.maxBytes = maxBytes;
  }

  push(chunk: Uint8Array): SseEvent[] {
    this.buffer += this.decoder.decode(chunk, { stream: true });
    const events: SseEvent[] = [];
    let match = BLANK_LINE.exec(this.buffer);
    while (match) {
      const block = this.buffer.slice(0, match.index);
      this.buffer = this.buffer.slice(match.index + match[0].length);
      const event = parseBlock(block);
      if (event) {
        events.push(event);
      }
      match = BLANK_LINE.exec(this.buffer);
    }
    if (this.exceedsLimit(this.buffer)) {
      this.buffer = '';
      throw new OversizedEventError();
    }
    return events;
  }

  /** UTF-8 size of the pending text against the limit, encoding only when the length alone cannot tell. */
  private exceedsLimit(text: string): boolean {
    if (text.length > this.maxBytes) {
      return true;
    }
    if (text.length * 3 <= this.maxBytes) {
      return false;
    }
    return new TextEncoder().encode(text).length > this.maxBytes;
  }

  /**
   * End of the stream. A block cut in the middle (no blank line after it) is NOT delivered, as the SSE standard
   * says: the connection dropped before the event was complete.
   */
  end(): void {
    this.decoder.decode();
    this.buffer = '';
  }
}

/** One block (lines up to a blank line) to an event; a block with only comments or no data is not an event. */
function parseBlock(block: string): SseEvent | null {
  let id: string | undefined;
  let event = 'message';
  const data: string[] = [];
  for (const line of block.split(/\r\n|\n|\r/)) {
    if (line === '' || line.startsWith(':')) {
      continue;
    }
    const colon = line.indexOf(':');
    const field = colon === -1 ? line : line.slice(0, colon);
    let value = colon === -1 ? '' : line.slice(colon + 1);
    if (value.startsWith(' ')) {
      value = value.slice(1);
    }
    if (field === 'data') {
      data.push(value);
    } else if (field === 'event') {
      event = value;
    } else if (field === 'id' && !value.includes('\u0000')) {
      id = value;
    }
  }
  if (data.length === 0) {
    return null;
  }
  return id === undefined ? { event, data: data.join('\n') } : { id, event, data: data.join('\n') };
}
