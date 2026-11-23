-- Pianifica la funzione notify-tutors ogni minuto con pg_cron + pg_net.
-- Eseguire UNA volta nello SQL editor di Supabase, dopo aver:
--   1) abilitato le estensioni pg_cron e pg_net (Database → Extensions);
--   2) pubblicato la funzione:  supabase functions deploy notify-tutors --no-verify-jwt
--   3) impostato il segreto:   supabase secrets set CRON_SECRET=<stringa-casuale-lunga>
-- Sostituire <PROJECT_REF> e <CRON_SECRET> (il segreto resta nel database, non nel repository).

select cron.schedule(
  'notify-tutors',
  '* * * * *',
  $$
  select net.http_post(
    url := 'https://<PROJECT_REF>.supabase.co/functions/v1/notify-tutors',
    headers := jsonb_build_object('content-type', 'application/json', 'x-cron-secret', '<CRON_SECRET>'),
    body := '{}'::jsonb
  );
  $$
);
