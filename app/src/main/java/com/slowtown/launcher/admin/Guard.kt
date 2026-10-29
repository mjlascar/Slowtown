package com.slowtown.launcher.admin

import android.app.role.RoleManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import com.slowtown.launcher.HomeActivity
import com.slowtown.launcher.Prefs
import com.slowtown.launcher.apps.ScreenTime

/**
 * La guardia: mira qué app está adelante y, si no corresponde ([Rules.hold]),
 * vuelve a Inicio y dice por qué. Sin Device Owner es lo que cierra el
 * teléfono, como las apps de bloqueo de la Play Store (docs/sin-cable.md),
 * con lo mínimo: el acceso a los datos de uso, que se da en Ajustes sin cable
 * (y que Slowtown ya usa para el tiempo de pantalla), y ser el Inicio, que
 * Android deja abrir pantallas desde segundo plano. Con Device Owner corre
 * sólo si hay límites por app: lock task no sabe cuánto lleva cada una.
 *
 * Lee los eventos de uso de Android cada medio segundo, en su propio hilo y
 * sólo con el teléfono en uso ([Bedtime.inUse]): con la pantalla apagada o
 * bloqueada no hay nada que mirar.
 */
object Guard {
    private const val TAG = "Slowtown"
    private const val POLL_MS = 500L

    /** Al arrancar, hasta dónde mirar para saber qué app quedó adelante. */
    private const val LOOK_BACK_MS = 10 * 60_000L

    /** Después de volver a Inicio, cuánto esperar antes de hacerlo otra vez con la misma app. */
    private const val KICK_AGAIN_MS = 2_000L

    /** La barra del sistema, que la guardia no frena: sus diálogos son pantallas. */
    private val EXEMPT = setOf("com.android.systemui")

    /**
     * El instalador de Android, que pregunta antes de instalar o desinstalar:
     * estos y el que atienda «desinstalar» en el teléfono ([installers]).
     * Pasa sólo un rato después de que Slowtown lo abre ([allowInstaller]):
     * si pasara siempre, cualquier app que ofrezca desinstalar podría sacar a
     * Slowtown sin cable. Fuera de ese rato está entre lo que no se abre nunca
     * ([Rules.alwaysBlocked]).
     */
    private val INSTALLERS = setOf(
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
        "com.samsung.android.packageinstaller",
    )
    private const val INSTALLER_MS = 2 * 60_000L

    @Volatile
    private var installerUntil = 0L

    /**
     * Deja pasar el instalador de Android un par de minutos: lo abre Slowtown
     * para desinstalar una app de la lista o para confirmar una actualización.
     */
    fun allowInstaller() {
        installerUntil = SystemClock.elapsedRealtime() + INSTALLER_MS
    }

    private val handler by lazy { Handler(HandlerThread("guardia").apply { start() }.looper) }
    private val loop = Runnable { tick() }

    // Todo esto, sólo en el hilo de la guardia.
    private lateinit var app: Context
    private var running = false
    private var launchers = emptySet<String>()
    private var installers = INSTALLERS
    private var cursor = 0L
    private var front: String? = null
    private var frontClass: String? = null
    private var limitPackage: String? = null
    private var limitAt = 0L
    private var kickedPackage: String? = null
    private var kickedAt = 0L

    /** Una app que se frenó: cuál, por qué y cuándo (en tiempo desde el arranque). */
    data class Held(val packageName: String, val hold: Rules.Hold, val at: Long)

    /** La última que frenó la guardia, para que Inicio diga por qué. */
    @Volatile
    var lastHeld: Held? = null
        private set

    fun clearLastHeld() {
        lastHeld = null
    }

    /** Si la guardia puede cerrar el teléfono sin cable: con el acceso a los datos de uso y siendo el Inicio. */
    fun canWatch(context: Context): Boolean = ScreenTime.hasAccess(context) && isHome(context)

