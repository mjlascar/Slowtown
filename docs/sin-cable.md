# Sin cable: cómo bloquean las apps de la Play Store

Relevado el 27/10/2026 para publicar Slowtown en Google Play algún día, sin
adb ni Device Owner, y para el S10e de Marcos (Android 12 / One UI 4.1). Lo
que no tiene marca sale de la documentación oficial, de las políticas de Play
o del código de AOSP (con la rama); lo *inferido* está marcado así. La guardia
se probó en el emulador con Android 12 (paso 15 de `scripts/probar-emulador.sh`);
en el teléfono, todavía no.

## En corto

**La guardia que ya está** (`admin/Guard.kt`: `queryEvents` cada 500 ms desde
el proceso del launcher, y vuelta a Inicio con `startActivity` y
`FLAG_ACTIVITY_NEW_TASK`) **no choca con nada de lo encontrado.** AOSP deja que
el Inicio por defecto abra pantallas desde segundo plano en todas las
versiones, de Android 12 a 17 (sección 2), y no congela su proceso
(sección 7). Lo que sí hay que tener en cuenta:

1. **Desde Android 15, el acceso a los datos de uso es «configuración
   restringida»** para lo instalado desde un archivo (el APK de las releases
   de GitHub): el interruptor queda gris hasta tocar «Permitir configuración
   restringida». En el S10e (Android 12) no existe, y desde Play no aplica
   (sección 5).
2. **Play Protect bloquea instalar desde el navegador los APKs que declaran
   un lector de notificaciones o accesibilidad** («protección mejorada contra
   fraudes», en 185 mercados). Slowtown declara `WavesListener`: *inferido*,
   puede ser lo que frenó el APK bajado con Chrome (sección 6).
3. **El instalador de Android pasa por la guardia sólo un par de minutos
   después de que Slowtown lo abre** (`Guard.allowInstaller`: «Desinstalar» de
   la lista, confirmar una actualización). Antes pasaba siempre, y cualquier
   app permitida que ofreciera desinstalar podía sacar a Slowtown (sección
   10). La Play Store, si está permitida, desinstala sin pasar por él.
4. **Falta probar en el S10e** que Samsung no congele el proceso de Inicio con
   otra app adelante: AOSP no lo hace, Samsung tiene su propio congelador y no
   lo documenta (sección 7).

**Recomendación:**

- **Base sin cable: el sondeo de datos de uso desde el launcher, como ahora.**
  Es lo que menos puertas cierra: un solo permiso especial, que Play no pide
  declarar, que Play Integrity no cuenta como riesgo (*inferido*: no ve la
  pantalla, no controla, no dibuja encima), que sobrevive a forzar la
  detención (al apretar Inicio vuelve a arrancar) y a la Protección avanzada
  de Android 17.
- **Accesibilidad, si se suma, opcional y apagada de entrada** (un «modo
  estricto» para quien lo pida, nunca en el modo con cable). Es lo único que
  ve el panel, una página puntual de Ajustes o lo que pasa adentro de una app,
  y reacciona al instante. A cambio: declaración y video en Play Console,
  divulgación en la app, prohibido usarla para impedir desinstalar, forzar la
  detención la apaga, la Protección avanzada de Android 17 la saca, y los
  bancos que usan `appAccessRiskVerdict` la ven como `CONTROLLING`.
- **Device Owner por cable sigue siendo lo que cierra de verdad** (panel,
  modo seguro, desinstalar). Nada de esto lo reemplaza.

| Pedir (versión de Play) | Para qué |
|---|---|
| Ser el Inicio (`RoleManager.ROLE_HOME`) | Abrir pantallas desde segundo plano y tener el proceso con prioridad de Inicio |
| Acceso a datos de uso | Saber qué app está adelante; el tiempo de pantalla |
| `SCHEDULE_EXACT_ALARM`, opcional | Bedtime; lo da el usuario (Android 14 lo niega de entrada) |
| Lector de notificaciones, opcional | Las oleadas; en Play no tiene formulario |

| Evitar en la versión de Play | Por qué |
|---|---|
| `QUERY_ALL_PACKAGES` | Los launchers no están entre los usos permitidos; alcanza con `<queries>` |
| `REQUEST_INSTALL_PACKAGES`, `UPDATE_PACKAGES_WITHOUT_USER_ACTION` y `Updater` | Play prohíbe actualizarse por fuera de Play |
| `SYSTEM_ALERT_WINDOW` | No hace falta (el Inicio ya abre pantallas) y sale como `OVERLAYS` |
| Device Admin para impedir desinstalar | La política de malware lo prohíbe con esas palabras |
| Accesibilidad obligatoria | Ver arriba |
| `USE_EXACT_ALARM`; servicio en primer plano | Sólo alarmas y calendarios; el servicio, sólo si Samsung congela la guardia |

