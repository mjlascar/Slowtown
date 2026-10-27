package com.slowtown.launcher.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.util.Log
import com.slowtown.launcher.Prefs
import com.slowtown.launcher.admin.AdminSession
import com.slowtown.launcher.admin.Guard
import com.slowtown.launcher.admin.Policies
import com.slowtown.launcher.admin.Rules
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors

/** Lo que Inicio y la administración muestran de la actualización ([Updater]). */
sealed interface UpdateState {
    /** Nada que decir: al día, todavía sin buscar, o una versión de cable. */
    data object None : UpdateState

    /** Buscando a mano, desde la administración. */
    data object Checking : UpdateState

    /** Buscó a mano y no hay nada más nuevo. */
    data object UpToDate : UpdateState

    /** Buscó a mano y no pudo, y no se conocía una versión nueva de antes. */
    data object CheckFailed : UpdateState

    /** Hay una versión nueva: es lo único que muestra Inicio. */
    sealed interface Pending : UpdateState {
        val version: String
    }

    data class Available(override val version: String) : Pending

    /** Sin Device Owner, falta que Android deje a Slowtown instalar apps. */
    data class NeedsPermission(override val version: String) : Pending

    data class Downloading(override val version: String, val percent: Int) : Pending

    /** [waiting]: Android espera que se confirme en su pantalla, y tocar la vuelve a abrir. */
    data class Installing(override val version: String, val waiting: Boolean = false) : Pending

    /** [detail]: lo que dijeron Android o la red, para la administración. */
    data class Failed(override val version: String, val detail: String?) : Pending
}

/**
 * Slowtown se actualiza sola desde las releases de GitHub (Marcos no quiere
 * conectar el cable cada vez). Al volver a Inicio, cada 6 horas como mucho,
 * busca la última; la baja recién cuando se toca «Actualizar», verifica que
 * sea Slowtown, más nueva y firmada con la misma clave, y se la pasa al
 * instalador de Android a nombre de Slowtown.
 *
 * Con Device Owner instala sin preguntar, también con Slowtown activo: no hace
 * falta ninguna pantalla. Sin él, Android pide antes que se deje a Slowtown
 * «instalar apps desconocidas», y puede pedir confirmación.
 *
 * Sólo en lo firmado con la clave de las releases: la variante de cable, con
 * la clave personal, no podría instalar una release encima.
 *
 * Es también un camino para cambiar el nivel 2: lo cambia una release nueva.
 * Marcos lo aceptó (23/10): publicar una exige escribir en el repo.
 */
object Updater {
    /** La huella SHA-256 del certificado de las releases (scripts/firma-releases.sha256). */
    private const val RELEASE_CERT = "3ef7e1d4a3e180864607d49a7c2ac38b0190d03b94f0c3e95d897e066bb25559"

    /** Como mucho, una búsqueda sola cada 6 horas: sin cuenta, GitHub contesta 60 por hora. */
    private const val CHECK_EVERY_MS = 6 * 60 * 60_000L

    private const val TAG = "Slowtown"
    private const val DIR = "actualizacion"
    private const val NO_SESSION = -1
    private const val CREATING = -2

    private val _state = MutableStateFlow<UpdateState>(UpdateState.None)
    val state: StateFlow<UpdateState> = _state

