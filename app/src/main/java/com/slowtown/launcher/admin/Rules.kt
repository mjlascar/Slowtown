package com.slowtown.launcher.admin

import android.content.Context
import android.os.SystemClock
import com.slowtown.launcher.Prefs
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
 * - las permitidas, más lo que no puede faltar ([always]);
 * - de noche, sólo lo que no puede faltar ([Bedtime]);
 * - en Flow State, sin sus apps ([FlowState]);
 * - sin las que llegaron a su límite del día ([Limits]);
 * - la app de una llamada, siempre ([CallGuard]);
 * - con una sesión (administración o «Habilitar todo»), todo.
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
        /** No está entre las permitidas. */
        data object NotAllowed : Hold

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
     * Lo que se puede abrir ahora sin contar las sesiones: lo que no puede
     * faltar, las permitidas ([packages]) que no estén frenadas (de noche,
     * ninguna) y la app de una llamada.
     */
    fun allowed(context: Context, packages: Collection<String>): Set<String> = buildSet {
        addAll(always(context))
        if (!night(context)) addAll(packages - held(context))
        addAll(CallGuard.packages(context))
    }

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
            pkg !in prefs.allowedPackages -> Hold.NotAllowed
            night(context) -> Hold.Night
            pkg in FlowState.held(context) -> Hold.Flow(FlowState.endsAt(context))
            pkg in Limits.reached(context) -> Hold.Limit(prefs.appLimits[pkg] ?: 0)
            else -> null
        }
    }

    /** Ajustes, que suma la sesión chica de la información de una app ([AdminSession]). */
    const val SETTINGS = "com.android.settings"

    /**
     * Lo que la guardia deja pasar en la sesión chica: Ajustes y, en Samsung,
     * la app de sus pantallas de accesibilidad, por si Ajustes le pasa la de
     * prender el bloqueo de páginas ([PageGuard]).
     */
    private val INFO_SCREENS = setOf(SETTINGS, "com.samsung.accessibility")

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
        val packages = buildSet {
            add(context.packageName)
            addAll(Essentials.packages(context))
            addAll(Bedtime.apps(context))
            addAll(ScreenTime.WELLBEING)
        }
        cached = now to packages
        return packages
    }

    @Volatile
    private var cached: Pair<Long, Set<String>>? = null
    private const val ALWAYS_TTL_MS = 60_000L
}
