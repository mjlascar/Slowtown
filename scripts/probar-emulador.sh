#!/usr/bin/env bash
# Prueba de punta a punta de Slowtown en el emulador de Android 12 (el AVD
# slowtown-api31 de scripts/emulador.sh). Es la regla 4 de CLAUDE.md hecha
# script: al teléfono sólo llega lo que ya anduvo acá, con llamadas y SMS
# probados con Slowtown activo.
#
# En orden: instala y provisiona, activa Slowtown con cuatro apps, intenta
# escaparse, abre una app permitida, mira el tiempo de pantalla de Inicio
# (sube con un rato en el Reloj, y sin el acceso al uso no está), atiende una
# llamada, recibe un SMS, busca y abre una app desde la lista de todas, saca
# y vuelve a poner una app en Inicio, marca hábitos (los botones de hoy y un
# día en el resumen), cambia el pie de Inicio entre el widget del Reloj y los
# hábitos, arma y borra una carpeta, usa las dos veces de «Habilitar todo»,
# prueba el nivel 2, prende la hora de dormir (de noche sólo el Reloj, una
# ráfaga de 5 minutos sin contraseña, el final de la ráfaga y la alarma del
# final de la noche, y apagada vuelve todo), abre Ajustes desde la
# administración, reinicia (y los hábitos siguen), cuenta las reglas de
# Inicio fijo, fuerza tres crashes y usa la salida por cable.
#
# El nivel 2: el navegador del emulador (WebView Browser Tester) pausado por
# el Device Owner, sin abrirse, sin aparecer en ninguna lista y sin poder
# desinstalarse; las políticas de Chrome guardadas; «Páginas bloqueadas» suma
# y saca una página, y no suma una de las fijas; dos apps de prueba que la
# corrida arma con aapt2 (un navegador y una «NewPipe») quedan pausadas apenas
# se instalan; y no hay «Dejar de ser Device Owner». Se mira también con
# Slowtown en pausa, con todo habilitado, después de reiniciar y con la
# guardia de crashes abierta. La salida por cable tiene que rechazar a
# cualquiera que no sea adb, sacar el nivel 2, el bloqueo y el Inicio fijo y
# dejar a Slowtown en pausa; después se prueba también liberar.sh --forzar.
# Después de cada salida se vuelve a provisionar, y el nivel 2 vuelve solo.
#
# Cada verificación imprime ✅ o ❌. Después de una falla la prueba sigue, salvo
# que sin eso lo que viene no pruebe nada; si hubo alguna ❌, termina con
# código 1.
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
# Sólo corre contra un emulador, y sólo si hay uno solo: libera y vuelve a
# provisionar Device Owner, reinicia, fuerza crashes, instala apps de prueba
# y desinstala y vuelve a instalar el navegador del emulador. Un teléfono
# conectado queda de lado: todo va con -s y ANDROID_SERIAL.
#
# Mientras corre, no tocar el emulador: la prueba maneja la pantalla.

# Sin -e a propósito: una verificación que falla se anota y la prueba sigue.
set -uo pipefail

RAIZ=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
PAQUETE=com.slowtown.launcher
INICIO_COMPONENTE="$PAQUETE/.HomeActivity"
# Gradle corre en el JDK 26 del sistema: de Java 21 sólo está el runtime (CLAUDE.md).
JAVA_HOME_GRADLE=/usr/lib/jvm/java-26-openjdk
SDK="${ANDROID_HOME:-$HOME/Android/Sdk}"
LLAVE_DEBUG="$HOME/.android/debug.keystore"   # firma las apps de prueba (clave «android», alias androiddebugkey)

CLAVE="${SLOWTOWN_CLAVE:-prueba1234}"
# Por defecto, el directorio de trabajo de la sesión en que se escribió esta
# prueba; conviene pasarle otro con SLOWTOWN_CAPTURAS.
CAPTURAS="${SLOWTOWN_CAPTURAS:-/tmp/claude-1000/-home-imgordo-Documents-proyectos-Slowtown/a0220bbf-933f-4f77-a59f-1051719f2301/scratchpad/e2e}"
ATENDER_XY="${SLOWTOWN_ATENDER_XY:-542,459}"

# ── Textos de Slowtown ──────────────────────────────────────────────────────
# Copiados de app/src/main/java/com/slowtown/launcher/ui/ y de HomeActivity.kt
# (los carteles de Inicio sin apps). Si cambia la redacción en la app, se
# cambia acá y en ningún otro lado.
TXT_HORA_RE='^[0-9]{1,2}:[0-9]{2}$'          # la hora de Inicio, que se mantiene apretada
TXT_FECHA_RE='^[^ ]+ [0-9]{1,2} de [^ ]+$'   # «Lunes 19 de octubre»
TXT_INICIO_SIN_APPS='Deslizá para arriba para ver todas las apps, y mantené apretada una para ponerla acá.'
TXT_INICIO_SIN_PERMITIDAS='Mantené apretada la hora para elegir las apps permitidas.'   # activo y sin permitidas
TXT_INICIO_FALLOS='se colgó varias veces seguidas y se pausó solo'   # un pedazo del aviso en Inicio
TXT_TODAS='↑ todas las apps'                 # al pie de Inicio: abre la lista de todas las apps
TXT_CREAR_CLAVE='Elegí una contraseña'
TXT_GUARDAR='Guardar'
TXT_ENTRAR='Entrar'
TXT_CLAVE_MAL='No es esa.'
TXT_CLAVE_ESPERA='Demasiados intentos'
TXT_ADMIN='Administración'
TXT_ADMIN_FALLOS='Modo a prueba de fallos'
TXT_SALIR_FALLOS='Ya lo revisé: salir del modo a prueba de fallos'
TXT_ELEGIR_APPS='Elegir las apps permitidas'
TXT_ELEGIR_WIDGET='Elegir el widget de Inicio'
TXT_ACTIVAR='Activar Slowtown'
TXT_PAUSAR='Pausar Slowtown'
TXT_IR_AJUSTES='Ir a Ajustes'
TXT_APPS_TITULO='Apps permitidas'
TXT_APPS_PISTA='Con Slowtown activo, las que no marques no se abren. Las llamadas y los SMS entran igual. Cuáles se ven en Inicio se elige aparte, deslizando para arriba en Inicio.'
TXT_VOLVER='Volver'
TXT_LISTO_RE='^Listo \(([0-9]+)\)$'
TXT_EN_INICIO='en Inicio'                    # la nota de las apps de Inicio en la lista de todas
TXT_PONER_EN_INICIO='Poner en Inicio'        # el diálogo de una app mantenida apretada en esa lista
TXT_SACAR_DE_INICIO='Sacar de Inicio'
TXT_CANCELAR='Cancelar'
TXT_WIDGET_TITULO='Widget de Inicio'
TXT_SACAR_WIDGET='Sacar el widget'
TXT_CARPETAS='Carpetas'                      # en Administración
TXT_NUEVA_CARPETA='Nueva carpeta'
TXT_CREAR='Crear'
TXT_BORRAR='Borrar'
TXT_MOVER_A='Mover a '                       # el diálogo de una app: «Mover a Utilitario»
TXT_HABILITAR='Habilitar todo por 5 minutos' # en la pantalla de la contraseña, sin contraseña
TXT_YA_USASTE='Ya usaste las 2 de estas 12 horas'
TXT_HABILITADO='Todo habilitado hasta las'   # el aviso de Inicio mientras dura
TXT_PAGINAS='Páginas bloqueadas'             # en Administración, y el título de su pantalla
TXT_LAS_TUYAS='Las tuyas'                    # las páginas del nivel 1 van entre este título...
TXT_SIEMPRE_BLOQUEADAS='Siempre bloqueadas'  # ...y éste, el de las del nivel 2
TXT_SIN_PAGINAS='Todavía no sumaste ninguna.'
TXT_AGREGAR=Agregar
TXT_SACAR=Sacar
TXT_YA_FIJA='Ésa ya está bloqueada siempre.'
TXT_SOLTAR='Dejar de ser Device Owner'       # el botón que ya no está en la administración
TXT_SOLO_CABLE='sólo por cable'              # un pedazo de lo que dice ahora, al pie de la administración
TXT_SIN_COINCIDENCIAS='No hay ninguna app con ese nombre.'   # el buscador de la lista de todas
TXT_PANTALLA_RE='^Hoy: (.+) de pantalla$'    # bajo la fecha, con acceso al uso: «Hoy: 1 h 5 min de pantalla»
TXT_MENOS_DE_UN_MINUTO='menos de un minuto'  # el tiempo de pantalla, antes del primer minuto
TXT_HOY_MEDITAR='Meditación de hoy'          # los botones de hoy de los hábitos, por su descripción
TXT_HOY_EJERCICIO='Ejercicio de hoy'
TXT_FILA_HABITO=', últimos 7 días: '         # «Meditar, últimos 7 días: 1 de 2. Ver el resumen»
TXT_VER_RESUMEN='. Ver el resumen'
TXT_HABITOS_TITULO='Hábitos'                 # el resumen
TXT_HABITOS_PISTA='Tocá un día para marcarlo o desmarcarlo.'
TXT_ELEGIR_HABITOS='Elegir hábitos'           # el botón del resumen y el título de su pantalla
TXT_HABITOS_TOPE='Ya hay 4 en Inicio: sacá uno para sumar otro.'
TXT_HABITOS_UNO='Tiene que quedar al menos uno.'
TXT_HABITOS_WIDGET='Hábitos de Slowtown'     # la primera opción de «Elegir el widget de Inicio»
TXT_HABITOS_WIDGET_SUB='Meditar y ejercicio'
TXT_DORMIR='Hora de dormir'                  # el botón de la administración y el título de su pantalla
TXT_DORMIR_PRENDIDA=Prendida                 # su interruptor
TXT_EMPIEZA=Empieza                          # sus dos filas de hora, de a 15 minutos con − y +
TXT_TERMINA=Termina
TXT_MAS_TEMPRANO='Más temprano: '            # el − y el + de cada fila, por su descripción: «Más temprano: Empieza»
TXT_MAS_TARDE='Más tarde: '
TXT_DORMIR_HASTA='Hora de dormir hasta las ' # el aviso de Inicio de noche
TXT_USAR_5='Usar 5 minutos'                  # la ráfaga, en Inicio, sin contraseña
TXT_RAFAGA='Ráfaga de 5 minutos: hasta las ' # el aviso de Inicio mientras dura
DORMIR_DESDE=$((23 * 60))                    # la noche de fábrica, en minutos del día: de 23:00...
DORMIR_HASTA=$((7 * 60))                     # ...a 07:00
INICIALES=LMXJVSD                            # los días del widget de hábitos, de lunes a domingo
DIAS_ES=(lunes martes miércoles jueves viernes sábado domingo)   # las celdas del resumen:
MESES_ES=(enero febrero marzo abril mayo junio julio agosto septiembre octubre noviembre diciembre)   # «Meditar, domingo 18 de octubre: no»
HABITOS=(meditar ejercicio)                  # como los guarda Slowtown: habit_meditar y habit_ejercicio
declare -A HABITO_NOMBRE=([meditar]=Meditar [ejercicio]=Ejercicio)
declare -A HABITO_META=([meditar]=2 [ejercicio]=3)
declare -A HABITO_BOTON=([meditar]="$TXT_HOY_MEDITAR" [ejercicio]="$TXT_HOY_EJERCICIO")
CARPETA=Utilitario                           # la carpeta que se crea (en pantalla, en mayúsculas)
APP_CARPETA=Clock                            # la que se mueve a la carpeta
PAGINA=ejemplo.com                           # la que se suma y se saca en «Páginas bloqueadas»
PAGINA_FIJA='https://www.youtube.com/watch?v=x'   # cae en youtube.com, del nivel 2: no se suma

# ── Textos de scripts/liberar.sh ────────────────────────────────────────────
TXT_SOLTADA_VUELVEN='Se pueden abrir otra vez:'   # soltada por Slowtown: las que despausó, en una línea
TXT_FORZADA_VUELVE='ya se puede abrir:'           # con --forzar: una línea por cada una

# ── Textos del emulador ─────────────────────────────────────────────────────
# La imagen AOSP de Android 12 está en inglés.
APPS_INICIO=(Phone Messaging Camera Clock)   # las que se permiten (y, sin tocar nada, las de Inicio)
APP_RELOJ=Clock                              # la app permitida que se abre
APP_QUITAR=Camera                            # la que se saca de Inicio y se vuelve a poner
APP_AJUSTES=Settings                         # ni en el selector de apps ni, activo, en la lista de todas
APP_CALENDARIO=Calendar                      # instalada y no permitida: con Slowtown activo, no se lista
BUSQUEDA=clo                                 # lo que se escribe en el buscador: deja sólo APP_RELOJ
WIDGET_RELOJ='Analog clock'                  # widget del Reloj de AOSP, sin pantalla de configuración
PAQUETE_RELOJ=com.android.deskclock          # el que dibuja ese widget (y el reloj que queda de noche)
PAQUETE_CAMARA=com.android.camera2           # el de APP_QUITAR, que de noche no se abre
CAMARA_ACTIVIDAD=$PAQUETE_CAMARA/com.android.camera.CameraLauncher
# El nivel 2 pausa todo navegador que no sea Chrome, y esta imagen no trae
# Chrome: el único es éste, el que trae AOSP para probar WebView.
NAVEGADOR=org.chromium.webview_shell
NAVEGADOR_ACTIVIDAD=$NAVEGADOR/.WebViewBrowserActivity
APP_NAVEGADOR='WebView Browser Tester'
BUSQUEDA_NAVEGADOR=webview                   # lo que lo encontraría en el buscador si se listara
# Las políticas de Chrome las guarda Android y no Chrome: están aunque Chrome
# no esté instalado. Es de system: se lee con root.
RESTRICCIONES_CHROME=/data/system/users/0/res_com.android.chrome.xml
CHROME=com.android.chrome                    # no está en esta imagen
# Dos apps de prueba que la corrida arma e instala (armar_apks_de_prueba): un
# navegador cualquiera y una de Fixed.APPS, con su paquete.
NAVEGADOR_PRUEBA=com.slowtown.prueba.navegador
APP_VETADA=org.schabi.newpipe
APP_VETADA_NOMBRE='NewPipe de prueba'
BUSQUEDA_VETADA=newpipe
TXT_ATENDER=Answer                           # el aviso de llamada (en pantalla dice ANSWER)
TXT_GESTIONAR=Manage                         # botón al pie del panel de notificaciones
ID_GESTIONAR=id/manage_text                  # el mismo (dice History si el historial está prendido)
TXT_OK=OK                                    # cartel «App is not available» y aviso «Can't open this app»
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
FOCO_PERMISO_WIDGET=AllowBindAppWidgetActivity   # el diálogo de Ajustes que pide permiso para un widget
FOCO_NAVEGADOR=$NAVEGADOR/
# «Can't open this app», de Ajustes: así frena Android una app que pausó el
# Device Owner, con Slowtown activo o no.
FOCO_APP_PAUSADA=ActionDisabledByAdminDialog
RELOJ_ACTIVIDAD=com.android.deskclock/.DeskClock
CALENDARIO=com.android.calendar/.AllInOneActivity

# Números para la radio simulada del emulador.
NUM_CALENTAMIENTO=2235550001
NUM_LLAMADA=2235554321
NUM_SMS=2235551111

UI_REMOTO=/data/local/tmp/slowtown-ui.xml

# Se completan al arrancar y en el paso 0.
SERIE=''
ADB=(adb)
ANCHO=1080
ALTO=2340
SALIDA=''
UI_XML=''

PASO=0
FALLAS=()
N_OK=0     # las ✅; con las de FALLAS, el total de verificaciones del resumen
N_CAPTURA=0
CARTEL=0
LISTA=''   # la última lista que vio esperar_apps, en una línea
RESTRICCIONES=''   # las últimas políticas de Chrome que leyó politicas_chrome
N_POLITICAS=0
HABITO_HOY=''      # lo que marca el paso 8b: ejercicio hoy y meditar ayer (fechas ISO)
HABITO_AYER=''
FALTA=''           # lo último que vio esperar_sin_faltas que no estaba como tenía que estar
declare -A CAMBIAR=() VISTAS=()

