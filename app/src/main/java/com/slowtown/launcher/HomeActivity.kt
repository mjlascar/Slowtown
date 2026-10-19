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
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.slowtown.launcher.admin.AdminSession
import com.slowtown.launcher.admin.LockTaskEvents
import com.slowtown.launcher.admin.Policies
import com.slowtown.launcher.apps.AppCatalog
import com.slowtown.launcher.apps.Folder
import com.slowtown.launcher.apps.Folders
import com.slowtown.launcher.apps.LaunchableApp
import com.slowtown.launcher.security.CrashGuard
import com.slowtown.launcher.security.Password
import com.slowtown.launcher.ui.AdminScreen
import com.slowtown.launcher.ui.AdminStatus
import com.slowtown.launcher.ui.AppActions
import com.slowtown.launcher.ui.ChooseAppsScreen
import com.slowtown.launcher.ui.CreatePasswordScreen
import com.slowtown.launcher.ui.DrawerScreen
import com.slowtown.launcher.ui.FolderScreen
import com.slowtown.launcher.ui.FoldersScreen
import com.slowtown.launcher.ui.HomeScreen
import com.slowtown.launcher.ui.SlowtownTheme
import com.slowtown.launcher.ui.UnlockScreen
import com.slowtown.launcher.ui.WidgetChoice
import com.slowtown.launcher.ui.WidgetPickerScreen
import com.slowtown.launcher.ui.WidgetSlot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    private enum class Screen { Home, Drawer, Folder, Unlock, CreatePassword, Admin, ChooseApps, WidgetPicker, Folders }

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
    private var openFolderId by mutableStateOf<String?>(null)
    private var homeApps by mutableStateOf<List<LaunchableApp>?>(null)
    private var homeHint by mutableStateOf("")

    private lateinit var widgetHost: AppWidgetHost
    private val widgetManager by lazy { AppWidgetManager.getInstance(this) }
    private var widget by mutableStateOf<Pair<Int, AppWidgetProviderInfo>?>(null)
    private var widgetChoices by mutableStateOf<List<WidgetChoice>>(emptyList())

    /** El widget que se está armando, hasta que lo confirme su configuración. */
    private var pendingWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    /** Si Android no deja crear el widget sin preguntar, pregunta él (una vez, con «Permitir siempre»). */
    private val bindWidget = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) configureOrKeepWidget(pendingWidgetId) else discardPendingWidget()
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
                Screen.ChooseApps, Screen.WidgetPicker, Screen.Folders -> screen = Screen.Admin
                else -> goHome()
            }
        })

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
            val appActions = AppActions(
                folders = folders,
                favorites = favorites,
                onToggleFavorite = ::toggleFavorite,
                onMoveToFolder = ::moveToFolder,
            )
            SlowtownTheme {
                when (screen) {
                    Screen.Home -> HomeScreen(
                        apps = homeApps,
                        hint = homeHint,
                        safeMode = status.safeMode,
                        widget = widget?.let { (id, info) -> @Composable { WidgetSlot(widgetHost, id, info) } },
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
                        onUnlocked = { showIfStarted(Screen.Admin) },
                        onBack = ::goHome,
                    )
                    Screen.CreatePassword -> CreatePasswordScreen(
                        onCreated = { showIfStarted(Screen.Admin) },
                        onBack = ::goHome,
                    )
                    Screen.Admin -> AdminScreen(
                        status = status,
                        onChooseApps = { screen = Screen.ChooseApps },
                        onChooseWidget = ::openWidgetPicker,
                        onFolders = { screen = Screen.Folders },
                        onToggleClosed = ::toggleClosed,
                        onOpenSettings = { startAdminSession(Intent(Settings.ACTION_SETTINGS)) },
                        onOpenPlayStore = {
                            startAdminSession(packageManager.getLaunchIntentForPackage(AppCatalog.PLAY_STORE))
                        },
                        onChangePassword = { screen = Screen.CreatePassword },
                        onLeaveSafeMode = ::leaveSafeMode,
                        onRelease = ::release,
                        onBack = ::goHome,
                    )
                    Screen.ChooseApps -> ChooseAppsScreen(
                        apps = permittable,
                        chosen = prefs.allowed,
                        onDone = ::saveChosen,
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
                        hasWidget = widget != null,
                        onPick = ::pickWidget,
                        onRemove = ::removeWidget,
                        onBack = { screen = Screen.Admin },
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

    override fun onDestroy() {
        unregisterReceiver(screenOff)
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        if (screen == Screen.Home && pendingWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            // Volver de Ajustes con Atrás también termina la sesión, no sólo Inicio.
            endAdminSession()
            lockIfNeeded()
        }
        refreshStatus()
        refreshApps()
        refreshWidget()
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
    private fun startAdminSession(target: Intent?) {
        if (target == null) return
        screen = Screen.Home
        lifecycleScope.launch {
            if (isLocked()) {
                withContext(Dispatchers.Default) { AdminSession.start(this@HomeActivity, prefs.allowedPackages) }
            }
            runCatching { startActivity(target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                .onFailure { Log.w(TAG, "No pude abrir $target", it) }
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
        refreshWidget()
        screen = Screen.Admin
    }

    /** Si la app del widget se desinstaló, el lugar queda vacío. */
    private fun refreshWidget() {
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

    private fun release() {
        if (isLocked()) runCatching { stopLockTask() }
        Policies.release(this)
        prefs.closed = false
        refreshStatus()
        refreshApps()
    }

    private fun refreshStatus() {
        status = AdminStatus(
            deviceOwner = Policies.isDeviceOwner(this),
            closed = prefs.closed,
            locked = isLocked(),
            safeMode = CrashGuard.inSafeMode(this),
        )
    }

    /** Leer las etiquetas de todas las apps tarda: fuera del hilo principal. */
    private fun refreshApps() {
        appsLoad?.cancel()
        appsLoad = lifecycleScope.launch {
            val all = withContext(Dispatchers.Default) { AppCatalog.all(this@HomeActivity) }
            val active = shouldBeClosed()
            val allowed = prefs.allowed
            val visible = if (active) all.filter { it.key in allowed } else all
            val chosen = prefs.favorites
            permittable = AppCatalog.permittable(all)
            openable = visible
            favorites = chosen
            folders = prefs.folders
            homeApps = visible.filter { it.key in chosen }
            homeHint = if (active && visible.isEmpty()) {
                "Mantené apretada la hora para elegir las apps permitidas."
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
    }
}
