package com.slowtown.launcher.admin

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle

/**
 * Las políticas de Chrome. Chrome las lee de las restricciones que le pone el
 * Device Owner: muestra «Administrado por tu organización» y esas opciones
 * quedan grises. Las guarda Android y no Chrome, así que borrarle los datos
 * no las saca, y siguen en modo seguro. Sólo las cambia Slowtown. Desinstalarle
 * las actualizaciones tampoco: queda bloqueado (y se suelta solo si Slowtown
 * deja de ser Device Owner).
 *
 * Mezcla el nivel 2 ([Fixed.CORE_SITES] y [Fixed.listedSites]) con las
 * páginas que se suman desde la configuración (nivel 1).
 */
object Chrome {
    const val PACKAGE = "com.android.chrome"

    /** Chrome no lee más que éstas por lista (lo documenta Google). */
    private const val MAX_ENTRIES = 1000

    /**
     * Las políticas, con nombre y tipo como los documenta
     * chromeenterprise.google/policies. En la lista, primero las elegidas a
     * mano, después las del nivel 1 y al final la base de las listas: si no
     * entran todas, quedan afuera las menos visitadas.
     */
    fun policies(context: Context, extraSites: Collection<String>): Bundle = Bundle().apply {
        val sites = (Fixed.CORE_SITES + extraSites + Fixed.listedSites(context)).distinct().take(MAX_ENTRIES)
        putStringArray("URLBlocklist", sites.toTypedArray())
        putStringArray("URLAllowlist", Fixed.SITE_EXCEPTIONS.toTypedArray())
        // 1: incógnito deshabilitado.
        putInt("IncognitoModeAvailability", 1)
        putBoolean("ForceGoogleSafeSearch", true)
        // 1: el filtro de Chrome para sitios de adultos, con la clasificación
        // de Google (Android, desde Chrome 116). Tapa lo que la lista no alcanza.
        putInt("SafeSitesFilterBehavior", 1)
        // Sin DNS propio de Chrome: el día que el teléfono tenga un DNS que
        // filtre, Chrome no lo puede saltear.
        putString("DnsOverHttpsMode", "off")
        // Sin las noticias de la pestaña nueva.
        putBoolean("NTPContentSuggestionsEnabled", false)
    }

    /** Las pone si cambiaron: cada escritura le avisa a Chrome y se guarda a disco. */
    @Synchronized
    fun apply(context: Context, extraSites: Collection<String>) {
        if (!Policies.isDeviceOwner(context)) return
        val dpm = context.getSystemService(DevicePolicyManager::class.java)
        val admin = Policies.admin(context)
        val wanted = policies(context, extraSites)
        if (!same(dpm.getApplicationRestrictions(admin, PACKAGE), wanted)) {
            dpm.setApplicationRestrictions(admin, PACKAGE, wanted)
        }
        // Volver a la versión de fábrica dejaría un Chrome viejo y con los datos
        // borrados; bloqueado, «Desinstalar actualizaciones» no anda.
        if (isInstalled(context) && !dpm.isUninstallBlocked(admin, PACKAGE)) {
            dpm.setUninstallBlocked(admin, PACKAGE, true)
        }
    }

    /** Para la salida por cable: sin Slowtown, nadie más las podría sacar. */
    @Synchronized
    fun clear(context: Context) {
        if (!Policies.isDeviceOwner(context)) return
        val dpm = context.getSystemService(DevicePolicyManager::class.java)
        val admin = Policies.admin(context)
        dpm.setApplicationRestrictions(admin, PACKAGE, Bundle())
        if (isInstalled(context)) dpm.setUninstallBlocked(admin, PACKAGE, false)
    }

    private fun isInstalled(context: Context): Boolean = runCatching {
        context.packageManager.getApplicationInfo(PACKAGE, PackageManager.MATCH_DISABLED_COMPONENTS)
    }.isSuccess

    /**
     * «https://www.9gag.com/hot» → «9gag.com», como lo entiende URLBlocklist
     * (bloquea también los subdominios). null si no parece una página.
     */
    fun normalizeSite(input: String): String? {
        val host = input.trim().lowercase()
            .substringAfter("://")
            .substringBefore('/').substringBefore('?').substringBefore('#')
            .substringAfterLast('@').substringBefore(':')
            .removePrefix("www.")
        return host.takeIf { SITE.matches(it) }
    }

    private val SITE = Regex("^([a-z0-9]([a-z0-9-]*[a-z0-9])?\\.)+[a-z]{2,}$")

    // Bundle no tiene equals, y los arreglos se comparan por contenido.
    @Suppress("DEPRECATION")
    private fun same(a: Bundle, b: Bundle): Boolean =
        a.keySet() == b.keySet() && a.keySet().all { key ->
            val x = a.get(key)
            val y = b.get(key)
            if (x is Array<*> && y is Array<*>) x.contentEquals(y) else x == y
        }
}
