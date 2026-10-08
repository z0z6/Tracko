# TrailTrack 0.3

Darmowa, open-source'owa aplikacja na Androida do nagrywania przejazdów rowerowych
(odpowiednik podstawowych funkcji Stravy) – z naciskiem na jazdę w różnym terenie.
Bez kont, reklam i Google Play Services.

## Funkcje
- **Nagrywanie GPS w tle**, pauza ręczna, **auto-pauza**, **okrążenia** (ręczne i automatyczne co 1/5/10 km)
- **Nawierzchnie** przełączane w trakcie jazdy (asfalt, bruk, szuter, droga leśna, singletrack, błoto, piasek, śnieg),
  trasa na mapie kolorowana wg nawierzchni (białe obwódki dla czytelności, legenda kolorów, bez łączenia linii przez pauzy)
- **Wymuszony GPS**: po uruchomieniu aplikacja wymaga zgody na lokalizację i włączonego GPS (pełnoekranowa blokada z przyciskiem
  do ustawień); jeśli GPS zgaśnie w trakcie nagrywania – blokada + głośne powiadomienie, a luka nie wlicza się do dystansu
- **Room (SQLite)** zamiast plików JSON; punkty zapisują się na bieżąco, więc przerwany przejazd jest odzyskiwany
  (przejazdy z wersji 0.1 migrują się automatycznie)
- **Wykresy**: profil wysokości, prędkość wzdłuż trasy, prędkość (średnia i maks.) wg nawierzchni, podziały co 1 km
- **Statystyki**: dystans, czas w ruchu/całkowity/postoju/pauzy, średnia i maks. prędkość, podjazd, zjazd,
  wysokość min/maks., maks. nachylenie podjazdu i zjazdu, przewyższenie na km, najszybszy km, kalorie (szacunek)
- **Import GPX** i podążanie za trasą (niebieska linia, odległość do końca, ostrzeżenie o zjechaniu z trasy)
- **Mapy offline**: pobieranie kafelków (własny serwer kafelków) + import plików MBTiles/GEMF/ZIP
- Eksport do GPX; interfejs w stylu iOS (kafelki, okienka, przyciski-pigułki, ciemny motyw)

## Rodzaje aktywności (0.5)
Wybór na górze zakładki *Nagrywaj* (przed startem): **Rower, Bieganie, Pływanie, Siłownia, Bieżnia, Narty biegowe**.
Każda aktywność zapisuje się z kategorią (migracja bazy do v4, dotychczasowe przejazdy to „Rower”), historia ma filtr
kategorii, a zakładka *Analiza* – podsumowanie osobno dla każdej z nich. Eksport TCX/CSV/GPX uwzględnia rodzaj aktywności.
- **Rower / Bieganie / Narty biegowe** – GPS, mapa kolorowana wg nawierzchni (narty domyślnie na śniegu), tempo w min/km
  dla biegania; aplikacja wymusza włączony GPS tylko dla tych trzech
- **Bieżnia** – bez GPS: ustawiasz prędkość (±0,1 / ±1 km/h) i nachylenie (±0,5 / ±1 %) tak jak na bieżni; dystans liczy się
  z prędkości × czas, przewyższenie z nachylenia
- **Pływanie (basen)** – bez GPS: długość basenu 25/50 m lub własna, duży przycisk „+ Długość”, tempo na 100 m, podziały
  co 100 m, serie przyciskiem „Seria”. Pływanie na wodach otwartych nie jest obsługiwane (GPS pod wodą nie działa)
- **Siłownia** – bez GPS: czas, tętno z pasa BLE, kalorie (szacunek, MET 5), serie
- Komunikaty głosowe i cele działają we wszystkich aktywnościach; rekordy i „tempo jak zazwyczaj” liczone są osobno dla
  każdej aktywności

