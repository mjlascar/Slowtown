package com.slowtown.launcher

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import com.slowtown.launcher.apps.LaunchableApp
import com.slowtown.launcher.security.CrashGuard
import com.slowtown.launcher.security.Password
import com.slowtown.launcher.ui.AdminScreen
import com.slowtown.launcher.ui.AdminStatus
import com.slowtown.launcher.ui.ChooseAppsScreen
import com.slowtown.launcher.ui.CreatePasswordScreen
import com.slowtown.launcher.ui.HomeScreen
import com.slowtown.launcher.ui.SlowtownTheme
import com.slowtown.launcher.ui.UnlockScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * La pantalla de inicio, y la única actividad. Contraseña, administración y
 * elegir apps son estados de esta misma: volver a Inicio, del modo que sea,
 * cae siempre acá, cierra la sesión de administración y vuelve a activar
 * Slowtown.
 *
 * Lo que lee todos los paquetes del teléfono (armar la política, listar las
 * apps) corre fuera del hilo principal: en un teléfono con cientos de apps
 * traba el arranque, y un ANR acá alimentaría la guardia de crashes.
 */
class HomeActivity : ComponentActivity() {

    private enum class Screen { Home, Unlock, CreatePassword, Admin, ChooseApps }

    private lateinit var prefs: Prefs
    private var screen by mutableStateOf(Screen.Home)
    private var catalog by mutableStateOf<List<LaunchableApp>>(emptyList())
    private var homeApps by mutableStateOf<List<LaunchableApp>?>(null)
    private var status by mutableStateOf(AdminStatus())
    private var appsLoad: Job? = null

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
        registerReceiver(screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF), RECEIVER_NOT_EXPORTED)

        // Una sesión de administración que quedó de una vida anterior de esta
        // pantalla ya terminó. Y una vez por creación se recompone la política,
        // por si quedó algo a medias (una actualización, un reinicio); en cada
        // vuelta a Inicio no hace falta.
        lifecycleScope.launch {
            withContext(Dispatchers.Default) {
                AdminSession.end(this@HomeActivity)
                if (shouldBeClosed()) Policies.apply(this@HomeActivity, prefs.allowedPackages)
            }
            lockIfNeeded()
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            // Atrás nunca sale del launcher: desde una pantalla interna vuelve a Inicio.
            override fun handleOnBackPressed() = goHome()
        })

        // startLockTask y stopLockTask se aplican después de volver: el estado
        // que se muestra se actualiza cuando el sistema avisa.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                LockTaskEvents.changes.collect { refreshStatus() }
            }
        }

        setContent {
            SlowtownTheme {
                when (screen) {
                    Screen.Home -> HomeScreen(
                        apps = homeApps,
                        safeMode = status.safeMode,
                        onOpen = ::launch,
                        onAdmin = ::askForAdmin,
                    )
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
                        apps = catalog,
                        chosen = prefs.allowed,
                        onDone = ::saveChosen,
                        onBack = { screen = Screen.Admin },
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        unregisterReceiver(screenOff)
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        if (screen == Screen.Home) {
            // Volver de Ajustes con Atrás también termina la sesión, no sólo Inicio.
            endAdminSession()
            lockIfNeeded()
        }
        refreshStatus()
        refreshApps()
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
        // Una sesión de administración no sobrevive a apagar la pantalla ni a
        // irse a otra app: al volver, contraseña de nuevo.
        screen = Screen.Home
    }

    private fun goHome() {
        screen = Screen.Home
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
            }
        } else {
            // Salir de lock task desde la tarea raíz cierra las demás tareas: lo
            // avisa la pantalla de administración.
            if (isLocked()) runCatching { stopLockTask() }
            Policies.open(this)
            refreshStatus()
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
            val apps = withContext(Dispatchers.Default) { AppCatalog.launchable(this@HomeActivity) }
            val allowed = prefs.allowed
            catalog = apps
            homeApps = apps.filter { it.key in allowed }
        }
    }

    private companion object {
        const val TAG = "Slowtown"
    }
}
