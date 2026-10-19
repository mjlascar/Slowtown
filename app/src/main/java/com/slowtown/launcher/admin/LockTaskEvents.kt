package com.slowtown.launcher.admin

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * Avisa cuando el sistema entra o sale de lock task. Hace falta porque
 * `startLockTask()` y `stopLockTask()` vuelven antes de que el cambio se
 * aplique: leer el estado justo después da el de antes.
 */
object LockTaskEvents {
    private val count = MutableStateFlow(0)
    val changes: StateFlow<Int> = count

    fun changed() = count.update { it + 1 }
}
