#!/usr/bin/env bash
# La salida de emergencia por cable: abre el teléfono y saca a Slowtown de
# Device Owner.
#
# Sólo funciona si la app instalada es una build de prueba (android:testOnly,
# que es lo que sale de assembleDebug). Si no lo es, Android no deja sacarla
# por cable: queda «Dejar de ser Device Owner» dentro de la app, o un factory
# reset.
#
#   ./scripts/liberar.sh
set -euo pipefail

PAQUETE=com.slowtown.launcher
ADMIN="$PAQUETE/.admin.SlowtownAdminReceiver"

if [ "$(adb get-state 2>/dev/null)" != device ]; then
  echo "🚨 No hay ningún dispositivo conectado y autorizado." >&2
  echo "   Conectalo por USB y aceptá el diálogo (marcá «Permitir siempre»)." >&2
  exit 1
fi

# Sacar el administrador es lo que abre el teléfono: el sistema vacía la lista
# de lock task. (`am task lock stop` no sirve acá: sólo corta la fijación de
# pantalla común, no un cierre de Device Owner.)
echo "── sacando Device Owner ──────────────────────────────"
if adb shell dpm remove-active-admin "$ADMIN"; then
  echo "✅ Slowtown ya no es Device Owner y el teléfono quedó abierto."
  # El Inicio fijo no se va con el administrador: sólo lo borra la propia app
  # («Dejar de ser Device Owner») o desinstalarla.
  echo "   Sigue siendo la pantalla de inicio hasta desinstalarla:"
  echo "   adb uninstall $PAQUETE"
else
  echo "🚨 No se pudo. Si la app no es una build de prueba (testOnly), Android" >&2
  echo "   no deja sacarla por cable: usá «Dejar de ser Device Owner» en la app." >&2
  exit 1
fi
