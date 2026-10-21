package com.slowtown.launcher.notif

import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import java.time.ZonedDateTime

/**
 * Pospone las notificaciones de las apps elegidas hasta la próxima entrega
 * ([Waves]). A esa hora Android vuelve a publicar las originales, solo.
 *
 * Nunca se cuelga: corre en el proceso del Inicio, y la guardia de crashes
 * cuenta lo que se caiga acá. Todo va envuelto: si algo falla, la
 * notificación llega como siempre.
 *
 * Lo que Android 12 no deja esquivar desde un listener:
 * - Avisa después de hacerla sonar: una retenida puede sonar o asomarse un
 *   instante antes de irse (posponerla corta el sonido).
 * - Si la app actualiza una notificación ya pospuesta (WhatsApp junta cada
 *   chat en una sola), no le avisa a nadie: sigue esperando, aunque traiga
 *   una palabra de «pasan siempre».
 * - Lo pospuesto vive en memoria: un reinicio lo borra, como a todas. Android
 *   guarda la hora de cada una y, si la app la vuelve a publicar antes, la
 *   pospone de nuevo hasta entonces; la que la app no vuelve a publicar se
 *   pierde. Si vuelve después de su hora, acá se reconoce como su entrega.
 */
class WavesListener : NotificationListenerService() {

    override fun onListenerConnected() {
        connected = this
        safely { reapplyNow() }
    }

    override fun onListenerDisconnected() {
        if (connected === this) connected = null
    }

    override fun onDestroy() {
        if (connected === this) connected = null
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        safely {
            val pass = Pass(fromCallback = true)
            pass.run(listOf(sbn))
            pass.save()
        }
    }

    /** Lo que la persona descarta o la app cancela ya no hace falta recordarlo. */
    override fun onNotificationRemoved(
        sbn: StatusBarNotification?,
        rankingMap: NotificationListenerService.RankingMap?,
        reason: Int,
    ) {
        if (sbn == null || reason == NotificationListenerService.REASON_SNOOZED) return
        safely {
            val held = Waves.held(this).toMutableMap()
            if (held.remove(sbn.key) != null) Waves.setHeld(this, held)
        }
    }

    /**
     * Al conectarse (también después de un reinicio) y cada vez que cambia la
     * configuración: lo que está a la vista se decide con la misma regla, y lo
     * que pospuso Slowtown se suelta si ya no tiene que esperar o se acomoda
     * a los horarios de ahora.
     */
    private fun reapplyNow() {
        val pass = Pass(fromCallback = false)
        pass.run(pass.active)
        pass.revisitSnoozed()
        pass.save()
    }

    /** Una pasada: lee la configuración y lo pospuesto una sola vez, decide, pospone y guarda. */
    private inner class Pass(private val fromCallback: Boolean) {
        private val config = Waves.config(this@WavesListener)
        private val now: ZonedDateTime = ZonedDateTime.now()
        private val nowMillis = now.toInstant().toEpochMilli()
        private val local = now.toLocalDateTime()

        /** La próxima entrega, en epoch ms: la misma para todo lo que se retiene ahora. */
        private val target = nowMillis + Waves.millisUntilNextDelivery(now, config.times)
        private val held = Waves.held(this@WavesListener)
            .filterValues { it.until > nowMillis - FORGET_AFTER }
            .toMutableMap()

        val active: List<StatusBarNotification> by lazy { activeNotifications?.filterNotNull().orEmpty() }

        fun run(posted: List<StatusBarNotification>) {
            val holding = mutableSetOf<String>()
            val groups = mutableSetOf<String>()
            for (sbn in posted) {
                if (sbn.isGroup) groups += sbn.groupKey
                if (isSummary(sbn)) continue
                if (shouldHoldNow(sbn, visible = 0)) {
                    hold(sbn)
                    holding += sbn.key
                }
            }
            // Los resúmenes al final: dependen de lo que quedó a la vista de su grupo.
            for (group in groups) {
                val members = active.filter { it.isGroup && it.groupKey == group }
                val summary = members.firstOrNull { isSummary(it) } ?: continue
                val visible = members.count { !isSummary(it) && it.key !in holding }
                if (shouldHoldNow(summary, visible)) hold(summary)
            }
        }

        /** Lo que pospuso Slowtown y sigue pospuesto. Lo que pospuso otro (desde el panel) no se toca. */
        fun revisitSnoozed() {
            for (sbn in snoozedNotifications?.filterNotNull().orEmpty()) {
                val entry = held[sbn.key] ?: continue
                if (entry.delivered) continue
                val overdue = nowMillis >= entry.until - SLACK
                when {
                    overdue || !Waves.shouldHold(config, local, incoming(sbn)) -> {
                        // Android no tiene «soltar»: se pospone por 1 ms y vuelve enseguida,
                        // como entrega (vence ya, así que no se retiene de nuevo).
                        snoozeNotification(sbn.key, 1)
                        held[sbn.key] = Waves.Held(nowMillis, delivered = false)
                    }
                    entry.until != target -> hold(sbn)
                }
            }
        }

        fun save() = Waves.setHeld(this@WavesListener, held)

        /** Si [sbn] se pospone ahora. [visible] cuenta sólo para un resumen de grupo. */
        private fun shouldHoldNow(sbn: StatusBarNotification, visible: Int): Boolean {
            val entry = held[sbn.key]
            if (entry != null && !entry.delivered && nowMillis >= entry.until - SLACK) {
                // Su entrega: a horario, o tarde si el teléfono estaba apagado a esa hora.
                held[sbn.key] = entry.copy(delivered = true)
                return false
            }
            // Ya entregada y todavía a la vista: al reconectarse no se esconde de nuevo.
            // Si la app la actualiza (un mensaje más en ese chat), sí espera.
            if (entry != null && entry.delivered && !fromCallback) return false
            val incoming = incoming(sbn)
            return if (isSummary(sbn)) {
                Waves.shouldHoldSummary(config, local, incoming, visible)
            } else {
                Waves.shouldHold(config, local, incoming)
            }
        }

        private fun hold(sbn: StatusBarNotification) {
            snoozeNotification(sbn.key, (target - System.currentTimeMillis()).coerceAtLeast(1))
            held[sbn.key] = Waves.Held(target, delivered = false)
        }

        private fun incoming(sbn: StatusBarNotification): Waves.Incoming {
            val n = sbn.notification
            return Waves.Incoming(
                packageName = sbn.packageName,
                own = sbn.packageName == packageName,
                ongoing = !sbn.isClearable,
                foregroundService = (n.flags and Notification.FLAG_FOREGROUND_SERVICE) != 0,
                media = n.extras?.containsKey(Notification.EXTRA_MEDIA_SESSION) == true,
                category = n.category,
                // Leer el texto sólo hace falta si hay palabras que buscar.
                text = if (config.alwaysPass.isEmpty()) "" else readable(n),
            )
        }
    }

