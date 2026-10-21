#!/usr/bin/env bash
# Arma «Avisos de prueba» (com.slowtown.prueba.avisos) sin Gradle: javac del
# JDK del sistema, d8, aapt2, zipalign y apksigner del SDK, firmada con la
# clave de debug de la máquina. La app imita a WhatsApp (chats en un solo
# grupo con resumen, y una llamada por internet) para probar en el emulador
# las oleadas y la guardia de llamadas. Cómo se maneja: Publicar.java.
#
#   scripts/prueba-avisos/armar.sh SALIDA_DIR    # deja SALIDA_DIR/avisos.apk
#
# Callado si anda; si falta una herramienta o falla un paso, lo dice y sale con 1.
set -euo pipefail

if (( $# != 1 )); then
  echo "uso: $0 SALIDA_DIR" >&2
  exit 1
fi
SALIDA=$1
AQUI=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
SDK="${ANDROID_HOME:-$HOME/Android/Sdk}"
# De Java 21 sólo está el runtime: javac es el del JDK 26 (CLAUDE.md).
JDK="${JAVA_HOME_AVISOS:-/usr/lib/jvm/java-26-openjdk}"
LLAVE="$HOME/.android/debug.keystore"   # clave «android», alias androiddebugkey

falta() {
  echo "armar.sh: falta $*" >&2
  exit 1
}

# Las build-tools 36.0.0 o, si no, las 35.0.0; el android.jar de la 36. Sólo
# se usa API de Android 12 o anterior: el emulador y el S10e son API 31.
bt=''
for v in 36.0.0 35.0.0; do
  if [[ -x $SDK/build-tools/$v/d8 && -x $SDK/build-tools/$v/aapt2 ]]; then
    bt=$SDK/build-tools/$v
    break
  fi
done
jar=$SDK/platforms/android-36/android.jar
[ -n "$bt" ] || falta "d8 y aapt2 (build-tools 36.0.0 o 35.0.0 en $SDK)"
[ -x "$bt/zipalign" ] || falta "$bt/zipalign"
[ -x "$bt/apksigner" ] || falta "$bt/apksigner"
[ -f "$jar" ] || falta "$jar"
[ -x "$JDK/bin/javac" ] || falta "javac en $JDK"
[ -f "$LLAVE" ] || falta "la clave de debug ($LLAVE)"
command -v zip >/dev/null || falta "zip"

mkdir -p "$SALIDA"
SALIDA=$(cd "$SALIDA" && pwd)
tmp=$(mktemp -d "$SALIDA/.avisos.XXXXXX")
trap 'rm -rf "$tmp"' EXIT
log=$tmp/armado.log

paso() {   # paso NOMBRE COMANDO...: lo corre callado; si falla, muestra lo que dijo
  local nombre=$1
  shift
  if ! "$@" >>"$log" 2>&1; then
    echo "armar.sh: falló $nombre:" >&2
    tail -n 30 "$log" >&2
    exit 1
  fi
}

mapfile -t fuentes < <(find "$AQUI/src" -name '*.java' | sort)
mkdir -p "$tmp/clases"
paso javac "$JDK/bin/javac" --release 11 -encoding UTF-8 -nowarn -cp "$jar" -d "$tmp/clases" "${fuentes[@]}"
mapfile -t clases < <(find "$tmp/clases" -name '*.class' | sort)
paso d8 env JAVA_HOME="$JDK" PATH="$JDK/bin:$PATH" "$bt/d8" --release --min-api 28 --lib "$jar" --output "$tmp" "${clases[@]}"
paso aapt2 "$bt/aapt2" link -o "$tmp/sin-codigo.apk" -I "$jar" --manifest "$AQUI/AndroidManifest.xml" \
  --version-code 1 --version-name 1
cp "$tmp/sin-codigo.apk" "$tmp/con-codigo.apk"
paso zip zip -q -j "$tmp/con-codigo.apk" "$tmp/classes.dex"
paso zipalign "$bt/zipalign" -f 4 "$tmp/con-codigo.apk" "$tmp/alineado.apk"
# Sin firma v4: así adb instala de la manera común.
paso apksigner env JAVA_HOME="$JDK" PATH="$JDK/bin:$PATH" "$bt/apksigner" sign --ks "$LLAVE" \
  --ks-pass pass:android --ks-key-alias androiddebugkey --key-pass pass:android \
  --v4-signing-enabled false --out "$SALIDA/avisos.apk" "$tmp/alineado.apk"
