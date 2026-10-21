package com.slowtown.launcher.admin

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.UserManager
import com.slowtown.launcher.HomeActivity

/**
 * Todo lo que Slowtown hace como Device Owner pasa por acá. Sin Device Owner
 * nada de esto tiene efecto y [isDeviceOwner] da false.
 *
 * La regla que no se rompe: Ajustes y el launcher de fábrica nunca se
 * deshabilitan, se esconden ni se suspenden. En la tablet del depósito eso
 * terminó en un boot loop y un factory reset, porque Ajustes trae la pantalla
 * de inicio de respaldo que usa el sistema al arrancar. Quedan fuera de
 * alcance sólo por no estar en la lista de lock task.
 */
object Policies {

    /**
     * Lo que queda con Slowtown activo: Inicio (que es Slowtown), el panel
     * de notificaciones sin los ajustes rápidos (sin engranaje no hay camino a
     * Ajustes), la barra de estado, la pantalla de bloqueo (sin KEYGUARD no hay
     * bloqueo aunque haya PIN) y el menú de apagado. Recientes queda afuera.
     * BLOCK_ACTIVITY_START_IN_TASK cierra la otra puerta: sin él, una app
     * permitida puede abrir dentro de su tarea pantallas de paquetes que no
     * están en la lista, Ajustes incluido.
     */
    private const val FEATURES =
        DevicePolicyManager.LOCK_TASK_FEATURE_HOME or
            DevicePolicyManager.LOCK_TASK_FEATURE_NOTIFICATIONS or
            DevicePolicyManager.LOCK_TASK_FEATURE_SYSTEM_INFO or
            DevicePolicyManager.LOCK_TASK_FEATURE_KEYGUARD or
            DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS or
            DevicePolicyManager.LOCK_TASK_FEATURE_BLOCK_ACTIVITY_START_IN_TASK

    /**
     * Un usuario invitado arranca sin ninguna de estas reglas (Device Owner
     * por adb no las pone solo). Y la fecha y la hora no se cambian a mano:
     * con ellas se cuentan las veces de «Habilitar todo»; la hora automática
     * de la red sigue andando.
     */
    private val RESTRICTIONS = listOf(
        UserManager.DISALLOW_ADD_USER,
        UserManager.DISALLOW_USER_SWITCH,
        UserManager.DISALLOW_CONFIG_DATE_TIME,
    )

    fun admin(context: Context) = ComponentName(context, SlowtownAdminReceiver::class.java)

    private fun dpm(context: Context): DevicePolicyManager =
        context.getSystemService(DevicePolicyManager::class.java)

    fun isDeviceOwner(context: Context): Boolean = dpm(context).isDeviceOwnerApp(context.packageName)

    /**
     * Lo que Device Owner cambia del teléfono sin que nadie lo pida, devuelto
     * a como estaba: Android apaga la copia de seguridad (Google One deja de
     * guardar apps, SMS y ajustes) y muestra «pertenece a tu organización».
     * Corre en cada arranque porque es una consulta barata.
     */
    @Synchronized
    fun restoreDefaults(context: Context) {
        if (!isDeviceOwner(context)) return
        val dpm = dpm(context)
        val admin = admin(context)
        if (!dpm.isBackupServiceEnabled(admin)) dpm.setBackupServiceEnabled(admin, true)
        // Sin leerlo antes: en Android 12 getOrganizationName sólo anda dentro
        // de un perfil de trabajo, y siendo Device Owner tira SecurityException.
        dpm.setOrganizationName(admin, ORGANIZATION)
    }

    private const val ORGANIZATION = "Slowtown"

    /**
     * Si `startLockTask()` cierra el teléfono sin peligro: Slowtown está en la
     * lista (si no, mostraría el cartel de fijar pantalla) y las funciones ya
     * están puestas (sin KEYGUARD, entrar en lock task saca el PIN).
     */
    fun readyToLock(context: Context): Boolean {
        val dpm = dpm(context)
        return dpm.isLockTaskPermitted(context.packageName) &&
            dpm.getLockTaskFeatures(admin(context)) == FEATURES
    }

    /**
     * Arma el modo cerrado: funciones, lista, Inicio fijo y restricciones.
     * Va sólo cuando algo cambia (cerrar, elegir apps, salir del modo a prueba
     * de fallos, fin de una sesión de administración) y una vez por arranque,
     * nunca en cada vuelta a Inicio: el sistema no deduplica el Inicio fijo, y
     * cada llamada sumaba una regla más en disco (en el emulador llegó a 30).
     */
    @Synchronized
    fun apply(context: Context, packages: Collection<String>) {
        if (!isDeviceOwner(context)) return
        val dpm = dpm(context)
        val admin = admin(context)
        // Primero las funciones y después la lista: si la lista habilita a
        // Slowtown antes de que esté KEYGUARD, el sistema puede entrar en lock
        // task sin pantalla de bloqueo y sacar el PIN (le pasó a otro proyecto
        // con este mismo diseño).
        if (dpm.getLockTaskFeatures(admin) != FEATURES) dpm.setLockTaskFeatures(admin, FEATURES)
        setPackages(context, effectivePackages(context, packages))
        dpm.clearPackagePersistentPreferredActivities(admin, context.packageName)
        dpm.addPersistentPreferredActivity(
            admin,
            homeFilter(),
            ComponentName(context, HomeActivity::class.java),
        )
        val current = dpm.getUserRestrictions(admin)
        RESTRICTIONS.filterNot(current::getBoolean).forEach { dpm.addUserRestriction(admin, it) }
    }

