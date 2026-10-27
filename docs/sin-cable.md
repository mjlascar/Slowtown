# Sin cable: cómo bloquean las apps de la Play Store

Relevado el 27/10/2026 para publicar Slowtown en Google Play algún día, sin
adb ni Device Owner, y para el S10e de Marcos (Android 12 / One UI 4.1). Lo
**verificado** sale de la documentación oficial, de las políticas de Play o
del código de AOSP (rama citada); lo *inferido* está marcado así. Nada de esto
se probó todavía en el emulador.

**Estado: en curso.** Las secciones 2, 3, 5 y 6 están verificadas; el resto
se completa a medida que se confirma (ver «Pendiente»).

## En corto

**Lo que ya está implementado (la guardia con `queryEvents` cada 500 ms desde
el proceso del launcher, que vuelve a Inicio con `startActivity`) no choca con
nada de lo encontrado.** El código de AOSP deja abrir pantallas desde segundo
plano a la app que es el Inicio por defecto, sin excepciones de versión, de
Android 12 a Android 17 (sección 2). Dos avisos que sí cambian cosas:

1. **Desde Android 15, el acceso a los datos de uso es «configuración
   restringida»** para las apps instaladas desde un archivo (el APK bajado
   con Chrome, el de las releases de GitHub). En el S10e (Android 12) no
   aplica; a los amigos con Android 15 o más les va a aparecer el interruptor
   gris hasta que toquen «Permitir configuración restringida» (sección 5).
2. **Play Protect bloquea la instalación desde el navegador de APKs que
   declaran un lector de notificaciones o accesibilidad** («protección
   mejorada contra fraudes», hoy en 185 mercados). Slowtown declara
   `WavesListener`: es la explicación más probable de que Play Protect frenara
   el APK bajado con Chrome (sección 6).

Recomendación:

- **Bloquear con el sondeo de `UsageStatsManager` desde el proceso del
  launcher, como ahora, y nada más como requisito.** Pide un solo permiso
  especial (los datos de uso), que Play no exige declarar, que Play Integrity
  no cuenta como riesgo y que los bancos no miran (sección 6).
- **Accesibilidad, si algún día se suma, opcional y apagada de entrada**, para
  lo que el sondeo no ve a tiempo (el panel, Ajustes, el cambio de launcher).
  Para Play exige declaración, video, divulgación en la app y no permite
  impedir desinstalar (sección 3). Para los bancos es lo que más pesa: una app
  con accesibilidad activa sale como `CONTROLLING` en Play Integrity, y fuera
  de Play como `UNKNOWN_CONTROLLING` (sección 6).
- **Evitar en la versión de Play:** `QUERY_ALL_PACKAGES` (un launcher se
  arregla con `<queries>`), `REQUEST_INSTALL_PACKAGES` y
  `UPDATE_PACKAGES_WITHOUT_USER_ACTION` (Play prohíbe autoactualizarse),
  `SYSTEM_ALERT_WINDOW` (sale como `OVERLAYS` y no hace falta: el Inicio ya
  puede abrir pantallas), Device Admin y `WRITE_SECURE_SETTINGS`.
- **Para Marcos (Android 12, con Device Owner):** nada de esto cambia su
  teléfono. El riesgo para los bancos aparece sólo si se suma accesibilidad.

## 1. Cómo detectan la app de adelante y cómo la frenan

*(Pendiente: tabla por app y proyectos abiertos.)*

## 2. Abrir pantallas desde segundo plano

Desde Android 10, una app en segundo plano no puede abrir actividades, salvo
excepciones. La página oficial (actualizada el 18/05/2026) lista, entre
otras: tener una ventana visible, ser el teclado activo, tener
`SYSTEM_ALERT_WINDOW` dado por el usuario, estar ligada por un servicio con
permiso para abrirlas, y **«el lanzamiento lo inicia el launcher del
equipo»**. Lo concreto está en el código:

| Versión | Archivo (AOSP) | Qué dice |
|---|---|---|
| 12 y 13 | `services/core/java/com/android/server/wm/ActivityStarter.java`, `shouldAbortBackgroundActivityStart` (ramas `android12-release`, `android13-release`) | «Always allow home application to start activities»: la segunda revisión, después de root/system/NFC. `isHomeApp` compara con `mHomeProcess` o, si no hay, con `getDefaultHomeActivity` |
| 14 | `services/core/java/com/android/server/wm/BackgroundActivityStartController.java` (`android14-release`) | Igual, con el código `BAL_ALLOW_ALLOWLISTED_COMPONENT` y el motivo «Home app» |
| 15 y 16 | el mismo archivo (`android15-release`, `android16-release`, `android16-qpr2-release`) | Igual; además, «el Inicio puede abrir apps aunque los cambios de app estén frenados» (la protección del botón Inicio) |
| 17 | el mismo archivo (`android17-release`) | Igual, con `.allowNewTask()` |

