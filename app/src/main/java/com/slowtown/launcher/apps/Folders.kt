package com.slowtown.launcher.apps

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Una carpeta de la lista de todas las apps: una categoría que arma el
 * usuario (billeteras, inversiones, buscadores). Guarda componentes, igual
 * que Inicio. Es sólo orden: no afloja nada, así que no pide contraseña.
 */
data class Folder(val id: String, val name: String, val apps: List<String>)

/** Operaciones sobre la lista de carpetas. Una app está en una carpeta como mucho. */
object Folders {

    fun parse(json: String?): List<Folder> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(json)
            (0 until array.length()).map { i ->
                val item = array.getJSONObject(i)
                val apps = item.getJSONArray("apps")
                Folder(
                    id = item.getString("id"),
                    name = item.getString("name"),
                    apps = (0 until apps.length()).map(apps::getString),
                )
            }
        }.getOrDefault(emptyList())
    }

    fun serialize(folders: List<Folder>): String = JSONArray().apply {
        folders.forEach { folder ->
            put(
                JSONObject()
                    .put("id", folder.id)
                    .put("name", folder.name)
                    .put("apps", JSONArray(folder.apps)),
            )
        }
    }.toString()

    /** Crea una carpeta; si se pasa una app, la mueve adentro. */
    fun create(folders: List<Folder>, name: String, app: String? = null): List<Folder> {
        val folder = Folder(UUID.randomUUID().toString(), name.trim(), emptyList())
        val withFolder = folders + folder
        return if (app == null) withFolder else move(withFolder, app, folder.id)
    }

    fun rename(folders: List<Folder>, id: String, name: String): List<Folder> =
        folders.map { if (it.id == id) it.copy(name = name.trim()) else it }

    /** Borra la carpeta; sus apps vuelven a la lista. */
    fun delete(folders: List<Folder>, id: String): List<Folder> = folders.filterNot { it.id == id }

    /** Saca la app de donde esté y, si [to] no es null, la pone en esa carpeta. */
    fun move(folders: List<Folder>, app: String, to: String?): List<Folder> =
        folders.map { folder ->
            val without = folder.apps - app
            if (folder.id == to) folder.copy(apps = without + app) else folder.copy(apps = without)
        }

    fun folderOf(folders: List<Folder>, app: String): Folder? = folders.firstOrNull { app in it.apps }
}