    /**
     * Sólo la lista, cuando cambia algo que depende de la hora (empieza o
     * termina la noche, o una ráfaga): escribe sólo si cambió, así que se
     * puede llamar en cada vuelta a Inicio.
     */
    @Synchronized
    fun refreshPackages(context: Context, packages: Collection<String>) {
        if (!isDeviceOwner(context)) return
        setPackages(context, effectivePackages(context, packages))
    }

    /**
     * La lista que corresponde ahora. Con una sesión en curso (administración
     * o «Habilitar todo»), todo lo instalado: rearmar la política no la puede
     * cortar antes. De noche, sin ráfaga, sólo lo de siempre ([allowlist]
     * suma el reloj y las alarmas), sin las apps permitidas.
     */
    private fun effectivePackages(context: Context, packages: Collection<String>): Set<String> {
        val session = AdminSession.isActive(context) || FreeSession.isActive(context)
        val allowed = allowlist(context, if (Bedtime.restricts(context)) emptySet() else packages)
        return if (session) allowed + installed(context) else allowed
    }

    /**
     * Sesión de administración: se puede abrir cualquier cosa sin salir de
     * lock task. Salir cerraría las demás tareas (Maps navegando, una llamada
     * de WhatsApp); agrandar la lista no cierra nada. Al terminar, [apply]
     * vuelve a la lista de siempre y el sistema cierra lo que quedó afuera.
     */
    @Synchronized
    fun openForAdmin(context: Context, packages: Collection<String>) {
        if (!isDeviceOwner(context)) return
        setPackages(context, allowlist(context, packages) + installed(context))
    }

    // La variante con PackageInfoFlags recién existe en API 33.
    @Suppress("DEPRECATION")
    private fun installed(context: Context): List<String> =
        context.packageManager.getInstalledPackages(0).map { it.packageName }

    /**
     * Vacía la lista de lock task. Si el teléfono estaba cerrado, se abre (el
     * sistema saca del modo a cualquier tarea que ya no esté en la lista, y la
     * cierra). Vuelven los usuarios y el cambio de usuario.
     */
    @Synchronized
    fun open(context: Context) {
        if (!isDeviceOwner(context)) return
        val dpm = dpm(context)
        val admin = admin(context)
        setPackages(context, emptySet())
        RESTRICTIONS.forEach { dpm.clearUserRestriction(admin, it) }
    }

    /** Lo que hace la guardia de crashes: abrir y soltar el Inicio fijo, sin dejar de ser Device Owner. */
    @Synchronized
    fun relax(context: Context) {
        if (!isDeviceOwner(context)) return
        open(context)
        dpm(context).clearPackagePersistentPreferredActivities(admin(context), context.packageName)
    }

    /**
     * Deja de ser Device Owner. Sólo lo pide el cable ([CableProvider], desde
     * `scripts/liberar.sh`), después de deshacer el nivel 2: desde la
     * administración no, porque la contraseña está en un cajón de la casa.
     * Para volver hay que provisionar otra vez, sin cuentas en el teléfono.
     */
    // Obsoleto desde API 26, pero sigue siendo la única forma de que el propio
    // Device Owner se suelte, y anda de Android 12 a 17.
    @Suppress("DEPRECATION")
    @Synchronized
    fun release(context: Context) {
        if (!isDeviceOwner(context)) return
        relax(context)
        dpm(context).clearDeviceOwnerApp(context.packageName)
    }

    /**
     * Slowtown, las apps de Inicio y lo que no puede faltar: lo esencial y el
     * reloj y las alarmas, de día y de noche (la alarma de la mañana tiene que
     * poder mostrar su pantalla con Slowtown activo).
     */
    private fun allowlist(context: Context, packages: Collection<String>): Set<String> = buildSet {
        add(context.packageName)
        addAll(packages)
        addAll(Essentials.packages(context))
        addAll(Bedtime.apps(context))
    }

    /** Cada escritura guarda a disco y avisa a todo el sistema: sólo si cambió. */
    private fun setPackages(context: Context, packages: Set<String>) {
        val dpm = dpm(context)
        val admin = admin(context)
        if (dpm.getLockTaskPackages(admin).toSet() != packages) {
            dpm.setLockTaskPackages(admin, packages.toTypedArray())
        }
    }

    private fun homeFilter() = IntentFilter(Intent.ACTION_MAIN).apply {
        addCategory(Intent.CATEGORY_HOME)
        addCategory(Intent.CATEGORY_DEFAULT)
    }
}
