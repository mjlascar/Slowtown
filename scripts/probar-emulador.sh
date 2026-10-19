#!/usr/bin/env bash
# Prueba de punta a punta de Slowtown en el emulador de Android 12 (el AVD
# slowtown-api31 de scripts/emulador.sh). Es la regla 4 de CLAUDE.md hecha
# script: al teléfono sólo llega lo que ya anduvo acá, con llamadas y SMS
# probados con Slowtown activo.
#
# En orden: instala y provisiona, activa Slowtown con cuatro apps, intenta
# escaparse, abre una app permitida, atiende una llamada, recibe un SMS, abre
# Ajustes desde la administración, reinicia, cuenta las reglas de Inicio fijo,
# fuerza tres crashes y usa la salida por cable. Cada verificación imprime ✅
# o ❌. Después de una falla la prueba sigue, salvo que sin eso lo que viene no
# pruebe nada; si hubo alguna ❌, termina con código 1.
#
#   ./scripts/probar-emulador.sh                   # con el APK ya compilado
#   ./scripts/probar-emulador.sh --build           # compila antes
#   ./scripts/probar-emulador.sh --limpio          # libera, desinstala y arranca de cero
#   ./scripts/probar-emulador.sh --build --limpio
#
# Variables:
#   SLOWTOWN_CLAVE       contraseña de prueba: letras y números, 4 o más (por
#                        defecto prueba1234). Sin --limpio tiene que ser la que
#                        Slowtown ya tiene guardada.
#   SLOWTOWN_CAPTURAS    dónde dejar capturas, volcados de pantalla y logcat;
#                        adentro se crea una carpeta por corrida.
#   SLOWTOWN_ATENDER_XY  dónde tocar «Answer» si uiautomator no ve el aviso de
#                        llamada (por defecto 542,459: un Pixel 5 de 1080x2340).
#
# Sólo corre contra un emulador, y sólo si es lo único conectado: libera y
# vuelve a provisionar Device Owner, reinicia y fuerza crashes. Mientras
# corre, no tocar el emulador: la prueba maneja la pantalla.

# Sin -e a propósito: una verificación que falla se anota y la prueba sigue.
set -uo pipefail

RAIZ=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
PAQUETE=com.slowtown.launcher
INICIO_COMPONENTE="$PAQUETE/.HomeActivity"
# Gradle corre en el JDK 26 del sistema: de Java 21 sólo está el runtime (CLAUDE.md).
JAVA_HOME_GRADLE=/usr/lib/jvm/java-26-openjdk

CLAVE="${SLOWTOWN_CLAVE:-prueba1234}"
# Por defecto, el directorio de trabajo de la sesión en que se escribió esta
# prueba; conviene pasarle otro con SLOWTOWN_CAPTURAS.
CAPTURAS="${SLOWTOWN_CAPTURAS:-/tmp/claude-1000/-home-imgordo-Documents-proyectos-Slowtown/a0220bbf-933f-4f77-a59f-1051719f2301/scratchpad/e2e}"
ATENDER_XY="${SLOWTOWN_ATENDER_XY:-542,459}"

# ── Textos de Slowtown ──────────────────────────────────────────────────────
# Copiados de app/src/main/java/com/slowtown/launcher/ui/. Si cambia la
# redacción en la app, se cambia acá y en ningún otro lado.
TXT_HORA_RE='^[0-9]{1,2}:[0-9]{2}$'          # la hora de Inicio, que se mantiene apretada
TXT_FECHA_RE='^[^ ]+ [0-9]{1,2} de [^ ]+$'   # «Lunes 19 de octubre»
TXT_INICIO_SIN_APPS='Mantené apretada la hora para elegir las apps.'
TXT_INICIO_FALLOS='se colgó varias veces seguidas y se pausó solo'   # un pedazo del aviso en Inicio
TXT_CREAR_CLAVE='Elegí una contraseña'
TXT_GUARDAR='Guardar'
TXT_ENTRAR='Entrar'
TXT_CLAVE_MAL='No es esa.'
TXT_CLAVE_ESPERA='Demasiados intentos'
TXT_ADMIN='Administración'
TXT_ADMIN_FALLOS='Modo a prueba de fallos'
TXT_SALIR_FALLOS='Ya lo revisé: salir del modo a prueba de fallos'
TXT_ELEGIR_APPS='Elegir las apps de Inicio'
TXT_ACTIVAR='Activar Slowtown'
TXT_PAUSAR='Pausar Slowtown'
TXT_IR_AJUSTES='Ir a Ajustes'
TXT_APPS_TITULO='Apps de Inicio'
TXT_APPS_PISTA='Con Slowtown activo, las que no marques no se abren. Las llamadas y los SMS entran igual.'
TXT_VOLVER='Volver'
TXT_LISTO_RE='^Listo \(([0-9]+)\)$'

# ── Textos del emulador ─────────────────────────────────────────────────────
# La imagen AOSP de Android 12 está en inglés.
APPS_INICIO=(Phone Messaging Camera Clock)   # las que se eligen para Inicio
APP_RELOJ=Clock                              # la app permitida que se abre
APP_AJUSTES=Settings                         # no puede aparecer en el selector de apps
TXT_ATENDER=Answer                           # el aviso de llamada (en pantalla dice ANSWER)
TXT_GESTIONAR=Manage                         # botón al pie del panel de notificaciones
ID_GESTIONAR=id/manage_text                  # el mismo (dice History si el historial está prendido)
TXT_OK=OK                                    # cartel «App is not available»
ID_OK=id/button1
TXT_CERRAR_APP='Close app'                   # diálogo «Slowtown keeps stopping»
ID_CERRAR_APP=id/aerr_close
TXT_SELECTOR_SLOWTOWN=Slowtown               # selector de Inicio: «Select a Home app» o «Use Slowtown as Home»
TXT_UNA_VEZ='Just once'
ID_UNA_VEZ=id/button_once
# Lo que no puede aparecer en el panel con Slowtown activo: el engranaje y los
# ajustes rápidos (ids y descripción de SystemUI en Android 12).
PANEL_PROHIBIDO_RE='settings_button|quick_qs_panel|quick_settings_panel|Open settings'

# ── Ventanas (lo que muestra mCurrentFocus en dumpsys window) ───────────────
FOCO_INICIO="$PAQUETE/$PAQUETE.HomeActivity"
FOCO_PANEL=NotificationShade
FOCO_BLOQUEO=BlockedAppActivity       # «App is not available»: así frena lock task a una app no permitida
FOCO_SELECTOR=ResolverActivity        # el selector de Inicio de Android
FOCO_ERROR='Application Error'        # el diálogo de crash
FOCO_RELOJ=com.android.deskclock/
FOCO_LLAMADA=InCallActivity
FOCO_AJUSTES=com.android.settings/
FOCO_CALENDARIO=com.android.calendar/
FOCO_RECIENTES=com.android.launcher3/   # en el emulador, Recientes lo dibuja Quickstep
RELOJ_ACTIVIDAD=com.android.deskclock/.DeskClock
CALENDARIO=com.android.calendar/.AllInOneActivity

# Números para la radio simulada del emulador.
NUM_CALENTAMIENTO=2235550001
NUM_LLAMADA=2235554321
NUM_SMS=2235551111

UI_REMOTO=/data/local/tmp/slowtown-ui.xml

# Se completan en el paso 0.
SERIE=''
ADB=(adb)
ANCHO=1080
ALTO=2340
SALIDA=''
UI_XML=''

PASO=0
FALLAS=()
N_CAPTURA=0
CARTEL=0
declare -A CAMBIAR=() VISTAS=()

