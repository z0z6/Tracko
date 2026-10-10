// Źródło kafelków mapy – wybór zależny od konfiguracji (assets/config.js, wstrzykiwanej z sekretów podczas wdrożenia).
//
// 1) domyślnie: kafelki OpenStreetMap (bez klucza, do umiarkowanego użytku) + ciemny motyw przez filtr CSS,
// 2) cartoKey: darmowy klucz CARTO (https://carto.com/basemaps/apikey) → ładne mapy jasna/ciemna bez znaku wodnego,
// 3) tileLight / tileDark / tileAttr: dowolny własny dostawca (MapTiler, Stadia, Thunderforest…, klucz w adresie URL).
//
// Od końca sierpnia 2026 CARTO bez klucza stempluje każdy kafelek napisem „API KEY REQUIRED”.

export const OSM_URL = 'https://tile.openstreetmap.org/{z}/{x}/{y}.png';
export const OSM_ATTR = '© <a href="https://www.openstreetmap.org/copyright" rel="noopener">OpenStreetMap</a>';
export const CARTO_ATTR = `${OSM_ATTR} © <a href="https://carto.com/attributions" rel="noopener">CARTO</a>`;

/**
 * @returns {{light:string, dark:string, attr:string, filterDark:boolean, provider:string}}
 *  filterDark = true → w ciemnym motywie przyciemniamy kafelki filtrem CSS (gdy dostawca nie ma osobnego stylu ciemnego)
 */
export function tileConfig(cfg = {}) {
  const clean = (s) => (typeof s === 'string' ? s.trim() : '');
  const light = clean(cfg.tileLight), dark = clean(cfg.tileDark), attr = clean(cfg.tileAttr), key = clean(cfg.cartoKey);
  if (light) {
    return { light, dark: dark || light, attr: attr || OSM_ATTR, filterDark: !dark, provider: 'własny' };
  }
  if (key) {
    const k = encodeURIComponent(key);
    return {
      light: `https://{s}.basemaps.cartocdn.com/light_all/{z}/{x}/{y}{r}.png?key=${k}`,
      dark: `https://{s}.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}{r}.png?key=${k}`,
      attr: CARTO_ATTR, filterDark: false, provider: 'CARTO',
    };
  }
  return { light: OSM_URL, dark: OSM_URL, attr: OSM_ATTR, filterDark: true, provider: 'OpenStreetMap' };
}
