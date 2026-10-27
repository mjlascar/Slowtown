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
import java.time.Instant
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
    fun today(context: Context, now: Long = System.currentTimeMillis()): Long? =
        byApp(context, now)?.values?.sum()?.coerceIn(0L, now - midnight(now))

    /**
     * Lo mismo, por app: para los límites de cada una ([com.slowtown.launcher.admin.Limits]).
     * Null sin acceso. Fuera del hilo principal.
     */
    fun byApp(context: Context, now: Long = System.currentTimeMillis()): Map<String, Long>? = runCatching {
        if (!hasAccess(context)) return null
        val midnight = midnight(now)
        val query = context.getSystemService(UsageStatsManager::class.java).queryEvents(midnight - LOOK_BEHIND, now)
        val events = buildList {
            val event = UsageEvents.Event()
            while (query.hasNextEvent()) {
                query.getNextEvent(event)
                add(Event(event.timeStamp, event.eventType, event.packageName, event.className))
            }
        }
        val interactive = context.getSystemService(PowerManager::class.java).isInteractive
        foreground(events, midnight, now, excluded(context), interactive)
    }.getOrNull()

    private fun midnight(now: Long): Long {
        val zone = ZoneId.systemDefault()
        return Instant.ofEpochMilli(now).atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()
    }

    /** Bienestar digital: el de Samsung y el de Google. Con Slowtown activo se abre siempre (ver Policies). */
    val WELLBEING = listOf("com.samsung.android.forest", "com.google.android.apps.wellbeing")

    /**
     * Bienestar digital, para ver el detalle de hoy por app: lo abre tocar el
     * tiempo de pantalla de Inicio. El de Samsung no tiene ícono (se entra
     * desde Ajustes): sin pantalla de inicio de la app, va la primera
     * principal que se pueda abrir. Null si el teléfono no tiene ninguno.
     */
    // La variante con ResolveInfoFlags recién existe en API 33.
    @Suppress("DEPRECATION")
    fun wellbeing(context: Context): Intent? {
        val pm = context.packageManager
        for (pkg in WELLBEING) {
            pm.getLaunchIntentForPackage(pkg)?.let { return it }
            val main = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).setPackage(pkg), 0)
                .firstOrNull { it.activityInfo.exported } ?: continue
            return Intent(Intent.ACTION_MAIN).setClassName(pkg, main.activityInfo.name)
        }
        return null
    }

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

    internal data class Event(val time: Long, val type: Int, val packageName: String?, val className: String? = null)

    /**
     * El tiempo adelante de cada app entre [start] y [end]. Una pantalla
     * empieza con RESUMED y termina con su PAUSED; apagar la pantalla, la
     * pantalla de bloqueo o apagar el teléfono terminan lo que esté abierto. Lo
     * que sigue abierto al final cuenta hasta [end] sólo con la pantalla prendida.
     *
     * STOPPED no cuenta, y PAUSED sólo si es de la pantalla que está adelante:
     * al pasar de una pantalla a otra de la misma app (en WhatsApp, de la lista
     * de chats a un chat), Android avisa que se paró la vieja un instante
     * después de que arrancó la nueva. Tomarlo como el final dejaba sin contar
     * todo el rato en el chat: Inicio decía 22 min con 54 en Bienestar digital.
     */
    internal fun foreground(
        events: List<Event>,
        start: Long,
        end: Long,
        excluded: Set<String>,
        openAtEnd: Boolean,
    ): Map<String, Long> {
        var current: String? = null
        var currentClass: String? = null
        var since = 0L
        val total = mutableMapOf<String, Long>()
        fun close(at: Long) {
            val app = current ?: return
            val span = (minOf(at, end) - maxOf(since, start)).coerceAtLeast(0L)
            if (app !in excluded && span > 0) total[app] = (total[app] ?: 0L) + span
            current = null
            currentClass = null
        }
        for (event in events.sortedBy { it.time }) {
            when (event.type) {
                UsageEvents.Event.ACTIVITY_RESUMED -> {
                    close(event.time)
                    current = event.packageName
                    currentClass = event.className
                    since = event.time
                }
                UsageEvents.Event.ACTIVITY_PAUSED -> {
                    val same = event.className == null || currentClass == null || event.className == currentClass
                    if (event.packageName == current && same) close(event.time)
                }
                UsageEvents.Event.SCREEN_NON_INTERACTIVE,
                UsageEvents.Event.KEYGUARD_SHOWN,
                UsageEvents.Event.DEVICE_SHUTDOWN -> close(event.time)
            }
        }
        if (openAtEnd) close(end)
        return total
    }

    /**
     * Lo que no es «usar el teléfono»: Slowtown, los launchers y la barra del
     * sistema. Ajustes no, aunque también se anuncia como Inicio: es la
     * pantalla de respaldo del arranque, con prioridad negativa.
     */
    private fun excluded(context: Context): Set<String> = buildSet {
        add(context.packageName)
        add("com.android.systemui")
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        context.packageManager.queryIntentActivities(home, PackageManager.MATCH_ALL)
            .filter { it.priority >= 0 }
            .mapTo(this) { it.activityInfo.packageName }
    }
}