**Para Marcos:** su S10e (Android 12, hoy sin Device Owner, paso 2) puede
cerrar con la guardia, que no usa accesibilidad. El riesgo con los bancos
aparece recién si se suma accesibilidad: instalada desde GitHub sería
`UNKNOWN_CONTROLLING`, justo lo que bloquean bancos como OCBC. No encontré
bancos argentinos ni a Mercado Pago haciéndolo, pero eso no se ve desde afuera
hasta que pasa.

## 1. Cómo lo hacen las apps de la Play Store

Dos maneras de saber qué app está adelante, y las apps de Play usan las dos:

- **Accesibilidad** (`TYPE_WINDOW_STATE_CHANGED`): llega al instante y ve
  también el panel, los diálogos y el texto (páginas de Ajustes, sitios,
  Shorts).
- **Datos de uso** (`UsageStatsManager.queryEvents` en un bucle): ve sólo
  actividades, con la demora del intervalo. TimeLimit mira cada 100 ms a 1 s,
  Mindful cada 750 ms, y un desarrollador que pasó de accesibilidad a esto
  midió unos 700 ms más.
- **Para frenar:** una superposición (`SYSTEM_ALERT_WINDOW`), su pantalla
  encima, o `GLOBAL_ACTION_HOME`/`BACK` desde la accesibilidad, que además
  puede dibujar sin ese permiso (`TYPE_ACCESSIBILITY_OVERLAY`).
- **Bienestar digital frena de otra manera:** tiene el rol `SYSTEM_WELLBEING`
  (sólo apps de sistema), que da `SUSPEND_APPS` para pausar paquetes, y
  `registerAppUsageLimitObserver` para los temporizadores; las dos son
  `@SystemApi`. El de Samsung declara los mismos permisos. Lo más parecido para
  una app común es pausar siendo Device Owner, lo que hace Slowtown con cable.

| App | Detecta | Frena | Además pide | Modo estricto |
|---|---|---|---|---|
| AppBlock | Datos de uso; accesibilidad «optional but recommended» (sitios, Ajustes, pantalla dividida, Shorts) | Su pantalla encima | Device Admin, notificaciones, sin optimizar batería | No deja editar reglas; Device Admin contra desinstalar; opcional, bloquear Ajustes y Recientes |
| Stay Focused | Accesibilidad según su FAQ (Play la da como opcional, para sitios) | No documentado | Device Admin, sin optimizar batería; sugiere ponerla como Inicio | Device Admin contra desinstalar, «Block Power Off» |
| Lock Me Out | Accesibilidad, obligatoria desde la 6.3.0 (2021); datos de uso para reglas por uso | Pantalla propia, Inicio, y la saca de Recientes | Device Admin, notificaciones, No molestar | Pago: bloquea desinstalar, quitar permisos, páginas de Ajustes y reiniciar |
| ScreenZen | Accesibilidad | Superposición | Mostrar encima, sin optimizar batería; ahora trae su launcher | Mira si la pantalla ofrece desinstalarla |
| one sec | Accesibilidad (recomendada); la alternativa vieja, sondeo de datos de uso | Superposición (obligatoria) | No documentado | No documentado |
| Opal | Accesibilidad desde Android 14; antes, datos de uso (*inferido* de su política de privacidad) | No documentado | No documentado | «Hard Mode»; contra desinstalar, no documentado |
| Digital Detox (Urbandroid) | Datos de uso | Superposición; abre lo permitido desde su pantalla | Accesibilidad opcional, contra trampas | Salir cuesta plata o un código; Device Admin opcional |
| BlockerX | Accesibilidad (sitios para adultos); para apps, no documentado | Superposición | VPN opcional | No impide desinstalar: avisa a un compañero |
| Regain | Accesibilidad | No documentado | No documentado | Anuncia «uninstall prevention» |
| Freedom | Accesibilidad | No documentado | Device Admin, notificaciones | «Locked Mode»; admite que Device Admin no impide desinstalar desde Ajustes y sugiere bloquear Ajustes |