# ── Lectura de pantallas ────────────────────────────────────────────────────
# Busca en el XML de «uiautomator dump». Modos:
#   texto|contiene|regex|id CONSULTA   centro "x y" del primer nodo que calza
#   campo N                            centro del N-ésimo campo de texto
#   textos                             x, y, borde de arriba, de abajo y texto de cada nodo
#   hay REGEX                          los nodos cuyo id o texto calzan (sale 1 si ninguno)
#   libre TIPO CONSULTA PAQUETE        como texto/regex, pero sólo en ese paquete y sale 4
#                                      si otra ventana (un aviso) tapa ese punto
#   valido                             si el XML se puede leer
PY_UI=$(cat <<'PY'
import re
import sys
import xml.etree.ElementTree as ET


def nodos(archivo):
    for n in ET.parse(archivo).getroot().iter('node'):
        b = [int(v) for v in re.findall(r'-?\d+', n.get('bounds', ''))]
        if len(b) == 4 and b[2] > b[0] and b[3] > b[1]:
            yield n, b


def centro(b):
    return (b[0] + b[2]) // 2, (b[1] + b[3]) // 2


def textos(n):
    return [' '.join(t.split()) for t in (n.get('text'), n.get('content-desc')) if t and t.strip()]


def criterio(tipo, consulta):
    if tipo == 'regex':
        r = re.compile(consulta)
        return lambda n: any(r.search(t) for t in textos(n))
    if tipo == 'contiene':
        q = consulta.casefold()
        return lambda n: any(q in t.casefold() for t in textos(n))
    if tipo == 'id':
        return lambda n: (n.get('resource-id') or '').endswith(consulta)
    q = ' '.join(consulta.split()).casefold()
    return lambda n: any(t.casefold() == q for t in textos(n))


def main(modo, archivo, *args):
    if modo == 'valido':
        ET.parse(archivo)
        return 0
    lista = list(nodos(archivo))
    if modo == 'textos':
        for n, b in lista:
            x, y = centro(b)
            for t in textos(n):
                print(f'{x}\t{y}\t{b[1]}\t{b[3]}\t{t}')
        return 0
    if modo == 'campo':
        campos = [centro(b) for n, b in lista if (n.get('class') or '').endswith('EditText')]
        i = int(args[0]) - 1
        if 0 <= i < len(campos):
            print(*campos[i])
            return 0
        return 1
    if modo == 'hay':
        r = re.compile(args[0])
        hallados = [' '.join([n.get('resource-id') or ''] + textos(n)).strip()
                    for n, b in lista
                    if r.search(n.get('resource-id') or '') or any(r.search(t) for t in textos(n))]
        print('\n'.join(hallados))
        return 0 if hallados else 1
    if modo == 'libre':
        tipo, consulta, paquete = args
        cumple = criterio(tipo, consulta)
        for n, b in lista:
            if n.get('package') == paquete and cumple(n):
                x, y = centro(b)
                if any(m.get('package') != paquete and o[0] <= x < o[2] and o[1] <= y < o[3]
                       for m, o in lista):
                    return 4
                print(x, y)
                return 0
        return 1
    cumple = criterio(modo, args[0])
    for n, b in lista:
        if cumple(n):
            print(*centro(b))
            return 0
    return 1


try:
    sys.exit(main(*sys.argv[1:]))
except (ET.ParseError, OSError):
    sys.exit(3)
PY
)