    /** Buscar, bajar e instalar: de a uno, en su hilo. */
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "actualizacion") }

    /** La pantalla de confirmación de Android, mientras la espera: tocar «Instalando…» la vuelve a abrir. */
    @Volatile
    private var confirmation: Intent? = null

    /**
     * La sesión del instalador en curso, para no confundir su resultado con el
     * de una vieja que se descarta. [NO_SESSION]: no se sabe (Slowtown arrancó
     * de nuevo), y vale cualquiera.
     */
    @Volatile
    private var session = NO_SESSION

    /** Slowtown como está instalada. No cambia mientras corre: actualizarla la cierra. */
    private class Installed(val version: String, val code: Long, val signers: Set<String>)

    @Volatile
    private var cached: Installed? = null

    // La variante con PackageInfoFlags recién existe en API 33.
    @Suppress("DEPRECATION")
    private fun installed(context: Context): Installed = cached ?: context.packageManager
        .getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        .let { Installed(it.versionName.orEmpty(), it.longVersionCode, signers(it)) }
        .also { cached = it }

    /** La versión instalada, «0.2.1». */
    fun version(context: Context): String = runCatching { installed(context).version }.getOrDefault("")

    /**
     * Si se actualiza sola: sólo firmada con la clave de las releases. La de
     * cable, con la clave personal, se actualiza por cable.
     */
    fun enabled(context: Context): Boolean =
        runCatching { installed(context).signers == setOf(RELEASE_CERT) }.getOrDefault(false)

    /**
     * Al volver a Inicio: muestra lo que ya se sabe y, si pasaron 6 horas,
     * busca en su hilo. No traba Inicio ni tira.
     */
    fun onHome(context: Context) {
        val app = context.applicationContext
        worker.execute {
            runCatching {
                if (!enabled(app)) return@runCatching
                settle(::idle, newer(app)?.let { UpdateState.Available(it.version) } ?: UpdateState.None)
                if (due(app)) fetchLatest(app, manual = false)
            }.onFailure { Log.w(TAG, "No pude revisar si hay una versión nueva", it) }
        }
    }

    /** «Buscar actualización», en la administración: ahora, sin esperar las 6 horas. */
    fun checkNow(context: Context) {
        val app = context.applicationContext
        val before = _state.value
        if (!enabled(app) || busy(before) || !_state.compareAndSet(before, UpdateState.Checking)) return
        worker.execute {
            runCatching { fetchLatest(app, manual = true) }.onFailure {
                Log.w(TAG, "No pude buscar una versión nueva", it)
                settle({ s -> s == UpdateState.Checking }, UpdateState.CheckFailed)
            }
        }
    }

    /**
     * Tocar el renglón de la actualización: la baja, la verifica y la instala.
     * Si Android espera la confirmación, la vuelve a abrir (por si no se vio,
     * o se volvió a Inicio sin contestarla).
     */
    fun update(context: Context) {
        val now = _state.value
        if (now is UpdateState.Installing) {
            confirmation?.let { confirm(context, it) }
            return
        }
        if (now !is UpdateState.Available && now !is UpdateState.NeedsPermission && now !is UpdateState.Failed) return
        val version = (now as? UpdateState.Pending)?.version ?: return
        // Sin Device Owner, Android instala sólo si se dejó a Slowtown instalar
        // apps. Con Device Owner no hace falta, y con Slowtown activo esa
        // pantalla de Ajustes no se abriría.
        if (!Policies.isDeviceOwner(context) && !context.packageManager.canRequestPackageInstalls()) {
            _state.value = UpdateState.NeedsPermission(version)
            // Sin cable, la guardia deja pasar esa pantalla de Ajustes en una
            // sesión chica, como la información de una app.
            if (Rules.mode(context) == Rules.Mode.GUARD) {
                AdminSession.start(context, Prefs(context).allowedPackages, infoOnly = true)
            }
            show(
                context,
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")),
                "el permiso para instalar apps",
            )
            return
        }
        _state.value = UpdateState.Downloading(version, 0)
        val app = context.applicationContext
        worker.execute { deliver(app) }
    }

    /** Lo que una búsqueda puede reemplazar: no lo que está en curso ni lo que espera que se toque. */
    private fun idle(state: UpdateState): Boolean =
        state == UpdateState.None || state == UpdateState.UpToDate ||
            state == UpdateState.CheckFailed || state is UpdateState.Available

    private fun busy(state: UpdateState): Boolean =
        state == UpdateState.Checking || state is UpdateState.Downloading || state is UpdateState.Installing

    /** Pasa a [next] sólo desde un estado que cumpla [from]: si no, lo cambió otro mientras tanto. */
    private fun settle(from: (UpdateState) -> Boolean, next: UpdateState) =
        _state.update { if (from(it)) next else it }

    private fun due(context: Context): Boolean {
        val since = System.currentTimeMillis() - prefs(context).getLong(KEY_CHECKED_AT, 0L)
        // Negativo si la hora del teléfono volvió para atrás: mejor buscar.
        return since !in 0 until CHECK_EVERY_MS
    }

    /**
     * Le pregunta a GitHub por la última release. Cuenta como búsqueda aunque
     * falle: sin conexión, no reintenta en cada vuelta a Inicio. [manual]: desde
     * la administración, que muestra cómo le fue.
     */
    private fun fetchLatest(context: Context, manual: Boolean) {
        prefs(context).edit().putLong(KEY_CHECKED_AT, System.currentTimeMillis()).apply()
        val found = runCatching { Releases.latest(userAgent(context)) }
            .onSuccess { remember(context, it) }
            .onFailure { Log.w(TAG, "No pude buscar una versión nueva", it) }
        // Si no pudo buscar, vale la versión nueva que se conocía de antes.
        val next = newer(context)?.let { UpdateState.Available(it.version) } ?: when {
            !manual -> UpdateState.None
            found.isFailure -> UpdateState.CheckFailed
            else -> UpdateState.UpToDate
        }
        if (manual) settle({ it == UpdateState.Checking }, next) else settle(::idle, next)
    }

    /** Baja, verifica e instala la versión nueva conocida. Cómo termina lo cuenta [Result]. */
    private fun deliver(context: Context) {
        val release = runCatching { newer(context) }.getOrNull()
        if (release == null) {
            _state.value = UpdateState.None
            return
        }
        val dir = File(context.cacheDir, DIR)
        runCatching {
            dir.deleteRecursively()
            dir.mkdirs()
            val apk = File(dir, "Slowtown-${release.version}.apk")
            Releases.download(release, apk, userAgent(context)) {
                _state.value = UpdateState.Downloading(release.version, it)
            }
            verify(context, apk)
            _state.value = UpdateState.Installing(release.version)
            commit(context, apk)
        }.onFailure {
            Log.w(TAG, "No se pudo actualizar a la ${release.version}", it)
            _state.value = UpdateState.Failed(release.version, it.message ?: it.javaClass.simpleName)
        }
        // El instalador ya tiene su copia.
        runCatching { dir.deleteRecursively() }
    }

    /**
     * Que el APK sea Slowtown, más nueva y firmada con la misma clave que la
     * instalada. Android igual rechaza otra firma al instalar encima, pero así
     * al instalador no le llega nada que no sea Slowtown.
     */
    // La variante con PackageInfoFlags recién existe en API 33.
    @Suppress("DEPRECATION")
    private fun verify(context: Context, apk: File) {
        val own = installed(context)
        val info = context.packageManager.getPackageArchiveInfo(apk.path, PackageManager.GET_SIGNING_CERTIFICATES)
            ?: error("Android no pudo leer el APK")
        check(info.packageName == context.packageName) { "El APK es de ${info.packageName}" }
        check(signers(info).let { it.isNotEmpty() && it == own.signers }) { "El APK está firmado con otra clave" }
        check(info.longVersionCode > own.code) { "El APK no es más nuevo que la versión instalada" }
    }

    /**
     * Le pasa el APK al instalador de Android a nombre de Slowtown. Con Device
     * Owner instala sin preguntar; sin él, sin preguntar si Android lo deja (ver
     * el manifest) o con su confirmación. Al instalar, Android cierra Slowtown y
     * la versión nueva arranca de cero.
     */
    private fun commit(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        // Lo que haya quedado de un intento anterior: Android deja pocas sesiones abiertas por app.
        session = CREATING
        installer.mySessions.forEach { runCatching { installer.abandonSession(it.sessionId) } }
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
            setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        session = id
        try {
            installer.openSession(id).use { staged ->
                apk.inputStream().use { input ->
                    staged.openWrite("Slowtown.apk", 0, apk.length()).use { output ->
                        input.copyTo(output)
                        staged.fsync(output)
                    }
                }
                staged.commit(resultIntent(context, id).intentSender)
            }
        } catch (e: Exception) {
            runCatching { installer.abandonSession(id) }
            throw e
        }
    }

    // Mutable, para que Android le agregue el resultado; explícito (con la
    // clase), como pide Android 14 a las mutables.
    private fun resultIntent(context: Context, id: Int): PendingIntent = PendingIntent.getBroadcast(
        context,
        id,
        Intent(context, Result::class.java),
        PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /**
     * El resultado del instalador de Android. Si pide confirmar, abre su
     * pantalla: Android deja abrir pantallas desde atrás al Inicio y al Device
     * Owner, y si no, Inicio está adelante porque se acaba de tocar.
     */
    class Result : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            runCatching { finished(context.applicationContext, intent) }
                .onFailure { Log.e(TAG, "No pude leer el resultado de la actualización", it) }
        }
    }

    // getParcelableExtra con la clase recién existe en API 33.
    @Suppress("DEPRECATION")
    private fun finished(context: Context, intent: Intent) {
        val id = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, NO_SESSION)
        val current = session
        // El de una sesión vieja, descartada al empezar otra.
        if (current != NO_SESSION && id != current) return
        val version = (_state.value as? UpdateState.Pending)?.version ?: known(context)?.version ?: return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
            if (confirm != null) {
                confirmation = confirm
                _state.value = UpdateState.Installing(version, waiting = true)
                confirm(context, confirm)
                return
            }
        }
        confirmation = null
        session = NO_SESSION
        if (status == PackageInstaller.STATUS_SUCCESS) {
            // Casi nunca se ve: Android cierra Slowtown para reemplazarla, y
            // esto lo recibe la versión nueva.
            _state.value = UpdateState.None
            return
        }
        // Cancelar la confirmación también llega acá (STATUS_FAILURE_ABORTED), y
        // un rechazo de Play Protect puede llegar igual: lo que dijo Android
        // queda a la vista en la administración.
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        Log.w(TAG, "Android no instaló la $version: estado $status, $message")
        _state.value = UpdateState.Failed(version, message ?: "Android contestó $status")
    }

    /** La confirmación del instalador de Android: sin cable, la guardia la deja pasar porque la abre Slowtown. */
    private fun confirm(context: Context, intent: Intent) {
        Guard.allowInstaller()
        show(context, intent, "la confirmación de Android")
    }

    /** Abre una pantalla de Android, también desde el resultado del instalador, que no es una actividad. */
    private fun show(context: Context, intent: Intent, what: String) {
        runCatching { context.startActivity(Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { Log.w(TAG, "No pude abrir $what", it) }
    }

    /** Las huellas SHA-256 de los certificados que firman el APK, como las muestra apksigner. */
    private fun signers(info: PackageInfo): Set<String> =
        info.signingInfo?.apkContentsSigners.orEmpty().mapTo(mutableSetOf()) { signature ->
            MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())
                .joinToString("") { "%02x".format(it) }
        }

    private fun userAgent(context: Context): String = "Slowtown/${version(context)}"

    /** La versión nueva conocida, si es más nueva que la instalada. */
    private fun newer(context: Context): Releases.Release? =
        known(context)?.takeIf { it.code > installed(context).code }

    /** Lo que se sabe de la última release y cuándo se buscó, en su propio archivo: sobrevive a un reinicio. */
    private fun prefs(context: Context) = context.getSharedPreferences("slowtown_update", Context.MODE_PRIVATE)

    private fun known(context: Context): Releases.Release? {
        val prefs = prefs(context)
        val version = prefs.getString(KEY_VERSION, null) ?: return null
        val url = prefs.getString(KEY_URL, null) ?: return null
        return Releases.Release(version, prefs.getLong(KEY_CODE, 0L), url, prefs.getLong(KEY_SIZE, 0L))
    }

    private fun remember(context: Context, release: Releases.Release?) {
        val edit = prefs(context).edit()
        if (release == null) {
            edit.remove(KEY_VERSION).remove(KEY_CODE).remove(KEY_URL).remove(KEY_SIZE)
        } else {
            edit.putString(KEY_VERSION, release.version)
                .putLong(KEY_CODE, release.code)
                .putString(KEY_URL, release.url)
                .putLong(KEY_SIZE, release.size)
        }
        edit.apply()
    }

    private const val KEY_CHECKED_AT = "checked_at"
    private const val KEY_VERSION = "version"
    private const val KEY_CODE = "code"
    private const val KEY_URL = "url"
    private const val KEY_SIZE = "size"
}