Jomo no tiene versión para Android. Sobre la confiabilidad, según las propias
apps: one sec dice que la accesibilidad es más estable («not killed by the
system») y mucho más rápida que los datos de uso; el historial de Lock Me Out
es una lista de agujeros tapados con accesibilidad (páginas de batería, el
modo mantenimiento de Samsung) y de arreglos de velocidad («Fixed slow app
detection speed on Android 14»); Stay Focused admite que el modo seguro la
saltea («no app can prevent that»); AppBlock avisa que en Samsung «App Power
Monitor can interfere», y Freedom, que Xiaomi, Vivo y Oppo pueden sacarle la
accesibilidad solos.

**Proyectos abiertos para mirar:**

| Proyecto | Licencia | Cómo | Último commit |
|---|---|---|---|
| Focus Launcher (github.com/C-P-S-P/focus-launcher) | GPL-3.0+ | Launcher; datos de uso, y accesibilidad opcional que abre su pantalla. El diseño más parecido a Slowtown | 10/2026 |
| TimeLimit (codeberg.org/timelimit/timelimit-android) | GPL-3.0 | Datos de uso cada 100 ms a 1 s, accesibilidad opcional, superposición; con Device Owner, `setPackagesSuspended` | 09/2026 |
| Mindful (github.com/akaMrNagar/Mindful) | GPL-2.0 | Accesibilidad si está; si no, `queryEvents` cada 750 ms; superposición e Inicio; VPN | 08/2025 |
| Curbox, ex Digipaws (github.com/curbox-app/curbox-android) | GPL-3.0+ | Accesibilidad con `GLOBAL_ACTION_HOME`; Device Admin | 10/2026 |
| Pause (github.com/dtsykunov/pause-app) | MIT | Accesibilidad con `TYPE_ACCESSIBILITY_OVERLAY` | 09/2026 |
| Escape Launcher (github.com/GeorgeClensy/Escape-Launcher) | MIT hasta la 2.x, AGPL-3.0 desde la 3.0 | Launcher con cuenta regresiva antes de abrir, sin detectar nada | 10/2026 |

Copiar código GPL obliga a publicar Slowtown bajo GPL; Pause y Escape
Launcher 2.x se pueden usar con atribución.

## 2. Abrir pantallas desde segundo plano

Desde Android 10, una app en segundo plano no puede abrir actividades salvo
excepciones. La página oficial (actualizada el 18/05/2026) lista, entre
otras: una ventana visible, ser el teclado activo, `SYSTEM_ALERT_WINDOW` dado
por el usuario, estar ligada por un servicio con permiso para abrirlas, y
«el lanzamiento lo inicia el launcher del equipo». Lo preciso está en el
código:

| Versión | Archivo de AOSP (rama) | Qué dice |
|---|---|---|
| 12 y 13 | `services/core/java/com/android/server/wm/ActivityStarter.java`, `shouldAbortBackgroundActivityStart` (`android12-release`, `android13-release`) | «Always allow home application to start activities»: la segunda revisión, después de root, system y NFC. `isHomeApp` compara con `mHomeProcess` y, si no hay, con `getDefaultHomeActivity` |
| 14 | `services/core/java/com/android/server/wm/BackgroundActivityStartController.java` (`android14-release`) | Igual, con `BAL_ALLOW_ALLOWLISTED_COMPONENT` y el motivo «Home app» |
| 15 y 16 | el mismo (`android15-release`, `android16-release`, `android16-qpr2-release`) | Igual, y además «The home app can start apps even if app switches are usually disallowed» (la protección después de apretar Inicio) |
| 17 | el mismo (`android17-release`) | Igual, con `.allowNewTask()` |

- **El modelo de seguridad de actividades (ASM)** de Android 15 en adelante,
  que frena lanzamientos de quien no está arriba de la tarea, deja pasar un
  `FLAG_ACTIVITY_NEW_TASK` cuando el veredicto es el del Inicio
  (`checkActivityAllowedToStart`, mismo archivo, ramas 15, 16 y 17). La
  guardia ya usa `FLAG_ACTIVITY_NEW_TASK`: hay que mantenerlo.
- **Un servicio de accesibilidad** también puede: el sistema lo liga con
  `BIND_ALLOW_BACKGROUND_ACTIVITY_STARTS`
  (`services/accessibility/java/com/android/server/accessibility/AccessibilityServiceConnection.java`,
  ramas 12 y 16).
- **`SYSTEM_ALERT_WINDOW` sigue siendo excepción** en 15, 16 y 17
  (`BAL_ALLOW_SAW_PERMISSION`). Lo que cambió en Android 15 para estas apps es
  arrancar un servicio en primer plano desde segundo plano: ahora piden además
  una ventana superpuesta visible.
- *Inferido:* Samsung no sacó la excepción del Inicio (la usa su propio
  launcher), pero su código no es público.

