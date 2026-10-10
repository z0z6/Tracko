-- =====================================================================================================
--  Tracko – zaplecze online (Supabase / PostgreSQL)
--  Uruchom w Supabase: SQL Editor → wklej całość → Run. Skrypt można uruchamiać wielokrotnie.
--  Wymaga włączenia: Authentication → Sign In / Providers → "Allow anonymous sign-ins".
--  Klucz „anon” jest publiczny z założenia – bezpieczeństwo zapewniają poniższe polityki RLS.
-- =====================================================================================================

-- ------------------------------------------------------------------ tabele ------------------------

create table if not exists public.segments (
  uid         text primary key check (length(uid) between 8 and 64),
  owner       uuid not null default auth.uid() references auth.users(id) on delete cascade,
  name        text not null check (length(name) between 1 and 80),
  sport       smallint not null check (sport between 0 and 20),
  length_m    double precision not null check (length_m between 100 and 300000),
  geom        text not null check (length(geom) between 20 and 120000),   -- "lat,lon;lat,lon;…"
  min_lat     double precision not null,
  max_lat     double precision not null,
  min_lon     double precision not null,
  max_lon     double precision not null,
  author      text not null default '' check (length(author) <= 40),
  is_public   boolean not null default true,
  created_at  timestamptz not null default now()
);
create index if not exists segments_sport_idx on public.segments (sport);
create index if not exists segments_bbox_idx  on public.segments (min_lat, max_lat);

create table if not exists public.segment_efforts (
  id           bigint generated always as identity primary key,
  segment_uid  text not null references public.segments(uid) on delete cascade,
  owner        uuid not null default auth.uid() references auth.users(id) on delete cascade,
  athlete      text not null check (length(athlete) between 1 and 40),
  started_at   bigint not null,                                  -- ms od 1970 (czas startu przejazdu)
  time_sec     double precision not null check (time_sec between 5 and 172800),
  -- profil „ducha”: 101 czasów (s) w punktach co 1% długości odcinka
  profile      text not null check (array_length(string_to_array(profile, ','), 1) = 101),
  created_at   timestamptz not null default now(),
  unique (segment_uid, owner, started_at)
);
create index if not exists segment_efforts_rank_idx on public.segment_efforts (segment_uid, time_sec);

create table if not exists public.rides (
  owner        uuid not null default auth.uid() references auth.users(id) on delete cascade,
  started_at   bigint not null,
  sport        smallint not null check (sport between 0 and 20),
  name         text not null default '' check (length(name) <= 120),
  athlete      text not null default '' check (length(athlete) <= 40),
  distance_m   double precision not null default 0 check (distance_m >= 0),
  moving_s     double precision not null default 0 check (moving_s >= 0),
  ascent_m     double precision not null default 0,
  kcal         double precision not null default 0,
  avg_hr       integer not null default 0,
  tss          double precision not null default 0,
  terrain_enc  text not null default '' check (length(terrain_enc) <= 400),
  polyline     text not null default '' check (length(polyline) <= 120000),   -- uproszczona, przycięta trasa
  visibility   text not null default 'private' check (visibility in ('private', 'public')),
  created_at   timestamptz not null default now(),
  primary key (owner, started_at)
);
create index if not exists rides_public_idx on public.rides (visibility, started_at desc);

create table if not exists public.routes (
  id           uuid primary key default gen_random_uuid(),
  owner        uuid not null default auth.uid() references auth.users(id) on delete cascade,
  client_uid   text check (length(client_uid) <= 64),          -- id nadane przez aplikację (deduplikacja)
  name         text not null check (length(name) between 1 and 80),
  sport        smallint not null default 0 check (sport between 0 and 20),
  distance_m   double precision not null default 0 check (distance_m >= 0),
  ascent_m     double precision not null default 0,
  geom         text not null check (length(geom) between 20 and 300000),   -- "lat,lon,ele;lat,lon,ele;…"
  is_public    boolean not null default false,
  source       text not null default 'app' check (source in ('app', 'web')),
  author       text not null default '' check (length(author) <= 40),
  created_at   timestamptz not null default now(),
  unique (owner, client_uid)
);
create index if not exists routes_public_idx on public.routes (is_public, created_at desc);

-- ------------------------------------------------------------------ kontrola wyników i limity -----

create or replace function public.tracko_check_effort() returns trigger
language plpgsql set search_path = '' as $$
declare
  seg_len double precision;
  last_val double precision;
  recent int;
begin
  select s.length_m into seg_len from public.segments s where s.uid = new.segment_uid;
  if seg_len is null then raise exception 'segment_not_found'; end if;

  -- wiarygodność: średnia prędkość nie może przekraczać ~108 km/h
  if seg_len / new.time_sec > 30 then raise exception 'implausible_speed'; end if;

  -- profil musi się zgadzać z czasem końcowym i rosnąć
  last_val := (string_to_array(new.profile, ','))[101]::double precision;
  if abs(last_val - new.time_sec) > 3 then raise exception 'profile_mismatch'; end if;

  -- limit: 200 wyników na godzinę na użytkownika
  select count(*) into recent from public.segment_efforts e
   where e.owner = new.owner and e.created_at > now() - interval '1 hour';
  if recent >= 200 then raise exception 'rate_limited'; end if;
  return new;
