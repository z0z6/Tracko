-- =====================================================================================================
--  Tracko – migracja v2 (hardening + wydajność). Uruchom PO backend/supabase/schema.sql. Idempotentna.
--  Każda zmiana została sprawdzona na PostgreSQL 16 z rolami anon/authenticated i działającym RLS.
--  Kompatybilność: kontrakt REST (nazwy tabel, kolumn, widoku segment_overview) bez zmian.
-- =====================================================================================================

-- ------------------------------------------------------------------ 1. uprawnienia (defense in depth)
-- Supabase domyślnie nadaje anon/authenticated pełne uprawnienia na nowych tabelach; zostawiamy tylko to, co potrzebne.
revoke all on all tables    in schema public from anon, authenticated;
revoke all on all sequences in schema public from anon, authenticated;
revoke execute on function public.tracko_check_effort()  from public, anon, authenticated;
revoke execute on function public.tracko_limit_inserts() from public, anon, authenticated;
grant select on public.segments, public.segment_efforts, public.rides, public.routes, public.segment_overview to anon, authenticated;
grant insert, update, delete on public.segments, public.rides, public.routes to authenticated;
grant insert, delete on public.segment_efforts to authenticated;
grant usage, select on all sequences in schema public to authenticated;

-- ------------------------------------------------------------------ 2. walidacja danych (NOT VALID: nie rusza starych wierszy)
do $$ begin
  alter table public.segments add constraint segments_uid_fmt   check (uid ~ '^[0-9A-Za-z-]{8,64}$') not valid;
  exception when duplicate_object then null; end $$;
do $$ begin
  alter table public.segments add constraint segments_geom_fmt  check (geom ~ '^(-?[0-9.]+,-?[0-9.]+;)+-?[0-9.]+,-?[0-9.]+$') not valid;
  exception when duplicate_object then null; end $$;
do $$ begin
  alter table public.segments add constraint segments_bbox_rng  check (min_lat between -90 and 90 and max_lat between -90 and 90
    and min_lon between -180 and 180 and max_lon between -180 and 180 and min_lat <= max_lat and min_lon <= max_lon) not valid;
  exception when duplicate_object then null; end $$;
do $$ begin
  alter table public.segment_efforts add constraint efforts_profile_len check (length(profile) <= 2500) not valid;
  exception when duplicate_object then null; end $$;
do $$ begin
  alter table public.segment_efforts add constraint efforts_started_rng check (started_at between 946684800000 and 4102444800000) not valid;
  exception when duplicate_object then null; end $$;
do $$ begin
  alter table public.rides add constraint rides_values_rng check (
    started_at between 946684800000 and 4102444800000 and distance_m <= 3000000 and moving_s <= 604800
    and ascent_m between 0 and 30000 and kcal between 0 and 100000 and avg_hr between 0 and 300 and tss between 0 and 5000) not valid;
  exception when duplicate_object then null; end $$;
do $$ begin
  alter table public.rides add constraint rides_poly_fmt check (polyline = '' or polyline ~ '^(-?[0-9.]+,-?[0-9.]+;)+-?[0-9.]+,-?[0-9.]+$') not valid;
  exception when duplicate_object then null; end $$;
do $$ begin
  alter table public.rides add constraint rides_terrain_fmt check (terrain_enc = '' or terrain_enc ~ '^[A-Z]+=[0-9.]+(;[A-Z]+=[0-9.]+)*$') not valid;
  exception when duplicate_object then null; end $$;
do $$ begin
  alter table public.routes add constraint routes_geom_fmt check (geom ~ '^(-?[0-9.]+,-?[0-9.]+,-?[0-9.]+;)*-?[0-9.]+,-?[0-9.]+(,-?[0-9.]+)?$') not valid;
  exception when duplicate_object then null; end $$;

-- ------------------------------------------------------------------ 3. wiarygodność wyników
create or replace function public.tracko_check_effort() returns trigger
language plpgsql set search_path = '' as $$
declare
  seg_len double precision; seg_sport smallint; recent int; bad int; max_v double precision;
begin
  -- serializacja per użytkownik: bez tego równoległe żądania omijają limit (test: 40 sesji → 239 zamiast 200)
  perform pg_advisory_xact_lock(hashtext('effort:' || new.owner::text));

  select s.length_m, s.sport into seg_len, seg_sport from public.segments s where s.uid = new.segment_uid;
  if seg_len is null then raise exception 'segment_not_found'; end if;

  max_v := case seg_sport when 1 then 12 when 6 then 4 when 5 then 15 when 7 then 8 when 8 then 18 else 30 end;  -- m/s
  if seg_len / new.time_sec > max_v then raise exception 'implausible_speed'; end if;

  -- profil: 101 liczb, niemalejący, nieujemny, początek ~0, koniec = czas
  select count(*) into bad from (
    select x::double precision as v, lag(x::double precision) over (order by ord) as pv
    from unnest(string_to_array(new.profile, ',')) with ordinality as t(x, ord)) q
   where q.v < 0 or (q.pv is not null and q.v < q.pv);
  if bad > 0 then raise exception 'profile_not_monotonic'; end if;
  if (string_to_array(new.profile, ','))[1]::double precision > 3 then raise exception 'profile_bad_start'; end if;
  if abs((string_to_array(new.profile, ','))[101]::double precision - new.time_sec) > 3 then raise exception 'profile_mismatch'; end if;

  select count(*) into recent from public.segment_efforts e where e.owner = new.owner and e.created_at > now() - interval '1 hour';
  if recent >= 200 then raise exception 'rate_limited'; end if;
  return new;
