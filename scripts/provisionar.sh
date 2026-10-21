#!/usr/bin/env bash
# Instala Slowtown y la deja como Device Owner del dispositivo conectado.
#
# Device Owner es lo que de verdad cierra el teléfono: lock task, Inicio fijo,
# Ajustes detrás de la contraseña. Android sólo lo acepta si en ese momento el
# dispositivo no tiene NINGUNA cuenta (Google, Samsung, WhatsApp...) ni
# usuarios extra (Carpeta segura, Dual Messenger, invitados). Este script lo
# verifica antes de intentar, exige una build de prueba (para que
# scripts/liberar.sh siempre pueda deshacerlo) y en un teléfono real exige la
# clave estable de scripts/firma.sh y pide confirmación.
#
#   ./scripts/provisionar.sh               # el APK de debug ya compilado
#   ./scripts/provisionar.sh otra.apk
set -euo pipefail

RAIZ="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PAQUETE=com.slowtown.launcher
ADMIN="$PAQUETE/.admin.SlowtownAdminReceiver"
APK="${1:-app/build/outputs/apk/debug/app-debug.apk}"

# apksigner y aapt2, del SDK: para mirar el APK antes de instalarlo.
sdk="${ANDROID_HOME:-$(sed -n 's/^sdk\.dir=//p' "$RAIZ/local.properties" 2>/dev/null)}"
herramienta() {
  ls -1 "${sdk:-$HOME/Android/Sdk}"/build-tools/*/"$1" 2>/dev/null | sort -V | tail -n 1
}

# Lo que Slowtown necesita y sólo se da desde Ajustes, que con Slowtown activo
# queda bloqueado: poner widgets en Inicio sin el diálogo de Android, y leer los
# datos de uso para mostrar el tiempo de pantalla (lo mismo que Bienestar
# digital, que no comparte sus números).
permitir_widgets() {
  adb shell appwidget grantbind --package "$PAQUETE" --user 0 >/dev/null
  adb shell appops set "$PAQUETE" GET_USAGE_STATS allow >/dev/null
  # La hora de dormir empieza y termina a horario, no «más o menos».
  adb shell appops set "$PAQUETE" SCHEDULE_EXACT_ALARM allow >/dev/null
  # Las notificaciones por oleadas: leer y posponer las de las apps elegidas.
  # Si falla (un APK sin el lector), Slowtown anda igual: sólo no hay oleadas.
  adb shell cmd notification allow_listener "$PAQUETE/.notif.WavesListener" >/dev/null 2>&1 ||
    echo "  ⚠️  no pude habilitar el lector de notificaciones (las oleadas no van a andar)"
}

if [ "$(adb get-state 2>/dev/null)" != device ]; then
  echo "🚨 No hay ningún dispositivo conectado y autorizado." >&2
  echo "   Conectalo por USB y aceptá el diálogo (marcá «Permitir siempre»)." >&2
  exit 1
fi
if [ ! -f "$APK" ]; then
  echo "🚨 No encuentro $APK. Compilalo antes: ./gradlew assembleDebug" >&2
  exit 1
fi

modelo=$(adb shell getprop ro.product.model | tr -d '\r')
android=$(adb shell getprop ro.build.version.release | tr -d '\r')
emulador=$(adb shell getprop ro.kernel.qemu | tr -d '\r')
echo "── dispositivo ───────────────────────────────────────"
echo "modelo:  $modelo"
echo "android: $android"
[ "$emulador" = 1 ] && echo "es un emulador"

# La firma no cambia (CLAUDE.md, regla 7): a un teléfono real sólo llega el APK
# firmado con la clave estable. Con la clave de debug de la máquina, el día que
# se reinstale la compu no habría cómo actualizarlo sin sacar las cuentas.
# Antes de tocar nada del teléfono.
if [ "$emulador" != 1 ]; then
  echo
  echo "── firma ─────────────────────────────────────────────"
  esperada=$(tr -d '[:space:]' < "$RAIZ/scripts/firma.sha256" 2>/dev/null || true)
  if [ -z "$esperada" ]; then
    echo "🚨 No hay clave estable (falta scripts/firma.sha256): corré ./scripts/firma.sh" >&2
    echo "   y compilá de nuevo antes de tocar un teléfono real." >&2
    exit 1
  fi
  apksigner=$(herramienta apksigner)
  if [ -z "$apksigner" ]; then
    echo "🚨 No encuentro apksigner en el SDK." >&2
    exit 1
  fi
  firmas=$("$apksigner" verify --print-certs "$APK" 2>/dev/null || true)
  huella=$(sed -n 's/^Signer #1 certificate SHA-256 digest: //p' <<<"$firmas")
  if [ "$huella" != "$esperada" ]; then
    echo "🚨 $APK no está firmado con la clave estable." >&2
    echo "   Tiene ${huella:-una firma que no pude leer}, y tendría que ser $esperada." >&2
    echo "   ¿Falta ~/slowtown-firma? Compilá de nuevo: ./gradlew assembleDebug" >&2
    exit 1
  fi
  echo "  ✅ firmado con la clave estable"
fi

# Sin testOnly no queda el último recurso de liberar.sh (--forzar), para cuando
# Slowtown no contesta. Se mira en el APK, antes de instalar nada.
aapt2=$(herramienta aapt2)
if [ -z "$aapt2" ]; then
  echo "🚨 No encuentro aapt2 en el SDK." >&2
  exit 1
fi
manifiesto=$("$aapt2" dump xmltree --file AndroidManifest.xml "$APK" 2>/dev/null || true)
if ! grep -q 'testOnly(0x01010272)=true' <<<"$manifiesto"; then
  echo "🚨 $APK no es una build de prueba (testOnly). No lo instalo: sin eso, liberar.sh" >&2
  echo "   no tendría su último recurso. Compilá con ./gradlew assembleDebug." >&2
  exit 1
fi

echo
echo "── salida por cable ──────────────────────────────────"
# Android revoca la autorización de una computadora que no se conecta en 7
# días, y con Slowtown activo el diálogo para volver a autorizarla no
# puede aparecer: liberar.sh dejaría de llegar justo cuando hace falta.
# 0 es que no vence nunca.
adb shell settings put global adb_allowed_connection_time 0
vence=$(adb shell settings get global adb_allowed_connection_time | tr -d '\r')
if [ "$vence" != 0 ]; then
  echo "🚨 No pude dejar sin vencimiento la autorización de adb (quedó en $vence)." >&2
  exit 1
fi
echo "  ✅ la autorización de esta computadora no vence"

# Primero a una variable y después grep: con pipefail, un `grep -q` en el
# pipeline corta la lectura, adb muere por SIGPIPE y el if lee un error.
politicas=$(adb shell dumpsys device_policy | tr -d '\r')
if grep -q "package=$PAQUETE" <<<"$politicas"; then
  echo
  echo "Slowtown ya es Device Owner: sólo la reinstalo."
  adb install -r -t "$APK"
  permitir_widgets
  exit 0
fi

echo
echo "── requisitos ────────────────────────────────────────"
usuarios=$(adb shell pm list users | tr -d '\r' | grep -c 'UserInfo{' || true)
if [ "$usuarios" -gt 1 ]; then
  echo "🚨 Hay $usuarios usuarios y Device Owner necesita uno solo:" >&2
  adb shell pm list users >&2
  echo "   Sacá Carpeta segura, Dual Messenger o invitados y volvé a probar." >&2
  exit 1
fi
echo "  ✅ un solo usuario"

# Sólo los tipos de cuenta, sin los nombres.
cuentas=$(adb shell dumpsys account | tr -d '\r' | grep -oE 'Account \{[^}]*type=[^},]+' \
  | sed -E 's/.*type=//' | sort | uniq -c || true)
if [ -n "$cuentas" ]; then
  echo "🚨 Hay cuentas en el dispositivo y Device Owner necesita cero:" >&2
  echo "$cuentas" >&2
  echo "   Sacalas desde Ajustes → Cuentas, provisioná, y después volvé a agregarlas." >&2
  exit 1
fi
echo "  ✅ ninguna cuenta"

echo
echo "── instalando ────────────────────────────────────────"
# Instalarla todavía no cierra nada: es una app más hasta set-device-owner.
adb install -r -t "$APK"
permitir_widgets

paquete=$(adb shell dumpsys package "$PAQUETE" | tr -d '\r')
if ! grep -q TEST_ONLY <<<"$paquete"; then
  echo "🚨 El APK instalado no es una build de prueba (testOnly). No lo dejo como" >&2
  echo "   Device Owner: liberar.sh no tendría su último recurso." >&2
  exit 1
fi
echo "  ✅ build de prueba: scripts/liberar.sh --forzar la puede sacar"

# Lo que va a pausar el nivel 2 apenas sea Device Owner, preguntado a la
# propia Slowtown (no hace falta que sea Device Owner para contestar).
echo
echo "── nivel 2 ───────────────────────────────────────────"
respuesta=$(timeout 60 adb shell content call --uri "content://$PAQUETE.cable" --method mostrar 2>&1 | tr -d '\r' || true)
pausaria=$(sed -n 's/.*pausaria=\([^,}]*\).*/\1/p' <<<"$respuesta")
echo "  va a pausar: ${pausaria:-nada}"

if [ "$emulador" != 1 ]; then
  echo
  echo "⚠️  Esto es un teléfono real. Device Owner no se desinstala: sólo sale con"
  echo "   scripts/liberar.sh, por cable, o con un factory reset. Mirá la lista de"
  echo "   arriba: si hay algo que no tendría que pausarse, cancelá."
  read -r -p "   Escribí SI para seguir: " ok
  [ "$ok" = SI ] || { echo "Cancelado. Slowtown quedó instalada como una app común."; exit 1; }
fi

echo
echo "── Device Owner ──────────────────────────────────────"
adb shell dpm set-device-owner "$ADMIN"

echo
echo "Listo. Ahora en el dispositivo: apretá Inicio, elegí Slowtown, mantené"
echo "apretada la hora, elegí la contraseña y las apps, y «Activar Slowtown»."
