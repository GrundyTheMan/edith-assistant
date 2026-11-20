import { useEffect, useState } from 'react';

/** The current time, refreshed every minute, so relative times ("Mon 2:06 PM") stay right while the app is open. */
export function useNow(): number {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const t = setInterval(() => setNow(Date.now()), 60_000);
    return () => clearInterval(t);
  }, []);
  return now;
}
