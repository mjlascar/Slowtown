package com.slowtown.launcher.update

import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Las releases de GitHub, que publica `.github/workflows/release.yml` con cada
 * etiqueta vX.Y.Z: cuál es la última y su APK. Con HttpURLConnection y el JSON
 * que trae Android, sin sumar bibliotecas. Todo bloquea: fuera del hilo
 * principal.
 */
internal object Releases {
    private const val LATEST = "https://api.github.com/repos/mjlascar/Slowtown/releases/latest"
    private const val TIMEOUT_MS = 20_000
    private const val MAX_REDIRECTS = 5

    /** Slowtown pesa unos 25 MB: algo mucho más grande no es Slowtown, y llenaría el teléfono. */
    private const val MAX_SIZE = 200L * 1024 * 1024

    private val VERSION_TAG = Regex("""v(\d+)\.(\d+)\.(\d+)""")

    /** Una release con su APK. [code] es el versionCode que le puso el workflow. */
    data class Release(val version: String, val code: Long, val url: String, val size: Long)

    /** vX.Y.Z → X·10000 + Y·100 + Z, como el workflow de releases. Null si la etiqueta es otra cosa. */
    fun versionCode(tag: String): Long? {
        val parts = VERSION_TAG.matchEntire(tag)?.groupValues?.drop(1)?.map { it.toLongOrNull() ?: return null }
            ?: return null
        val (major, minor, patch) = parts
        // Con 100 o más en el medio o al final, dos versiones darían el mismo número.
        if (minor >= 100 || patch >= 100) return null
        return major * 10_000 + minor * 100 + patch
    }

    /**
     * La última release publicada (GitHub no cuenta los borradores ni las de
     * prueba), o null si no trae el APK que corresponde. Tira si no hay
     * conexión o GitHub contesta otra cosa.
     */
    fun latest(userAgent: String): Release? {
        val connection = open(LATEST, "application/vnd.github+json", userAgent)
        val json = try {
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
        val release = JSONObject(json)
        val tag = release.getString("tag_name")
        val code = versionCode(tag) ?: return null
        val version = tag.removePrefix("v")
        val apk = "Slowtown-$version.apk"
        val assets = release.getJSONArray("assets")
        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            if (asset.optString("name") != apk) continue
            val size = asset.getLong("size")
            if (size !in 1..MAX_SIZE) return null
            return Release(version, code, asset.getString("browser_download_url"), size)
        }
        return null
    }

    /**
     * Baja el APK a [to] y avisa el por ciento a [onProgress]. Tira si no
     * llega entero, o si llegan más bytes de los que dice GitHub.
     */
    fun download(release: Release, to: File, userAgent: String, onProgress: (Int) -> Unit) {
        val connection = open(release.url, "application/octet-stream", userAgent)
        try {
            val length = connection.contentLengthLong
            check(length < 0 || length == release.size) { "El APK mide $length bytes y GitHub dice ${release.size}" }
            var read = 0L
            var shown = -1
            connection.inputStream.use { input ->
                to.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        read += n
                        check(read <= release.size) { "Llegaron más bytes de los que dice GitHub" }
                        output.write(buffer, 0, n)
                        val percent = (read * 100 / release.size).toInt()
                        if (percent != shown) {
                            shown = percent
                            onProgress(percent)
                        }
                    }
                }
            }
            check(read == release.size) { "Llegaron $read bytes de ${release.size}" }
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Abre [url] siguiendo las redirecciones a mano, sólo por https: el APK lo
     * sirve otro dominio de GitHub (hoy release-assets.githubusercontent.com).
     * El dominio no se fija: lo que asegura que es Slowtown es la firma.
     */
    private fun open(url: String, accept: String, userAgent: String): HttpURLConnection {
        var current = URL(url)
        repeat(MAX_REDIRECTS + 1) {
            check(current.protocol == "https") { "La dirección no es https" }
            val connection = current.openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.setRequestProperty("Accept", accept)
            // La API de GitHub rechaza los pedidos sin User-Agent.
            connection.setRequestProperty("User-Agent", userAgent)
            val code = connection.responseCode
            if (code == HttpURLConnection.HTTP_OK) return connection
            val location = connection.getHeaderField("Location")
            connection.disconnect()
            check(code in 300..399 && location != null) { "GitHub contestó $code" }
            current = URL(current, location)
        }
        error("Demasiadas redirecciones")
    }
}
