package com.slowtown.launcher.notif

import android.app.Notification
import android.content.Context
import com.slowtown.launcher.apps.normalize
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZonedDateTime

/**
 * Las notificaciones por oleadas: las de las apps elegidas no llegan apenas
 * salen; esperan y llegan todas juntas a ciertas horas (de entrada, 12 y 19).
 * No se copian ni se rearman: [WavesListener] le pide a Android que posponga
 * la original hasta la próxima entrega, así que al llegar se toca y sus
 * botones (responder, marcar como leído) andan como siempre.
 *
 * Tiene su propio archivo de preferencias, aparte de [com.slowtown.launcher.Prefs].
 * Las decisiones son funciones puras con valores sueltos, para razonarlas sin
 * Android (las constantes de [Notification] se copian al compilar).
 */
object Waves {

    /** De entrada, al mediodía y a la tarde. Minutos desde la medianoche. */
    val DEFAULT_TIMES = listOf(12 * 60, 19 * 60)

    private const val DAY = 24 * 60

    /**
     * Pasan aunque la app espere: una llamada que entra, una alarma, una
     * llamada perdida (alguien quiso hablar ya) y un reproductor, que es un
     * control y no una novedad: la música en pausa deja de estar «en curso».
     */
    private val NEVER_CATEGORIES = setOf(
        Notification.CATEGORY_CALL,
        Notification.CATEGORY_ALARM,
        Notification.CATEGORY_MISSED_CALL,
        Notification.CATEGORY_TRANSPORT,
    )

    data class Config(
        val enabled: Boolean,
        val packages: Set<String>,
        /** Ordenados, sin repetir, nunca vacíos. */
        val times: List<Int>,
        val alwaysPass: List<String>,
        /** De estas apps esperan sólo los chats de grupo: los de a dos llegan en el momento. */
        val groupsOnly: Set<String>,
    )

    /** Lo que hace falta saber de una notificación para decidir. */
    data class Incoming(
        val packageName: String,
        /** Si es de Slowtown. */
        val own: Boolean,
        /** No se puede descartar (FLAG_ONGOING_EVENT o FLAG_NO_CLEAR): un viaje, una descarga. */
        val ongoing: Boolean,
        val foregroundService: Boolean,
        /** Trae una sesión de medios: los controles de la música o de un podcast. */
        val media: Boolean,
        val category: String?,
        /** Título, texto y mensajes juntos: donde se buscan las palabras de «pasan siempre». */
        val text: String,
        /** Si es de un chat de grupo. Sólo se averigua para las apps de [Config.groupsOnly]. */
        val groupConversation: Boolean,
    )

    /** La próxima entrega, estrictamente después de [now]: si hoy ya no queda ninguna, la primera de mañana. */
    fun nextDelivery(now: LocalDateTime, times: List<Int>): LocalDateTime {
        val sorted = cleanTimes(times).ifEmpty { DEFAULT_TIMES }
        val midnight = now.toLocalDate().atStartOfDay()
        return sorted.map { midnight.plusMinutes(it.toLong()) }.firstOrNull { it.isAfter(now) }
            ?: midnight.plusDays(1).plusMinutes(sorted.first().toLong())
    }

    /**
     * Cuánto falta para la próxima entrega. Con la zona y no en hora local:
     * el día que cambia la hora, la cuenta es de horas reales.
     */
    fun millisUntilNextDelivery(now: ZonedDateTime, times: List<Int>): Long {
        val next = nextDelivery(now.toLocalDateTime(), times).atZone(now.zone)
        // Android no pospone por 0 ms o menos: con 1 ms vuelve enseguida.
        return Duration.between(now, next).toMillis().coerceAtLeast(1)
    }

    /**
     * Si [now] cae en el minuto de una entrega. Lo que Android vuelve a
     * publicar en ese minuto es la oleada misma: retenerlo la mandaría a la
     * siguiente, y así para siempre.
     */
    fun isDeliveryMinute(now: LocalDateTime, times: List<Int>): Boolean =
        (now.hour * 60 + now.minute) in cleanTimes(times).ifEmpty { DEFAULT_TIMES }

    /** Si [text] trae alguna de [words] entera, sin tildes ni mayúsculas: «mamá» encuentra «¡Mamá!», no «mamadera». */
    fun passes(text: String, words: List<String>): Boolean {
        if (words.isEmpty()) return false
        val haystack = spaced(text)
        return words.any { word -> spaced(word).let { it.isNotBlank() && it in haystack } }
    }

    /** Si una notificación espera a la próxima entrega. Un resumen de grupo, además, pasa por [shouldHoldSummary]. */
    fun shouldHold(config: Config, now: LocalDateTime, n: Incoming): Boolean = when {
        !config.enabled -> false
        n.own -> false
        n.packageName !in config.packages -> false
        // «Sólo los grupos»: de esa app, lo que no es de un chat de grupo llega en el momento.
        n.packageName in config.groupsOnly && !n.groupConversation -> false
        n.ongoing || n.foregroundService || n.media -> false
        n.category != null && n.category in NEVER_CATEGORIES -> false
        isDeliveryMinute(now, config.times) -> false
        passes(n.text, config.alwaysPass) -> false
        else -> true
    }

