-- Promemoria Farmaci — schema iniziale
-- Due ruoli: "anziano" (riceve i promemoria) e "tutore" (riceve gli avvisi).
-- Tutte le scritture sensibili passano da funzioni RPC con controlli espliciti;
-- le tabelle sono protette da Row Level Security.


-- ---------------------------------------------------------------------------
-- Parametri del servizio
-- ---------------------------------------------------------------------------

-- Minuti di tolleranza prima che una dose non confermata diventi "saltata".
create or replace function public.dose_grace() returns interval
language sql immutable as $$ select interval '30 minutes' $$;

-- Minuti di attesa prima che un controllo "STO BENE" senza risposta avvisi il tutore.
create or replace function public.wellness_grace() returns interval
language sql immutable as $$ select interval '15 minutes' $$;

-- Orari (ora locale dell'anziano) in cui compare il pulsante "STO BENE":
-- ogni 4 ore tra le 8 e le 22. Devono coincidere con WellnessSlots.kt.
create or replace function public.wellness_slot_hours() returns int[]
language sql immutable as $$ select array[8, 12, 16, 20] $$;

-- ---------------------------------------------------------------------------
-- Tabelle
-- ---------------------------------------------------------------------------

create table public.profiles (
  id uuid primary key references auth.users (id) on delete cascade,
  role text not null check (role in ('anziano', 'tutore')),
  display_name text not null check (length(trim(display_name)) between 1 and 80),
  timezone text not null default 'Europe/Rome',
  phone text check (phone is null or phone ~ '^\+?[0-9 ]{6,20}$'),
  last_seen_at timestamptz,
  created_at timestamptz not null default now()
);

create table public.care_links (
  elder_id uuid not null references public.profiles (id) on delete cascade,
  tutor_id uuid not null references public.profiles (id) on delete cascade,
  created_at timestamptz not null default now(),
  primary key (elder_id, tutor_id)
);

create table public.pairing_codes (
  code text primary key check (code ~ '^[0-9]{6}$'),
  elder_id uuid not null references public.profiles (id) on delete cascade,
  expires_at timestamptz not null
);

create table public.medications (
  id uuid primary key default gen_random_uuid(),
  elder_id uuid not null references public.profiles (id) on delete cascade,
  name text not null check (length(trim(name)) between 1 and 80),
  dosage text not null default '' check (length(dosage) <= 80),
  instructions text not null default '' check (length(instructions) <= 200),
  active boolean not null default true,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
create index on public.medications (elder_id);

create table public.medication_times (
  id uuid primary key default gen_random_uuid(),
  medication_id uuid not null references public.medications (id) on delete cascade,
  time_of_day time not null check (date_trunc('minute', time_of_day) = time_of_day),
  created_at timestamptz not null default now(),
  unique (medication_id, time_of_day)
);

create table public.dose_events (
  id uuid primary key default gen_random_uuid(),
  medication_time_id uuid not null references public.medication_times (id) on delete cascade,
  elder_id uuid not null references public.profiles (id) on delete cascade,
  scheduled_for timestamptz not null,
  status text not null check (status in ('taken', 'missed')),
  confirmed_at timestamptz,
  created_at timestamptz not null default now(),
  unique (medication_time_id, scheduled_for)
);
create index on public.dose_events (elder_id, scheduled_for desc);

create table public.wellness_checks (
  id uuid primary key default gen_random_uuid(),
  elder_id uuid not null references public.profiles (id) on delete cascade,
  slot_at timestamptz not null,
  status text not null check (status in ('ok', 'missed')),
  answered_at timestamptz,
  created_at timestamptz not null default now(),
  unique (elder_id, slot_at)
);
create index on public.wellness_checks (elder_id, slot_at desc);

-- Avvisi destinati ai tutori: la funzione notify-tutors li invia come push.
create table public.alerts (
  id uuid primary key default gen_random_uuid(),
  elder_id uuid not null references public.profiles (id) on delete cascade,
  kind text not null check (kind in ('dose_missed', 'dose_taken_late', 'wellness_missed', 'emergency_unlock')),
  title text not null,
  body text not null,
  created_at timestamptz not null default now(),
  pushed_at timestamptz
);
create index on public.alerts (elder_id, created_at desc);
create index alerts_unpushed on public.alerts (created_at) where pushed_at is null;

create table public.push_tokens (
  token text primary key,
  user_id uuid not null references public.profiles (id) on delete cascade,
  updated_at timestamptz not null default now()
);
create index on public.push_tokens (user_id);

-- ---------------------------------------------------------------------------
-- Funzioni di autorizzazione
-- ---------------------------------------------------------------------------

create or replace function public.is_tutor_of(p_elder uuid) returns boolean
language sql stable security definer set search_path = public as $$
  select exists (
    select 1 from public.care_links
    where elder_id = p_elder and tutor_id = auth.uid()
  )
$$;

create or replace function public.can_view_elder(p_elder uuid) returns boolean
language sql stable security definer set search_path = public as $$
  select p_elder = auth.uid() or public.is_tutor_of(p_elder)
$$;

create or replace function public.my_role() returns text
language sql stable security definer set search_path = public as $$
  select role from public.profiles where id = auth.uid()
$$;

-- ---------------------------------------------------------------------------
-- Row Level Security
-- ---------------------------------------------------------------------------

alter table public.profiles enable row level security;
alter table public.care_links enable row level security;
alter table public.pairing_codes enable row level security;
alter table public.medications enable row level security;
alter table public.medication_times enable row level security;
alter table public.dose_events enable row level security;
alter table public.wellness_checks enable row level security;
alter table public.alerts enable row level security;
alter table public.push_tokens enable row level security;

-- Profili: ciascuno vede il proprio e quelli collegati (anziano <-> tutore).
create policy profiles_select on public.profiles for select using (
  id = auth.uid()
  or public.is_tutor_of(id)
  or exists (select 1 from public.care_links c where c.elder_id = auth.uid() and c.tutor_id = profiles.id)
);
create policy profiles_update on public.profiles for update
  using (id = auth.uid()) with check (id = auth.uid());

create policy care_links_select on public.care_links for select
  using (elder_id = auth.uid() or tutor_id = auth.uid());
create policy care_links_delete on public.care_links for delete
  using (tutor_id = auth.uid());

-- Terapia: la vedono anziano e tutori, la modificano solo i tutori.
create policy medications_select on public.medications for select
  using (public.can_view_elder(elder_id));
create policy medications_insert on public.medications for insert
  with check (public.is_tutor_of(elder_id));
create policy medications_update on public.medications for update
  using (public.is_tutor_of(elder_id)) with check (public.is_tutor_of(elder_id));
create policy medications_delete on public.medications for delete
  using (public.is_tutor_of(elder_id));

create policy medication_times_select on public.medication_times for select using (
  exists (select 1 from public.medications m where m.id = medication_id and public.can_view_elder(m.elder_id))
);
create policy medication_times_insert on public.medication_times for insert with check (
  exists (select 1 from public.medications m where m.id = medication_id and public.is_tutor_of(m.elder_id))
);
create policy medication_times_delete on public.medication_times for delete using (
  exists (select 1 from public.medications m where m.id = medication_id and public.is_tutor_of(m.elder_id))
);

-- Storico: solo lettura; le scritture passano dalle RPC.
create policy dose_events_select on public.dose_events for select
  using (public.can_view_elder(elder_id));
create policy wellness_checks_select on public.wellness_checks for select
  using (public.can_view_elder(elder_id));
create policy alerts_select on public.alerts for select
  using (public.is_tutor_of(elder_id));

create policy push_tokens_all on public.push_tokens for all
  using (user_id = auth.uid()) with check (user_id = auth.uid());

-- pairing_codes: nessuna policy, accessibile solo tramite RPC.

-- Il ruolo non può essere cambiato dopo la registrazione.
create or replace function public.profiles_lock_role() returns trigger
language plpgsql as $$
begin
  if new.role is distinct from old.role or new.id is distinct from old.id then
    raise exception 'role_immutable';
  end if;
  return new;
end $$;
create trigger profiles_lock_role before update on public.profiles
  for each row execute function public.profiles_lock_role();

create or replace function public.touch_updated_at() returns trigger
language plpgsql as $$ begin new.updated_at := now(); return new; end $$;
create trigger medications_touch before update on public.medications
  for each row execute function public.touch_updated_at();

-- ---------------------------------------------------------------------------
-- RPC per le app
-- ---------------------------------------------------------------------------

create or replace function public.register_profile(
  p_role text, p_display_name text, p_timezone text default 'Europe/Rome', p_phone text default null
) returns public.profiles
language plpgsql security definer set search_path = public as $$
declare
  result public.profiles;
begin
  if auth.uid() is null then raise exception 'not_authenticated'; end if;
  if p_role not in ('anziano', 'tutore') then raise exception 'invalid_role'; end if;
  if not exists (select 1 from pg_timezone_names where name = p_timezone) then
    raise exception 'invalid_timezone';
  end if;
  insert into public.profiles (id, role, display_name, timezone, phone)
  values (auth.uid(), p_role, trim(p_display_name), p_timezone, nullif(trim(p_phone), ''))
  on conflict (id) do update
    set display_name = excluded.display_name, timezone = excluded.timezone, phone = excluded.phone
    where profiles.role = excluded.role
  returning * into result;
  if result.id is null then raise exception 'role_immutable'; end if;
  return result;
end $$;

-- Anziano: genera un codice a 6 cifre valido 30 minuti da comunicare al tutore.
create or replace function public.create_pairing_code() returns text
language plpgsql security definer set search_path = public as $$
declare
  v_code text;
begin
  if public.my_role() is distinct from 'anziano' then raise exception 'only_elder'; end if;
  delete from public.pairing_codes where elder_id = auth.uid() or expires_at < now();
  loop
    v_code := lpad((floor(random() * 1000000))::int::text, 6, '0');
    begin
      insert into public.pairing_codes (code, elder_id, expires_at)
      values (v_code, auth.uid(), now() + interval '30 minutes');
      return v_code;
    exception when unique_violation then
      -- codice già in uso: riprova
    end;
  end loop;
end $$;

-- Tutore: usa il codice per collegarsi all'anziano.
create or replace function public.claim_pairing_code(p_code text) returns uuid
language plpgsql security definer set search_path = public as $$
declare
  v_elder uuid;
begin
  if public.my_role() is distinct from 'tutore' then raise exception 'only_tutor'; end if;
  delete from public.pairing_codes
  where code = trim(p_code) and expires_at >= now()
  returning elder_id into v_elder;
  if v_elder is null then raise exception 'invalid_code'; end if;
  insert into public.care_links (elder_id, tutor_id) values (v_elder, auth.uid())
  on conflict do nothing;
  return v_elder;
end $$;

-- Anziano: terapia corrente in formato compatto, più i tutori da chiamare.
create or replace function public.get_my_schedule() returns jsonb
language plpgsql stable security definer set search_path = public as $$
begin
  if public.my_role() is distinct from 'anziano' then raise exception 'only_elder'; end if;
  return jsonb_build_object(
    'timezone', (select timezone from public.profiles where id = auth.uid()),
    'wellness_hours', to_jsonb(public.wellness_slot_hours()),
    'items', coalesce((
      select jsonb_agg(jsonb_build_object(
        'medication_time_id', mt.id,
        'name', m.name,
        'dosage', m.dosage,
        'instructions', m.instructions,
        'time', to_char(mt.time_of_day, 'HH24:MI')
      ) order by mt.time_of_day, m.name)
      from public.medications m
      join public.medication_times mt on mt.medication_id = m.id
      where m.elder_id = auth.uid() and m.active
    ), '[]'::jsonb),
    'tutors', coalesce((
      select jsonb_agg(jsonb_build_object('name', p.display_name, 'phone', p.phone) order by c.created_at)
      from public.care_links c join public.profiles p on p.id = c.tutor_id
      where c.elder_id = auth.uid()
    ), '[]'::jsonb)
  );
end $$;

create or replace function public.heartbeat() returns void
language sql security definer set search_path = public as $$
  update public.profiles set last_seen_at = now() where id = auth.uid()
$$;

-- Anziano: conferma l'assunzione. Funziona anche in ritardo o offline:
-- se la dose era già stata segnata come saltata, il tutore riceve un avviso di recupero.
create or replace function public.confirm_dose(p_medication_time_id uuid, p_scheduled_for timestamptz)
returns void
language plpgsql security definer set search_path = public as $$
declare
  v_name text;
  v_prev text;
begin
  select m.name into v_name
  from public.medication_times mt join public.medications m on m.id = mt.medication_id
  where mt.id = p_medication_time_id and m.elder_id = auth.uid();
  if v_name is null then raise exception 'not_found'; end if;
  if p_scheduled_for > now() + interval '1 hour' or p_scheduled_for < now() - interval '3 days' then
    raise exception 'invalid_time';
  end if;

  select status into v_prev from public.dose_events
  where medication_time_id = p_medication_time_id and scheduled_for = p_scheduled_for;

  insert into public.dose_events (medication_time_id, elder_id, scheduled_for, status, confirmed_at)
  values (p_medication_time_id, auth.uid(), p_scheduled_for, 'taken', now())
  on conflict (medication_time_id, scheduled_for)
  do update set status = 'taken', confirmed_at = coalesce(dose_events.confirmed_at, excluded.confirmed_at);

  if v_prev = 'missed' then
    insert into public.alerts (elder_id, kind, title, body)
    select auth.uid(), 'dose_taken_late',
      p.display_name || ' ha preso il farmaco in ritardo',
      v_name || ' (previsto alle ' || to_char(p_scheduled_for at time zone p.timezone, 'HH24:MI') || ')'
    from public.profiles p where p.id = auth.uid();
  end if;
end $$;

create or replace function public.answer_wellness(p_slot_at timestamptz) returns void
language plpgsql security definer set search_path = public as $$
begin
  if public.my_role() is distinct from 'anziano' then raise exception 'only_elder'; end if;
  if p_slot_at > now() + interval '1 hour' or p_slot_at < now() - interval '1 day' then
    raise exception 'invalid_time';
  end if;
  insert into public.wellness_checks (elder_id, slot_at, status, answered_at)
  values (auth.uid(), p_slot_at, 'ok', now())
  on conflict (elder_id, slot_at)
  do update set status = 'ok', answered_at = coalesce(wellness_checks.answered_at, excluded.answered_at);
end $$;

-- Anziano: è stato usato lo sblocco di emergenza della schermata farmaco.
create or replace function public.report_emergency_unlock() returns void
language plpgsql security definer set search_path = public as $$
begin
  if public.my_role() is distinct from 'anziano' then raise exception 'only_elder'; end if;
  insert into public.alerts (elder_id, kind, title, body)
  select id, 'emergency_unlock',
    display_name || ' ha usato lo sblocco di emergenza',
    'Il telefono è stato sbloccato senza confermare il farmaco. Prova a chiamare.'
  from public.profiles where id = auth.uid();
end $$;

-- ---------------------------------------------------------------------------
-- Controllo periodico (eseguito dalla funzione notify-tutors con service_role)
-- ---------------------------------------------------------------------------

-- Segna come saltate le dosi e i controlli "STO BENE" scaduti e crea gli avvisi.
-- Considera solo le ultime 24 ore e solo anziani con almeno un tutore.
-- Restituisce il numero di avvisi creati.
create or replace function public.process_overdue(p_now timestamptz default now()) returns int
language plpgsql security definer set search_path = public as $$
declare
  v_doses int;
  v_checks int;
begin
  with elders as (
    select p.id, p.display_name, p.timezone, p.created_at
    from public.profiles p
    where p.role = 'anziano' and exists (select 1 from public.care_links c where c.elder_id = p.id)
  ),
  due as (
    select mt.id as medication_time_id, e.id as elder_id, e.display_name, e.timezone, m.name, m.dosage,
      ((d.day + mt.time_of_day) at time zone e.timezone) as scheduled_for,
      mt.created_at
    from elders e
    join public.medications m on m.elder_id = e.id and m.active
    join public.medication_times mt on mt.medication_id = m.id
    cross join lateral (values
      ((p_now at time zone e.timezone)::date),
      ((p_now at time zone e.timezone)::date - 1)
    ) as d(day)
  ),
  inserted as (
    insert into public.dose_events (medication_time_id, elder_id, scheduled_for, status)
    select medication_time_id, elder_id, scheduled_for, 'missed'
    from due
    where scheduled_for + public.dose_grace() <= p_now
      and scheduled_for > p_now - interval '24 hours'
      and scheduled_for >= created_at
    on conflict (medication_time_id, scheduled_for) do nothing
    returning medication_time_id, scheduled_for
  )
  insert into public.alerts (elder_id, kind, title, body)
  select d.elder_id, 'dose_missed',
    d.display_name || ' non ha preso un farmaco',
    trim(d.name || ' ' || d.dosage) || ' delle ' || to_char(d.scheduled_for at time zone d.timezone, 'HH24:MI')
      || ' non è stato confermato.'
  from inserted i
  join due d on d.medication_time_id = i.medication_time_id and d.scheduled_for = i.scheduled_for;
  get diagnostics v_doses = row_count;

  with elders as (
    select p.id, p.display_name, p.timezone, p.created_at
    from public.profiles p
    where p.role = 'anziano' and exists (select 1 from public.care_links c where c.elder_id = p.id)
  ),
  slots as (
    select e.id as elder_id, e.display_name, e.timezone, e.created_at,
      ((d.day + make_time(h, 0, 0)) at time zone e.timezone) as slot_at
    from elders e
    cross join lateral (values
      ((p_now at time zone e.timezone)::date),
      ((p_now at time zone e.timezone)::date - 1)
    ) as d(day)
    cross join unnest(public.wellness_slot_hours()) as h
  ),
  inserted as (
    insert into public.wellness_checks (elder_id, slot_at, status)
    select elder_id, slot_at, 'missed'
    from slots
    where slot_at + public.wellness_grace() <= p_now
      and slot_at > p_now - interval '24 hours'
      and slot_at >= created_at
    on conflict (elder_id, slot_at) do nothing
    returning elder_id, slot_at
  )
  insert into public.alerts (elder_id, kind, title, body)
  select s.elder_id, 'wellness_missed',
    s.display_name || ' non ha premuto STO BENE',
    'Nessuna risposta al controllo delle ' || to_char(s.slot_at at time zone s.timezone, 'HH24:MI')
      || '. Prova a chiamare.'
  from inserted i
  join slots s on s.elder_id = i.elder_id and s.slot_at = i.slot_at;
  get diagnostics v_checks = row_count;

  return v_doses + v_checks;
end $$;

-- ---------------------------------------------------------------------------
-- Permessi di esecuzione
-- ---------------------------------------------------------------------------

revoke execute on function public.process_overdue(timestamptz) from public, anon, authenticated;
grant execute on function public.process_overdue(timestamptz) to service_role;

revoke all on public.pairing_codes from anon, authenticated;
revoke insert, update, delete on public.dose_events, public.wellness_checks, public.alerts, public.care_links
  from anon, authenticated;
grant delete on public.care_links to authenticated;
revoke insert, delete on public.profiles from anon, authenticated;
