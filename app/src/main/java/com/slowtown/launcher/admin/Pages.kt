package com.slowtown.launcher.admin

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.slowtown.launcher.Prefs
import com.slowtown.launcher.R

/**
 * Las páginas bloqueadas sin cable. Con Device Owner, Chrome recibe la lista en
 * sus políticas ([Chrome]); sin él, nadie se la puede poner, así que la hace
 * cumplir [PageGuard], con el permiso de accesibilidad: mira la dirección que
 * muestra Chrome y, si es de una bloqueada, vuelve atrás. Es lo que hacen las
 * apps de bloqueo de la Play Store (docs/sin-cable.md); Marcos lo eligió el
 * 27/10 porque queda adentro de Slowtown, sin otra app ni otro servicio.
 *
 * Las mismas páginas que las políticas, con las mismas reglas: el nivel 2
 * ([Fixed.CORE_SITES] y [Fixed.listedSites]), las del nivel 1 y las
 * excepciones ([Fixed.SITE_EXCEPTIONS]), sin el tope de 1.000 de Chrome. Como el
 * nivel 2, vale con Slowtown activo o en pausa.
 */
object Pages {
    /**
     * Los que mira: Chrome y sus versiones de prueba (`page_guard_browsers`,
     * lo mismo que lee res/xml/page_guard.xml). La variante de prueba suma el
     * navegador de prueba del emulador.
     */
    fun browsers(context: Context): Set<String> =
        browsers ?: context.getString(R.string.page_guard_browsers).split(',').map(String::trim).toSet()
            .also { browsers = it }

    @Volatile
    private var browsers: Set<String>? = null

    /**
     * Si el permiso de accesibilidad de Slowtown está prendido, como lo guarda
     * Ajustes. La lista de AccessibilityManager tiene sólo los servicios
     * ligados ahora, y un rato después de reiniciarse el servicio (o mientras
     * mira uiautomator) diría que está apagado.
     */
    fun isOn(context: Context): Boolean {
        val resolver = context.contentResolver
        if (Settings.Secure.getInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0) != 1) return false
        val mine = ComponentName(context, PageGuard::class.java)
        return Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            .split(':')
            .any { ComponentName.unflattenFromString(it.trim()) == mine }
    }

    /**
     * La pantalla de Ajustes donde se prende y se apaga: la de accesibilidad.
     * La de Slowtown sola existe, pero la abre sólo el sistema (pide un permiso
     * que una app no tiene).
     */
    fun settingsIntent(): Intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)

    /**
     * La de la lista que bloquea [host] (ella o un dominio que la contiene,
     * como hace Chrome con sus subdominios), o null si se puede abrir.
     */
    fun blocked(context: Context, host: String): String? {
        if (Fixed.SITE_EXCEPTIONS.any { host == it || host.endsWith(".$it") }) return null
        val sites = sites(context)
        val parts = host.split('.')
        return parts.indices.asSequence().map { parts.drop(it).joinToString(".") }.firstOrNull { it in sites }
    }

    /** Todas juntas, para buscar rápido: se vuelven a juntar sólo si cambian las del nivel 1. */
    private fun sites(context: Context): Set<String> {
        val chosen = Prefs(context).blockedSites
        cached?.let { (from, all) -> if (from == chosen) return all }
        val all = (Fixed.CORE_SITES + chosen + Fixed.listedSites(context)).toHashSet()
        cached = chosen to all
        return all
    }

    @Volatile
    private var cached: Pair<Set<String>, Set<String>>? = null
}
