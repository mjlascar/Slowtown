#!/usr/bin/env bash
#
# Genera la clave estable con la que se firma Slowtown, una sola vez.
#
# Por qué existe: Android identifica una app por su paquete Y su firma, y un
# Device Owner no se desinstala. Si la firma cambia, la versión nueva no se
# instala encima, y la única salida es liberar, desinstalar y provisionar de
# nuevo, que exige sacar todas las cuentas del teléfono. Con la clave de debug
# de la máquina pasa lo mismo el día que se reinstala la compu.
#
# A diferencia de Waltra, la clave no va a los secretos de GitHub: Slowtown
# llega al teléfono sólo por cable, desde esta compu. CI sigue compilando con
# una clave descartable, y lo que sale de CI no se puede instalar encima.
#
#   ./scripts/firma.sh              # deja la clave en ~/slowtown-firma/
#   ./scripts/firma.sh /otra/ruta   # o donde quieras (después: SLOWTOWN_FIRMA=/otra/ruta)
#
set -euo pipefail

DESTINO="${1:-$HOME/slowtown-firma}"
ARCHIVO="$DESTINO/slowtown.keystore"
PROPIEDADES="$DESTINO/firma.properties"
ALIAS="slowtown"

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
HUELLA_REPO="$REPO/scripts/firma.sha256"

# El repositorio es público: la clave no puede terminar adentro ni por error.
ABS_DESTINO="$(mkdir -p "$DESTINO" && cd "$DESTINO" && pwd)"
case "$ABS_DESTINO/" in
  "$REPO"/*)
    echo "🚨 $ABS_DESTINO está adentro del repositorio, que es público." >&2
    echo "   Elegí una carpeta de afuera, por ejemplo ~/slowtown-firma." >&2
    exit 1
    ;;
esac

# Pisar un keystore existente es perder la clave: si ya firmó el Slowtown del
# teléfono, ninguna versión futura se instala encima.
if [ -e "$ARCHIVO" ] || [ -e "$PROPIEDADES" ]; then
  echo "🚨 Ya hay una clave en $ABS_DESTINO. No la piso." >&2
  echo "   Si es la que firmó el Slowtown del teléfono, perderla obliga a" >&2
  echo "   liberarlo, desinstalarlo y provisionarlo de nuevo, sin cuentas." >&2
  echo "   Si de verdad querés otra, movela o borrala a mano primero." >&2
  exit 1
fi

if ! command -v keytool >/dev/null 2>&1; then
  echo "🚨 Falta keytool, que viene con el JDK." >&2
  exit 1
fi

# Al azar: no hay que recordarla, vive en firma.properties.
if command -v openssl >/dev/null 2>&1; then
  PASS="$(openssl rand -base64 33 | tr -d '\n/+=' | cut -c1-32)"
else
  PASS="$(head -c 48 /dev/urandom | base64 | tr -d '\n/+=' | cut -c1-32)"
fi

umask 077
chmod 700 "$ABS_DESTINO"
# PKCS12, el formato de keytool: una sola contraseña para el almacén y la clave.
keytool -genkeypair \
  -keystore "$ARCHIVO" \
  -storetype PKCS12 \
  -alias "$ALIAS" \
  -keyalg RSA -keysize 4096 \
  -validity 18250 \
  -storepass "$PASS" -keypass "$PASS" \
  -dname "CN=Slowtown, OU=Personal, O=Slowtown, L=Buenos Aires, C=AR" \
  >/dev/null 2>&1

{
  echo "# La clave estable de Slowtown (scripts/firma.sh). Nunca adentro del repo."
  echo "storeFile=$ABS_DESTINO/slowtown.keystore"
  echo "storePassword=$PASS"
  echo "keyAlias=$ALIAS"
  echo "keyPassword=$PASS"
} > "$PROPIEDADES"
chmod 600 "$ARCHIVO" "$PROPIEDADES"

# La huella del certificado es pública: va al repo, y provisionar.sh se niega
# a poner en un teléfono real un APK firmado con otra cosa.
HUELLA=$(keytool -list -v -keystore "$ARCHIVO" -alias "$ALIAS" -storepass "$PASS" 2>/dev/null \
  | sed -n 's/^[[:space:]]*SHA256: //p' | tr -d ':' | tr 'A-F' 'a-f')
if [ -z "$HUELLA" ]; then
  echo "🚨 La clave se creó pero no pude leer su huella." >&2
  exit 1
fi
echo "$HUELLA" > "$HUELLA_REPO"

cat <<FIN

✅ Clave creada en $ABS_DESTINO
   Su huella quedó en scripts/firma.sha256 (es pública, va al repo).

Desde ahora ./gradlew assembleDebug firma con ella.

Para tener a salvo, fuera de la compu (un pendrive que quede en tu casa):

  - $ABS_DESTINO/   la clave y su contraseña
  - ~/.android/adbkey y ~/.android/adbkey.pub   la llave de adb de esta compu

Si se pierde la clave, actualizar Slowtown obliga a liberarlo, desinstalarlo
y provisionarlo de nuevo, sin cuentas en el teléfono. Si se pierde la llave
de adb, con Slowtown activo ninguna compu nueva entra por cable (habría que
pasar antes por el modo seguro).

FIN
