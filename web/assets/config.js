// Konfiguracja strony. Podczas wdrożenia (workflow „Pages”) ten plik jest generowany z sekretów repozytorium
// TRACKO_CLOUD_URL i TRACKO_CLOUD_ANON_KEY (+ opcjonalnie TRACKO_CARTO_KEY, TRACKO_TILE_URL…). Puste wartości = tryb
// demonstracyjny z danymi przykładowymi. Adres projektu Supabase i klucze kafelków są jawne z założenia (ogranicz je do domeny).
window.TRACKO_CONFIG = {
  url: "",
  key: "",
  releasesUrl: "https://github.com/z0z6/Tracko/releases/latest",
  repoUrl: "https://github.com/z0z6/Tracko"
  // Mapa: domyślnie kafelki OpenStreetMap (bez klucza). Opcjonalnie:
  //   cartoKey: "…"                      – darmowy klucz CARTO (carto.com/basemaps/apikey): jasna i ciemna mapa
  //   tileLight / tileDark / tileAttr    – własny dostawca (adres z {z}/{x}/{y} i kluczem)
};
