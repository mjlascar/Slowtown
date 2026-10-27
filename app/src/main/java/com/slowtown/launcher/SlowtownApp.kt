package com.slowtown.launcher

import android.annotation.SuppressLint
import android.app.Application
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.util.Log
import com.slowtown.launcher.admin.Bedtime
import com.slowtown.launcher.admin.CallGuard
import com.slowtown.launcher.admin.Fixed
import com.slowtown.launcher.admin.Policies
import com.slowtown.launcher.security.CrashGuard
import java.util.concurrent.Executors
import kotlin.concurrent.thread

class SlowtownApp : Application() {

    /**
     * Una app que se instala o se vuelve a habilitar (con Play abierto en una
     * sesión, por ejemplo) se revisa enseguida: si es del nivel 2, se pausa
     * antes de que nadie la abra. Y si fue Chrome, vuelven sus políticas.
     * Slowtown es el Inicio, así que su proceso vive casi siempre; si no, la
     * revisión de cada arranque la agarra igual.
     */
    private val packages = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.data?.schemeSpecificPart == packageName) return
            val pending = goAsync()
            thread(name = "nivel-2") {
                try {
                    runCatching { Fixed.enforce(this@SlowtownApp) }
                        .onFailure { Log.e(TAG, "No pude revisar las apps nuevas", it) }
                } finally {
                    pending.finish()
                }
            }
        }
    }

    /**
     * Recién hecho Device Owner con el proceso ya andando: si Slowtown ya era
     * el Inicio, la instalación de provisionar.sh la vuelve a abrir antes de
     * `dpm set-device-owner`, y la revisión del arranque no pudo poner nada.
     * Si estaba activa de antes (liberada con --forzar), se rearma también la
     * política, y se cierra en la próxima vuelta a Inicio.
     */
    private val owner = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val pending = goAsync()
            thread(name = "nivel-2") {
                try {
                    runCatching {
                        Fixed.enforce(this@SlowtownApp)
                        val prefs = Prefs(this@SlowtownApp)
                        if (prefs.closed && !CrashGuard.inSafeMode(this@SlowtownApp)) {
                            Policies.apply(this@SlowtownApp, prefs.allowedPackages)
                        }
                    }.onFailure { Log.e(TAG, "No pude poner el nivel 2", it) }
                } finally {
                    pending.finish()
                }
            }
        }
    }

    /**
     * La noche no corta llamadas ([CallGuard]). Cada cambio del modo de audio
     * (suena, se atiende, se corta) rearma la lista: al cortar, el audio
     * vuelve a normal y se achica; al empezar, queda agendada la mirada de
     * cada minuto de [Bedtime.refresh]. Consulta al sistema y puede esperar
     * unos segundos: en su hilo, de a uno.
     */
    private val callThread = Executors.newSingleThreadExecutor { Thread(it, "llamada") }
    private val callMode = AudioManager.OnModeChangedListener { mode ->
        runCatching { Bedtime.callChanged(this@SlowtownApp, ended = mode == AudioManager.MODE_NORMAL) }
            .onFailure { Log.e(TAG, "No pude rearmar la lista al cambiar una llamada", it) }
    }

    /**
     * La noche rige con el teléfono en uso ([Bedtime.inUse]): al apagar la
     * pantalla vuelve la lista de siempre, para que una llamada pueda abrir
     * la suya, y al desbloquear se achica. La guardia ([Guard]) también mira
     * sólo con el teléfono en uso: para al apagar la pantalla y arranca al
     * desbloquear ([Bedtime.refresh] la avisa). Consulta al sistema: en otro hilo.
     */
    private val screen = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val pending = goAsync()
            thread(name = "pantalla") {
                try {
                    runCatching { Bedtime.refresh(this@SlowtownApp) }
                        .onFailure { Log.e(TAG, "No pude rearmar la lista al prender o apagar la pantalla", it) }
                } finally {
                    pending.finish()
                }
            }
        }
    }

    // RECEIVER_NOT_EXPORTED es de API 33; en Android 12 el sistema lo ignora, y
    // estos avisos los manda sólo el sistema igual.
    @SuppressLint("InlinedApi")
    override fun onCreate() {
        super.onCreate()
        // Lo primero de todo, antes de cualquier pantalla: si Slowtown viene de
        // colgarse varias veces seguidas, el teléfono queda abierto.
        runCatching { if (CrashGuard.check(this)) Policies.relax(this) }
            .onFailure { Log.e(TAG, "La guardia de crashes falló", it) }
        runCatching { Policies.restoreDefaults(this) }
            .onFailure { Log.e(TAG, "No pude restaurar la copia de seguridad", it) }
        // Antes de la revisión del arranque, así una de las dos lo agarra: si ya
        // era Device Owner al registrarse, la revisión lo ve; si no, llega el aviso.
        registerReceiver(
            owner,
            IntentFilter(DevicePolicyManager.ACTION_DEVICE_OWNER_CHANGED),
            RECEIVER_NOT_EXPORTED,
        )
        val screenFilter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        registerReceiver(screen, screenFilter, RECEIVER_NOT_EXPORTED)
        // El modo de audio lo lee cualquier app: no pide permisos.
        runCatching { getSystemService(AudioManager::class.java).addOnModeChangedListener(callThread, callMode) }
            .onFailure { Log.e(TAG, "No pude escuchar las llamadas", it) }
        // El nivel 2 en cada arranque (un reinicio, una actualización de
        // Slowtown), también con la guardia de crashes abierta: no traba nada.
        // Recorre todas las apps: fuera del hilo principal.
        thread(name = "nivel-2") {
            runCatching { Fixed.enforce(this) }
                .onFailure { Log.e(TAG, "No pude poner el nivel 2", it) }
            // Un reinicio borra las alarmas: los horarios se vuelven a agendar.
            runCatching { Bedtime.schedule(this) }
                .onFailure { Log.e(TAG, "No pude agendar los horarios", it) }
            // Y si Slowtown estaba activo sin cable, la guardia vuelve a mirar.
            runCatching { Bedtime.refresh(this) }
                .onFailure { Log.e(TAG, "No pude poner al día lo que se puede abrir", it) }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        registerReceiver(packages, filter, RECEIVER_NOT_EXPORTED)
    }

    private companion object {
        const val TAG = "Slowtown"
    }
}
