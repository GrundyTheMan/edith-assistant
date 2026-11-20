const DAYS = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];
const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

function clock(d: Date): string {
  const h = d.getHours();
  return `${h % 12 === 0 ? 12 : h % 12}:${String(d.getMinutes()).padStart(2, '0')} ${h < 12 ? 'AM' : 'PM'}`;
}
function startOfDay(d: Date): number {
  return new Date(d.getFullYear(), d.getMonth(), d.getDate()).getTime();
}
function daysBetween(now: Date, then: Date): number {
  return Math.round((startOfDay(now) - startOfDay(then)) / 86_400_000);
}

/** Under a bubble: "2:06 PM" today, "Mon 2:06 PM" this week, "Oct 12, 2:06 PM" before that. */
export function messageTime(at: number, now: number = Date.now()): string {
  const d = new Date(at);
  const n = new Date(now);
  const days = daysBetween(n, d);
  if (days <= 0) return clock(d);
  if (days < 7) return `${DAYS[d.getDay()]} ${clock(d)}`;
  return `${MONTHS[d.getMonth()]} ${d.getDate()}, ${clock(d)}`;
}

/** In the conversation list: "2:06 PM", "Mon", "Oct 12". */
export function listTime(at: number, now: number = Date.now()): string {
  const d = new Date(at);
  const days = daysBetween(new Date(now), d);
  if (days <= 0) return clock(d);
  if (days < 7) return DAYS[d.getDay()];
  return `${MONTHS[d.getMonth()]} ${d.getDate()}`;
}
