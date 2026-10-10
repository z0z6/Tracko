# Tracko 0.8

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

## Strona www (folder `web/`)
Statyczna strona na GitHub Pages: animacja startowa jak w aplikacji, **wyniki z aplikacji**, ranking i **wyścig duchów** na mapie,
aktywności (z rozkładem nawierzchni), **planer tras** (routing po drogach, profil wysokości, zapis do konta i eksport GPX) oraz
jasny/ciemny motyw. Działa na tym samym zapleczu Supabase; bez konfiguracji pokazuje tryb demonstracyjny (`?demo=1`).
Wdrożenie: *Settings → Pages → Source: GitHub Actions*, sekrety jak w buildzie aplikacji – szczegóły w [`web/README.md`](web/README.md).

## Tracko 0.10 – rywalizacja online
Moduł wymiany danych z internetem: rekordy, duchy, przejechane aktywności i trasy trafiają na serwer, a ze strony www
(np. na GitHub Pages) – z powrotem do aplikacji. **GitHub Pages jest hostingiem statycznym i nie przyjmuje danych**, więc
zapleczem jest bezpłatny projekt Supabase (baza + REST + logowanie), z którym rozmawiają i aplikacja, i strona.
- Ustawienia → **Rywalizacja online**: zgoda, konto (anonimowe lub e-mail), zakres udostępniania, **przycinanie początku
  i końca śladu** (ochrona miejsca zamieszkania), tylko Wi-Fi, ręczna synchronizacja, usunięcie konta i danych.
- **Odcinki i wyniki** wysyłają się same po utworzeniu odcinka i po każdym przejeździe; ranking odcinka (duchy innych)
  pobiera się przed ściganiem. **Przeglądaj odcinki online** pozwala pobrać cudzy odcinek razem z rankingiem.
- **Aktywności i trasy** wysyłają się opcjonalnie (domyślnie prywatne). **Trasy online** pobierają trasy zaplanowane na
  stronie www albo opublikowane przez innych.
- Konfiguracja i kontrakt danych: [`docs/CLOUD_API.md`](docs/CLOUD_API.md), schemat bazy: `backend/supabase/schema.sql`
  (z politykami RLS, limitami i kontrolą wiarygodności wyników; sprawdzony testami na PostgreSQL 16).

## Tracko 0.9
- **Nowe aktywności:** Spacer (tempo min/km), Kajakarstwo (km/h, bez nawierzchni – woda) i Rolki (km/h, nawierzchnie z mapy),
  wszystkie z GPS, mapą, odcinkami z duchem, komunikatami głosowymi i eksportem. Import FIT rozpoznaje też chód/turystykę,
  wiosłowanie/kajak/SUP/rafting i jazdę na rolkach.
- **Dostosowanie ekranu wyboru:** przycisk *Dostosuj* → dotknij kafelków, żeby je pokazać lub ukryć (zostaje co najmniej
  jeden); wybór jest zapamiętywany.

## Tracko 0.8
- **Nazwa aplikacji: Tracko.** Wewnętrzny identyfikator pakietu (`pl.trailtrack`), nazwa bazy i plik ustawień zostały
  bez zmian, żeby aktualizacja zachowała dane i ustawienia.
- **Nawierzchnie wykrywają się automatycznie** po zapisie aktywności: każdy punkt trasy jest dopasowywany do najbliższej
  drogi lub ścieżki z OpenStreetMap (tagi `surface`, `tracktype`, `highway`; Overpass API, wymaga internetu).
  Narty biegowe = śnieg (bez internetu). W trakcie nagrywania nie ma wyboru nawierzchni, ślad jest jednokolorowy.
  Kolorowanie trasy wg nawierzchni (z legendą) pojawia się dopiero w podsumowaniu zapisanej aktywności; można ponowić
  wykrywanie albo poprawić nawierzchnię ręcznie dla zakresu trasy. Starsze aktywności zachowują dotychczasowe dane.
- **Duch** to kwadratowy kafelek przy prawej krawędzi: na mapie podczas jazdy (luka do ducha na żywo) i obok karty
  gotowości przed startem.

## Nowy przepływ i warstwa graficzna (0.7)
- **Animacja startowa** (~2 s): ślad rysuje się gradientową linią, za nim podąża przerywany „duch”, potem wjeżdża nazwa.
- **Wybór aktywności** to osobny ekran między startem a nagrywaniem: siatka kafelków (ikona + nazwa na dole) z kaskadowym
  wjazdem, „oddychającymi” ikonami, przechyłem w stronę dotyku i sprężystym dociśnięciem; po wyborze kafelek się powiększa.