## 3. Play y la accesibilidad

Política vigente («Permissions and APIs that Access Sensitive Information»,
sección «Accessibility API», y la página de ayuda de la API):

- Cualquier app puede usar la API, pero sólo las hechas para personas con
  discapacidad declaran `isAccessibilityTool=true` y quedan exentas de la
  divulgación. Los launchers figuran como ejemplo de lo que *no* es una
  herramienta de accesibilidad. Las demás necesitan: **declaración en Play
  Console con video, divulgación dentro de la app (no alcanza la descripción
  ni `AccessibilityService_description`), consentimiento con un toque
  explícito, y contar el uso en la ficha de Play.**
- Prohibido, textual: «Change user settings without their permission or
  prevent the ability for users to disable or uninstall any app or service
  unless authorized by a parent or guardian through a parental control app or
  by authorized administrators through enterprise management software». Las
  únicas excepciones son el control parental y la gestión empresarial.
- También prohibido: esquivar los controles de seguridad, privacidad y avisos
  de Android, engañar con la interfaz, grabar llamadas, y (nuevo) una app que
  «inicie, planifique y ejecute acciones o decisiones de forma autónoma»; la
  automatización por reglas fijas («si pasa X, hacer Y») se permite.
- «Apps must use more narrowly scoped APIs and permissions in lieu of the
  Accessibility API when possible». Para saber qué app está adelante existe
  `UsageStatsManager`: *inferido*, pedir accesibilidad sólo para eso es fácil
  de rechazar. En la práctica la revisión rechaza por falta de divulgación
  (un desarrollador contó cuatro rechazos antes de pasarse a datos de uso).

**Los modos estrictos de Play** (AppBlock, Stay Focused, Lock Me Out, Regain:
Device Admin contra desinstalar, y accesibilidad sobre Ajustes) no tienen
excepción escrita. Además de la cláusula de arriba, la política de malware
cuenta como abuso de privilegios a las «Apps that abuse features to prevent
them from being uninstalled or stopped», y dice «Don't use device admin
features to block uninstallation». No encontré bajas públicas de estas apps.
*Inferido:* Play lo tolera cuando lo prende el usuario, avisado y por un
tiempo; es zona gris, no permiso. Para Slowtown en Play: no impedir
desinstalar.

## 4. Play y el resto de los permisos

| Qué | Política de Play | Para Slowtown |
|---|---|---|
| `QUERY_ALL_PACKAGES` | Sólo si la función central necesita ver todas las apps: búsqueda en el equipo, antivirus, gestores de archivos, navegadores, y (excepción temporal) bancos y billeteras por seguridad. Formulario en Play Console | Los launchers no figuran. Las apps con ícono se ven con `<queries>` de `MAIN`/`LAUNCHER`; el marcador y la pantalla de llamada, con `<queries>` por intent. Sacarlo en la versión de Play |
| `REQUEST_INSTALL_PACKAGES` | Sólo si la función central es mandar, recibir o instalar paquetes (navegador, mensajería con adjuntos, gestor de archivos, MDM, copias, migración, compañera de un reloj). «may not be used to perform self updates» | Fuera de la versión de Play |
| Autoactualizarse | «An app distributed via Google Play may not modify, replace, or update itself using any method other than Google Play's update mechanism» («Device and Network Abuse»). Tampoco bajar código ejecutable | La versión de Play, sin `Updater` |
| Device Admin | Malware: «Don't use device admin features to block uninstallation». Desde Android 15 es configuración restringida para lo instalado desde un archivo | Sólo para el modo con cable |
| Device Owner por adb | No encontré regla en contra: TestDPC, de Google, se instala desde Play y la guía de AOSP la provisiona con `dpm set-device-owner` | *Inferido:* la versión de Play puede conservar el modo con cable |
| `SYSTEM_ALERT_WINDOW` | Sin formulario; sólo pide mandar al usuario a Ajustes | No hace falta |
| Datos de uso (`PACKAGE_USAGE_STATS`) | No figura en la política de permisos sensibles: sin formulario | La base de la guardia |
| Lector de notificaciones | Tampoco figura | Fuera de Play: Play Protect y configuración restringida |
| `WRITE_SECURE_SETTINGS` | No figura; Android lo da sólo por adb | Sin cable no sirve; *inferido*, puede quedar para el modo con cable |
| `VpnService` | Sólo apps de VPN, o que necesitan un servidor remoto para control parental, seguimiento de uso, seguridad, herramientas de red, navegadores u operadoras. Contarlo en la ficha y cifrar hasta el extremo del túnel | *Inferido:* un filtro DNS local no arma un túnel a un servidor remoto; entraría como control parental o seguimiento de uso, con riesgo de rechazo |
| `USE_EXACT_ALARM` | Sólo alarmas, temporizadores y calendarios; revisión obligatoria | No: `SCHEDULE_EXACT_ALARM`, que da el usuario |
| Servicios en primer plano (Android 14+) | Cada tipo se declara en Play Console con descripción, impacto y video; `specialUse` es para lo que no entra en otro tipo, con una `<property>` en el manifiesto, y se revisa | Hoy no usa ninguno (sección 7) |
| Nivel de API | Desde el 31/08/2026, apps nuevas y actualizaciones con API 36 (Android 16); prórroga hasta el 01/11/2026 | Slowtown ya apunta a 37 |

