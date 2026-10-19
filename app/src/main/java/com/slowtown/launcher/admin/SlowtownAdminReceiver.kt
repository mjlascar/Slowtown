package com.slowtown.launcher.admin

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/** El componente que `dpm set-device-owner` deja a cargo del teléfono. */
class SlowtownAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        Log.i(TAG, "Slowtown quedó como administrador del dispositivo")
    }

    override fun onLockTaskModeEntering(context: Context, intent: Intent, pkg: String) {
        Log.i(TAG, "Slowtown activo (lock task) por $pkg")
        LockTaskEvents.changed()
    }

    override fun onLockTaskModeExiting(context: Context, intent: Intent) {
        Log.i(TAG, "Slowtown en pausa (fin de lock task)")
        LockTaskEvents.changed()
    }

    private companion object {
        const val TAG = "Slowtown"
    }
}
