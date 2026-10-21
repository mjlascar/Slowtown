package com.slowtown.launcher.admin

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.AlarmClock
import android.util.Log
import com.slowtown.launcher.Prefs
import com.slowtown.launcher.security.CrashGuard
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.concurrent.thread

/**
 * La hora de dormir: entre [Prefs.bedtimeStart] y [Prefs.bedtimeEnd], con
 * Slowtown activo, sólo se abren el reloj y las alarmas, además de lo de
 * siempre ([Essentials]: llamadas, SMS, emergencias). Para usar el teléfono,
 * ráfagas de [BURST_MS] sin contraseña desde Inicio: vuelven las apps
 * permitidas, y al terminar el sistema cierra lo que quedó afuera. Lo pidió
 * Marcos para dejar de usar el teléfono tan tarde.
 *
 * El principio y el final de la noche los agenda el sistema, así que llegan
 * aunque haya otra app abierta: la lista de lock task se achica y Android
 * cierra lo demás. Las sesiones (administración, «Habilitar todo») siguen
 * abriendo todo, y al terminar vuelve la lista de la noche.
 */
object Bedtime {
    const val BURST_MS = 5 * 60_000L

    private const val TAG = "Slowtown"
    private const val EXTRA_BURST_END = "fin_de_rafaga"

    /**
     * Alarmas que no siempre se anuncian como reloj: Alarmy, con la que Marcos
     * se despierta, y los relojes de fábrica.
     */
    private val KNOWN = setOf(
        "droom.sleepIfUCan",
        "com.sec.android.app.clockpackage",
        "com.google.android.deskclock",
        "com.android.deskclock",
    )

    /** Si [minute] (del día) cae en la noche que va de [start] a [end], que puede cruzar la medianoche. */
    fun inWindow(minute: Int, start: Int, end: Int): Boolean = when {
        start == end -> false
        start < end -> minute in start until end
        else -> minute >= start || minute < end
    }

    fun isNight(context: Context, now: LocalDateTime = LocalDateTime.now()): Boolean {
        val prefs = Prefs(context)
        return prefs.bedtimeEnabled && inWindow(now.hour * 60 + now.minute, prefs.bedtimeStart, prefs.bedtimeEnd)
    }

    fun burstUntil(context: Context): Long =
        Prefs(context).bedtimeBurstUntil.takeIf { it > System.currentTimeMillis() } ?: 0L

    /** Si ahora manda la noche: es de noche y no hay una ráfaga en curso. */
    fun restricts(context: Context): Boolean = isNight(context) && burstUntil(context) == 0L

    /**
     * El reloj y las alarmas instalados. Van en la lista siempre, de día
     * también: si no, con Slowtown activo la alarma de la mañana no podría
     * mostrar su pantalla.
     */
    fun apps(context: Context): Set<String> = buildSet {
        val pm = context.packageManager
        for (action in listOf(AlarmClock.ACTION_SHOW_ALARMS, AlarmClock.ACTION_SET_ALARM)) {
            pm.queryIntentActivities(Intent(action), PackageManager.MATCH_ALL)
                .mapTo(this) { it.activityInfo.packageName }
        }
        KNOWN.filterTo(this) { runCatching { pm.getApplicationInfo(it, 0) }.isSuccess }
    }

    /**
     * Cinco minutos con las apps de siempre. Sin contraseña y sin tope: la
     * fricción es tener que volver a pedirla. Fuera del hilo principal.
     */
    fun startBurst(context: Context) {
        val app = context.applicationContext
        Prefs(app).bedtimeBurstUntil = System.currentTimeMillis() + BURST_MS
        refresh(app)
        context.getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + BURST_MS,
            burstEndIntent(app),
        )
        // La alarma puede atrasarse; esto la hace puntual si Slowtown sigue vivo.
        Handler(Looper.getMainLooper()).postDelayed({ thread(name = "fin-de-rafaga") { endBurst(app) } }, BURST_MS)
    }

    /** Vuelve la lista de la noche. Fuera del hilo principal. */
    fun endBurst(context: Context) {
        val prefs = Prefs(context)
        if (prefs.bedtimeBurstUntil == 0L) return
        prefs.bedtimeBurstUntil = 0L
        context.getSystemService(AlarmManager::class.java).cancel(burstEndIntent(context))
        refresh(context)
    }

    fun endBurstIfExpired(context: Context) {
        val until = Prefs(context).bedtimeBurstUntil
        if (until != 0L && until <= System.currentTimeMillis()) endBurst(context)
    }

    /** Rearma la lista de lock task según la hora, si Slowtown está activo. Escribe sólo si cambió. */
    fun refresh(context: Context) {
        val prefs = Prefs(context)
        if (prefs.closed && !CrashGuard.inSafeMode(context) && Policies.isDeviceOwner(context)) {
            Policies.refreshPackages(context, prefs.allowedPackages)
        }
    }

    /**
     * Agenda el próximo cambio (que empiece o que termine la noche). Exacta si
     * Android lo deja (provisionar.sh da el permiso), si no, aproximada.
     */
    fun schedule(context: Context) {
        val app = context.applicationContext
        val am = app.getSystemService(AlarmManager::class.java)
        val prefs = Prefs(app)
        val intent = tickIntent(app)
        if (!prefs.bedtimeEnabled || prefs.bedtimeStart == prefs.bedtimeEnd) {
            am.cancel(intent)
            return
        }
        val at = nextBoundary(LocalDateTime.now(), prefs.bedtimeStart, prefs.bedtimeEnd)
        val millis = at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        if (am.canScheduleExactAlarms()) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, intent)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, intent)
        }
    }

    /** El primer momento después de [now] en que empieza o termina la noche. */
    internal fun nextBoundary(now: LocalDateTime, start: Int, end: Int): LocalDateTime {
        val today = now.toLocalDate().atStartOfDay()
        return listOf(start, end)
            .flatMap { listOf(today.plusMinutes(it.toLong()), today.plusDays(1).plusMinutes(it.toLong())) }
            .filter { it.isAfter(now) }
            .minOrNull() ?: now.plusDays(1)
    }

    private fun tickIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        2,
        Intent(context, Tick::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun burstEndIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        3,
        Intent(context, Tick::class.java).putExtra(EXTRA_BURST_END, true),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** Empieza o termina la noche, o termina una ráfaga: llega aunque Slowtown no esté corriendo. */
    class Tick : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val pending = goAsync()
            val app = context.applicationContext
            val burstEnd = intent.getBooleanExtra(EXTRA_BURST_END, false)
            thread(name = "hora-de-dormir") {
                try {
                    runCatching {
                        if (burstEnd) {
                            endBurst(app)
                        } else {
                            endBurstIfExpired(app)
                            refresh(app)
                            schedule(app)
                        }
                    }.onFailure { Log.e(TAG, "La hora de dormir falló", it) }
                } finally {
                    pending.finish()
                }
            }
        }
    }
}
