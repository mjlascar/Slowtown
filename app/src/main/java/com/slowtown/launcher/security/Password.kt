package com.slowtown.launcher.security

import android.content.Context
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * La contraseña de administración, guardada derivada (PBKDF2 con sal) y no en
 * claro. Contra el dueño del teléfono esto no es una barrera criptográfica,
 * es fricción: lo que la sostiene es que con Slowtown activo no se llega a
 * Ajustes para borrarle los datos a Slowtown. Si se olvida, se sale por cable
 * (scripts/liberar.sh).
 */
object Password {
    const val MIN_LENGTH = 4
    private const val ITERATIONS = 30_000
    private const val FAILS_BEFORE_WAIT = 5
    private const val WAIT_MS = 60_000L

    sealed interface Result {
        data object Ok : Result
        data object Wrong : Result
        data class Locked(val seconds: Long) : Result
    }

    fun isSet(context: Context): Boolean = prefs(context).contains(KEY_HASH)

    /** Lenta a propósito (PBKDF2): llamarla fuera del hilo principal. */
    fun save(context: Context, password: String) {
        val salt = ByteArray(16).also(SecureRandom()::nextBytes)
        prefs(context).edit()
            .putString(KEY_SALT, encode(salt))
            .putString(KEY_HASH, encode(derive(password, salt)))
            .putInt(KEY_FAILS, 0)
            .putLong(KEY_WAIT_UNTIL, 0)
            .apply()
    }

    /** Lenta a propósito (PBKDF2): llamarla fuera del hilo principal. */
    fun check(context: Context, password: String): Result {
        val prefs = prefs(context)
        val now = System.currentTimeMillis()
        val waitUntil = prefs.getLong(KEY_WAIT_UNTIL, 0)
        if (now < waitUntil) return Result.Locked((waitUntil - now + 999) / 1000)

        val salt = prefs.getString(KEY_SALT, null)?.let(::decode)
        val hash = prefs.getString(KEY_HASH, null)?.let(::decode)
        if (salt != null && hash != null && MessageDigest.isEqual(hash, derive(password, salt))) {
            prefs.edit().putInt(KEY_FAILS, 0).apply()
            return Result.Ok
        }

        val fails = prefs.getInt(KEY_FAILS, 0) + 1
        val edit = prefs.edit().putInt(KEY_FAILS, fails)
        // Cada cinco errores, un minuto de espera: adivinar tiene que costar.
        if (fails % FAILS_BEFORE_WAIT == 0) edit.putLong(KEY_WAIT_UNTIL, now + WAIT_MS)
        edit.apply()
        return Result.Wrong
    }

    private fun derive(password: String, salt: ByteArray): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(password.toCharArray(), salt, ITERATIONS, 256))
            .encoded

    private fun encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    private fun decode(text: String): ByteArray = Base64.getDecoder().decode(text)

    private fun prefs(context: Context) = context.getSharedPreferences("password", Context.MODE_PRIVATE)

    private const val KEY_SALT = "salt"
    private const val KEY_HASH = "hash"
    private const val KEY_FAILS = "fails"
    private const val KEY_WAIT_UNTIL = "wait_until"
}
