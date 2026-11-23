const TIME_RE = /^([01]?\d|2[0-3])[:.]([0-5]\d)$/;

/** "8:00, 20.30" -> ["08:00", "20:30"]; null se un orario non è valido. */
export function parseTimes(input: string): string[] | null {
  const parts = input.split(/[,;\s]+/).filter(Boolean);
  if (parts.length === 0) return null;
  const out = new Set<string>();
  for (const p of parts) {
    const m = TIME_RE.exec(p);
    if (!m) return null;
    out.add(`${m[1].padStart(2, '0')}:${m[2]}`);
  }
  return [...out].sort();
}

export const hhmm = (time: string) => time.slice(0, 5);

export function formatDateTime(iso: string, timeZone?: string): string {
  return new Date(iso).toLocaleString('it-IT', {
    timeZone,
    weekday: 'short',
    day: '2-digit',
    month: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  });
}

export function ago(iso: string | null, now = Date.now()): string {
  if (!iso) return 'mai';
  const min = Math.round((now - new Date(iso).getTime()) / 60000);
  if (min < 2) return 'adesso';
  if (min < 60) return `${min} minuti fa`;
  const h = Math.round(min / 60);
  if (h < 48) return `${h} ${h === 1 ? 'ora' : 'ore'} fa`;
  return `${Math.round(h / 24)} giorni fa`;
}
