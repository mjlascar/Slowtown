package com.slowtown.launcher.admin

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.util.Log
import com.slowtown.launcher.Prefs

/**
 * La salida por cable desde adentro: `scripts/liberar.sh` le pide a Slowtown
 * que deshaga el nivel 2 y deje de ser Device Owner.
 *
 *   adb shell content call --uri content://com.slowtown.launcher.cable --method soltar
 *
 * Tiene que ser Slowtown: la pausa del nivel 2 queda a nombre de `android`, y
 * adb no la puede sacar (`pm unsuspend` sólo saca las suyas, probado en
 * Android 12). Sin Device Owner, nadie más podría.
 *
 * Cualquier app la puede llamar, pero sólo atiende a adb (shell) y a root: el
 * cable es lo único que no está a mano en el día a día.
 */
class CableProvider : ContentProvider() {

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val uid = Binder.getCallingUid()
        if (uid != SHELL_UID && uid != ROOT_UID) throw SecurityException("Sólo por cable (uid $uid)")
        val context = context?.applicationContext ?: return null
        return when (method) {
            METHOD_RELEASE -> {
                val restored = Fixed.undo(context)
                Policies.release(context)
                // En pausa: si se la vuelve a provisionar, no se cierra sola.
                Prefs(context).closed = false
                Log.i(TAG, "Soltado por cable; vuelven: $restored")
                Bundle().apply {
                    putBoolean("ok", !Policies.isDeviceOwner(context))
                    putString("vuelven", restored.joinToString(" "))
                }
            }
            else -> null
        }
    }

    override fun onCreate() = true
    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0

    private companion object {
        const val TAG = "Slowtown"
        const val METHOD_RELEASE = "soltar"
        // AID_SHELL y AID_ROOT: Process.SHELL_UID no es API pública.
        const val SHELL_UID = 2000
        const val ROOT_UID = 0
    }
}
