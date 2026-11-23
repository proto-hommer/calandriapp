import com.google.protobuf.gradle.proto
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.protobuf)
}

android {
    namespace = "io.github.protohommer.meshpaper"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "io.github.protohommer.calandriapp"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Firma de publicacion: la clave vive FUERA del repo (por defecto en ~/calandriapp-firma,
    // o donde diga la variable CALANDRIAPP_FIRMA). Sin ese archivo, el release sale sin firmar.
    val firma = Properties().apply {
        val f = file(System.getenv("CALANDRIAPP_FIRMA") ?: "${System.getProperty("user.home")}/calandriapp-firma/keystore.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }
    signingConfigs {
        if (firma.getProperty("storeFile") != null) create("release") {
            storeFile = file(firma.getProperty("storeFile"))
            storePassword = firma.getProperty("storePassword")
            keyAlias = firma.getProperty("keyAlias")
            keyPassword = firma.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
            optimization {
                // Apagada: con la optimizacion de R8 el release se cerraba al abrir
                // (IllegalAccessError en clases de Kotlin reempaquetadas). Probado en un moto e22.
                enable = false
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
    sourceSets {
        getByName("main") {
            // Submódulo meshtastic/protobufs (fijado al commit que usa el firmware del nodo)
            proto { srcDir("../protobufs") }
        }
    }
}

protobuf {
    protoc { artifact = libs.protoc.get().toString() }
    generateProtoTasks {
        all().forEach { task ->
            task.builtins {
                create("java") { option("lite") }
                create("kotlin") { option("lite") }
            }
        }
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.protobuf.kotlin.lite)
    implementation(libs.zxing.core)
    implementation(libs.zxing.embedded) { isTransitive = false } // solo la pantalla de escaneo; core va arriba
    implementation(libs.osmdroid.android)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}