# ── Resultados ──────────────────────────────────────────────────────────────
ok()    { echo "  ✅ $*"; }
nota()  { echo "  · $*"; }
aviso() { echo "  ⚠️  $*"; }
mal() {
  echo "  ❌ $*"
  FALLAS+=("$PASO. $*")
  # Una captura por falla: después, sin el emulador delante, es lo único que
  # cuenta qué había en pantalla.
  if [ -n "$SERIE" ]; then captura "falla-paso$PASO"; fi
  return 0
}
titulo() {
  PASO=$1
  shift
  echo
  echo "── $PASO. $* ──────────────────────────────"
}
resumen() {
  echo
  echo "── resumen ──────────────────────────────────────────"
  if [ -n "$SERIE" ]; then adb_t 60 logcat -d >"$SALIDA/logcat.txt" 2>/dev/null; fi
  echo "Capturas, volcados y logcat: $SALIDA"
  if (( ${#FALLAS[@]} )); then
    echo "❌ Fallaron ${#FALLAS[@]}:"
    printf '   %s\n' "${FALLAS[@]}"
  else
    echo "✅ Todo anduvo."
  fi
}
abortar() {
  mal "$*"
  echo "  🚨 Corto acá: sin esto, lo que sigue no prueba nada."
  resumen
  exit 1
}

# ── adb ─────────────────────────────────────────────────────────────────────
# Siempre con stdin en /dev/null: adb shell lee stdin, y adentro de un
# «while read» se comería el resto de la entrada.
adb_() { "${ADB[@]}" "$@" </dev/null; }
adb_t() {   # adb_t SEGUNDOS ...: lo mismo con tope de tiempo, por si adb se cuelga
  local seg=$1
  shift
  timeout "$seg" "${ADB[@]}" "$@" </dev/null
}
# La salida de un comando en el dispositivo, sin los \r de adb. Primero a una
# variable y después grep: con pipefail, un «grep -q» al final de un pipeline
# corta la lectura, adb muere por SIGPIPE y el if lee un error.
disp() { adb_t 120 shell "$@" 2>&1 | tr -d '\r'; }
emu() {     # la consola del emulador: llamadas y SMS simulados
  adb_t 30 emu "$@" >/dev/null 2>&1 || aviso "falló adb emu $*"
}

# Las esperas corren del lado del dispositivo: una sola llamada a adb por
# espera, que pregunta cada medio segundo hasta el tope. El timeout de este
# lado es la red por si adb mismo se cuelga.
esperar() {    # esperar SEGUNDOS 'condición de shell en el dispositivo'
  local seg=$1 cond=$2
  adb_t $((seg + 30)) shell \
    "fin=\$((\$(date +%s) + $seg)); until $cond; do [ \$(date +%s) -ge \$fin ] && exit 1; sleep 0.5; done; exit 0" \
    >/dev/null 2>&1
}
sostener() {   # sostener SEGUNDOS 'condición': que valga todo ese tiempo, no sólo una vez
  local seg=$1 cond=$2
  adb_t $((seg + 30)) shell \
    "fin=\$((\$(date +%s) + $seg)); while [ \$(date +%s) -lt \$fin ]; do $cond || exit 1; sleep 0.5; done; exit 0" \
    >/dev/null 2>&1
}
c_foco()  { echo "dumpsys window displays | grep -q 'mCurrentFocus=.*$1'"; }
c_lock()  { echo "dumpsys activity activities | grep -q 'mLockTaskModeState=$1'"; }
c_llamada() { echo "dumpsys telecom | grep -q 'state=$1'"; }
c_sin_llamada() { echo "! dumpsys telecom | grep -qE 'state=(RINGING|DIALING|ANSWERED|ACTIVE|ON_HOLD|DISCONNECTING)'"; }

foco() {      # la ventana con foco, p. ej. Window{1a2b u0 com.slowtown.launcher/...HomeActivity}
  local out
  out=$(disp dumpsys window displays)
  sed -n '/mCurrentFocus=/{s/.*mCurrentFocus=//;p;q}' <<<"$out"
}
estado() {    # LOCKED, NONE o PINNED
  local out
  out=$(disp dumpsys activity activities)
  sed -n '/mLockTaskModeState=/{s/.*mLockTaskModeState=//;p;q}' <<<"$out"
}
es_device_owner() {
  local out
  out=$(disp dpm list-owners)
  grep -q "admin=$PAQUETE/.*DeviceOwner" <<<"$out"
}
instalada() {
  local out
  out=$(disp pm path "$PAQUETE")
  grep -q '^package:' <<<"$out"
}
inicio_resuelve_a_slowtown() {   # a qué resuelve Inicio ahora (sin el Inicio fijo, al selector de Android)
  local out
  out=$(disp cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME)
  grep -q "^$PAQUETE/" <<<"$out"
}
marca_logcat() {   # la hora del dispositivo en el formato de «logcat -T»
  local t
  t=$(disp date +%s)
  echo "$t.000"
}
violaciones_desde() {   # cuántas veces lock task frenó un arranque desde la marca
  local out
  out=$(adb_t 60 logcat -d -T "$1" 2>/dev/null)
  grep -c 'Attempted Lock Task Mode violation' <<<"$out"
}
adb_como() {   # adb_como 0|2000: root (0) o shell (2000)
  # adbd se reinicia y por un rato no hay a quién preguntarle: esta espera da
  # vueltas desde la computadora.
  local uid=$1 fin u
  if [ "$uid" = 0 ]; then adb_t 30 root >/dev/null 2>&1; else adb_t 30 unroot >/dev/null 2>&1; fi
  fin=$((SECONDS + 60))
  while (( SECONDS < fin )); do
    u=$(adb_t 10 shell id -u 2>/dev/null | tr -d '\r')
    [ "$u" = "$uid" ] && return 0
    sleep 1
  done
  return 1
}

captura() {   # captura NOMBRE: la pantalla a $SALIDA/NN-NOMBRE.png
  local archivo
  N_CAPTURA=$((N_CAPTURA + 1))
  printf -v archivo '%s/%02d-%s.png' "$SALIDA" "$N_CAPTURA" "$1"
  if adb_t 30 exec-out screencap -p >"$archivo" 2>/dev/null && [ -s "$archivo" ]; then
    echo "     captura: $archivo"
  else
    rm -f "$archivo"
    return 1
  fi
}

# ── Tocar la pantalla ───────────────────────────────────────────────────────
ui() { python3 -I -c "$PY_UI" "$1" "$UI_XML" "${@:2}"; }

volcar_ui() {   # volcar_ui [--windows]: lo que hay en pantalla, a $UI_XML
  # Sin --windows, uiautomator ve sólo la ventana activa (la app o un diálogo);
  # con --windows ve también las del sistema, como los avisos de llamada.
  local intento
  for intento in 1 2 3; do
    rm -f "$UI_XML"
    adb_t 60 shell "rm -f $UI_REMOTO; uiautomator dump $* $UI_REMOTO" >/dev/null 2>&1
    if adb_t 30 exec-out cat "$UI_REMOTO" >"$UI_XML" 2>/dev/null && ui valido; then
      return 0
    fi
    sleep 1
  done
  return 1
}
tocar_xy() { adb_t 20 shell input tap "$1" "$2" >/dev/null 2>&1; }
tocar() {       # tocar TIPO CONSULTA [--windows]: vuelca, busca y toca el centro
  local tipo=$1 consulta=$2 xy x y
  shift 2
  volcar_ui "$@" || return 1
  xy=$(ui "$tipo" "$consulta") || return 1
  read -r x y <<<"$xy"
  tocar_xy "$x" "$y"
}
tocar_boton() { # tocar_boton ID TEXTO: por id o, si no, por texto, en un solo volcado
  local xy x y
  volcar_ui || return 1
  xy=$(ui id "$1") || xy=$(ui texto "$2") || return 1
  read -r x y <<<"$xy"
  tocar_xy "$x" "$y"
}
arrastrar() {   # arrastrar DESDE HASTA (en % del alto de la pantalla), sin inercia
  local x=$((ANCHO / 2)) y1=$((ALTO * $1 / 100)) y2=$((ALTO * $2 / 100))
  local ym=$(((y1 + y2) / 2))
  # input swipe suelta el dedo en movimiento y la lista sigue de largo por
  # inercia: se puede saltear apps. Con motionevent el dedo se queda quieto un
  # rato antes de soltar, y la lista se mueve justo lo arrastrado.
  adb_t 30 shell "input motionevent DOWN $x $y1; input motionevent MOVE $x $ym; input motionevent MOVE $x $y2; sleep 0.4; input motionevent UP $x $y2" >/dev/null 2>&1
}
tocar_desplazando() {   # como tocar, pero si no está a la vista corre la pantalla y prueba de nuevo
  local vuelta
  for vuelta in 1 2 3 4; do
    tocar "$@" && return 0
    # Si quedó abierto el teclado, tapa la mitad de abajo: el arrastre va por
    # la mitad de arriba, así no toca teclas.
    arrastrar 45 20
  done
  return 1
}
esperar_ui() {  # esperar_ui SEGUNDOS TIPO CONSULTA [--windows]: deja el volcado en $UI_XML
  local seg=$1 tipo=$2 consulta=$3 fin
  shift 3
  fin=$((SECONDS + seg))
  # Esta espera sí da vueltas desde la computadora: cada intento necesita el
  # volcado de este lado para buscar el texto y sus coordenadas.
  until volcar_ui "$@" && ui "$tipo" "$consulta" >/dev/null; do
    (( SECONDS < fin )) || return 1
    sleep 0.5
  done
}
tocar_en_inicio() {   # tocar_en_inicio TIPO CONSULTA [MS]: toca (o mantiene MS) algo de Inicio
  # Un aviso del sistema (el del SMS, una llamada perdida) se dibuja encima de
  # la hora y se llevaría el toque: se espera a que se vaya solo.
  local tipo=$1 consulta=$2 ms=${3:-} fin=$((SECONDS + 25)) xy='' r x y
  while :; do
    if volcar_ui --windows; then
      xy=$(ui libre "$tipo" "$consulta" "$PAQUETE")
      r=$?
      (( r == 0 )) && break
      (( r == 1 )) && return 1
    fi
    (( SECONDS < fin )) || return 1
    sleep 1
  done
  read -r x y <<<"$xy"
  if [ -n "$ms" ]; then
    adb_t 20 shell input swipe "$x" "$y" "$x" "$y" "$ms" >/dev/null 2>&1
  else
    tocar_xy "$x" "$y"
  fi
}

# ── Pasos compuestos ────────────────────────────────────────────────────────
ir_a_inicio() {   # aprieta Inicio y espera a Slowtown en primer plano
  adb_t 20 shell input keyevent KEYCODE_HOME >/dev/null 2>&1
  esperar 15 "$(c_foco "$FOCO_INICIO")"
}

afirmar_en_inicio() {   # afirmar_en_inicio QUÉ: foco en Slowtown y lock task en LOCKED
  local que=$1 f e
  esperar 15 "$(c_foco "$FOCO_INICIO")"
  f=$(foco)
  e=$(estado)
  if [[ $f == *"$FOCO_INICIO"* && $e == LOCKED ]]; then
    ok "$que"
  else
    mal "$que (foco: ${f:-?}; lock task: ${e:-?})"
  fi
}

escribir_clave() { adb_t 20 shell input text "$CLAVE" >/dev/null 2>&1; }

entrar_admin() {   # desde Inicio hasta «Administración»: mantener apretada la hora y la contraseña
  local fin pantalla=''
  tocar_en_inicio regex "$TXT_HORA_RE" 1500 || { nota "no encuentro la hora en Inicio"; return 1; }
  fin=$((SECONDS + 15))
  while (( SECONDS < fin )); do
    if volcar_ui; then
      if ui texto "$TXT_CREAR_CLAVE" >/dev/null; then pantalla=crear; break; fi
      if ui texto "$TXT_ENTRAR" >/dev/null; then pantalla=pedir; break; fi
    fi
    sleep 0.5
  done
  case $pantalla in
    crear)
      tocar_desplazando campo 1 && escribir_clave &&
        tocar_desplazando campo 2 && escribir_clave &&
        tocar_desplazando texto "$TXT_GUARDAR" || return 1
      ;;
    pedir)
      tocar_desplazando campo 1 && escribir_clave &&
        tocar_desplazando texto "$TXT_ENTRAR" || return 1
      ;;
    *)
      nota "mantener apretada la hora no abrió la contraseña"
      return 1
      ;;
  esac
  # La contraseña se deriva con PBKDF2 a propósito lento: puede tardar.
  esperar_ui 20 texto "$TXT_ADMIN" && return 0
  if ui texto "$TXT_CLAVE_MAL" >/dev/null || ui contiene "$TXT_CLAVE_ESPERA" >/dev/null; then
    nota "Slowtown no acepta SLOWTOWN_CLAVE: tiene guardada otra (corré con --limpio o pasá la correcta)"
  fi
  return 1
}

