# Slowtown — notas para trabajar en este repo

Launcher propio que convierte el Galaxy S10e de Marcos (SM-G970U, Android 12 /
One UI 4.1, API 31, su teléfono de todos los días) en un dumbphone que sigue
siendo útil: llamadas, SMS, WhatsApp, banco y Mercado Pago, Maps y Uber,
música y búsqueda de Google. Nada de entretenimiento. Kotlin + Jetpack
Compose, una sola actividad (`HomeActivity`).

Lo que se investigó antes de escribir código (Device Owner en Android 12,
filtro de páginas, desbloqueo con fricción, cómo preparar el S10e) está en
`docs/investigacion.md`, con fuentes.

## Cómo cierra el teléfono

Dos maneras, según el teléfono, con las mismas reglas (`admin/Rules.kt`: qué
se puede abrir ahora y por qué no lo demás). Sin cable, la guardia (ver «Sin
cable: la guardia»): se esquiva más fácil, pero no pide adb ni sacar las
cuentas. Lo que cierra de verdad es Device Owner, que se activa por cable con
`scripts/provisionar.sh`. Con él, «Activar Slowtown» es lock task:

- sólo se abren las apps de Inicio y las esenciales (`Essentials`: llamadas,
  SMS, emergencias, alertas y las pantallas de sistema que abren las apps);
- el panel de notificaciones queda, pero sin ajustes rápidos ni engranaje;
- no hay Recientes, e Inicio es siempre Slowtown;
- Ajustes y todo lo demás se alcanza sólo desde la administración, que pide
  la contraseña (mantener apretada la hora o la fecha, arriba de todo). La
  excepción es la información de una app (ver Inicio).

## Inicio

- **Permitidas** (`Prefs.allowed`): las que se pueden abrir con Slowtown
  activo. Se eligen en la administración, con contraseña, porque aflojan las
  reglas. Ajustes y la Galaxy Store no se pueden elegir; la Play Store sí,
  desde el 22/10 (Marcos la quiere a mano para bajar una app fuera de casa):
  lo que se instala no abre hasta permitirlo, y lo del nivel 2 se pausa
  apenas se instala. La contraseña es larga, Marcos no la memoriza y la deja anotada
  en papel en su casa (decisión suya): aflojar algo exige estar ahí.
- **En Inicio** (`Prefs.favorites`): cuáles de las permitidas se ven en
  Inicio. Sin contraseña, porque no aflojan nada; se ponen y se sacan
  manteniendo apretada una app en la lista de todas.
- **Todas las apps**: deslizando para arriba. «↑ todas las apps», el renglón
  que lo abre con un toque, viene escondido desde el 27/10 (Marcos no lo
  quiere a la vista) y se muestra en «Cómo se ve Inicio». Arriba
  las carpetas, en dos columnas; debajo, las apps que no están en ninguna.
  El buscador ignora tildes, busca también adentro de las carpetas y Enter
  abre la primera; el teclado se abre recién al tocarlo (lo pidió Marcos).
  Con Slowtown activo lista sólo las permitidas.
- **Carpetas** (`Prefs.folders`, JSON): se crean, renombran y borran en la
  configuración, no en la lista: Marcos quiere menos botones en el uso
  diario. Una app se mueve a una carpeta manteniéndola apretada en la lista
  («Mover a una carpeta» y después cuál). Son sólo orden: sin contraseña.
- **Mantener apretada una app** en la lista: Inicio, carpeta, ocultar,
  «Información de la app» y «Desinstalar». La información es una pantalla de
  Ajustes y no pide contraseña (lo decidió Marcos el 22/10: desde ahí se
  llega a los ajustes de esa app, y a la Play Store, que no le preocupa): con
  Slowtown activo se abre en una sesión chica, que suma sólo Ajustes a la
  lista y termina como la de administración. Desinstalar no la pide (sacar una app no afloja nada) y no
  se ofrece para las apps de sistema (desinstalarles las actualizaciones
  borraría la pausa del nivel 2) ni para las que tienen la desinstalación
  bloqueada. Siendo Device Owner pregunta Slowtown y desinstala sin el
  diálogo de Android, que con lock task no se abre.
- **Ocultas** (`Prefs.hiddenApps`): no aparecen al recorrer la lista ni
  adentro de las carpetas, pero sí al buscarlas. Para las que tienen que
  estar y andar de fondo sin ocupar lugar, como el teclado (lo pidió Marcos).
  Sin contraseña.
