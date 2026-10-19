package com.slowtown.launcher

import android.app.Application
import android.util.Log
import com.slowtown.launcher.admin.Policies
import com.slowtown.launcher.security.CrashGuard

class SlowtownApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Lo primero de todo, antes de cualquier pantalla: si Slowtown viene de
        // colgarse varias veces seguidas, el teléfono queda abierto.
        runCatching { if (CrashGuard.check(this)) Policies.relax(this) }
            .onFailure { Log.e("Slowtown", "La guardia de crashes falló", it) }
    }
}
