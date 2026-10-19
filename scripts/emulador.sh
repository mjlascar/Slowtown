#!/usr/bin/env bash
# Levanta el emulador de prueba: Android 12 (API 31), la misma versión del
# S10e, con la imagen AOSP sin cuentas de Google, que es lo que exige Device
# Owner para provisionar. Si el AVD no existe, lo crea.
#
#   ./scripts/emulador.sh            # con ventana
#   ./scripts/emulador.sh --limpio   # borra los datos: el factory reset del emulador,
#                                    # para cuando el kiosco te deja encerrado
#
# Ocupa unos 3 GB de RAM reales: con el build de Gradle corriendo a la vez,
# conviene cerrar Firefox o compilar antes de levantarlo.
set -euo pipefail

SDK="${ANDROID_HOME:-$HOME/Android/Sdk}"
AVD=slowtown-api31
IMAGEN="system-images;android-31;default;x86_64"

avds=$("$SDK/emulator/emulator" -list-avds)
if ! grep -qx "$AVD" <<<"$avds"; then
  echo "── creando $AVD ──────────────────────────────────────"
  # El «no» contesta si se quiere un perfil de hardware a medida.
  echo no | "$SDK/cmdline-tools/latest/bin/avdmanager" create avd \
    -n "$AVD" -k "$IMAGEN" -d pixel_5
fi

# 1,5 GB y no 2: con 2 el proceso del emulador llegó a 3,4 GB y el kernel lo
# mató por falta de memoria mientras compilaba Gradle (19/10/2026).
args=(-avd "$AVD" -memory 1536 -no-boot-anim -no-audio -gpu auto)
[ "${1:-}" = --limpio ] && args+=(-wipe-data)

# En Wayland (Hyprland), el emulador abre su ventana por XWayland.
QT_QPA_PLATFORM=xcb exec "$SDK/emulator/emulator" "${args[@]}"