## 5. Configuración restringida

- **Android 13 y 14:** lo instalado desde un archivo
  (`PACKAGE_SOURCE_LOCAL_FILE` o `PACKAGE_SOURCE_DOWNLOADED_FILE`: el
  instalador de Android abierto desde Chrome o un gestor de archivos) queda
  marcado con la operación `ACCESS_RESTRICTED_SETTINGS` en error, también en
  cada actualización instalada así (`services/core/java/com/android/server/pm/InstallPackageHelper.java`,
  `android13-release`). Ajustes deja gris la accesibilidad y el lector de
  notificaciones hasta tocar, en la información de la app, ⋮ → «Permitir
  configuración restringida».
- **Android 15 en adelante («modo de confirmación mejorado», ECM):** protege
  los permisos de SMS, **Device Admin, accesibilidad, lector de
  notificaciones, `SYSTEM_ALERT_WINDOW` y datos de uso** (`GET_USAGE_STATS` y
  `LOADER_USAGE_STATS`), y los roles de marcador y SMS
  (`service/java/com/android/ecm/EnhancedConfirmationService.java` en
  `packages/modules/Permission`, ramas 15, 16 y 17). La pantalla del acceso a
  datos de uso lo respeta (`UsageAccessDetails.java` de Settings,
  `android15-release`), y las banderas que lo encienden
  (`enhanced_confirmation_mode_apis_enabled`, `extend_ecm_to_all_settings`)
  están en `ENABLED` en las configuraciones de release de AOSP para 15 (`ap3a`)
  y 16 (`bp1a`, `bp2a`). Además, durante una llamada «no confiable» no se
  puede dar accesibilidad ni «instalar apps desconocidas»
  (`unknown_call_package_install_blocking_enabled`, `ENABLED` en `bp2a`).
- **Quién queda restringida en 15+:** se decide al consultar, no al instalar.
  Si la fuente es un archivo, sí; si no, depende de si la instaló una app de
  fábrica o una de la lista de instaladores de confianza del equipo. Lo de
  Play no queda restringido.
- **La actualización que Slowtown se instala a sí misma** (sesión de
  `PackageInstaller` sin `setPackageSource`): en 13 y 14 no marca ni desmarca.
  En 15+, una vez que el usuario permitió, queda guardado como «no protegida»
  y las actualizaciones no lo cambian. *Inferido del código:* si nunca
  permitió, después de autoactualizarse la instaladora pasa a ser Slowtown, y
  si el equipo tiene lista de instaladores de confianza, sigue restringida.
- **En el S10e (Android 12) no existe.** Con Device Owner, además, los
  permisos los da `provisionar.sh` por adb.

## 6. Bancos y Play Integrity

- **`appAccessRiskVerdict`** (opcional, lo activa cada app en Play Console)
  dice si hay apps corriendo que puedan ver la pantalla (`CAPTURING`),
  controlar el equipo y sus entradas (`CONTROLLING`) o dibujar encima
  (`OVERLAYS`). `KNOWN_` es lo instalado por Play o de fábrica; `UNKNOWN_`,
  por cualquier otro medio. El ejemplo de la documentación acepta sólo si no
  hay `CAPTURING` ni `CONTROLLING`, **sin distinguir `KNOWN` de `UNKNOWN`**, y
  Play ofrece diálogos para pedir que se cierren esas apps
  (`CLOSE_UNKNOWN_ACCESS_RISK`, `CLOSE_ALL_ACCESS_RISK`).
- **Sólo quedan afuera los servicios de accesibilidad verificados:** los que
  pasaron la revisión ampliada de Play (publicados con
  `isAccessibilityTool=true` o con la revisión pedida). Slowtown no califica.
  Venir de Play sólo cambia `UNKNOWN_` por `KNOWN_`.
