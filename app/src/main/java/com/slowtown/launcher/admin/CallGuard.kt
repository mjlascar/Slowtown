package com.slowtown.launcher.admin

import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import android.os.SystemClock
import android.service.notification.StatusBarNotification
import android.util.Log
import com.slowtown.launcher.notif.WavesListener
import kotlin.concurrent.thread

/**
 * La hora de dormir no corta llamadas. Las del teléfono pasan siempre
 * ([Essentials]), pero las de WhatsApp son de una app: de noche quedaría
 * afuera de la lista de lock task, y Android cerraría la llamada en curso o
 * no dejaría abrir su pantalla para atender.
 *
 * Mientras hay una llamada, sonando o en curso, la noche espera y vuelve la
 * lista de siempre ([Policies]); al cortar, se achica. Se ve por dos lados: el
 * modo de audio (Android lo pone en llamada, y las apps de llamadas por
 * internet en comunicación) y las notificaciones de llamada que pasan por el
 * lector ([WavesListener]), porque mientras suena una de WhatsApp el audio
 * todavía no cambió.
 *
 * No cuentan las apps bloqueadas ([Rules.blocked]): una que Marcos bloqueó no
 * entra por una llamada. Corre adentro del lector y del Inicio: nada de acá
 * tira una excepción.
 */
object CallGuard {
    private const val TAG = "Slowtown"
    private const val FILE = "slowtown_calls"
    private const val KEY_CALLS = "calls"

    /**
     * Una notificación anotada hace más que esto no es una llamada sino un
     * aviso perdido (el de que se fue): no deja una app abierta para siempre.
     * Una llamada larga de verdad sigue contando por el modo de audio.
     */
    private const val MAX_AGE_MS = 6 * 60 * 60_000L

    /** Sonando, en una llamada del teléfono o por internet, o filtrando una. */
    private val CALL_MODES = setOf(
        AudioManager.MODE_RINGTONE,
        AudioManager.MODE_IN_CALL,
        AudioManager.MODE_IN_COMMUNICATION,
        AudioManager.MODE_CALL_SCREENING,
    )

    /**
     * De qué app es y desde cuándo, con el reloj del arranque: no lo mueve un
     * cambio de hora, y lo anotado antes de un reinicio queda «en el futuro».
     */
    private data class Call(val packageName: String, val since: Long)

    /** Desde el lector de notificaciones: una notificación publicada. */
    @Synchronized
    fun posted(context: Context, sbn: StatusBarNotification) {
        safely {
            if (sbn.packageName == context.packageName) return@safely
            val calls = load(context)
            val before = packagesOf(calls)
            if (isCall(sbn)) {
                // Si la app la actualiza, conserva su hora: el tope cuenta desde que empezó.
                if (sbn.key in calls) return@safely
                calls[sbn.key] = Call(sbn.packageName, SystemClock.elapsedRealtime())
            } else {
                // La que sonaba y quedó como llamada perdida sale.
                if (calls.remove(sbn.key) == null) return@safely
            }
            update(context, calls, before)
        }
    }

    /** Desde el lector: una notificación que se fue (por cualquier motivo). */
    @Synchronized
    fun removed(context: Context, sbn: StatusBarNotification) {
        safely {
            val calls = load(context)
            val before = packagesOf(calls)
            if (calls.remove(sbn.key) != null) update(context, calls, before)
        }
    }

    /** Al conectarse el lector (también después de un reinicio): rearma el estado con lo que está a la vista. */
    @Synchronized
    fun sync(context: Context, active: List<StatusBarNotification>) {
        safely {
            val old = load(context)
            val now = SystemClock.elapsedRealtime()
            val calls = mutableMapOf<String, Call>()
            for (sbn in active) {
                if (sbn.packageName == context.packageName || !isCall(sbn)) continue
                calls[sbn.key] = old[sbn.key] ?: Call(sbn.packageName, now)
            }
            // Se guarda siempre: así se va también lo que quedó colgado de antes.
            update(context, calls, packagesOf(old))
        }
    }

