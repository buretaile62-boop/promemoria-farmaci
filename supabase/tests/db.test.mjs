// Test dello schema Supabase su PGlite (Postgres in WebAssembly), senza servizi esterni.
// Lo schema "auth" e i ruoli di Supabase sono simulati in modo minimo.
import { test, before } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, readdirSync } from 'node:fs';
import { PGlite } from '@electric-sql/pglite';

const migrationsDir = new URL('../migrations/', import.meta.url);

const AUTH_STUB = `
  create role anon nologin;
  create role authenticated nologin;
  create role service_role nologin bypassrls;
  create schema auth;
  create table auth.users (id uuid primary key);
  create function auth.uid() returns uuid language sql stable as
    $$ select nullif(current_setting('request.jwt.claim.sub', true), '')::uuid $$;
  grant usage on schema auth to anon, authenticated, service_role;
  grant execute on function auth.uid() to anon, authenticated, service_role;
`;

// Privilegi predefiniti come su Supabase: tutto concesso, poi RLS e revoke della migrazione.
const DEFAULT_PRIVILEGES = `
  grant usage on schema public to anon, authenticated, service_role;
  alter default privileges in schema public grant all on tables to anon, authenticated, service_role;
  alter default privileges in schema public grant all on functions to anon, authenticated, service_role;
`;

let db;

const ids = {
  elder: '00000000-0000-0000-0000-00000000000e',
  tutor: '00000000-0000-0000-0000-00000000000a',
  stranger: '00000000-0000-0000-0000-00000000000f',
};

async function as(user, fn) {
  await db.exec(`reset role; select set_config('request.jwt.claim.sub', '${user ?? ''}', false);`);
  await db.exec(user ? 'set role authenticated' : 'set role service_role');
  try {
    return await fn();
  } finally {
    await db.exec('reset role');
  }
}

const q = (sql, params) => db.query(sql, params);

before(async () => {
  db = new PGlite();
  await db.exec(AUTH_STUB);
  await db.exec(DEFAULT_PRIVILEGES);
  for (const file of readdirSync(migrationsDir).sort()) {
    await db.exec(readFileSync(new URL(file, migrationsDir), 'utf8'));
  }
  await db.exec(`insert into auth.users values ('${ids.elder}'), ('${ids.tutor}'), ('${ids.stranger}')`);
});

test('registrazione dei profili e ruolo immutabile', async () => {
  await as(ids.elder, () => q(`select register_profile('anziano', 'Nonna Lucia', 'Europe/Rome')`));
  await as(ids.tutor, () => q(`select register_profile('tutore', 'Marco', 'Europe/Rome', '+39 333 1234567')`));
  await as(ids.stranger, () => q(`select register_profile('tutore', 'Estraneo')`));
  await assert.rejects(
    as(ids.elder, () => q(`select register_profile('tutore', 'Nonna Lucia')`)),
    /role_immutable/,
  );
  await assert.rejects(
    as(ids.elder, () => q(`update profiles set role = 'tutore' where id = $1`, [ids.elder])),
    /role_immutable/,
  );
  // Retrodatiamo la registrazione per poter simulare orari passati.
  await q(`update profiles set created_at = '2026-11-01T00:00:00Z'`);
});

test('abbinamento con codice a 6 cifre', async () => {
  await assert.rejects(as(ids.tutor, () => q(`select create_pairing_code()`)), /only_elder/);
  const { rows } = await as(ids.elder, () => q(`select create_pairing_code() as code`));
  const code = rows[0].code;
  assert.match(code, /^\d{6}$/);
  await assert.rejects(as(ids.elder, () => q(`select claim_pairing_code($1)`, [code])), /only_tutor/);
  await assert.rejects(as(ids.tutor, () => q(`select claim_pairing_code('999999x')`)), /invalid_code/);
  const claimed = await as(ids.tutor, () => q(`select claim_pairing_code($1) as elder`, [code]));
  assert.equal(claimed.rows[0].elder, ids.elder);
  // Il codice è monouso.
  await assert.rejects(as(ids.stranger, () => q(`select claim_pairing_code($1)`, [code])), /invalid_code/);
  // Nessun accesso diretto ai codici.
  await assert.rejects(as(ids.tutor, () => q(`select * from pairing_codes`)), /permission denied/);
});

test('solo il tutore collegato gestisce la terapia', async () => {
  await assert.rejects(
    as(ids.elder, () => q(`insert into medications (elder_id, name) values ($1, 'X')`, [ids.elder])),
    /row-level security/,
  );
  await assert.rejects(
    as(ids.stranger, () => q(`insert into medications (elder_id, name) values ($1, 'X')`, [ids.elder])),
    /row-level security/,
  );
  await as(ids.tutor, async () => {
    const med = await q(
      `insert into medications (elder_id, name, dosage) values ($1, 'Cardioaspirina', '100 mg') returning id`,
      [ids.elder],
    );
    await q(`insert into medication_times (medication_id, time_of_day) values ($1, '08:00'), ($1, '20:00')`, [
      med.rows[0].id,
    ]);
  });
  await q(`update medication_times set created_at = '2026-11-01T00:00:00Z'`);

  const strangerView = await as(ids.stranger, () => q(`select * from medications`));
  assert.equal(strangerView.rows.length, 0);

  const schedule = await as(ids.elder, () => q(`select get_my_schedule() as s`));
  const s = schedule.rows[0].s;
  assert.equal(s.items.length, 2);
  assert.deepEqual(s.items.map((i) => i.time), ['08:00', '20:00']);
  assert.deepEqual(s.wellness_hours, [8, 12, 16, 20]);
  assert.equal(s.tutors[0].phone, '+39 333 1234567');
});

