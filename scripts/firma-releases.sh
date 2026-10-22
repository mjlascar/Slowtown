#!/usr/bin/env bash
#
# Genera la clave de las releases públicas de Slowtown, una sola vez.
#
# Es otra clave, distinta de la del S10e (scripts/firma.sh): ésa no sale nunca
# de esta compu, y ésta vive en los secretos de GitHub, para que el workflow de
# releases compile, firme y publique solo. Todas las releases tienen que salir
# con la misma: Android no instala una versión nueva encima de una firmada con
# otra clave, y quien la tenga de Inicio tendría que desinstalarla.
#
#   ./scripts/firma-releases.sh              # deja la clave en ~/slowtown-firma-releases/
#   ./scripts/firma-releases.sh /otra/ruta   # o donde quieras
#
set -euo pipefail

DESTINO="${1:-$HOME/slowtown-firma-releases}"
ARCHIVO="$DESTINO/releases.keystore"
CONTRASENA="$DESTINO/contrasena.txt"
SECRETOS="$DESTINO/para-github.txt"
# Va también en app/build.gradle.kts y en el workflow: no es secreto.
ALIAS="slowtown-releases"

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
HUELLA_REPO="$REPO/scripts/firma-releases.sha256"

# El repositorio es público: la clave no puede terminar adentro ni por error.
ABS_DESTINO="$(mkdir -p "$DESTINO" && cd "$DESTINO" && pwd)"
case "$ABS_DESTINO/" in
  "$REPO"/*)
    echo "🚨 $ABS_DESTINO está adentro del repositorio, que es público." >&2
    echo "   Elegí una carpeta de afuera, por ejemplo ~/slowtown-firma-releases." >&2
    exit 1
    ;;
esac

# Pisarla es dejar sin actualizaciones a todos los que ya la instalaron.
if [ -e "$ARCHIVO" ] || [ -e "$CONTRASENA" ]; then
  echo "🚨 Ya hay una clave de releases en $ABS_DESTINO. No la piso." >&2
  echo "   Con otra, nadie que tenga Slowtown podría actualizarla encima." >&2
  exit 1
fi

if ! command -v keytool >/dev/null 2>&1; then
  echo "🚨 Falta keytool, que viene con el JDK." >&2
  exit 1
fi

# Al azar: no hay que recordarla, vive en contrasena.txt y en GitHub.
if command -v openssl >/dev/null 2>&1; then
  PASS="$(openssl rand -base64 33 | tr -d '\n/+=' | cut -c1-32)"
else
  PASS="$(head -c 48 /dev/urandom | base64 | tr -d '\n/+=' | cut -c1-32)"
fi

umask 077
chmod 700 "$ABS_DESTINO"
# PKCS12: una sola contraseña para el almacén y la clave.
keytool -genkeypair \
  -keystore "$ARCHIVO" \
  -storetype PKCS12 \
  -alias "$ALIAS" \
  -keyalg RSA -keysize 4096 \
  -validity 18250 \
  -storepass "$PASS" -keypass "$PASS" \
  -dname "CN=Slowtown, OU=Releases, O=Slowtown, L=Buenos Aires, C=AR" \
  >/dev/null 2>&1
echo "$PASS" > "$CONTRASENA"

# Lo que se pega en GitHub, tal cual: Settings → Secrets and variables →
# Actions → New repository secret, un secreto por bloque.
{
  echo "Secretos para GitHub (Settings → Secrets and variables → Actions → New repository secret)."
  echo "Uno por bloque: el nombre arriba, el valor (todo, en una sola línea) abajo."
  echo
  echo "SLOWTOWN_RELEASE_KEYSTORE_B64"
  base64 -w 0 "$ARCHIVO"
  echo
  echo
  echo "SLOWTOWN_RELEASE_PASSWORD"
  echo "$PASS"
} > "$SECRETOS"
chmod 600 "$ARCHIVO" "$CONTRASENA" "$SECRETOS"

# La huella del certificado es pública: va al repo y a las notas de cada
# release, para que quien baje el APK pueda comprobar de quién es.
HUELLA=$(keytool -list -v -keystore "$ARCHIVO" -alias "$ALIAS" -storepass "$PASS" 2>/dev/null \
  | sed -n 's/^[[:space:]]*SHA256: //p' | tr -d ':' | tr 'A-F' 'a-f')
if [ -z "$HUELLA" ]; then
  echo "🚨 La clave se creó pero no pude leer su huella." >&2
  exit 1
fi
echo "$HUELLA" > "$HUELLA_REPO"

cat <<FIN

✅ Clave de releases creada en $ABS_DESTINO
   Su huella quedó en scripts/firma-releases.sha256 (es pública, va al repo).

Falta cargarla en GitHub: los dos secretos están en
  $SECRETOS

Y tenerla a salvo fuera de la compu (el pendrive, junto a la otra):
  $ABS_DESTINO/

Si se pierde, las releases siguientes salen con otra firma y nadie las puede
instalar encima de la que tiene.

FIN