# ── Lectura de pantallas ────────────────────────────────────────────────────
# Busca en el XML de «uiautomator dump». Modos:
#   texto|contiene|regex|id CONSULTA   centro "x y" del primer nodo que calza
#   campo N                            centro del N-ésimo campo de texto
#   textos                             x, y, borde de arriba, de abajo y texto de cada nodo,
#                                      salvo lo de un widget (su etiqueta, su hora): no son apps
#   filas                              por cada fila que se mantiene apretada (las apps de la
#                                      lista de todas): su primer texto, tab y los demás
#   widgets                            por cada widget: etiqueta, paquetes de lo que dibuja y
#                                      sus textos, separados por tab (sale 1 si no hay ninguno)
#   hay REGEX                          los nodos cuyo id o texto calzan (sale 1 si ninguno)
#   libre TIPO CONSULTA PAQUETE        como texto/regex, pero sólo en ese paquete y sale 4
#                                      si otra ventana (un aviso) tapa ese punto
#   marcado CONSULTA                   «checkable checked» del primer nodo con ese texto o
#                                      descripción, p. ej. «true false» (sale 1 si no está)
#   valido                             si el XML se puede leer
PY_UI=$(cat <<'PY'
import re
import sys
import xml.etree.ElementTree as ET

# Así se presenta cualquier widget para accesibilidad: AppWidgetHostView fija
# ese nombre de clase, y de descripción pone la etiqueta del widget. Lo de
# adentro lo dibuja la app del widget, y sus nodos traen el paquete de esa app
# aunque estén en la ventana de Slowtown.
WIDGET = 'android.appwidget.AppWidgetHostView'


def caja(n):
    b = [int(v) for v in re.findall(r'-?\d+', n.get('bounds', ''))]
    return b if len(b) == 4 and b[2] > b[0] and b[3] > b[1] else None


def nodos(archivo, sin_widgets=False):
    raiz = ET.parse(archivo).getroot()
    fuera = set()
    if sin_widgets:
        for w in raiz.iter('node'):
            if w.get('class') == WIDGET:
                fuera.update(w.iter('node'))
    for n in raiz.iter('node'):
        b = caja(n)
        if b and n not in fuera:
            yield n, b


def centro(b):
    return (b[0] + b[2]) // 2, (b[1] + b[3]) // 2


def textos(n):
    return [' '.join(t.split()) for t in (n.get('text'), n.get('content-desc')) if t and t.strip()]


def adentro(n):
    """Los nodos visibles de adentro de n, sin n."""
    return [m for m in n.iter('node') if m is not n and caja(m)]


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
    lista = list(nodos(archivo, sin_widgets=(modo == 'textos')))
    if modo == 'textos':
        for n, b in lista:
            x, y = centro(b)
            for t in textos(n):
                print(f'{x}\t{y}\t{b[1]}\t{b[3]}\t{t}')
        return 0
    if modo == 'filas':
        # Las carpetas y los botones sólo se tocan; el buscador, que también
        # se puede mantener apretado, es el campo de texto.
        for n, b in lista:
            if n.get('long-clickable') != 'true' or (n.get('class') or '').endswith('EditText'):
                continue
            dichos = textos(n) + [t for m in adentro(n) for t in textos(m)]
            if dichos:
                print(dichos[0] + '\t' + (' · '.join(dichos[1:]) or '-'))
        return 0
    if modo == 'widgets':
        hallados = []
        for w, b in lista:
            if w.get('class') != WIDGET:
                continue
            de = adentro(w)
            paquetes = sorted({m.get('package') for m in de if m.get('package')})
            # Con «-» en los vacíos, para que bash no corra las columnas.
            hallados.append('\t'.join([
                ' '.join(textos(w)) or '-',
                ','.join(paquetes) or '-',
                ' / '.join(t for m in de for t in textos(m)) or '-',
            ]))
        print('\n'.join(hallados))
        return 0 if hallados else 1
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
    if modo == 'marcado':
        cumple = criterio('texto', args[0])
        for n, b in lista:
            if cumple(n):
                print(n.get('checkable') or 'false', n.get('checked') or 'false')
                return 0
        return 1
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
ok()    { N_OK=$((N_OK + 1)); echo "  ✅ $*"; }
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
    echo "❌ Fallaron ${#FALLAS[@]} de $((N_OK + ${#FALLAS[@]})) verificaciones:"
    printf '   %s\n' "${FALLAS[@]}"
  else
    echo "✅ Todo anduvo: $N_OK verificaciones."
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
c_teclado() { echo "dumpsys input_method | grep -q 'mInputShown=true'"; }
c_resumida() { echo "dumpsys activity activities | grep -q 'ResumedActivity.*$1'"; }   # una pantalla de ese paquete en primer plano
c_pausada() { echo "dumpsys package $1 | grep -q 'User 0: .*suspended=true'"; }
# Una sesión de administración agranda la lista de lock task a todo lo
# instalado: con Ajustes adentro («u0:[..., com.android.settings, ...]»), hay sesión.
c_sesion() { echo "dumpsys activity activities | grep -q 'u0:\[.*com\.android\.settings[],]'"; }
c_en_lock_task() { echo "dumpsys activity activities | grep -q 'u0:\[.*${1//./\\.}[],]'"; }   # ese paquete en la lista de lock task (mLockTaskPackages)

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
bloqueos_desde() {   # cuántas veces lock task frenó un arranque desde la marca
  # Frena de dos maneras: con el error de siempre, o (con
  # BLOCK_ACTIVITY_START_IN_TASK) cambiando la pantalla pedida por su cartel,
  # sin ningún error en logcat. Se cuentan las dos.
  local out
  out=$(adb_t 60 logcat -d -T "$1" 2>/dev/null)
  grep -cE 'Attempted Lock Task Mode violation|BlockedAppActivity' <<<"$out"
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
uid_adb() { adb_t 10 shell id -u 2>/dev/null | tr -d '\r'; }   # 0 si adbd es root, 2000 si no

# Lo que guarda Android en /data/system es XML binario: abx2xml lo pasa a
# texto. Hace falta root.
package_restrictions() {   # package-restrictions.xml, donde están las reglas de Inicio fijo
  disp "f=/data/local/tmp/slowtown-pr.xml; abx2xml /data/system/users/0/package-restrictions.xml \$f 2>/dev/null || cp /data/system/users/0/package-restrictions.xml \$f; cat \$f; rm -f \$f"
}
reglas_inicio_fijo() {   # cuántas reglas de Inicio fijo tiene Slowtown en el package-restrictions.xml de stdin
  awk '
    /<persistent-preferred-activities[^>]*\/>/ { next }
    /<persistent-preferred-activities/ { dentro = 1; next }
    /<\/persistent-preferred-activities>/ { dentro = 0 }
    dentro && /<item / && /com\.slowtown\.launcher\// { n++ }
    END { print n + 0 }'
}
politicas_chrome() {   # deja en RESTRICCIONES las políticas de Chrome que guarda Android (sale 1 sin root)
  # Sin archivo, RESTRICCIONES queda vacío: Android lo borra cuando no queda
  # ninguna. Cada lectura queda también en $SALIDA.
  RESTRICCIONES=$(disp "ls /data/system/users/0 >/dev/null 2>&1 || exit 3; [ -e $RESTRICCIONES_CHROME ] || exit 0; f=/data/local/tmp/slowtown-res.xml; abx2xml $RESTRICCIONES_CHROME \$f 2>/dev/null || cp $RESTRICCIONES_CHROME \$f; cat \$f; rm -f \$f") || return 1
  N_POLITICAS=$((N_POLITICAS + 1))
  printf '%s\n' "$RESTRICCIONES" >"$SALIDA/politicas-chrome-$PASO-$N_POLITICAS.xml"
}
politica_chrome() {   # politica_chrome CLAVE: su valor en RESTRICCIONES, o sus valores, uno por línea
  # Un valor va en la misma línea que la clave («<entry key="X" type="i">1</entry>»);
  # una lista, en líneas «<value>...</value>» hasta «</entry>».
  awk -v k="$1" '
    /<entry key="/ {
      clave = $0
      sub(/.*<entry key="/, "", clave)
      sub(/".*/, "", clave)
      if (clave == k && /<\/entry>/) { v = $0; sub(/^.*<entry [^>]*>/, "", v); sub(/<\/entry>.*/, "", v); print v }
      next
    }
    /<value>/ && clave == k { v = $0; sub(/.*<value>/, "", v); sub(/<\/value>.*/, "", v); print v }
    /<\/entry>/ { clave = "" }' <<<"$RESTRICCIONES"
}

# Las apps que pausa el nivel 2 quedan suspendidas a nombre de «android», que
# es como suspende el Device Owner: adb no lo puede sacar.
pausa_de() {   # pausa_de PAQUETE: a nombre de quién está pausada («android»), «no», o «?» si no está
  # «dumpsys package» trae una línea «User 0: ... suspended=...» y, si está
  # pausada, otra con «suspendingPackage=».
  local out
  out=$(disp dumpsys package "$1")
  awk '
    /^ *User 0: .*suspended=/ && !visto { visto = 1; pausada = / suspended=true/; next }
    visto && pausada && /suspendingPackage=/ { sub(/.*suspendingPackage=/, ""); sub(/[ ,}].*/, ""); print; dicho = 1; exit }
    END { if (!dicho) print (!visto ? "?" : pausada ? "alguien" : "no") }' <<<"$out"
}
hay_paquete() {   # hay_paquete PAQUETE: si está instalado para el usuario 0
  local out
  out=$(disp pm path "$1")
  grep -q '^package:' <<<"$out"
}

# Las apps de prueba no tienen código (hasCode=false) y su pantalla es
# android.app.Activity, que trae Android: alcanza con el manifiesto. aapt2 lo
# empaqueta, zipalign lo alinea y apksigner lo firma con la clave de debug de
# la máquina. Sin firma v4, así adb instala de la manera común.
armar_apks_de_prueba() {   # arma $SALIDA/apk/navegador.apk y vetada.apk (sale 1 si falta algo)
  local dir="$SALIDA/apk" bt jar nombre
  bt=$(ls -d "$SDK"/build-tools/*/ 2>/dev/null | sort -V | tail -n 1)
  jar=$(ls -1 "$SDK"/platforms/*/android.jar 2>/dev/null | sort -V | tail -n 1)
  if [[ ! -x ${bt}aapt2 || ! -x ${bt}zipalign || ! -x ${bt}apksigner || ! -f $jar || ! -f $LLAVE_DEBUG ]]; then
    nota "faltan las build-tools, un android.jar o $LLAVE_DEBUG"
    return 1
  fi
  mkdir -p "$dir"
  cat >"$dir/navegador.xml" <<EOF
<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="$NAVEGADOR_PRUEBA">
    <uses-sdk android:minSdkVersion="24" android:targetSdkVersion="31" />
    <application android:label="Navegador de prueba" android:hasCode="false">
        <activity android:name="android.app.Activity" android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.VIEW" />
                <category android:name="android.intent.category.DEFAULT" />
                <category android:name="android.intent.category.BROWSABLE" />
                <data android:scheme="http" />
                <data android:scheme="https" />
            </intent-filter>
        </activity>
    </application>
</manifest>
EOF
  cat >"$dir/vetada.xml" <<EOF
<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="$APP_VETADA">
    <uses-sdk android:minSdkVersion="24" android:targetSdkVersion="31" />
    <application android:label="$APP_VETADA_NOMBRE" android:hasCode="false">
        <activity android:name="android.app.Activity" android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
EOF
  for nombre in navegador vetada; do
    "${bt}aapt2" link -o "$dir/$nombre-sin-alinear.apk" -I "$jar" --manifest "$dir/$nombre.xml" \
      --version-code 1 --version-name 1 &&
      "${bt}zipalign" -f 4 "$dir/$nombre-sin-alinear.apk" "$dir/$nombre-alineado.apk" &&
      "${bt}apksigner" sign --ks "$LLAVE_DEBUG" --ks-pass pass:android --ks-key-alias androiddebugkey \
        --key-pass pass:android --v4-signing-enabled false --out "$dir/$nombre.apk" "$dir/$nombre-alineado.apk" ||
      return 1
  done >>"$dir/armado.log" 2>&1 </dev/null
}

afirmar_pausada() {   # afirmar_pausada QUÉ: que el navegador siga pausado por el Device Owner
  local p
  p=$(pausa_de "$NAVEGADOR")
  case $p in
    android) ok "$1: $APP_NAVEGADOR sigue pausado por el Device Owner" ;;
    no) mal "$1: $APP_NAVEGADOR no está pausado" ;;
    '?') mal "$1: no encuentro $NAVEGADOR en este emulador; actualizá la constante" ;;
    *) mal "$1: $APP_NAVEGADOR está pausado por «$p», no por el Device Owner" ;;
  esac
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
  # con --windows ve también las del sistema, como los avisos de llamada. Si
  # la pantalla no se aquieta (un cursor titilando en un campo de texto),
  # uiautomator no escribe nada y no avisa: por eso se borra el archivo antes,
  # y si falta es que no estaba quieta y se prueba otra vez.
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
  # la hora y se llevaría el toque: se espera a que se vaya solo. Sirve igual
  # en la lista de todas las apps, que es la misma ventana.
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

afirmar_en_inicio() {   # afirmar_en_inicio QUÉ [ESTADO]: foco en Slowtown y lock task en ESTADO (LOCKED si no se dice)
  local que=$1 esperado=${2:-LOCKED} f e
  esperar 15 "$(c_foco "$FOCO_INICIO")"
  f=$(foco)
  e=$(estado)
  if [[ $f == *"$FOCO_INICIO"* && $e == "$esperado" ]]; then
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
  # «ui textos» ya deja afuera lo de un widget de otra app; lo que va al pie,
  # debajo de «↑ todas las apps» (ese widget o los hábitos), tampoco son apps.
  local x y arriba abajo t pie
  pie=$(ui textos | awk -F'\t' -v t="$TXT_TODAS" '$5 == t { print $3; exit }')
  while IFS=$'\t' read -r x y arriba abajo t; do
    [[ -n $pie ]] && (( arriba >= pie )) && continue
    [[ $t =~ $TXT_HORA_RE || $t =~ $TXT_FECHA_RE || $t =~ $TXT_PANTALLA_RE ]] && continue
    [[ $t == "$TXT_INICIO_SIN_APPS" || $t == "$TXT_INICIO_SIN_PERMITIDAS" || $t == "$TXT_TODAS" ]] && continue
    [[ $t == *"$TXT_INICIO_FALLOS"* ]] && continue
    printf '%s\n' "$t"
  done < <(ui textos)
}