- *Inferido:* ni el acceso a datos de uso ni el lector de notificaciones
  entran en las tres categorías, y el Inicio por defecto no es una
  superposición. La documentación no lista los permisos que mira.
- **Bancos que bloquean:** el caso documentado es OCBC (Singapur, desde
  2023): su app no anda si hay una app instalada fuera de una tienda oficial
  con accesibilidad prendida, ni con la depuración USB o inalámbrica prendida.
  Las de Play con accesibilidad no entran en su criterio.
- **Argentina:** no encontré reportes de Mercado Pago ni de bancos argentinos
  que bloqueen con accesibilidad activa. La comunicación «A» 7517 del BCRA
  (2022) les exige accesibilidad digital, lo que hace improbable un bloqueo
  general; uno contra apps desconocidas con accesibilidad sigue siendo
  posible. Los bancos y billeteras tienen excepción para `QUERY_ALL_PACKAGES`
  «por seguridad»: pueden ver qué hay instalado.
- **La alternativa que Google les recomienda** (diciembre de 2025) es marcar
  las pantallas sensibles con `accessibilityDataSensitive`: desde Android 16,
  un servicio que no es herramienta de accesibilidad no las puede leer ni
  tocar. Es más fino que bloquear toda la app.
- **Play Protect, «protección mejorada contra fraudes»:** bloquea instalar
  desde una fuente de «sideloading por internet» (navegador, mensajería,
  gestor de archivos) las apps que declaran alguno de cuatro permisos:
  `RECEIVE_SMS`, `READ_SMS`, el lector de notificaciones o accesibilidad.
  Empezó en Singapur (2024); en febrero de 2026 Google la daba por extendida a
  185 mercados. *Inferido:* con la actualización que se instala Slowtown
  misma no debería aplicar (no es navegador ni mensajería), pero no está
  documentado.

## 7. Samsung

- **«Apps en suspensión»** (página de Samsung para desarrolladores): las que
  no se usan en unos 3 días y gastan batería pasan a suspensión, con
  restricciones a jobs, alarmas y servicios en primer plano; las de
  «suspensión profunda» sólo corren abiertas. Samsung garantiza los servicios
  en primer plano desde One UI 6 (apps que apuntan a Android 14); el S10e
  tiene One UI 4.1. *Inferido:* el Inicio se usa todo el día y no debería
  caer, pero conviene pedir «Nunca en suspensión» y batería «Sin
  restricciones» (Samsung documenta un deeplink a esas listas).
- **AOSP no congela el Inicio:** su proceso sube a `HOME_APP_ADJ` (600) y deja
  de contar como en caché (`services/core/java/com/android/server/am/OomAdjuster.java`,
  `android12-release`), y el congelador sólo congela desde
  `CACHED_APP_MIN_ADJ` (900). La guardia sigue corriendo con otra app
  adelante. Samsung tiene su propio congelador, sin documentación: es lo que
  hay que probar.
- **Accesibilidad:** forzar la detención la apaga del todo (AOSP saca el
  servicio de la lista de habilitados en `onHandleForceStop`,
  `AccessibilityManagerService.java`, de 12 a 17), y las apps de Play cuentan
  problemas con fabricantes (sección 1). *Inferido:* el acceso a datos de uso
  es una operación de AppOps y forzar la detención no lo toca.
- **Si Samsung congelara la guardia:** un servicio en primer plano (en Android
  12 sin tipo; desde 14, `specialUse` con declaración y video en Play).
- **Prueba:** el S10e con otra app adelante 30 minutos y la pantalla
  prendida, mirando en logcat que la guardia siga frenando, y el consumo en
  `dumpsys batterystats`.

## 8. Protección avanzada

- **Android 16** la estrenó: un interruptor que, entre otras cosas, bloquea
  instalar apps de fuentes desconocidas **y actualizar las que se instalaron
  así**, deja Play Protect siempre prendido y protege el USB con la pantalla
  bloqueada. Las apps la detectan con `AdvancedProtectionManager`.
- **Android 17** suma «Restricts accessibility services to verified
  accessibility tools» (ayuda de Android). En el código
  (`AccessibilityManagerService.java`, `android17-release`) pone la restricción
  global `DISALLOW_NON_TOOL_ACCESSIBILITY_SERVICE` y sólo permite paquetes de
  sistema o con `isAccessibilityTool`. AppBlock y Freedom avisan que se la
  sacan; AppBlock aclara que el bloqueo de apps sigue, porque no depende de la
  accesibilidad.
