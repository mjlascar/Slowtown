package com.slowtown.launcher.admin

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.SystemClock
import com.slowtown.launcher.Prefs
import com.slowtown.launcher.apps.AppCatalog
import com.slowtown.launcher.apps.ScreenTime
import com.slowtown.launcher.security.CrashGuard
import java.time.LocalDateTime

/**
 * Qué se puede abrir ahora con Slowtown activo, y por qué no lo demás. Lo
 * hacen cumplir dos cosas, según el teléfono: con Device Owner, la lista de
 * lock task ([Policies]); sin cable, la guardia ([Guard]), que vuelve a Inicio
 * si adelante queda una app que no corresponde, como las apps de bloqueo de la
 * Play Store (ver docs/sin-cable.md). Las dos leen las mismas reglas:
 *
 * - todo, menos las bloqueadas y lo que no se abre nunca ([blocked],
 *   [alwaysBlocked]); lo que no puede faltar ([always]) no se bloquea;
 * - de noche, sólo lo que no puede faltar y las apps de Bedtime ([Bedtime]);
 * - en Flow State, sin sus apps ([FlowState]);
 * - sin las que llegaron a su límite del día ([Limits]);
 * - la app de una llamada, siempre ([CallGuard]);
 * - con una sesión (administración o «Habilitar todo»), todo.
 *
 * Hasta el 29/10 se elegían las permitidas y lo demás quedaba cerrado; Marcos
 * prefirió elegir las bloqueadas ([Prefs.blocked]).
 */
object Rules {

    /** Cómo cierra Slowtown ahora. */
    enum class Mode {
        /** No cierra: en pausa, en el modo a prueba de fallos, o sin con qué. */
        PAUSED,

        /** Con Device Owner: lock task. */
        LOCK_TASK,

        /** Sin cable: la guardia, con el acceso a los datos de uso. */
        GUARD,
    }

    /** Por qué una app no se abre ahora. */
    sealed interface Hold {
        /** Está bloqueada: la eligió el usuario, o no se abre nunca ([alwaysBlocked]). */
        data object Blocked : Hold

        /** Es de noche ([Bedtime]). */
        data object Night : Hold

        /** Es Flow State, hasta [until] (null si no se sabe). */
        data class Flow(val until: LocalDateTime?) : Hold

        /** Llegó a su límite del día, de [minutes]. */
        data class Limit(val minutes: Int) : Hold
    }

    fun mode(context: Context): Mode {
        if (!Prefs(context).closed || CrashGuard.inSafeMode(context)) return Mode.PAUSED
        return when {
            Policies.isDeviceOwner(context) -> Mode.LOCK_TASK
            Guard.canWatch(context) -> Mode.GUARD
            else -> Mode.PAUSED
        }
    }

    fun active(context: Context): Boolean = mode(context) != Mode.PAUSED

    /** Si manda la noche: es de noche, sin ráfaga, con el teléfono en uso y sin llamada ([Bedtime]). */
    fun night(context: Context): Boolean =
        Bedtime.restricts(context) && Bedtime.inUse(context) && !CallGuard.inCall(context)

    /** Lo que Flow State y los límites frenan ahora, salvo la app de una llamada. */
    fun held(context: Context): Set<String> =
        FlowState.held(context) + Limits.reached(context) - CallGuard.packages(context)

    /**
     * Para la lista de lock task: lo que se puede abrir ahora sin contar las
     * sesiones. Lo que no puede faltar, lo instalado que no está bloqueado ni
     * frenado (de noche, sólo lo elegido para Bedtime) y la app de una llamada.
     * Recorre lo instalado: fuera del hilo principal.
     */
    fun allowed(context: Context): Set<String> = buildSet {
        addAll(always(context))
        val open = permitted(context) - held(context)
        addAll(if (night(context)) open intersect Prefs(context).bedtimeApps else open)
        addAll(CallGuard.packages(context))
    }

    /**
     * Lo instalado que no está bloqueado, sin los otros Inicios: con Device
     * Owner, Inicio es siempre Slowtown, y desde otro se llegaría a sus Recientes.
     */
    // Las variantes con *Flags recién existen en API 33.
    @Suppress("DEPRECATION")
    private fun permitted(context: Context): Set<String> {
        val pm = context.packageManager
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val launchers = pm.queryIntentActivities(home, PackageManager.MATCH_ALL).map { it.activityInfo.packageName }
        return pm.getInstalledPackages(0).mapTo(mutableSetOf()) { it.packageName } - blocked(context) - launchers.toSet()
    }

    /** Las que eligió el usuario y lo que no se abre nunca ([alwaysBlocked]), por paquete. */
    fun blocked(context: Context): Set<String> = Prefs(context).blockedPackages + alwaysBlocked(context)

    /**
     * Para la guardia: por qué [pkg] no se puede abrir ahora, o null si se
     * puede. Las mismas reglas que [allowed] y las sesiones de [Policies].
     */
    fun hold(context: Context, pkg: String): Hold? {
        if (FreeSession.isActive(context)) return null
        if (AdminSession.isActive(context) && (!AdminSession.isInfoOnly(context) || pkg in INFO_SCREENS)) return null
        if (pkg in always(context) || pkg in CallGuard.packages(context)) return null
        val prefs = Prefs(context)
        return when {
            pkg in prefs.blockedPackages || pkg in alwaysBlocked(context) -> Hold.Blocked
            night(context) && pkg !in prefs.bedtimeApps -> Hold.Night
            pkg in FlowState.held(context) -> Hold.Flow(FlowState.endsAt(context))
            pkg in Limits.reached(context) -> Hold.Limit(prefs.appLimits[pkg] ?: 0)
            else -> null
        }
    }

