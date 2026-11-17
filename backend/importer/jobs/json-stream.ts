// Streams the items of one top-level JSON array without parsing the whole document.
//
// The Cardmarket price guide is ~16 MB of JSON (~80k rows). `JSON.parse` on the full text needs several times
// that in heap, close to the Worker's 128 MB, so the import reads it as a stream and parses one item at a time.

/**
 * Yields each element of the array stored under `key` in a JSON object, e.g. `priceGuides` in
 * `{"version":1,"createdAt":"…","priceGuides":[{…},{…}]}`. Only object and array elements are supported, which is
 * all the price guide has. Everything before the array is kept in {@link prefix}, so small header fields such as
 * `createdAt` can be read from it.
 */
export class JsonArrayStream<T = unknown> {
  /** The document text before the array, available once the array has started. */
  prefix = "";

  constructor(private readonly key: string) {}

  async *items(body: ReadableStream<Uint8Array>): AsyncGenerator<T> {
    const marker = new RegExp(`"${this.key}"\\s*:\\s*\\[`);
    const reader = body.pipeThrough(new TextDecoderStream() as ReadableWritablePair<string, Uint8Array>).getReader();

    let buffer = "";
    let inArray = false;
    let done = false;
    // Scanner state for the current element; it survives chunk boundaries.
    let depth = 0;
    let inString = false;
    let escaped = false;
    let start = -1;
    let pos = 0;

    while (!done) {
      const next = await reader.read();
      if (next.done) break;
      buffer += next.value;

      if (!inArray) {
        const m = marker.exec(buffer);
        if (!m) {
          // The header is small; a document that never starts the array is not what we expect.
          if (buffer.length > 1 << 20) throw new Error(`no "${this.key}" array in the first MiB`);
          continue;
        }
        this.prefix = buffer.slice(0, m.index);
        buffer = buffer.slice(m.index + m[0].length);
        inArray = true;
        pos = 0;
      }

      for (; pos < buffer.length; pos++) {
        const c = buffer.charCodeAt(pos);
        if (inString) {
          if (escaped) escaped = false;
          else if (c === 0x5c /* \ */) escaped = true;
          else if (c === 0x22 /* " */) inString = false;
          continue;
        }
        if (c === 0x22) {
          inString = true;
        } else if (c === 0x7b /* { */ || c === 0x5b /* [ */) {
          if (depth === 0) start = pos;
          depth++;
        } else if (c === 0x7d /* } */ || c === 0x5d /* ] */) {
          if (depth === 0) {
            // The closing bracket of the array itself.
            done = true;
            break;
          }
          depth--;
          if (depth === 0) {
            yield JSON.parse(buffer.slice(start, pos + 1)) as T;
            start = -1;
          }
        }
      }

      // Drop what has been consumed; keep a partial element for the next chunk.
      if (start >= 0) {
        buffer = buffer.slice(start);
        pos -= start;
        start = 0;
      } else {
        buffer = "";
        pos = 0;
      }
    }
    await reader.cancel().catch(() => {});
    if (!inArray) throw new Error(`no "${this.key}" array`);
    if (!done) throw new Error(`"${this.key}" array is truncated`);
  }
}
