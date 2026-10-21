package com.slowtown.launcher.apps

import android.Manifest
import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.PowerManager
import android.os.Process
import java.time.LocalDate
import java.time.ZoneId

/**
 * El tiempo de pantalla de hoy, el mismo número que muestra Bienestar digital.
 * Bienestar digital no se lo comparte a otras apps, así que se calcula igual
 * que él, con los datos de uso de Android: cuánto estuvo adelante cada app
 * desde la medianoche. Inicio (Slowtown y cualquier otro launcher) y la barra
 * del sistema no cuentan: mirar Inicio no es usar el teléfono.
 *
 * Necesita el acceso a los datos de uso, que da `provisionar.sh` por adb: con
 * Slowtown activo, la pantalla de Ajustes para darlo no se alcanza.
 */
object ScreenTime {

    // Desde un rato antes de la medianoche, para saber qué app estaba adelante
    // a las 0:00 (no tiene un «empezó» dentro del día).
    private const val LOOK_BEHIND = 12 * 60 * 60_000L

    fun hasAccess(context: Context): Boolean {
        val mode = context.getSystemService(AppOpsManager::class.java)
            .unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        return when (mode) {
            AppOpsManager.MODE_ALLOWED -> true
            AppOpsManager.MODE_DEFAULT ->
                context.checkSelfPermission(Manifest.permission.PACKAGE_USAGE_STATS) == PackageManager.PERMISSION_GRANTED
            else -> false
        }
    }

    /** Milisegundos de pantalla de hoy, o null sin acceso. Recorre los eventos del día: fuera del hilo principal. */
    fun today(context: Context, now: Long = System.currentTimeMillis()): Long? = runCatching {
        if (!hasAccess(context)) return null
        val zone = ZoneId.systemDefault()
        val midnight = LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()
        val query = context.getSystemService(UsageStatsManager::class.java).queryEvents(midnight - LOOK_BEHIND, now)
        val events = buildList {
            val event = UsageEvents.Event()
            while (query.hasNextEvent()) {
                query.getNextEvent(event)
                add(Event(event.timeStamp, event.eventType, event.packageName))
            }
        }
        val interactive = context.getSystemService(PowerManager::class.java).isInteractive
        foreground(events, midnight, now, excluded(context), interactive).coerceIn(0L, now - midnight)
    }.getOrNull()

    /** «1 h 5 min», «2 h», «45 min» o «0 min»: corto, va arriba a la derecha de Inicio. */
    fun format(millis: Long): String {
        val minutes = millis / 60_000L
        val hours = minutes / 60
        val rest = minutes % 60
        return when {
            minutes < 1 -> "0 min"
            hours == 0L -> "$rest min"
            rest == 0L -> "$hours h"
            else -> "$hours h $rest min"
        }
    }

    internal data class Event(val time: Long, val type: Int, val packageName: String?)

    /**
     * Suma el tiempo adelante de cada app entre [start] y [end]. Una app empieza
     * con RESUMED y termina con su PAUSED o STOPPED; apagar la pantalla, la
     * pantalla de bloqueo o apagar el teléfono terminan lo que esté abierto. Lo
     * que sigue abierto al final cuenta hasta [end] sólo con la pantalla prendida.
     */
    internal fun foreground(
        events: List<Event>,
        start: Long,
        end: Long,
        excluded: Set<String>,
        openAtEnd: Boolean,
    ): Long {
        var current: String? = null
        var since = 0L
        var total = 0L
        fun close(at: Long) {
            val app = current ?: return
            if (app !in excluded) total += (minOf(at, end) - maxOf(since, start)).coerceAtLeast(0L)
            current = null
        }
        for (event in events.sortedBy { it.time }) {
            when (event.type) {
                UsageEvents.Event.ACTIVITY_RESUMED -> {
                    close(event.time)
                    current = event.packageName
                    since = event.time
                }
                UsageEvents.Event.ACTIVITY_PAUSED, UsageEvents.Event.ACTIVITY_STOPPED ->
                    if (event.packageName == current) close(event.time)
                UsageEvents.Event.SCREEN_NON_INTERACTIVE,
                UsageEvents.Event.KEYGUARD_SHOWN,
                UsageEvents.Event.DEVICE_SHUTDOWN -> close(event.time)
            }
        }
        if (openAtEnd) close(end)
        return total
    }

    /** Lo que no es «usar el teléfono»: Slowtown, los launchers y la barra del sistema. */
    private fun excluded(context: Context): Set<String> = buildSet {
        add(context.packageName)
        add("com.android.systemui")
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        context.packageManager.queryIntentActivities(home, PackageManager.MATCH_ALL)
            .mapTo(this) { it.activityInfo.packageName }
    }
}
