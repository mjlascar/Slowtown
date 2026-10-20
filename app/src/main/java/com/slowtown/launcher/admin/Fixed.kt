package com.slowtown.launcher.admin

import android.content.Context
import android.util.Log
import com.slowtown.launcher.Prefs
import com.slowtown.launcher.R

/**
 * El nivel 2: lo que Slowtown bloquea pase lo que pase. No se cambia desde la
 * configuración, ni con la contraseña: sólo con una versión nueva de Slowtown,
 * compilada e instalada por cable desde la compu, con la clave estable. Marcos
 * lo pidió así para no acostumbrarse a aflojarlo: lo que se saca con una
 * contraseña que está en un cajón de su casa se termina sacando.
 *
 * Vale con Slowtown en pausa, durante «Habilitar todo» y la sesión de
 * administración, y en modo seguro, porque lo guarda Android y no depende de
 * que Slowtown esté corriendo. El nivel 1 es el de la configuración: las apps
 * permitidas y las páginas que se suman ([com.slowtown.launcher.Prefs.blockedSites]).
 */
object Fixed {

    /**
     * Bloqueadas en Chrome, elegidas a mano. Van antes que las de las listas
     * ([listedSites]), así el tope de Chrome nunca las deja afuera. Cada una
     * bloquea también sus subdominios.
     */
    val CORE_SITES = listOf(
        "youtube.com", "youtu.be", "youtube-nocookie.com", "youtubekids.com",
        "tiktok.com",
        "instagram.com", "threads.net", "threads.com",
        "facebook.com", "fb.com", "fb.watch",
        "x.com", "twitter.com",
        "reddit.com", "redd.it",
        "twitch.tv", "kick.com",
        "netflix.com", "disneyplus.com", "max.com", "hbomax.com", "primevideo.com",
        "paramountplus.com", "pluto.tv", "crunchyroll.com", "mubi.com",
        // El traductor de Google sirve cualquier página desde su propio
        // dominio: con él se vería YouTube aunque youtube.com esté bloqueado.
        "translate.goog",
    )

    /**
     * La base de las listas de la Universidad Toulouse Capitole, las más
     * visitadas de cada categoría (`res/raw/paginas_fijas.txt`, la arma
     * `scripts/paginas-fijas.py`), de la más visitada a la menos.
     */
    fun listedSites(context: Context): List<String> {
        listed?.let { return it }
        synchronized(this) {
            return listed ?: context.resources.openRawResource(R.raw.paginas_fijas).bufferedReader().useLines { lines ->
                lines.map(String::trim).filter { it.isNotEmpty() && !it.startsWith("#") }.toList()
            }.also { listed = it }
        }
    }

    @Volatile
    private var listed: List<String>? = null

    /** Si el nivel 2 ya bloquea esta página: ella o un dominio que la contiene. */
    fun covers(context: Context, site: String): Boolean {
        val all = CORE_SITES.toSet() + listedSites(context)
        val parts = site.split('.')
        return parts.indices.any { parts.drop(it).joinToString(".") in all }
    }

    /**
     * Lo que se abre aunque caiga adentro de las bloqueadas. Iniciar sesión en
     * Google desde Chrome pasa por accounts.youtube.com para dejar la sesión
     * puesta también en YouTube, y sin esto el ingreso queda a medias.
     */
    val SITE_EXCEPTIONS = listOf("accounts.youtube.com")

    /**
     * Apps que no se abren nunca. Además de éstas, cualquier navegador que no
     * sea Chrome ([Vetoed]): Chrome es el único con políticas.
     */
    val APPS = setOf(
        // YouTube y sus variantes
        "com.google.android.youtube",
        "com.google.android.apps.youtube.kids",
        "app.revanced.android.youtube",
        "com.vanced.android.youtube",
        "org.schabi.newpipe",
        // TikTok
        "com.zhiliaoapp.musically",
        "com.zhiliaoapp.musically.go",
        "com.ss.android.ugc.trill",
        // Instagram y Threads
        "com.instagram.android",
        "com.instagram.lite",
        "com.instagram.barcelona",
        // Facebook (Messenger no: es para hablar, como WhatsApp)
        "com.facebook.katana",
        "com.facebook.lite",
        "com.twitter.android",
        "com.reddit.frontpage",
        "tv.twitch.android.app",
        // Streaming
        "com.netflix.mediaclient",
        "com.disney.disneyplus",
        "com.wbd.stream",
        "com.hbo.hbonow",
        "com.amazon.avod.thirdpartyclient",
        "com.cbs.app",
        "tv.pluto.android",
        "com.crunchyroll.crunchyroid",
        "com.mubi",
    )

    /**
     * Un solo candado para poner y sacar el nivel 2: si una revisión (la del
     * arranque, una app que se instala) se cruzara con la salida por cable,
     * volvería a pausar todo justo antes de que Slowtown se suelte, y esas
     * pausas quedarían sin nadie que las pueda sacar. No es el de este objeto:
     * ése lo toma [listedSites], adentro de [Chrome.apply].
     */
    private val lock = Any()

    /**
     * Pone el nivel 2 (y las páginas del nivel 1, que viajan en las mismas
     * políticas de Chrome). Sin importar si Slowtown está activo o en pausa.
     * Hace consultas al sistema: fuera del hilo principal.
     */
    fun enforce(context: Context) {
        synchronized(lock) {
            if (!Policies.isDeviceOwner(context)) return
            // Cada mitad por su lado: si Chrome falla, las apps se pausan igual.
            runCatching { Chrome.apply(context, Prefs(context).blockedSites) }
                .onFailure { Log.e(TAG, "No pude poner las políticas de Chrome", it) }
            runCatching { Vetoed.enforce(context) }
                .onFailure { Log.e(TAG, "No pude pausar las apps del nivel 2", it) }
        }
    }

    /** Sólo las páginas, al cambiar las del nivel 1. Fuera del hilo principal. */
    fun applySites(context: Context) {
        synchronized(lock) {
            if (Policies.isDeviceOwner(context)) Chrome.apply(context, Prefs(context).blockedSites)
        }
    }

    /**
     * La salida por cable: saca el nivel 2 y deja de ser Device Owner, sin que
     * una revisión se cruce. Devuelve las apps que se pueden abrir otra vez.
     */
    fun release(context: Context): List<String> {
        synchronized(lock) {
            Chrome.clear(context)
            val restored = Vetoed.undo(context)
            Policies.release(context)
            return restored
        }
    }

    private const val TAG = "Slowtown"
}
