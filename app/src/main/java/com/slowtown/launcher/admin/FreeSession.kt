package com.slowtown.launcher.admin

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.slowtown.launcher.Prefs
import com.slowtown.launcher.security.CrashGuard
import kotlin.concurrent.thread

/**
 * «Habilitar todo por 5 minutos»: sin contraseña, pero [USES] veces cada
 * [WINDOW_MS] como mucho. Nació para actualizar Waltra, que baja su APK de
 * GitHub y necesita el instalador de Android, que con Slowtown activo queda
 * bloqueado.
 *
 * A diferencia de la sesión de administración, no se corta al volver a
 * Inicio ni al apagar la pantalla: sólo cuando pasan los 5 minutos (hay que
 * poder pasar de Waltra al navegador y al instalador). El final lo agenda el
 * sistema, así que llega aunque Slowtown no esté corriendo; y un temporizador
 * en el proceso lo hace llegar puntual, porque la alarma puede atrasarse.
 *
 * Las veces se cuentan con la hora del teléfono; por eso Slowtown activo no
 * deja cambiarla a mano ([Policies]).
 */
object FreeSession {
    const val LENGTH_MS = 5 * 60_000L
    const val USES = 2
    const val WINDOW_MS = 12 * 60 * 60_000L

    fun isActive(context: Context): Boolean = Prefs(context).freeSessionUntil > System.currentTimeMillis()

    /** Cuándo termina la que está en curso; 0 si no hay. */
    fun until(context: Context): Long = Prefs(context).freeSessionUntil.takeIf { it > System.currentTimeMillis() } ?: 0L

    /** Cuántas quedan en las últimas 12 horas. */
    fun remaining(context: Context): Int = (USES - recentUses(context).size).coerceAtLeast(0)

    /** Si no quedan, cuándo vuelve a haber una. */
    fun nextAt(context: Context): Long? =
        recentUses(context).minOrNull()?.plus(WINDOW_MS)?.takeIf { remaining(context) == 0 }

    /**
     * Arranca, si quedan: anota la vez, habilita todo y agenda el final.
     * Hace consultas al sistema: fuera del hilo principal.
     */
    fun start(context: Context, allowed: Collection<String>): Boolean {
        val prefs = Prefs(context)
        val now = System.currentTimeMillis()
        val uses = recentUses(context)
        if (uses.size >= USES) return false
        prefs.freeSessionUses = (uses + now).joinToString(",")
        prefs.freeSessionUntil = now + LENGTH_MS
        Policies.openForAdmin(context, allowed)
        val app = context.applicationContext
        context.getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + LENGTH_MS,
            endIntent(app),
        )
        Handler(Looper.getMainLooper()).postDelayed({ thread(name = "fin-de-todo") { end(app) } }, LENGTH_MS)
        return true
    }

    /** Si ya pasó la hora y nadie la cerró (la alarma se atrasó), la cierra. */
    fun endIfExpired(context: Context) {
        val until = Prefs(context).freeSessionUntil
        if (until != 0L && until <= System.currentTimeMillis()) end(context)
    }

    /**
     * Vuelve a la lista de siempre, y el sistema cierra lo que quedó afuera.
     * Hace consultas al sistema: fuera del hilo principal.
     */
    fun end(context: Context) {
        val prefs = Prefs(context)
        if (prefs.freeSessionUntil == 0L) return
        prefs.freeSessionUntil = 0L
        context.getSystemService(AlarmManager::class.java).cancel(endIntent(context))
        if (prefs.closed && !CrashGuard.inSafeMode(context) && Policies.isDeviceOwner(context)) {
            Policies.apply(context, prefs.allowedPackages)
        }
    }

    private fun recentUses(context: Context): List<Long> {
        val now = System.currentTimeMillis()
        return Prefs(context).freeSessionUses.split(',')
            .mapNotNull { it.toLongOrNull() }
            // Una vez «del futuro» (la hora estaba adelantada) también cuenta.
            .filter { now - it < WINDOW_MS }
    }

    private fun endIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        1,
        Intent(context, End::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** El final agendado: llega aunque Slowtown no esté corriendo. */
    class End : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val pending = goAsync()
            val app = context.applicationContext
            thread(name = "fin-de-todo") {
                try {
                    end(app)
                } finally {
                    pending.finish()
                }
            }
        }
    }
}