esperar_lista_inicio() {   # hasta que Inicio muestre sus apps o el cartel de ayuda
  # La lista carga en segundo plano: recién vuelto a Inicio puede estar vacía
  # sin que falte nada.
  local fin=$((SECONDS + 15))
  while (( SECONDS < fin )); do
    if volcar_ui && { [ -n "$(etiquetas_inicio)" ] || ui texto "$TXT_INICIO_SIN_APPS" >/dev/null ||
      ui texto "$TXT_INICIO_SIN_PERMITIDAS" >/dev/null; }; then
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

intento_navegador() {   # intento_navegador QUÉ [ESTADO]: intenta abrir el navegador pausado y vuelve a Inicio
  # Pausado no es lo mismo que fuera de la lista de lock task: antes de que
  # lock task mire, Android cambia el pedido por su aviso «Can't open this
  # app», que es de Ajustes. Con Slowtown activo lock task frena después el
  # aviso; en pausa o con todo habilitado, el aviso se ve y se cierra con OK.
  # Que haya aparecido el aviso (en logcat) dice que lo frenó la pausa.
  local que=$1 esperado=${2:-LOCKED} marca salida log abierto=0 visto=' (no llega a verse: lock task frena también el aviso)'
  marca=$(marca_logcat)
  salida=$(disp am start -a android.intent.action.VIEW -d http://example.com -n "$NAVEGADOR_ACTIVIDAD")
  if grep -qE 'unable to resolve|does not exist' <<<"$salida"; then
    mal "$que: $NAVEGADOR no existe en este emulador; actualizá la constante"
    return 1
  fi
  # Que no llegue a mostrarse nunca, no sólo que al final no esté.
  sostener 4 "! $(c_foco "$FOCO_NAVEGADOR") && ! $(c_resumida "$NAVEGADOR")" || abierto=1
  if [[ $(foco) == *"$FOCO_APP_PAUSADA"* ]]; then
    visto=": se ve el aviso «Can't open this app» y se cierra con OK"
    captura aviso-app-pausada
    tocar_boton "$ID_OK" "$TXT_OK" || adb_t 20 shell input keyevent KEYCODE_BACK >/dev/null 2>&1
    esperar 5 "! $(c_foco "$FOCO_APP_PAUSADA")"
  fi
  # Si algo quedó frenado por lock task, el cartel mantiene viva la tarea: se cierra igual.
  cerrar_cartel_bloqueo
  log=$(adb_t 60 logcat -d -T "$marca" 2>/dev/null)
  ir_a_inicio
  if (( abierto )); then
    mal "$que: $APP_NAVEGADOR se abrió igual"
  elif grep -q "$FOCO_APP_PAUSADA" <<<"$log"; then
    ok "$que: $APP_NAVEGADOR no se abre, Android lo cambia por su aviso de app pausada$visto"
  else
    mal "$que: $APP_NAVEGADOR no se abrió, pero no lo frenó la pausa (no apareció el aviso de Android)"
  fi
  afirmar_en_inicio "$que: de vuelta en Slowtown y $esperado" "$esperado"
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

en_una_linea() { sort -u | awk 'NR > 1 { printf ", " } { printf "%s", $0 }'; }   # «a, b, c»

esperar_apps() {   # esperar_apps SEGUNDOS FUNCIÓN APP...: hasta que FUNCIÓN liste justo esas apps
  # FUNCIÓN lee el último volcado (etiquetas_inicio, etiquetas_cajon...). En
  # LISTA queda lo último que listó. Sale 1 si nunca fue eso, y 2 si
  # uiautomator no pudo volcar la pantalla ni una vez.
  local seg=$1 fn=$2 fin esperadas vista volco=0
  shift 2
  esperadas=$(printf '%s\n' "$@" | sort -u)
  fin=$((SECONDS + seg))
  LISTA='(uiautomator no pudo volcar la pantalla)'
  while :; do
    if volcar_ui; then
      volco=1
      vista=$("$fn" | sort -u)
      LISTA=$(en_una_linea <<<"$vista")
      [ "$vista" = "$esperadas" ] && return 0
    fi
    (( SECONDS < fin )) || break
    sleep 0.5
  done
  (( volco )) && return 1
  return 2
}

# La lista de todas las apps es otra pantalla de la misma actividad: el foco
# no cambia, se la reconoce por lo que muestra. Arriba pueden ir carpetas
# (cajas con el nombre en mayúsculas), que sólo se tocan; las apps son las
# filas que se mantienen apretadas.
abrir_cajon() {   # desde Inicio, con «↑ todas las apps», hasta la lista de todas las apps
  tocar_en_inicio texto "$TXT_TODAS" || return 1
  # No tiene título: la delata el buscador, el único campo de texto.
  esperar_ui 10 campo 1
}

etiquetas_cajon() { ui filas | cut -f1; }   # las apps de la lista en el último volcado

favoritas_en_cajon() {   # las que la lista marca «en Inicio» en el último volcado
  # Buscando, la nota lleva antes la carpeta: «BILLETERAS · en Inicio».
  local nombre nota
  while IFS=$'\t' read -r nombre nota; do
    [[ $nota == *"$TXT_EN_INICIO" ]] && printf '%s\n' "$nombre"
  done < <(ui filas)
}

accion_en_cajon() {   # accion_en_cajon APP ACCIÓN: mantiene apretada APP en la lista y elige ACCIÓN
  tocar_en_inicio texto "$1" 1500 || return 1
  # El diálogo lleva de título el nombre de la app. Si no ofrece ACCIÓN, se
  # cierra con «Cancelar» para que no quede tapando la lista.
  if esperar_ui 10 texto "$2" && ui texto "$1" >/dev/null && tocar texto "$2"; then
    return 0
  fi
  tocar texto "$TXT_CANCELAR"
  return 1
}

volver_del_cajon() {   # Atrás hasta Inicio
  # Con el teclado abierto, el primer Atrás sólo lo cierra; con el diálogo de
  # una app, lo mismo.
  local vuelta
  for vuelta in 1 2 3; do
    adb_t 20 shell input keyevent KEYCODE_BACK >/dev/null 2>&1
    esperar_ui 5 texto "$TXT_TODAS" && return 0
  done
  return 1
}

# «dumpsys appwidget» termina con «Widgets:», «Hosts:» y «Grants:». Cada
# widget trae «host=HostId{..., pkg:PAQUETE}» y, si está armado,
# «provider=ProviderId{..., cmp:ComponentInfo{paquete/clase}}»; cada permiso
# de «appwidget grantbind», una línea «[0] user=0 package=PAQUETE».
puede_armar_widgets() {   # si Android deja a Slowtown armar widgets sin preguntar
  local out
  out=$(disp dumpsys appwidget)
  awk -v p="package=$PAQUETE" '/^Grants:/ { g = 1; next } g && $NF == p { hay = 1 } END { exit !hay }' <<<"$out"
}
widgets_armados() {   # los widgets que Android tiene armados para Slowtown, como paquete/clase
  local out
  out=$(disp dumpsys appwidget)
  awk -v host="pkg:$PAQUETE}" '
    /^Widgets:/ { dentro = 1; next }
    /^[^ ]/ { dentro = 0 }
    !dentro { next }
    /^  \[[0-9]+\] id=/ { mio = 0 }
    /^    host=/ { mio = (index($0, host) > 0) }
    /^    provider=/ && mio { sub(/.*ComponentInfo[{]/, ""); sub(/[}].*/, ""); print }' <<<"$out"
}

esperar_widget() {   # esperar_widget SEGUNDOS: hasta que la pantalla muestre un widget, y qué muestra
  local fin=$((SECONDS + $1)) w
  until volcar_ui && w=$(ui widgets); do
    (( SECONDS < fin )) || return 1
    sleep 0.5
  done
  printf '%s\n' "$w"
}
esperar_sin_widget() {   # esperar_sin_widget SEGUNDOS: hasta que no muestre ninguno
  local fin=$((SECONDS + $1))
  until volcar_ui && ! ui widgets >/dev/null; do
    (( SECONDS < fin )) || return 1
    sleep 0.5
  done
}

# En «Páginas bloqueadas», las que se suman (el nivel 1) van en filas con su
# botón «Sacar», entre «Las tuyas» y «Siempre bloqueadas». Las del nivel 2 son
# un solo texto, todas juntas, y el campo de texto está arriba de todo.
paginas_tuyas() {   # las páginas sumadas en el último volcado, una por línea
  local x y arriba abajo t lo='' hi=''
  while IFS=$'\t' read -r x y arriba abajo t; do
    [ "$t" = "$TXT_LAS_TUYAS" ] && lo=$abajo
    [ "$t" = "$TXT_SIEMPRE_BLOQUEADAS" ] && hi=$arriba
  done < <(ui textos)
  [[ -n $lo && -n $hi ]] || return 1
  while IFS=$'\t' read -r x y arriba abajo t; do
    (( arriba >= lo && abajo <= hi )) || continue
    [[ $t == "$TXT_SACAR" || $t == "$TXT_SIN_PAGINAS" ]] && continue
    printf '%s\n' "$t"
  done < <(ui textos)
}
escribir_pagina() {   # escribir_pagina TEXTO: lo escribe en el campo de «Páginas bloqueadas» y toca «Agregar»
  # Comillas simples para el shell del dispositivo: la URL trae «?» y «=».
  tocar campo 1 || return 1
  esperar 5 "$(c_teclado)"
  sleep 1
  adb_t 20 shell "input text '$1'" >/dev/null 2>&1
  tocar texto "$TXT_AGREGAR" || return 1
  # El teclado queda abierto y taparía la lista.
  cerrar_teclado
}
cerrar_teclado() {   # Atrás sólo si el teclado está abierto: si no, Atrás cambiaría de pantalla
  esperar 1 "$(c_teclado)" || return 0
  adb_t 20 shell input keyevent KEYCODE_BACK >/dev/null 2>&1
  esperar 5 "! $(c_teclado)"
}

esperar_que_no() {   # esperar_que_no SEGUNDOS TIPO CONSULTA: hasta que la pantalla ya no muestre eso
  local fin=$((SECONDS + $1))
  until volcar_ui && ! ui "$2" "$3" >/dev/null; do
    (( SECONDS < fin )) || return 1
    sleep 0.5
  done
}

# ── Hábitos y tiempo de pantalla ────────────────────────────────────────────
# Los hábitos van por día, el del emulador: LocalDate.now() de Slowtown, con
# la zona del emulador. Las cuentas de fechas se hacen de este lado, con
# fechas ISO (date -u: sin horas, no hay husos que se metan).
hoy_en_el_emulador() { disp date +%F; }
dia_mas() { date -u -d "$1 $2 day" +%F; }   # dia_mas FECHA N: N días después (antes, si N es negativo)
lunes_de() { dia_mas "$1" $((1 - $(date -u -d "$1" +%u))); }   # el lunes de la semana de esa fecha
contar_entre() {   # contar_entre DESDE HASTA: cuántas de las fechas de stdin caen entre las dos
  awk -v a="$1" -v b="$2" '$0 != "" && $0 >= a && $0 <= b { n++ } END { print n + 0 }'
}
racha_de() {   # racha_de META HOY: semanas seguidas, de lunes a domingo, con la meta cumplida (fechas en stdin)
  # Como Habits.streak: la semana de ahora cuenta si ya está cumplida; si no,
  # todavía hay tiempo y se cuenta desde la anterior.
  local meta=$1 fechas lunes n=0
  fechas=$(cat)
  lunes=$(lunes_de "$2")
  (( $(contar_entre "$lunes" "$(dia_mas "$lunes" 6)" <<<"$fechas") < meta )) && lunes=$(dia_mas "$lunes" -7)
  while (( $(contar_entre "$lunes" "$(dia_mas "$lunes" 6)" <<<"$fechas") >= meta )); do
    n=$((n + 1))
    lunes=$(dia_mas "$lunes" -7)
  done
  echo "$n"
}
iniciales_de() {   # iniciales_de HOY: las de los últimos 7 días, hoy a la derecha («MXJVSDL» un lunes)
  local i u s=''
  for i in 6 5 4 3 2 1 0; do
    u=$(date -u -d "$1 -$i day" +%u)
    s+=${INICIALES:u-1:1}
  done
  echo "$s"
}
iniciales_vistas() {   # las iniciales del widget de hábitos, de izquierda a derecha, en el último volcado
  ui textos | awk -F'\t' -v l="$INICIALES" 'length($5) == 1 && index(l, $5) { print $1 "\t" $5 }' |
    sort -n | cut -f2 | tr -d '\n'
}
celda_re() {   # celda_re NOMBRE FECHA: el comienzo de la descripción de esa celda del resumen, como regex
  # «Meditar, domingo 18 de octubre: », como lo escribe Java en castellano;
  # septiembre va con las dos formas, por si es «setiembre».
  local u d m mes
  u=$(date -u -d "$2" +%u)
  d=$(date -u -d "$2" +%-d)
  m=$(date -u -d "$2" +%-m)
  mes=${MESES_ES[m - 1]}
  (( m == 9 )) && mes='(septiembre|setiembre)'
  echo "^$1, ${DIAS_ES[u - 1]} $d de $mes: "
}
dias_de_habito() {   # dias_de_habito HÁBITO: las fechas que tiene guardadas (meditar o ejercicio), una por línea
  # Un conjunto vacío Android lo escribe «<set name="..." />».
  local prefs
  prefs=$(disp run-as "$PAQUETE" cat shared_prefs/slowtown.xml)
  awk -v k="habit_$1" '
    index($0, "<set name=\"" k "\"") { if ($0 ~ /\/>/) exit; dentro = 1; next }
    dentro && /<\/set>/ { exit }
    dentro { gsub(/.*<string>|<\/string>.*/, ""); print }' <<<"$prefs" | sort
}
esperar_dia() {   # esperar_dia HÁBITO FECHA si|no: hasta que esté (o no) entre las guardadas
  # Slowtown guarda con apply(): llega al disco un momento después.
  local fin=$((SECONDS + 10)) esta
  while :; do
    if grep -qxF "$2" <<<"$(dias_de_habito "$1")"; then esta=si; else esta=no; fi
    [ "$esta" = "$3" ] && return 0
    (( SECONDS < fin )) || return 1
    sleep 0.5
  done
}
esperar_marcado() {   # esperar_marcado DESCRIPCIÓN true|false: hasta que ese botón esté (o no) marcado
  local fin=$((SECONDS + 10)) e
  until volcar_ui && e=$(ui marcado "$1") && [ "$e" = "true $2" ]; do
    (( SECONDS < fin )) || return 1
    sleep 0.5
  done
}
esperar_lejos_de_medianoche() {   # si falta poco para la medianoche del emulador, espera que pase
  # Los hábitos van por día: que no cambie en el medio de las verificaciones.
  local h m s falta
  IFS=: read -r h m s <<<"$(disp date +%H:%M:%S)"
  [[ $h =~ ^[0-9]+$ && $m =~ ^[0-9]+$ && $s =~ ^[0-9]+$ ]] || return 0
  falta=$((86400 - 10#$h * 3600 - 10#$m * 60 - 10#$s))
  if (( falta < 240 )); then
    nota "faltan $falta s para la medianoche del emulador: espero, para que el día no cambie en el medio"
    adb_t $((falta + 40)) shell "sleep $((falta + 5))" >/dev/null 2>&1
  fi
}
afirmar_habitos() {   # afirmar_habitos QUÉ: que Inicio muestre los hábitos como están guardados
  # Por hábito: «N/meta» con N de los últimos 7 días, la fila con la misma
  # cuenta, y el botón de hoy marcado si hoy está guardado. Más las iniciales.
  local que=$1 hoy h dias n esperado e marcado mal_=() dice=()
  hoy=$(hoy_en_el_emulador)
  if ! esperar_ui 10 texto "$TXT_HOY_MEDITAR"; then
    mal "$que: Inicio no muestra los hábitos"
    return 1
  fi
  for h in "${HABITOS[@]}"; do
    dias=$(dias_de_habito "$h")
    n=$(contar_entre "$(dia_mas "$hoy" -6)" "$hoy" <<<"$dias")
    esperado="$n/${HABITO_META[$h]}"
    ui texto "$esperado" >/dev/null || mal_+=("no dice $esperado para $h")
    ui texto "${HABITO_NOMBRE[$h]}$TXT_FILA_HABITO$n de ${HABITO_META[$h]}$TXT_VER_RESUMEN" >/dev/null ||
      mal_+=("la fila de $h no dice «$n de ${HABITO_META[$h]}»")
    if grep -qxF "$hoy" <<<"$dias"; then marcado=true; else marcado=false; fi
    e=$(ui marcado "${HABITO_BOTON[$h]}")
    [ "$e" = "true $marcado" ] || mal_+=("«${HABITO_BOTON[$h]}» está «${e:-?}» y tendría que ser «true $marcado»")
    dice+=("$h $esperado$( [ "$marcado" = true ] && echo ', hoy sí')")
  done
  e=$(iniciales_vistas)
  [ "$e" = "$(iniciales_de "$hoy")" ] || mal_+=("los días dicen «$e» y tendrían que ser «$(iniciales_de "$hoy")»")
  if (( ${#mal_[@]} )); then
    mal "$que: $(printf '%s; ' "${mal_[@]}")"
  else
    ok "$que: Inicio muestra los hábitos como están guardados ($(printf '%s; ' "${dice[@]}" | sed 's/; $//'))"
  fi
}

texto_pantalla() {   # lo que dice el tiempo de pantalla en el último volcado, sin «Hoy: » ni « de pantalla»
  local x y arriba abajo t
  while IFS=$'\t' read -r x y arriba abajo t; do
    if [[ $t =~ $TXT_PANTALLA_RE ]]; then
      printf '%s\n' "${BASH_REMATCH[1]}"
      return 0
    fi
  done < <(ui textos)
  return 1
}
minutos_de() {   # minutos_de TIEMPO: «1 h 5 min» → 65, «45 min» → 45, «menos de un minuto» → 0
  local h m
  if [ "$1" = "$TXT_MENOS_DE_UN_MINUTO" ]; then echo 0; return 0; fi
  [[ $1 =~ ^(([0-9]+) h)?( ?([0-9]+) min)?$ ]] || return 1
  h=${BASH_REMATCH[2]}
  m=${BASH_REMATCH[4]}
  [[ -n $h || -n $m ]] || return 1
  echo $(( ${h:-0} * 60 + ${m:-0} ))
}
volver_desde_el_reloj() {   # abre el Reloj (permitido) y vuelve a Inicio: así Inicio se reanuda
  disp am start -n "$RELOJ_ACTIVIDAD" >/dev/null
  esperar 10 "$(c_foco "$FOCO_RELOJ")"
  ir_a_inicio
}

# ── 0. El dispositivo ───────────────────────────────────────────────────────
paso_0() {
  titulo 0 "Dispositivo"
  local listado lineas otros serie estado_adb qemu sdk tam
  [[ $CLAVE =~ ^[A-Za-z0-9]{4,}$ ]] ||
    abortar "SLOWTOWN_CLAVE tiene que ser de letras y números, 4 o más: se tipea con «input text», que se lleva mal con espacios y símbolos"
  listado=$(adb devices 2>/dev/null) || abortar "adb devices no anda"
  # Sólo emuladores locales: adb emu, con el que se simulan llamadas y SMS,
  # llega a un emulador por su puerto de consola. Un teléfono real conectado
  # (el S10e, mientras se lo mira) queda afuera: todo lo de acá va con -s, y
  # liberar.sh y provisionar.sh, por ANDROID_SERIAL, al emulador elegido.
  mapfile -t lineas < <(awk '/^List of devices/ {next} NF >= 2 && $1 ~ /^emulator-[0-9]+$/ {print $1 "\t" $2}' <<<"$listado")
  otros=$(awk '/^List of devices/ {next} NF >= 2 && $1 !~ /^\*/ && $1 !~ /^emulator-[0-9]+$/ {print $1}' <<<"$listado" | paste -sd ' ')
  (( ${#lineas[@]} == 1 )) ||
    abortar "tiene que haber exactamente un emulador conectado y hay ${#lineas[@]}: así no hay forma de equivocarse"
  [ -z "$otros" ] || nota "también está conectado $otros: no lo toco"
  IFS=$'\t' read -r serie estado_adb <<<"${lineas[0]}"
  [ "$estado_adb" = device ] || abortar "$serie está «$estado_adb», no «device»"
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
  # selector para que queden marcadas justo las cuatro: Inicio muestra las
  # permitidas mientras nadie elija otras, y el paso 8 deja todo como estaba.
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
    abortar "el selector dice «Listo (${n:-?})» y tendrían que ser ${#APPS_INICIO[@]} (si quedó otro Inicio de una corrida que falló, probá con --limpio)"
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
  local hallado xy x y fin
  ir_a_inicio

  # Ajustes rápidos: con lock task, el panel tiene que abrir sin ellos (sin
  # engranaje no hay camino a Ajustes). Abierto, el panel suele tomar el foco;
  # se espera eso, pero se mira con uiautomator aunque no lo tome.
  adb_t 20 shell cmd statusbar expand-settings >/dev/null 2>&1
  esperar 5 "$(c_foco "$FOCO_PANEL")"
  captura panel-ajustes-rapidos
  if volcar_ui --windows; then
    cp "$UI_XML" "$SALIDA/panel-ajustes-rapidos.xml"
    if hallado=$(ui hay "$PANEL_PROHIBIDO_RE"); then
      mal "el panel muestra ajustes rápidos o el engranaje: $(tr '\n' ';' <<<"$hallado")"
    else
      ok "pedir los ajustes rápidos no muestra ni ajustes rápidos ni engranaje"
    fi
  else
    mal "no pude volcar el panel con uiautomator"
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
  esperar 5 "$(c_foco "$FOCO_PANEL")"
  # El foco llega antes de que el panel termine de abrirse, y el pie aparece
  # al final (con muchas notificaciones, un volcado al toque no lo vio): se espera.
  xy=''
  fin=$((SECONDS + 8))
  until [ -n "$xy" ] || (( SECONDS >= fin )); do
    volcar_ui --windows && { xy=$(ui id "$ID_GESTIONAR") || xy=$(ui texto "$TXT_GESTIONAR"); }
    [ -n "$xy" ] || sleep 0.5
  done
  if [ -n "$xy" ]; then
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

# ── 4b. Tiempo de pantalla ──────────────────────────────────────────────────
# Bajo la fecha, «Hoy: 1 h 5 min de pantalla»: la misma cuenta que Bienestar
# digital, con UsageStatsManager. Necesita el acceso al uso, que da
# provisionar.sh (appops GET_USAGE_STATS); sin él, la línea no está.
paso_4b() {
  titulo 4b "Tiempo de pantalla en Inicio"
  local out t1 t2 m1 m2 quieta
  out=$(disp appops get "$PAQUETE" GET_USAGE_STATS)
  if grep -q 'GET_USAGE_STATS: allow' <<<"$out"; then
    ok "provisionar.sh le dio a Slowtown el acceso al uso (appops GET_USAGE_STATS allow)"
  else
    mal "Slowtown no tiene el acceso al uso: $(head -n 1 <<<"$out")"
  fi
  ir_a_inicio
  if ! esperar_ui 10 regex "$TXT_PANTALLA_RE"; then
    mal "Inicio no muestra «Hoy: … de pantalla» bajo la fecha"
    return
  fi
  t1=$(texto_pantalla)
  if ! m1=$(minutos_de "$t1"); then
    mal "no entiendo el tiempo de pantalla «$t1»"
    return
  fi
  ok "bajo la fecha, Inicio muestra «Hoy: $t1 de pantalla»"

  # Un rato en una app permitida, con la pantalla prendida aunque nadie la toque.
  quieta=$(disp settings get global stay_on_while_plugged_in)
  [[ $quieta =~ ^[0-9]+$ ]] || quieta=0
  disp svc power stayon true >/dev/null
  disp am start -n "$RELOJ_ACTIVIDAD" >/dev/null
  if esperar 10 "$(c_foco "$FOCO_RELOJ")"; then
    nota "70 segundos en el Reloj"
    adb_t 100 shell 'sleep 70' >/dev/null 2>&1
    [[ $(foco) == *"$FOCO_RELOJ"* ]] || nota "a los 70 segundos el Reloj ya no estaba al frente ($(foco))"
  else
    mal "no se abre el Reloj ($(foco))"
  fi
  ir_a_inicio
  disp settings put global stay_on_while_plugged_in "$quieta" >/dev/null
  if esperar_ui 10 regex "$TXT_PANTALLA_RE" && t2=$(texto_pantalla) && m2=$(minutos_de "$t2"); then
    if (( m2 >= m1 + 1 )); then
      ok "70 segundos en el Reloj suman al menos un minuto: de «$t1» a «$t2»"
    else
      mal "después de 70 segundos en el Reloj dice «$t2», y antes decía «$t1»"
    fi
  else
    mal "después del Reloj, el tiempo de pantalla no está o no se entiende («${t2:-}»)"
  fi
  captura tiempo-de-pantalla

  # Sin el acceso al uso, la línea no está; con el acceso, vuelve. Inicio la
  # recalcula al reanudarse: se sale al Reloj y se vuelve.
  disp appops set "$PAQUETE" GET_USAGE_STATS deny >/dev/null
  volver_desde_el_reloj
  if esperar_que_no 10 regex "$TXT_PANTALLA_RE"; then
    ok "sin el acceso al uso (appops deny), Inicio no muestra el tiempo de pantalla"
  else
    mal "sin el acceso al uso, Inicio sigue mostrando «Hoy: $(texto_pantalla) de pantalla»"
  fi
  disp appops set "$PAQUETE" GET_USAGE_STATS allow >/dev/null
  volver_desde_el_reloj
  if esperar_ui 10 regex "$TXT_PANTALLA_RE"; then
    ok "con el acceso de vuelta (allow), vuelve: «Hoy: $(texto_pantalla) de pantalla»"
  else
    mal "con el acceso de vuelta, Inicio no muestra el tiempo de pantalla"
  fi
  afirmar_en_inicio "nada de esto toca el cierre: Slowtown y LOCKED"
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
  # El marcador de AOSP retiene unos segundos la llamada cortada («llamada
  # finalizada»), y si entra otra en ese rato no publica su aviso: suena pero
  # no hay cómo atender. Pasa igual con Slowtown en pausa (probado el
  # 19/10/2026: 1 aviso de 2 en llamadas a 1,5 s), así que no es lock task.
  adb_t 20 shell 'sleep 8' >/dev/null 2>&1
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
  n=$(bloqueos_desde "$marca")
  if [ "$n" = 0 ]; then
    ok "lock task no frenó nada al atender"
  else
    mal "lock task frenó algo al atender: $n líneas de violación o de BlockedAppActivity en logcat"
  fi
  captura en-llamada
  emu gsm cancel "$NUM_LLAMADA"
  esperar 15 "$(c_sin_llamada)" || mal "la llamada no se corta"
  # Si algo de la llamada quedó frenado, el cartel se queda arriba: cerrarlo
  # para que la falla no arrastre a lo que sigue.
  esperar 10 "$(c_foco "$FOCO_INICIO") || $(c_foco "$FOCO_BLOQUEO")"
  cerrar_cartel_bloqueo
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

# ── 7. Todas las apps ───────────────────────────────────────────────────────
paso_7() {
  titulo 7 "Todas las apps, con Slowtown activo"
  local esperadas
  esperadas=$(printf '%s\n' "${APPS_INICIO[@]}" | sort -u)
  ir_a_inicio
  # Con «↑ todas las apps» y no deslizando: un toque no depende de la
  # velocidad del dedo.
  if ! abrir_cajon; then
    mal "«$TXT_TODAS» no abre la lista de todas las apps"
    ir_a_inicio
    return
  fi
  ok "«$TXT_TODAS» abre la lista de todas las apps"
  if esperar_apps 10 etiquetas_cajon "${APPS_INICIO[@]}"; then
    ok "lista justo las permitidas, ${APPS_INICIO[*]}: ni $APP_CALENDARIO ni $APP_AJUSTES"
  else
    mal "lista «$LISTA» y tendrían que ser justo ${APPS_INICIO[*]}"
  fi
  # Mientras nadie elija, las de Inicio son las permitidas.
  if [ "$(favoritas_en_cajon | sort -u)" = "$esperadas" ]; then
    ok "las ${#APPS_INICIO[@]} llevan la nota «$TXT_EN_INICIO»"
  else
    mal "llevan la nota «$TXT_EN_INICIO» sólo: $(favoritas_en_cajon | en_una_linea)"
  fi
  captura todas-las-apps

  # El teclado se abre recién al tocar el buscador, y tarda en conectarse: lo
  # que se escribe enseguida se pierde.
  if tocar campo 1 && esperar 5 "$(c_teclado)"; then
    ok "tocar el buscador abre el teclado"
  else
    mal "tocar el buscador no abre el teclado"
  fi
  sleep 1
  adb_t 20 shell input text "$BUSQUEDA" >/dev/null 2>&1
  esperar_apps 20 etiquetas_cajon "$APP_RELOJ"
  case $? in
    0) ok "escribir «$BUSQUEDA» deja sólo $APP_RELOJ" ;;
    1) mal "con «$BUSQUEDA» en el buscador, la lista dice «$LISTA» y tendría que ser sólo $APP_RELOJ" ;;
    # Lo cubre Enter: sin el filtro abriría la primera de la lista
    # (en orden alfabético, Camera, que va antes que Clock).
    *) aviso "con el cursor titilando, uiautomator no pudo volcar la lista: lo dice Enter" ;;
  esac
  captura busqueda
  adb_t 20 shell input keyevent KEYCODE_ENTER >/dev/null 2>&1
  if esperar 10 "$(c_foco "$FOCO_RELOJ")"; then
    ok "Enter abre la primera que coincide: $APP_RELOJ"
  else
    mal "Enter no abre $APP_RELOJ ($(foco))"
  fi
  if [ "$(estado)" = LOCKED ]; then
    ok "con el Reloj abierto desde la lista, Slowtown sigue activo (LOCKED)"
  else
    mal "con el Reloj abierto desde la lista, lock task está en $(estado)"
  fi
  ir_a_inicio
  afirmar_en_inicio "Inicio vuelve a Slowtown"
}

# ── 8. Apps de Inicio ───────────────────────────────────────────────────────
paso_8() {
  titulo 8 "Sacar y volver a poner $APP_QUITAR en Inicio, sin contraseña"
  local sin=() a marcadas puestas=0
  for a in "${APPS_INICIO[@]}"; do [ "$a" = "$APP_QUITAR" ] || sin+=("$a"); done
  ir_a_inicio

  # Sacarla: mantenerla apretada en la lista abre un diálogo con su nombre.
  if ! abrir_cajon; then
    mal "«$TXT_TODAS» no abre la lista de todas las apps"
    ir_a_inicio
    return
  fi
  if ! accion_en_cajon "$APP_QUITAR" "$TXT_SACAR_DE_INICIO"; then
    mal "mantener apretada $APP_QUITAR en la lista no ofrece «$TXT_SACAR_DE_INICIO»"
  elif esperar_apps 10 favoritas_en_cajon "${sin[@]}"; then
    ok "mantener apretada $APP_QUITAR y «$TXT_SACAR_DE_INICIO» le sacan la nota «$TXT_EN_INICIO»"
  else
    mal "después de «$TXT_SACAR_DE_INICIO», llevan la nota «$TXT_EN_INICIO»: $LISTA"
  fi
  volver_del_cajon || { mal "Atrás no vuelve de la lista a Inicio"; ir_a_inicio; }
  if esperar_apps 10 etiquetas_inicio "${sin[@]}"; then
    ok "de vuelta en Inicio, muestra ${sin[*]}: sin $APP_QUITAR"
  else
    mal "Inicio muestra «$LISTA» y tendría que ser ${sin[*]}"
  fi
  captura inicio-sin-camara

  # Volver a ponerla. Se repasan todas las de Inicio, para que una falla de
  # arriba no le deje otro Inicio a lo que sigue ni a la corrida siguiente;
  # las que ya llevan la nota no se tocan (mantenerlas apretadas las sacaría).
  if ! abrir_cajon; then
    mal "«$TXT_TODAS» no abre la lista de todas las apps"
    ir_a_inicio
    return
  fi
  volcar_ui
  marcadas=$(favoritas_en_cajon)
  for a in "${APPS_INICIO[@]}"; do
    grep -qxF "$a" <<<"$marcadas" && continue
    [ "$a" = "$APP_QUITAR" ] || nota "$a tampoco estaba en Inicio: la vuelvo a poner"
    if accion_en_cajon "$a" "$TXT_PONER_EN_INICIO"; then
      puestas=$((puestas + 1))
    else
      mal "mantener apretada $a en la lista no ofrece «$TXT_PONER_EN_INICIO»"
    fi
  done
  if esperar_apps 10 favoritas_en_cajon "${APPS_INICIO[@]}"; then
    if (( puestas )); then
      ok "«$TXT_PONER_EN_INICIO» le devuelve la nota: la llevan las ${#APPS_INICIO[@]}"
    else
      nota "las ${#APPS_INICIO[@]} ya llevaban la nota: no hubo nada que volver a poner"
    fi
  else
    mal "llevan la nota «$TXT_EN_INICIO»: $LISTA, y tendrían que ser las ${#APPS_INICIO[@]}"
  fi
  volver_del_cajon || { mal "Atrás no vuelve de la lista a Inicio"; ir_a_inicio; }
  if esperar_apps 10 etiquetas_inicio "${APPS_INICIO[@]}"; then
    ok "Inicio vuelve a mostrar ${APPS_INICIO[*]}"
  else
    mal "Inicio muestra «$LISTA» y tendría que ser ${APPS_INICIO[*]}"
  fi
  afirmar_en_inicio "nada de esto pidió la contraseña: sigue en Slowtown y LOCKED"
}

# ── 8b. Hábitos ─────────────────────────────────────────────────────────────
# Meditar 2 veces por semana y ejercicio 3, marcados desde Inicio: sin widget
# de otra app, van al pie, en su lugar. Los botones marcan hoy; el resumen,
# cualquier día que ya pasó. Slowtown los guarda en sus preferencias, como
# fechas ISO (se leen con run-as: la build es depurable). Sin contraseña.
paso_8b() {
  titulo 8b "Hábitos en Inicio: meditar 2 veces por semana y ejercicio 3"
  local hoy ayer h e celda esperado dias xy_b xy_t limpios=1
  ir_a_inicio
  esperar_lejos_de_medianoche
  hoy=$(hoy_en_el_emulador)
  ayer=$(dia_mas "$hoy" -1)
  if ! esperar_ui 10 texto "$TXT_HOY_MEDITAR"; then
    mal "Inicio no muestra los hábitos al pie (no encuentro «$TXT_HOY_MEDITAR»)"
    return
  fi
  e=$(iniciales_vistas)
  xy_b=$(ui texto "$TXT_HOY_MEDITAR")
  xy_t=$(ui texto "$TXT_TODAS") || xy_t='0 99999'
  if [ "$e" != "$(iniciales_de "$hoy")" ]; then
    mal "los días del widget de hábitos dicen «$e» y tendrían que ser «$(iniciales_de "$hoy")»"
  elif (( ${xy_b#* } < ${xy_t#* } )); then
    mal "los hábitos no están al pie: quedan arriba de «$TXT_TODAS»"
  else
    ok "sin widget elegido, Inicio muestra los hábitos al pie, con los últimos 7 días: $e (hoy a la derecha)"
  fi
  # De entrada, hoy sin marcar. Lo que quedó de una corrida anterior se desmarca.
  for h in "${HABITOS[@]}"; do
    volcar_ui
    e=$(ui marcado "${HABITO_BOTON[$h]}")
    case $e in
      'true false') ;;
      'true true')
        limpios=0
        nota "«${HABITO_BOTON[$h]}» quedó marcado de una corrida anterior: lo desmarco"
        tocar_en_inicio texto "${HABITO_BOTON[$h]}"
        esperar_marcado "${HABITO_BOTON[$h]}" false || mal "no pude desmarcar «${HABITO_BOTON[$h]}»"
        ;;
      *)
        limpios=0
        mal "«${HABITO_BOTON[$h]}» no es un botón que se marca (checkable/checked: ${e:-no está})"
        ;;
    esac
  done
  (( limpios )) && ok "los dos botones de hoy, «$TXT_HOY_MEDITAR» y «$TXT_HOY_EJERCICIO», empiezan sin marcar"

  # Meditar hoy: se marca y se desmarca. Ejercicio hoy queda marcado.
  if tocar_en_inicio texto "$TXT_HOY_MEDITAR" && esperar_marcado "$TXT_HOY_MEDITAR" true &&
    esperar_dia meditar "$hoy" si; then
    ok "tocar «$TXT_HOY_MEDITAR» lo marca, y Slowtown guarda $hoy en habit_meditar"
  else
    mal "tocar «$TXT_HOY_MEDITAR» no lo marca o no guarda $hoy (guardadas: $(dias_de_habito meditar | en_una_linea))"
  fi
  if tocar_en_inicio texto "$TXT_HOY_MEDITAR" && esperar_marcado "$TXT_HOY_MEDITAR" false &&
    esperar_dia meditar "$hoy" no; then
    ok "tocarlo otra vez lo desmarca, y $hoy sale de habit_meditar"
  else
    mal "tocar otra vez «$TXT_HOY_MEDITAR» no lo desmarca (guardadas: $(dias_de_habito meditar | en_una_linea))"
  fi
  if tocar_en_inicio texto "$TXT_HOY_EJERCICIO" && esperar_marcado "$TXT_HOY_EJERCICIO" true &&
    esperar_dia ejercicio "$hoy" si; then
    ok "tocar «$TXT_HOY_EJERCICIO» lo marca, y Slowtown guarda $hoy en habit_ejercicio"
  else
    mal "tocar «$TXT_HOY_EJERCICIO» no lo marca o no guarda $hoy (guardadas: $(dias_de_habito ejercicio | en_una_linea))"
  fi
  captura habitos-inicio

  # El resumen, tocando los cuadraditos de meditar.
  if ! { tocar_en_inicio contiene "${HABITO_NOMBRE[meditar]}$TXT_FILA_HABITO" &&
    esperar_ui 10 texto "$TXT_HABITOS_PISTA"; }; then
    mal "tocar los días de meditar no abre el resumen ($(foco))"
    ir_a_inicio
    return
  fi
  if ui texto "$TXT_HABITOS_TITULO" >/dev/null && ui texto "${HABITO_NOMBRE[meditar]}" >/dev/null &&
    ui texto "${HABITO_META[meditar]} por semana" >/dev/null; then
    ok "tocar los días de meditar abre el resumen «$TXT_HABITOS_TITULO»: «$TXT_HABITOS_PISTA»"
  else
    mal "el resumen no tiene el título «$TXT_HABITOS_TITULO» o «${HABITO_NOMBRE[meditar]}, ${HABITO_META[meditar]} por semana»"
  fi
  # Ayer, para el que se olvidó de marcarlo. Los días que vienen no tienen celda.
  celda=$(celda_re "${HABITO_NOMBRE[meditar]}" "$ayer")
  if ui regex "${celda}sí\$" >/dev/null; then
    nota "ayer ya estaba marcado (de una corrida anterior): lo desmarco primero"
    tocar regex "${celda}sí\$"
    esperar_ui 10 regex "${celda}no\$" || mal "no pude desmarcar ayer en el resumen"
  fi
  if tocar regex "${celda}no\$" && esperar_ui 10 regex "${celda}sí\$" && esperar_dia meditar "$ayer" si; then
    ok "en el resumen, tocar ayer (${celda#^}no) lo pasa a «sí», y Slowtown guarda $ayer"
  else
    mal "en el resumen no pude marcar ayer: busqué «${celda}no» (guardadas: $(dias_de_habito meditar | en_una_linea))"
  fi
  if ui regex "$(celda_re "${HABITO_NOMBRE[meditar]}" "$(dia_mas "$hoy" 1)")" >/dev/null; then
    mal "el resumen tiene una celda para mañana"
  fi
  # La línea de cuentas de cada hábito, contra lo guardado.
  for h in "${HABITOS[@]}"; do
    dias=$(dias_de_habito "$h")
    esperado="Esta semana: $(contar_entre "$(lunes_de "$hoy")" "$(dia_mas "$(lunes_de "$hoy")" 6)" <<<"$dias") de ${HABITO_META[$h]}"
    esperado+=" · Semanas cumplidas seguidas: $(racha_de "${HABITO_META[$h]}" "$hoy" <<<"$dias")"
    esperado+=" · Días en total: $(contar_entre 0000-00-00 "$hoy" <<<"$dias")"
    volcar_ui
    if ! ui texto "$esperado" >/dev/null; then
      arrastrar 70 45
      volcar_ui
    fi
    if ui texto "$esperado" >/dev/null; then
      ok "${HABITO_NOMBRE[$h]}: «$esperado»"
    else
      mal "${HABITO_NOMBRE[$h]}: no encuentro «$esperado» (dice: $(ui textos | cut -f5 | grep 'Esta semana' | en_una_linea))"
    fi
  done
  captura habitos-resumen
  # «Volver» vuelve a Inicio.
  if tocar_desplazando texto "$TXT_VOLVER" && esperar_ui 10 texto "$TXT_TODAS"; then
    ok "«$TXT_VOLVER» vuelve a Inicio"
  else
    mal "«$TXT_VOLVER» no vuelve del resumen a Inicio ($(foco))"
    ir_a_inicio
  fi
  afirmar_habitos "de vuelta en Inicio"
  HABITO_HOY=$hoy
  HABITO_AYER=$ayer
  afirmar_en_inicio "los hábitos no piden contraseña: Slowtown sigue activo (LOCKED)"
}

# Atrás, como lo haría cualquiera: en las pantallas largas (el resumen con
# cuatro hábitos, la lista para elegirlos) «Volver» queda muy abajo.
atras() { adb_t 20 shell input keyevent KEYCODE_BACK >/dev/null 2>&1; sleep 1; }

# ── 8c. Elegir hábitos ──────────────────────────────────────────────────────
paso_8c() {
  titulo 8c "Elegir hábitos: sumar uno, cambiarle las veces por semana, el tope de 4 y sacarlo"
  ir_a_inicio
  if ! { tocar_en_inicio contiene "${HABITO_NOMBRE[meditar]}$TXT_FILA_HABITO" &&
    esperar_ui 10 texto "$TXT_ELEGIR_HABITOS" && tocar texto "$TXT_ELEGIR_HABITOS" &&
    esperar_ui 10 texto "Leer"; }; then
    mal "no llego a «$TXT_ELEGIR_HABITOS» desde el resumen ($(foco))"
    ir_a_inicio
    return
  fi
  ok "el resumen tiene «$TXT_ELEGIR_HABITOS», y abre la lista de hábitos"
  captura elegir-habitos
  # Leer: se suma con 5 por semana, y con + pasa a 6.
  if tocar texto "Leer" && esperar_ui 10 texto "Más veces: Leer" && ui texto "5 por semana" >/dev/null; then
    ok "tocar «Leer» lo suma, con 5 veces por semana de entrada"
  else
    mal "tocar «Leer» no lo suma con 5 por semana"
  fi
  if tocar texto "Más veces: Leer" && esperar_ui 10 texto "6 por semana"; then
    ok "con «+» Leer pasa a 6 por semana"
  else
    mal "«+» no sube Leer a 6 por semana"
  fi
  # El tope: con Caminar son 4, y Bici ya no entra.
  if tocar texto "Caminar" && esperar_ui 10 texto "Más veces: Caminar" && tocar texto "Bici" &&
    esperar_ui 10 texto "$TXT_HABITOS_TOPE" && ! ui texto "Más veces: Bici" >/dev/null; then
    ok "con 4 hábitos, sumar un quinto (Bici) dice «$TXT_HABITOS_TOPE»"
  else
    mal "el tope de 4 no anda: no aparece «$TXT_HABITOS_TOPE» o Bici quedó sumado"
  fi
  captura elegir-habitos-cuatro
  # En Inicio: cuatro filas, con el botón de hoy de Leer y su meta de 6.
  if atras && esperar_ui 10 texto "$TXT_HABITOS_PISTA" && atras && esperar_ui 10 texto "Lectura de hoy" &&
    ui texto "Caminata de hoy" >/dev/null && ui contiene "Leer$TXT_FILA_HABITO" >/dev/null &&
    ui texto "0/6" >/dev/null; then
    ok "Inicio suma Leer con su meta (0/6) y los botones «Lectura de hoy» y «Caminata de hoy»"
  else
    mal "Inicio o el resumen no muestran los hábitos sumados ($(foco))"
  fi
  captura inicio-cuatro-habitos
  # Sacar Leer y Caminar, y el piso de uno: sin ejercicio, meditar no se puede sacar.
  if tocar_en_inicio contiene "${HABITO_NOMBRE[meditar]}$TXT_FILA_HABITO" && esperar_ui 10 texto "$TXT_ELEGIR_HABITOS" &&
    tocar texto "$TXT_ELEGIR_HABITOS" && esperar_ui 10 texto "Leer" &&
    tocar texto "Leer" && esperar_que_no 10 texto "Más veces: Leer" &&
    tocar texto "Caminar" && esperar_que_no 10 texto "Más veces: Caminar" &&
    tocar texto "Ejercicio" && esperar_que_no 10 texto "Más veces: Ejercicio" &&
    tocar texto "Meditar" && esperar_ui 10 texto "$TXT_HABITOS_UNO" && ui texto "Más veces: Meditar" >/dev/null; then
    ok "se sacan con un toque, y el último no: «$TXT_HABITOS_UNO»"
  else
    mal "sacar hábitos o el piso de uno no anda ($(foco))"
  fi
  # Ejercicio vuelve con sus 3 de entrada y sus días: sacarlo no los borra.
  if tocar texto "Ejercicio" && esperar_ui 10 texto "Más veces: Ejercicio" && ui texto "3 por semana" >/dev/null &&
    atras && esperar_ui 10 texto "$TXT_HABITOS_PISTA" &&
    atras && esperar_ui 10 texto "$TXT_HOY_EJERCICIO" &&
    ! ui texto "Lectura de hoy" >/dev/null; then
    ok "Ejercicio vuelve con 3 por semana, e Inicio queda como estaba: meditar y ejercicio"
  else
    mal "no pude dejar los hábitos como estaban ($(foco))"
  fi
  afirmar_habitos "después de elegir hábitos, con los días de antes"
  afirmar_en_inicio "elegir hábitos no pide contraseña: Slowtown sigue activo (LOCKED)"
}

# ── 9. Widget de Inicio ─────────────────────────────────────────────────────
# El lugar del pie es de un widget: el de una app (el Reloj) o los hábitos de
# Slowtown, que vienen puestos. Elegir uno saca al otro, y «Sacar el widget»
# saca el que esté.
abrir_lista_de_widgets() {   # desde Inicio hasta la lista de widgets de la administración
  entrar_admin || { nota "no pude entrar a la administración"; return 1; }
  tocar_desplazando texto "$TXT_ELEGIR_WIDGET" && esperar_ui 10 texto "$TXT_WIDGET_TITULO"
}
elegir_habitos_en_la_lista() {   # en la lista de widgets: «Hábitos de Slowtown», y de vuelta en Inicio
  tocar_desplazando texto "$TXT_HABITOS_WIDGET" && esperar_ui 15 texto "$TXT_TODAS"
}
paso_9() {
  titulo 9 "Widget de Inicio: $WIDGET_RELOJ y los hábitos"
  local w etiqueta paquetes dichos armados xy_h xy_r
  ir_a_inicio
  # provisionar.sh corre «appwidget grantbind»: sin ese permiso, Android
  # pregunta con un diálogo de Ajustes antes de armar el widget.
  if puede_armar_widgets; then
    ok "Android deja a Slowtown poner widgets sin preguntar (appwidget grantbind, de provisionar.sh)"
  else
    mal "Slowtown no tiene permiso para poner widgets sin preguntar: ¿provisionar.sh no corrió appwidget grantbind?"
  fi
  if ! abrir_lista_de_widgets; then
    mal "«$TXT_ELEGIR_WIDGET» no abre la lista de widgets"
    ir_a_inicio
    return
  fi
  # La lista carga en segundo plano: primero se la espera, después se toca.
  esperar_ui 10 texto "$WIDGET_RELOJ"
  if xy_h=$(ui texto "$TXT_HABITOS_WIDGET") && xy_r=$(ui texto "$WIDGET_RELOJ") &&
    ui texto "$TXT_HABITOS_WIDGET_SUB" >/dev/null && (( ${xy_h#* } < ${xy_r#* } )); then
    ok "la lista ofrece primero «$TXT_HABITOS_WIDGET» («$TXT_HABITOS_WIDGET_SUB»), antes que los widgets de las apps"
  else
    mal "la lista de widgets no ofrece primero «$TXT_HABITOS_WIDGET» con «$TXT_HABITOS_WIDGET_SUB»"
  fi
  captura lista-de-widgets
  if ! tocar_desplazando texto "$WIDGET_RELOJ"; then
    mal "no encuentro «$WIDGET_RELOJ» en la lista de widgets"
    ir_a_inicio
    return
  fi
  # Inicio y la lista de widgets son la misma actividad: la vuelta se ve en
  # la pantalla, no en el foco.
  if ! esperar_ui 15 texto "$TXT_TODAS"; then
    if [[ $(foco) == *"$FOCO_PERMISO_WIDGET"* ]]; then
      mal "Android pidió permiso con su diálogo: falta appwidget grantbind (provisionar.sh)"
      adb_t 20 shell input keyevent KEYCODE_BACK >/dev/null 2>&1
    else
      mal "elegir «$WIDGET_RELOJ» no vuelve a Inicio ($(foco))"
    fi
    ir_a_inicio
    return
  fi
  afirmar_en_inicio "elegir «$WIDGET_RELOJ» vuelve a Inicio: Slowtown y LOCKED"
  # Se busca por clase y por paquete, no por posición ni por cómo se ve: el
  # widget es un AppWidgetHostView, y lo de adentro lo dibuja el Reloj con su
  # propio paquete. Con el foco en Slowtown, un nodo de com.android.deskclock
  # en su ventana sólo puede ser el widget, y que esté dice que se dibujó (si
  # falla, Android pone ahí un cartel de error suyo, con el paquete de Slowtown).
  if w=$(esperar_widget 15); then
    IFS=$'\t' read -r etiqueta paquetes dichos <<<"$w"
    if [[ $etiqueta == "$WIDGET_RELOJ" && ,$paquetes, == *",$PAQUETE_RELOJ,"* ]]; then
      ok "Inicio muestra el widget «$WIDGET_RELOJ», dibujado por $PAQUETE_RELOJ"
    else
      mal "Inicio muestra el widget «$etiqueta», con nodos de $paquetes ($dichos): tendría que ser «$WIDGET_RELOJ», de $PAQUETE_RELOJ"
    fi
    if ui texto "$TXT_HOY_MEDITAR" >/dev/null; then
      mal "con el widget del Reloj, Inicio sigue mostrando los hábitos"
    else
      ok "y en su lugar: los hábitos ya no están"
    fi
  else
    armados=$(widgets_armados | en_una_linea)
    mal "Inicio no muestra ningún widget (Android lo tiene armado para Slowtown: ${armados:-no})"
  fi
  captura widget
  # El armado fue una sesión de administración. Termina en segundo plano al
  # quedar el widget: se espera a que la lista de lock task vuelva a ser la de
  # siempre, y Ajustes tiene que estar bloqueado otra vez.
  esperar 10 "! $(c_sesion)" || nota "la lista de lock task todavía tiene a Ajustes, como en una sesión"
  intento_am_start "Ajustes con am start después de elegir el widget" "$FOCO_AJUSTES" -a android.settings.SETTINGS

  # Los hábitos de vuelta: sacan al Reloj, y con las marcas de antes.
  if abrir_lista_de_widgets && elegir_habitos_en_la_lista; then
    afirmar_en_inicio "elegir «$TXT_HABITOS_WIDGET» vuelve a Inicio: Slowtown y LOCKED"
    afirmar_habitos "con «$TXT_HABITOS_WIDGET» elegido otra vez"
    if esperar_sin_widget 10; then
      ok "y el widget del Reloj ya no está"
    else
      mal "con los hábitos elegidos, Inicio sigue mostrando un widget: $(ui widgets | tr '\t' ' ')"
    fi
    armados=$(widgets_armados | en_una_linea)
    if [ -z "$armados" ]; then
      ok "Android ya no tiene ningún widget armado para Slowtown"
    else
      mal "Android sigue con widgets armados para Slowtown: $armados"
    fi
  else
    mal "no pude volver a elegir «$TXT_HABITOS_WIDGET» ($(foco))"
    ir_a_inicio
  fi

  # «Sacar el widget» saca el que esté, también los hábitos.
  if abrir_lista_de_widgets && esperar_ui 10 texto "$TXT_SACAR_WIDGET" &&
    tocar_desplazando texto "$TXT_SACAR_WIDGET" && esperar_ui 10 texto "$TXT_ADMIN"; then
    nota "«$TXT_SACAR_WIDGET» vuelve a la administración"
  else
    mal "con los hábitos puestos, no pude usar «$TXT_SACAR_WIDGET» ($(foco))"
  fi
  ir_a_inicio
  afirmar_en_inicio "de vuelta en Inicio: Slowtown y LOCKED"
  if esperar_que_no 10 texto "$TXT_HOY_MEDITAR" && ! ui widgets >/dev/null; then
    ok "«$TXT_SACAR_WIDGET» saca los hábitos: el pie de Inicio queda vacío"
  else
    mal "después de «$TXT_SACAR_WIDGET», Inicio sigue mostrando los hábitos o un widget"
  fi
  captura sin-widget

  # Y se dejan puestos, como vienen, para lo que sigue.
  if abrir_lista_de_widgets && elegir_habitos_en_la_lista && esperar_ui 10 texto "$TXT_HOY_MEDITAR"; then
    ok "«$TXT_HABITOS_WIDGET» los vuelve a poner"
  else
    mal "no pude volver a poner los hábitos ($(foco))"
    ir_a_inicio
  fi
  afirmar_en_inicio "de vuelta en Inicio: Slowtown y LOCKED"
}

# ── 9b. Carpetas ────────────────────────────────────────────────────────────
paso_9b() {
  titulo 9b "Carpetas: se crean en la configuración y una app se mueve manteniéndola apretada"
  local mayus="${CARPETA^^}" sueltas=() a
  for a in "${APPS_INICIO[@]}"; do [ "$a" = "$APP_CARPETA" ] || sueltas+=("$a"); done
  ir_a_inicio

  # Crearla: en la configuración, no en la lista (en el uso diario, menos botones).
  if ! entrar_admin || ! tocar_desplazando texto "$TXT_CARPETAS" || ! esperar_ui 10 texto "$TXT_NUEVA_CARPETA"; then
    mal "Administración no lleva a «$TXT_CARPETAS»"
    ir_a_inicio
    return
  fi
  tocar texto "$TXT_NUEVA_CARPETA"
  # El diálogo enfoca su campo solo; un segundo más para que se conecte el teclado.
  esperar 10 "$(c_teclado)"
  sleep 1
  adb_t 20 shell input text "$CARPETA" >/dev/null 2>&1
  if tocar texto "$TXT_CREAR" && esperar_ui 10 texto "$mayus"; then
    ok "«$TXT_NUEVA_CARPETA» crea $mayus en la configuración"
  else
    mal "no se creó la carpeta $mayus"
    ir_a_inicio
    return
  fi
  ir_a_inicio

  if ! abrir_cajon; then
    mal "«$TXT_TODAS» no abre la lista de todas las apps"
    ir_a_inicio
    return
  fi
  if ui texto "$mayus" >/dev/null; then
    ok "la lista de todas muestra la carpeta $mayus"
  else
    mal "la lista de todas no muestra la carpeta $mayus"
  fi
  if ! accion_en_cajon "$APP_CARPETA" "$TXT_MOVER_A$CARPETA"; then
    mal "mantener apretada $APP_CARPETA no ofrece «$TXT_MOVER_A$CARPETA»"
  elif esperar_apps 10 etiquetas_cajon "${sueltas[@]}"; then
    ok "«$TXT_MOVER_A$CARPETA» saca $APP_CARPETA de la lista suelta: quedan ${sueltas[*]}"
  else
    mal "después de moverla, la lista suelta muestra «$LISTA»"
  fi
  captura carpetas
  if tocar texto "$mayus" && esperar_apps 10 etiquetas_cajon "$APP_CARPETA"; then
    ok "abrir $mayus muestra adentro sólo $APP_CARPETA"
  else
    mal "abrir $mayus muestra «$LISTA»"
  fi
  adb_t 20 shell input keyevent KEYCODE_BACK >/dev/null 2>&1
  esperar_ui 5 campo 1 || nota "Atrás desde la carpeta no volvió a la lista"

  # Buscando, aparece aunque esté en una carpeta, con la carpeta al lado.
  tocar campo 1
  esperar 10 "$(c_teclado)"
  sleep 1
  adb_t 20 shell input text "clo" >/dev/null 2>&1
  if esperar_ui 10 contiene "$mayus" && ui texto "$APP_CARPETA" >/dev/null; then
    ok "buscar «clo» encuentra $APP_CARPETA adentro de $mayus, y lo dice"
  else
    mal "buscar «clo» no muestra $APP_CARPETA con su carpeta"
  fi
  volver_del_cajon || { mal "Atrás no vuelve de la lista a Inicio"; ir_a_inicio; }

  # Borrarla: sus apps vuelven a la lista. Deja todo como estaba.
  if entrar_admin && tocar_desplazando texto "$TXT_CARPETAS" && esperar_ui 10 texto "$mayus" && tocar texto "$TXT_BORRAR"; then
    ir_a_inicio
    if abrir_cajon && esperar_apps 10 etiquetas_cajon "${APPS_INICIO[@]}"; then
      ok "borrar $mayus devuelve $APP_CARPETA a la lista"
    else
      mal "después de borrar la carpeta, la lista muestra «$LISTA»"
    fi
    volver_del_cajon || ir_a_inicio
  else
    mal "no pude borrar la carpeta $mayus desde la configuración"
    ir_a_inicio
  fi
  afirmar_en_inicio "las carpetas no pidieron nada más que la configuración: Slowtown y LOCKED"
}

# ── 9c. «Habilitar todo por 5 minutos» ──────────────────────────────────────
# Sin contraseña, 2 veces cada 12 horas. Para no esperar los 5 minutos, el
# final se dispara como lo hace la alarma, con root (el receptor no se exporta).
paso_9c() {
  titulo 9c "«$TXT_HABILITAR»: sin contraseña, 2 veces cada 12 horas"
  local vez quedan
  ir_a_inicio
  tocar_en_inicio regex "$TXT_HORA_RE" 1500 || { mal "no encuentro la hora en Inicio"; return; }
  if esperar_ui 4 contiene "$TXT_YA_USASTE"; then
    aviso "ya se usaron las 2 de estas 12 horas (corré con --limpio): salteo este paso"
    ir_a_inicio
    return
  fi
  ir_a_inicio
  if ! adb_como 0; then
    aviso "sin root no puedo terminarla antes de los 5 minutos: salteo este paso"
    return
  fi
  for vez in 1 2; do
    quedan=$((3 - vez))
    tocar_en_inicio regex "$TXT_HORA_RE" 1500 || { mal "no encuentro la hora en Inicio"; break; }
    if ! esperar_ui 10 texto "$TXT_HABILITAR"; then
      mal "la pantalla de la contraseña no ofrece «$TXT_HABILITAR»"
      ir_a_inicio
      break
    fi
    if ui contiene "Te quedan $quedan de 2" >/dev/null; then
      ok "vez $vez: el botón dice que quedan $quedan de 2"
    else
      mal "vez $vez: el botón no dice «Te quedan $quedan de 2»"
    fi
    tocar texto "$TXT_HABILITAR"
    if esperar_ui 10 contiene "$TXT_HABILITADO"; then
      ok "vez $vez: Inicio avisa «$TXT_HABILITADO …»"
    else
      mal "vez $vez: Inicio no avisa hasta cuándo está todo habilitado"
    fi
    captura "habilitado-$vez"
    disp am start -a android.settings.SETTINGS >/dev/null
    if esperar 10 "$(c_foco "$FOCO_AJUSTES")"; then
      ok "vez $vez: con todo habilitado, Ajustes abre"
    else
      mal "vez $vez: con todo habilitado, Ajustes no abre ($(foco))"
    fi
    ir_a_inicio
    disp am start -a android.settings.SETTINGS >/dev/null
    if esperar 10 "$(c_foco "$FOCO_AJUSTES")"; then
      ok "vez $vez: volver a Inicio no la corta, Ajustes sigue abriendo"
    else
      mal "vez $vez: volver a Inicio la cortó ($(foco))"
    fi
    ir_a_inicio
    # Todo es todo menos el nivel 2: el navegador pausado sigue sin abrirse.
    (( vez == 1 )) && intento_navegador "vez 1, con todo habilitado"
    disp "am broadcast -n '$PAQUETE/.admin.FreeSession\$End'" >/dev/null
    sleep 3
    intento_am_start "vez $vez, después del final: Ajustes" "$FOCO_AJUSTES" -a android.settings.SETTINGS
  done
  tocar_en_inicio regex "$TXT_HORA_RE" 1500
  if esperar_ui 10 contiene "$TXT_YA_USASTE"; then
    ok "la tercera no se puede: «$TXT_YA_USASTE» y cuándo vuelve a haber"
  else
    mal "después de 2 veces, el botón no dice «$TXT_YA_USASTE»"
  fi
  captura habilitar-sin-veces
  ir_a_inicio
  adb_como 2000 || aviso "adb quedó como root (adb unroot lo vuelve atrás)"
}

# ── 9d. Nivel 2 ─────────────────────────────────────────────────────────────
# Lo que Slowtown bloquea siempre, también con la contraseña: el navegador
# pausado y las políticas de Chrome. Con root, para leer las políticas que
# guarda Android; al final, adb queda como estaba.
paso_9d() {
  titulo 9d "Nivel 2: lo que se bloquea siempre, también con la contraseña"
  local uid root=0 p out bloqueadas n apk instaladas=0
  ir_a_inicio
  # Si una corrida anterior se cortó entre desinstalarlo y volver a instalarlo.
  out=$(disp pm path "$NAVEGADOR")
  if ! grep -q '^package:' <<<"$out"; then
    nota "$NAVEGADOR estaba desinstalado para el usuario 0 (¿una corrida cortada?): lo vuelvo a instalar"
    disp cmd package install-existing "$NAVEGADOR" >/dev/null
    esperar 15 "$(c_pausada "$NAVEGADOR")"
  fi
  p=$(pausa_de "$NAVEGADOR")
  case $p in
    android) ok "$APP_NAVEGADOR, el único navegador del emulador, está pausado a nombre de android: lo pausó el Device Owner" ;;
    no) mal "$APP_NAVEGADOR no está pausado: el nivel 2 no lo agarró" ;;
    '?') mal "no encuentro $NAVEGADOR en este emulador; actualizá la constante" ;;
    *) mal "$APP_NAVEGADOR está pausado por «$p», no por el Device Owner" ;;
  esac
  intento_navegador "con Slowtown activo"

  uid=$(uid_adb)
  if [ "$uid" = 0 ] || adb_como 0; then
    root=1
  else
    mal "no pude pasar adb a root: sin eso no leo las políticas de Chrome"
  fi
  if (( root )) && politicas_chrome; then
    bloqueadas=$(politica_chrome URLBlocklist)
    n=$(grep -c . <<<"$bloqueadas")
    if ! grep -qxF youtube.com <<<"$bloqueadas" || ! grep -qxF translate.goog <<<"$bloqueadas"; then
      mal "URLBlocklist de Chrome no tiene youtube.com y translate.goog ($n páginas: $(head -n 5 <<<"$bloqueadas" | en_una_linea)…)"
    elif (( n > 1000 )); then
      mal "URLBlocklist de Chrome tiene $n páginas, y Chrome no lee más de 1000"
    else
      ok "Android guarda para Chrome URLBlocklist con $n páginas (Chrome lee hasta 1000), youtube.com y translate.goog entre ellas"
    fi
    if grep -qxF accounts.youtube.com <<<"$(politica_chrome URLAllowlist)"; then
      ok "URLAllowlist deja pasar accounts.youtube.com (el ingreso a Google)"
    else
      mal "URLAllowlist de Chrome no tiene accounts.youtube.com: $(politica_chrome URLAllowlist | en_una_linea)"
    fi
    out=''
    [ "$(politica_chrome IncognitoModeAvailability)" = 1 ] || out+=" IncognitoModeAvailability=$(politica_chrome IncognitoModeAvailability)"
    [ "$(politica_chrome ForceGoogleSafeSearch)" = true ] || out+=" ForceGoogleSafeSearch=$(politica_chrome ForceGoogleSafeSearch)"
    [ "$(politica_chrome SafeSitesFilterBehavior)" = 1 ] || out+=" SafeSitesFilterBehavior=$(politica_chrome SafeSitesFilterBehavior)"
    [ "$(politica_chrome DnsOverHttpsMode)" = off ] || out+=" DnsOverHttpsMode=$(politica_chrome DnsOverHttpsMode)"
    [ "$(politica_chrome NTPContentSuggestionsEnabled)" = false ] || out+=" NTPContentSuggestionsEnabled=$(politica_chrome NTPContentSuggestionsEnabled)"
    # Chrome en Android no la lee: no tiene que estar.
    grep -q 'key="AllowDinosaurEasterEgg"' <<<"$RESTRICCIONES" && out+=" AllowDinosaurEasterEgg (no tendría que estar)"
    if [ -z "$out" ]; then
      ok "y sin incógnito, con SafeSearch y el filtro de sitios para adultos, sin DNS propio ni noticias en la pestaña nueva (y sin AllowDinosaurEasterEgg, que en Android no anda)"
    else
      mal "políticas de Chrome que no son las de Chrome.policies:$out"
    fi
  elif (( root )); then
    mal "no pude leer las políticas de Chrome ($RESTRICCIONES_CHROME)"
  fi

  # Desinstalar para el usuario (o las actualizaciones) rearma de cero una app
  # de sistema, sin la pausa: a las de sistema, Slowtown se lo bloquea.
  out=$(disp pm uninstall -k --user 0 "$NAVEGADOR")
  p=$(pausa_de "$NAVEGADOR")
  if grep -q DELETE_FAILED_OWNER_BLOCKED <<<"$out" && [ "$p" = android ]; then
    ok "desinstalarlo para el usuario no se puede ($(grep -o 'Failure.*' <<<"$out" | head -n 1)), y sigue pausado"
  else
    mal "pm uninstall -k --user 0 $NAVEGADOR dijo «$(head -n 1 <<<"$out")» y quedó pausado: $p (tendría que estar bloqueado)"
    # Para que lo que sigue lo encuentre.
    hay_paquete "$NAVEGADOR" || disp cmd package install-existing "$NAVEGADOR" >/dev/null
  fi

  # Lo que se instala lo agarra el aviso de PACKAGE_ADDED, sin esperar al
  # próximo arranque: un navegador cualquiera y una de Fixed.APPS, de prueba.
  if ! armar_apks_de_prueba; then
    mal "no pude armar las apps de prueba (ver $SALIDA/apk/armado.log)"
  else
    for p in "$NAVEGADOR_PRUEBA" "$APP_VETADA"; do
      if hay_paquete "$p"; then
        nota "$p estaba instalada de una corrida anterior: la saco para instalarla de cero"
        adb_t 60 uninstall "$p" >/dev/null 2>&1
      fi
    done
    for apk in navegador vetada; do
      out=$(adb_t 120 install "$SALIDA/apk/$apk.apk" 2>&1)
      if grep -q Success <<<"$out"; then instaladas=$((instaladas + 1))
      else mal "no pude instalar $apk.apk: $(tail -n 1 <<<"$out")"; fi
    done
    if (( instaladas == 2 )); then
      if esperar 15 "$(c_pausada "$NAVEGADOR_PRUEBA") && $(c_pausada "$APP_VETADA")" &&
        [ "$(pausa_de "$NAVEGADOR_PRUEBA")" = android ] && [ "$(pausa_de "$APP_VETADA")" = android ]; then
        ok "recién instalados, un navegador de prueba y «$APP_VETADA_NOMBRE» ($APP_VETADA, de Fixed.APPS) quedan pausados enseguida"
      else
        mal "recién instalados y sin pausar: $NAVEGADOR_PRUEBA $(pausa_de "$NAVEGADOR_PRUEBA"), $APP_VETADA $(pausa_de "$APP_VETADA")"
      fi
    fi
  fi

  if ! entrar_admin; then
    mal "no pude entrar a la administración"
    ir_a_inicio
  else
    nivel2_administracion "$root"
  fi

  # Las de prueba se van, para que lo que sigue no las vea. No son de sistema:
  # pausadas y todo, desinstalarlas no está bloqueado.
  if (( instaladas )); then
    out=''
    for p in "$NAVEGADOR_PRUEBA" "$APP_VETADA"; do
      hay_paquete "$p" || continue
      grep -q Success <<<"$(adb_t 60 uninstall "$p" 2>&1)" || out+=" $p"
    done
    if [ -z "$out" ]; then ok "las de prueba se desinstalan sin problema: el bloqueo es sólo para las de sistema"
    else mal "no pude desinstalar:$out"; fi
  fi
  if (( root )) && [ "$uid" != 0 ]; then adb_como 2000 || aviso "adb quedó como root (adb unroot lo vuelve atrás)"; fi
}

nivel2_administracion() {   # nivel2_administracion ROOT: lo del nivel 2 que se ve desde la administración
  local root=$1 vistos='' vuelta busqueda
  # La lista de apps permitidas: ni se puede permitir. Se recorre sin tocar
  # nada y se sale con «Volver».
  if tocar_desplazando texto "$TXT_ELEGIR_APPS" && esperar_ui 10 texto "$TXT_APPS_TITULO"; then
    CAMBIAR=()
    if ! recorrer_selector; then
      mal "no pude recorrer el selector de apps"
    elif [[ -n ${VISTAS[$APP_NAVEGADOR]+x} || -n ${VISTAS[$APP_VETADA_NOMBRE]+x} ]]; then
      mal "«$TXT_ELEGIR_APPS» ofrece lo del nivel 2: $(printf '%s\n' "${!VISTAS[@]}" | grep -xF -e "$APP_NAVEGADOR" -e "$APP_VETADA_NOMBRE" | en_una_linea)"
    elif [[ -z ${VISTAS[$APP_CALENDARIO]+x} ]]; then
      mal "el selector no lista ni $APP_CALENDARIO: así no prueba nada ($(printf '%s\n' "${!VISTAS[@]}" | en_una_linea))"
    else
      ok "ni «$APP_NAVEGADOR» ni «$APP_VETADA_NOMBRE» aparecen en «$TXT_ELEGIR_APPS» (que lista ${#VISTAS[@]} apps)"
    fi
    tocar texto "$TXT_VOLVER"
  else
    mal "«$TXT_ELEGIR_APPS» no abre el selector"
  fi
  esperar_ui 10 texto "$TXT_ADMIN" || { mal "no vuelvo a la administración ($(foco))"; ir_a_inicio; return; }

  # Las páginas del nivel 1: rigen en Chrome desde que se suman.
  if ! { tocar_desplazando texto "$TXT_PAGINAS" && esperar_ui 10 texto "$TXT_LAS_TUYAS"; }; then
    mal "«$TXT_PAGINAS» no abre su pantalla"
    ir_a_inicio
    return
  fi
  ok "la administración tiene «$TXT_PAGINAS»"
  if esperar_ui 5 texto "$TXT_SIN_PAGINAS"; then
    nota "todavía no hay ninguna sumada"
  else
    aviso "ya había páginas sumadas de antes: $(paginas_tuyas | en_una_linea)"
  fi
  escribir_pagina "$PAGINA"
  if esperar_apps 10 paginas_tuyas "$PAGINA"; then
    ok "«$TXT_AGREGAR» suma $PAGINA a la lista, con «$TXT_SACAR»"
  else
    mal "después de «$TXT_AGREGAR», la lista dice «$LISTA» y tendría que ser $PAGINA"
  fi
  if (( root )); then
    if esperar 10 "grep -aq '$PAGINA' $RESTRICCIONES_CHROME" && politicas_chrome &&
      grep -qxF "$PAGINA" <<<"$(politica_chrome URLBlocklist)"; then
      ok "y Chrome la tiene en URLBlocklist enseguida"
    else
      mal "$PAGINA no llegó a URLBlocklist de Chrome"
    fi
  fi
  captura pagina-sumada
  escribir_pagina "$PAGINA_FIJA"
  if esperar_ui 10 texto "$TXT_YA_FIJA"; then
    if esperar_apps 3 paginas_tuyas "$PAGINA"; then
      ok "$PAGINA_FIJA: «$TXT_YA_FIJA», y no se suma"
    else
      mal "$PAGINA_FIJA: avisa, pero la lista dice «$LISTA»"
    fi
  else
    mal "$PAGINA_FIJA no avisa «$TXT_YA_FIJA» ($(paginas_tuyas | en_una_linea))"
  fi
  captura pagina-fija
  if tocar texto "$TXT_SACAR" && esperar_ui 10 texto "$TXT_SIN_PAGINAS"; then
    ok "«$TXT_SACAR» la saca de la lista"
  else
    mal "«$TXT_SACAR» no deja la lista vacía ($(paginas_tuyas | en_una_linea))"
  fi
  if (( root )); then
    if esperar 10 "! grep -aq '$PAGINA' $RESTRICCIONES_CHROME" && politicas_chrome &&
      ! grep -qxF "$PAGINA" <<<"$(politica_chrome URLBlocklist)" &&
      grep -qxF youtube.com <<<"$(politica_chrome URLBlocklist)"; then
      ok "y sale de URLBlocklist de Chrome, que sigue con las del nivel 2"
    else
      mal "después de «$TXT_SACAR», URLBlocklist de Chrome no quedó como antes: $(politica_chrome URLBlocklist | en_una_linea)"
    fi
  fi
  # Atrás vuelve a la administración (si quedó el teclado, el primero sólo lo cierra).
  for vuelta in 1 2 3; do
    adb_t 20 shell input keyevent KEYCODE_BACK >/dev/null 2>&1
    esperar_ui 5 texto "$TXT_ADMIN" && break
  done
  if ! ui texto "$TXT_ADMIN" >/dev/null; then
    mal "Atrás no vuelve de «$TXT_PAGINAS» a la administración ($(foco))"
    ir_a_inicio
    return
  fi

  # Soltar Device Owner ya no está: sólo por cable. Se baja hasta el pie.
  for vuelta in 1 2 3 4; do
    volcar_ui || break
    vistos+=$(ui textos | cut -f5)$'\n'
    ui contiene "$TXT_SOLO_CABLE" >/dev/null && break
    arrastrar 70 30
  done
  if grep -qiF "$TXT_SOLTAR" <<<"$vistos"; then
    mal "la administración todavía ofrece «$TXT_SOLTAR»"
  elif ! grep -qF "$TXT_SOLO_CABLE" <<<"$vistos"; then
    mal "no llegué al pie de la administración, donde dice que es «$TXT_SOLO_CABLE»"
  else
    ok "la administración ya no ofrece «$TXT_SOLTAR»: al pie dice que es $TXT_SOLO_CABLE"
  fi
  captura administracion-pie

  # En pausa, lock task se va y el nivel 2 no.
  if ! { tocar_desplazando texto "$TXT_PAUSAR" && esperar 10 "$(c_lock NONE)"; }; then
    mal "«$TXT_PAUSAR» no saca de lock task (está en $(estado))"
    ir_a_inicio
    return
  fi
  ok "«$TXT_PAUSAR» saca de lock task (NONE)"
  ir_a_inicio
  afirmar_pausada "con Slowtown en pausa"
  intento_navegador "con Slowtown en pausa" NONE
  # En pausa la lista de todas tiene todo lo instalado, menos lo del nivel 2.
  if ! abrir_cajon; then
    mal "«$TXT_TODAS» no abre la lista de todas las apps"
    ir_a_inicio
  else
    if esperar_ui 10 texto "$APP_CALENDARIO"; then
      ok "en pausa, la lista de todas tiene también las no permitidas, como $APP_CALENDARIO"
    else
      mal "en pausa, la lista de todas no muestra $APP_CALENDARIO: así no prueba nada"
    fi
    tocar campo 1
    esperar 5 "$(c_teclado)"
    sleep 1
    for busqueda in "$BUSQUEDA_NAVEGADOR" "$BUSQUEDA_VETADA"; do
      adb_t 20 shell input text "$busqueda" >/dev/null 2>&1
      # Primero que el campo diga eso: si no, el cartel puede ser de la búsqueda anterior.
      if esperar_ui 10 texto "$busqueda" && esperar_ui 10 texto "$TXT_SIN_COINCIDENCIAS"; then
        ok "buscar «$busqueda» no encuentra nada: «$TXT_SIN_COINCIDENCIAS»"
      else
        mal "buscar «$busqueda» encuentra algo: $(etiquetas_cajon | en_una_linea)"
      fi
      captura "busqueda-$busqueda"
      # Se borra, una tecla por letra, hasta que vuelve la lista entera.
      adb_t 20 shell input keyevent $(printf 'KEYCODE_DEL %.0s' $(seq ${#busqueda})) >/dev/null 2>&1
      esperar_ui 10 texto "$APP_CALENDARIO" || nota "borrar «$busqueda» no devolvió la lista entera"
    done
    volver_del_cajon || { mal "Atrás no vuelve de la lista a Inicio"; ir_a_inicio; }
  fi

  # De vuelta activo, para lo que sigue.
  if entrar_admin && activar_slowtown; then
    ok "«$TXT_ACTIVAR» lo vuelve a activar (LOCKED)"
  else
    mal "no pude volver a activar Slowtown (lock task: $(estado))"
  fi
  ir_a_inicio
  afirmar_en_inicio "de vuelta en Inicio: Slowtown y LOCKED"
}

# ── 9e. Hora de dormir ──────────────────────────────────────────────────────
# De noche, con Slowtown activo, sólo el reloj (y lo de siempre); ráfagas de
# 5 minutos sin contraseña desde Inicio. La noche se corre para que incluya la
# hora del emulador, con margen, y al final vuelve a la de fábrica. El final
# de la ráfaga se dispara como lo hace la alarma, con root.
minutos_hhmm() { [[ $1 =~ ^([0-9]{2}):([0-9]{2})$ ]] && echo $((10#${BASH_REMATCH[1]} * 60 + 10#${BASH_REMATCH[2]})); }   # «23:00» → 1380
hhmm() { printf '%02d:%02d' $(($1 / 60)) $(($1 % 60)); }   # 1380 → «23:00»
en_la_noche() {   # en_la_noche MINUTO DESDE HASTA: como Bedtime.inWindow, cruzando la medianoche
  local m=$1 d=$2 h=$3
  if (( d == h )); then return 1; elif (( d < h )); then (( m >= d && m < h )); else (( m >= d || m < h )); fi
}
hora_de_fila() {   # hora_de_fila FILA: la hora («23:00») de la fila «Empieza» o «Termina» en el último volcado
  ui textos | awk -F'\t' -v f="$1" '
    $5 == f { y = $2 }
    $5 ~ /^[0-9][0-9]:[0-9][0-9]$/ { n++; h[n] = $5; hy[n] = $2 }
    END { for (i = 1; i <= n; i++) { d = hy[i] - y; if (d < 0) d = -d; if (y != "" && d < 40) { print h[i]; exit } } }'
}
poner_hora() {   # poner_hora FILA MINUTOS: con «Más temprano» o «Más tarde» (lo más corto), hasta que la fila muestre esa hora
  local fila=$1 meta=$2 vuelta actual dif boton n xy x y
  for vuelta in 1 2 3; do
    volcar_ui || return 1
    actual=$(minutos_hhmm "$(hora_de_fila "$fila")") || return 1
    dif=$(( ((meta - actual) % 1440 + 1440) % 1440 ))   # cuántos minutos más tarde
    (( dif == 0 )) && return 0
    if (( dif <= 720 )); then boton="$TXT_MAS_TARDE$fila"; n=$(( (dif + 14) / 15 ))
    else boton="$TXT_MAS_TEMPRANO$fila"; n=$(( (1440 - dif + 14) / 15 )); fi
    xy=$(ui texto "$boton") || return 1
    read -r x y <<<"$xy"
    # Un toque por vez y con un respiro: cada uno suma 15 minutos a la hora que
    # muestra la pantalla, así que dos juntos antes de redibujar contarían uno.
    # Si igual se perdió alguno, la vuelta siguiente lo corrige.
    adb_t $((n * 2 + 30)) shell "for i in \$(seq $n); do input tap $x $y; sleep 0.2; done" >/dev/null 2>&1
    sleep 1
  done
  volcar_ui && [ "$(hora_de_fila "$fila")" = "$(hhmm "$meta")" ]
}
abrir_dormir() {   # desde Inicio hasta la pantalla «Hora de dormir» de la administración
  entrar_admin && tocar_desplazando texto "$TXT_DORMIR" && esperar_ui 10 texto "$TXT_DORMIR_PRENDIDA"
}
resumida() {   # la pantalla en primer plano, p. ej. com.slowtown.launcher/.HomeActivity
  local out
  out=$(disp dumpsys activity activities)
  sed -n '/ResumedActivity/{s/.*ResumedActivity[^{]*{[^ ]* [^ ]* //;s/ .*//;p;q}' <<<"$out"
}
alarma_de_dormir() {   # si Android tiene agendada una alarma RTC_WAKEUP para Bedtime$Tick (el próximo borde de la noche)
  local out
  out=$(disp dumpsys alarm)
  # En «dumpsys alarm», cada alarma es una línea «RTC_WAKEUP #0: Alarm{…}» y,
  # unas líneas más abajo, su «tag=*walarm*:paquete/.Clase».
  awk -v c="$PAQUETE/.admin.Bedtime\$Tick" '
    /^ *[A-Z_]+ #[0-9]+: Alarm\{/ { tipo = $1 }
    index($0, "tag=") && index($0, c) && tipo == "RTC_WAKEUP" { hay = 1 }
    END { exit !hay }' <<<"$out"
}
falta_de_noche() {   # en el último volcado de Inicio, lo que no está como de noche (nada si está todo)
  local falta=''
  ui regex "^$TXT_DORMIR_HASTA" >/dev/null || falta+=" sin «$TXT_DORMIR_HASTA…»"
  ui texto "$TXT_USAR_5" >/dev/null || falta+=" sin «$TXT_USAR_5»"
  ui texto "$APP_RELOJ" >/dev/null || falta+=" sin $APP_RELOJ"
  ui texto "$APP_QUITAR" >/dev/null && falta+=" con $APP_QUITAR"
  echo "$falta"
}
falta_de_rafaga() {   # lo mismo, durante la ráfaga
  local falta=''
  ui regex "^$TXT_RAFAGA" >/dev/null || falta+=" sin «$TXT_RAFAGA…»"
  ui texto "$APP_QUITAR" >/dev/null || falta+=" sin $APP_QUITAR"
  echo "$falta"
}
falta_de_dia() {   # lo mismo, con la hora de dormir apagada
  local falta=''
  ui texto "$APP_QUITAR" >/dev/null || falta+=" sin $APP_QUITAR"
  ui texto "$APP_RELOJ" >/dev/null || falta+=" sin $APP_RELOJ"
  ui texto "$TXT_USAR_5" >/dev/null && falta+=" con «$TXT_USAR_5»"
  ui regex "^$TXT_DORMIR_HASTA" >/dev/null && falta+=" con «$TXT_DORMIR_HASTA…»"
  echo "$falta"
}
esperar_sin_faltas() {   # esperar_sin_faltas SEGUNDOS FUNCIÓN: hasta que FUNCIÓN no diga nada del volcado; lo último que dijo queda en FALTA
  local fin=$((SECONDS + $1))
  while :; do
    if volcar_ui; then
      FALTA=$($2)
      [ -z "$FALTA" ] && return 0
    else
      FALTA=' no pude volcar la pantalla'
    fi
    (( SECONDS < fin )) || return 1
    sleep 0.5
  done
}

paso_9e() {
  titulo 9e "«$TXT_DORMIR»: de noche sólo el reloj, y ráfagas de 5 minutos sin contraseña"
  local ahora min desde=$DORMIR_DESDE hasta=$DORMIR_HASTA i f uid root=0 r
  ir_a_inicio
  if ! abrir_dormir; then
    mal "Administración no lleva a «$TXT_DORMIR»"
    ir_a_inicio
    return
  fi
  if ui texto "$TXT_DORMIR" >/dev/null; then
    ok "«$TXT_DORMIR» en la administración abre su pantalla, con «$TXT_DORMIR_PRENDIDA», «$TXT_EMPIEZA» y «$TXT_TERMINA»"
  else
    mal "la pantalla de «$TXT_DORMIR_PRENDIDA» no tiene el título «$TXT_DORMIR»"
  fi
  if [ "$(ui marcado "$TXT_DORMIR_PRENDIDA")" = "true true" ]; then
    nota "estaba prendida de una corrida anterior: la apago para arrancar de fábrica"
    tocar texto "$TXT_DORMIR_PRENDIDA"
    esperar_marcado "$TXT_DORMIR_PRENDIDA" false || mal "no pude apagar la hora de dormir que había quedado prendida"
  fi
  volcar_ui
  i=$(hora_de_fila "$TXT_EMPIEZA")
  f=$(hora_de_fila "$TXT_TERMINA")
  if [ "$i" = "$(hhmm "$DORMIR_DESDE")" ] && [ "$f" = "$(hhmm "$DORMIR_HASTA")" ]; then
    ok "de fábrica, la noche va de $i a $f"
  else
    # Una corrida anterior pudo cortarse con la noche corrida.
    mal "la noche tendría que ir de $(hhmm "$DORMIR_DESDE") a $(hhmm "$DORMIR_HASTA") y va de ${i:-?} a ${f:-?}: la vuelvo a la de fábrica"
    poner_hora "$TXT_EMPIEZA" "$DORMIR_DESDE" && poner_hora "$TXT_TERMINA" "$DORMIR_HASTA" ||
      { mal "no pude volverla a la de fábrica"; ir_a_inicio; return; }
  fi

  # Que la noche tenga adentro la hora del emulador, con margen: si falta poco
  # para que termine (o terminó hace poco), se corre el final media hora más
  # allá de ahora; si no es de noche, se adelanta el principio hasta ahora.
  ahora=$(disp date +%H:%M)
  if ! min=$(minutos_hhmm "$ahora"); then
    mal "no entiendo la hora del emulador («$ahora»)"
    ir_a_inicio
    return
  fi
  if (( min >= DORMIR_HASTA - 30 && min < DORMIR_HASTA + 30 )); then
    hasta=$(( (min + 30 + 14) / 15 * 15 ))
  elif ! en_la_noche "$min" "$DORMIR_DESDE" "$DORMIR_HASTA"; then
    desde=$(( min / 15 * 15 ))
  fi
  if (( desde != DORMIR_DESDE )) && ! poner_hora "$TXT_EMPIEZA" "$desde"; then
    mal "no pude correr el principio de la noche a las $(hhmm "$desde") (dice ${i:-?})"
  fi
  if (( hasta != DORMIR_HASTA )) && ! poner_hora "$TXT_TERMINA" "$hasta"; then
    mal "no pude correr el final de la noche a las $(hhmm "$hasta")"
  fi
  volcar_ui
  i=$(hora_de_fila "$TXT_EMPIEZA")
  f=$(hora_de_fila "$TXT_TERMINA")
  if [ "$i" = "$(hhmm "$desde")" ] && [ "$f" = "$(hhmm "$hasta")" ]; then
    ok "en el emulador son las $ahora: la noche queda de $i a $f, con los botones de a 15 minutos"
  else
    mal "la noche tendría que quedar de $(hhmm "$desde") a $(hhmm "$hasta") y quedó de ${i:-?} a ${f:-?}"
  fi
  tocar texto "$TXT_DORMIR_PRENDIDA"
  if esperar_marcado "$TXT_DORMIR_PRENDIDA" true; then
    ok "«$TXT_DORMIR_PRENDIDA» la prende"
  else
    mal "tocar «$TXT_DORMIR_PRENDIDA» no la prendió ($(ui marcado "$TXT_DORMIR_PRENDIDA"))"
  fi
  captura hora-de-dormir

  # De noche, la lista de lock task se achica al reloj (y lo de siempre).
  if esperar 15 "! $(c_en_lock_task "$PAQUETE_CAMARA")"; then
    ok "de noche, $APP_QUITAR ($PAQUETE_CAMARA) sale de la lista de lock task"
  else
    mal "de noche, $APP_QUITAR sigue en la lista de lock task"
  fi
  if esperar 5 "$(c_en_lock_task "$PAQUETE_RELOJ")"; then
    ok "y el reloj ($PAQUETE_RELOJ) sigue, para que suene la alarma"
  else
    mal "de noche, el reloj ($PAQUETE_RELOJ) salió de la lista de lock task"
  fi
  r=$(estado)
  if [ "$r" = LOCKED ]; then ok "y Slowtown sigue LOCKED"; else mal "prender la hora de dormir dejó el lock task en ${r:-?}"; fi

  ir_a_inicio
  if esperar_sin_faltas 15 falta_de_noche; then
    ok "Inicio de noche: «$TXT_DORMIR_HASTA…», «$TXT_USAR_5» y $APP_RELOJ, sin $APP_QUITAR"
  else
    mal "Inicio de noche no está como tendría que estar:$FALTA"
  fi
  captura inicio-de-noche
  disp am start -n "$CAMARA_ACTIVIDAD" >/dev/null
  if esperar 10 "$(c_resumida "$FOCO_BLOQUEO")" && ! esperar 1 "$(c_resumida "$PAQUETE_CAMARA/")"; then
    ok "de noche, abrir $APP_QUITAR con am start da «App is not available» ($FOCO_BLOQUEO)"
  else
    mal "de noche, am start de $APP_QUITAR dejó en primer plano $(resumida)"
  fi
  adb_t 20 shell input keyevent KEYCODE_BACK >/dev/null 2>&1
  sleep 1
  ir_a_inicio

  # La ráfaga: sin contraseña, vuelven las permitidas.
  if ! tocar_en_inicio texto "$TXT_USAR_5"; then
    mal "no encuentro «$TXT_USAR_5» en Inicio"
  else
    if esperar_sin_faltas 15 falta_de_rafaga; then
      ok "«$TXT_USAR_5»: Inicio dice «$TXT_RAFAGA…» y vuelve $APP_QUITAR"
    else
      mal "después de «$TXT_USAR_5», Inicio no está como tendría que estar:$FALTA"
    fi
    captura rafaga
    if esperar 15 "$(c_en_lock_task "$PAQUETE_CAMARA")"; then
      ok "durante la ráfaga, $APP_QUITAR vuelve a la lista de lock task"
    else
      mal "durante la ráfaga, $APP_QUITAR no vuelve a la lista de lock task"
    fi
    disp am start -n "$CAMARA_ACTIVIDAD" >/dev/null
    # Si es la primera vez, la cámara puede abrir encima el pedido de permisos.
    if esperar 10 "dumpsys activity activities | grep -qE 'ResumedActivity.*($PAQUETE_CAMARA/|permissioncontroller)'"; then
      ok "durante la ráfaga, $APP_QUITAR abre ($(resumida))"
    else
      mal "durante la ráfaga, am start de $APP_QUITAR dejó en primer plano $(resumida)"
    fi

    # El final, como lo dispara la alarma: con root, que el receptor no se exporta.
    uid=$(uid_adb)
    if [ "$uid" = 0 ] || adb_como 0; then
      root=1
    else
      mal "no pude pasar adb a root: sin eso no puedo terminar la ráfaga antes de los 5 minutos"
    fi
    if (( root )); then
      disp "am broadcast -n '$PAQUETE/.admin.Bedtime\$Tick' --ez fin_de_rafaga true" >/dev/null
      if esperar 10 "! $(c_en_lock_task "$PAQUETE_CAMARA")"; then
        ok "al terminar la ráfaga, $APP_QUITAR sale otra vez de la lista de lock task"
      else
        mal "terminada la ráfaga, $APP_QUITAR sigue en la lista de lock task"
      fi
      if esperar 10 "$(c_resumida "$PAQUETE/.HomeActivity")"; then
        ok "y Android cierra $APP_QUITAR: queda Inicio de Slowtown"
      else
        mal "terminada la ráfaga, en primer plano quedó $(resumida)"
      fi
      if [ "$uid" != 0 ]; then adb_como 2000 || aviso "adb quedó como root (adb unroot lo vuelve atrás)"; fi
    fi
    cerrar_cartel_bloqueo
    ir_a_inicio
  fi
  if alarma_de_dormir; then
    ok "Android tiene agendada una alarma RTC_WAKEUP de $PAQUETE/.admin.Bedtime\$Tick: el final de la noche"
  else
    mal "dumpsys alarm no tiene ninguna alarma RTC_WAKEUP de $PAQUETE/.admin.Bedtime\$Tick"
  fi

  # Apagarla devuelve el día, y la noche vuelve a la de fábrica para lo que sigue.
  if ! abrir_dormir; then
    mal "no pude volver a «$TXT_DORMIR» para apagarla"
    ir_a_inicio
    return
  fi
  tocar texto "$TXT_DORMIR_PRENDIDA"
  if esperar_marcado "$TXT_DORMIR_PRENDIDA" false; then
    ok "«$TXT_DORMIR_PRENDIDA» la apaga"
  else
    mal "tocar «$TXT_DORMIR_PRENDIDA» no la apagó ($(ui marcado "$TXT_DORMIR_PRENDIDA"))"
  fi
  if esperar 15 "$(c_en_lock_task "$PAQUETE_CAMARA")"; then
    ok "apagada, $APP_QUITAR vuelve a la lista de lock task"
  else
    mal "apagada, $APP_QUITAR no vuelve a la lista de lock task"
  fi
  if poner_hora "$TXT_EMPIEZA" "$DORMIR_DESDE" && poner_hora "$TXT_TERMINA" "$DORMIR_HASTA"; then
    (( desde != DORMIR_DESDE || hasta != DORMIR_HASTA )) &&
      ok "la noche vuelve a la de fábrica: de $(hhmm "$DORMIR_DESDE") a $(hhmm "$DORMIR_HASTA")"
  else
    mal "no pude volver la noche a la de fábrica (de $(hhmm "$DORMIR_DESDE") a $(hhmm "$DORMIR_HASTA"))"
  fi
  ir_a_inicio
  if esperar_sin_faltas 15 falta_de_dia; then
    ok "Inicio apagada: vuelve $APP_QUITAR, sin «$TXT_USAR_5»"
  else
    mal "con la hora de dormir apagada, Inicio no está como tendría que estar:$FALTA"
  fi
  afirmar_en_inicio "la hora de dormir apagada deja todo como antes: Slowtown y LOCKED"
}

# ── 10. Ajustes desde la administración ─────────────────────────────────────
paso_10() {
  titulo 10 "Ajustes desde la administración"
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

# ── 11. Reinicio ────────────────────────────────────────────────────────────
paso_11() {
  titulo 11 "Reinicio"
  local antes boot='' fin
  adb_t 60 logcat -d >"$SALIDA/logcat-antes-del-reinicio.txt" 2>/dev/null
  antes=$(disp cat /proc/sys/kernel/random/boot_id)
  if [ -z "$antes" ]; then
    mal "no pude leer el boot_id: sin él no sabría si de verdad reinició"
    return
  fi
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
  # La pausa la guarda Android: no depende de que Slowtown llegue a arrancar.
  afirmar_pausada "después de reiniciar"
  # Los hábitos, Slowtown en sus preferencias: tienen que seguir ahí.
  if [ -n "$HABITO_HOY" ]; then
    if grep -qxF "$HABITO_HOY" <<<"$(dias_de_habito ejercicio)" &&
      grep -qxF "$HABITO_AYER" <<<"$(dias_de_habito meditar)"; then
      ok "después de reiniciar siguen guardados ejercicio $HABITO_HOY y meditar $HABITO_AYER"
    else
      mal "después de reiniciar faltan hábitos: ejercicio $(dias_de_habito ejercicio | en_una_linea); meditar $(dias_de_habito meditar | en_una_linea)"
    fi
    ir_a_inicio
    afirmar_habitos "después de reiniciar"
  fi
  captura tras-reinicio
}

# ── 12. Inicio fijo sin duplicados ──────────────────────────────────────────
paso_12() {
  titulo 12 "Inicio fijo sin duplicados"
  local i xml n
  ir_a_inicio
  for i in 1 2 3 4 5; do
    disp am start -n "$RELOJ_ACTIVIDAD" >/dev/null
    esperar 10 "$(c_foco "$FOCO_RELOJ")" || aviso "vuelta $i: el Reloj no llegó al frente"
    ir_a_inicio || aviso "vuelta $i: Inicio no volvió a Slowtown"
  done
  nota "5 vueltas a Inicio desde el Reloj"
  # Las reglas de Inicio fijo sólo se ven en package-restrictions.xml, que es
  # de system: hace falta root (la imagen del emulador es userdebug).
  if ! adb_como 0; then
    mal "no pude pasar adb a root"
    return
  fi
  xml=$(package_restrictions)
  adb_como 2000 || aviso "adb quedó como root (adb unroot lo vuelve atrás)"
  printf '%s\n' "$xml" >"$SALIDA/package-restrictions.xml"
  if ! grep -q '<package-restrictions' <<<"$xml"; then
    mal "no pude leer package-restrictions.xml"
    return
  fi
  n=$(reglas_inicio_fijo <<<"$xml")
  if [ "$n" = 1 ]; then
    ok "hay exactamente 1 regla de Inicio fijo para Slowtown"
  elif [ "$n" = 0 ]; then
    mal "no hay ninguna regla de Inicio fijo para Slowtown"
  else
    mal "hay $n reglas de Inicio fijo para Slowtown: se duplican al volver a Inicio"
  fi
}

# ── 13. Guardia de crashes ──────────────────────────────────────────────────
paso_13() {
  titulo 13 "Guardia de crashes"
  local i pid crashes=0 solo=1
  ir_a_inicio
  for i in 1 2 3; do
    # am crash sólo le llega a un proceso vivo que ya terminó de arrancar: se
    # espera que Slowtown tenga el foco. Un crash que no llega no se cuenta, y
    # el modo a prueba de fallos no se activaría nunca. Si en cambio aparece el
    # selector de Inicio, el modo llegó antes (con crashes de una corrida
    # anterior, de hace menos de diez minutos).
    if ! esperar 40 "{ pidof $PAQUETE >/dev/null && $(c_foco "$FOCO_INICIO"); } || $(c_foco "$FOCO_SELECTOR")"; then
      mal "antes del crash $i, Slowtown no volvió a primer plano ($(foco))"
      break
    fi
    if [[ $(foco) == *"$FOCO_SELECTOR"* ]]; then
      nota "el selector de Inicio apareció después de $crashes crashes"
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
  # La guardia abre el teléfono, pero el nivel 2 no lo toca.
  afirmar_pausada "con la guardia de crashes abierta"
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

# ── 14. La salida por cable ─────────────────────────────────────────────────
# Primero la de siempre: liberar.sh le pide a Slowtown que se suelte
# (CableProvider), y Slowtown deshace el nivel 2, abre el teléfono, suelta el
# Inicio fijo, queda en pausa y deja de ser Device Owner. Atiende sólo a shell
# y a root. Después el último recurso, liberar.sh --forzar, como si Slowtown
# no contestara (paso_14_forzar). Después de cada una se vuelve a provisionar.
paso_14() {
  titulo 14 "Salida por cable"
  local salida rc p xml n
  local soltar=(content call --uri "content://$PAQUETE.cable" --method soltar)
  # Como en el teléfono, adb sin root: liberar.sh llega como shell (uid 2000).
  adb_como 2000 || mal "no pude sacarle root a adb: liberar.sh llamaría a Slowtown como root"

  # Nadie más la puede soltar. Desde el uid de una app (run-as), Android ya no
  # deja que «content» llegue (le falta ACCESS_CONTENT_PROVIDERS_EXTERNALLY);
  # system (uid 1000) sí llega, y ahí la que tiene que decir que no es Slowtown.
  salida=$(disp run-as "$PAQUETE" "${soltar[@]}")
  if grep -q 'Result:' <<<"$salida" || ! grep -q SecurityException <<<"$salida"; then
    mal "run-as $PAQUETE ${soltar[*]}: no lo rechazó ($(grep -m1 -E 'Result|Exception' <<<"$salida"))"
  else
    ok "desde el uid de la app (run-as), «content call» no llega a Slowtown: Android lo rechaza"
  fi
  salida=$(disp su 1000 "${soltar[@]}")
  if grep -q 'Sólo por cable' <<<"$salida"; then
    ok "como system (uid 1000) sí llega, y Slowtown lo rechaza: «$(grep -o 'Sólo por cable[^"]*' <<<"$salida" | head -n 1)»"
  elif grep -q 'Result:' <<<"$salida"; then
    mal "como system (uid 1000), Slowtown se soltó: sólo tiene que atender a shell y root"
  else
    aviso "no pude llamar como system (uid 1000): $(head -n 1 <<<"$salida")"
  fi
  p=$(pausa_de "$NAVEGADOR")
  if ! es_device_owner; then
    mal "después de esos intentos, Slowtown ya no es Device Owner: la soltó otro que no es adb"
  elif [ "$p" != android ]; then
    mal "después de esos intentos, $APP_NAVEGADOR no está pausado ($p)"
  else
    ok "después de esos intentos, Slowtown sigue siendo Device Owner y $APP_NAVEGADOR sigue pausado"
  fi

  salida=$(cd "$RAIZ" && ./scripts/liberar.sh </dev/null 2>&1)
  rc=$?
  printf '%s\n' "$salida"
  if (( rc == 0 )); then ok "liberar.sh termina bien"; else mal "liberar.sh falló"; fi
  if grep -q "$TXT_SOLTADA_VUELVEN .*$NAVEGADOR" <<<"$salida"; then
    ok "liberar.sh dice que $NAVEGADOR se puede abrir otra vez"
  else
    mal "liberar.sh no dice que $NAVEGADOR se puede abrir otra vez"
  fi
  if esperar 15 "$(c_lock NONE)"; then ok "lock task en NONE"; else mal "lock task sigue en $(estado)"; fi
  if es_device_owner; then mal "Slowtown sigue siendo Device Owner"; else ok "Slowtown ya no es Device Owner"; fi
  p=$(pausa_de "$NAVEGADOR")
  if [ "$p" = no ]; then ok "$APP_NAVEGADOR ya no está pausado"; else mal "$APP_NAVEGADOR sigue pausado ($p)"; fi
  # Queda en pausa: si se la vuelve a provisionar, no se cierra sola.
  if esperar 5 "run-as $PAQUETE cat shared_prefs/slowtown.xml | grep -q 'name=\"closed\" value=\"false\"'"; then
    ok "queda en pausa: closed=false en sus preferencias"
  else
    mal "sus preferencias no dicen closed=false: $(disp run-as "$PAQUETE" cat shared_prefs/slowtown.xml | grep -o '<boolean name="closed"[^>]*>')"
  fi
  # Sin el bloqueo, desinstalarlo para el usuario anda, y vuelto a poner ya no
  # está pausado (sin Device Owner, nadie lo pausa).
  salida=$(disp pm uninstall -k --user 0 "$NAVEGADOR")
  disp cmd package install-existing "$NAVEGADOR" >/dev/null
  p=$(pausa_de "$NAVEGADOR")
  if grep -q Success <<<"$salida" && hay_paquete "$NAVEGADOR" && [ "$p" = no ]; then
    ok "ya no está bloqueado: se desinstala para el usuario, se vuelve a poner y no está pausado"
  else
    mal "después de soltarla: pm uninstall dijo «$(head -n 1 <<<"$salida")», y vuelto a poner quedó pausado: $p"
  fi
  # Lo que queda guardado en el sistema, con root y después sin root otra vez.
  if adb_como 0; then
    if politicas_chrome; then
      if [ -z "$(politica_chrome URLBlocklist)" ]; then
        if [ -z "$RESTRICCIONES" ]; then ok "Android ya no guarda políticas para Chrome (borró el archivo)"
        else ok "las políticas de Chrome ya no tienen URLBlocklist"; fi
      else
        mal "Chrome sigue con URLBlocklist: $(politica_chrome URLBlocklist | head -n 3 | en_una_linea)…"
      fi
    else
      mal "no pude leer las políticas de Chrome ($RESTRICCIONES_CHROME)"
    fi
    xml=$(package_restrictions)
    printf '%s\n' "$xml" >"$SALIDA/package-restrictions-liberado.xml"
    n=$(reglas_inicio_fijo <<<"$xml")
    if ! grep -q '<package-restrictions' <<<"$xml"; then mal "no pude leer package-restrictions.xml"
    elif [ "$n" = 0 ]; then ok "y suelta el Inicio fijo: ninguna regla para Slowtown"
    else mal "quedan $n reglas de Inicio fijo para Slowtown"; fi
    adb_como 2000 || aviso "adb quedó como root (adb unroot lo vuelve atrás)"
  else
    mal "no pude pasar adb a root para ver lo que quedó en el sistema"
  fi
  captura liberado

  nota "vuelvo a provisionar"
  if volver_a_provisionar; then
    # Soltada en pausa: volver a Inicio no la cierra sola.
    ir_a_inicio
    sostener 3 "$(c_lock NONE)"
    afirmar_en_inicio "otra vez Device Owner, pero en pausa como quedó: volver a Inicio no la cierra" NONE
  fi
  paso_14_forzar
  nota "al terminar: lock task $(estado), foco $(foco)"
}

volver_a_provisionar() {   # provisionar.sh, y que el nivel 2 vuelva solo
  # Como en el teléfono, con Slowtown de Inicio común: la instalación la
  # vuelve a abrir antes de que sea Device Owner, y el nivel 2 tiene que
  # llegar igual, sin esperar a otro arranque.
  disp cmd package set-home-activity "$INICIO_COMPONENTE" >/dev/null
  ir_a_inicio || nota "Inicio no lleva a Slowtown antes de volver a provisionar ($(foco))"
  if (cd "$RAIZ" && ./scripts/provisionar.sh </dev/null) && es_device_owner; then
    ok "provisionar.sh la deja otra vez como Device Owner"
  else
    mal "no pude volver a provisionar"
    return 1
  fi
  if esperar 15 "$(c_pausada "$NAVEGADOR")" && [ "$(pausa_de "$NAVEGADOR")" = android ]; then
    ok "y el nivel 2 vuelve solo: $APP_NAVEGADOR pausado otra vez"
  else
    mal "otra vez Device Owner, pero $APP_NAVEGADOR no está pausado ($(pausa_de "$NAVEGADOR")): el nivel 2 no llegó"
  fi
}

# El último recurso: liberar.sh --forzar saca a Slowtown con «dpm
# remove-active-admin», sin preguntarle (SLOWTOWN_SIN_SOLTAR=1 hace como si no
# contestara). Antes lee de sus preferencias lo que pausó, y después les saca
# la pausa a las de sistema desinstalándolas para el usuario y volviéndolas a
# poner. Android se lleva solo las políticas de Chrome y el bloqueo de
# desinstalar; el Inicio fijo queda, y Slowtown no queda en pausa.
paso_14_forzar() {
  local salida rc p n
  # Activa, para que haya algo que abrir.
  if entrar_admin && activar_slowtown; then
    ok "activa otra vez (LOCKED), para probar el último recurso"
  else
    mal "no pude activar Slowtown antes de --forzar (lock task: $(estado))"
  fi
  ir_a_inicio
  adb_como 2000 || mal "no pude sacarle root a adb"
  salida=$(cd "$RAIZ" && SLOWTOWN_SIN_SOLTAR=1 ./scripts/liberar.sh --forzar </dev/null 2>&1)
  rc=$?
  printf '%s\n' "$salida"
  if (( rc == 0 )); then ok "liberar.sh --forzar termina bien"; else mal "liberar.sh --forzar falló (salió $rc)"; fi
  if esperar 15 "$(c_lock NONE)"; then ok "--forzar abre el teléfono: lock task en NONE"; else mal "después de --forzar, lock task sigue en $(estado)"; fi
  if es_device_owner; then mal "después de --forzar, Slowtown sigue siendo Device Owner"; else ok "después de --forzar, Slowtown ya no es Device Owner"; fi
  p=$(pausa_de "$NAVEGADOR")
  if [ "$p" = no ] && grep -q "$TXT_FORZADA_VUELVE $NAVEGADOR\$" <<<"$salida"; then
    ok "le saca la pausa a $APP_NAVEGADOR, que es de sistema, y lo dice: «$TXT_FORZADA_VUELVE $NAVEGADOR»"
  else
    mal "después de --forzar, $APP_NAVEGADOR quedó pausado: $p ($(grep -c "$TXT_FORZADA_VUELVE" <<<"$salida") líneas «$TXT_FORZADA_VUELVE»)"
  fi
  if hay_paquete "$NAVEGADOR"; then
    nota "$NAVEGADOR sigue instalado para el usuario 0"
  else
    mal "--forzar dejó $NAVEGADOR desinstalado para el usuario 0"
    disp cmd package install-existing "$NAVEGADOR" >/dev/null
  fi
  # Las políticas de Chrome las borra Android en segundo plano (se miran con
  # root), pero sólo las de las apps instaladas: sin Chrome, quedan.
  if adb_como 0; then
    if esperar 10 "ls /data/system/users/0 >/dev/null 2>&1 && [ ! -e $RESTRICCIONES_CHROME ]"; then
      ok "Android borra las políticas de Chrome al sacar el Device Owner"
    elif ! hay_paquete "$CHROME"; then
      aviso "las políticas de Chrome siguen guardadas: Android las borra sólo de las apps instaladas (clearApplicationRestrictions, Android 12), y este emulador no tiene Chrome; en el teléfono Chrome está"
    else
      mal "Chrome está instalado y sus políticas siguen guardadas después de --forzar"
    fi
    n=$(package_restrictions | reglas_inicio_fijo)
    if [ "$n" = 1 ]; then nota "el Inicio fijo queda (1 regla), como avisa liberar.sh"
    else aviso "liberar.sh avisa que el Inicio fijo queda, y quedaron $n reglas"; fi
    adb_como 2000 || aviso "adb quedó como root (adb unroot lo vuelve atrás)"
  else
    mal "no pude pasar adb a root para ver lo que quedó en el sistema"
  fi
  captura forzado

  nota "vuelvo a provisionar para dejar el emulador listo"
  volver_a_provisionar || return
  # --forzar no la deja en pausa: al volver a ser Device Owner, Slowtown rearma
  # la política sola, y se cierra en la primera vuelta a Inicio.
  esperar 15 "dumpsys activity activities | grep -q 'u0:\[.*$PAQUETE[],]'" ||
    nota "la lista de lock task todavía no tiene a Slowtown"
  ir_a_inicio
  if esperar 10 "$(c_lock LOCKED)"; then
    afirmar_en_inicio "estaba activa al forzarla: otra vez Device Owner, se cierra en la primera vuelta a Inicio"
  else
    mal "estaba activa al forzarla, y otra vez Device Owner no se cierra al volver a Inicio (lock task: $(estado))"
  fi
}

# ── Principal ───────────────────────────────────────────────────────────────
COMPILAR=0
LIMPIO=0
for arg in "$@"; do
  case $arg in
    --build) COMPILAR=1 ;;
    --limpio) LIMPIO=1 ;;
    -h | --help)
      sed -n '2,/^# Mientras corre, no tocar/{s/^# \{0,1\}//;p}' "$0"
      exit 0
      ;;
    *)
      echo "No conozco la opción $arg (ver --help)." >&2
      exit 2
      ;;
  esac
done

PATH="$PATH:$SDK/platform-tools"
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
paso_4b
paso_5
paso_6
paso_7
paso_8
paso_8b
paso_8c
paso_9
paso_9b
paso_9c
paso_9d
paso_9e
paso_10
paso_11
paso_12
paso_13
paso_14
resumen
(( ${#FALLAS[@]} == 0 ))
