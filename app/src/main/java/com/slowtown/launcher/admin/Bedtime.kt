package com.slowtown.launcher.admin

import android.app.AlarmManager
import android.app.KeyguardManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.AlarmClock
import android.util.Log
import com.slowtown.launcher.Prefs
import java.time.DayOfWeek
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
 *
 * La noche no corta llamadas ([CallGuard]): mientras hay una, sonando o en
 * curso, también de WhatsApp, la noche espera; al cortar, la lista se achica.
 *
 * La ráfaga y los horarios son también los de Flow State ([FlowState]): una
 * ráfaga suelta las dos cosas, y [Tick] agenda los bordes de las dos (y la
 * medianoche, para los límites por app).
 */
object Bedtime {
    const val BURST_MS = 5 * 60_000L

    private const val TAG = "Slowtown"
    private const val EXTRA_BURST_END = "fin_de_rafaga"
    private const val EXTRA_CALL_CHECK = "llamada"
    private const val EXTRA_LIMIT_CHECK = "limite"

    /** Cada cuánto se vuelve a mirar si sigue la llamada que posterga la noche. */
    private const val CALL_CHECK_MS = 60_000L

    /** El margen antes de achicar la lista cuando termina una llamada ([callChanged]). */
    private const val CALL_SETTLE_MS = 5_000L

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
        val start = prefs.bedtimeStart
        val end = prefs.bedtimeEnd
        return prefs.bedtimeEnabled &&
            inWindow(now.hour * 60 + now.minute, start, end) &&
            nightOf(now, start, end) in prefs.bedtimeDays
    }

    /**
     * De qué día es la noche que está corriendo en [now] (sólo tiene sentido
     * adentro de la ventana): del día en que empieza, como se dice, así que la
     * del viernes va de viernes a sábado. Una que empieza de madrugada (de 1 a
     * 7) es de la noche anterior.
     */
    internal fun nightOf(now: LocalDateTime, start: Int, end: Int): DayOfWeek {
        val minute = now.hour * 60 + now.minute
        val fromYesterday = if (start > end) minute < end else start < 12 * 60
        return (if (fromYesterday) now.toLocalDate().minusDays(1) else now.toLocalDate()).dayOfWeek
    }

    fun burstUntil(context: Context): Long =
        Prefs(context).bedtimeBurstUntil.takeIf { it > System.currentTimeMillis() } ?: 0L

    /** Si ahora manda la noche: es de noche y no hay una ráfaga en curso. */
    fun restricts(context: Context): Boolean = isNight(context) && burstUntil(context) == 0L

    /**
     * Si el teléfono se está usando: pantalla prendida y desbloqueado. La
     * noche rige sólo así ([Policies]). Con la pantalla apagada o en la de
     * bloqueo no hay nada que frenar, y es justo cuando Android abre la
     * pantalla de una llamada que entra (la de WhatsApp), antes de que
     * Slowtown alcance a sumar la app: con la lista de la noche, la bloquearía.
     * Al desbloquear se achica ([com.slowtown.launcher.SlowtownApp]). Ante la
     * duda, se está usando: rige.
     */
    fun inUse(context: Context): Boolean = runCatching {
        val power = context.getSystemService(PowerManager::class.java)
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        power.isInteractive && !keyguard.isKeyguardLocked
    }.getOrDefault(true)

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
     * Cinco minutos con las apps de siempre, de noche o en Flow State. Sin
     * contraseña y sin tope: la fricción es tener que volver a pedirla. Fuera
     * del hilo principal.
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

    /**
     * Pone al día lo que se puede abrir, si Slowtown está activo: los límites
     * por app (a la medianoche vuelven las que habían llegado), la lista de
     * lock task con Device Owner (escribe sólo si cambió) y, sin cable, la
     * guardia, que mira ya lo que está adelante. Si una llamada posterga la
     * noche, se vuelve a mirar cada minuto mientras dure: el corte avisa por
     * el modo de audio y por el lector de notificaciones, y esto es por si se
     * pierden los dos.
     */
    fun refresh(context: Context) {
        val mode = Rules.mode(context)
        if (mode == Rules.Mode.PAUSED) return
        val app = context.applicationContext
        val prefs = Prefs(app)
        if (prefs.appLimits.isNotEmpty()) Limits.check(app)
        if (mode == Rules.Mode.LOCK_TASK) {
            Policies.refreshPackages(app, prefs.allowedPackages)
            val am = app.getSystemService(AlarmManager::class.java)
            if (restricts(app) && CallGuard.inCall(app)) {
                am.setAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + CALL_CHECK_MS,
                    callCheckIntent(app),
                )
            } else {
                am.cancel(callCheckIntent(app))
            }
        }
        Guard.update(app)
    }

    /**
     * Cuándo se le acaba el tiempo a la app con límite que está adelante: lo
     * mira la guardia, y esto es por si Slowtown no sigue corriendo.
     */
    fun scheduleLimitCheck(context: Context, delayMs: Long) {
        val app = context.applicationContext
        val am = app.getSystemService(AlarmManager::class.java)
        val at = SystemClock.elapsedRealtime() + delayMs + LIMIT_MARGIN_MS
        if (am.canScheduleExactAlarms()) {
            am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, limitCheckIntent(app))
        } else {
            am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, limitCheckIntent(app))
        }
    }

    /** Un poco después del límite: los datos de uso de Android se anotan con un instante de atraso. */
    private const val LIMIT_MARGIN_MS = 2_000L

    /**
     * Empezó o terminó una llamada ([CallGuard]). Cambia algo sólo de noche o
     * con apps frenadas (Flow State, un límite): si no, las apps de la llamada
     * ya están en la lista. Al terminar, con
     * unos segundos de margen: al atender, algunas apps cambian la
     * notificación que suena por la de la llamada en curso, o pasan un
     * instante por el audio normal, y en el medio no se ve ninguna llamada.
     * Achicar ahí cerraría la pantalla recién abierta. Espera ese rato: fuera
     * del hilo principal.
     */
    fun callChanged(context: Context, ended: Boolean) {
        if (!isNight(context) && FlowState.held(context).isEmpty() && Limits.reached(context).isEmpty()) return
        if (ended) Thread.sleep(CALL_SETTLE_MS)
        refresh(context)
    }

    /**
     * Agenda el próximo cambio de los horarios: que empiece o termine la noche
     * o un horario de Flow State, o la medianoche si hay límites por app (ahí
     * vuelven las que llegaron al suyo). Exacta si Android lo deja
     * (provisionar.sh da el permiso), si no, aproximada.
     */
    fun schedule(context: Context) {
        val app = context.applicationContext
        val am = app.getSystemService(AlarmManager::class.java)
        val intent = tickIntent(app)
        val at = nextTick(app, LocalDateTime.now())
        if (at == null) {
            am.cancel(intent)
            return
        }
        val millis = at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        if (am.canScheduleExactAlarms()) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, intent)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, intent)
        }
    }

    /** Lo que agenda [schedule], o null si no hay nada que agendar. */
    internal fun nextTick(context: Context, now: LocalDateTime): LocalDateTime? {
        val prefs = Prefs(context)
        return listOfNotNull(
            nextBoundary(now, prefs.bedtimeStart, prefs.bedtimeEnd)
                .takeIf { prefs.bedtimeEnabled && prefs.bedtimeStart != prefs.bedtimeEnd },
            FlowState.nextBoundary(prefs.flowWindows, now).takeIf { prefs.flowEnabled },
            now.toLocalDate().plusDays(1).atStartOfDay().takeIf { prefs.appLimits.isNotEmpty() },
        ).minOrNull()
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

    private fun callCheckIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        4,
        Intent(context, Tick::class.java).putExtra(EXTRA_CALL_CHECK, true),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun limitCheckIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        5,
        Intent(context, Tick::class.java).putExtra(EXTRA_LIMIT_CHECK, true),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /**
     * Los horarios: empieza o termina la noche o Flow State, es medianoche,
     * termina una ráfaga, toca mirar si sigue una llamada o se le acaba el
     * tiempo a una app con límite. Llega aunque Slowtown no esté corriendo.
     */
    class Tick : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val pending = goAsync()
            val app = context.applicationContext
            val burstEnd = intent.getBooleanExtra(EXTRA_BURST_END, false)
            val check = intent.getBooleanExtra(EXTRA_CALL_CHECK, false) || intent.getBooleanExtra(EXTRA_LIMIT_CHECK, false)
            thread(name = "hora-de-dormir") {
                try {
                    runCatching {
                        when {
                            burstEnd -> endBurst(app)
                            // Si la llamada sigue, refresh agenda la próxima mirada.
                            check -> refresh(app)
                            else -> {
                                endBurstIfExpired(app)
                                refresh(app)
                                schedule(app)
                            }
                        }
                    }.onFailure { Log.e(TAG, "La hora de dormir falló", it) }
                } finally {
                    pending.finish()
                }
            }
        }
    }
}