- **Cómo se ve Inicio** (en la administración): el tamaño de todo Inicio junto
  (70, 80, 90 o 100 %, `Prefs.homeScalePercent`), la hora grande
  (`Prefs.showClock`, prendida de entrada) y cada parte por separado (lo pidió
  Marcos): la hora, la fecha, el tiempo de pantalla, los avisos, las apps,
  «↑ todas las apps» y los hábitos, cada una con su tamaño (de 60 a 150 %,
  sobre el de todo Inicio) y su lugar a lo ancho (`Prefs.homeBlocks`, JSON; de
  entrada, como era Inicio). «Todas las apps» además se saca (`HomeBlock.hideable`,
  y viene sacado); la fecha no, porque sin la hora grande es lo que se mantiene
  apretado para entrar. El tiempo de pantalla comparte el renglón de la
  hora (sin ella, el de la fecha) si van en lugares distintos y entran; si no,
  va justo debajo. El widget de otra app no cambia. Arriba, fija, una vista
  previa: Inicio en miniatura, armado con las mismas piezas (`HomeContent`).
- **Una sola hora en Inicio** (`StatusBarClock`): con la hora grande a la
  vista, la de la barra de arriba se esconde mientras Inicio está al frente, y
  vuelve en cualquier otra pantalla (Marcos no la quiere repetida). Android no
  deja que una app esconda sólo la hora de la barra: se usa la lista de íconos
  escondidos de SystemUI (`icon_blacklist`), un ajuste seguro, con
  `WRITE_SECURE_SETTINGS` que da `provisionar.sh` por adb. Guarda cómo estaba
  la lista y la deja igual al mostrarla; la salida por cable también. Sin la
  hora grande, la fecha encabeza Inicio y la barra queda como siempre.
- **La hora de Inicio** se mira una vez por segundo mientras Inicio se ve, y
  apenas vuelve a verse o recupera el foco (`rememberMinute`). Antes esperaba
  el minuto siguiente con un delay, que no corre con el teléfono dormido: al
  desbloquear mostraba unos segundos la hora vieja, y a veces quedaba minutos
  atrás de la del panel (lo vio Marcos el 27/10).
- **Widget**: uno, pegado al borde de abajo (`AppWidgetHost`). Se elige en la
  administración y se arma en una sesión de administración, porque la
  configuración del widget es de su app. `provisionar.sh` corre `appwidget
  grantbind` para que Android no muestre su diálogo de permiso, que es de
  Ajustes. Para que tocarlo abra su app, esa app tiene que estar permitida.

## Hábitos y tiempo de pantalla

- **Hábitos** (`apps/Habits.kt`, `ui/Habits.kt`, `ui/HabitIcons.kt`): sin app
  aparte. Van en el lugar del widget, y vienen puestos (`Prefs.habitsWidget`,
  salvo que ya haya un widget de otra app); en el selector de widget son
  «Hábitos de Slowtown». Una fila por hábito: a la izquierda, un fuego con la
  racha (semanas seguidas, de lunes a domingo, con la meta cumplida,
  `Habits.streak`), que pasa de brasa a fuego fuerte con las semanas; los
  últimos 7 días, como los cuadraditos de GitHub, hoy a la derecha; cuánto
  lleva contra la meta en esos 7 días; y a la derecha, pegado al día de hoy,
  el botón que lo marca. En una pantalla angosta (el S10e) los cuadraditos se
  achican para que entre todo. Tocar
  los cuadraditos abre «Hábitos», el resumen por semanas, donde se puede marcar
  un día pasado, y de ahí «Elegir hábitos»: un catálogo de 14 (`HabitKind`,
  cada uno con su dibujo hecho con Canvas, sin emojis), hasta 4 en Inicio, cada
  uno con sus veces por semana (de 1 a 7). De entrada, lo que Marcos se propuso:
  meditar 2 y ejercicio 3. Sin contraseña: no afloja nada. El plan se guarda en
  `habit_plan` (JSON) y los días como fechas ISO, por hábito (`habit_meditar`,
  `habit_leer`…): sacar un hábito no borra sus días.
