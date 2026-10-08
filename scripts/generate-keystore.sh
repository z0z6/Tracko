#!/usr/bin/env bash
# Generuje klucz podpisu wydań Tracko i wypisuje wartości sekretów dla GitHuba.
# Uruchom RAZ, plik .jks zachowaj w bezpiecznym miejscu (kopia zapasowa!) i NIGDY nie dodawaj go do repozytorium.
# Bez tego klucza nie wydasz aktualizacji, które zainstalują się nad poprzednią wersją.
set -euo pipefail

OUT="${1:-tracko-release.jks}"
ALIAS="${2:-tracko}"

if [ -e "$OUT" ]; then echo "Plik $OUT już istnieje – przerywam, żeby go nie nadpisać."; exit 1; fi

read -r -s -p "Hasło do magazynu kluczy (min. 8 znaków): " PASS; echo
read -r -s -p "Powtórz hasło: " PASS2; echo
[ "$PASS" = "$PASS2" ] || { echo "Hasła się różnią."; exit 1; }

keytool -genkeypair -v -keystore "$OUT" -alias "$ALIAS" -keyalg RSA -keysize 4096 -validity 10000 \
  -storepass "$PASS" -keypass "$PASS" -dname "CN=Tracko, O=Tracko, C=PL"

echo
echo "================ Sekrety do dodania w GitHub: Settings → Secrets and variables → Actions ================"
echo "KEYSTORE_PASSWORD = (hasło, które właśnie podałeś)"
echo "KEY_PASSWORD      = (to samo hasło)"
echo "KEY_ALIAS         = $ALIAS"
echo "KEYSTORE_BASE64   = (poniższa długa linia)"
if base64 --help 2>&1 | grep -q -- '-w'; then base64 -w0 "$OUT"; else base64 "$OUT" | tr -d '\n'; fi
echo
echo "========================================================================================================"
echo "Zrób kopię $OUT (np. menedżer haseł / dysk offline). Utrata klucza = brak możliwości aktualizacji."
