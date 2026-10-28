package com.slowtown.launcher.admin

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.provider.Browser
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import com.slowtown.launcher.HomeActivity
import com.slowtown.launcher.security.CrashGuard

/**
 * Las páginas bloqueadas sin cable ([Pages]): un servicio de accesibilidad que
 * mira la barra de direcciones de Chrome y, si muestra una página bloqueada,
 * vuelve atrás y lo dice. Viene apagado y lo prende el usuario en Ajustes,
 * después de leer en «Páginas bloqueadas» qué mira y para qué: Slowtown no lo
 * puede prender solo.
 *
 * Mira sólo Chrome (res/xml/page_guard.xml) y, de Chrome, sólo la barra de
 * direcciones mientras no se escribe en ella: nada se guarda ni sale del
 * teléfono. Con Device Owner no hace nada, porque las políticas de Chrome ya
 * bloquean, y mejor: antes de que la página cargue. Tampoco en el modo a prueba
 * de fallos ([CrashGuard]), por si el que se cuelga es él.
 *
 * Avisa apenas la ve, pero vuelve atrás un segundo después: mientras la
 * página entra, Atrás no tiene adónde volver y saca a Chrome entero (lo vio
 * Marcos en el S10e). Si en ese segundo la barra se esconde (la página se
 * corrió), vuelve atrás igual: es la misma página. Si Atrás no la saca (la
 * pestaña empezó ahí, o Chrome se reabre en ella), abre Google en la pestaña de
 * Slowtown, siempre la misma; y si vuelve una y otra vez, va a Inicio. Así
 * Chrome nunca queda trabado en una bloqueada.
 *
 * Mirar la barra es preguntarle a Chrome, que puede tardar: se hace en un hilo
 * aparte, no en el de Inicio. Todo lo de abajo corre en ese hilo.
 */
class PageGuard : AccessibilityService() {
    /** La última por la que hizo algo (Atrás, Google, Inicio), y cuándo. */
    private var actedSite: String? = null
    private var actedAt = 0L

    /** La que vio y espera a que termine de entrar para volver atrás. */
    private var pendingSite: String? = null

    /** Cuántas veces se volvió a mirar sin poder ver Chrome ([pendingSite]). */
    private var pendingTries = 0

    /**
     * Hasta cuándo no mirar lo que avisa Chrome: lo que hizo recién todavía
     * está pasando (la página entra, Chrome vuelve atrás o carga Google). A esa
     * hora se vuelve a mirar ([recheck]); los avisos del medio no cuentan.
     */
    private var quietUntil = 0L

    /** La que se dejó con Atrás: si Chrome se vuelve a abrir en ella, Atrás no alcanzó. */
    private var backedOut: String? = null

    /** Cuándo actuó, para darse cuenta si entra en una vuelta sin fin. */
    private val recent = ArrayDeque<Long>()
    private var toldSite: String? = null
    private var toldAt = 0L
    private var idleCheckedAt = 0L
    private var idle = false