activar_slowtown() {   # en Administración: deja Slowtown activo pasando siempre por «Activar Slowtown»
  volcar_ui || return 1
  if ui texto "$TXT_PAUSAR" >/dev/null; then
    # Quedó activo de una corrida anterior. Se pausa para probar la activación
    # de cero, y porque después de volver a provisionar el botón puede decir
    # «Pausar» sin que el sistema esté en lock task.
    nota "ya estaba activo de antes: lo pauso para probar la activación"
    tocar_desplazando texto "$TXT_PAUSAR" || return 1
    esperar_ui 10 texto "$TXT_ACTIVAR" || return 1
  fi
  tocar_desplazando texto "$TXT_ACTIVAR" || return 1
  esperar 15 "$(c_lock LOCKED)"
}

etiquetas_inicio() {   # las apps que lista Inicio en el último volcado, una por línea
  local x y arriba abajo t
  while IFS=$'\t' read -r x y arriba abajo t; do
    [[ $t =~ $TXT_HORA_RE || $t =~ $TXT_FECHA_RE ]] && continue
    [[ $t == "$TXT_INICIO_SIN_APPS" || $t == *"$TXT_INICIO_FALLOS"* ]] && continue
    printf '%s\n' "$t"
  done < <(ui textos)
}

esperar_lista_inicio() {   # hasta que Inicio muestre sus apps o el cartel de «elegir las apps»
  # La lista carga en segundo plano: recién vuelto a Inicio puede estar vacía
  # sin que falte nada.
  local fin=$((SECONDS + 15))
  while (( SECONDS < fin )); do
    if volcar_ui && { [ -n "$(etiquetas_inicio)" ] || ui texto "$TXT_INICIO_SIN_APPS" >/dev/null; }; then
      return 0
    fi
    sleep 0.5
  done
  return 1
}

es_de_inicio() {   # si la etiqueta es una de APPS_INICIO
  local a
  for a in "${APPS_INICIO[@]}"; do [ "$a" = "$1" ] && return 0; done
  return 1
}

es_texto_del_selector() {   # lo que hay en el selector de apps que no es una app
  [[ $1 == "$TXT_APPS_TITULO" || $1 == "$TXT_APPS_PISTA" || $1 == "$TXT_VOLVER" || $1 =~ $TXT_LISTO_RE ]]
}

contar_listo() {   # el N de «Listo (N)» en el último volcado
  local x y arriba abajo t
  while IFS=$'\t' read -r x y arriba abajo t; do
    if [[ $t =~ $TXT_LISTO_RE ]]; then
      echo "${BASH_REMATCH[1]}"
      return 0
    fi
  done < <(ui textos)
  return 1
}