- **Tiempo de pantalla** (`apps/ScreenTime.kt`): arriba a la derecha de
  Inicio, sólo el número («1 h 5 min»; Marcos quiere menos texto en Inicio, y
  el lector de pantalla dice «Hoy: 1 h 5 min de pantalla»). Tocarlo abre
  Bienestar digital (`ScreenTime.wellbeing`: el de Samsung puede no tener
  ícono, y entonces se busca su pantalla principal; en el S10e abre su resumen
  del día), que está siempre en la lista de lock
  task: muestra el uso y suma límites, no afloja nada. Mantenerlo apretado
  entra a la administración, como el resto de la cabecera. Bienestar digital no comparte sus números, así que se
  calcula igual que él, con los datos de uso de Android (sin contar Inicio).
  Necesita el acceso a los datos de uso, que da `provisionar.sh` por adb
  (`appops set … GET_USAGE_STATS allow`); sin eso, la línea no aparece.

## Bedtime

`admin/Bedtime.kt`, en la administración («Bedtime», con contraseña; la hora
se elige con dos ruedas, horas y minutos, `ui/NumberWheel.kt`):
de noche (de entrada, de 23 a 7; Marcos quiere dejar de usar el teléfono tan
tarde), con Slowtown activo, la lista de lock task se achica a lo esencial
más el reloj y las alarmas (`Bedtime.apps`: lo que atiende `AlarmClock`, más
Alarmy, `droom.sleepIfUCan`, con la que Marcos se despierta, y los relojes de
fábrica). Las alarmas están en la lista siempre, de día también: si no, con
Slowtown activo la alarma de la mañana no podría mostrar su pantalla.

- **Qué noches** (`Prefs.bedtimeDays`, de entrada todas): un círculo por día
  en la pantalla de Bedtime, para dejar libres, por ejemplo, el viernes y el
  sábado (lo pidió Marcos). Cada noche es del día en que empieza
  (`Bedtime.nightOf`): la del viernes va del viernes a las 23 al sábado a las
  7, y una que empieza de madrugada es de la noche anterior. Los bordes se
  agendan todos los días igual: al llegar, la revisión decide.

- **Ráfagas de 5 minutos** (sin contraseña y sin tope: la fricción es volver
  a pedirla): de noche Inicio dice «Es *Bedtime*, andá a dormir», y tocándolo
  aparece «Usar 5 minutos» (Marcos no lo quiere siempre a la vista). Vuelven
  las apps permitidas, y al terminar el sistema cierra lo que quedó afuera.
- El principio y el final los agenda el sistema (`Bedtime.Tick`, alarma
  exacta: `provisionar.sh` da `SCHEDULE_EXACT_ALARM` por adb), así que llegan
  aunque haya otra app abierta. Al volver a Inicio, además,
  `Policies.refreshPackages` pone la lista al día (escribe sólo si cambió), y
  un reinicio vuelve a agendar desde `SlowtownApp`.
- `Policies.effectivePackages` decide la lista: sesión en curso, todo; noche
  sin ráfaga, con el teléfono en uso y sin llamada, lo esencial y las alarmas;
  si no, lo de siempre. Las sesiones de administración y «Habilitar todo»
  siguen abriendo todo, también de noche.
- **No corta llamadas** (`admin/CallGuard.kt`, lo pidió Marcos). Las del
  teléfono pasan siempre (`Essentials`), pero las de WhatsApp son de una app.
  Mientras hay una llamada (el modo de audio, o una notificación de llamada
  de una app permitida, que le pasa `WavesListener`), la noche espera y la app
  de la llamada está en la lista; al cortar, a los 5 segundos se achica (el
  margen es por las apps que cambian la notificación al atender). Mientras
  dura, se vuelve a mirar cada minuto.
- **Rige con el teléfono en uso** (`Bedtime.inUse`: pantalla prendida y
  desbloqueado). Con la pantalla apagada Android abre la pantalla de una
  llamada que entra antes de que el lector la vea, y con la lista de la noche
  quedaba bloqueada (lo vio la prueba en el emulador). Al apagar la pantalla
  vuelve la lista de siempre, y al desbloquear (`USER_PRESENT`) se achica.
  Sin desbloquear sólo se abre lo que se muestra encima de la pantalla de
  bloqueo, como la cámara segura.
- En el emulador, el final de una ráfaga se dispara a mano con root:
  `adb shell "am broadcast -n 'com.slowtown.launcher/.admin.Bedtime\$Tick' --ez fin_de_rafaga true"`.

## Flow State y límites por app

