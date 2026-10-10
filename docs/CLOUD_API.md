# Tracko – zaplecze online i kontrakt danych

GitHub Pages hostuje tylko pliki statyczne (HTML/CSS/JS) i **nie przyjmie danych z aplikacji**. Dlatego rywalizacja online
składa się z trzech części:

```
 Aplikacja Tracko (Android) ──HTTPS──►  Supabase  ◄──HTTPS (supabase-js)──  Strona www (GitHub Pages)
   odcinki, wyniki/duchy,               Postgres + Auth + REST               ranking, mapa, analiza,
   aktywności, trasy                    (bezpłatny plan wystarcza)           planowanie tras
```

Aplikacja i strona rozmawiają z tą samą bazą przez REST (PostgREST). Strona używa publicznego klucza **anon**
(bezpieczeństwo zapewniają polityki RLS z `backend/supabase/schema.sql`, nie tajność klucza).

## 1. Uruchomienie zaplecza (jednorazowo)

1. Załóż projekt na <https://supabase.com> (plan Free).
2. **SQL Editor** → wklej `backend/supabase/schema.sql` → Run (można uruchamiać wielokrotnie).
3. **Authentication → Sign In / Providers → Allow anonymous sign-ins: włącz** (aplikacja loguje się anonimowo, bez rejestracji).
4. Opcjonalnie **Authentication → Providers → Email → wyłącz „Confirm email”** – wtedy założenie konta z e-mailem w aplikacji działa od razu.
5. Skopiuj dwie wartości (to są właśnie `TRACKO_CLOUD_URL` i `TRACKO_CLOUD_ANON_KEY`):
   - **Project URL** – `https://<ref>.supabase.co` (Project Settings → API albo przycisk *Connect*); bez końcowego `/` i bez `/rest/v1`.
   - **Klucz publiczny** – Project Settings → **API Keys**: *Publishable key* (`sb_publishable_…`) albo, w zakładce *Legacy API Keys*,
     klucz **anon** (`eyJ…`). Aplikacja obsługuje oba rodzaje. **Nigdy** nie wpisuj kluczy *secret* / *service_role* – omijają zabezpieczenia.
6. W repozytorium GitHub: *Settings → Secrets and variables → Actions* → dodaj `TRACKO_CLOUD_URL` oraz `TRACKO_CLOUD_ANON_KEY`.
   Następne wydanie (tag `v*`) będzie miało adres wbudowany w aplikację. Bez sekretów adres i klucz można wpisać w aplikacji:
   Ustawienia → Rywalizacja online → Zaawansowane.
7. Darmowy projekt **usypia się po okresie bezczynności** (ok. tygodnia). Workflow `.github/workflows/supabase-keepalive.yml`
   budzi go cyklicznie (korzysta z tych samych sekretów). Uwaga: w repozytoriach publicznych GitHub wyłącza zaplanowane
   workflowy po ~60 dniach bez aktywności w repo – wystarczy dowolny commit.

## 2. Tabele (schemat `public`)

| Tabela | Klucz | Zawartość |
|---|---|---|
| `segments` | `uid` (text, UUID z aplikacji) | odcinek: `name`, `sport`, `length_m`, `geom`, ramka `min/max_lat/lon`, `author`, `is_public` |
| `segment_efforts` | `id`; unikalne `(segment_uid, owner, started_at)` | wynik na odcinku: `athlete`, `started_at` (ms), `time_sec`, `profile` (**duch**) |
| `rides` | `(owner, started_at)` | aktywność: statystyki, `polyline` (uproszczony, przycięty ślad), `visibility` (`private`/`public`) |
| `routes` | `id` (uuid); unikalne `(owner, client_uid)` | trasa: `geom` z wysokością, `is_public`, `source` (`app`/`web`) |
| widok `segment_overview` | `uid` | odcinek + `efforts` (liczba wyników) + `best_sec` – do przeglądania i rankingów |

### Formaty pól tekstowych
- **`geom` (odcinek)**: `lat,lon;lat,lon;…` – WGS84, 6 miejsc po przecinku, punkty co ≥ 8 m, maks. ok. 900 punktów.
- **`geom` (trasa)**: `lat,lon,ele;lat,lon,ele;…` – wysokość w metrach (może być `0`), maks. 3000 punktów.
- **`profile` (duch)**: 101 liczb rozdzielonych przecinkami = czas w sekundach po przejechaniu `k`% długości odcinka
  (`k = 0…100`), niemalejące, ostatnia wartość = `time_sec`. Pozycję ducha po czasie *t* wyznacza się odwracając ten profil
  (interpolacja liniowa) – tak samo robi aplikacja (`Ghost.kt`).
- **`polyline` (aktywność)**: jak `geom` odcinka. Pusty, gdy ślad jest za krótki po przycięciu albo aktywność bez GPS.
- **`terrain_enc`**: `NAZWA=metry;NAZWA=metry` np. `ASPHALT=8200.5;GRAVEL=1100`. Nazwy: `ASPHALT, COBBLES, GRAVEL, DIRT,
  SINGLETRACK, MUD, SAND, SNOW`.
