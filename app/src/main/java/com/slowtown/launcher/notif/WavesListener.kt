package com.slowtown.launcher.notif

import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.slowtown.launcher.admin.CallGuard
import java.time.ZonedDateTime

/**
 * Pospone las notificaciones de las apps elegidas hasta la próxima entrega
 * ([Waves]). A esa hora Android vuelve a publicar las originales, solo.
 *
 * Es también el único lector de notificaciones de Slowtown, así que le pasa
 * a [CallGuard] lo que entra y sale del panel, con las oleadas prendidas o
 * no. Cada parte va envuelta aparte: si una falla, la otra sigue.
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
 * - Lo pospuesto vuelve en silencio, a la hora de la entrega o al soltarlo.
 * - Al posponer lo último que quedaba a la vista de un grupo, Android pospone
 *   también su resumen. Y mientras un resumen está pospuesto, todo lo nuevo
 *   de su grupo se pospone con él, hasta su hora, sin avisarle a nadie: ni
 *   una palabra de «pasan siempre» lo saca. Con «sólo los grupos» eso dejaría
 *   esperando a los chats de a dos, así que ahí el resumen se suelta
 *   enseguida, y queda solo en el panel, en silencio, con las últimas líneas
 *   de los grupos que esperan. Un chat de a dos que llega justo en ese
 *   instante se suelta un segundo después, sin sonido.
 * - Lo pospuesto vive en memoria: un reinicio lo borra, como a todas. Android
 *   guarda la hora de cada una y, si la app la vuelve a publicar antes, la
 *   pospone de nuevo hasta entonces; la que la app no vuelve a publicar se
 *   pierde. Si vuelve después de su hora, acá se reconoce como su entrega.
 */
class WavesListener : NotificationListenerService() {

    private val handler = Handler(Looper.getMainLooper())

    /**
     * Cuándo soltó Slowtown el resumen de cada grupo, por clave de grupo y con
     * el reloj del arranque. Con esto se reconoce lo que Android trae de vuelta
     * junto con un resumen (ver [Pass.broughtBack]).
     */
    private val summaryReleases = mutableMapOf<String, Long>()

    /** Una vuelta por lo pospuesto, después de que Android hizo lo suyo (ver [sweepSoon]). */
    private val sweep = Runnable {
        safely {
            if (connected === this) {
                val pass = Pass(fromCallback = false)
                pass.revisitSnoozed()
                pass.save()
            }
        }
    }

    override fun onListenerConnected() {
        connected = this
        safely(CALL_GUARD_FAILED) { CallGuard.sync(this, activeNotifications?.filterNotNull().orEmpty()) }
        safely { reapplyNow() }
    }

    override fun onListenerDisconnected() {
        if (connected === this) connected = null
        handler.removeCallbacks(sweep)
    }

    override fun onDestroy() {
        if (connected === this) connected = null
        handler.removeCallbacks(sweep)
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        safely(CALL_GUARD_FAILED) { CallGuard.posted(this, sbn) }
        safely {
            val pass = Pass(fromCallback = true)
            pass.run(listOf(sbn))
            pass.save()
        }
    }

    /**
     * Lo que la persona descarta o la app cancela ya no hace falta recordarlo.
     * La guardia de llamadas se entera de todo, también de lo pospuesto: una
     * llamada nunca espera, y para la guardia cuenta lo que está a la vista.
     */
    override fun onNotificationRemoved(
        sbn: StatusBarNotification?,
        rankingMap: NotificationListenerService.RankingMap?,
        reason: Int,
    ) {
        if (sbn == null) return
        safely(CALL_GUARD_FAILED) { CallGuard.removed(this, sbn) }
        if (reason == NotificationListenerService.REASON_SNOOZED) {
            safely { summaryTaken(sbn) }
            return
        }
        safely {
            val held = Waves.held(this).toMutableMap()
            if (held.remove(sbn.key) != null) Waves.setHeld(this, held)
        }
    }

