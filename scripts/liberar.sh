#!/usr/bin/env bash
# La salida de emergencia por cable: abre el teléfono y saca a Slowtown de
# Device Owner.
#
# Primero le pide a la propia Slowtown que deshaga el nivel 2 (las apps que
# pausó y las políticas de Chrome) y se suelte: sólo ella puede, adb no
# (probado). Si Slowtown no contesta, no sigue: lo que dejó puesto quedaría
# sin nadie que lo saque. Se arregla instalando una versión que ande
# (./scripts/provisionar.sh la reinstala) y volviendo a correr esto.
#
# Último recurso, --forzar: la saca igual con `dpm remove-active-admin`, que
# sólo anda con una build de prueba (android:testOnly, lo que sale de
# assembleDebug). Después saca la pausa a las apps de sistema que Slowtown
# había pausado; las de Play quedan pausadas hasta reinstalarlas.
#
#   ./scripts/liberar.sh
#   ./scripts/liberar.sh --forzar
set -euo pipefail

PAQUETE=com.slowtown.launcher
ADMIN="$PAQUETE/.admin.SlowtownAdminReceiver"
FORZAR=0
[ "${1:-}" = --forzar ] && FORZAR=1

if [ "$(adb get-state 2>/dev/null)" != device ]; then
  echo "🚨 No hay ningún dispositivo conectado y autorizado (o hay más de uno:" >&2
  echo "   elegilo con ANDROID_SERIAL=...). Conectalo por USB y aceptá el" >&2
  echo "   diálogo, con «Permitir siempre» marcado." >&2
  exit 1
fi

# Primero a una variable y después grep: con pipefail, un `grep -q` en el
# pipeline corta la lectura, adb muere por SIGPIPE y el if lee un error.
es_device_owner() {
  local politicas
  politicas=$(adb shell dumpsys device_policy | tr -d '\r')
  grep -q "package=$PAQUETE" <<<"$politicas"
}

if ! es_device_owner; then
  echo "Slowtown no es Device Owner: no hay nada que liberar."
  exit 0
fi

echo "── soltando desde Slowtown ───────────────────────────"
# SLOWTOWN_SIN_SOLTAR=1 hace como si Slowtown no contestara: es para que la
# prueba del emulador pase por --forzar sin tener que romperla.
if [ "${SLOWTOWN_SIN_SOLTAR:-0}" = 1 ]; then
  respuesta="(no le pregunté: SLOWTOWN_SIN_SOLTAR=1)"
else
  respuesta=$(adb shell content call --uri "content://$PAQUETE.cable" --method soltar 2>&1 | tr -d '\r' || true)
fi
if ! es_device_owner; then
  vuelven=$(sed -n 's/.*vuelven=\([^,}]*\).*/\1/p' <<<"$respuesta")
  echo "✅ Slowtown ya no es Device Owner y el teléfono quedó abierto."
  if [ -n "$vuelven" ]; then echo "   Se pueden abrir otra vez: $vuelven"; fi
  echo "   Queda en pausa, como un launcher común. Para usar otro, elegilo en"
  echo "   Ajustes → Aplicaciones → Aplicaciones predeterminadas, o desinstalala:"
  echo "   adb uninstall $PAQUETE"
  exit 0
fi

echo "🚨 Slowtown no se soltó. Contestó: ${respuesta:-nada}" >&2
if (( ! FORZAR )); then
  echo "   No la saco a la fuerza: las apps que pausó y las políticas de Chrome" >&2
  echo "   quedarían puestas sin nadie que las pueda sacar. Instalá una versión" >&2
  echo "   que ande (./scripts/provisionar.sh la reinstala) y volvé a correr esto." >&2
  echo "   Último recurso: ./scripts/liberar.sh --forzar" >&2
  exit 1
fi

echo
echo "── sacando Device Owner a la fuerza ──────────────────"
# Lo que pausó Slowtown, leído de sus preferencias antes de soltarla: después
# nadie más lo sabe. run-as anda porque la build de prueba es depurable, y no
# necesita que Slowtown arranque.
prefs=$(adb shell run-as "$PAQUETE" cat shared_prefs/slowtown.xml 2>/dev/null | tr -d '\r' || true)
pausadas=$(awk '/<set name="vetoed"/ {dentro = 1; next}
  dentro && /<\/set>/ {dentro = 0}
  dentro {gsub(/.*<string>|<\/string>.*/, ""); print}' <<<"$prefs")

# Sacar el administrador es lo que abre el teléfono: el sistema vacía la lista
# de lock task. (`am task lock stop` no sirve acá: sólo corta la fijación de
# pantalla común, no un cierre de Device Owner.)
if ! adb shell dpm remove-active-admin "$ADMIN"; then
  echo "🚨 No se pudo. Si la app no es una build de prueba (testOnly), Android" >&2
  echo "   no deja sacarla por cable." >&2
  exit 1
fi
echo "✅ Slowtown ya no es Device Owner y el teléfono quedó abierto."

# Las políticas de Chrome y el bloqueo de desinstalar se van solos con el
# Device Owner; la pausa no. A una app de sistema se le borra desinstalándola
# para este usuario y volviéndola a poner: el APK queda y, con -k, sus datos.
sistema=$(adb shell pm list packages -s | tr -d '\r')
quedan=()
for p in $pausadas; do
  if grep -qx "package:$p" <<<"$sistema"; then
    adb shell pm uninstall -k --user 0 "$p" >/dev/null 2>&1 || true
    adb shell cmd package install-existing "$p" >/dev/null 2>&1 || true
  fi
  estado=$(adb shell dumpsys package "$p" | tr -d '\r')
  if grep -q "suspended=true" <<<"$estado"; then quedan+=("$p"); else echo "   ya se puede abrir: $p"; fi
done
if (( ${#quedan[@]} )); then
  echo "⚠️  Siguen pausadas: ${quedan[*]}"
  echo "   Son de Play: desinstalalas y volvelas a bajar para sacarles la pausa."
fi
echo "   El Inicio fijo no se va hasta desinstalarla: adb uninstall $PAQUETE"