Los dos los pidió Marcos el 27/10, y los dos viven en la administración, con
contraseña (achicarlos afloja). Rigen con Slowtown activo, con cable o sin
él, y frenan una app de las permitidas: con Device Owner sale de la lista de
lock task (Android la cierra si estaba abierta); sin cable, la guardia vuelve
a Inicio. En Inicio quedan en gris, y tocarlas dice por qué y hasta cuándo.
Nunca frenan lo que no puede faltar (`Rules.always`) ni la app de una llamada.

- **Flow State** (`admin/FlowState.kt`, `ui/FlowStateScreen.kt`), «así como
  está Bedtime»: varios horarios, cada uno con sus ruedas y sus días (cada uno
  es del día en que empieza, como las noches), y las mismas apps en todos (por
  paquete). De entrada, de lunes a viernes de 9 a 13, apagado. Inicio dice
  «Es *Flow State* hasta las 13:00», y tocándolo aparece la ráfaga de 5
  minutos, la misma de Bedtime: una ráfaga suelta las dos. Los bordes los
  agenda `Bedtime.schedule`, junto con los de la noche.
- **Límites por app** (`admin/Limits.kt`, `ui/LimitsScreen.kt`): minutos por
  día por paquete, de a 15 (hasta 8 h). Cuenta el tiempo de pantalla de cada
  app (`ScreenTime.byApp`, igual que el total de Inicio). Sin ráfagas: el
  límite es del día. Recorrer el uso del día cuesta, así que lo mira la
  guardia cuando la app con límite llega adelante y cuando se le acaba el
  rato (también con una alarma, por si Slowtown no sigue corriendo), Inicio
  al volver, y los horarios a la medianoche. Lo que llegó se guarda con la
  fecha (`Prefs.limitsReached`) y al cambiar el día deja de valer solo. Con
  Device Owner la guardia corre sólo para esto: lock task no sabe cuánto
  lleva cada app.

## Sin cable: la guardia

`admin/Guard.kt`. Sin Device Owner no hay lock task: la guardia lee los
eventos de uso de Android cada medio segundo (`UsageStatsManager.queryEvents`,
con el teléfono en uso) y, si adelante queda una app que no corresponde
(`Rules.hold`), abre Inicio, que dice por qué. Es lo que hacen las apps de
bloqueo de la Play Store (`docs/sin-cable.md`, lo pidió Marcos para no
cerrarse la puerta de Play), con lo mínimo: el acceso a los datos de uso, que
se da en Ajustes sin cable, y ser el Inicio (Android le deja al Inicio abrir
pantallas desde segundo plano). Sin servicio de accesibilidad ni ventanas
encima: los bancos los miran mal.

- **Activar sin cable** (`Rules.Mode.GUARD`): la administración deja activar
  Slowtown si tiene las dos cosas, y si falta una, ofrece darla. Rige todo lo
  mismo que con lock task: las permitidas, Bedtime, Flow State, los límites y
  las dos sesiones (con una, la guardia deja pasar). El instalador de Android
  pasa sólo un par de minutos después de que Slowtown lo abre (para
  desinstalar una app de la lista o confirmar una actualización,
  `Guard.allowInstaller`): si pasara siempre, una app permitida que ofrezca
  desinstalar podría sacar a Slowtown. La pantalla de Ajustes del permiso
  para instalar, que pide la actualización sin Device Owner, va en una sesión
  chica, como la información de una app. No frena la barra del sistema ni
  los Inicios (el de fábrica asoma mientras Slowtown se actualiza, y desde él
  o sus Recientes sólo se salta a otras apps), salvo Ajustes, que también se
  anuncia como Inicio (su pantalla de respaldo del arranque, con prioridad
  negativa): dejarlo pasar era dejar pasar Ajustes entero, y lo vio el paso 15
  de la prueba.
- **Se esquiva**: el modo seguro, desinstalar desde la Play Store si está
  permitida, o una sesión de administración usada para sacar el acceso a los
  datos de uso. La app frenada llega a verse menos de un segundo. El nivel 2
  no existe sin Device Owner: lo de `Fixed.APPS` frena sólo por no estar
  permitido.
- **En el S10e** (27/10, con la 0.4.0, probado por adb y vuelto a pausar):
  frenó Ajustes, Maps y el engranaje del panel en menos de un segundo, y con
  el Reloj adelante 5 minutos Samsung no congeló la guardia (el proceso queda
  perceptible, `prcl`). Falta la prueba larga y el consumo (`docs/sin-cable.md`,
  sección 7).

## Notificaciones por oleadas