    companion object {
        /** Lo que vuelve hasta 10 s antes de su hora ya es la entrega: los relojes no son exactos. */
        private const val SLACK = 10_000L

        /** Lo que no volvió en un día no vuelve más (la app la canceló, o se perdió en un reinicio). */
        private const val FORGET_AFTER = 24 * 60 * 60_000L

        private val TEXT_KEYS = listOf(
            Notification.EXTRA_TITLE,
            Notification.EXTRA_TITLE_BIG,
            Notification.EXTRA_TEXT,
            Notification.EXTRA_BIG_TEXT,
            Notification.EXTRA_SUB_TEXT,
            Notification.EXTRA_SUMMARY_TEXT,
            Notification.EXTRA_INFO_TEXT,
            Notification.EXTRA_CONVERSATION_TITLE,
        )

        // El que está conectado, para que la configuración le avise de un cambio.
        // Mismo proceso y mismo hilo (el principal) que la pantalla.
        @Volatile
        private var connected: WavesListener? = null

        /** Después de cambiar la configuración: suelta, reacomoda o retiene lo que corresponda. */
        fun reapply() {
            val listener = connected ?: return
            safely { listener.reapplyNow() }
        }

        /** Si Android le da a Slowtown las notificaciones. Lo da provisionar.sh por adb. */
        fun hasAccess(context: Context): Boolean = runCatching {
            context.getSystemService(NotificationManager::class.java)
                ?.isNotificationListenerAccessGranted(ComponentName(context, WavesListener::class.java)) == true
        }.getOrDefault(false)

        /** Sin grupo, Android no la trata como resumen: va como cualquier otra. */
        private fun isSummary(sbn: StatusBarNotification): Boolean =
            sbn.isGroup && (sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0

        /** Lo que se lee de una notificación: título, textos, líneas y los mensajes de un chat, con quién los mandó. */
        // getParcelableArray con tipo recién existe en API 33; el S10e es API 31.
        @Suppress("DEPRECATION")
        private fun readable(n: Notification): String {
            val parts = mutableListOf<CharSequence?>(n.tickerText)
            val extras = n.extras ?: return parts.filterNotNull().joinToString("\n")
            for (key in TEXT_KEYS) parts += extras.getCharSequence(key)
            extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.let { parts.addAll(it) }
            extras.getParcelableArray(Notification.EXTRA_MESSAGES)?.forEach { message ->
                (message as? Bundle)?.let {
                    // Las claves de Notification.MessagingStyle.Message: «sender» sigue
                    // viniendo con el nombre, para los listeners viejos.
                    parts += it.getCharSequence("sender")
                    parts += it.getCharSequence("text")
                }
            }
            return parts.filterNotNull().joinToString("\n")
        }
    }
}

private const val TAG = "SlowtownOleadas"

/** Nada de acá puede colgar al Inicio: si algo falla, la notificación llega como siempre. */
private fun safely(block: () -> Unit) {
    runCatching(block).onFailure { Log.w(TAG, "Las oleadas fallaron; la notificación llega como siempre", it) }
}