- **`sport`**: `0` rower, `1` bieganie, `2` pływanie (basen), `3` siłownia, `4` bieżnia, `5` narty biegowe, `6` spacer,
  `7` kajak, `8` rolki.

## 3. Co robi aplikacja (REST)

Nagłówki: `apikey: <anon>`, `Authorization: Bearer <access_token>` (albo klucz anon dla odczytów publicznych).

| Akcja | Żądanie |
|---|---|
| logowanie anonimowe | `POST /auth/v1/signup` z `{}` |
| odświeżenie sesji | `POST /auth/v1/token?grant_type=refresh_token` |
| konto z e-mailem (z zachowaniem danych) | `PUT /auth/v1/user` `{email, password}` |
| logowanie e-mail/hasło | `POST /auth/v1/token?grant_type=password` |
| wysłanie odcinka | `POST /rest/v1/segments?on_conflict=uid` + `Prefer: resolution=ignore-duplicates` |
| wysłanie wyniku | `POST /rest/v1/segment_efforts?on_conflict=segment_uid,owner,started_at` + `Prefer: resolution=ignore-duplicates` |
| ranking odcinka | `GET /rest/v1/segment_efforts?segment_uid=eq.<uid>&order=time_sec.asc&limit=100` |
| przeglądanie odcinków | `GET /rest/v1/segment_overview?order=efforts.desc&name=ilike.*tekst*` |
| wysłanie aktywności | `POST /rest/v1/rides?on_conflict=owner,started_at` + `Prefer: resolution=merge-duplicates` |
| wysłanie / pobranie tras | `POST /rest/v1/routes?on_conflict=owner,client_uid`, `GET /rest/v1/routes?order=created_at.desc` |
| usunięcie konta i danych | `POST /rest/v1/rpc/delete_my_account` |

## 4. Strona www (GitHub Pages)

Gotowa strona jest w folderze [`web/`](../web/README.md) (wdrażana workflowem `Pages`): ranking, wyścigi duchów, aktywności i planer
tras. Poniżej przykłady zapytań z `supabase-js`, na których się opiera.

```html
<script src="https://cdn.jsdelivr.net/npm/@supabase/supabase-js@2"></script>
<script>
  const db = supabase.createClient('https://TWÓJ-PROJEKT.supabase.co', 'TWÓJ_KLUCZ_ANON');

  // ranking odcinka (publiczny, bez logowania)
  const { data: top } = await db.from('segment_efforts')
    .select('athlete,time_sec,started_at').eq('segment_uid', UID).order('time_sec').limit(20);

  // lista odcinków z liczbą wyników
  const { data: segs } = await db.from('segment_overview').select('*').order('efforts', { ascending: false });

  // zalogowany użytkownik zapisuje trasę zaplanowaną na stronie – aplikacja zobaczy ją w „Trasy online”
  await db.auth.signInWithPassword({ email, password });
  await db.from('routes').insert({
    name: 'Pętla wokół jeziora', sport: 0, distance_m: 42000, ascent_m: 310,
    geom: '50.0,19.0,200;50.01,19.02,210', source: 'web', is_public: false
  });
</script>
```
Mapę i profil wysokości na stronie można rysować np. Leaflet + dane z `geom` / `polyline` (rozdziel po `;` i `,`).

## 5. Bezpieczeństwo i prywatność

- **Wszystkie tabele mają RLS**: odczyt publicznych rekordów dla każdego, zapis tylko właściciela (`owner = auth.uid()`).
  Aktywności i trasy są domyślnie **prywatne** (`visibility = 'private'`, `is_public = false`).
- Przed wysłaniem aplikacja **wycina początek i koniec śladu** (0 / 100 / 200 / 500 m, domyślnie 200 m) i upraszcza go.
  Tętno, moc i inne dane sensorów **nie są wysyłane**.
- Wysyłanie jest **opt-in** (okno zgody przy pierwszym włączeniu), a konto można usunąć razem z danymi
  (Ustawienia → Rywalizacja online → Usuń konto…). Na stronie przygotuj politykę prywatności zgodną z RODO.
- **Kontrola uczciwości**: baza odrzuca wyniki o nierealnej średniej prędkości (> ~108 km/h), z profilem niezgodnym z czasem
  i ponad 200 wyników na godzinę z jednego konta; są też dzienne limity odcinków (30), aktywności (300) i tras (100).
  To nie jest ochrona przed zdeterminowanym oszustem (aplikacja nie ma dowodu przejazdu) – dla rywalizacji towarzyskiej wystarcza.
- Anonimowe logowanie ma limit żądań na adres IP (Supabase), a konta anonimowe można nadużywać – jeśli ruch wzrośnie, włącz
  CAPTCHA w Supabase albo wymagaj e-maila przed wysyłaniem.

## 6. Zmiany kontraktu

Schemat rozwijaj **wyłącznie przez dodawanie** kolumn z wartością domyślną i nowych tabel – wtedy starsze wersje aplikacji
działają dalej. Przy zmianach łamiących (nowy format `geom`/`profile`) dodaj kolumnę wersji i obsłuż obie wersje.
