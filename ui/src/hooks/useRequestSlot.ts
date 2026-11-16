import { useCallback, useEffect, useRef, useState } from 'react';
import { isAbortError } from '../api/client';

export type SlotState<T> =
  | { status: 'idle' }
  | { status: 'loading' }
  | { status: 'success'; data: T }
  | { status: 'error'; error: unknown };

export interface RequestSlot<T> {
  state: SlotState<T>;
  /** Aborts the previous request and starts a new one; only the latest result is committed. */
  run: (fn: (signal: AbortSignal) => Promise<T>) => Promise<void>;
  /** Aborts the current request and returns to idle. */
  reset: () => void;
}

/**
 * One read request with a latest-wins guard (ADR 0017 Consequences): every run aborts the
 * previous request and bumps a sequence number; a result whose number is not the latest, or
 * that arrives after unmount, is dropped. Abort rejections are ignored.
 */
export function useRequestSlot<T>(): RequestSlot<T> {
  const [state, setState] = useState<SlotState<T>>({ status: 'idle' });
  const seqRef = useRef(0);
  const controllerRef = useRef<AbortController | null>(null);
  const mountedRef = useRef(true);

  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
      controllerRef.current?.abort();
    };
  }, []);

  const run = useCallback(async (fn: (signal: AbortSignal) => Promise<T>) => {
    controllerRef.current?.abort();
    const controller = new AbortController();
    controllerRef.current = controller;
    seqRef.current += 1;
    const seq = seqRef.current;
    setState({ status: 'loading' });
    const isLatest = () => seq === seqRef.current && mountedRef.current;
    try {
      const data = await fn(controller.signal);
      if (isLatest()) setState({ status: 'success', data });
    } catch (error) {
      if (controller.signal.aborted && isAbortError(error)) return;
      if (isLatest()) setState({ status: 'error', error });
    }
  }, []);

  const reset = useCallback(() => {
    controllerRef.current?.abort();
    seqRef.current += 1;
    setState({ status: 'idle' });
  }, []);

  return { state, run, reset };
}