    /** Si hay una llamada en curso o sonando: telefónica o de una app (WhatsApp). */
    fun inCall(context: Context): Boolean = audioInCall(context) || packages(context).isNotEmpty()

    /** Las apps no bloqueadas que tienen una llamada en curso o sonando, para sumarlas a la lista de lock task. */
    fun packages(context: Context): Set<String> = runCatching {
        val calls = load(context)
        // Sin el lector no llega el aviso de que una llamada terminó: lo anotado ya no vale.
        if (calls.isEmpty() || !listening(context)) {
            emptySet<String>()
        } else {
            val blocked = Rules.blocked(context)
            packagesOf(calls).filterTo(mutableSetOf()) { it !in blocked }
        }
    }.onFailure { Log.w(TAG, "No pude leer las llamadas", it) }.getOrDefault(emptySet<String>())

    /**
     * Una llamada que suena o está en curso. Una llamada perdida que se
     * anuncia como llamada no cuenta, porque dejaría la app abierta hasta que
     * alguien la descarte: ésa se puede descartar, no es de un servicio en
     * primer plano y no trae pantalla completa.
     */
    private fun isCall(sbn: StatusBarNotification): Boolean {
        val n = sbn.notification ?: return false
        if (n.category != Notification.CATEGORY_CALL) return false
        return !sbn.isClearable ||
            (n.flags and Notification.FLAG_FOREGROUND_SERVICE) != 0 ||
            n.fullScreenIntent != null
    }

    private fun audioInCall(context: Context): Boolean = runCatching {
        val audio = context.getSystemService(AudioManager::class.java)
        audio != null && audio.mode in CALL_MODES
    }.getOrDefault(false)

    private fun listening(context: Context): Boolean =
        context.getSystemService(NotificationManager::class.java)
            ?.isNotificationListenerAccessGranted(ComponentName(context, WavesListener::class.java)) == true

    private fun packagesOf(calls: Map<String, Call>): Set<String> = calls.values.mapTo(mutableSetOf()) { it.packageName }

    /**
     * Guarda y, si cambió qué apps están en llamada, rearma la lista. En otro
     * hilo: consulta al sistema, y el lector corre en el principal.
     */
    private fun update(context: Context, calls: Map<String, Call>, before: Set<String>) {
        save(context, calls)
        val after = packagesOf(calls)
        if (after == before) return
        val app = context.applicationContext
        val ended = before.containsAll(after)
        thread(name = "llamada") {
            runCatching { Bedtime.callChanged(app, ended) }
                .onFailure { Log.w(TAG, "No pude rearmar la lista por una llamada", it) }
        }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Lo anotado, sin lo de otro arranque ni lo que pasó de [MAX_AGE_MS]. */
    private fun load(context: Context): MutableMap<String, Call> {
        val now = SystemClock.elapsedRealtime()
        val calls = mutableMapOf<String, Call>()
        for (entry in prefs(context).getStringSet(KEY_CALLS, null).orEmpty()) {
            // La clave de la notificación trae «|» adentro (usuario|paquete|id|tag|uid): va al final.
            val parts = entry.split('|', limit = 3)
            val since = parts.getOrNull(0)?.toLongOrNull() ?: continue
            val pkg = parts.getOrNull(1).orEmpty()
            val key = parts.getOrNull(2).orEmpty()
            if (pkg.isEmpty() || key.isEmpty() || since > now || now - since >= MAX_AGE_MS) continue
            calls[key] = Call(pkg, since)
        }
        return calls
    }

    private fun save(context: Context, calls: Map<String, Call>) {
        val entries = calls.map { (key, call) -> "${call.since}|${call.packageName}|$key" }.toSet()
        prefs(context).edit().putStringSet(KEY_CALLS, entries).apply()
    }

    private fun safely(block: () -> Unit) {
        runCatching { block() }.onFailure { Log.w(TAG, "La guardia de llamadas falló", it) }
    }
}
