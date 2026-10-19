#!/usr/bin/env bash
# Instala Slowtown y la deja como Device Owner del dispositivo conectado.
#
# Device Owner es lo que de verdad cierra el teléfono: lock task, Inicio fijo,
# Ajustes detrás de la contraseña. Android sólo lo acepta si en ese momento el
# dispositivo no tiene NINGUNA cuenta (Google, Samsung, WhatsApp...) ni
# usuarios extra (Carpeta segura, Dual Messenger, invitados). Este script lo
# verifica antes de intentar, exige una build de prueba (para que
# scripts/liberar.sh siempre pueda deshacerlo) y en un teléfono real pide
# confirmación.
#
#   ./scripts/provisionar.sh               # el APK de debug ya compilado
#   ./scripts/provisionar.sh otra.apk
set -euo pipefail

PAQUETE=com.slowtown.launcher
ADMIN="$PAQUETE/.admin.SlowtownAdminReceiver"
APK="${1:-app/build/outputs/apk/debug/app-debug.apk}"

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

if [ "$emulador" != 1 ]; then
  echo
  echo "⚠️  Esto es un teléfono real. Device Owner no se desinstala: sólo sale con"
  echo "   scripts/liberar.sh (si el APK es de prueba), desde la propia app, o con"
  echo "   un factory reset."
  read -r -p "   Escribí SI para seguir: " ok
  [ "$ok" = SI ] || { echo "Cancelado."; exit 1; }
fi

echo
echo "── instalando ────────────────────────────────────────"
adb install -r -t "$APK"

# Sin testOnly, liberar.sh no funciona y la única salida es la app o un reset.
paquete=$(adb shell dumpsys package "$PAQUETE" | tr -d '\r')
if ! grep -q TEST_ONLY <<<"$paquete"; then
  echo "🚨 El APK instalado no es una build de prueba (testOnly). No lo dejo como" >&2
  echo "   Device Owner: si algo sale mal, no habría salida por cable." >&2
  exit 1
fi
echo "  ✅ build de prueba: scripts/liberar.sh la puede sacar"

echo
echo "── Device Owner ──────────────────────────────────────"
adb shell dpm set-device-owner "$ADMIN"

echo
echo "Listo. Ahora en el dispositivo: apretá Inicio, elegí Slowtown, mantené"
echo "apretada la hora, elegí la contraseña y las apps, y «Activar Slowtown»."
