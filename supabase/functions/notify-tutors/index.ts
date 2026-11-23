// Supabase Edge Function: da eseguire ogni minuto (vedi supabase/cron.sql).
// 1) segna dosi e controlli "STO BENE" scaduti e crea gli avvisi (process_overdue);
// 2) invia gli avvisi non ancora inviati ai telefoni dei tutori tramite Expo Push.
import { createClient } from 'jsr:@supabase/supabase-js@2';

const EXPO_PUSH_URL = 'https://exp.host/--/api/v2/push/send';

type AlertRow = { id: string; elder_id: string; title: string; body: string; kind: string };

Deno.serve(async (req) => {
  const secret = Deno.env.get('CRON_SECRET');
  if (!secret || req.headers.get('x-cron-secret') !== secret) {
    return new Response('forbidden', { status: 403 });
  }

  const db = createClient(Deno.env.get('SUPABASE_URL')!, Deno.env.get('SUPABASE_SERVICE_ROLE_KEY')!, {
    auth: { persistSession: false },
  });

  const overdue = await db.rpc('process_overdue');
  if (overdue.error) return Response.json({ error: overdue.error.message }, { status: 500 });

  const { data: alerts, error } = await db
    .from('alerts')
    .select('id, elder_id, title, body, kind')
    .is('pushed_at', null)
    .gt('created_at', new Date(Date.now() - 6 * 3600e3).toISOString())
    .order('created_at')
    .limit(200);
  if (error) return Response.json({ error: error.message }, { status: 500 });

  let sent = 0;
  for (const alert of (alerts ?? []) as AlertRow[]) {
    const { data: links } = await db.from('care_links').select('tutor_id').eq('elder_id', alert.elder_id);
    const tutorIds = (links ?? []).map((l) => l.tutor_id);
    const { data: tokens } = tutorIds.length
      ? await db.from('push_tokens').select('token').in('user_id', tutorIds)
      : { data: [] };

    const messages = (tokens ?? []).map((t) => ({
      to: t.token,
      title: alert.title,
      body: alert.body,
      sound: 'default',
      priority: 'high',
      channelId: 'avvisi',
      data: { alertId: alert.id, elderId: alert.elder_id, kind: alert.kind },
    }));

    if (messages.length) {
      const res = await fetch(EXPO_PUSH_URL, {
        method: 'POST',
        headers: { 'content-type': 'application/json', accept: 'application/json' },
        body: JSON.stringify(messages),
      });
      if (!res.ok) {
        // Lascia pushed_at vuoto: verrà ritentato al prossimo giro.
        console.error('expo push failed', res.status, await res.text());
        continue;
      }
      const result = await res.json();
      // Rimuove i token non più validi (app disinstallata).
      (result.data ?? []).forEach((r: { status: string; details?: { error?: string } }, i: number) => {
        if (r.status === 'error' && r.details?.error === 'DeviceNotRegistered') {
          db.from('push_tokens').delete().eq('token', messages[i].to).then(() => {});
        }
      });
      sent += messages.length;
    }
    await db.from('alerts').update({ pushed_at: new Date().toISOString() }).eq('id', alert.id);
  }

  return Response.json({ created: overdue.data, alerts: alerts?.length ?? 0, sent });
});