`notif/Waves.kt`, `notif/WavesListener.kt` y «Notificaciones» en la
administración (con contraseña: apagarlas o sacar una app afloja). Las de las
apps elegidas no llegan apenas salen: `WavesListener` (un lector de
notificaciones) le pide a Android que posponga la original
(`snoozeNotification`) hasta la próxima entrega (de entrada, 12 y 19), y a esa
hora Android la vuelve a publicar: se toca y se responde como siempre. Nunca
esperan las llamadas, las alarmas, las llamadas perdidas, la música ni lo que
está en curso. «Pasan siempre»: palabras enteras (un nombre, «urgente») que
hacen llegar una notificación en el momento.

- Tiene su propio archivo de preferencias (`slowtown_notif`), y anota lo que
  pospuso para reconocer su entrega aunque llegue tarde.
- Límites de Android 12: avisa después de hacerla sonar (una retenida puede
  sonar un instante); lo pospuesto vive en memoria y un reinicio lo borra (la
  app la vuelve a publicar o se pierde); si la app actualiza una ya pospuesta,
  sigue esperando.
- El permiso lo da `provisionar.sh` por adb (`cmd notification
  allow_listener`); sin él, la pantalla lo avisa y no se retiene nada.
- **Llegan en silencio**: Android 12 vuelve a publicar sin sonido todo lo que
  sale de una posposición, también las entregas de las 12 y las 19.
- **«Sólo los grupos»**, por app (para WhatsApp, lo pidió Marcos): esperan los
  chats de grupo y los de a dos llegan en el momento. El grupo se reconoce por
  `EXTRA_IS_GROUP_CONVERSATION`, por el JID de WhatsApp (`…@g.us` es un grupo,
  `…@s.whatsapp.net` una persona) o por un título con mensajes de dos
  personas o más; ante la duda, llega.
- **El resumen del grupo** («5 mensajes de 3 chats»), visto en el emulador:
  al posponer lo último que se ve de un grupo, Android pospone también su
  resumen, y mientras el resumen está pospuesto todo lo nuevo de su grupo se
  pospone con él antes de avisarle al lector. WhatsApp junta todos los chats
  en un grupo: en modo normal, desde la primera que espera, lo que llega
  después espera sin pasar por Slowtown (ni «pasan siempre» lo saca). Con
  «sólo los grupos» el resumen se suelta enseguida, así los de a dos llegan,
  pero queda a la vista con las últimas líneas de los grupos que esperan.
- **App de prueba**: `scripts/prueba-avisos/` (`armar.sh DIR`, sin Gradle)
  publica chats como los de WhatsApp, con su resumen, y llamadas con
  pantalla completa, para probar todo esto en el emulador.

## Las dos maneras de abrir todo un rato

- **Sesión de administración** (`AdminSession`, con contraseña): «Ir a
  Ajustes» o «Ir a Play Store». Termina al volver a Inicio, al apagar la
  pantalla o a los 10 minutos.
- **«Habilitar todo por 5 minutos»** (`FreeSession`, sin contraseña): está en
  la pantalla de la contraseña. Nació para actualizar Waltra, que baja su APK
  de GitHub y necesita el instalador de Android. Hasta 2 veces cada 12 horas
  (ventana móvil, contada con la hora del teléfono; por eso Slowtown activo
  bloquea cambiarla a mano con `DISALLOW_CONFIG_DATE_TIME`). No se corta al
  volver a Inicio ni al apagar la pantalla: sólo a los 5 minutos.

Las dos agrandan la lista de lock task a todo lo instalado, sin salir de lock
task (salir cerraría las demás tareas). «Habilitar todo» incluye Ajustes a
propósito: desde Ajustes hay herramientas de desarrollador con las que se
podría saltear el nivel 2, y Marcos lo sabe y lo acepta (19/10). No sacar
Ajustes de ahí ni apagar la depuración inalámbrica sin preguntarle. El final lo agenda el sistema con una
alarma, y `Policies.apply` respeta una sesión en curso: rearmar la política no
la corta antes. En el emulador, el final se dispara a mano con root:
`adb shell "am broadcast -n 'com.slowtown.launcher/.admin.FreeSession\$End'"`.

## Los dos niveles

Marcos los pidió para no acostumbrarse a aflojar: una contraseña que está en
un cajón de su casa se termina sacando.

- **Nivel 1, la configuración** (con contraseña): las apps permitidas, las
  páginas que se suman en «Páginas bloqueadas» (`Prefs.blockedSites`),
  pausar Slowtown y las dos sesiones.
