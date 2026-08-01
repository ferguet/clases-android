plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "es.guiamayores.clases"
    compileSdk = 34

    defaultConfig {
        applicationId = "es.guiamayores.clases"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
    }

    // Misma llave fija que las otras dos apps, y por el mismo motivo: sin
    // ella, cada compilacion en GitHub firma distinto y Android se niega a
    // instalar la nueva version encima de la vieja.
    signingConfigs {
        create("fija") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        getByName("debug") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("fija")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Cliente HTTP para mandar el audio al servidor y esperar la
    // transcripcion. OkHttp, no la libreria de red mas pesada de Google,
    // porque aqui solo hace falta una peticion POST con un fichero.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