recorrer_selector() {
  # Recorre el selector de apps de punta a punta: toca las que están en
  # CAMBIAR (para que queden marcadas justo APPS_INICIO) y anota en VISTAS
  # todas las que aparecen. Las casillas no se pueden leer con uiautomator
  # (no tienen estado de accesibilidad), por eso se decide desde lo que
  # mostraba Inicio.
  local vuelta vacias=0 nuevas lo hi x y arriba abajo t
  VISTAS=()
  for vuelta in $(seq 1 20); do
    volcar_ui || return 1
    # La franja de la lista: entre la pista de arriba y la fila de «Volver» y
    # «Listo». Sólo se toca lo que está entero adentro: algo que asoma a medias
    # podría caer sobre esos botones.
    lo=0
    hi=$ALTO
    while IFS=$'\t' read -r x y arriba abajo t; do
      if [[ $t == "$TXT_APPS_TITULO" || $t == "$TXT_APPS_PISTA" ]] && (( abajo > lo )); then lo=$abajo; fi
      if [[ $t == "$TXT_VOLVER" || $t =~ $TXT_LISTO_RE ]] && (( arriba < hi )); then hi=$arriba; fi
    done < <(ui textos)
    nuevas=0
    while IFS=$'\t' read -r x y arriba abajo t; do
      es_texto_del_selector "$t" && continue
      if [[ -z ${VISTAS[$t]+x} ]]; then
        VISTAS[$t]=1
        nuevas=1
      fi
      if [[ ${CAMBIAR[$t]:-} == 1 ]] && (( arriba >= lo && abajo <= hi )); then
        tocar_xy "$x" "$y"
        CAMBIAR[$t]=hecho
      fi
    done < <(ui textos)
    if (( ${#VISTAS[@]} == 0 )); then
      # La lista también carga en segundo plano.
      (( ++vacias > 10 )) && return 1
      sleep 1
      continue
    fi
    (( nuevas )) || return 0   # nada nuevo después de correrla: es el final
    arrastrar 70 40
  done
  return 1
}

cerrar_cartel_bloqueo() {   # si está «App is not available» (BlockedAppActivity), lo cierra con OK
  CARTEL=0
  [[ $(foco) == *"$FOCO_BLOQUEO"* ]] || return 0
  CARTEL=1
  captura cartel-bloqueo
  tocar_boton "$ID_OK" "$TXT_OK" || adb_t 20 shell input keyevent KEYCODE_BACK >/dev/null 2>&1
  esperar 5 "! $(c_foco "$FOCO_BLOQUEO")"
}

vigilar_escape() {   # vigilar_escape QUÉ FOCO_PROHIBIDO: después de un intento de escape
  local que=$1 prohibido=$2 escapo=0 como=''
  # No alcanza con que al final se esté en Slowtown: la app prohibida no tiene
  # que llegar a mostrarse nunca, así que se mira el foco unos segundos.
  sostener 4 "! $(c_foco "$prohibido")" || escapo=1
  # Con BLOCK_ACTIVITY_START_IN_TASK, lock task frena mostrando su cartel en
  # vez de devolver un error; puede tardar un poco más que el intento.
  esperar 4 "$(c_foco "$FOCO_INICIO") || $(c_foco "$FOCO_BLOQUEO")"
  cerrar_cartel_bloqueo
  (( CARTEL )) && como=' con el cartel «App is not available»'
  if (( escapo )); then
    mal "$que: se abrió igual ($(foco))"
    ir_a_inicio
    return 1
  fi
  afirmar_en_inicio "$que: bloqueado$como, sigue en Slowtown y LOCKED"
}

intento_am_start() {   # intento_am_start QUÉ FOCO_PROHIBIDO ARGUMENTOS_DE_AM_START...
  local que=$1 prohibido=$2 salida
  shift 2
  salida=$(disp am start "$@")
  if grep -qE 'unable to resolve|does not exist' <<<"$salida"; then
    # Si la app no existe, «no se abrió» no prueba nada.
    mal "$que: no existe en este emulador; actualizá la constante ($(grep -m1 Error <<<"$salida"))"
    return 1
  fi
  if grep -q 'unknown error code 101' <<<"$salida"; then
    nota "am start: error 101, la violación de lock task de siempre"
  fi
  vigilar_escape "$que" "$prohibido"
}

cerrar_dialogo_crash() {   # si quedó «Slowtown keeps stopping», lo cierra
  # Mientras el diálogo está abierto, el proceso que se colgó sigue vivo
  # esperando la respuesta: sin cerrarlo, el crash no termina de contarse.
  [[ $(foco) == *"$FOCO_ERROR"* ]] || return 0
  nota "apareció el diálogo de crash: lo cierro con «$TXT_CERRAR_APP»"
  captura dialogo-crash
  tocar_boton "$ID_CERRAR_APP" "$TXT_CERRAR_APP" || adb_t 20 shell input keyevent KEYCODE_BACK >/dev/null 2>&1
}

elegir_slowtown_en_selector() {   # en el selector de Inicio de Android: Slowtown, «Just once»
  local xy x y
  volcar_ui || return 1
  if xy=$(ui texto "$TXT_SELECTOR_SLOWTOWN"); then
    # En la lista, el primer toque sólo la marca; «Just once» la abre. Si con
    # el toque ya se abrió, no hay botón que tocar.
    read -r x y <<<"$xy"
    tocar_xy "$x" "$y"
    esperar 3 "$(c_foco "$FOCO_INICIO")" && return 0
    tocar_boton "$ID_UNA_VEZ" "$TXT_UNA_VEZ" || return 1
  elif ui contiene "$TXT_SELECTOR_SLOWTOWN" >/dev/null; then
    # «Use Slowtown as Home»: ya viene elegida, falta «Just once».
    tocar_boton "$ID_UNA_VEZ" "$TXT_UNA_VEZ" || return 1
  else
    return 1
  fi
  esperar 10 "$(c_foco "$FOCO_INICIO")"
}

# ── 0. El dispositivo ───────────────────────────────────────────────────────
paso_0() {
  titulo 0 "Dispositivo"
  local listado lineas serie estado_adb qemu sdk tam
  [[ $CLAVE =~ ^[A-Za-z0-9]{4,}$ ]] ||
    abortar "SLOWTOWN_CLAVE tiene que ser de letras y números, 4 o más: se tipea con «input text», que se lleva mal con espacios y símbolos"
  listado=$(adb devices 2>/dev/null) || abortar "adb devices no anda"
  mapfile -t lineas < <(awk '/^List of devices/ {next} NF >= 2 && $1 !~ /^\*/ {print $1 "\t" $2}' <<<"$listado")
  (( ${#lineas[@]} == 1 )) ||
    abortar "tiene que haber exactamente un dispositivo conectado y hay ${#lineas[@]}: así no hay forma de equivocarse de teléfono"
  IFS=$'\t' read -r serie estado_adb <<<"${lineas[0]}"
  [ "$estado_adb" = device ] || abortar "$serie está «$estado_adb», no «device»"
  # adb emu, con el que se simulan llamadas y SMS, sólo llega a un emulador
  # local por su puerto de consola.
  [[ $serie =~ ^emulator-[0-9]+$ ]] || abortar "$serie no es un emulador local (emulator-NNNN)"
  ADB=(adb -s "$serie")
  qemu=$(disp getprop ro.kernel.qemu)
  [ "$qemu" = 1 ] || abortar "$serie no dice ser un emulador (ro.kernel.qemu=$qemu)"
  # Recién ahora, con la certeza de que es el emulador: desde acá mal() saca
  # capturas, y liberar.sh y provisionar.sh, que llaman a adb a secas, van a
  # este mismo.
  SERIE=$serie
  export ANDROID_SERIAL="$SERIE"
  ok "un solo dispositivo y es un emulador: $SERIE"
  sdk=$(disp getprop ro.build.version.sdk)
  if [ "$sdk" = 31 ]; then ok "Android 12 (API 31)"; else aviso "API $sdk: la prueba está pensada para Android 12 (API 31)"; fi
  [ "$(disp getprop sys.boot_completed)" = 1 ] || abortar "el emulador todavía está arrancando"
  tam=$(disp wm size)
  tam=$(grep -oE '[0-9]+x[0-9]+' <<<"$tam" | tail -n 1)
  ANCHO=${tam%x*}
  ALTO=${tam#*x}
  [[ $ANCHO =~ ^[0-9]+$ && $ALTO =~ ^[0-9]+$ ]] || abortar "no pude leer el tamaño de la pantalla"
  nota "pantalla ${ANCHO}x$ALTO; capturas en $SALIDA"
  if [ "$tam" != 1080x2340 ]; then
    aviso "«Answer» se toca en $ATENDER_XY, medido en 1080x2340: si uiautomator no ve el aviso, ajustá SLOWTOWN_ATENDER_XY"
  fi
}

# ── 1. Instalación ──────────────────────────────────────────────────────────
paso_1() {
  titulo 1 "Instalación"
  if (( COMPILAR )); then
    [ -d "$JAVA_HOME_GRADLE" ] || abortar "no está el JDK en $JAVA_HOME_GRADLE"
    nota "compilando: JAVA_HOME=$JAVA_HOME_GRADLE ./gradlew assembleDebug"
    if (cd "$RAIZ" && JAVA_HOME="$JAVA_HOME_GRADLE" ./gradlew assembleDebug </dev/null); then
      ok "compila"
    else
      abortar "no compila"
    fi
  fi
  if (( LIMPIO )); then
    if es_device_owner; then
      # Un Device Owner no se desinstala: primero la salida por cable.
      nota "Slowtown es Device Owner: la libero antes de desinstalarla"
      (cd "$RAIZ" && ./scripts/liberar.sh </dev/null) || abortar "liberar.sh falló"
    fi
    if instalada; then
      if adb_t 120 uninstall "$PAQUETE" >/dev/null; then ok "desinstalada: arranca de cero"
      else abortar "no pude desinstalar $PAQUETE"; fi
    fi
  fi
  # Sin terminal en stdin: si provisionar.sh no reconociera el emulador y
  # pidiera confirmación como en un teléfono real, lee vacío y cancela.
  (cd "$RAIZ" && ./scripts/provisionar.sh </dev/null) || abortar "provisionar.sh falló"
  if es_device_owner; then ok "Slowtown es Device Owner"; else abortar "Slowtown no quedó como Device Owner"; fi
}

# ── 2. Activar Slowtown ─────────────────────────────────────────────────────
paso_2() {
  titulo 2 "Activar Slowtown con ${APPS_INICIO[*]}"
  local out l n mostradas esperadas faltan=()
  local -A en_inicio=()
  out=$(disp cmd package set-home-activity "$INICIO_COMPONENTE")
  if grep -q Success <<<"$out"; then ok "Slowtown queda como Inicio"; else mal "set-home-activity: $out"; fi
  ir_a_inicio || abortar "Inicio no lleva a Slowtown ($(foco))"
  esperar_lista_inicio || abortar "Inicio de Slowtown no termina de cargar"
  # Lo que ya está en Inicio (de una corrida anterior) dice qué tocar en el
  # selector para que queden marcadas justo las cuatro.
  while IFS= read -r l; do
    [ -n "$l" ] && en_inicio[$l]=1
  done < <(etiquetas_inicio)
  (( ${#en_inicio[@]} )) && nota "Inicio ya mostraba: ${!en_inicio[*]}"
  CAMBIAR=()
  for l in "${APPS_INICIO[@]}"; do
    [[ -n ${en_inicio[$l]+x} ]] || CAMBIAR[$l]=1
  done
  for l in "${!en_inicio[@]}"; do
    es_de_inicio "$l" || CAMBIAR[$l]=1
  done

  entrar_admin || abortar "no pude entrar a la administración"
  ok "mantener apretada la hora y la contraseña llevan a la administración"
  if ui texto "$TXT_SALIR_FALLOS" >/dev/null; then
    nota "quedó en modo a prueba de fallos de una corrida anterior: salgo antes de empezar"
    tocar_desplazando texto "$TXT_SALIR_FALLOS"
  fi

  { tocar_desplazando texto "$TXT_ELEGIR_APPS" && esperar_ui 10 texto "$TXT_APPS_TITULO"; } ||
    abortar "«$TXT_ELEGIR_APPS» no abre el selector"
  recorrer_selector || abortar "no pude recorrer el selector de apps"
  for l in "${!CAMBIAR[@]}"; do
    [ "${CAMBIAR[$l]}" = hecho ] || faltan+=("$l")
  done
  (( ${#faltan[@]} )) && mal "no pude marcar o desmarcar en el selector: ${faltan[*]}"
  if [[ -n ${VISTAS[$APP_AJUSTES]+x} ]]; then
    mal "«$APP_AJUSTES» aparece en el selector de apps"
  else
    ok "«$APP_AJUSTES» no aparece en el selector (que lista ${#VISTAS[@]} apps)"
  fi
  volcar_ui
  n=$(contar_listo)
  if [ "$n" = "${#APPS_INICIO[@]}" ]; then
    ok "quedan marcadas ${#APPS_INICIO[@]}"
  else
    abortar "el selector dice «Listo (${n:-?})» y tendrían que ser ${#APPS_INICIO[@]}"
  fi
  { tocar regex "$TXT_LISTO_RE" && esperar_ui 10 texto "$TXT_ADMIN"; } ||
    abortar "«Listo» no vuelve a la administración"

  if activar_slowtown; then
    ok "«$TXT_ACTIVAR» entra en lock task (LOCKED)"
  else
    abortar "«$TXT_ACTIVAR» no deja el sistema en LOCKED (está en $(estado))"
  fi
  ir_a_inicio
  afirmar_en_inicio "en Inicio con Slowtown activo"
  esperar_lista_inicio
  mostradas=$(etiquetas_inicio | sort -u)
  esperadas=$(printf '%s\n' "${APPS_INICIO[@]}" | sort -u)
  if [ "$mostradas" = "$esperadas" ]; then
    ok "Inicio muestra justo ${APPS_INICIO[*]}"
  else
    mal "Inicio muestra «$(tr '\n' ',' <<<"$mostradas")» en vez de ${APPS_INICIO[*]}"
  fi
  captura activo
}

# ── 3. Intentos de escape ───────────────────────────────────────────────────
paso_3() {
  titulo 3 "Intentos de escape"
  local hallado xy x y
  ir_a_inicio

  # Ajustes rápidos: con lock task, el panel tiene que abrir sin ellos (sin
  # engranaje no hay camino a Ajustes).
  adb_t 20 shell cmd statusbar expand-settings >/dev/null 2>&1
  if esperar 5 "$(c_foco "$FOCO_PANEL")"; then
    captura panel-ajustes-rapidos
    if volcar_ui --windows; then
      cp "$UI_XML" "$SALIDA/panel-ajustes-rapidos.xml"
      if hallado=$(ui hay "$PANEL_PROHIBIDO_RE"); then
        mal "el panel muestra ajustes rápidos o el engranaje: $(tr '\n' ';' <<<"$hallado")"
      else
        ok "el panel abre sin ajustes rápidos ni engranaje"
      fi
    else
      mal "no pude volcar el panel con uiautomator"
    fi
  else
    ok "pedir los ajustes rápidos ni siquiera abre el panel"
  fi
  adb_t 20 shell cmd statusbar collapse >/dev/null 2>&1
  vigilar_escape "ajustes rápidos" "$FOCO_AJUSTES"

  adb_t 20 shell input keyevent KEYCODE_APP_SWITCH >/dev/null 2>&1
  vigilar_escape "tecla de Recientes" "$FOCO_RECIENTES"

  intento_am_start "Ajustes con am start" "$FOCO_AJUSTES" -a android.settings.SETTINGS
  intento_am_start "Calendario con am start" "$FOCO_CALENDARIO" -n "$CALENDARIO"

  # «Manage», al pie del panel, abre los ajustes de notificaciones. Una
  # notificación propia asegura que el panel tenga pie: vacío no hay botón.
  adb_t 20 shell "cmd notification post -t Slowtown slowtown-e2e 'Prueba del panel'" >/dev/null 2>&1
  adb_t 20 shell cmd statusbar expand-notifications >/dev/null 2>&1
  if esperar 5 "$(c_foco "$FOCO_PANEL")" && volcar_ui --windows &&
    { xy=$(ui id "$ID_GESTIONAR") || xy=$(ui texto "$TXT_GESTIONAR"); }; then
    captura panel-manage
    read -r x y <<<"$xy"
    tocar_xy "$x" "$y"
    esperar 3 "! $(c_foco "$FOCO_PANEL")"
    adb_t 20 shell cmd statusbar collapse >/dev/null 2>&1
    vigilar_escape "botón «$TXT_GESTIONAR» del panel" "$FOCO_AJUSTES"
  else
    mal "no encontré «$TXT_GESTIONAR» en el panel ($(foco))"
    adb_t 20 shell cmd statusbar collapse >/dev/null 2>&1
    ir_a_inicio
  fi
}

# ── 4. Una app permitida ────────────────────────────────────────────────────
paso_4() {
  titulo 4 "App permitida: $APP_RELOJ"
  ir_a_inicio
  if ! tocar_en_inicio texto "$APP_RELOJ"; then
    mal "no encuentro «$APP_RELOJ» en Inicio"
    return
  fi
  if esperar 10 "$(c_foco "$FOCO_RELOJ")"; then ok "se abre el Reloj"; else mal "no se abre el Reloj ($(foco))"; fi
  if [ "$(estado)" = LOCKED ]; then
    ok "con el Reloj abierto, Slowtown sigue activo (LOCKED)"
  else
    mal "con el Reloj abierto, lock task está en $(estado)"
  fi
  captura reloj
  ir_a_inicio
  afirmar_en_inicio "Inicio vuelve a Slowtown"
}

# ── 5. Llamada entrante ─────────────────────────────────────────────────────
paso_5() {
  titulo 5 "Llamada entrante"
  local marca xy x y visto=no n
  ir_a_inicio

  # Calentamiento: la primera llamada después de instalar una vez no mostró
  # el aviso (el marcador arrancando en frío). Se informa, pero no cuenta.
  emu gsm call "$NUM_CALENTAMIENTO"
  if esperar 20 "$(c_llamada RINGING)"; then
    esperar_ui 8 texto "$TXT_ATENDER" --windows && visto=sí
    captura calentamiento-sonando
    nota "calentamiento: sonó; uiautomator vio «$TXT_ATENDER»: $visto"
  else
    nota "calentamiento: no llegó a sonar (no hubo RINGING)"
  fi
  emu gsm cancel "$NUM_CALENTAMIENTO"
  esperar 15 "$(c_sin_llamada)" || aviso "la llamada de calentamiento no terminó de cortarse"
  ir_a_inicio

  marca=$(marca_logcat)
  emu gsm call "$NUM_LLAMADA"
  if ! esperar 20 "$(c_llamada RINGING)"; then
    mal "la llamada no llega a sonar (no hay RINGING en dumpsys telecom)"
    emu gsm cancel "$NUM_LLAMADA"
    ir_a_inicio
    return
  fi
  # uiautomator, por defecto, ve sólo la app: el aviso es una ventana del
  # sistema. Con --windows a veces se ve; si no, coordenadas.
  visto=no
  if esperar_ui 8 texto "$TXT_ATENDER" --windows; then
    visto=sí
    xy=$(ui texto "$TXT_ATENDER")
  else
    xy=${ATENDER_XY/,/ }
  fi
  if captura llamada-sonando; then
    if [ "$visto" = sí ]; then
      ok "suena (RINGING) con el aviso a la vista: uiautomator ve «$TXT_ATENDER»"
    else
      ok "suena (RINGING); uiautomator no ve el aviso: confirmalo en la captura"
    fi
  else
    mal "suena, pero no pude guardar la captura del aviso"
  fi
  read -r x y <<<"$xy"
  tocar_xy "$x" "$y"
  if esperar 15 "$(c_foco "$FOCO_LLAMADA")"; then
    ok "«$TXT_ATENDER» abre la pantalla de llamada"
  else
    mal "al atender no aparece la pantalla de llamada ($(foco))"
  fi
  if esperar 15 "$(c_llamada ACTIVE)"; then ok "la llamada queda ACTIVE"; else mal "la llamada no queda ACTIVE"; fi
  n=$(violaciones_desde "$marca")
  if [ "$n" = 0 ]; then
    ok "lock task no frenó nada al atender"
  else
    mal "lock task frenó $n arranques al atender (Attempted Lock Task Mode violation)"
  fi
  captura en-llamada
  emu gsm cancel "$NUM_LLAMADA"
  esperar 15 "$(c_sin_llamada)" || mal "la llamada no se corta"
  afirmar_en_inicio "al cortar, de vuelta en Slowtown y LOCKED"
}

# ── 6. SMS ──────────────────────────────────────────────────────────────────
paso_6() {
  titulo 6 "SMS"
  local token out pkg
  ir_a_inicio
  # Una marca distinta en cada corrida: así una notificación vieja no cuenta.
  token="e2e$(date +%H%M%S)"
  emu sms send "$NUM_SMS" "Prueba de Slowtown $token"
  if esperar 30 "dumpsys notification --noredact | grep -q '$token'"; then
    out=$(disp dumpsys notification --noredact)
    pkg=$(awk -v t="$token" '
      /NotificationRecord\(/ { pkg = ""; if (match($0, /pkg=[^ ]+/)) pkg = substr($0, RSTART + 4, RLENGTH - 4) }
      index($0, t) && pkg != "" { print pkg; exit }' <<<"$out")
    if [ "$pkg" = com.android.messaging ]; then
      ok "llega el SMS y Mensajes lo notifica"
    else
      mal "la notificación con el SMS es de «${pkg:-?}», no de com.android.messaging"
    fi
  else
    mal "no aparece ninguna notificación con el SMS"
  fi
  captura sms
  afirmar_en_inicio "con el aviso del SMS, sigue en Slowtown y LOCKED"
}

# ── 7. Ajustes desde la administración ──────────────────────────────────────
paso_7() {
  titulo 7 "Ajustes desde la administración"
  local out
  ir_a_inicio
  # Una app abierta de antes: la sesión de Ajustes no la tiene que cerrar
  # (salir de lock task sí cerraría todo).
  if tocar_en_inicio texto "$APP_RELOJ" && esperar 10 "$(c_foco "$FOCO_RELOJ")"; then
    nota "dejo el Reloj abierto en segundo plano"
  else
    mal "no pude abrir el Reloj antes de la sesión ($(foco))"
  fi
  ir_a_inicio
  if ! entrar_admin; then
    mal "no pude entrar a la administración"
    ir_a_inicio
    return
  fi
  if ! tocar_desplazando texto "$TXT_IR_AJUSTES"; then
    mal "no encuentro «$TXT_IR_AJUSTES»"
    ir_a_inicio
    return
  fi
  if esperar 10 "$(c_foco "$FOCO_AJUSTES")"; then
    ok "«$TXT_IR_AJUSTES» abre Ajustes"
  else
    mal "«$TXT_IR_AJUSTES» no abre Ajustes ($(foco))"
  fi
  if [ "$(estado)" = LOCKED ]; then
    ok "con Ajustes abierto, Slowtown sigue activo (LOCKED)"
  else
    mal "con Ajustes abierto, lock task está en $(estado)"
  fi
  captura sesion-ajustes
  ir_a_inicio
  afirmar_en_inicio "volver a Inicio termina la sesión: Slowtown y LOCKED"
  out=$(disp dumpsys activity activities)
  if grep -qF "$RELOJ_ACTIVIDAD" <<<"$out"; then
    ok "el Reloj sobrevivió a la sesión en segundo plano"
  else
    mal "después de la sesión ya no está el Reloj ($RELOJ_ACTIVIDAD)"
  fi
  intento_am_start "Ajustes con am start después de la sesión" "$FOCO_AJUSTES" -a android.settings.SETTINGS
}

# ── 8. Reinicio ─────────────────────────────────────────────────────────────
paso_8() {
  titulo 8 "Reinicio"
  local antes boot='' fin
  adb_t 60 logcat -d >"$SALIDA/logcat-antes-del-reinicio.txt" 2>/dev/null
  antes=$(disp cat /proc/sys/kernel/random/boot_id)
  adb_t 30 reboot >/dev/null 2>&1
  # Mientras el emulador está apagado no hay a quién preguntarle: esta espera
  # da vueltas desde la computadora. Un boot_id distinto dice que de verdad
  # reinició (si no, el primer getprop puede contestar el arranque anterior).
  fin=$((SECONDS + 300))
  while (( SECONDS < fin )); do
    boot=$(adb_t 10 shell cat /proc/sys/kernel/random/boot_id 2>/dev/null | tr -d '\r')
    [[ -n $boot && $boot != "$antes" ]] && break
    sleep 2
  done
  [[ -n $boot && $boot != "$antes" ]] || abortar "el emulador no volvió del reinicio"
  esperar 240 '[ "$(getprop sys.boot_completed)" = 1 ]' || abortar "el emulador no termina de arrancar"
  ok "reinició"
  # Sin tocar nada: Slowtown tiene que volver a activarse sola.
  if esperar 90 "$(c_lock LOCKED) && $(c_foco "$FOCO_INICIO")"; then
    ok "después de reiniciar, sin tocar nada: Slowtown en primer plano y LOCKED"
  else
    mal "después de reiniciar, sin tocar nada: lock task $(estado), foco $(foco)"
    # Para que el resto pueda seguir: sacar la pantalla de bloqueo e ir a Inicio.
    adb_t 20 shell wm dismiss-keyguard >/dev/null 2>&1
    ir_a_inicio
    if esperar 15 "$(c_lock LOCKED)"; then nota "sacando el bloqueo y apretando Inicio, sí se activa"; fi
  fi
  captura tras-reinicio
}

# ── 9. Inicio fijo sin duplicados ───────────────────────────────────────────
paso_9() {
  titulo 9 "Inicio fijo sin duplicados"
  local i xml n
  ir_a_inicio
  for i in 1 2 3 4 5; do
    disp am start -n "$RELOJ_ACTIVIDAD" >/dev/null
    esperar 10 "$(c_foco "$FOCO_RELOJ")" || aviso "vuelta $i: el Reloj no llegó al frente"
    ir_a_inicio || aviso "vuelta $i: Inicio no volvió a Slowtown"
  done
  nota "5 vueltas a Inicio desde el Reloj"
  # Las reglas de Inicio fijo sólo se ven en package-restrictions.xml, que es
  # de system: hace falta root (la imagen del emulador es userdebug). En
  # Android 12 el archivo es XML binario; abx2xml lo pasa a texto.
  if ! adb_como 0; then
    mal "no pude pasar adb a root"
    return
  fi
  xml=$(disp "f=/data/local/tmp/slowtown-pr.xml; abx2xml /data/system/users/0/package-restrictions.xml \$f 2>/dev/null || cp /data/system/users/0/package-restrictions.xml \$f; cat \$f; rm -f \$f")
  adb_como 2000 || aviso "adb quedó como root (adb unroot lo vuelve atrás)"
  printf '%s\n' "$xml" >"$SALIDA/package-restrictions.xml"
  if ! grep -q '<package-restrictions' <<<"$xml"; then
    mal "no pude leer package-restrictions.xml"
    return
  fi
  n=$(awk '
    /<persistent-preferred-activities[^>]*\/>/ { next }
    /<persistent-preferred-activities/ { dentro = 1; next }
    /<\/persistent-preferred-activities>/ { dentro = 0 }
    dentro && /<item / && /com\.slowtown\.launcher\// { n++ }
    END { print n + 0 }' <<<"$xml")
  if [ "$n" = 1 ]; then
    ok "hay exactamente 1 regla de Inicio fijo para Slowtown"
  elif [ "$n" = 0 ]; then
    mal "no hay ninguna regla de Inicio fijo para Slowtown"
  else
    mal "hay $n reglas de Inicio fijo para Slowtown: se duplican al volver a Inicio"
  fi
}

# ── 10. Guardia de crashes ──────────────────────────────────────────────────
paso_10() {
  titulo 10 "Guardia de crashes"
  local i pid crashes=0 solo=1
  ir_a_inicio
  for i in 1 2 3; do
    # am crash sólo le llega a un proceso vivo que ya terminó de arrancar: se
    # espera que Slowtown tenga el foco. Un crash que no llega no se cuenta, y
    # el modo a prueba de fallos no se activaría nunca.
    if ! esperar 40 "pidof $PAQUETE >/dev/null && $(c_foco "$FOCO_INICIO")"; then
      if [[ $(foco) == *"$FOCO_SELECTOR"* ]]; then
        nota "el selector de Inicio apareció después de $crashes crashes"
        break
      fi
      mal "antes del crash $i, Slowtown no volvió a primer plano ($(foco))"
      break
    fi
    pid=$(disp pidof "$PAQUETE")
    disp am crash "$PAQUETE" >/dev/null
    crashes=$i
    # O muere ese proceso, o queda colgado esperando el diálogo «keeps stopping».
    esperar 20 "[ \"\$(pidof $PAQUETE)\" != '$pid' ] || $(c_foco "$FOCO_ERROR")"
    cerrar_dialogo_crash
    esperar 20 "[ \"\$(pidof $PAQUETE)\" != '$pid' ]" || mal "el crash $i no terminó con el proceso $pid"
  done
  if esperar 30 "$(c_lock NONE)"; then
    ok "con $crashes crashes seguidos, Slowtown se pausa solo (lock task en NONE)"
  else
    mal "después de $crashes crashes, lock task sigue en $(estado)"
    ir_a_inicio
    return
  fi
  # Sin el Inicio fijo, Android pregunta qué Inicio usar. Si el selector no
  # aparece solo, apretar Inicio lo tiene que mostrar.
  if ! esperar 20 "$(c_foco "$FOCO_SELECTOR")"; then
    solo=0
    adb_t 20 shell input keyevent KEYCODE_HOME >/dev/null 2>&1
    esperar 10 "$(c_foco "$FOCO_SELECTOR")"
  fi
  if [[ $(foco) == *"$FOCO_SELECTOR"* ]]; then
    if (( solo )); then ok "aparece el selector de Inicio de Android"
    else ok "Inicio ya no está fijo: al apretarlo aparece el selector de Android"; fi
    captura selector-inicio
    if elegir_slowtown_en_selector; then
      ok "elijo Slowtown con «$TXT_UNA_VEZ»"
    else
      mal "no pude elegir Slowtown en el selector ($(foco))"
      ir_a_inicio
    fi
  else
    mal "no aparece el selector de Inicio ($(foco))"
  fi
  if esperar_ui 10 contiene "$TXT_INICIO_FALLOS"; then
    ok "Inicio avisa que Slowtown se pausó solo"
  else
    mal "Inicio no muestra el aviso del modo a prueba de fallos"
  fi
  captura modo-a-prueba-de-fallos
  if ! entrar_admin; then
    mal "no pude entrar a la administración en modo a prueba de fallos"
    ir_a_inicio
    return
  fi
  if ui contiene "$TXT_ADMIN_FALLOS" >/dev/null; then
    ok "la administración muestra el modo a prueba de fallos"
  else
    mal "la administración no menciona el modo a prueba de fallos"
  fi
  if ! tocar_desplazando texto "$TXT_SALIR_FALLOS"; then
    mal "no encuentro «$TXT_SALIR_FALLOS»"
    ir_a_inicio
    return
  fi
  # Lo que tiene que pasar: se activa ahí mismo, sin pasar por Inicio.
  if esperar 10 "$(c_lock LOCKED)"; then
    ok "al salir del modo a prueba de fallos se activa enseguida, sin apretar Inicio"
  else
    mal "al salir del modo a prueba de fallos no se activa (lock task: $(estado))"
  fi
  if inicio_resuelve_a_slowtown; then ok "Inicio vuelve a quedar fijo en Slowtown"; else mal "Inicio no vuelve a quedar fijo en Slowtown"; fi
  ir_a_inicio
  afirmar_en_inicio "de vuelta en Inicio: Slowtown y LOCKED"
}

# ── 11. La salida por cable ─────────────────────────────────────────────────
paso_11() {
  titulo 11 "Salida por cable"
  if (cd "$RAIZ" && ./scripts/liberar.sh </dev/null); then ok "liberar.sh termina bien"; else mal "liberar.sh falló"; fi
  if esperar 15 "$(c_lock NONE)"; then ok "lock task en NONE"; else mal "lock task sigue en $(estado)"; fi
  if es_device_owner; then mal "Slowtown sigue siendo Device Owner"; else ok "Slowtown ya no es Device Owner"; fi
  captura liberado
  nota "vuelvo a provisionar para dejar el emulador listo"
  if (cd "$RAIZ" && ./scripts/provisionar.sh </dev/null) && es_device_owner; then
    ok "provisionar.sh la deja otra vez como Device Owner"
  else
    mal "no pude volver a provisionar"
  fi
  ir_a_inicio
  nota "al terminar: lock task $(estado), foco $(foco)"
}

# ── Principal ───────────────────────────────────────────────────────────────
COMPILAR=0
LIMPIO=0
for arg in "$@"; do
  case $arg in
    --build) COMPILAR=1 ;;
    --limpio) LIMPIO=1 ;;
    -h | --help)
      sed -n '2,/^# corre, no tocar/{s/^# \{0,1\}//;p}' "$0"
      exit 0
      ;;
    *)
      echo "No conozco la opción $arg (ver --help)." >&2
      exit 2
      ;;
  esac
done

PATH="$PATH:${ANDROID_HOME:-$HOME/Android/Sdk}/platform-tools"
for cmd in adb python3 timeout; do
  command -v "$cmd" >/dev/null || { echo "🚨 Falta $cmd." >&2; exit 2; }
done
SALIDA="$CAPTURAS/$(date +%Y%m%d-%H%M%S)"
mkdir -p "$SALIDA" || { echo "🚨 No puedo crear $SALIDA." >&2; exit 2; }
UI_XML="$SALIDA/ui.xml"
trap 'echo; aviso "cortado a mano"; resumen; exit 130' INT TERM

echo "Slowtown: prueba de punta a punta en el emulador"
paso_0
paso_1
paso_2
paso_3
paso_4
paso_5
paso_6
paso_7
paso_8
paso_9
paso_10
paso_11
resumen
(( ${#FALLAS[@]} == 0 ))