- **Nivel 2, el código** (`Fixed`): no se cambia desde el teléfono, ni con la
  contraseña; sólo con una versión nueva: por cable, o una release de GitHub
  que Slowtown baja sola (ver «Releases»; Marcos aceptó ese camino el 23/10).
  Son las políticas de Chrome (`Chrome`: sin incógnito,
  SafeSearch y el filtro de sitios de Chrome, sin DNS propio de Chrome, sin
  noticias en la pestaña nueva, y `URLBlocklist`) y las apps
  pausadas (`Vetoed`: `Fixed.APPS` y
  cualquier navegador que no sea Chrome, también los deshabilitados). Nunca se
  pausa nada de `Vetoed.NEVER`, de `Essentials`, un launcher ni un teclado.
- **Las páginas del nivel 2**: Chrome lee hasta 1.000. Primero
  `Fixed.CORE_SITES` (a mano: YouTube, TikTok, Instagram, el streaming),
  después las del nivel 1 y al final la base, `res/raw/paginas_fijas.txt`:
  la lista de bloqueo por defecto de la Universidad Toulouse Capitole (UT1,
  CC BY-SA 4.0). De cada categoría (redes sociales, video y streaming, juegos
  online, apuestas, citas, contenido para adultos, entre otras) van las más
  visitadas según el ranking Tranco, más algunos dominios enteros como
  `bet.ar`. La arma `scripts/paginas-fijas.py`,
  que también explica lo que se saca a propósito (`NUNCA`): ajedrez online
  (lo pidió Marcos), música, podcasts y radio, ayuda para dejar el juego,
  dominios compartidos y lo técnico. Las listas completas, que son millones,
  son para el DNS.

El nivel 2 se pone en cada arranque, cada vez que se instala o cambia una app
y al quedar como Device Owner (`SlowtownApp`; esto último porque, si Slowtown
ya era el Inicio, `provisionar.sh` la reabre antes de `set-device-owner` y la
revisión del arranque llega tarde), con Slowtown activo o en pausa. Aguanta
las sesiones y el modo seguro porque lo guarda Android. «Dejar de ser Device
Owner» ya no está en la administración: sólo por cable.

Pausadas y no escondidas: la pausa sobrevive a las actualizaciones de Play, y
en Ajustes se ve qué pasa. Probado en el emulador: la pausa queda a nombre de
`android`, adb no la saca (`pm unsuspend` no hace nada) ni puede volver a
mostrar una app escondida (no tiene `MANAGE_USERS`), y desinstalar Slowtown no
la levanta. Por eso la salida por cable pasa por Slowtown. Lo único que la
borra es volver a la versión de fábrica de una app de sistema («Desinstalar
actualizaciones»): a esas, y a Chrome, Slowtown les bloquea desinstalar
(`setUninstallBlocked`), y sacar el Device Owner lo suelta solo. Abrir una app
pausada con Slowtown activo muestra «Ajustes no está disponible»: Android
quiere mostrar su aviso de administrador, que es de Ajustes.

## Reglas que no se rompen

1. **Ajustes y el launcher de fábrica nunca se deshabilitan, esconden ni
   suspenden.** En la tablet del depósito eso fue un boot loop y un factory
   reset: Ajustes trae la pantalla de inicio de respaldo que usa el sistema al
   arrancar. Quedan fuera de alcance sólo por no estar en la lista de lock task.
2. **Tampoco se tocan los paquetes de telefonía, SMS e IMS** de Samsung:
   `com.android.phone`, `com.android.server.telecom`,
   `com.android.providers.telephony`, `com.samsung.android.incallui`,
   `com.samsung.android.app.telephonyui`, `com.samsung.android.providers.contacts`,
   `com.sec.imsservice`, `com.sec.ims`, ni `com.vzw.apnlib` si está. Ni
   SystemUI, `com.android.providers.settings`, `com.android.shell` o el
   instalador de paquetes.
