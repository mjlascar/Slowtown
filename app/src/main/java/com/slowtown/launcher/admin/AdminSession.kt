package com.slowtown.launcher.admin

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import com.slowtown.launcher.Prefs
import com.slowtown.launcher.security.CrashGuard
import kotlin.concurrent.thread

/**
 * La sesión de administración: Ajustes o la Play Store abiertos desde la
 * administración, con todo el teléfono a mano hasta volver a Inicio, apagar la
 * pantalla o pasar [LENGTH_MS].
 *
 * El estado vive en disco y el final está agendado en el sistema, no en la
 * pantalla de inicio: si Android mata a Slowtown mientras se usa Ajustes, la
 * sesión igual termina a tiempo.
 */
object AdminSession {
    const val LENGTH_MS = 10 * 60_000L

    fun isActive(context: Context): Boolean = Prefs(context).adminSessionUntil > System.currentTimeMillis()

    /** Si ya pasó la hora y nadie la cerró (la alarma se atrasó), la cierra. */
    fun endIfExpired(context: Context) {
        val until = Prefs(context).adminSessionUntil
        if (until != 0L && until <= System.currentTimeMillis()) end(context)
    }

    /** Agranda la lista y agenda el final. Hace consultas al sistema: fuera del hilo principal. */
    fun start(context: Context, allowed: Collection<String>) {
        Policies.openForAdmin(context, allowed)
        Prefs(context).adminSessionUntil = System.currentTimeMillis() + LENGTH_MS
        context.getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + LENGTH_MS,
            endIntent(context),
        )
    }

    /**
     * Vuelve a la lista de siempre, y el sistema cierra lo que quedó afuera.
     * Hace consultas al sistema: fuera del hilo principal.
     */
    fun end(context: Context) {
        val prefs = Prefs(context)
        if (prefs.adminSessionUntil == 0L) return
        prefs.adminSessionUntil = 0L
        context.getSystemService(AlarmManager::class.java).cancel(endIntent(context))
        if (prefs.closed && !CrashGuard.inSafeMode(context) && Policies.isDeviceOwner(context)) {
            Policies.apply(context, prefs.allowedPackages)
        }
    }

    private fun endIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        0,
        Intent(context, End::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** El final agendado: llega aunque Slowtown no esté corriendo. */
    class End : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val pending = goAsync()
            val app = context.applicationContext
            thread(name = "fin-de-sesion") {
                try {
                    end(app)
                } finally {
                    pending.finish()
                }
            }
        }
    }
}
