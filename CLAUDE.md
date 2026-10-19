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

Sin Device Owner, Slowtown es sólo un launcher: se esquiva bajando el panel y
tocando el engranaje. Lo que cierra de verdad es Device Owner, que se activa
por cable con `scripts/provisionar.sh`. «Activar Slowtown» es lock task:

- sólo se abren las apps de Inicio y las esenciales (`Essentials`: llamadas,
  SMS, emergencias, alertas y las pantallas de sistema que abren las apps);
- el panel de notificaciones queda, pero sin ajustes rápidos ni engranaje;
- no hay Recientes, e Inicio es siempre Slowtown;
- Ajustes y todo lo demás se alcanza sólo desde la administración, que pide
  la contraseña (mantener apretada la hora).

## Inicio

- **Permitidas** (`Prefs.allowed`): las que se pueden abrir con Slowtown
  activo. Se eligen en la administración, con contraseña, porque aflojan las
  reglas. La contraseña la elige y la guarda otra persona (decisión de
  Marcos).
- **En Inicio** (`Prefs.favorites`): cuáles de las permitidas se ven en
  Inicio. Sin contraseña, porque no aflojan nada; se ponen y se sacan
  manteniendo apretada una app en la lista de todas.
- **Todas las apps**: deslizando para arriba (o «↑ todas las apps»). Arriba
  las carpetas, en dos columnas; debajo, las apps que no están en ninguna.
  El buscador ignora tildes, busca también adentro de las carpetas y Enter
  abre la primera; el teclado se abre recién al tocarlo (lo pidió Marcos).
  Con Slowtown activo lista sólo las permitidas.
- **Carpetas** (`Prefs.folders`, JSON): se crean, renombran y borran en la
  configuración, no en la lista: Marcos quiere menos botones en el uso
  diario. Una app se mueve a una carpeta manteniéndola apretada en la lista.
  Son sólo orden: sin contraseña.
- **Widget**: uno, pegado al borde de abajo (`AppWidgetHost`). Se elige en la
  administración y se arma en una sesión de administración, porque la
  configuración del widget es de su app. `provisionar.sh` corre `appwidget
  grantbind` para que Android no muestre su diálogo de permiso, que es de
  Ajustes. Para que tocarlo abra su app, esa app tiene que estar permitida.

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
3. **Siempre hay una salida por cable.** El build de debug es `testOnly`
   (`app/src/debug/AndroidManifest.xml`) para que `scripts/liberar.sh` pueda
   sacar a Slowtown de Device Owner. Se instala con `adb install -t`.
   `provisionar.sh` se niega a provisionar un APK sin testOnly.
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
   sacar todas las cuentas del teléfono. Antes del primer uso en el S10e hay
   que fijar una clave estable, como hace `scripts/firma.sh` en Waltra.
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
punta (instalar, activar, intentos de escape, llamada, SMS, Ajustes, reinicio,
crashes, salida por cable) y termina con código 1 si algo falla. Al teléfono
no llega nada que no haya pasado por acá.

En el emulador se simulan llamadas y SMS: `adb emu gsm call <número>`,
`adb emu gsm cancel <número>`, `adb emu sms send <número> <texto>`, y un crash
de Slowtown con `adb shell am crash com.slowtown.launcher`. `uiautomator dump`
sólo ve la ventana de la app, no los avisos del sistema (para tocar «Atender»
hacen falta coordenadas).

**Antes de cerrar un teléfono real, marcar «Permitir siempre desde esta
computadora»** en el diálogo de adb: con lock task activo no se puede
autorizar una computadora nueva, y sin eso `liberar.sh` no llega.

## La red contra el peor caso

El peor caso es Slowtown colgándose en loop mientras es el Inicio fijo y el
teléfono está cerrado: el rescate de Android para launchers no toca lo que
fijó un Device Owner. `CrashGuard` cuenta los crashes y, con tres en diez
minutos, abre el teléfono y suelta el Inicio fijo hasta que alguien lo revise
desde la administración. Si algo cambia en el arranque de `HomeActivity`,
probar que esto siga andando (forzar un crash en el emulador).