3. **Siempre hay una salida por cable.** `scripts/liberar.sh` le pide a
   Slowtown, con `adb shell content call` (`CableProvider`, que sólo atiende a
   shell y root), que deshaga el nivel 2, abra el teléfono y deje de ser
   Device Owner, y queda en pausa (con un candado: una revisión del nivel 2
   no se puede cruzar con la salida). Si Slowtown no contesta, se instala una
   versión que ande y se vuelve a correr. El último recurso, `liberar.sh
   --forzar`, usa `dpm remove-active-admin`, que anda porque el build de debug
   es `testOnly` (`app/src/debug/AndroidManifest.xml`). Después saca la pausa a
   las apps de sistema (las que tienen la pausa de `android` según `dumpsys
   package`: las desinstala para el usuario y las vuelve a poner, con sus
   datos); las de Play quedan pausadas hasta reinstalarlas. Se instala con
   `adb install -t`. `provisionar.sh` mira testOnly en el APK antes de
   instalarlo, y antes de provisionar muestra qué va a pausar el nivel 2
   (`content call … --method mostrar`): en un teléfono real, antes del «SI».
4. **Primero el emulador** (Android 12, API 31, sin cuentas). Al teléfono sólo
   llega lo que ya anduvo ahí, con llamadas y SMS probados con el teléfono
   cerrado.
5. **Las restricciones duras van al final**: `DISALLOW_DEBUGGING_FEATURES`,
   `DISALLOW_FACTORY_RESET` y `DISALLOW_SAFE_BOOT` juntas, más un bug que
   cuelgue a Slowtown, dejan como única salida reflashear el teléfono.
6. **Nunca `DISALLOW_INSTALL_APPS`**: bloquea también las actualizaciones de
   Play, y WhatsApp y el banco dejan de actualizarse.
7. **La firma no cambia.** Un Device Owner no se desinstala: cambiar la clave
   obliga a liberarlo, desinstalar y provisionar de nuevo, y eso último exige
   sacar todas las cuentas del teléfono. La clave estable vive fuera del repo,
   en `~/slowtown-firma/` (la creó `scripts/firma.sh` el 19/10; tiene que
   haber una copia fuera de la compu), y `assembleDebug` firma con ella. Su
   huella está en `scripts/firma.sha256`, y `provisionar.sh` se niega a poner
   en un teléfono real un APK firmado con otra. Nunca regenerarla. CI compila
   con una clave descartable: lo que sale de CI no se instala encima.
8. El S10e tiene Waltra con datos reales: nunca instalar Waltra encima con
   otra firma.

## Compilar y probar

El SDK está en `~/Android/Sdk` (sin sudo; `local.properties` lo apunta, y
está ignorado por git). De Java 21 sólo está el runtime (`jre21-openjdk`), así
que Gradle corre en el JDK 26 del sistema, y anda con AGP 9.4.1 + Gradle 9.8.1.
En CI corre en Temurin 21.

```bash
./gradlew assembleDebug      # APK de prueba (testOnly) en app/build/outputs/apk/debug/
./scripts/emulador.sh        # Android 12 sin cuentas (AVD slowtown-api31)
./scripts/provisionar.sh     # instala y deja a Slowtown como Device Owner
./scripts/liberar.sh         # la salida por cable
```

La máquina tiene 6,7 GB de RAM. El emulador, aun con 1,5 GB asignados, ocupa
unos 3 GB reales, y el build otros 2: el 19/10 el kernel lo mató por falta de
memoria a mitad de un build. Compilar antes de levantarlo, o cerrar Firefox.

`./scripts/probar-emulador.sh --limpio` corre todo el recorrido de punta a
punta (con `--pasos=4c,9h,15`, sólo esos, además de instalar y activar) (instalar, activar, intentos de escape, llamada, SMS, Ajustes, nivel 2,
reinicio, crashes, salida por cable, también con `--forzar`, y al final la
guardia sin cable) y termina con código 1 si algo falla. El navegador del emulador (WebView Browser Tester) es
el que el nivel 2 pausa; para probar las instalaciones nuevas, la prueba arma
dos APK mínimos con aapt2. Chrome no está en el emulador: se verifica que
Android guarde las políticas, no que Chrome las cumpla. Al teléfono
no llega nada que no haya pasado por acá. Corre sólo en un emulador: si el
S10e también está conectado, lo deja de lado (todo va con `-s` y
`ANDROID_SERIAL`). `liberar.sh` y `provisionar.sh` llaman a `adb` a secas:
con dos dispositivos conectados, a mano, siempre con `ANDROID_SERIAL`.

En el emulador se simulan llamadas y SMS: `adb emu gsm call <número>`,
`adb emu gsm cancel <número>`, `adb emu sms send <número> <texto>`, y un crash
de Slowtown con `adb shell am crash com.slowtown.launcher`. `uiautomator dump`
sólo ve la ventana de la app, no los avisos del sistema (para tocar «Atender»
hacen falta coordenadas).

