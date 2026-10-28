package com.slowtown.launcher

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.KeyguardManager
import android.app.role.RoleManager
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.slowtown.launcher.admin.AdminSession
import com.slowtown.launcher.admin.Bedtime
import com.slowtown.launcher.admin.Fixed
import com.slowtown.launcher.admin.FlowState
import com.slowtown.launcher.admin.FreeSession
import com.slowtown.launcher.admin.Guard
import com.slowtown.launcher.admin.Limits
import com.slowtown.launcher.admin.LockTaskEvents
import com.slowtown.launcher.admin.Pages
import com.slowtown.launcher.admin.Policies
import com.slowtown.launcher.admin.Rules
import com.slowtown.launcher.apps.AppCatalog
import com.slowtown.launcher.apps.Folder
import com.slowtown.launcher.apps.Folders
import com.slowtown.launcher.apps.HabitKind
import com.slowtown.launcher.apps.HabitPlan
import com.slowtown.launcher.apps.Habits
import com.slowtown.launcher.apps.LaunchableApp
import com.slowtown.launcher.apps.ScreenTime
import com.slowtown.launcher.security.CrashGuard
import com.slowtown.launcher.security.Password
import com.slowtown.launcher.ui.AdminScreen
import com.slowtown.launcher.ui.AdminStatus
import com.slowtown.launcher.ui.AppLimit
import com.slowtown.launcher.ui.AppActions
import com.slowtown.launcher.ui.BedtimeScreen
import com.slowtown.launcher.ui.BedtimeSettings
import com.slowtown.launcher.ui.NotificationsScreen
import com.slowtown.launcher.ui.ChooseAppsScreen
import com.slowtown.launcher.ui.CreatePasswordScreen
import com.slowtown.launcher.ui.DrawerScreen
import com.slowtown.launcher.ui.FlowSettings
import com.slowtown.launcher.ui.FlowStateScreen
import com.slowtown.launcher.ui.FolderScreen
import com.slowtown.launcher.ui.FreeSessionInfo
import com.slowtown.launcher.ui.FoldersScreen
import com.slowtown.launcher.ui.HabitsPickerScreen
import com.slowtown.launcher.ui.HabitsScreen
import com.slowtown.launcher.ui.HabitsWidget
import com.slowtown.launcher.ui.HomeLook
import com.slowtown.launcher.ui.HomeLookScreen
import com.slowtown.launcher.ui.HomeScreen
import com.slowtown.launcher.ui.LimitsScreen
import com.slowtown.launcher.ui.Pause
import com.slowtown.launcher.ui.SitesScreen
import com.slowtown.launcher.ui.SlowtownTheme
import com.slowtown.launcher.ui.UnlockScreen
import com.slowtown.launcher.ui.WidgetChoice
import com.slowtown.launcher.ui.WidgetPickerScreen
import com.slowtown.launcher.ui.WidgetSlot
import com.slowtown.launcher.update.Updater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * La pantalla de inicio, y la única actividad. Contraseña, administración,
 * todas las apps y elegir apps o widget son estados de esta misma: volver a
 * Inicio, del modo que sea, cae siempre acá, cierra la sesión de
 * administración y vuelve a activar Slowtown.
 *
 * Lo que lee todos los paquetes del teléfono (armar la política, listar las
 * apps) corre fuera del hilo principal: en un teléfono con cientos de apps
 * traba el arranque, y un ANR acá alimentaría la guardia de crashes.
 */
class HomeActivity : ComponentActivity() {

    private enum class Screen {
        Home, Drawer, Folder, Unlock, CreatePassword, Admin, ChooseApps, Sites, WidgetPicker, Folders, Habits,
        HabitsPicker, Bedtime, FlowState, FlowApps, Limits, LimitApps, Notifications, HomeLook,
    }

    private lateinit var prefs: Prefs
    private var screen by mutableStateOf(Screen.Home)
    private var status by mutableStateOf(AdminStatus())
    private var appsLoad: Job? = null

    /** Lo que se puede permitir, para la pantalla de elegir. */
    private var permittable by mutableStateOf<List<LaunchableApp>>(emptyList())

    /** Lo que se puede abrir ahora: con Slowtown activo, sólo lo permitido. */
    private var openable by mutableStateOf<List<LaunchableApp>>(emptyList())
    private var favorites by mutableStateOf<Set<String>>(emptySet())
    private var folders by mutableStateOf<List<Folder>>(emptyList())
    private var blockedSites by mutableStateOf<Set<String>>(emptySet())
    private var openFolderId by mutableStateOf<String?>(null)
    private var homeApps by mutableStateOf<List<LaunchableApp>?>(null)
    private var homeHint by mutableStateOf("")
    private var notice by mutableStateOf<String?>(null)
    private var freeInfo by mutableStateOf<FreeSessionInfo?>(null)

    /** Las ocultas de la lista de todas las apps: aparecen sólo buscándolas. */
    private var hidden by mutableStateOf<Set<String>>(emptySet())

    /** Si ahora mandan la noche o Flow State (con Slowtown activo): Inicio ofrece la ráfaga de 5 minutos. */
    private var pause by mutableStateOf<Pause?>(null)
    private var bedtimeSettings by mutableStateOf(BedtimeSettings(false, 23 * 60, 7 * 60, emptySet()))
    private var flowSettings by mutableStateOf(FlowSettings(false, emptyList(), emptySet()))
    private var limitRows by mutableStateOf<List<AppLimit>>(emptyList())

    /** Las apps de Inicio frenadas ahora (Flow State, su límite), por paquete: en gris, y tocarlas dice por qué. */
    private var held by mutableStateOf<Map<String, Rules.Hold>>(emptyMap())

    /** Por qué se frenó una app recién (la guardia, o tocarla en Inicio): se dice hasta irse de Inicio. */
    private var heldNotice: String? = null

    /** Cómo se ve Inicio: el tamaño, si va la hora grande, y el tamaño y el lugar de cada parte. */
    private var homeLook by mutableStateOf(HomeLook(100, false))

    /**
     * Si Inicio esconde sólo la hora de la barra (con el permiso de adb) y no
     * la barra entera, que se lleva la batería ([StatusBarClock]).
     */
    private var clockOnly by mutableStateOf(true)