test('dosi saltate e controlli STO BENE generano avvisi una sola volta', async () => {
  // 23/11/2026 alle 08:40 ora italiana (UTC+1): la dose delle 08:00 è oltre la tolleranza di 30 minuti,
  // il controllo delle 08:00 è oltre i 15 minuti.
  const now = '2026-11-23T07:40:00Z';
  const created = await as(null, () => q(`select process_overdue($1) as n`, [now]));
  const alerts = await q(`select kind, title, body from alerts order by created_at, kind`);
  const kinds = alerts.rows.map((r) => r.kind).sort();
  // Finestra di 24 ore (dalle 08:40 di ieri): dosi di ieri 20:00 e oggi 08:00 => 2;
  // controlli di ieri 12/16/20 e oggi 08:00 => 4.
  assert.equal(created.rows[0].n, 6);
  assert.equal(kinds.filter((k) => k === 'dose_missed').length, 2);
  assert.equal(kinds.filter((k) => k === 'wellness_missed').length, 4);
  assert.ok(alerts.rows.some((r) => r.body.includes('Cardioaspirina 100 mg delle 08:00')));

  const again = await as(null, () => q(`select process_overdue($1) as n`, [now]));
  assert.equal(again.rows[0].n, 0);
});

test('conferma in orario: nessun avviso; conferma in ritardo: avviso di recupero', async () => {
  const { rows } = await q(`select id from medication_times where time_of_day = '20:00'`);
  const mt = rows[0].id;
  // Fissiamo un "adesso" coerente con la finestra ammessa da confirm_dose.
  const slot = new Date(Date.now() - 5 * 60 * 1000);
  slot.setUTCSeconds(0, 0);
  await as(ids.elder, () => q(`select confirm_dose($1, $2)`, [mt, slot.toISOString()]));
  const n = await as(null, () => q(`select process_overdue($1) as n`, [new Date(slot.getTime() + 3600e3).toISOString()]));
  const ev = await q(`select status from dose_events where medication_time_id = $1 and scheduled_for = $2`, [
    mt,
    slot.toISOString(),
  ]);
  assert.equal(ev.rows[0].status, 'taken');
  assert.ok(n.rows[0].n >= 0);

  // Dose di ieri sera già segnata "saltata" -> conferma tardiva.
  const missed = await q(`select scheduled_for from dose_events where status = 'missed' and medication_time_id = $1`, [mt]);
  assert.ok(missed.rows.length > 0);
  const before = await q(`select count(*)::int as c from alerts where kind = 'dose_taken_late'`);
  await q(`update dose_events set scheduled_for = now() - interval '2 hours' where status = 'missed' and medication_time_id = $1`, [mt]);
  const late = await q(`select scheduled_for from dose_events where status = 'missed' and medication_time_id = $1`, [mt]);
  await as(ids.elder, () => q(`select confirm_dose($1, $2)`, [mt, late.rows[0].scheduled_for]));
  const after = await q(`select count(*)::int as c from alerts where kind = 'dose_taken_late'`);
  assert.equal(after.rows[0].c, before.rows[0].c + 1);
});

test('isolamento: estranei e anziano non vedono gli avvisi altrui; nessuna scrittura diretta', async () => {
  const stranger = await as(ids.stranger, () => q(`select * from alerts`));
  assert.equal(stranger.rows.length, 0);
  const tutor = await as(ids.tutor, () => q(`select * from alerts`));
  assert.ok(tutor.rows.length > 0);
  await assert.rejects(
    as(ids.elder, () => q(`insert into dose_events (medication_time_id, elder_id, scheduled_for, status)
      select id, $1, now(), 'taken' from medication_times limit 1`, [ids.elder])),
    /permission denied/,
  );
  await assert.rejects(
    as(ids.tutor, () => q(`insert into alerts (elder_id, kind, title, body) values ($1, 'dose_missed', 'x', 'y')`, [ids.elder])),
    /permission denied/,
  );
  await assert.rejects(as(ids.tutor, () => q(`select process_overdue()`)), /permission denied/);
  const mt = (await q(`select id from medication_times limit 1`)).rows[0].id;
  await assert.rejects(as(ids.stranger, () => q(`select confirm_dose($1, now())`, [mt])), /not_found/);
});

test('STO BENE e sblocco di emergenza', async () => {
  const slot = new Date();
  slot.setUTCMinutes(0, 0, 0);
  await as(ids.elder, () => q(`select answer_wellness($1)`, [slot.toISOString()]));
  const row = await q(`select status from wellness_checks where elder_id = $1 and slot_at = $2`, [
    ids.elder,
    slot.toISOString(),
  ]);
  assert.equal(row.rows[0].status, 'ok');
  await as(ids.elder, () => q(`select report_emergency_unlock()`));
  const alert = await as(ids.tutor, () => q(`select title from alerts where kind = 'emergency_unlock'`));
  assert.match(alert.rows[0].title, /Nonna Lucia ha usato lo sblocco di emergenza/);
  await assert.rejects(as(ids.tutor, () => q(`select report_emergency_unlock()`)), /only_elder/);
});

test('ora legale: la dose delle 08:00 resta alle 08:00 locali', async () => {
  // 29/03/2026 cambio ora in Italia: alle 08:00 locali sono le 06:00 UTC.
  const r = await q(`select (('2026-03-29'::date + time '08:00') at time zone 'Europe/Rome') as t`);
  assert.equal(new Date(r.rows[0].t).toISOString(), '2026-03-29T06:00:00.000Z');
});