- **El modelo de seguridad de actividades (ASM)**, que desde Android 15 frena
  lanzamientos de quien no está arriba de la tarea, deja pasar un
  `FLAG_ACTIVITY_NEW_TASK` con veredicto `BAL_ALLOW_ALLOWLISTED_COMPONENT`
  (`checkActivityAllowedToStart`, mismo archivo, ramas 16 y 17). La guardia
  usa `FLAG_ACTIVITY_NEW_TASK`: hay que mantenerlo.
- **Un servicio de accesibilidad** también puede: el sistema lo liga con
  `BIND_ALLOW_BACKGROUND_ACTIVITY_STARTS`
  (`services/accessibility/java/com/android/server/accessibility/AccessibilityServiceConnection.java`,
  ramas 12 y 16).
- **`SYSTEM_ALERT_WINDOW`** sigue siendo excepción en 15, 16 y 17
  (`BAL_ALLOW_SAW_PERMISSION`). El cambio de Android 15 para estas apps es
  sobre servicios en primer plano, no sobre actividades *(a confirmar en la
  página de cambios de Android 15)*.
- *Inferido:* Samsung no debería haber sacado la excepción del Inicio, porque
  One UI Home la usa. No se vio en el código de Samsung.

## 3. Play y la accesibilidad

Política vigente («Permissions and APIs that Access Sensitive Information»,
sección «Accessibility API»):

- Cualquier app puede usar la API, pero sólo las hechas para personas con
  discapacidad pueden declarar `isAccessibilityTool=true`, y esas quedan
  exentas de la divulgación. Las demás (y los launchers se nombran como
  ejemplo de lo que *no* es herramienta de accesibilidad): **declaración en
  Play Console con video, divulgación dentro de la app (no sólo en la
  descripción ni en `AccessibilityService_description`), consentimiento con
  un toque explícito, y documentar el uso en la ficha de Play.**
- Prohibido, textual: «Change user settings without their permission or
  prevent the ability for users to disable or uninstall any app or service
  unless authorized by a parent or guardian through a parental control app or
  by authorized administrators through enterprise management software». Las
  únicas excepciones son el control parental y la gestión empresarial.
- También prohibido: esquivar controles de seguridad o privacidad de Android,
  grabar llamadas, y (nuevo) que la app «inicie, planifique y ejecute
  acciones o decisiones de forma autónoma»; la automatización por reglas fijas
  («si pasa X, hacer Y») sí se permite.
- «Apps must use more narrowly scoped APIs and permissions in lieu of the
  Accessibility API when possible»: para saber qué app está adelante existe
  `UsageStatsManager`, así que pedir accesibilidad sólo para eso es fácil de
  rechazar *(inferido)*.

*(Pendiente: cómo encajan los modos estrictos de AppBlock, Stay Focused y Lock
Me Out.)*

## 4. Play y el resto de los permisos

| Qué | Política de Play | Para Slowtown |
|---|---|---|
| `QUERY_ALL_PACKAGES` | Sólo si la función central necesita ver todas las apps; permitidos: búsqueda en el equipo, antivirus, gestores de archivos, navegadores, y (excepción temporal) bancos y billeteras por seguridad. Declaración en Play Console | Los launchers no figuran. Las apps con ícono se ven con `<queries>` de `MAIN`/`LAUNCHER`; el marcador y la pantalla de llamada, con `<queries>` por intent. Sacarlo en la versión de Play |
| `REQUEST_INSTALL_PACKAGES` | Sólo si la función central es mandar, recibir o instalar paquetes (navegador, mensajería con adjuntos, gestor de archivos, MDM, copias, migración, compañera de un reloj). «may not be used to perform self updates» | Fuera de la versión de Play, con `update/Updater.kt` |
| Autoactualizarse | «Device and Network Abuse», textual: «An app distributed via Google Play may not modify, replace, or update itself using any method other than Google Play's update mechanism». Tampoco puede bajar código ejecutable de otro lado | Lo mismo: la versión de Play sin `Updater` |
| `VpnService` | Sólo apps de VPN, o que necesitan un servidor remoto para control parental, seguimiento de uso, seguridad, herramientas de red, navegadores u operadoras. Documentarlo en la ficha y cifrar hasta el extremo del túnel | Un filtro DNS local no arma túnel a un servidor remoto; *inferido:* entra por «seguimiento de uso» o «control parental», con riesgo de rechazo |
| `USE_EXACT_ALARM` | Sólo alarmas, temporizadores y calendarios; revisión obligatoria | No: seguir con `SCHEDULE_EXACT_ALARM`, que da el usuario (Android 14 lo niega de entrada a las apps nuevas) |
| Servicios en primer plano (Android 14+) | Declarar cada tipo en Play Console con descripción, impacto y video; `specialUse` es para lo que no entra en otro tipo, con una `<property>` en el manifiesto y revisión | Hoy no usa ninguno; ver sección 7 |
| Nivel de API | Desde el 31/08/2026, apps nuevas y actualizaciones con API 36 (Android 16); prórroga hasta el 01/11/2026 | Slowtown ya apunta a 37 |

