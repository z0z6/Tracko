# TrailTrack 0.2

Darmowa, open-source'owa aplikacja na Androida do nagrywania przejazdów rowerowych
(odpowiednik podstawowych funkcji Stravy) – z naciskiem na jazdę w różnym terenie.
Bez kont, reklam i Google Play Services.

## Funkcje
- **Nagrywanie GPS w tle**, pauza ręczna, **auto-pauza**, **okrążenia** (ręczne i automatyczne co 1/5/10 km)
- **Nawierzchnie** przełączane w trakcie jazdy (asfalt, bruk, szuter, droga leśna, singletrack, błoto, piasek, śnieg),
  trasa na mapie kolorowana wg nawierzchni
- **Room (SQLite)** zamiast plików JSON; punkty zapisują się na bieżąco, więc przerwany przejazd jest odzyskiwany
  (przejazdy z wersji 0.1 migrują się automatycznie)
- **Wykresy**: profil wysokości, prędkość wzdłuż trasy, prędkość (średnia i maks.) wg nawierzchni, podziały co 1 km
- **Statystyki**: dystans, czas w ruchu/całkowity/postoju/pauzy, średnia i maks. prędkość, podjazd, zjazd,
  wysokość min/maks., maks. nachylenie podjazdu i zjazdu, przewyższenie na km, najszybszy km, kalorie (szacunek)
- **Import GPX** i podążanie za trasą (niebieska linia, odległość do końca, ostrzeżenie o zjechaniu z trasy)
- **Mapy offline**: pobieranie kafelków (własny serwer kafelków) + import plików MBTiles/GEMF/ZIP
- Eksport do GPX; interfejs w stylu iOS (kafelki, okienka, przyciski-pigułki, ciemny motyw)

## Mapy offline – ważne
OSM i OpenTopoMap **zabraniają masowego pobierania** kafelków (osmdroid zresztą odmawia takiego pobierania).
Masz więc trzy drogi:
1. W *Ustawieniach → Mapy* wpisz adres własnego serwera/dostawcy z `{z}/{x}/{y}` (sprawdź jego regulamin)
   – wtedy działa pobieranie w zakładce *Mapy offline*.
2. Zaimportuj gotowy plik MBTiles/GEMF/ZIP (*Ustawienia → Importuj plik mapy*), po czym zrestartuj aplikację.
3. Kafelki oglądane online zapisują się same w pamięci podręcznej (do 2 GB) i są dostępne offline.

## Budowanie na GitHubie
1. Wypchnij projekt do repozytorium (gałąź `main`).
2. **Actions → Build APK** uruchomi się sam (albo *Run workflow*).
3. Pobierz artefakt **TrailTrack-debug-apk**, rozpakuj, zainstaluj `app-debug.apk`.
4. Release z APK: `git tag v0.2.0 && git push origin v0.2.0`.

Lokalnie: Android Studio → *Open*, albo `gradle assembleDebug` (Gradle 8.7, JDK 17).

## Uwagi
- Kod nie był kompilowany w momencie tworzenia – jeśli build się wywali, wklej log z Actions.
- Wysokość z GPS jest zaszumiona (wygładzanie + próg 3 m), kalorie to tylko szacunek (MET × waga × czas).
