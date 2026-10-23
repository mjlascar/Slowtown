package com.slowtown.launcher

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.KeyguardManager
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.Bundle
import android.os.Process
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.slowtown.launcher.admin.AdminSession
import com.slowtown.launcher.admin.Bedtime
import com.slowtown.launcher.admin.Fixed
import com.slowtown.launcher.admin.FreeSession
import com.slowtown.launcher.admin.LockTaskEvents
import com.slowtown.launcher.admin.Policies
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
import com.slowtown.launcher.ui.AppActions
import com.slowtown.launcher.ui.BedtimeScreen
import com.slowtown.launcher.ui.BedtimeSettings
import com.slowtown.launcher.ui.NotificationsScreen
import com.slowtown.launcher.ui.ChooseAppsScreen
import com.slowtown.launcher.ui.CreatePasswordScreen
import com.slowtown.launcher.ui.DrawerScreen
import com.slowtown.launcher.ui.FolderScreen
import com.slowtown.launcher.ui.FreeSessionInfo
import com.slowtown.launcher.ui.FoldersScreen
import com.slowtown.launcher.ui.HabitsPickerScreen
import com.slowtown.launcher.ui.HabitsScreen
import com.slowtown.launcher.ui.HabitsWidget
import com.slowtown.launcher.ui.HomeLook
import com.slowtown.launcher.ui.HomeLookScreen
import com.slowtown.launcher.ui.HomeScreen
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

    private enum class Screen { Home, Drawer, Folder, Unlock, CreatePassword, Admin, ChooseApps, Sites, WidgetPicker, Folders, Habits, HabitsPicker, Bedtime, Notifications, HomeLook }

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

    /** Si ahora manda la noche (con Slowtown activo): Inicio ofrece la ráfaga de 5 minutos. */
    private var night by mutableStateOf(false)
    private var bedtimeSettings by mutableStateOf(BedtimeSettings(false, 23 * 60, 7 * 60, emptySet()))

    /** Cómo se ve Inicio: el tamaño y si va la hora grande. */
    private var homeLook by mutableStateOf(HomeLook(100, false))

    private lateinit var widgetHost: AppWidgetHost
    private val widgetManager by lazy { AppWidgetManager.getInstance(this) }
    private var widget by mutableStateOf<Pair<Int, AppWidgetProviderInfo>?>(null)

    /** Los hábitos de Slowtown en el lugar del widget, en vez del de una app. */
    private var showHabits by mutableStateOf(false)
    private var habitPlan by mutableStateOf(Habits.DEFAULT_PLAN)
    private var habitMarks by mutableStateOf<Map<HabitKind, Set<LocalDate>>>(emptyMap())

    /** Lo mismo que muestra Bienestar digital, calculado con los datos de uso de Android. */
    private var screenTime by mutableStateOf<String?>(null)
    private var today by mutableStateOf(LocalDate.now())
    private var widgetChoices by mutableStateOf<List<WidgetChoice>>(emptyList())

    /** El widget que se está armando, hasta que lo confirme su configuración. */
    private var pendingWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID

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
        homeLook = HomeLook(prefs.homeScalePercent, prefs.showClock)
        // Con Inicio al frente y la hora grande, la de la barra se esconde; al
        // ir a otra pantalla de Slowtown, vuelve. Escribir el ajuste es una
        // llamada corta: en el hilo principal, para que no se crucen.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                snapshotFlow { screen == Screen.Home && homeLook.showClock }
                    .collect { StatusBarClock.setHidden(this@HomeActivity, it) }
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
                Screen.ChooseApps, Screen.Sites, Screen.WidgetPicker, Screen.Folders, Screen.Bedtime,
                Screen.Notifications, Screen.HomeLook ->
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

        // Si la noche empieza o termina con Inicio a la vista, el aviso y las
        // apps cambian solos. Consulta barata: una vez cada medio minuto.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                var was = Bedtime.restricts(this@HomeActivity)
                while (true) {
                    delay(30_000L)
                    val now = Bedtime.restricts(this@HomeActivity)
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
                        onBurst = if (night) ::startBurst else null,
                        update = updateState,
                        onUpdate = { Updater.update(this@HomeActivity) },
                        screenTime = screenTime,
                        showClock = homeLook.showClock,
                        scalePercent = homeLook.scalePercent,
                        widget = if (showHabits) {
                            @Composable {
                                HabitsWidget(
                                    plan = habitPlan,
                                    marks = habitMarks,
                                    today = today,
                                    onToggleToday = { toggleHabit(it, today) },
                                    onOpen = { screen = Screen.Habits },
                                )
                            }
                        } else {
                            widget?.let { (id, info) -> @Composable { WidgetSlot(widgetHost, id, info) } }
                        },
                        scaleWidget = showHabits,
                        onOpen = ::launch,
                        onAdmin = ::askForAdmin,
                        onDrawer = { screen = Screen.Drawer },
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
                    Screen.HomeLook -> HomeLookScreen(
                        look = homeLook,
                        onChange = ::saveHomeLook,
                        onBack = { screen = Screen.Admin },
                    )
                    Screen.Sites -> SitesScreen(
                        sites = blockedSites,
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
        StatusBarClock.setHidden(this, false)
        super.onPause()
    }

    override fun onDestroy() {
        unregisterReceiver(screenOff)
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        // Si una alarma de fin se atrasó, la sesión se cierra acá.
        lifecycleScope.launch {
            withContext(Dispatchers.Default) {
                AdminSession.endIfExpired(this@HomeActivity)
                Bedtime.endBurstIfExpired(this@HomeActivity)
                // Por si la alarma de la noche se atrasó: escribe sólo si cambió.
                if (shouldBeClosed()) Policies.refreshPackages(this@HomeActivity, prefs.allowedPackages)
                FreeSession.endIfExpired(this@HomeActivity)
            }
            refreshStatus()
            refreshApps()
        }
        // Al volver a Inicio: mientras se mira Inicio, el tiempo no corre.
        lifecycleScope.launch {
            val millis = withContext(Dispatchers.Default) { ScreenTime.today(this@HomeActivity) }
            screenTime = millis?.let { ScreenTime.format(it) }
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
        runCatching { startActivity(app.intent()) }
            .onFailure { Log.w(TAG, "No pude abrir ${app.key}", it) }
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
     * La información de una app es una pantalla de Ajustes: con Slowtown
     * activo, con la contraseña y en una sesión de administración. Con Slowtown
     * en pausa, o con «Habilitar todo», que ya abre Ajustes, directo.
     */
    /**
     * Sin contraseña: con Slowtown activo, en una sesión que suma sólo Ajustes
     * y termina como la de administración (ver AdminSession).
     */
    private fun openAppInfo(app: LaunchableApp) {
        val info = AppCatalog.infoIntent(app.packageName)
        if (isLocked() && !FreeSession.isActive(this)) {
            startAdminSession(info, infoOnly = true)
            return
        }
        runCatching { startActivity(info) }
            .onFailure { Log.w(TAG, "No pude abrir la información de ${app.packageName}", it) }
    }

    /**
     * Desinstalar no pide contraseña: sacar una app no afloja nada. Sin Device
     * Owner pregunta Android, con su diálogo. Con Device Owner ya preguntó
     * Slowtown, y desinstala sin el diálogo de Android, que con Slowtown activo
     * quedaría bloqueado: el instalador no está en la lista.
     */
    private fun uninstall(app: LaunchableApp) {
        if (!Policies.isDeviceOwner(this)) {
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
    }

    /**
     * Ajustes y la Play Store: todo el teléfono queda a mano hasta volver a
     * Inicio, apagar la pantalla o pasar [AdminSession.LENGTH_MS].
     */
    private fun startAdminSession(target: Intent?, infoOnly: Boolean = false) {
        if (target == null) return
        screen = Screen.Home
        lifecycleScope.launch {
            if (isLocked()) {
                withContext(Dispatchers.Default) { AdminSession.start(this@HomeActivity, prefs.allowedPackages, infoOnly) }
            }
            runCatching { startActivity(target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                .onFailure { Log.w(TAG, "No pude abrir $target", it) }
        }
    }

    /**
     * «Habilitar todo por 5 minutos», sin contraseña y con tope. No se corta
     * al volver a Inicio: al terminar, se rearma la lista de siempre.
     */
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

    /** Cómo se ve Inicio: no hay nada que rearmar, Inicio lo toma al volver. */
    private fun saveHomeLook(look: HomeLook) {
        prefs.homeScalePercent = look.scalePercent
        prefs.showClock = look.showClock
        homeLook = look
    }

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
            if (isLocked()) {
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
        )
        val freeUntil = FreeSession.until(this)
        val burstUntil = Bedtime.burstUntil(this)
        val bedtime = shouldBeClosed() && Bedtime.isNight(this)
        night = bedtime && burstUntil == 0L
        notice = when {
            freeUntil > 0 -> "Todo habilitado hasta las ${hhmm(freeUntil)}"
            bedtime && burstUntil > 0 -> "Ráfaga de 5 minutos: hasta las ${hhmm(burstUntil)}"
            // De noche el aviso es otro, y lo arma HomeScreen: «Es Bedtime, andá a dormir».
            else -> null
        }
        // El botón sólo tiene sentido con Slowtown activo y sin una sesión en curso.
        freeInfo = if (isLocked() && freeUntil == 0L) {
            FreeSessionInfo(FreeSession.remaining(this), FreeSession.nextAt(this)?.let(::whenText))
        } else {
            null
        }
    }

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
            val active = shouldBeClosed()
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
        val HHMM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    }
}
