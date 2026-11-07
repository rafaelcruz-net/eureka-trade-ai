import { MAX_EVENT_BYTES, OversizedEventError, SseParser } from '@/features/dashboard/sse-reader';

/** Passo 5 of U9 (BR5.1, NFR6.5): the own reader of `text/event-stream`, fed in chunks as the network delivers them. */
const encoder = new TextEncoder();
const bytes = (text: string) => encoder.encode(text);

describe('SseParser', () => {
  it('reads one simple block with id, event and data', () => {
    const parser = new SseParser();

    expect(parser.push(bytes('id: b1.l2.s3\nevent: heartbeat\ndata: {"at":"x"}\n\n'))).toEqual([
      { id: 'b1.l2.s3', event: 'heartbeat', data: '{"at":"x"}' },
    ]);
  });

  it('joins several data lines with a line break', () => {
    const parser = new SseParser();

    const [event] = parser.push(bytes('event: bot.state\ndata: {"a":\ndata: 1}\n\n'));

    expect(event?.data).toBe('{"a":\n1}');
  });

  it('ignores comment lines and a block with only comments', () => {
    const parser = new SseParser();

    expect(parser.push(bytes(': keep-alive\n\n: other\nevent: heartbeat\ndata: x\n\n'))).toEqual([
      { event: 'heartbeat', data: 'x' },
    ]);
  });

  it('accepts CRLF line endings and the CRLF blank line', () => {
    const parser = new SseParser();

    expect(
      parser.push(
        bytes('event: heartbeat\r\ndata: x\r\n\r\nevent: pnl.updated\r\ndata: y\r\n\r\n'),
      ),
    ).toEqual([
      { event: 'heartbeat', data: 'x' },
      { event: 'pnl.updated', data: 'y' },
    ]);
  });

  it('keeps a chunk cut in the middle of a line until the block is complete', () => {
    const parser = new SseParser();

    expect(parser.push(bytes('event: heart'))).toEqual([]);
    expect(parser.push(bytes('beat\ndata: {"at"'))).toEqual([]);
    expect(parser.push(bytes(':"x"}\n'))).toEqual([]);
    expect(parser.push(bytes('\n'))).toEqual([{ event: 'heartbeat', data: '{"at":"x"}' }]);
  });

  it('keeps a multibyte character cut between two chunks', () => {
    const parser = new SseParser();
    const whole = bytes('event: data.status\ndata: {"name":"Fonte Ação ✓"}\n\n');
    const cut = whole.indexOf(0xc3) + 1;

    expect(parser.push(whole.slice(0, cut))).toEqual([]);
    const [event] = parser.push(whole.slice(cut));

    expect(event?.data).toBe('{"name":"Fonte Ação ✓"}');
  });

  it('keeps an empty id as the empty string and an absent id as undefined', () => {
    const parser = new SseParser();

    const [withEmpty, withoutId] = parser.push(
      bytes('id:\nevent: heartbeat\ndata: a\n\nevent: heartbeat\ndata: b\n\n'),
    );

    expect(withEmpty).toEqual({ id: '', event: 'heartbeat', data: 'a' });
    expect(withoutId).not.toHaveProperty('id');
  });

  it('names an event without the event field "message"', () => {
    const parser = new SseParser();

    expect(parser.push(bytes('data: x\n\n'))).toEqual([{ event: 'message', data: 'x' }]);
  });

  it('reads a line without a colon as a field with an empty value', () => {
    const parser = new SseParser();

    expect(parser.push(bytes('event: heartbeat\ndata\nretry\n\n'))).toEqual([
      { event: 'heartbeat', data: '' },
    ]);
  });

  it('stops with OVERSIZED_EVENT above 1 MiB without a separator', () => {
    const parser = new SseParser();
    const chunk = bytes(`data: ${'a'.repeat(64 * 1024)}`);

    expect(() => {
      for (let sent = 0; sent <= MAX_EVENT_BYTES; sent += chunk.length) {
        parser.push(chunk);
      }
    }).toThrow(OversizedEventError);
    expect(new OversizedEventError().reason).toBe('OVERSIZED_EVENT');
  });

  it('counts the limit in UTF-8 bytes, not in characters', () => {
    const parser = new SseParser(30);

    expect(() => parser.push(bytes('data: ááááááááááááá'))).toThrow(OversizedEventError);
    expect(new SseParser(30).push(bytes('data: ok\n\n'))).toEqual([
      { event: 'message', data: 'ok' },
    ]);
  });

  it('drops a block cut by the end of the stream', () => {
    const parser = new SseParser();

    expect(parser.push(bytes('event: heartbeat\ndata: x\n'))).toEqual([]);
    parser.end();

    expect(parser.push(bytes('\n'))).toEqual([]);
  });

  it('reads several blocks of one chunk in order and keeps markup as plain text', () => {
    const parser = new SseParser();

    const events = parser.push(
      bytes(
        'event: a\ndata: 1\n\nevent: b\ndata: <img src=x onerror=alert(1)>\n\nevent: c\ndata: 3\n\n',
      ),
    );

    expect(events.map((event) => event.event)).toEqual(['a', 'b', 'c']);
    expect(events[1]?.data).toBe('<img src=x onerror=alert(1)>');
  });
});
