# Tracko – strona www

Statyczna strona (HTML + CSS + JavaScript, bez kroku budowania) hostowana na GitHub Pages. Czyta i zapisuje dane w tym samym
zapleczu Supabase co aplikacja (kontrakt: [`docs/CLOUD_API.md`](../docs/CLOUD_API.md)).

## Co potrafi
- **Ekran startowy** – ta sama animacja co w aplikacji (ślad rysuje się gradientem, za nim przerywany duch, nazwa; ok. 2 s; klik pomija).
- **Wyniki** – ostatnie wyniki z aplikacji, liczniki, najpopularniejsze odcinki, Twoje wyniki po zalogowaniu.
- **Odcinki i duchy** – wyszukiwarka z filtrem aktywności, ranking odcinka, **wyścig duchów** na mapie (wybierasz wyniki, ustawiasz
  prędkość, przewijasz), pobranie pliku `.ttseg` do zaimportowania w aplikacji.
- **Aktywności** – publiczne i Twoje (wysłane z aplikacji): uproszczony ślad, statystyki, rozkład nawierzchni, eksport GPX,
  „Zaplanuj podobną trasę”.
- **Planer tras** – punkty klikane na mapie, wyznaczanie po drogach (rower/pieszo) albo odręcznie, pętla, profil wysokości
  z podglądem na mapie, szacowany czas, zapis w koncie (trasa pojawia się w aplikacji w *Trasy online*), eksport GPX.
- Jasny/ciemny motyw (jak w aplikacji), układ mobilny z dolnym paskiem, tryb ograniczonego ruchu.

## Uruchomienie lokalne
```
python3 -m http.server 8000 -d web      # potem: http://localhost:8000/?demo=1
```
`?demo=1` (albo pusty `assets/config.js`) włącza **tryb demonstracyjny** z danymi przykładowymi – nie wymaga serwera.
`?nosplash` pomija animację startową.

## Wdrożenie
1. W repozytorium: *Settings → Pages → Build and deployment → Source: **GitHub Actions***.
2. Sekrety `TRACKO_CLOUD_URL` i `TRACKO_CLOUD_ANON_KEY` (te same co w buildzie aplikacji) – workflow `Pages` wstrzykuje je do
   `assets/config.js`. Bez nich strona działa w trybie demonstracyjnym.
3. W Supabase: *Authentication → URL Configuration* → **Site URL** = adres strony (np. `https://z0z6.github.io/Tracko/`) –
   potrzebne, jeśli potwierdzasz e-maile przy zakładaniu kont.
4. Push do `main` (zmiana w `web/`) publikuje stronę automatycznie; ręcznie: *Actions → Pages → Run workflow*.

## Usługi zewnętrzne (zasady uczciwego użycia)
| Do czego | Usługa | Uwagi |
|---|---|---|
| kafelki mapy | CARTO (dane © OpenStreetMap) | atrybucja widoczna na mapie; przy dużym ruchu użyj własnego dostawcy z kluczem |
| trasy po drogach | FOSSGIS OSRM (`routing.openstreetmap.de`) | publiczny serwer do umiarkowanego użytku |
| wysokości | Open-Meteo Elevation | bezpłatne dla użytku niekomercyjnego |
| szukanie miejsc | Nominatim (OSM) | max. 1 zapytanie/s, wyszukiwanie tylko na żądanie użytkownika |
| biblioteki | Leaflet 1.9.4, supabase-js 2 (CDN) | |

## Struktura
```
web/index.html            szkielet, nawigacja, okno logowania
web/assets/app.js         widoki i router (hash: #/, #/odcinki, #/odcinek/<uid>, #/aktywnosci, #/aktywnosc/<owner>/<start>, #/planer)
web/assets/lib.js         czysta logika: geometria, profile duchów, formatowanie, GPX/.ttseg (testowalna w Node)
web/assets/api.js         zapytania do Supabase; demo.js – dane przykładowe o tym samym interfejsie
web/assets/splash.js      animacja startowa; style.css – motyw i komponenty
```