    /**
     * «Sólo los grupos»: Android se llevó el resumen al posponer un chat de
     * grupo, el último a la vista. Si sigue pospuesto, se suelta ya: mientras
     * lo está, se lleva con él todo lo nuevo de su grupo, también los chats de
     * a dos. La pasada que retuvo el chat ya lo suelta cuando sabe que Android
     * se lo va a llevar; esto es para cuando no lo sabía.
     */
    private fun summaryTaken(sbn: StatusBarNotification) {
        if (!isSummary(sbn)) return
        val pass = Pass(fromCallback = false)
        if (pass.releaseTakenSummary(sbn)) {
            pass.save()
            sweepSoon()
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

    /**
     * Repasa lo pospuesto en un rato: Android pospone después de que se lo
     * piden, y el resumen que pospone por su cuenta puede no haber llegado
     * nunca al panel, así que de eso no avisa. Si ya había uno pendiente, lo
     * corre para más tarde.
     */
    private fun sweepSoon() {
        handler.removeCallbacks(sweep)
        handler.postDelayed(sweep, SWEEP_DELAY)
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

        /** Lo pospuesto, leído una sola vez y sólo si hace falta. */
        private val snoozed: List<StatusBarNotification> by lazy { snoozedNotifications?.filterNotNull().orEmpty() }

        fun run(posted: List<StatusBarNotification>) {
            // Lo que está a la vista se lee antes de posponer nada: Android atiende
            // enseguida, y al posponer el último chat de un grupo se lleva también
            // el resumen. Leído después, el resumen ya no estaría.
            val before = active
            val holding = mutableSetOf<String>()
            val groups = mutableSetOf<String>()
            // Los grupos de apps de «sólo los grupos» a los que se les retuvo algo en esta pasada.
            val heldGroupsOnly = mutableSetOf<String>()
            for (sbn in posted) {
                if (sbn.isGroup) groups += sbn.groupKey
                if (isSummary(sbn)) continue
                if (shouldHoldNow(sbn, visible = 0)) {
                    hold(sbn)
                    holding += sbn.key
                    if (sbn.isGroup && sbn.packageName in config.groupsOnly) heldGroupsOnly += sbn.groupKey
                }
            }
            // Los resúmenes al final: dependen de lo que quedó a la vista de su grupo.
            for (group in groups) {
                val members = before.filter { it.isGroup && it.groupKey == group }
                val summary = members.firstOrNull { isSummary(it) } ?: continue
                val visible = members.count { !isSummary(it) && it.key !in holding }
                when {
                    shouldHoldNow(summary, visible) -> hold(summary)
                    // «Sólo los grupos» sin nada a la vista: Android pospone el resumen junto
                    // con lo último que se retuvo, y se suelta ya, detrás (Android atiende en
                    // orden). Sólo si no hay otro chat del grupo pospuesto: con eso, hay
                    // versiones de Android que no se lo llevan, y soltar un resumen a la vista
                    // las hace soltar también todo lo pospuesto del grupo. Si se lo llevó
                    // igual, lo suelta summaryTaken.
                    visible == 0 && group in heldGroupsOnly && noneSnoozed(group, except = holding) -> release(summary)
                }
            }
            // Por si Android pospuso un resumen que acá todavía no se veía, o se llevó con
            // él un chat de a dos que llegó en ese instante.
            if (heldGroupsOnly.isNotEmpty()) sweepSoon()
        }

        /** Ver [summaryTaken]: si el resumen sigue pospuesto, lo suelta. */
        fun releaseTakenSummary(summary: StatusBarNotification): Boolean {
            if (!config.enabled || summary.packageName !in config.packages || summary.packageName !in config.groupsOnly) {
                return false
            }
            // Si ya volvió, no se toca: soltar un resumen a la vista lo esconde un momento,
            // y con él a lo que esté a la vista de su grupo.
            val still = snoozed.firstOrNull { it.key == summary.key } ?: return false
            release(still)
            return true
        }

        /** Si no queda pospuesto ningún chat de [group], sin contar los de [except]. */
        private fun noneSnoozed(group: String, except: Set<String>): Boolean =
            snoozed.none { it.isGroup && it.groupKey == group && !isSummary(it) && it.key !in except }

        /**
         * Lo que pospuso Slowtown y sigue pospuesto: se suelta si ya no tiene que
         * esperar y se acomoda si cambiaron los horarios. Lo que pospuso otro se
         * deja, salvo lo que Android pospuso por su cuenta en una app de «sólo
         * los grupos» (ver [stuck]). Los resúmenes primero, para que lo que se
         * suelta de su grupo no quede pospuesto de nuevo con ellos.
         */
        fun revisitSnoozed() {
            // Leído de nuevo y no [snoozed]: en la misma pasada, [run] puede haber pospuesto o soltado algo.
            val snoozed = snoozedNotifications?.filterNotNull().orEmpty().sortedByDescending { isSummary(it) }
            for (sbn in snoozed) {
                val entry = held[sbn.key]
                if (entry == null || entry.delivered) {
                    if (stuck(sbn)) release(sbn)
                    continue
                }
                val overdue = nowMillis >= entry.until - SLACK
                when {
                    overdue || !stillWaits(sbn) -> release(sbn)
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
            // Volvió antes de su hora, sin que Slowtown la soltara, justo después de que
            // se soltó el resumen de su grupo: la trajo Android con él. Hay versiones de
            // Android que, al soltar un resumen, sueltan también lo ya pospuesto de su
            // grupo (el emulador de Android 12 no). Retenerla otra vez haría que Android
            // se lleve de nuevo el resumen, y así sin fin: ante la duda, llega.
            if (entry != null && !entry.delivered && fromCallback && broughtBack(sbn)) {
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

        /**
         * Si lo pospuesto sigue esperando con la configuración de ahora. Un
         * resumen se decide como si no quedara nada a la vista de su grupo.
         */
        private fun stillWaits(sbn: StatusBarNotification): Boolean =
            if (isSummary(sbn)) {
                Waves.shouldHoldSummary(config, local, incoming(sbn), visible = 0)
            } else {
                Waves.shouldHold(config, local, incoming(sbn))
            }

        /**
         * De una app de «sólo los grupos», lo que Android pospuso por su cuenta
         * y no tiene que esperar: el resumen, o un chat de a dos que llegó
         * mientras el resumen estaba pospuesto.
         */
        private fun stuck(sbn: StatusBarNotification): Boolean =
            config.enabled &&
                sbn.packageName in config.packages &&
                sbn.packageName in config.groupsOnly &&
                (isSummary(sbn) || !isGroupConversation(sbn))

        private fun hold(sbn: StatusBarNotification) {
            snoozeNotification(sbn.key, (target - System.currentTimeMillis()).coerceAtLeast(1))
            held[sbn.key] = Waves.Held(target, delivered = false)
        }

        /**
         * Android no tiene «soltar»: se pospone por 1 ms y vuelve enseguida,
         * como entrega (vence ya, así que no se retiene de nuevo). Así se borra
         * también la hora que Android le había puesto a un resumen.
         */
        private fun release(sbn: StatusBarNotification) {
            snoozeNotification(sbn.key, 1)
            held[sbn.key] = Waves.Held(nowMillis, delivered = false)
            if (isSummary(sbn)) summaryReleases[sbn.groupKey] = SystemClock.elapsedRealtime()
        }

        /** Si se soltó hace poco el resumen del grupo de [sbn] (ver [shouldHoldNow]). */
        private fun broughtBack(sbn: StatusBarNotification): Boolean {
            val at = summaryReleases[sbn.groupKey] ?: return false
            return sbn.isGroup && SystemClock.elapsedRealtime() - at < BROUGHT_BACK
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
                // Y si es de un grupo, sólo en las apps de «sólo los grupos».
                groupConversation = sbn.packageName in config.groupsOnly && isGroupConversation(sbn),
            )
        }
    }

    companion object {
        /** Lo que vuelve hasta 10 s antes de su hora ya es la entrega: los relojes no son exactos. */
        private const val SLACK = 10_000L

        /** Lo que no volvió en un día no vuelve más (la app la canceló, o se perdió en un reinicio). */
        private const val FORGET_AFTER = 24 * 60 * 60_000L

        /** Lo que tarda Android en posponer y en publicar un resumen que llega junto con su chat, con margen. */
        private const val SWEEP_DELAY = 1_000L

        /** Lo que tarda en volver lo que Android trae junto con un resumen soltado, con mucho margen. */
        private const val BROUGHT_BACK = 10_000L

        /**
         * WhatsApp (y WhatsApp Business) nombra cada chat por su JID, y lo usa
         * de id del atajo del chat y de tag de la notificación: «…@g.us» es un
         * grupo, «…@s.whatsapp.net» una persona.
         */
        private const val JID_GROUP = "@g.us"
        private const val JID_PERSON = "@s.whatsapp.net"

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

        /**
         * Si es de un chat de grupo. Primero lo que dice la app: el estilo de
         * mensajes de Android trae la marca (WhatsApp, Mensajes, Telegram,
         * Signal). Después el JID de WhatsApp, que además dice cuándo es de a
         * dos. Si la app no dice nada: un título de conversación con mensajes
         * de dos personas o más. Ante la duda no es un grupo, y llega en el
         * momento: mejor un grupo a deshora que un chat de a dos que espera.
         */
        private fun isGroupConversation(sbn: StatusBarNotification): Boolean {
            val n = sbn.notification
            val extras = n.extras ?: Bundle.EMPTY
            if (extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION)) return true
            val ids = listOfNotNull(n.shortcutId, sbn.tag)
            if (ids.any { it.endsWith(JID_GROUP) }) return true
            if (ids.any { it.endsWith(JID_PERSON) }) return false
            return !extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE).isNullOrBlank() &&
                otherSenders(extras) >= 2
        }

        /**
         * Cuántas personas distintas escriben en los mensajes, sin contar a
         * quien usa el teléfono: en un chat de a dos, después de responder
         * desde la notificación, la respuesta viene como un mensaje más, sin
         * remitente o con el nombre propio. Con eso solo no es un grupo.
         */
        // getParcelableArray con tipo recién existe en API 33; el S10e es API 31.
        @Suppress("DEPRECATION")
        private fun otherSenders(extras: Bundle): Int {
            val me = extras.getCharSequence(Notification.EXTRA_SELF_DISPLAY_NAME)?.toString()
            return extras.getParcelableArray(Notification.EXTRA_MESSAGES)
                ?.mapNotNull { (it as? Bundle)?.getCharSequence("sender")?.toString() }
                ?.filter { it.isNotBlank() && it != me }
                ?.distinct()
                ?.size
                ?: 0
        }

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
private const val WAVES_FAILED = "Las oleadas fallaron; la notificación llega como siempre"
private const val CALL_GUARD_FAILED = "La guardia de llamadas falló; las oleadas siguen"

/** Nada de acá puede colgar al Inicio: si algo falla, se anota y lo demás sigue. */
private fun safely(failure: String = WAVES_FAILED, block: () -> Unit) {
    runCatching(block).onFailure { Log.w(TAG, failure, it) }
}
