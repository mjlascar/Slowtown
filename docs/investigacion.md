# Investigación: hasta dónde se puede cerrar un Android

Relevado el 19/10/2026 para el Galaxy S10e de Marcos (SM-G970U, Android 12 /
One UI 4.1, API 31, último parche de seguridad de marzo de 2023). Lo marcado
como **probado** se verificó en el emulador con Android 12; el resto sale de
la documentación oficial y del código de AOSP, y en el teléfono real puede
cambiar porque Samsung modifica el sistema.

## En corto

| Nivel | Qué cierra | Qué queda abierto |
|---|---|---|
| Sólo launcher | Lo que se ve en Inicio | El panel, el engranaje, Ajustes, cambiar de launcher, modo seguro |
| Launcher + adb (sin Device Owner) | Apps deshabilitadas, escala de grises, DNS privado | Lo mismo de arriba: es fricción, no un muro |
| **Device Owner + lock task** | Sólo abren las apps elegidas, sin Recientes ni ajustes rápidos, Inicio fijo, Ajustes y Play Store detrás de la contraseña, DNS fijo | Modo seguro (si no se bloquea), adb (salida por cable) |

Slowtown usa el tercero. El costo está en preparar el teléfono, no en el código.

## Device Owner en Android 12

- **Provisión:** `adb shell dpm set-device-owner <admin>`. Falla si hay
  cualquier cuenta (Google, Samsung, WhatsApp, operador) o usuario extra
  (Carpeta segura es el usuario 150, Dual Messenger otro). No exige factory
  reset si se sacan antes.
- **No se desinstala.** Sólo sale si la propia app llama a
  `clearDeviceOwnerApp()` o con un factory reset. Con `android:testOnly`,
  además, `adb shell dpm remove-active-admin` lo saca por cable: es la salida
  de emergencia (probado). El flag se fija al activar el administrador.
- **Lock task**, funciones (API 28): HOME, NOTIFICATIONS (panel sin ajustes
  rápidos), SYSTEM_INFO, KEYGUARD (sin ella no hay pantalla de bloqueo aunque
  haya PIN), GLOBAL_ACTIONS (menú de apagado), y BLOCK_ACTIVITY_START_IN_TASK
  (API 30: sin ella una app permitida abre dentro de su tarea pantallas de
  paquetes no permitidos, Ajustes incluido). Probado: Ajustes, Calendario,
  Recientes y el «Manage» del panel quedan bloqueados.
- **Orden:** primero las funciones y después la lista. Al revés, el sistema
  puede entrar en lock task antes de que esté KEYGUARD y sacar el PIN (le pasó
  a LMNOPhone, un proyecto con este mismo diseño).
- **Salir de lock task desde la tarea raíz cierra todas las demás tareas**
  (Maps navegando, una llamada de WhatsApp). Agrandar la lista no cierra nada:
  por eso la sesión de administración agranda la lista en vez de salir.
- **El Inicio fijo** (`addPersistentPreferredActivity`) no se deduplica: cada
  llamada suma una regla en disco (probado: 30 copias). Tampoco se borra al
  sacar el Device Owner; sólo la API o desinstalar la app.
- **El rescate de Android para un launcher que se cuelga** borra la
  preferencia común pero no la persistente: sin una guardia propia, un crash
  en loop deja el teléfono trabado.
- **`DISALLOW_INSTALL_APPS` bloquea también las actualizaciones de Play**
  (WhatsApp y el banco dejan de actualizarse). LMNOPhone vio que con Play
  instalado, con sesión y fuera de la lista, Play actualiza solo en segundo
  plano.
- **Modo seguro:** mantener «Apagar» en el menú reinicia sin apps de
  terceros: Slowtown no corre y quedan el launcher de fábrica, Ajustes y las
  apps de sistema (en un Samsung, YouTube y Chrome lo son). Lo cierra
  `DISALLOW_SAFE_BOOT`, pero el modo seguro es también la última salida sin
  herramientas.
- **adb vence:** Android revoca la autorización de una computadora que no se
  conecta en 7 días (`adb_allowed_connection_time`), y con lock task activo el
  diálogo para volver a autorizarla no puede aparecer. `provisionar.sh` lo
  deja en 0 (no vence).
- **Lo que Device Owner apaga solo:** el servicio de copia de seguridad
  (Slowtown lo vuelve a activar). En Android 12 `getOrganizationName` tira
  SecurityException siendo Device Owner.
- **Escala de grises:** el Device Owner no puede ponerla. Por adb:
  `settings put secure accessibility_display_daltonizer_enabled 1` y
  `accessibility_display_daltonizer 0`, o `pm grant <app>
  android.permission.WRITE_SECURE_SETTINGS` para que la app la maneje.

## Llamadas, SMS y emergencias

- La llamada entrante llega como aviso y necesita NOTIFICATIONS. La pantalla
  de llamada es otro paquete: en Samsung `com.samsung.android.incallui`, que
  tiene que estar en la lista (KEYGUARD sólo exime al marcador del sistema).
  Probado en el emulador: aviso, Atender, cortar y SMS con Slowtown activo.