- Para Slowtown: la guardia por datos de uso no se ve afectada; un modo
  estricto con accesibilidad, sí. Las releases de GitHub no se instalan ni se
  actualizan en un teléfono con esta protección.

## 9. Verificación de desarrolladores

| Fecha | Qué |
|---|---|
| 08/2026 | Cuentas de distribución limitada (gratis, sin documento, hasta 20 equipos) y el «flujo avanzado» para instalar apps sin verificar |
| 30/09/2026 | Rige sólo para lo que se instala desde las tiendas participantes en Brasil, Indonesia, Singapur y Tailandia, en equipos certificados con Android 7 o más. Lo instalado directo de un APK todavía no |
| 2027 | Se extiende a todo el mundo y a todas las apps en equipos certificados. Argentina no tiene fecha propia |

- **adb no cambia:** se puede instalar sin verificación y sin la espera
  («The waiting period does not apply to ADB installs»). `provisionar.sh` y la
  salida por cable siguen andando.
- **El flujo avanzado:** activar el modo desarrollador, confirmar que nadie
  está guiando, reiniciar, esperar un día y confirmar con huella o PIN; después
  se instala sin verificar por 7 días o para siempre, con un aviso.
- **Una app sin registrar sólo se instala o actualiza con el flujo avanzado o
  por adb**: con el flujo apagado, sus actualizaciones fallan.
- **Para Slowtown:** Play registra sola casi todas sus apps. Las releases de
  GitHub (S10e incluido, que es un equipo certificado) necesitan, antes de que
  llegue a Argentina en 2027, registrar el paquete con la clave de releases:
  una cuenta completa (US$ 25) o una de distribución limitada (hasta 20
  equipos: alcanza para Marcos y algunos amigos). Hay una guía para registrar
  apps de código abierto que no llegué a leer.

## 10. Qué se cierra sin cable

| Hueco | Guardia (datos de uso + Inicio) | Con accesibilidad (como las apps de Play) | Device Owner |
|---|---|---|---|
| Panel y ajustes rápidos | Quedan (Wi-Fi, modo avión, editar mosaicos). Lo que abren como pantalla, el engranaje incluido, lo frena la guardia | Lo cierra al abrirse (`GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE`, API 31) | Lock task sin ajustes rápidos |
| Cambiar de launcher | Sólo desde Ajustes: la guardia puede frenar Ajustes entero; por página, *inferido* que no, porque en One UI casi todo es `SubSettings` | Frena la página por su título | Inicio fijo |
| Sacar el acceso a datos de uso | Desde Ajustes: igual | Igual | Ajustes no se alcanza |
| Forzar detención | Desde Ajustes; si pasa, al apretar Inicio vuelve a arrancar | La deja apagada hasta que el usuario la prenda | Ajustes no se alcanza |
| Desinstalar | Desde la Play Store, si está permitida (desinstala sin el instalador de Android). El instalador pasa sólo si lo abrió Slowtown | Device Admin más frenar su página (Play lo prohíbe) | No se desinstala |
| Modo seguro | Nada | Nada («no app can prevent that») | `DISALLOW_SAFE_BOOT` |

- **Una app común no puede cerrar el panel:** desde Android 12,
  `ACTION_CLOSE_SYSTEM_DIALOGS` tira `SecurityException` a las apps que
  apuntan a 12, y la salida que da Android es la acción de accesibilidad.
- **Las apps de Play cierran los huecos con accesibilidad** (páginas de
  Ajustes, el menú de apagado, el panel), Device Admin contra desinstalar, y
  su pantalla o superposición encima. Ninguna cierra el modo seguro.

## Pendiente

- En el S10e: la guardia con otra app adelante 30 minutos (el congelador de
  Samsung) y su consumo.
- En el emulador con Android 15 o 16: instalar desde un archivo y ver el
  acceso a datos de uso gris; después autoactualizar y ver si sigue
  restringida.
- Confirmar qué frenó Play Protect: instalar desde Chrome una variante sin
  `WavesListener`.
- Ver en los eventos de uso del S10e la clase de las pantallas de Ajustes que
  importan (aplicaciones predeterminadas, la información de Slowtown, el
  acceso a datos de uso), para saber si se pueden frenar sueltas.
- Leer la guía de registro de apps de código abierto de la verificación de
  desarrolladores.

## Fuentes