## Dźwięki i komunikaty głosowe (0.5)
Ustawienia → *Dźwięki i głos*. Wszystko działa w serwisie nagrywania, więc słychać to także przy wygaszonym ekranie
(muzyka z innych aplikacji jest na chwilę ściszana). Sygnały są generowane w kodzie, głos to wbudowany w Androida
syntezator mowy (offline, bez Google Play Services; wymaga zainstalowanego polskiego głosu).
- **Cel treningowy** na jeden przejazd: dystans (km), czas w ruchu (min) lub przewyższenie (m); komunikat po osiągnięciu
  celu (opcjonalnie także w połowie), pasek postępu na ekranie nagrywania
- **Najlepszy wynik**: najszybszy kilometr i najdłuższy przejazd w historii
- **Tempo lepsze / słabsze niż zazwyczaj** (średnia z 10 ostatnich przejazdów, czułość ±5/10/15%, sprawdzane co kilometr)
- **Zwiększony wysiłek**: tętno lub moc w wybranej strefie (Z3–Z5) przez 20 s (wymaga czujnika, progi LTHR/FTP w Analizie)
- Start/pauza/okrążenia/koniec, podsumowanie każdego km, utrata i powrót GPS, zjechanie z trasy do podążania
- Każdy rodzaj komunikatu można osobno włączyć lub wyłączyć, plus główne przełączniki *Sygnały* i *Głos* i trzy poziomy głośności
- Baza Room v3: przy pierwszym uruchomieniu 0.5 aplikacja jednorazowo uzupełnia „najszybszy km” starszych przejazdów

## Wygląd (0.3)
- Adaptacyjna ikona aplikacji (gradient + góry i szlak) z wersją monochromatyczną dla motywowanych ikon Androida 13+
- Własne ikony liniowe (bez zależności od material-icons): pasek zakładek, kafelki statystyk, przyciski, plakietki w ustawieniach
- Motywy: Auto / Jasny / Ciemny / AMOLED oraz 5 kolorów akcentu (Błękit, Turkus, Fiolet, Pomarańcz, Róż) – zmiana na żywo
- Tryb edge-to-edge, ikony paska statusu zgodne z motywem, przyciski z delikatnym gradientem

## Wygląd (0.4)
- Trzy style do wyboru w *Ustawienia → Wygląd*: **iOS**, **KDE Breeze**, **Windows 11 (Fluent)** – każdy z własną paletą,
  zaokrągleniami, przyciskami, paskiem zakładek, nagłówkami, dialogami i stylem ikon (grubość i zakończenia kresek)
- Tryby Auto / Jasny / Ciemny / AMOLED oraz kolor akcentu (pierwszy kolor = domyślny dla wybranego stylu)
- Własny zestaw ikon liniowych rysowanych w kodzie, adaptacyjna ikona aplikacji (z wersją monochromatyczną, Android 13+)

## Czujniki, Garmin i analityka (0.3)
- **Bluetooth LE**: pasy tętna (też Garmin HRM-Pro/Dual/600), mierniki mocy, czujniki prędkości/kadencji (profile 180D/1818/1816), poziom baterii
- Tętno, moc (uśredniana między punktami GPS) i kadencja zapisują się w każdym punkcie trasy (Room v2, migracja automatyczna)
- **Garmin Edge 530**: import plików `.FIT` (rekordy, okrążenia, pauzy); Edge sam jest odbiornikiem czujników, więc nie jest czujnikiem BLE
- **Analityka**: NP, IF, VI, TSS (z mocy) lub hrTSS (TRIMP z tętna), EF, rozprzężenie Pa:Hr, strefy tętna (LTHR) i mocy (FTP),
  krzywa mocy, PMC (CTL/ATL/TSB), podsumowania tygodniowe
- **Eksport pod TrainingPeaks**: TCX (tętno, kadencja, moc, okrążenia), ZIP z TCX, GPX z rozszerzeniami Garmina, CSV;
  bezpośrednie API TrainingPeaks wymaga zatwierdzenia partnera, więc używamy eksportu plików

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
