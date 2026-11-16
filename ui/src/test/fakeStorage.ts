/** A Map-backed Storage for unit tests; `failOn` makes the named operations throw. */
export function fakeStorage(
  initial: Record<string, string> = {},
  failOn: ReadonlyArray<'getItem' | 'setItem'> = [],
): Storage {
  const data = new Map(Object.entries(initial));
  const fail = (op: string) => {
    throw new DOMException(`${op} is not allowed`, 'SecurityError');
  };
  return {
    get length() {
      return data.size;
    },
    clear: () => data.clear(),
    key: (index: number) => Array.from(data.keys())[index] ?? null,
    getItem: (key: string) => (failOn.includes('getItem') ? fail('getItem') : (data.get(key) ?? null)),
    setItem: (key: string, value: string) => {
      if (failOn.includes('setItem')) fail('setItem');
      data.set(key, String(value));
    },
    removeItem: (key: string) => {
      data.delete(key);
    },
  };
}