    /** Ajustes, que suma la sesión chica de la información de una app ([AdminSession]). */
    const val SETTINGS = "com.android.settings"

    /** Las pantallas de accesibilidad de Samsung, donde se prende y se apaga [PageGuard]. */
    private const val SAMSUNG_ACCESSIBILITY = "com.samsung.accessibility"

    /** El mantenimiento del dispositivo de Samsung: duerme apps y fuerza su detención, como Ajustes. */
    private const val SAMSUNG_DEVICE_CARE = "com.samsung.android.lool"

    /**
     * Lo que la guardia deja pasar en la sesión chica: Ajustes y, en Samsung,
     * la app de sus pantallas de accesibilidad, por si Ajustes le pasa la de
     * prender el bloqueo de páginas ([PageGuard]).
     */
    private val INFO_SCREENS = setOf(SETTINGS, SAMSUNG_ACCESSIBILITY)

    /**
     * Lo que no se abre nunca con Slowtown activo, aunque no esté entre las
     * bloqueadas: las puertas que cierra la contraseña. Ajustes y la tienda de
     * Samsung ([AppCatalog.ADMIN_ONLY]), lo que hace de Ajustes (la
     * accesibilidad y el mantenimiento de Samsung), el instalador de Android
     * (pasa sólo cuando lo abre Slowtown, [Guard.allowInstaller]) y el nivel 2
     * ([Vetoed]: las apps de [Fixed.APPS] y los navegadores que no son Chrome;
     * con Device Owner, además, las pausa Android). Con la lista de permitidas
     * esto quedaba cerrado solo, por no estar permitido. Lo que no puede faltar
     * ([always]) gana. Leerlo pregunta varias cosas al sistema: se guarda un
     * minuto, o hasta que se instala algo ([forget]).
     */
    fun alwaysBlocked(context: Context): Set<String> {
        val now = SystemClock.elapsedRealtime()
        cachedBlocked?.let { (at, packages) -> if (now - at < ALWAYS_TTL_MS) return packages }
        val generation = this.generation
        val packages = buildSet {
            addAll(AppCatalog.ADMIN_ONLY)
            add(SAMSUNG_ACCESSIBILITY)
            add(SAMSUNG_DEVICE_CARE)
            addAll(Guard.installers(context))
            addAll(Vetoed.targets(context))
            addAll(unlisted(context))
        } - always(context)
        // Si mientras tanto se instaló algo, esto ya es viejo: no se guarda.
        if (generation == this.generation) cachedBlocked = now to packages
        return packages
    }

    /**
     * Lo de terceros que no aparece para elegir: lo que no tiene ícono (una
     * app que se esconde no se podría bloquear) y los otros Inicios (desde uno
     * se pide ser el Inicio, y sin serlo Slowtown la guardia se apaga). Con la
     * lista de permitidas quedaba cerrado solo; lo de sistema sin ícono (los
     * diálogos de Bluetooth, de la VPN) se abre como siempre.
     */
    // Las variantes con *Flags recién existen en API 33.
    @Suppress("DEPRECATION")
    private fun unlisted(context: Context): Set<String> {
        val pm = context.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val withIcon = pm.queryIntentActivities(launcher, 0).mapTo(mutableSetOf()) { it.activityInfo.packageName }
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val homes = pm.queryIntentActivities(home, PackageManager.MATCH_ALL).mapTo(mutableSetOf()) { it.activityInfo.packageName }
        return pm.getInstalledApplications(0)
            .filter { it.flags and ApplicationInfo.FLAG_SYSTEM == 0 && it.packageName != context.packageName }
            .mapNotNullTo(mutableSetOf()) { app -> app.packageName.takeIf { it !in withIcon || it in homes } }
    }

    /** Se instaló o cambió una app: lo guardado de [always] y [alwaysBlocked] ya no vale. */
    fun forget() {
        generation++
        cached = null
        cachedBlocked = null
    }

    /** Cuántas veces se olvidó lo guardado: lo que se calculó antes no se guarda ([forget]). */
    @Volatile
    private var generation = 0

    /**
     * Slowtown y lo que no puede faltar, de día y de noche: lo esencial
     * (llamadas, SMS, emergencias, las pantallas de sistema que abren las
     * apps), el reloj y las alarmas (la de la mañana tiene que poder mostrar su
     * pantalla) y Bienestar digital, que se abre tocando el tiempo de pantalla:
     * muestra el uso y suma límites, no afloja nada. Leerlo pregunta varias
     * cosas al sistema, y la guardia lo mira a cada rato: se guarda un minuto.
     */
    fun always(context: Context): Set<String> {
        val now = SystemClock.elapsedRealtime()
        cached?.let { (at, packages) -> if (now - at < ALWAYS_TTL_MS) return packages }
        val generation = this.generation
        val packages = buildSet {
            add(context.packageName)
            addAll(Essentials.packages(context))
            addAll(Bedtime.apps(context))
            addAll(ScreenTime.WELLBEING)
        }
        if (generation == this.generation) cached = now to packages
        return packages
    }

    @Volatile
    private var cached: Pair<Long, Set<String>>? = null

    @Volatile
    private var cachedBlocked: Pair<Long, Set<String>>? = null
    private const val ALWAYS_TTL_MS = 60_000L
}