*(Pendiente: Device Admin, Device Owner por adb en apps de Play,
`SYSTEM_ALERT_WINDOW`, `PACKAGE_USAGE_STATS`, el lector de notificaciones y
`WRITE_SECURE_SETTINGS`.)*

## 5. Configuración restringida

- **Android 13 y 14:** al instalar desde un archivo (`PACKAGE_SOURCE_LOCAL_FILE`
  o `PACKAGE_SOURCE_DOWNLOADED_FILE`: el instalador de Android abierto desde
  Chrome o un gestor de archivos), el sistema marca la app con la operación
  `ACCESS_RESTRICTED_SETTINGS` en error, también en cada actualización
  instalada así (`InstallPackageHelper.java`, `android13-release`). Ajustes
  deja gris la accesibilidad y el lector de notificaciones hasta que el
  usuario toca, en la información de la app, ⋮ → «Permitir configuración
  restringida».
- **Android 15 en adelante («modo de confirmación mejorado», ECM):** la lista
  crece. Protegidos por app: los permisos de SMS, **Device Admin,
  accesibilidad, lector de notificaciones, `SYSTEM_ALERT_WINDOW`, acceso a
  datos de uso** (`GET_USAGE_STATS` y `LOADER_USAGE_STATS`) y los roles de
  marcador y SMS (`EnhancedConfirmationService.java` en
  `packages/modules/Permission`, ramas 15, 16 y 17). La pantalla de Ajustes del
  acceso a datos de uso lo respeta (`UsageAccessDetails.java`, Settings,
  `android15-release`), y las banderas que lo encienden
  (`enhanced_confirmation_mode_apis_enabled` y `extend_ecm_to_all_settings`)
  están en `ENABLED` en las configuraciones de release de AOSP para 15 (`ap3a`)
  y 16 (`bp1a`, `bp2a`). Además, durante una llamada «no confiable» no se
  puede dar accesibilidad ni «instalar apps desconocidas» (bandera
  `unknown_call_package_install_blocking_enabled`, `ENABLED` en `bp2a`).
- **Qué app queda restringida en 15+:** la decisión se toma al mirar, no al
  instalar: si la fuente es un archivo, sí; si no, depende de si quien la
  instaló es de fábrica o está en la lista de instaladores de confianza del
  equipo. Play Store no queda restringida.
- **La actualización que Slowtown se instala a sí misma** (sesión de
  `PackageInstaller` sin `setPackageSource`): en 13 y 14 no marca nada, pero
  tampoco desmarca. En 15+, una vez que el usuario permitió, queda guardado
  como «no protegida» y las actualizaciones no lo cambian. *Inferido del
  código:* si el usuario nunca permitió, después de la autoactualización la
  instaladora pasa a ser Slowtown, y si el equipo tiene lista de instaladores
  de confianza, sigue restringida.
- **En el S10e (Android 12) no existe.** Con Device Owner, además, los
  permisos los da `provisionar.sh` por adb.

## 6. Bancos y Play Integrity

- **`appAccessRiskVerdict`** (opcional, lo activa cada app en Play Console):
  informa si hay apps corriendo que puedan ver la pantalla (`CAPTURING`),
  controlar el equipo y sus entradas (`CONTROLLING`) o dibujar encima
  (`OVERLAYS`). `KNOWN_` es instalada por Play o de fábrica; `UNKNOWN_`, por
  cualquier otro medio. El ejemplo de la documentación acepta sólo si no hay
  `CAPTURING` ni `CONTROLLING`, **sin distinguir `KNOWN` de `UNKNOWN`**, y Play
  ofrece diálogos para pedirle al usuario que cierre esas apps
  (`CLOSE_UNKNOWN_ACCESS_RISK`, `CLOSE_ALL_ACCESS_RISK`).
