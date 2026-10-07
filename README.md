# TrailTrack

Darmowa, open-source'owa aplikacja na Androida do nagrywania przejazdów rowerowych
(odpowiednik podstawowych funkcji Stravy) – z naciskiem na jazdę w różnym terenie.

## Funkcje
- Nagrywanie trasy GPS w tle (foreground service, bez Google Play Services, bez kont i bez reklam)
- Mapa OpenStreetMap / OpenTopoMap (poziomice, ścieżki) – bez kluczy API
- **Przełączanie nawierzchni w trakcie jazdy** (asfalt, bruk, szuter, droga leśna, singletrack, błoto, piasek, śnieg)
- Trasa na mapie kolorowana wg nawierzchni
- Statystyki: dystans, czas w ruchu, średnia/maks. prędkość, podjazd, **dystans i średnia prędkość na każdej nawierzchni**
- Eksport do GPX (z rozszerzeniem `tt:surface`) – można wgrać do Stravy, Komoot itp.
- Autozapis co ~30 s – trasa nie ginie, jeśli system ubije aplikację

## Budowanie na GitHubie
1. Utwórz repozytorium na GitHubie i wypchnij do niego ten projekt (gałąź `main`).
2. Zakładka **Actions** → workflow **Build APK** uruchomi się sam po pushu
   (albo ręcznie: *Run workflow*).
3. Po zakończeniu wejdź w przebieg i pobierz artefakt **TrailTrack-debug-apk**.
4. Rozpakuj ZIP i zainstaluj `app-debug.apk` na telefonie (zezwól na instalację z nieznanych źródeł).

Wydanie z APK w zakładce *Releases*: `git tag v0.1.0 && git push origin v0.1.0`.

## Lokalnie
Android Studio → *Open* → wskaż folder projektu. Albo `gradle assembleDebug` (Gradle 8.7, JDK 17).

## Pomysły na rozwój
- Pauza / auto-pauza, wykres wysokości, okrążenia
- Mapy offline (osmdroid + pobieranie kafelków), import GPX z trasami do podążania
- Room zamiast plików JSON, wykresy prędkości wg nawierzchni
- Podpisany build release (keystore w GitHub Secrets)