    /** Si está prendido el bloqueo de páginas sin cable ([Pages]), para «Páginas bloqueadas». */
    private var pageGuardOn by mutableStateOf(false)

    private lateinit var widgetHost: AppWidgetHost
    private val widgetManager by lazy { AppWidgetManager.getInstance(this) }
    private var widget by mutableStateOf<Pair<Int, AppWidgetProviderInfo>?>(null)

    /** Los hábitos de Slowtown en el lugar del widget, en vez del de una app. */
    private var showHabits by mutableStateOf(false)
    private var habitPlan by mutableStateOf(Habits.DEFAULT_PLAN)
    private var habitMarks by mutableStateOf<Map<HabitKind, Set<LocalDate>>>(emptyMap())

    /** Lo mismo que muestra Bienestar digital, calculado con los datos de uso de Android. */
    private var screenTime by mutableStateOf<String?>(null)

    /** Bienestar digital, que se abre tocando el tiempo de pantalla; null si el teléfono no lo tiene. */
    private var wellbeing by mutableStateOf<Intent?>(null)
    private var today by mutableStateOf(LocalDate.now())
    private var widgetChoices by mutableStateOf<List<WidgetChoice>>(emptyList())

    /** El widget que se está armando, hasta que lo confirme su configuración. */
    private var pendingWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    /** Elegir Slowtown como Inicio, con el diálogo de Android: al volver, la guardia ya puede mirar. */
    private val chooseHome = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        refreshStatus()
        Guard.update(this)
    }

    /** Si Android no deja crear el widget sin preguntar, pregunta él (una vez, con «Permitir siempre»). */
    private val bindWidget = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) configureOrKeepWidget(pendingWidgetId) else discardPendingWidget()
    }

    /**
     * Sin Device Owner, desinstalar es el diálogo de Android. Abierto esperando
     * un resultado, Android vuelve recién cuando terminó de desinstalar: la
     * lista ya no la tiene.
     */
    private val uninstallDialog = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        refreshApps()
    }

    /** Apagar la pantalla termina la sesión de administración. */
    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = endAdminSession()
    }

    // RECEIVER_NOT_EXPORTED es de API 33; en Android 12 el sistema lo ignora, y
    // SCREEN_OFF lo manda sólo el sistema igual.
    @SuppressLint("InlinedApi")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Íconos claros en la barra aunque el sistema esté en modo claro: el
        // fondo de Slowtown es siempre negro.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        prefs = Prefs(this)
        homeLook = HomeLook(prefs.homeScalePercent, prefs.showClock, prefs.homeBlocks)
        // Con Inicio al frente y la hora grande, la de la barra se esconde (sin
        // el permiso de adb, la barra entera); al ir a otra pantalla de
        // Slowtown, vuelve. Escribir el ajuste es una llamada corta: en el hilo
        // principal, para que no se crucen.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                snapshotFlow { screen == Screen.Home && homeLook.showClock }
                    .collect { StatusBarClock.apply(this@HomeActivity, it) }
            }
        }
        widgetHost = AppWidgetHost(applicationContext, WIDGET_HOST_ID)
        pendingWidgetId = savedInstanceState?.getInt(KEY_PENDING_WIDGET, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        registerReceiver(screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF), RECEIVER_NOT_EXPORTED)

        // Una sesión de administración que quedó de una vida anterior de esta
        // pantalla ya terminó (salvo que se esté armando un widget, que
        // vuelve acá con su resultado). Y una vez por creación se recompone la
        // política, por si quedó algo a medias (una actualización, un
        // reinicio); en cada vuelta a Inicio no hace falta.
        val armingWidget = pendingWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID
        lifecycleScope.launch {
            withContext(Dispatchers.Default) {
                if (!armingWidget) AdminSession.end(this@HomeActivity)
                if (shouldBeClosed() && !armingWidget) Policies.apply(this@HomeActivity, prefs.allowedPackages)
            }
            lockIfNeeded()
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            // Atrás nunca sale del launcher: vuelve un paso, y de ahí a Inicio.
            override fun handleOnBackPressed() = when (screen) {
                Screen.Folder -> screen = Screen.Drawer
                Screen.HabitsPicker -> screen = Screen.Habits
                Screen.FlowApps -> screen = Screen.FlowState
                Screen.LimitApps -> screen = Screen.Limits
                Screen.ChooseApps, Screen.Sites, Screen.WidgetPicker, Screen.Folders, Screen.Bedtime,
                Screen.FlowState, Screen.Limits, Screen.Notifications, Screen.HomeLook ->
                    screen = Screen.Admin
                else -> goHome()
            }
        })

        // Los últimos 7 días corren a la medianoche aunque Inicio quede abierto.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    refreshHabits()
                    delay(untilMidnight())
                }
            }
        }

        // Si la noche o Flow State empiezan o terminan con Inicio a la vista,
        // el aviso y las apps cambian solos. Consulta barata: cada medio minuto.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                var was = pauseKey()
                while (true) {
                    delay(30_000L)
                    val now = pauseKey()
                    if (now != was) {
                        was = now
                        refreshStatus()
                        refreshApps()
                    }
                }
            }
        }

        // startLockTask y stopLockTask se aplican después de volver: el estado
        // que se muestra se actualiza cuando el sistema avisa.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                LockTaskEvents.changes.collect {
                    refreshStatus()
                    refreshApps()
                }
            }
        }

        setContent {
            val updateState by Updater.state.collectAsState()
            val appActions = AppActions(
                folders = folders,
                favorites = favorites,
                hidden = hidden,
                confirmsUninstall = status.deviceOwner,
                onToggleFavorite = ::toggleFavorite,
                onMoveToFolder = ::moveToFolder,
                onToggleHidden = ::toggleHidden,
                onAppInfo = ::openAppInfo,
                canUninstall = { AppCatalog.canUninstall(this@HomeActivity, it.packageName) },
                onUninstall = ::uninstall,
            )
            SlowtownTheme {
                when (screen) {
                    Screen.Home -> HomeScreen(
                        apps = homeApps,
                        hint = homeHint,
                        safeMode = status.safeMode,
                        notice = notice,
                        pause = pause,
                        onBurst = ::startBurst,
                        update = updateState,
                        onUpdate = { Updater.update(this@HomeActivity) },
                        screenTime = screenTime,
                        onScreenTime = wellbeing?.let { intent -> { openWellbeing(intent) } },
                        look = homeLook,
                        showBattery = homeLook.showClock && !clockOnly,
                        dimmed = held.keys,
                        widget = if (showHabits) {
                            @Composable { align: Alignment.Horizontal ->
                                HabitsWidget(
                                    plan = habitPlan,
                                    marks = habitMarks,
                                    today = today,
                                    onToggleToday = { toggleHabit(it, today) },
                                    onOpen = { screen = Screen.Habits },
                                    alignment = align,
                                )
                            }
                        } else {
                            widget?.let { (id, info) -> @Composable { _: Alignment.Horizontal -> WidgetSlot(widgetHost, id, info) } }
                        },
                        scaleWidget = showHabits,
                        onOpen = ::launch,
                        onAdmin = ::askForAdmin,
                        onDrawer = { screen = Screen.Drawer },
                        onNotifications = ::openNotifications,
                    )
                    Screen.Drawer -> DrawerScreen(
                        apps = openable,
                        actions = appActions,
                        onOpen = ::launchFromList,
                        onOpenFolder = {
                            openFolderId = it.id
                            screen = Screen.Folder
                        },
                        onBack = ::goHome,
                    )
                    Screen.Folder -> {
                        val folder = folders.firstOrNull { it.id == openFolderId }
                        if (folder == null) {
                            // La carpeta ya no existe (se borró): de vuelta a la lista.
                            LaunchedEffect(Unit) { screen = Screen.Drawer }
                        } else {
                            FolderScreen(
                                folder = folder,
                                apps = openable,
                                actions = appActions,
                                onOpen = ::launchFromList,
                                onBack = { screen = Screen.Drawer },
                            )
                        }
                    }
                    Screen.Unlock -> UnlockScreen(
                        onUnlocked = ::unlocked,
                        onBack = ::goHome,
                        freeSession = freeInfo,
                        onFreeSession = ::startFreeSession,
                    )
                    Screen.CreatePassword -> CreatePasswordScreen(
                        onCreated = ::unlocked,
                        onBack = ::goHome,
                    )
                    Screen.Admin -> AdminScreen(
                        status = status,
                        version = Updater.version(this@HomeActivity),
                        updates = Updater.enabled(this@HomeActivity),
                        update = updateState,
                        onCheckUpdate = { Updater.checkNow(this@HomeActivity) },
                        onUpdate = { Updater.update(this@HomeActivity) },
                        onChooseApps = { screen = Screen.ChooseApps },
                        onSites = {
                            blockedSites = prefs.blockedSites
                            pageGuardOn = Pages.isOn(this@HomeActivity)
                            screen = Screen.Sites
                        },
                        onChooseWidget = ::openWidgetPicker,
                        onHomeLook = { screen = Screen.HomeLook },
                        onFolders = { screen = Screen.Folders },
                        onBedtime = {
                            bedtimeSettings = BedtimeSettings(
                                prefs.bedtimeEnabled,
                                prefs.bedtimeStart,
                                prefs.bedtimeEnd,
                                prefs.bedtimeDays,
                            )
                            screen = Screen.Bedtime
                        },
                        onFlowState = {
                            flowSettings = FlowSettings(prefs.flowEnabled, prefs.flowWindows, prefs.flowApps)
                            screen = Screen.FlowState
                        },
                        onLimits = ::openLimits,
                        onUsageAccess = { startAdminSession(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) },
                        onChooseHome = ::askToBeHome,
                        onNotifications = { screen = Screen.Notifications },
                        onToggleClosed = ::toggleClosed,
                        onOpenSettings = { startAdminSession(Intent(Settings.ACTION_SETTINGS)) },
                        onOpenPlayStore = {
                            startAdminSession(packageManager.getLaunchIntentForPackage(AppCatalog.PLAY_STORE))
                        },
                        onChangePassword = { screen = Screen.CreatePassword },
                        onLeaveSafeMode = ::leaveSafeMode,
                        onBack = ::goHome,
                    )
                    Screen.ChooseApps -> ChooseAppsScreen(
                        apps = permittable,
                        chosen = prefs.allowed,
                        onDone = ::saveChosen,
                        onBack = { screen = Screen.Admin },
                    )
                    Screen.Notifications -> NotificationsScreen(
                        apps = permittable,
                        onBack = { screen = Screen.Admin },
                    )
                    Screen.Bedtime -> BedtimeScreen(
                        settings = bedtimeSettings,
                        onChange = ::saveBedtime,
                        onBack = { screen = Screen.Admin },
                    )
                    Screen.FlowState -> FlowStateScreen(
                        settings = flowSettings,
                        appNames = permittable.filter { it.packageName in flowSettings.apps }
                            .map { it.label }.distinct(),
                        onChange = ::saveFlow,
                        onChooseApps = { screen = Screen.FlowApps },
                        onBack = { screen = Screen.Admin },
                    )
                    Screen.FlowApps -> ChooseAppsScreen(
                        apps = permittable,
                        chosen = permittable.filter { it.packageName in flowSettings.apps }.mapTo(mutableSetOf()) { it.key },
                        onDone = { keys ->
                            saveFlow(flowSettings.copy(apps = packagesOf(keys)))
                            screen = Screen.FlowState
                        },
                        onBack = { screen = Screen.FlowState },
                        title = "Apps de Flow State",
                        hint = "Las que marques no se abren en los horarios de Flow State. Las llamadas, " +
                            "también de WhatsApp, entran igual.",
                    )
                    Screen.Limits -> LimitsScreen(
                        limits = limitRows,
                        onChange = ::saveLimit,
                        onChooseApps = { screen = Screen.LimitApps },
                        onBack = { screen = Screen.Admin },
                    )
                    Screen.LimitApps -> ChooseAppsScreen(
                        apps = permittable,
                        chosen = permittable.filter { app -> limitRows.any { it.packageName == app.packageName } }
                            .mapTo(mutableSetOf()) { it.key },
                        onDone = { keys ->
                            saveLimitApps(packagesOf(keys))
                            screen = Screen.Limits
                        },
                        onBack = { screen = Screen.Limits },
                        title = "Apps con límite",
                        hint = "Las que marques arrancan con ${Limits.format(Limits.DEFAULT)} por día; " +
                            "después se cambia en «Límites por app».",
                    )
                    Screen.HomeLook -> HomeLookScreen(
                        look = homeLook,
                        appWidget = !showHabits && widget != null,
                        onChange = ::saveHomeLook,
                        onBack = { screen = Screen.Admin },
                    )
                    Screen.Sites -> SitesScreen(
                        sites = blockedSites,
                        deviceOwner = status.deviceOwner,
                        pageGuard = pageGuardOn,
                        onPageGuard = ::openPageGuardSettings,
                        onAdd = { site -> saveSites(blockedSites + site) },
                        onRemove = { site -> saveSites(blockedSites - site) },
                        onBack = { screen = Screen.Admin },
                    )
                    Screen.Folders -> FoldersScreen(
                        folders = folders,
                        onCreate = { name -> updateFolders { Folders.create(it, name) } },
                        onRename = { id, name -> updateFolders { Folders.rename(it, id, name) } },
                        onDelete = { id -> updateFolders { Folders.delete(it, id) } },
                        onBack = { screen = Screen.Admin },
                    )
                    Screen.WidgetPicker -> WidgetPickerScreen(
                        choices = widgetChoices,
                        hasWidget = widget != null || showHabits,
                        onPickHabits = ::pickHabits,
                        onPick = ::pickWidget,
                        onRemove = ::removeWidget,
                        onBack = { screen = Screen.Admin },
                    )
                    Screen.Habits -> HabitsScreen(
                        plan = habitPlan,
                        marks = habitMarks,
                        today = today,
                        onToggle = ::toggleHabit,
                        onChoose = { screen = Screen.HabitsPicker },
                        onBack = ::goHome,
                    )
                    Screen.HabitsPicker -> HabitsPickerScreen(
                        plan = habitPlan,
                        onChange = ::saveHabitPlan,
                        onBack = { screen = Screen.Habits },
                    )
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_PENDING_WIDGET, pendingWidgetId)
    }

    override fun onStart() {
        super.onStart()
        widgetHost.startListening()
    }

    // La hora de la barra vuelve apenas Inicio deja de estar al frente: otra
    // app, la pantalla apagada, un cuelgue que reinicia. Ver StatusBarClock.
    override fun onPause() {
        StatusBarClock.apply(this, false)
        super.onPause()
    }

    /**
     * Deslizar para abajo en Inicio abre las notificaciones, como en Olauncher:
     * sin la barra de arriba es lo más a mano. Android no tiene una manera
     * pública; ésta es la de la barra misma, con EXPAND_STATUS_BAR, y si un
     * día deja de andar, el gesto no hace nada.
     */
    @SuppressLint("WrongConstant", "PrivateApi")
    private fun openNotifications() {
        runCatching {
            Class.forName("android.app.StatusBarManager").getMethod("expandNotificationsPanel")
                .invoke(getSystemService("statusbar"))
        }.onFailure { Log.w(TAG, "No pude abrir las notificaciones", it) }
    }

    override fun onDestroy() {
        unregisterReceiver(screenOff)
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        // Los dos se cambian afuera: el permiso por adb, la accesibilidad en Ajustes.
        clockOnly = StatusBarClock.canHideClockOnly(this)
        pageGuardOn = Pages.isOn(this)
        // Si la guardia (o la lista de lock task) acaba de frenar una app, Inicio dice por qué.
        Guard.lastHeld?.let { last ->
            Guard.clearLastHeld()
            if (SystemClock.elapsedRealtime() - last.at < HELD_NOTICE_MS) heldNotice = holdText(last.packageName, last.hold)
        }
        // Si una alarma de fin se atrasó, la sesión se cierra acá.
        lifecycleScope.launch {
            withContext(Dispatchers.Default) {
                AdminSession.endIfExpired(this@HomeActivity)
                Bedtime.endBurstIfExpired(this@HomeActivity)
                FreeSession.endIfExpired(this@HomeActivity)
                // Por si una alarma de los horarios se atrasó: los límites, la
                // lista de lock task (escribe sólo si cambió) y la guardia.
                Bedtime.refresh(this@HomeActivity)
            }
            refreshStatus()
            refreshApps()
        }
        // Al volver a Inicio: mientras se mira Inicio, el tiempo no corre.
        lifecycleScope.launch {
            val millis = withContext(Dispatchers.Default) { ScreenTime.today(this@HomeActivity) }
            screenTime = millis?.let { ScreenTime.format(it) }
            wellbeing = withContext(Dispatchers.Default) { ScreenTime.wellbeing(this@HomeActivity) }
        }
        if (screen == Screen.Home && pendingWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            // Volver de Ajustes con Atrás también termina la sesión, no sólo Inicio.
            endAdminSession()
            lockIfNeeded()
        }
        refreshStatus()
        refreshApps()
        refreshWidget()
        // Si hay una versión nueva de Slowtown. Busca en su hilo, cada 6 horas como mucho.
        Updater.onHome(this)
    }

    /**
     * Si al reanudar la pantalla de bloqueo todavía figuraba como visible (el
     * desbloqueo mismo reanuda la actividad), el foco llega recién cuando se
     * va: es el segundo intento de activar.
     */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && screen == Screen.Home) lockIfNeeded()
    }

    /** Apretar Inicio estando en Slowtown llega acá. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        goHome()
    }

    override fun onStop() {
        super.onStop()
        widgetHost.stopListening()
        // Lo que se frenó ya se dijo: al volver a Inicio, el aviso no sigue.
        heldNotice = null
        // Una sesión de administración no sobrevive a apagar la pantalla ni a
        // irse a otra app: al volver, contraseña de nuevo.
        screen = Screen.Home
    }

    private fun goHome() {
        screen = Screen.Home
        discardPendingWidget()
        endAdminSession()
        lockIfNeeded()
        refreshStatus()
        refreshApps()
    }

    private fun askForAdmin() {
        screen = if (Password.isSet(this)) Screen.Unlock else Screen.CreatePassword
    }

    /**
     * La contraseña se verifica en segundo plano: si mientras tanto se apagó
     * la pantalla, al volver no se puede caer en la administración.
     */
    private fun showIfStarted(next: Screen) {
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) screen = next
    }

    /** Con la contraseña puesta, la administración. */
    private fun unlocked() = showIfStarted(Screen.Admin)

    private fun launch(app: LaunchableApp) {
        // Frenada (Flow State, su límite): ni se intenta, se dice por qué.
        held[app.packageName]?.let { hold ->
            heldNotice = holdText(app.packageName, hold)
            refreshStatus()
            return
        }
        runCatching { startActivity(app.intent()) }
            .onFailure { Log.w(TAG, "No pude abrir ${app.key}", it) }
    }

    /** Bienestar digital, con el detalle de hoy por app. Con Slowtown activo está en la lista (ver Policies). */
    private fun openWellbeing(intent: Intent) {
        runCatching { startActivity(Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { Log.w(TAG, "No pude abrir Bienestar digital", it) }
    }

    /** Desde la lista de todas o una carpeta: al volver, Inicio y no la lista. */
    private fun launchFromList(app: LaunchableApp) {
        screen = Screen.Home
        launch(app)
    }

    /** Las carpetas son sólo orden: no piden contraseña. */
    private fun moveToFolder(app: LaunchableApp, to: String?) =
        updateFolders { Folders.move(it, app.key, to) }

    private fun updateFolders(change: (List<Folder>) -> List<Folder>) {
        val updated = change(prefs.folders)
        prefs.folders = updated
        folders = updated
    }

    /** Poner o sacar de Inicio no pide contraseña: no afloja nada. */
    private fun toggleFavorite(app: LaunchableApp) {
        val current = prefs.favorites
        prefs.favorites = if (app.key in current) current - app.key else current + app.key
        refreshApps()
    }

    /** Ocultar de la lista tampoco: buscándola aparece igual, y sigue andando de fondo. */
    private fun toggleHidden(app: LaunchableApp) {
        val current = prefs.hiddenApps
        val updated = if (app.key in current) current - app.key else current + app.key
        prefs.hiddenApps = updated
        hidden = updated
    }

    /**
     * La información de una app es una pantalla de Ajustes. Sin contraseña:
     * con Slowtown activo, en una sesión que suma sólo Ajustes y termina como
     * la de administración (ver AdminSession). Con Slowtown en pausa, o con
     * «Habilitar todo», que ya abre Ajustes, directo.
     */
    private fun openAppInfo(app: LaunchableApp) {
        val info = AppCatalog.infoIntent(app.packageName)
        if (Rules.active(this) && !FreeSession.isActive(this)) {
            startAdminSession(info, infoOnly = true)
            return
        }
        runCatching { startActivity(info) }
            .onFailure { Log.w(TAG, "No pude abrir la información de ${app.packageName}", it) }
    }

    /**
     * La pantalla de Ajustes donde se prende (o se apaga) el bloqueo de
     * páginas sin cable. Con Slowtown activo va en la sesión chica, como la
     * información de una app, y al terminar se vuelve a Inicio; si no, directo,
     * y al volver «Páginas bloqueadas» dice cómo quedó.
     */
    private fun openPageGuardSettings() {
        val settings = Pages.settingsIntent()
        if (Rules.active(this) && !FreeSession.isActive(this)) {
            startAdminSession(settings, infoOnly = true)
            return
        }
        runCatching { startActivity(settings) }
            .onFailure { Log.w(TAG, "No pude abrir los ajustes de accesibilidad", it) }
    }

    /**
     * Desinstalar no pide contraseña: sacar una app no afloja nada. Sin Device
     * Owner pregunta Android, con su diálogo. Con Device Owner ya preguntó
     * Slowtown, y desinstala sin el diálogo de Android, que con Slowtown activo
     * quedaría bloqueado: el instalador no está en la lista.
     */
    private fun uninstall(app: LaunchableApp) {
        if (!Policies.isDeviceOwner(this)) {
            // Sin cable, la guardia deja pasar el diálogo de Android porque lo abrió Slowtown.
            Guard.allowInstaller()
            runCatching { uninstallDialog.launch(AppCatalog.uninstallIntent(app.packageName)) }
                .onFailure { Log.w(TAG, "No pude abrir el diálogo para desinstalar ${app.packageName}", it) }
            return
        }
        AppCatalog.uninstall(this, app.packageName) { ok ->
            // Falla, por ejemplo, si es administradora del teléfono.
            if (!ok) {
                Toast.makeText(applicationContext, "No se pudo desinstalar ${app.label}.", Toast.LENGTH_LONG).show()
            }
            refreshApps()
            refreshWidget()
        }
    }

    private fun shouldBeClosed(): Boolean =
        prefs.closed && !CrashGuard.inSafeMode(this) && Policies.isDeviceOwner(this)

    /** Arma la política fuera del hilo principal y después sigue con [then]. */
    private fun applyPolicy(then: () -> Unit = {}) {
        lifecycleScope.launch {
            withContext(Dispatchers.Default) { Policies.apply(this@HomeActivity, prefs.allowedPackages) }
            then()
        }
    }

    /**
     * Entra en lock task si corresponde. Sólo eso: la política se arma en
     * [Policies.apply] cuando algo cambia, no en cada vuelta a Inicio.
     */
    private fun lockIfNeeded() {
        if (AdminSession.isActive(this) || isLocked() || !shouldBeClosed()) return
        // Entrar en lock task con la pantalla bloqueada deja al sistema en un
        // estado raro; se reintenta al recuperar el foco.
        if (getSystemService(KeyguardManager::class.java).isKeyguardLocked) return
        if (!Policies.readyToLock(this)) return
        runCatching { startLockTask() }
            .onFailure { Log.w(TAG, "No pude activar Slowtown (lock task)", it) }
    }

    private fun isLocked(): Boolean =
        getSystemService(ActivityManager::class.java).lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE

    private fun toggleClosed() {
        prefs.closed = !prefs.closed
        if (prefs.closed) {
            applyPolicy {
                lockIfNeeded()
                refreshStatus()
                refreshApps()
            }
        } else {
            // Salir de lock task desde la tarea raíz cierra las demás tareas: lo
            // avisa la pantalla de administración.
            if (isLocked()) runCatching { stopLockTask() }
            Policies.open(this)
            refreshStatus()
            refreshApps()
        }
        // Sin cable, lo que cierra es la guardia: arranca o para.
        Guard.update(this)
    }

    /**
     * Ajustes y la Play Store: todo el teléfono queda a mano hasta volver a
     * Inicio, apagar la pantalla o pasar [AdminSession.LENGTH_MS]. Con Device
     * Owner agranda la lista de lock task; sin cable, la guardia deja pasar.
     */
    private fun startAdminSession(target: Intent?, infoOnly: Boolean = false) {
        if (target == null) return
        screen = Screen.Home
        lifecycleScope.launch {
            // Con Slowtown activo, con cable o sin él: la sesión es lo que deja pasar.
            if (Rules.active(this@HomeActivity)) {
                withContext(Dispatchers.Default) { AdminSession.start(this@HomeActivity, prefs.allowedPackages, infoOnly) }
            }
            runCatching { startActivity(target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                .onFailure { Log.w(TAG, "No pude abrir $target", it) }
        }
    }

    /** Cinco minutos con las apps de siempre, de noche. Sin contraseña. */
    private fun startBurst() {
        lifecycleScope.launch {
            withContext(Dispatchers.Default) { Bedtime.startBurst(this@HomeActivity) }
            refreshStatus()
            refreshApps()
            delay(Bedtime.BURST_MS + 1_000L)
            refreshStatus()
            refreshApps()
        }
    }

    /** Bedtime: rige desde ya (si es de noche, la lista se achica) y se agenda el próximo cambio. */
    private fun saveBedtime(settings: BedtimeSettings) {
        prefs.bedtimeEnabled = settings.enabled
        prefs.bedtimeStart = settings.start
        prefs.bedtimeEnd = settings.end
        prefs.bedtimeDays = settings.days
        bedtimeSettings = settings
        lifecycleScope.launch {
            withContext(Dispatchers.Default) {
                Bedtime.schedule(this@HomeActivity)
                Bedtime.refresh(this@HomeActivity)
            }
            refreshStatus()
            refreshApps()
        }
    }

    /** Flow State: rige desde ya (si es uno de sus horarios, frena sus apps) y se agenda el próximo cambio. */
    private fun saveFlow(settings: FlowSettings) {
        prefs.flowEnabled = settings.enabled
        prefs.flowWindows = settings.windows
        prefs.flowApps = settings.apps
        flowSettings = settings
        rearm()
    }

    /** Los límites, con lo que lleva hoy cada app. Recorrer el uso del día tarda: fuera del hilo principal. */
    private fun openLimits() {
        screen = Screen.Limits
        lifecycleScope.launch { limitRows = withContext(Dispatchers.Default) { loadLimits() } }
    }

    private fun loadLimits(): List<AppLimit> {
        val usage = ScreenTime.byApp(this)
        return prefs.appLimits.map { (pkg, minutes) -> AppLimit(pkg, appLabel(pkg), minutes, usage?.let { it[pkg] ?: 0L }) }
            .sortedBy { it.label.lowercase() }
    }

    /** Otro límite para una app, o ninguno ([minutes] null). Rige desde ya. */
    private fun saveLimit(packageName: String, minutes: Int?) {
        val limits = prefs.appLimits
        prefs.appLimits = if (minutes == null) limits - packageName else limits + (packageName to Limits.round(minutes))
        limitRows = if (minutes == null) {
            limitRows.filterNot { it.packageName == packageName }
        } else {
            limitRows.map { if (it.packageName == packageName) it.copy(minutes = Limits.round(minutes)) else it }
        }
        rearm()
    }

    /** Las apps con límite: las nuevas, con el de entrada; las que se desmarcaron, sin. */
    private fun saveLimitApps(packages: Set<String>) {
        val limits = prefs.appLimits
        prefs.appLimits = packages.associateWith { limits[it] ?: Limits.DEFAULT }
        lifecycleScope.launch {
            limitRows = withContext(Dispatchers.Default) { loadLimits() }
        }
        rearm()
    }

    /** Lo que cambió en los horarios o los límites rige desde ya: agenda, mira el uso y rearma lo que cierra. */
    private fun rearm() {
        lifecycleScope.launch {
            withContext(Dispatchers.Default) {
                Bedtime.schedule(this@HomeActivity)
                Limits.check(this@HomeActivity)
                Bedtime.refresh(this@HomeActivity)
            }
            refreshStatus()
            refreshApps()
        }
    }

    /** Elegir Slowtown como Inicio: sin cable, la guardia lo necesita para volver a Inicio. */
    private fun askToBeHome() {
        val roles = getSystemService(RoleManager::class.java)
        runCatching { chooseHome.launch(roles.createRequestRoleIntent(RoleManager.ROLE_HOME)) }
            .onFailure { startAdminSession(Intent(Settings.ACTION_HOME_SETTINGS)) }
    }

    /** Cómo se ve Inicio: no hay nada que rearmar, Inicio lo toma al volver. */
    private fun saveHomeLook(look: HomeLook) {
        prefs.homeScalePercent = look.scalePercent
        prefs.showClock = look.showClock
        prefs.homeBlocks = look.blocks
        homeLook = look
    }

    /**
     * «Habilitar todo por 5 minutos», sin contraseña y con tope. No se corta
     * al volver a Inicio: al terminar, se rearma la lista de siempre.
     */
    private fun startFreeSession() {
        lifecycleScope.launch {
            withContext(Dispatchers.Default) { FreeSession.start(this@HomeActivity, prefs.allowedPackages) }
            screen = Screen.Home
            refreshStatus()
            refreshApps()
            // Para que el aviso se vaya y la lista se achique solos al terminar.
            delay(FreeSession.LENGTH_MS + 1_000L)
            refreshStatus()
            refreshApps()
        }
    }

    private fun endAdminSession() {
        if (!AdminSession.isActive(this)) return
        lifecycleScope.launch {
            withContext(Dispatchers.Default) { AdminSession.end(this@HomeActivity) }
        }
    }

    private fun openWidgetPicker() {
        screen = Screen.WidgetPicker
        lifecycleScope.launch { widgetChoices = withContext(Dispatchers.Default) { loadWidgetChoices() } }
    }

    // La variante con ApplicationInfoFlags recién existe en API 33.
    @Suppress("DEPRECATION")
    private fun loadWidgetChoices(): List<WidgetChoice> {
        val pm = packageManager
        return widgetManager.getInstalledProvidersForProfile(Process.myUserHandle())
            .map { info ->
                val app = runCatching {
                    pm.getApplicationLabel(pm.getApplicationInfo(info.provider.packageName, 0)).toString()
                }.getOrDefault(info.provider.packageName)
                WidgetChoice(info, info.loadLabel(pm), app)
            }
            .sortedWith(compareBy({ it.app.lowercase() }, { it.label.lowercase() }))
    }

    /**
     * Arma el widget. Con Slowtown activo va en una sesión de administración:
     * el diálogo de permiso es de Ajustes y la configuración del widget es de
     * su app, y las dos quedarían bloqueadas.
     */
    private fun pickWidget(choice: WidgetChoice) {
        lifecycleScope.launch {
            if (Rules.active(this@HomeActivity)) {
                withContext(Dispatchers.Default) { AdminSession.start(this@HomeActivity, prefs.allowedPackages) }
            }
            discardPendingWidget()
            val id = widgetHost.allocateAppWidgetId()
            pendingWidgetId = id
            val info = choice.info
            if (widgetManager.bindAppWidgetIdIfAllowed(id, info.profile, info.provider, null)) {
                configureOrKeepWidget(id)
            } else {
                bindWidget.launch(
                    Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                        .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                        .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider)
                        .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE, info.profile),
                )
            }
        }
    }

    private fun configureOrKeepWidget(id: Int) {
        val info = widgetManager.getAppWidgetInfo(id) ?: return discardPendingWidget()
        if (info.configure == null) return keepWidget(id)
        runCatching {
            widgetHost.startAppWidgetConfigureActivityForResult(this, id, 0, REQUEST_CONFIGURE_WIDGET, null)
        }.onFailure {
            Log.w(TAG, "No pude abrir la configuración del widget", it)
            discardPendingWidget()
        }
    }

    // La configuración del widget la abre AppWidgetHost con
    // startIntentSenderForResult, que todavía responde por acá.
    @Deprecated("La configuración de widgets todavía responde por onActivityResult")
    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_CONFIGURE_WIDGET) return
        if (resultCode == RESULT_OK) keepWidget(pendingWidgetId) else discardPendingWidget()
    }

    private fun keepWidget(id: Int) {
        val previous = prefs.widgetId
        if (previous != AppWidgetManager.INVALID_APPWIDGET_ID && previous != id) {
            widgetHost.deleteAppWidgetId(previous)
        }
        prefs.widgetId = id
        prefs.habitsWidget = false
        pendingWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
        refreshWidget()
        endAdminSession()
        screen = Screen.Home
    }

    /** Un widget a medio armar (cancelado o sin permiso) se borra, y la sesión de armado termina. */
    private fun discardPendingWidget() {
        if (pendingWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return
        widgetHost.deleteAppWidgetId(pendingWidgetId)
        pendingWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
        endAdminSession()
    }

    private fun removeWidget() {
        val id = prefs.widgetId
        if (id != AppWidgetManager.INVALID_APPWIDGET_ID) widgetHost.deleteAppWidgetId(id)
        prefs.widgetId = AppWidgetManager.INVALID_APPWIDGET_ID
        prefs.habitsWidget = false
        refreshWidget()
        screen = Screen.Admin
    }

    /** Los hábitos en el lugar del widget: el de otra app, si había, se suelta. */
    private fun pickHabits() {
        val id = prefs.widgetId
        if (id != AppWidgetManager.INVALID_APPWIDGET_ID) widgetHost.deleteAppWidgetId(id)
        prefs.widgetId = AppWidgetManager.INVALID_APPWIDGET_ID
        prefs.habitsWidget = true
        refreshWidget()
        screen = Screen.Home
    }

    /** Marcar o desmarcar un día no pide contraseña: no afloja nada. Los días que no llegaron, no. */
    private fun toggleHabit(kind: HabitKind, date: LocalDate) {
        if (date.isAfter(LocalDate.now())) return
        val current = Habits.parse(prefs.habitDays(kind))
        val updated = if (date in current) current - date else current + date
        prefs.setHabitDays(kind, updated.mapTo(mutableSetOf()) { it.toString() })
        habitMarks = habitMarks + (kind to updated)
    }

    /** Qué hábitos van en Inicio y cuántas veces por semana: sin contraseña, no afloja nada. */
    private fun saveHabitPlan(plan: List<HabitPlan>) {
        prefs.habitPlan = plan
        habitPlan = plan
    }

    private fun refreshHabits() {
        today = LocalDate.now()
        habitPlan = prefs.habitPlan
        habitMarks = HabitKind.entries.associateWith { Habits.parse(prefs.habitDays(it)) }
    }

    /** Hasta un segundo después de la medianoche, para no despertar un instante antes. */
    private fun untilMidnight(): Long =
        Duration.between(LocalDateTime.now(), LocalDate.now().plusDays(1).atStartOfDay()).toMillis() + 1_000L

    /** Si la app del widget se desinstaló, el lugar queda vacío. */
    private fun refreshWidget() {
        showHabits = prefs.habitsWidget
        val id = prefs.widgetId
        val info = if (id != AppWidgetManager.INVALID_APPWIDGET_ID) widgetManager.getAppWidgetInfo(id) else null
        widget = info?.let { id to it }
    }

    private fun saveChosen(keys: Set<String>) {
        prefs.allowed = keys
        // Con Slowtown activo, la lista nueva rige desde ya.
        if (shouldBeClosed()) applyPolicy()
        screen = Screen.Admin
        refreshApps()
    }

    private fun leaveSafeMode() {
        CrashGuard.leaveSafeMode(this)
        if (shouldBeClosed()) {
            applyPolicy {
                lockIfNeeded()
                refreshStatus()
            }
        } else {
            refreshStatus()
        }
    }

    /** El nivel 1 de las páginas: rige en Chrome desde ya, esté Slowtown activo o en pausa. */
    private fun saveSites(sites: Set<String>) {
        prefs.blockedSites = sites
        blockedSites = sites
        lifecycleScope.launch {
            withContext(Dispatchers.Default) {
                runCatching { Fixed.applySites(this@HomeActivity) }
                    .onFailure { Log.e(TAG, "No pude poner las páginas en Chrome", it) }
            }
        }
    }

    private fun refreshStatus() {
        status = AdminStatus(
            deviceOwner = Policies.isDeviceOwner(this),
            closed = prefs.closed,
            locked = isLocked(),
            safeMode = CrashGuard.inSafeMode(this),
            usageAccess = ScreenTime.hasAccess(this),
            home = Guard.isHome(this),
        )
        val active = Rules.active(this)
        val freeUntil = FreeSession.until(this)
        val burstUntil = Bedtime.burstUntil(this)
        val nightNow = active && Bedtime.isNight(this)
        val flowNow = active && FlowState.isOn(this)
        // De noche o en Flow State, el aviso lo arma HomeScreen: «Es Bedtime, andá a dormir».
        pause = when {
            burstUntil > 0L -> null
            nightNow -> Pause.Night
            flowNow -> Pause.Flow(FlowState.endsAt(this)?.format(HHMM))
            else -> null
        }
        val session = when {
            freeUntil > 0 -> "Todo habilitado hasta las ${hhmm(freeUntil)}"
            (nightNow || flowNow) && burstUntil > 0 -> "Ráfaga de 5 minutos: hasta las ${hhmm(burstUntil)}"
            else -> null
        }
        notice = listOfNotNull(heldNotice, session).joinToString("\n").ifEmpty { null }
        // El botón sólo tiene sentido con Slowtown activo y sin una sesión en curso.
        freeInfo = if (active && freeUntil == 0L) {
            FreeSessionInfo(FreeSession.remaining(this), FreeSession.nextAt(this)?.let(::whenText))
        } else {
            null
        }
    }

    /** Lo que cambia solo con la hora: la noche y Flow State. */
    private fun pauseKey(): Pair<Boolean, Boolean> = Bedtime.restricts(this) to FlowState.held(this).isNotEmpty()

    /** Las de [apps] que Flow State o su límite frenan ahora, por paquete. */
    private fun heldOf(apps: List<LaunchableApp>): Map<String, Rules.Hold> =
        apps.map { it.packageName }.distinct().mapNotNull { pkg ->
            Rules.hold(this, pkg)?.takeIf { it is Rules.Hold.Flow || it is Rules.Hold.Limit }?.let { pkg to it }
        }.toMap()

    /** Por qué no se abre [packageName], para el aviso de Inicio. */
    private fun holdText(packageName: String, hold: Rules.Hold): String {
        val label = appLabel(packageName)
        return when (hold) {
            Rules.Hold.NotAllowed -> "$label no está entre las apps permitidas."
            Rules.Hold.Night -> "Es Bedtime: $label vuelve a las ${minutesText(prefs.bedtimeEnd)}."
            is Rules.Hold.Flow -> hold.until?.let { "Es Flow State: $label vuelve a las ${it.format(HHMM)}." }
                ?: "Es Flow State: $label vuelve cuando termine."
            is Rules.Hold.Limit -> "$label llegó a su límite de hoy (${Limits.format(hold.minutes)}). Vuelve mañana."
        }
    }

    private fun minutesText(minutes: Int): String = "%02d:%02d".format(minutes / 60, minutes % 60)

    /** El nombre de una app, o su paquete si no se encuentra. */
    // La variante con ApplicationInfoFlags recién existe en API 33.
    @Suppress("DEPRECATION")
    private fun appLabel(packageName: String): String = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString()
    }.getOrDefault(packageName)

    /** Los paquetes de unas apps elegidas, que se eligen como `paquete/actividad`. */
    private fun packagesOf(keys: Set<String>): Set<String> =
        keys.mapNotNullTo(mutableSetOf()) { ComponentName.unflattenFromString(it)?.packageName }

    private fun hhmm(millis: Long): String =
        Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(HHMM)

    /** «a las 21:30», o «mañana a las 05:58» si cae otro día. */
    private fun whenText(millis: Long): String {
        val zone = ZoneId.systemDefault()
        val at = Instant.ofEpochMilli(millis).atZone(zone)
        val day = if (at.toLocalDate().isAfter(LocalDate.now(zone))) "mañana " else ""
        return "${day}a las ${at.format(HHMM)}"
    }

    /** Leer las etiquetas de todas las apps tarda: fuera del hilo principal. */
    private fun refreshApps() {
        appsLoad?.cancel()
        appsLoad = lifecycleScope.launch {
            // Lo del nivel 2 no aparece en ningún lado: no se abre, ni se puede
            // permitir. Sin Device Owner (después de liberar) no hay nivel 2.
            val vetoed = if (Policies.isDeviceOwner(this@HomeActivity)) prefs.vetoed + Fixed.APPS else emptySet()
            val all = withContext(Dispatchers.Default) { AppCatalog.all(this@HomeActivity) }
                .filterNot { it.packageName in vetoed }
            val active = Rules.active(this@HomeActivity)
            val allowed = prefs.allowed
            // Con «Habilitar todo» en curso se ven todas, como sin Slowtown. De
            // noche, sólo el reloj y las alarmas.
            val nightNow = active && Bedtime.restricts(this@HomeActivity)
            val nightApps = if (nightNow) withContext(Dispatchers.Default) { Bedtime.apps(this@HomeActivity) } else emptySet()
            val visible = when {
                !active || FreeSession.isActive(this@HomeActivity) -> all
                nightNow -> all.filter { it.packageName in nightApps }
                else -> all.filter { it.key in allowed }
            }
            val chosen = prefs.favorites
            permittable = AppCatalog.permittable(all)
            openable = visible
            favorites = chosen
            // Ocultar es sólo de la lista de todas: Inicio las muestra igual.
            hidden = prefs.hiddenApps
            folders = prefs.folders
            homeApps = if (nightNow) visible else visible.filter { it.key in chosen }
            held = if (active && !nightNow) withContext(Dispatchers.Default) { heldOf(visible) } else emptyMap()
            homeHint = if (active && visible.isEmpty()) {
                "Mantené apretada ${if (prefs.showClock) "la hora" else "la fecha"} para elegir las apps permitidas."
            } else {
                "Deslizá para arriba para ver todas las apps, y mantené apretada una para ponerla acá."
            }
        }
    }

    private companion object {
        const val TAG = "Slowtown"
        const val WIDGET_HOST_ID = 0x5107
        const val REQUEST_CONFIGURE_WIDGET = 1
        const val KEY_PENDING_WIDGET = "pending_widget"

        /** Cuánto después de que la guardia frenó una app Inicio todavía dice por qué. */
        const val HELD_NOTICE_MS = 15_000L
        val HHMM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    }
}