end $$;

-- geometria i długość odcinka są niezmienne, gdy istnieją wyniki (inaczej ranking da się zafałszować po fakcie)
create or replace function public.tracko_freeze_segment() returns trigger
language plpgsql set search_path = '' as $$
begin
  if (new.geom is distinct from old.geom or new.length_m is distinct from old.length_m or new.sport is distinct from old.sport)
     and exists (select 1 from public.segment_efforts e where e.segment_uid = old.uid) then
    raise exception 'segment_frozen';
  end if;
  return new;
end $$;
revoke execute on function public.tracko_freeze_segment() from public, anon, authenticated;
drop trigger if exists tracko_freeze_segment_trg on public.segments;
create trigger tracko_freeze_segment_trg before update on public.segments for each row execute function public.tracko_freeze_segment();

-- ------------------------------------------------------------------ 4. limity dzienne: bez blokowania upsertów, z blokadą
create or replace function public.tracko_limit_inserts() returns trigger
language plpgsql set search_path = '' as $$
declare recent int; max_per_day int := tg_argv[0]::int;
begin
  perform pg_advisory_xact_lock(hashtext(tg_table_name || ':' || new.owner::text));
  -- ON CONFLICT DO UPDATE przechodzi przez BEFORE INSERT: nie liczymy aktualizacji istniejącego wiersza
  -- (zagnieżdżone IF-y: PL/pgSQL rozwiązuje pola rekordu NEW także w niewykonanej gałęzi AND, co psuje inne tabele)
  if tg_table_name = 'rides' then
    if exists (select 1 from public.rides r where r.owner = new.owner and r.started_at = new.started_at) then return new; end if;
  elsif tg_table_name = 'routes' then
    if exists (select 1 from public.routes r where r.owner = new.owner and r.client_uid = new.client_uid) then return new; end if;
  end if;
  execute format('select count(*) from %I.%I where owner = $1 and created_at > now() - interval ''1 day''', tg_table_schema, tg_table_name)
    into recent using new.owner;
  if recent >= max_per_day then raise exception 'rate_limited'; end if;
  return new;
end $$;

-- ------------------------------------------------------------------ 5. indeksy
create index if not exists segment_efforts_owner_created_idx on public.segment_efforts (owner, created_at desc);
create index if not exists segment_efforts_created_idx        on public.segment_efforts (created_at desc);
create index if not exists segments_owner_created_idx         on public.segments (owner, created_at desc);
create index if not exists segments_created_idx               on public.segments (created_at desc);
create index if not exists routes_owner_created_idx           on public.routes (owner, created_at desc);

-- ------------------------------------------------------------------ 6. RLS: niepowiązane IN (hashed SubPlan) zamiast EXISTS na wiersz
drop policy if exists efforts_read on public.segment_efforts;
create policy efforts_read on public.segment_efforts for select using (
  owner = (select auth.uid()) or segment_uid in (select s.uid from public.segments s where s.is_public)
);

-- ------------------------------------------------------------------ 7. liczniki zamiast agregatu w widoku
alter table public.segments add column if not exists efforts_n int not null default 0;
alter table public.segments add column if not exists best_sec  double precision;
update public.segments s set efforts_n = x.n, best_sec = x.b
  from (select segment_uid, count(*)::int n, min(time_sec) b from public.segment_efforts group by 1) x where x.segment_uid = s.uid;

create or replace function public.tracko_effort_counters() returns trigger
language plpgsql security definer set search_path = '' as $$
declare uid_ text := coalesce(new.segment_uid, old.segment_uid);
begin
  update public.segments s set efforts_n = c.n, best_sec = c.b
    from (select count(*)::int n, min(time_sec) b from public.segment_efforts where segment_uid = uid_) c
   where s.uid = uid_;
  return null;
end $$;
revoke execute on function public.tracko_effort_counters() from public, anon, authenticated;
drop trigger if exists tracko_effort_counters_trg on public.segment_efforts;
create trigger tracko_effort_counters_trg after insert or delete on public.segment_efforts
  for each row execute function public.tracko_effort_counters();

create index if not exists segments_popular_idx on public.segments (efforts_n desc, created_at desc);

create or replace view public.segment_overview with (security_invoker = true) as
select s.uid, s.name, s.sport, s.length_m, s.author, s.created_at,
       s.min_lat, s.max_lat, s.min_lon, s.max_lon,
       s.efforts_n as efforts, s.best_sec
from public.segments s;
grant select on public.segment_overview to anon, authenticated;

analyze;
