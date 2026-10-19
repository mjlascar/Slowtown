import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

/**
 * La clave estable (`scripts/firma.sh`), fuera del repo, que es público. Un
 * Device Owner no se desinstala: con otra firma la versión nueva no se instala
 * encima, y hay que liberar, desinstalar y provisionar de nuevo, sin cuentas en
 * el teléfono. Sin el archivo (CI, otra compu) la build sale con la clave de
 * debug de la máquina: sirve para el emulador, y `provisionar.sh` no la deja
 * llegar a un teléfono real.
 */
val firma: Properties? = File(
    System.getenv("SLOWTOWN_FIRMA") ?: "${System.getProperty("user.home")}/slowtown-firma",
    "firma.properties",
).takeIf { it.isFile }?.let { archivo -> Properties().apply { archivo.inputStream().use { load(it) } } }

android {
    namespace = "com.slowtown.launcher"
    compileSdk = 37

    defaultConfig {
        // No cambia nunca: Android identifica al Device Owner por este nombre
        // (y por la firma). Cambiarlo es liberar, desinstalar y provisionar de
        // nuevo, sin cuentas en el teléfono.
        applicationId = "com.slowtown.launcher"
        // El S10e quedó en Android 12. Con 31 de piso no hacen falta ramas por
        // versión en las APIs de Device Owner.
        minSdk = 31
        targetSdk = 37
        // En CI es el número de corrida, para que cada build instale encima
        // de la anterior.
        versionCode = System.getenv("SLOWTOWN_VERSION_CODE")?.toIntOrNull() ?: 1
        versionName = "0.1.0"
    }

    signingConfigs {
        if (firma != null) {
            create("estable") {
                storeFile = file(firma.getProperty("storeFile"))
                storePassword = firma.getProperty("storePassword")
                keyAlias = firma.getProperty("keyAlias")
                keyPassword = firma.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        // La que va al teléfono: testOnly, para que liberar.sh la pueda sacar.
        debug {
            if (firma != null) signingConfig = signingConfigs.getByName("estable")
        }
        release {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
}