    /**
     * Un resumen de grupo («3 mensajes de 2 chats») espera sólo si no queda
     * nada a la vista de su grupo. Android, al posponer un resumen, pospone
     * también lo que está a la vista de su grupo: si quedó algo que pasó (una
     * palabra de «pasan siempre», algo en curso) o lo de la última oleada, el
     * resumen se queda con eso. No retenerlo nunca lo dejaría solo en el
     * panel, con su sonido.
     *
     * De una app de «sólo los grupos» no espera nunca, aunque quede solo en el
     * panel: mientras un resumen está pospuesto, Android pospone con él todo lo
     * nuevo de su grupo antes de avisarle a nadie, y WhatsApp junta todos los
     * chats en un solo grupo, así que los de a dos esperarían también.
     *
     * @param visible cuántas del grupo quedan a la vista, sin contar el resumen
     *   ni las que se retienen en esta misma pasada.
     */
    fun shouldHoldSummary(config: Config, now: LocalDateTime, summary: Incoming, visible: Int): Boolean =
        visible == 0 && summary.packageName !in config.groupsOnly && shouldHold(config, now, summary)

    fun cleanTimes(times: List<Int>): List<Int> = times.filter { it in 0 until DAY }.distinct().sorted()

    /** «09:05». */
    fun format(minutes: Int): String = "%02d:%02d".format(minutes / 60, minutes % 60)

    /** La palabra como se guarda, o null si no tiene nada que buscar (sólo signos o emojis). */
    fun cleanWord(input: String): String? =
        input.trim().replace(WHITESPACE, " ").takeIf { spaced(it).isNotBlank() }

    /** Si dos palabras buscan lo mismo: «Mamá» y «mama» son la misma. */
    fun sameWord(a: String, b: String): Boolean = spaced(a) == spaced(b)

    private val SEPARATORS = Regex("[^\\p{L}\\p{N}]+")
    private val WHITESPACE = Regex("\\s+")

    /** «¡Mamá!» queda « mama »: con un espacio en cada punta, una palabra se busca entera. */
    private fun spaced(text: String): String = " " + normalize(text).replace(SEPARATORS, " ").trim() + " "

    // Lo que se guarda.

    /**
     * Lo que pospuso Slowtown, por clave de notificación: hasta cuándo (epoch
     * ms) y si ya se entregó y sigue a la vista. Con esto se reconoce la
     * entrega aunque llegue tarde (el teléfono estaba apagado a esa hora), no
     * se vuelve a retener lo ya entregado al reconectarse, y se suelta o se
     * reacomoda lo pospuesto si cambia la configuración.
     */
    data class Held(val until: Long, val delivered: Boolean)

    private const val FILE = "slowtown_notif"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_PACKAGES = "packages"
    private const val KEY_TIMES = "times"
    private const val KEY_ALWAYS_PASS = "always_pass"
    private const val KEY_GROUPS_ONLY = "groups_only"
    private const val KEY_HELD = "held"

    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun config(context: Context): Config {
        val prefs = prefs(context)
        return Config(
            enabled = prefs.getBoolean(KEY_ENABLED, false),
            packages = prefs.getStringSet(KEY_PACKAGES, null)?.toSet().orEmpty(),
            times = prefs.getString(KEY_TIMES, null)
                ?.split(',')
                ?.mapNotNull { it.trim().toIntOrNull() }
                ?.let { cleanTimes(it) }
                ?.ifEmpty { null }
                ?: DEFAULT_TIMES,
            alwaysPass = prefs.getString(KEY_ALWAYS_PASS, null)
                ?.split('\n')
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                .orEmpty(),
            groupsOnly = prefs.getStringSet(KEY_GROUPS_ONLY, null)?.toSet().orEmpty(),
        )
    }

    fun setEnabled(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, value).apply()
    }

    fun setPackages(context: Context, value: Set<String>) {
        prefs(context).edit().putStringSet(KEY_PACKAGES, value).apply()
    }

    /** Sin ningún horario vuelven los de entrada: tiene que haber cuándo entregar. */
    fun setTimes(context: Context, value: List<Int>) {
        prefs(context).edit().putString(KEY_TIMES, cleanTimes(value).joinToString(",")).apply()
    }

    fun setAlwaysPass(context: Context, value: List<String>) {
        prefs(context).edit().putString(KEY_ALWAYS_PASS, value.joinToString("\n")).apply()
    }

    fun setGroupsOnly(context: Context, value: Set<String>) {
        prefs(context).edit().putStringSet(KEY_GROUPS_ONLY, value).apply()
    }

    fun held(context: Context): Map<String, Held> =
        prefs(context).getStringSet(KEY_HELD, null).orEmpty().mapNotNull { entry ->
            // La clave trae «|» adentro (usuario|paquete|id|tag|uid): va al final y se corta en los dos primeros.
            val parts = entry.split('|', limit = 3)
            val until = parts.getOrNull(0)?.toLongOrNull()
            val key = parts.getOrNull(2)
            if (until == null || key.isNullOrEmpty()) null else key to Held(until, parts[1] == "d")
        }.toMap()

    fun setHeld(context: Context, value: Map<String, Held>) {
        val entries = value.map { (key, held) -> "${held.until}|${if (held.delivered) "d" else "h"}|$key" }.toSet()
        prefs(context).edit().putStringSet(KEY_HELD, entries).apply()
    }
}
