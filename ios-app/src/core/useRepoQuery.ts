import { useEffect, useState } from 'react';
import type { ChatRepository } from '../data/chatRepository';

/** Runs `query` now and again after every change to the repository; the newest result wins. */
export function useRepoQuery<T>(repo: ChatRepository, query: () => Promise<T>, deps: unknown[], initial: T): T {
  const [value, setValue] = useState<T>(initial);
  useEffect(() => {
    let alive = true;
    let seq = 0;
    const load = () => {
      const mine = ++seq;
      query().then(
        (v) => {
          if (alive && mine === seq) setValue(v);
        },
        () => undefined,
      );
    };
    load();
    const unsubscribe = repo.subscribe(load);
    return () => {
      alive = false;
      unsubscribe();
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [repo, ...deps]);
  return value;
}