- En Android 12 las **alertas de emergencia** (cell broadcast) no están
  exentas de lock task; la excepción llegó en Android 13. Su paquete va en la
  lista.
- `Essentials` lee del sistema el marcador, la pantalla de llamada (servicios
  con `IN_CALL_SERVICE_UI`), el marcador de emergencia y el receptor de
  alertas, con una lista fija de piso.
- Pendiente en el S10e: la primera llamada después de instalar no mostró el
  aviso una vez en el emulador (no se repitió); el marcador de emergencia de
  One UI; la tecla Bixby y los Edge panels.
- Picture in picture no funciona en lock task (Maps, videollamadas).

## Filtrar páginas sin extensión

Recomendación para una primera versión: dos capas.

1. **DNS privado fijo** (`setGlobalPrivateDnsModeSpecifiedHost` +
   `DISALLOW_CONFIG_PRIVATE_DNS`). Cubre todo el teléfono, también el
   reproductor de YouTube dentro de WhatsApp y los navegadores internos de las
   apps. NextDNS bloquea por servicio (su YouTube incluye googlevideo.com,
   ytimg.com y youtubei.googleapis.com) y por categoría; el plan gratis tiene
   300k consultas por mes y al pasarse deja de bloquear, así que conviene Pro
   (US$ 19,90 por año). Hostname: `<nombre>-<id>.dns.nextdns.io`. Riesgos: el
   modo estricto deja sin DNS en una red que bloquee el puerto 853, y el panel
   del proveedor se apaga desde una PC (mitigación: que la cuenta la tenga otra
   persona).
2. **Políticas de Chrome** por `setApplicationRestrictions` sobre
   `com.android.chrome`: `URLBlocklist` (`youtube.com` bloquea también los
   subdominios), `IncognitoModeAvailability=1`, `DnsOverHttpsMode=off`,
   `ForceGoogleSafeSearch`, `NTPContentSuggestionsEnabled=false` (sin
   Discover), `ProxySettings` directo. Las listas van como `String[]` o JSON.
   Las WebView leen `com.android.browser:URLBlocklist` de la app que las
   contiene. Samsung Internet no tiene políticas: se esconde.

`URLBlocklist` lee hasta 1.000 entradas (Google lo documenta en «Allow or
block access to websites»). Las listas por categoría tienen millones (UT1:
4,6 millones de eróticas, 51 mil de apuestas, 34 mil de juegos), así que a
Chrome va una base con las más visitadas de cada una según Tranco, y las
listas completas quedan para el DNS. Una entrada sin subdominio tapa todo un
dominio: `bet.ar` (las apuestas legales de Argentina) o `xxx`. Las listas
mezclan cosas: en «apuestas» de UT1 estaba Gamblers Anonymous, en «juegos»
Starlink, y en «video» radios y podcasts. Hay que revisarlas a mano.

Huecos a tapar: `translate.goog` (proxy de Google), AMP, frontends como
Invidious, navegadores que renderizan en sus servidores (Opera Mini, Puffin) y
VPNs (`DISALLOW_CONFIG_VPN`). Segunda versión posible: una VPN local que sólo
mira DNS, con listas propias (Block List Project, UT1, hagezi), para no
depender de un panel ajeno; ojo que lockdown con un túnel sólo-DNS corta todo
lo demás.

## Desbloquear con fricción de verdad

Una contraseña que sabe el dueño es fricción débil. Lo que muestran los
estudios:

- **Una pausa con opción de desistir** (one sec, PNAS 2023, n=280): se
  abandonó el 36% de los intentos y las aperturas bajaron 57%.
- **Cambios que rigen al día siguiente** (Allcott et al., AER 2022): el uso
  bajó 16% durante 12 semanas. La tentación se disipa en unos 2 minutos.
- **Tipear un texto largo** (LocknType, CHI 2019): 30 dígitos disuadieron el
  47,5% de las aperturas.

Propuesta: un tag NFC (cualquier sticker NTAG213, se valida el UID de 7
bytes, que no se puede cambiar) como única llave para aflojar reglas, guardado
lejos, con el cambio rigiendo después de un enfriamiento; endurecer es
inmediato. Para usar una app extra un rato, una pantalla tipo one sec con
espera creciente y un botón grande de «No, volver». Un pase de emergencia
semanal, y si se pierde el tag, un código de un solo uso que dicta otra
persona. En el S10e el NFC lee sólo con la pantalla desbloqueada, y la antena
está en el centro de la espalda.

## Preparar el S10e

Recomendación: sin factory reset. Deshabilitar por un rato las apps de
terceros que tienen cuenta (`pm disable-user --user 0 com.whatsapp`; Android
oculta sus cuentas sin borrarlas), sacar a mano sólo las cuentas de Google y
Samsung, borrar Carpeta segura y Dual Messenger, provisionar y volver a poner
todo. Así quedan los tokens de los bancos y las sesiones. No deshabilitar
`com.google.android.gms` ni la cuenta Samsung con ese truco.