- **Quedan afuera sólo los servicios de accesibilidad verificados**: los que
  pasaron la revisión ampliada de accesibilidad de Play, publicando con
  `isAccessibilityTool=true` o pidiéndola. Slowtown no califica.
- *Inferido:* el acceso a datos de uso no entra en ninguna de las tres
  categorías (no ve la pantalla, no controla, no dibuja), y el Inicio por
  defecto no es una superposición. Una guardia sólo con datos de uso no
  debería aparecer.
- **Bancos que bloquean con accesibilidad:** el caso documentado es OCBC
  (Singapur, desde 2023, y Hong Kong desde 12/2025): su app no anda si hay una
  app instalada fuera de una tienda oficial con accesibilidad prendida, ni con
  la depuración USB o inalámbrica prendida. Las de Play con accesibilidad no
  se marcan según sus criterios.
- **Argentina:** no encontré reportes de Mercado Pago ni de bancos argentinos
  que bloqueen con accesibilidad activa. La comunicación «A» 7517 del BCRA
  (2022) obliga a los bancos a ser accesibles, lo que hace improbable un
  bloqueo general; uno contra apps desconocidas con accesibilidad sigue siendo
  posible y no se ve desde afuera. Los bancos y billeteras tienen además una
  excepción para `QUERY_ALL_PACKAGES` «por seguridad»: pueden ver qué hay
  instalado.
- **Play Protect, «protección mejorada contra fraudes»:** bloquea la
  instalación desde una fuente de «sideloading por internet» (navegador,
  mensajería, gestor de archivos) de apps que declaran alguno de cuatro
  permisos: `RECEIVE_SMS`, `READ_SMS`, el lector de notificaciones o
  accesibilidad. Empezó en Singapur (2024) y en febrero de 2026 Google la
  daba por extendida a 185 mercados. *Inferido:* es lo que frenó el APK de
  Slowtown bajado con Chrome (declara `WavesListener`). Con la actualización
  que instala Slowtown misma no debería aplicar (no es navegador ni
  mensajería), pero no está documentado.

## 7. Samsung

*(Pendiente.)*

## 8. Protección avanzada (Android 16)

*(Pendiente.)*

## 9. Verificación de desarrolladores

*(Pendiente.)*

## 10. Qué se cierra sin cable

*(Pendiente.)*

## Pendiente

- Sección 1 (tabla de apps), 3 (modos estrictos), 4 (Device Admin y resto), 7,
  8, 9 y 10.
- Probar en el emulador: la guardia con Android 15 o 16 instalada desde un
  archivo (configuración restringida), y si la vuelta a Inicio anda con la
  protección del botón Inicio recién apretado.

## Fuentes

- https://developer.android.com/guide/components/activities/background-starts
- AOSP `platform/frameworks/base`: `services/core/java/com/android/server/wm/ActivityStarter.java`
  (android12-release, android13-release),
  `services/core/java/com/android/server/wm/BackgroundActivityStartController.java`
  (android14-release a android17-release),
  `services/accessibility/java/com/android/server/accessibility/AccessibilityServiceConnection.java`,
  `services/core/java/com/android/server/pm/InstallPackageHelper.java`,
  `core/java/android/security/responsible_apis_flags.aconfig`
- AOSP `platform/packages/modules/Permission`: `service/java/com/android/ecm/EnhancedConfirmationService.java`
- AOSP `platform/packages/apps/Settings`: `src/com/android/settings/applications/UsageAccessDetails.java`
- AOSP `platform/build/release`: `aconfig/ap3a`, `aconfig/bp1a`, `aconfig/bp2a`
- https://support.google.com/googleplay/android-developer/answer/9888170 (Permissions and APIs that Access Sensitive Information)
- https://support.google.com/googleplay/android-developer/answer/10964491 (AccessibilityService API)
- https://support.google.com/googleplay/android-developer/answer/10158779 (QUERY_ALL_PACKAGES)
- https://support.google.com/googleplay/android-developer/answer/13392821 (servicios en primer plano)
- https://developer.android.com/google/play/requirements/target-sdk
- https://support.google.com/android/answer/12623953 (configuración restringida)
- https://developer.android.com/google/play/integrity/verdicts
- https://www.ocbc.com/personal-banking/help-and-support/digital-banking/online-banking-security.page
- https://security.googleblog.com/2024/02/piloting-new-ways-to-protect-Android-users-from%20financial-fraud.html
- https://blog.google/security/keeping-google-play-android-app-ecosystem-safe-2025/