end $$;

drop trigger if exists tracko_check_effort_trg on public.segment_efforts;
create trigger tracko_check_effort_trg before insert on public.segment_efforts
  for each row execute function public.tracko_check_effort();

create or replace function public.tracko_limit_inserts() returns trigger
language plpgsql set search_path = '' as $$
declare
  recent int;
  max_per_day int := tg_argv[0]::int;
begin
  execute format('select count(*) from %I.%I where owner = $1 and created_at > now() - interval ''1 day''',
                 tg_table_schema, tg_table_name) into recent using new.owner;
  if recent >= max_per_day then raise exception 'rate_limited'; end if;
  return new;
end $$;

drop trigger if exists tracko_limit_segments on public.segments;
create trigger tracko_limit_segments before insert on public.segments
  for each row execute function public.tracko_limit_inserts(30);
drop trigger if exists tracko_limit_rides on public.rides;
create trigger tracko_limit_rides before insert on public.rides
  for each row execute function public.tracko_limit_inserts(300);
drop trigger if exists tracko_limit_routes on public.routes;
create trigger tracko_limit_routes before insert on public.routes
  for each row execute function public.tracko_limit_inserts(100);

-- ------------------------------------------------------------------ widok do przeglądania -----------

create or replace view public.segment_overview with (security_invoker = true) as
select s.uid, s.name, s.sport, s.length_m, s.author, s.created_at,
       s.min_lat, s.max_lat, s.min_lon, s.max_lon,
       count(e.id)::int as efforts,
       min(e.time_sec)  as best_sec
from public.segments s
left join public.segment_efforts e on e.segment_uid = s.uid
group by s.uid;

-- ------------------------------------------------------------------ usuwanie konta (RODO) ----------

create or replace function public.delete_my_account() returns void
language plpgsql security definer set search_path = '' as $$
begin
  if auth.uid() is null then raise exception 'not_authenticated'; end if;
  delete from auth.users where id = auth.uid();   -- kaskadowo usuwa odcinki, wyniki, aktywności, trasy
end $$;
revoke all on function public.delete_my_account() from public, anon;
grant execute on function public.delete_my_account() to authenticated;

-- ------------------------------------------------------------------ bezpieczeństwo wierszowe -------

alter table public.segments        enable row level security;
alter table public.segment_efforts enable row level security;
alter table public.rides           enable row level security;
alter table public.routes          enable row level security;

drop policy if exists segments_read   on public.segments;
drop policy if exists segments_insert on public.segments;
drop policy if exists segments_update on public.segments;
drop policy if exists segments_delete on public.segments;
create policy segments_read   on public.segments for select using (is_public or owner = auth.uid());
create policy segments_insert on public.segments for insert to authenticated with check (owner = auth.uid());
create policy segments_update on public.segments for update to authenticated using (owner = auth.uid()) with check (owner = auth.uid());
create policy segments_delete on public.segments for delete to authenticated using (owner = auth.uid());

drop policy if exists efforts_read   on public.segment_efforts;
drop policy if exists efforts_insert on public.segment_efforts;
drop policy if exists efforts_delete on public.segment_efforts;
create policy efforts_read on public.segment_efforts for select using (
  owner = auth.uid() or exists (select 1 from public.segments s where s.uid = segment_uid and s.is_public)
);
create policy efforts_insert on public.segment_efforts for insert to authenticated with check (
  owner = auth.uid() and exists (select 1 from public.segments s where s.uid = segment_uid)
);
create policy efforts_delete on public.segment_efforts for delete to authenticated using (owner = auth.uid());

drop policy if exists rides_read   on public.rides;
drop policy if exists rides_insert on public.rides;
drop policy if exists rides_update on public.rides;
drop policy if exists rides_delete on public.rides;
create policy rides_read   on public.rides for select using (visibility = 'public' or owner = auth.uid());
create policy rides_insert on public.rides for insert to authenticated with check (owner = auth.uid());
create policy rides_update on public.rides for update to authenticated using (owner = auth.uid()) with check (owner = auth.uid());
create policy rides_delete on public.rides for delete to authenticated using (owner = auth.uid());

drop policy if exists routes_read   on public.routes;
drop policy if exists routes_insert on public.routes;
drop policy if exists routes_update on public.routes;
drop policy if exists routes_delete on public.routes;
create policy routes_read   on public.routes for select using (is_public or owner = auth.uid());
create policy routes_insert on public.routes for insert to authenticated with check (owner = auth.uid());
create policy routes_update on public.routes for update to authenticated using (owner = auth.uid()) with check (owner = auth.uid());
create policy routes_delete on public.routes for delete to authenticated using (owner = auth.uid());

-- ------------------------------------------------------------------ uprawnienia ---------------------

grant usage on schema public to anon, authenticated;
grant select on public.segments, public.segment_efforts, public.rides, public.routes, public.segment_overview to anon, authenticated;
grant insert, update, delete on public.segments, public.rides, public.routes to authenticated;
grant insert, delete on public.segment_efforts to authenticated;
grant usage, select on all sequences in schema public to authenticated;
