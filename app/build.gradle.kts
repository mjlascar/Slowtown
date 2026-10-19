plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

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

    buildTypes {
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