- https://developer.android.com/guide/components/activities/background-starts
- https://developer.android.com/about/versions/12/behavior-changes-all (`ACTION_CLOSE_SYSTEM_DIALOGS`)
- https://developer.android.com/about/versions/14/changes/schedule-exact-alarms
- https://developer.android.com/about/versions/15/behavior-changes-15 (`SYSTEM_ALERT_WINDOW` y servicios en primer plano)
- AOSP, `platform/frameworks/base`:
  `services/core/java/com/android/server/wm/ActivityStarter.java` (android12-release, android13-release),
  `services/core/java/com/android/server/wm/BackgroundActivityStartController.java` (android14-release a android17-release),
  `services/accessibility/java/com/android/server/accessibility/AccessibilityServiceConnection.java`,
  `services/accessibility/java/com/android/server/accessibility/AccessibilityManagerService.java` (android12-release, android17-release),
  `services/core/java/com/android/server/am/OomAdjuster.java` (android12-release),
  `services/core/java/com/android/server/pm/InstallPackageHelper.java` (android13-release, android16-release),
  `core/java/android/security/responsible_apis_flags.aconfig`,
  `packages/SettingsLib/src/com/android/settingslib/RestrictedLockUtilsInternal.java`
- AOSP, `platform/packages/modules/Permission`: `service/java/com/android/ecm/EnhancedConfirmationService.java`
- AOSP, `platform/packages/apps/Settings`: `src/com/android/settings/applications/UsageAccessDetails.java`
- AOSP, `platform/build/release`: `aconfig/ap3a`, `aconfig/bp1a`, `aconfig/bp2a`
- https://support.google.com/googleplay/android-developer/answer/9888170 (Permissions and APIs that Access Sensitive Information)
- https://support.google.com/googleplay/android-developer/answer/10964491 (AccessibilityService API)
- https://support.google.com/googleplay/android-developer/answer/10158779 (QUERY_ALL_PACKAGES)
- https://support.google.com/googleplay/android-developer/answer/16559646 (Device and Network Abuse)
- https://support.google.com/googleplay/android-developer/answer/9888380 (Malware)
- https://support.google.com/googleplay/android-developer/answer/13392821 (servicios en primer plano)
- https://developer.android.com/google/play/requirements/target-sdk
- https://source.android.com/docs/devices/admin/testing-setup (TestDPC desde Play y `dpm set-device-owner`)
- https://developer.android.com/guide/topics/admin/device-admin
- https://support.google.com/android/answer/12623953 (configuración restringida)
  y https://docs.lawnchair.app/troubleshooting/restricted-settings (qué cubre en Android 13)
- https://developer.android.com/google/play/integrity/verdicts
- https://developer.android.com/blog/posts/enhancing-android-security-stop-malware-from-snooping-on-your-app-data
- https://www.ocbc.com/personal-banking/help-and-support/digital-banking/online-banking-security.page
- https://www.insuit.net/digital-accessibility-argentine-banking-redlink/ (comunicación «A» 7517 del BCRA)
- https://security.googleblog.com/2024/02/piloting-new-ways-to-protect-Android-users-from%20financial-fraud.html
- https://blog.google/security/keeping-google-play-android-app-ecosystem-safe-2025/
- https://developer.samsung.com/mobile/app-management.html
- https://dontkillmyapp.com/samsung
- https://developer.android.com/privacy-and-security/advanced-protection-mode
- https://support.google.com/android/answer/16339980 (Protección avanzada)
- https://appblock.app/androids-new-advanced-protection-affects-appblock-from-blocking/
- https://support.freedom.to/en/articles/4529927-freedom-for-android-troubleshooting
- https://developer.android.com/developer-verification y https://developer.android.com/developer-verification/guides/faq
- https://android-developers.googleblog.com/2026/03/android-developer-verification.html
- Apps: https://appblock.app/why-do-i-have-to-grant-so-many-permissions/,
  https://play.google.com/store/apps/details?id=com.stayfocused,
  https://www.teqtic.com/lockmeout-changelog,
  https://www.screenzen.co/policies/privacy-policy,
  https://tutorials.one-sec.app/en/articles/3034754,
  https://opalapp.com/privacy,
  https://play.google.com/store/apps/details?id=com.urbandroid.ddc,
  https://play.google.com/store/apps/details?id=io.funswitch.blocker,
  https://play.google.com/store/apps/details?id=ai.regainapp,
  https://support.freedom.to/en/articles/6350161-freedom-for-android-app-options-explained,
  https://jomo.so
- Bienestar digital: `PermissionController/res/xml/roles.xml` (packages/modules/Permission, android12-release) y https://support.google.com/android/answer/9346420
- Latencia con datos de uso: https://dev.to/lui61140/why-i-removed-accessibilityservice-from-my-android-app-and-finally-got-into-play-store-4i52