Lo que se pierde con Device Owner, se haga como se haga: Carpeta segura, Dual
Messenger, Samsung Pass, restaurar con Smart Switch, Quick Share y Samsung
Kids. Samsung Pay no anda en Argentina igual.

Antes de empezar:

1. Copia completa con Smart Switch a la PC.
2. Guardar `pm list packages -3`, `pm list users`, `dumpsys account` y
   `getprop ro.build.version.security_patch`.
3. Google: códigos de respaldo y un segundo factor fuera del teléfono;
   contactos en contacts.google.com; anotar las tarjetas de Wallet.
4. Samsung: contraseña y segundo factor; exportar Samsung Pass.
5. Contactos a un .vcf, también los guardados en el teléfono.
6. WhatsApp: backup a Drive, copia de `/sdcard/Android/media/com.whatsapp`,
   clave del backup cifrado y PIN de verificación en dos pasos.
7. Exportar Authenticator a otro equipo.
8. Bancos y Mercado Pago: no desinstalarlos ni borrarles datos; anotar cómo
   se re-enrola cada token (BNA+ pide DNI y foto; Galicia, código por mail o
   cajero).
9. Carpeta segura: «Hacer copia y desinstalar».
10. `adb pull` de DCIM, Download y Documents.
11. Fijar la clave de firma estable antes del primer `set-device-owner`.
12. Marcar «Permitir siempre desde esta computadora» en el diálogo de adb.

Algunos bancos no corren con la depuración USB prendida, y la salida por
cable la necesita: si pasa, se apaga y queda prendible desde la
administración (`setGlobalSetting(ADB_ENABLED)`). Play Integrity no mira si el
equipo está administrado; sí marca apps fuera de Play con accesibilidad o
ventanas superpuestas, por eso Slowtown no usa ninguna de las dos.

## Panel propio (Ajustes no se alcanza)

Siendo Device Owner, en Android 12 la app puede manejar: Wi-Fi y unirse a
redes, Bluetooth, ubicación, brillo, tiempo de pantalla, timbre y No molestar
(el Device Owner ya tiene ese acceso), volumen, linterna y alarmas. Con
`WRITE_SECURE_SETTINGS` dado por adb: escala de grises, rotación y NFC. No
hay vía limpia para datos móviles, modo avión ni punto de acceso: quedan para
la sesión de administración. El lector de notificaciones (para mostrar
«2 WhatsApp» en Inicio) se habilita por adb con `cmd notification
allow_listener`.

## Sin Device Owner

Un launcher no puede tapar el panel, el engranaje ni el cambio de launcher.
Un servicio de accesibilidad puede detectar Ajustes y volver a Inicio, pero
el usuario lo apaga, se cae si se fuerza el cierre de la app, y los bancos lo
miran mal (Play Integrity). `cmd statusbar send-disable-flag
statusbar-expansion` bloquea el panel pero no sobrevive a un reinicio.

## Proyectos para mirar

| Proyecto | Licencia | Para qué |
|---|---|---|
| LMNOPhone (heybeaux/lmnophone) | Apache-2.0 | Mismo diseño, con resultados de pruebas reales |
| TestDPC (googlesamples) | Apache-2.0 | La referencia de la API |
| Headwind MDM | Apache-2.0 | Kiosco (el lock task es de la versión paga) |
| Olauncher, mLauncher | GPL-3.0 | Launchers minimalistas |
| Unlauncher, µLauncher | MIT | Launchers minimalistas |
| RethinkDNS + firestack | Apache-2.0 / MPL-2.0 | VPN local con filtro |

Copiar código GPL obliga a publicar Slowtown bajo GPL.

## Fuentes principales

- https://developer.android.com/work/dpc/dedicated-devices/lock-task-mode
- https://developer.android.com/reference/android/app/admin/DevicePolicyManager
- https://developer.android.com/reference/android/os/UserManager
- AOSP android12-release: `services/core/java/com/android/server/wm/LockTaskController.java`,
  `services/devicepolicy/java/com/android/server/devicepolicy/DevicePolicyManagerService.java`,
  `services/core/java/com/android/server/am/AppErrors.java`,
  `services/core/java/com/android/server/pm/PackageInstallerService.java`
- https://github.com/heybeaux/lmnophone (docs/feasibility-questions.md y spikes/)
- https://chromeenterprise.google/policies/ y https://chromium.googlesource.com/chromium/src/+/main/docs/webview_policies.md
- https://github.com/nextdns/services/blob/main/services/youtube y https://nextdns.io/pricing
- https://pmc.ncbi.nlm.nih.gov/articles/PMC9974409 (one sec)
- https://www.nber.org/papers/w28936 (Allcott et al.)
- https://www.nxp.com/docs/en/data-sheet/NTAG213_215_216.pdf
- https://docs.samsungknox.com/admin/knox-platform-for-enterprise/kbas/kba-1205-secure-folder-not-available-on-a-device/
- https://developer.android.com/google/play/integrity/verdicts