    private val thread = HandlerThread("paginas").apply { start() }
    private val worker = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())

    /**
     * Lo que avisó Chrome desde la última vez que se miró: cuando avisa muchas
     * veces seguidas, se mira una sola. Lo comparten los dos hilos.
     */
    private val lock = Any()
    private var nextPackage: String? = null
    private var nextOpened = false
    private var nextCustomTab = false
    private val drain = Runnable {
        val (pkg, opened, customTab) = synchronized(lock) {
            Triple(nextPackage, nextOpened, nextCustomTab).also {
                nextPackage = null
                nextOpened = false
                nextCustomTab = false
            }
        }
        if (pkg != null) safely { check(pkg, opened, customTab, rechecking = false) }
    }

    /** La vuelta a mirar, aunque Chrome no avise nada (ver [quietUntil]). */
    private var recheckPackage: String? = null
    private val recheck = Runnable {
        recheckPackage?.let { pkg -> safely { check(pkg, opened = false, customTab = false, rechecking = true) } }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return
        val opened = event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        // Un enlace abierto desde otra app va en una pestaña personalizada, que
        // Atrás cierra y vuelve a esa app: siempre alcanza.
        val customTab = opened && event.className?.contains("customtabs", ignoreCase = true) == true
        synchronized(lock) {
            val idleNow = nextPackage == null
            nextPackage = pkg
            nextOpened = nextOpened || opened
            nextCustomTab = nextCustomTab || customTab
            if (idleNow) worker.post(drain)
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        worker.removeCallbacksAndMessages(null)
        main.removeCallbacksAndMessages(null)
        thread.quitSafely()
        super.onDestroy()
    }

    /** Corre en el proceso de Inicio: que una falla acá no lo tire abajo. */
    private inline fun safely(block: () -> Unit) {
        runCatching(block).onFailure { Log.w(TAG, "No pude revisar la página de Chrome", it) }
    }

    private fun check(pkg: String, opened: Boolean, customTab: Boolean, rechecking: Boolean) {
        if (pkg !in Pages.browsers(this) || idle()) return
        val root = rootInActiveWindow
        if (root == null) {
            // Entre una ventana y otra no hay nada que leer: un rato más.
            if (rechecking && pendingSite != null && ++pendingTries <= MAX_TRIES) later(pkg, ARRIVE_MS)
            return
        }
        if (root.packageName?.toString() != pkg) {
            // Chrome ya no está adelante: lo pendiente no corre.
            if (rechecking) pendingSite = null
            return
        }
        val bar = root.findAccessibilityNodeInfosByViewId("$pkg:id/url_bar").firstOrNull { it.isVisibleToUser }
        if (bar == null) {
            // La barra se escondió porque la página se corrió (cambiar de página
            // la vuelve a mostrar): sigue siendo la que se estaba esperando.
            pendingSite?.let { if (rechecking) react(pkg, it, reopened = false, customTab = false, rechecking = true) }
            return
        }
        // Mientras se escribe, lo que dice es lo que se está escribiendo, no la página.
        if (bar.isFocused) return
        val host = Chrome.normalizeSite(bar.text?.toString() ?: return) ?: return
        val site = Pages.blocked(this, host)
        if (site == null) {
            pendingSite = null
            if (opened) backedOut = null
            return
        }
        react(pkg, site, reopened = opened && !customTab, customTab, rechecking)
    }

    private fun react(pkg: String, site: String, reopened: Boolean, customTab: Boolean, rechecking: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (!rechecking && now < quietUntil) return
        tell(site, now)
        // Ya hizo algo por ésta recién, y sigue ahí: Atrás no alcanzó.
        val stuck = site == actedSite && now - actedAt < RETRY_MS
        // Chrome se volvió a abrir en la que se dejó con Atrás.
        val again = reopened && site == backedOut
        if (!stuck && !again && !customTab && !rechecking) {
            // Recién la ve: que termine de entrar, y entonces Atrás vuelve a la anterior.
            pendingSite = site
            pendingTries = 0
            later(pkg, ARRIVE_MS)
            return
        }
        pendingSite = null
        while (recent.isNotEmpty() && now - recent.first() > LOOP_MS) recent.removeFirst()
        recent.addLast(now)
        actedSite = site
        actedAt = now
        when {
            recent.size > MAX_ACTIONS -> {
                recent.clear()
                goHome()
            }
            stuck || again -> openSafePage(pkg)
            else -> {
                backedOut = site
                performGlobalAction(GLOBAL_ACTION_BACK)
            }
        }
        // Pasado el rato, se vuelve a mirar: si sigue ahí, lo de arriba no alcanzó.
        later(pkg, QUIET_MS)
    }

    /** No mira lo que avise Chrome por [delay], y entonces vuelve a mirar. */
    private fun later(pkg: String, delay: Long) {
        quietUntil = SystemClock.elapsedRealtime() + delay
        recheckPackage = pkg
        worker.removeCallbacks(recheck)
        worker.postDelayed(recheck, delay)
    }

    /** Google, siempre en la misma pestaña (la de Slowtown): no se van juntando pestañas. */
    private fun openSafePage(pkg: String) {
        val google = Intent(Intent.ACTION_VIEW, Uri.parse(SAFE_PAGE))
            .setPackage(pkg)
            .putExtra(Browser.EXTRA_APPLICATION_ID, packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(google) }.onFailure { goHome() }
    }

    private fun goHome() {
        runCatching { startActivity(Intent(this, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { performGlobalAction(GLOBAL_ACTION_HOME) }
    }

    private fun tell(site: String, now: Long) {
        if (site == toldSite && now - toldAt < TELL_AGAIN_MS) return
        toldSite = site
        toldAt = now
        main.post { Toast.makeText(this, "$site está bloqueada.", Toast.LENGTH_SHORT).show() }
    }

    /** Con Device Owner o en el modo a prueba de fallos, nada; se mira cada tanto, no a cada cambio. */
    private fun idle(): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (idleCheckedAt == 0L || now - idleCheckedAt > IDLE_TTL_MS) {
            idleCheckedAt = now
            idle = Policies.isDeviceOwner(this) || CrashGuard.inSafeMode(this)
        }
        return idle
    }

    private companion object {
        const val TAG = "Slowtown"
        const val SAFE_PAGE = "https://www.google.com/"
        const val ARRIVE_MS = 1_000L
        const val QUIET_MS = 2_000L
        const val RETRY_MS = 5_000L
        const val MAX_TRIES = 5
        const val LOOP_MS = 15_000L
        const val MAX_ACTIONS = 5
        const val TELL_AGAIN_MS = 4_000L
        const val IDLE_TTL_MS = 30_000L
    }
}