    fun isHome(context: Context): Boolean = runCatching {
        context.getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_HOME)
    }.getOrDefault(false)

    /**
     * Arranca, sigue o para, según haga falta, y mira ya lo que está adelante.
     * Va al prender o apagar la pantalla, al desbloquear, al volver a Inicio y
     * cuando cambian las reglas (activar, pausar, los horarios, un límite).
     */
    fun update(context: Context) {
        val appContext = context.applicationContext
        handler.post {
            app = appContext
            handler.removeCallbacks(loop)
            if (!running) {
                cursor = System.currentTimeMillis() - LOOK_BACK_MS
                front = null
                launchers = runCatching { launchers(appContext) }.getOrDefault(emptySet())
                installers = runCatching { installers(appContext) }.getOrDefault(INSTALLERS)
            }
            tick()
        }
    }

    private fun tick() {
        val context = app
        running = runCatching { needed(context) && Bedtime.inUse(context) }.getOrDefault(false)
        if (!running) {
            front = null
            limitPackage = null
            return
        }
        runCatching {
            read(context)
            front?.let { judge(context, it) }
        }.onFailure { Log.e(TAG, "La guardia falló", it) }
        handler.postDelayed(loop, POLL_MS)
    }

    /** Sin cable con Slowtown activo, siempre; con Device Owner, sólo para los límites por app. */
    private fun needed(context: Context): Boolean = when (Rules.mode(context)) {
        Rules.Mode.GUARD -> true
        Rules.Mode.LOCK_TASK -> Prefs(context).appLimits.isNotEmpty()
        Rules.Mode.PAUSED -> false
    }

    /**
     * Los eventos nuevos: la app de adelante es la de la última pantalla que
     * arrancó, hasta que esa misma pantalla se pausa. Como en [ScreenTime],
     * la pausa de otra pantalla de la misma app no cuenta: llega un instante
     * después de que arrancó la nueva.
     */
    private fun read(context: Context) {
        val now = System.currentTimeMillis()
        val events = context.getSystemService(UsageStatsManager::class.java).queryEvents(cursor, now) ?: return
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            when (event.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> {
                    front = event.packageName
                    frontClass = event.className
                }
                UsageEvents.Event.ACTIVITY_PAUSED -> {
                    val same = event.className == null || frontClass == null || event.className == frontClass
                    if (event.packageName == front && same) front = null
                }
            }
            cursor = maxOf(cursor, event.timeStamp + 1)
        }
    }

    private fun judge(context: Context, pkg: String) {
        if (pkg == context.packageName || pkg in EXEMPT || pkg in launchers) return
        if (pkg in installers && SystemClock.elapsedRealtime() < installerUntil) return
        val mode = Rules.mode(context)
        if (mode == Rules.Mode.GUARD) {
            Rules.hold(context, pkg)?.let { return kick(context, pkg, it) }
        }
        watchLimit(context, pkg, mode)
    }

    /**
     * Si la app de adelante tiene límite, cuándo se le acaba: el uso del día
     * se mira al llegar adelante y cuando se cumple ese rato, no a cada vuelta.
     * Por si Slowtown no sigue corriendo, también lo agenda el sistema.
     */
    private fun watchLimit(context: Context, pkg: String, mode: Rules.Mode) {
        val limits = Prefs(context).appLimits
        if (pkg !in limits) {
            limitPackage = null
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (pkg == limitPackage && now < limitAt) return
        val usage = Limits.check(context) ?: return
        val left = Limits.remaining(context, pkg, usage) ?: return
        if (left > 0) {
            limitPackage = pkg
            limitAt = now + left
            Bedtime.scheduleLimitCheck(context, left)
            return
        }
        limitPackage = null
        // Con Device Owner, sacarla de la lista la cierra; sin cable, la vuelta a Inicio.
        if (mode == Rules.Mode.LOCK_TASK) Policies.refreshPackages(context)
        kick(context, pkg, Rules.Hold.Limit(limits.getValue(pkg)))
    }

    /**
     * Los Inicios de fábrica, que la guardia tampoco frena: mientras Slowtown
     * se actualiza, Android muestra un instante el de fábrica, y frenarlo
     * dejaba en Inicio «One UI Home no está entre las apps permitidas» (con la
     * lista de permitidas de antes del 29/10). Desde él, o desde sus Recientes,
     * sólo se salta a otras apps, que la guardia mira igual. Ajustes no:
     * también se anuncia como Inicio (la pantalla de respaldo del arranque,
     * con prioridad negativa), y dejarlo pasar era dejar pasar Ajustes entero
     * (lo vio la prueba del emulador). Uno de terceros (Olauncher) tampoco:
     * desde él se pide ser el Inicio, y sin serlo Slowtown la guardia se apaga
     * ([Rules.alwaysBlocked] lo frena siempre; lo encontró una revisión, 29/10).
     */
    // La variante con ResolveInfoFlags recién existe en API 33.
    @Suppress("DEPRECATION")
    private fun launchers(context: Context): Set<String> =
        context.packageManager
            .queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_ALL)
            .filter { it.priority >= 0 && it.activityInfo.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0 }
            .mapTo(mutableSetOf()) { it.activityInfo.packageName } - Rules.SETTINGS

    /** El instalador de Android: los de siempre y quien atienda «desinstalar» en este teléfono (cada marca puede tener el suyo). */
    // La variante con ResolveInfoFlags recién existe en API 33.
    @Suppress("DEPRECATION")
    fun installers(context: Context): Set<String> {
        val delete = Intent(Intent.ACTION_DELETE, Uri.fromParts("package", context.packageName, null))
        return INSTALLERS + context.packageManager.queryIntentActivities(delete, PackageManager.MATCH_SYSTEM_ONLY)
            .map { it.activityInfo.packageName }
    }

    /** Vuelve a Inicio, que dice por qué ([lastHeld]). */
    private fun kick(context: Context, pkg: String, hold: Rules.Hold) {
        val now = SystemClock.elapsedRealtime()
        if (pkg == kickedPackage && now - kickedAt < KICK_AGAIN_MS) return
        kickedPackage = pkg
        kickedAt = now
        lastHeld = Held(pkg, hold, now)
        Log.i(TAG, "La guardia frenó $pkg: $hold")
        runCatching {
            context.startActivity(Intent(context, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { Log.w(TAG, "La guardia no pudo volver a Inicio", it) }
    }
}
