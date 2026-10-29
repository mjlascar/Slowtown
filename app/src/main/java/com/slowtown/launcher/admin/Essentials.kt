package com.slowtown.launcher.admin

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Telephony
import android.telecom.InCallService
import android.telecom.TelecomManager

/**
 * Los paquetes que van siempre en la lista de lock task aunque no estén en
 * Inicio. Se leen del sistema en cada teléfono, porque cada marca pone la
 * pantalla de llamada, el marcador de emergencia y las alertas en paquetes
 * distintos. La lista fija es el piso, por si alguna consulta no devuelve nada.
 */
object Essentials {

    /** Sin estos, una llamada suena pero no se atiende, o una alerta no se ve. */
    private val FLOOR = setOf(
        "com.android.phone",
        "com.android.server.telecom",
        "com.samsung.android.incallui",
        "com.samsung.android.dialer",
        "com.samsung.android.messaging",
        "com.android.dialer",
        "com.google.android.dialer",
        "com.android.messaging",
        "com.google.android.apps.messaging",
        // Alertas de emergencia: en Android 12 lock task no las deja pasar
        // solas (la excepción recién llegó en Android 13).
        "com.android.cellbroadcastreceiver",
        "com.android.cellbroadcastreceiver.module",
        "com.google.android.cellbroadcastreceiver",
    )

    /**
     * Pantallas del sistema que las apps abren dentro de su propia tarea: el
     * selector de «Compartir» y «Abrir con» (paquete `android`), los permisos,
     * el selector de archivos y el inicio de sesión de Google. Con
     * BLOCK_ACTIVITY_START_IN_TASK, lo que no está en la lista se bloquea
     * también adentro de una app que se puede abrir.
     *
     * Contracara: la lista es por paquete, así que cualquier pantalla de estos
     * paquetes se puede abrir, también como tarea nueva (por ejemplo, desde un
     * link `intent://` en Chrome). El que más expone es
     * `com.google.android.gms`, con pantallas de cuenta y de ayuda que tienen
     * vistas web. Se deja porque sin él se bloquean el diálogo de ubicación de
     * Maps y Uber, «Continuar con Google» y el autocompletado de códigos por
     * SMS. Probar en el S10e si alguna lleva a un navegador; el filtro de DNS
     * es la red de abajo.
     */
    private val HELPERS = setOf(
        "android",
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
        "com.android.documentsui",
        "com.google.android.documentsui",
        "com.google.android.gms",
    )

    private const val ACTION_DIAL_EMERGENCY = "com.android.phone.EmergencyDialer.DIAL"
    private const val ACTION_CALL_EMERGENCY = "android.intent.action.CALL_EMERGENCY"
    private const val ACTION_EMERGENCY_ALERT = "android.provider.action.SMS_EMERGENCY_CB_RECEIVED"

    fun packages(context: Context): Set<String> = buildSet {
        addAll(FLOOR)
        addAll(HELPERS)
        val telecom = context.getSystemService(TelecomManager::class.java)
        telecom?.systemDialerPackage?.let(::add)
        telecom?.defaultDialerPackage?.let(::add)
        Telephony.Sms.getDefaultSmsPackage(context)?.let(::add)
        addAll(inCallScreens(context))
        addAll(systemActivities(context, Intent(ACTION_DIAL_EMERGENCY)))
        addAll(systemActivities(context, Intent(ACTION_CALL_EMERGENCY)))
        addAll(systemReceivers(context, Intent(ACTION_EMERGENCY_ALERT)))
    }

    /**
     * Las apps que dibujan la pantalla de llamada. En un Pixel es el mismo
     * marcador; en Samsung es otro paquete (InCallUI), y sin él la llamada
     * entra pero no se puede atender.
     */
    // Las variantes con *Flags recién existen en API 33; el S10e es API 31.
    @Suppress("DEPRECATION")
    private fun inCallScreens(context: Context): List<String> =
        context.packageManager
            .queryIntentServices(Intent(InCallService.SERVICE_INTERFACE), PackageManager.GET_META_DATA)
            .filter { it.serviceInfo.metaData?.getBoolean(TelecomManager.METADATA_IN_CALL_SERVICE_UI) == true }
            .map { it.serviceInfo.packageName }

    @Suppress("DEPRECATION")
    private fun systemActivities(context: Context, intent: Intent): List<String> =
        context.packageManager
            .queryIntentActivities(intent, PackageManager.MATCH_SYSTEM_ONLY)
            .map { it.activityInfo.packageName }

    @Suppress("DEPRECATION")
    private fun systemReceivers(context: Context, intent: Intent): List<String> =
        context.packageManager
            .queryBroadcastReceivers(intent, PackageManager.MATCH_SYSTEM_ONLY)
            .map { it.activityInfo.packageName }
}