- **Mapa ładuje się dopiero po Start** – przed startem ekran nagrywania pokazuje gotowość i ustawienia aktywności.
- **GPS jest wymuszany po wybraniu aktywności z GPS** (rower, bieganie, narty) oraz w trakcie ich nagrywania.
- **Duch** to zminimalizowany, ale większy kafelek tuż pod mapą (luka do ducha na żywo, pasek postępu, szybkie ✕).
- **Nawierzchnie**: w trakcie jazdy tylko mały znacznik bieżącej nawierzchni na mapie (dotknięcie otwiera wybór);
  pełne kafelki i poprawianie nawierzchni dla całej trasy lub zakresu są w **podsumowaniu** po zapisie aktywności.
- Po **Stop** aplikacja zapisuje aktywność i otwiera jej podsumowanie.

## Odcinki i ściganie z duchem (0.6)
Ustawienia → *Odcinki i duchy* (albo przycisk w oknie z mapą). Działa dla aktywności z GPS (rower, bieganie, narty).
- **Tworzenie odcinka**: w szczegółach aktywności lub trasy → *Utwórz odcinek do ścigania z duchem*; suwakami wybierasz
  początek i koniec (min. 200 m). Z aktywności od razu powstaje pierwszy wynik.
- **Wyniki zapisują się same**: każdy przejazd przez znany odcinek tej samej aktywności (start ≤ 25 m od początku,
  meta 20 m przed końcem, zjazd > 40 m od linii przez ~8 s przerywa próbę). Pauzy nie wydłużają czasu.
- **Ściganie**: wybierasz odcinek i ducha (najlepszy lub dowolny z rankingu). Na mapie widać odcinek (pomarańczowy)
  i ducha 👻, na ekranie – przewagę lub stratę w sekundach; głosem co 25%, przy zmianie prowadzenia i na mecie
  („Szybciej od ducha o 12 sekund”, „Nowy rekord odcinka!”).
- **Udostępnianie**: odcinek z wynikami to plik `.ttseg` (JSON) – wysyłasz go przyciskiem *Udostępnij*, odbiorca importuje
  go na liście odcinków i może się ścigać z Twoim wynikiem; jego wyniki po wysłaniu pliku z powrotem dołączają do rankingu.
  Imię w rankingu ustawiasz w Ustawieniach. Nie ma serwera – wymiana plików odbywa się ręcznie (komunikator, e-mail, chmura).
- Baza Room v5 (nowe tabele `segments`, `segment_efforts`)

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

## Wydania (GitHub Releases + Obtainium)
Aplikacja jest rozdawana jako **podpisany APK z GitHub Releases**; użytkownicy mogą ją aktualizować automatycznie przez
[Obtainium](https://github.com/ImranR98/Obtainium). Repozytorium musi być **publiczne**, żeby inni mogli pobierać wydania.

**Jednorazowa konfiguracja (właściciel repozytorium):**
1. Uruchom `scripts/generate-keystore.sh` (wymaga JDK z `keytool`). Skrypt tworzy plik `tracko-release.jks` i wypisuje
   cztery wartości sekretów. **Zrób kopię pliku `.jks`** – bez niego nie wydasz aktualizacji, a plik nie może trafić do repo.
2. W repozytorium: *Settings → Secrets and variables → Actions → New repository secret*, dodaj:
   `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.

**Nowe wydanie:**
```
git tag v0.8.1
git push origin v0.8.1
```
Workflow zbuduje i podpisze `Tracko-0.8.1.apk`, sprawdzi podpis (`apksigner`), doliczy sumę SHA-256 i opublikuje wydanie
z notatkami. Wersja i `versionCode` biorą się z tagu (`vX.Y.Z` → kod `X*10000 + Y*100 + Z`), więc każdy kolejny tag musi
być wyższy od poprzedniego.

**Instalacja i aktualizacje u użytkownika:**
- ręcznie: pobierz `Tracko-X.Y.Z.apk` z [Releases](https://github.com/z0z6/Tracko/releases/latest) i zainstaluj, albo
- przez Obtainium: [dodaj Tracko](obtainium://add/https://github.com/z0z6/Tracko) (lub *Dodaj aplikację* → wklej
  `https://github.com/z0z6/Tracko`). Obtainium sprawdza nowe wydania i aktualizuje aplikację.

**Pierwsza instalacja podpisanej wersji:** dotychczasowe buildy debug były podpisane innym kluczem, więc Android nie pozwoli
zainstalować wydania „nad” nimi. Przed odinstalowaniem wyeksportuj dane (TCX/GPX/CSV, odcinki `.ttseg`). Wersja debug ma teraz
identyfikator `pl.trailtrack.debug`, więc może stać obok wydania.

**Testowe buildy** (każdy push/PR): *Actions → Build APK → artefakt* **Tracko-debug-apk**.
Lokalnie: Android Studio → *Open*, albo `gradle assembleDebug` (Gradle 8.7, JDK 17).

## Uwagi
- Kod nie był kompilowany w momencie tworzenia – jeśli build się wywali, wklej log z Actions.
- Wysokość z GPS jest zaszumiona (wygładzanie + próg 3 m), kalorie to tylko szacunek (MET × waga × czas).