**Antes de cerrar un teléfono real, marcar «Permitir siempre desde esta
computadora»** en el diálogo de adb: con lock task activo no se puede
autorizar una computadora nueva, y sin eso `liberar.sh` no llega.

## El logo

El reloj de arena inclinado, con el pueblo abajo (`docs/logo/slowtown.svg`):
lo eligió Marcos el 23/10, entre las propuestas de `docs/logo/propuestas*`,
inspiradas en «Slowtown» de Twenty One Pilots. Blanco y negro con detalles en
ámbar. `docs/logo/a_vector.py` lo pasa al ícono adaptable
(`res/drawable/ic_launcher_foreground.xml`, achicado para que nada pase de 35
dp del centro): si cambia el SVG, se vuelve a correr, no se edita a mano.

## Releases

Para los amigos de Marcos y quien quiera (lo pidió el 22/10): la variante
release, sin testOnly (se instala tocando el APK), firmada con la clave de
releases. Es otra clave que la del S10e: `scripts/firma-releases.sh` la creó
el 22/10 en `~/slowtown-firma-releases/` (copia en el pendrive, junto a la
otra), y vive en los secretos del repo `SLOWTOWN_RELEASE_KEYSTORE_B64` y
`SLOWTOWN_RELEASE_PASSWORD`. La del S10e no sale nunca de la compu. Perder la
de releases deja sin actualizaciones a todos los que la instalaron.

- Publicar: `git tag vX.Y.Z && git push origin vX.Y.Z`. El workflow
  `.github/workflows/release.yml` compila, compara la huella con
  `scripts/firma-releases.sha256` y publica `Slowtown-X.Y.Z.apk` con notas
  (cómo se instala y los cambios desde la etiqueta anterior). El versionCode
  sale de la etiqueta: X·10000 + Y·100 + Z.
- En esta compu, `./gradlew assembleRelease` firma desde
  `~/slowtown-firma-releases/` (para probar antes de etiquetar).
- **El S10e pasó a la release de GitHub** (23/10: Marcos desinstaló la de
  debug, que tenía la clave personal). Las actualizaciones de GitHub se
  instalan encima sin perder nada. Para el paso 3 no sirve tal cual, porque
  `liberar.sh --forzar` necesita testOnly: ese día se instala encima una
  variante de cable (testOnly) firmada con la clave de releases, que conserva
  la configuración, y `provisionar.sh` tiene que aceptar también la huella de
  `scripts/firma-releases.sha256`. La idea es que testOnly queda fijado al
  activar el administrador y las releases siguientes no lo sacan: confirmarlo
  en el emulador antes.
- **Se actualiza sola** (`update/Updater.kt`; lo pidió Marcos el 23/10 para
  no conectar el cable). Sólo lo firmado con la clave de releases: la de
  cable no busca nada. Al volver a Inicio, cada 6 horas como mucho, le
  pregunta a la API de GitHub por la última release, y si es más nueva Inicio
  muestra un renglón gris: «Versión X.Y.Z disponible · Actualizar». Recién al
  tocarlo baja `Slowtown-X.Y.Z.apk` al caché, verifica con Android que sea
  Slowtown, más nueva y firmada con la misma clave, y la instala con
  `PackageInstaller` a nombre de Slowtown. Con Device Owner no pregunta nada,
  también con Slowtown activo; sin él, Android pide antes «Instalar apps
  desconocidas» para Slowtown y quizás una confirmación. En la administración
  están la versión, «Buscar actualización» y, si falla, lo que dijo Android.
  Con esto el nivel 2 cambia también con una release (Marcos lo aceptó). Play
  Protect frenó el APK bajado con Chrome, pero no lo que instala Slowtown: el
  S10e se actualizó solo a la 0.3.2 (23/10) y a la 0.4.0 (27/10). Falta probar en el
  emulador qué hace lock task cuando Slowtown se reemplaza estando activa.

## La red contra el peor caso

El peor caso es Slowtown colgándose en loop mientras es el Inicio fijo y el
teléfono está cerrado: el rescate de Android para launchers no toca lo que
fijó un Device Owner. `CrashGuard` cuenta los crashes y, con tres en diez
minutos, abre el teléfono y suelta el Inicio fijo hasta que alguien lo revise
desde la administración. Si algo cambia en el arranque de `HomeActivity`,
probar que esto siga andando (forzar un crash en el emulador).
